package me.ri3d.cam.backup

import android.content.Context
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import me.ri3d.cam.R
import me.ri3d.cam.core.model.BackupMode
import me.ri3d.cam.core.ui.UiState
import me.ri3d.cam.core.ui.UiText
import me.ri3d.cam.drive.DRIVE_FILE_SCOPE
import me.ri3d.cam.drive.DriveAuthState
import me.ri3d.cam.drive.DriveError
import me.ri3d.cam.drive.DriveQuota
import me.ri3d.cam.media.BackupState
import me.ri3d.cam.media.eventually
import me.ri3d.cam.media.memoryDb
import me.ri3d.cam.media.recorderFile
import java.io.IOException

/** [BackupQueue] with WorkManager's test driver, the automatic rules observer and [BackupViewModel]. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33]) // network-request constraints (API 28+)
class BackupQueueTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val db = memoryDb(context)
    private lateinit var f: BackupFixture
    private lateinit var queue: BackupQueue
    private val workManager get() = WorkManager.getInstance(context)
    private val factory = object : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
            BackupWorker(appContext, workerParameters, f.backup, queue)
    }

    @Before
    fun setUp() {
        val config = Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor())
            .setTaskExecutor(SynchronousExecutor()).setWorkerFactory(factory).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        f.clear()
        db.close()
    }

    private fun TestScope.setup(): BackupFixture {
        f = BackupFixture(context, db, this, tmp.newFile("prefs.preferences_pb").apply { delete() })
        queue = BackupQueue(context, f.repository, f.preferences, f.auth, f.backup, f.store)
        return f
    }

    private fun state(id: String) = runBlocking { f.item(id)?.backupState }

    private fun works() = workManager.getWorkInfosByTag(BackupQueue.TAG).get()

    private fun unfinished() = works().filter { !it.state.isFinished }

    /** The network is there: runs whatever waits for its constraints. */
    private fun networkUp() = works().filter { it.state == WorkInfo.State.ENQUEUED }.forEach {
        runCatching { WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(it.id) }
    }

    @Test
    fun `manual backup uploads phone copies one at a time until all are DONE`() = runTest {
        setup()
        val a = f.local("/sim/EVENT/a.mp4")
        val b = f.local("/sim/NORMAL/b.mp4", type = 0)
        f.repository.upsertFromRecorderListing(1, listOf(recorderFile("/sim/EVENT/remote.mp4")))
        val remote = db.mediaDao().byRecorderPath("/sim/EVENT/remote.mp4")!!

        assertThat(queue.enqueue(listOf(a.id, b.id, remote.id))).isEqualTo(EnqueueResult(queued = 2, notOnPhone = 1, alreadyDone = 0))

        val first = unfinished().single() // one upload at a time
        assertThat(first.constraints.requiredNetworkRequest!!.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).isTrue() // default
        eventually {
            networkUp()
            state(a.id) == BackupState.DONE && state(b.id) == BackupState.DONE
        }
        assertThat(state(remote.id)).isEqualTo(BackupState.NONE) // nothing is streamed from the recorder
        assertThat(f.api.sessions).hasSize(2)
        assertThat(queue.enqueue(listOf(a.id))).isEqualTo(EnqueueResult(queued = 0, notOnPhone = 0, alreadyDone = 1))
    }

    @Test
    fun `an offline upload waits for its retry and keeps the single slot`() = runTest {
        setup()
        val a = f.local("/sim/EVENT/a.mp4")
        val b = f.local("/sim/EVENT/b.mp4")
        f.api.uploadFailures += { DriveError.Offline(IOException("no route")) }
        queue.enqueue(listOf(a.id, b.id))
        val running = unfinished().single()

        networkUp()
        eventually { workManager.getWorkInfoById(running.id).get()!!.runAttemptCount == 1 && state(running.tags.idOf()) == BackupState.QUEUED }

        assertThat(unfinished().map { it.id }).containsExactly(running.id) // the other item has no work yet
        eventually { queue.progress.value[running.tags.idOf()]?.running == false }
    }

    @Test
    fun `the automatic rules queue items once they are on the phone`() = runTest {
        setup()
        f.preferences.update { it.copy(backupMode = BackupMode.INCIDENTS) }
        backgroundScope.launch { queue.observe() }
        val incident = f.local("/sim/EVENT/e.mp4", type = 1)
        val loop = f.local("/sim/NORMAL/n.mp4", type = 0)
        f.repository.upsertFromRecorderListing(1, listOf(recorderFile("/sim/EVENT/remote.mp4")))
        val remote = db.mediaDao().byRecorderPath("/sim/EVENT/remote.mp4")!!

        eventually { state(incident.id) == BackupState.QUEUED }
        assertThat(state(loop.id)).isEqualTo(BackupState.NONE)
        assertThat(state(remote.id)).isEqualTo(BackupState.NONE)

        f.preferences.update { it.copy(backupMode = BackupMode.ALL) }
        eventually { state(loop.id) == BackupState.QUEUED }
        assertThat(state(remote.id)).isEqualTo(BackupState.NONE)
        assertThat(unfinished()).hasSize(1)
    }

    @Test
    fun `a cancelled item is not queued again by the rules until it is backed up manually`() = runTest {
        setup()
        f.preferences.update { it.copy(backupMode = BackupMode.ALL) }
        backgroundScope.launch { queue.observe() }
        val item = f.local("/sim/EVENT/e.mp4")
        eventually { state(item.id) == BackupState.QUEUED && unfinished().isNotEmpty() }

        queue.cancel(item.id)

        eventually { state(item.id) == BackupState.NONE && unfinished().isEmpty() }
        settle() // the observer sees the change
        assertThat(state(item.id)).isEqualTo(BackupState.NONE)
        assertThat(f.store.isExcluded(item.id)).isTrue()

        assertThat(queue.enqueue(listOf(item.id)).queued).isEqualTo(1)
        assertThat(f.store.isExcluded(item.id)).isFalse()
        eventually {
            networkUp()
            state(item.id) == BackupState.DONE
        }
    }

    @Test
    fun `changed conditions apply to the waiting upload`() = runTest {
        setup()
        backgroundScope.launch { queue.observe() }
        val item = f.local("/sim/EVENT/e.mp4")
        queue.enqueue(listOf(item.id))
        assertThat(unfinished().single().constraints.requiredNetworkRequest).isNotNull()

        f.preferences.update { it.copy(backupRequireInternetWifi = false, backupOnMobileData = true) }

        eventually { unfinished().singleOrNull()?.constraints?.requiredNetworkType == NetworkType.CONNECTED }
        assertThat(unfinished().single().constraints.requiredNetworkRequest).isNull()
    }

    @Test
    fun `nothing is scheduled while Drive is not connected, needs a reconnect or is full`() = runTest {
        setup()
        val item = f.local("/sim/EVENT/e.mp4")
        f.auth.state.value = DriveAuthState.NotConnected
        queue.enqueue(listOf(item.id))
        assertThat(works()).isEmpty()
        f.auth.state.value = DriveAuthState.NeedsReconnect("abgelaufen")
        queue.schedule()
        assertThat(works()).isEmpty()
        f.auth.state.value = DriveAuthState.Connected("a@example.com", setOf(DRIVE_FILE_SCOPE))
        f.store.setStorageFull(true)
        queue.schedule()
        assertThat(works()).isEmpty()

        queue.resume()
        assertThat(unfinished()).hasSize(1)
        assertThat(state(item.id)).isEqualTo(BackupState.QUEUED)
    }

    @Test
    fun `an account switch seen by the observer cancels the upload and resets every state`() = runTest {
        setup()
        backgroundScope.launch { queue.observe() }
        val done = f.local("/sim/EVENT/d.mp4")
        val waiting = f.local("/sim/EVENT/w.mp4")
        queue.enqueue(listOf(done.id))
        eventually {
            networkUp()
            state(done.id) == BackupState.DONE
        }
        queue.enqueue(listOf(waiting.id))
        eventually { unfinished().isNotEmpty() }

        f.auth.state.value = DriveAuthState.Connected("other@example.com", setOf(DRIVE_FILE_SCOPE))

        eventually { state(done.id) == BackupState.NONE && state(waiting.id) == BackupState.NONE && unfinished().isEmpty() }
        assertThat(runBlocking { f.item(done.id)!!.driveFileId }).isNull()
        assertThat(f.backup.adoptAccount("other@example.com")).isFalse() // already adopted
    }

    @Test
    fun `the view model shows the queue, saves the conditions and deletes Drive copies only`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        setup()
        val vm = BackupViewModel(queue, f.preferences, f.api, f.auth, f.repository)
        backgroundScope.launch { vm.queue.collect {} }
        backgroundScope.launch { vm.preferences.collect {} }
        val done = f.queued("/sim/EVENT/d.mp4")
        f.backup.upload(done.id)
        val failed = f.local("/sim/EVENT/f.mp4").let { item ->
            f.repository.update(item.id) { it.copy(backupState = BackupState.FAILED, backupError = "HTTP:403:accessNotConfigured") }!!
        }

        eventually { vm.queue.value.items.map { it.id } == listOf(failed.id) && vm.queue.value.done == 1 }
        assertThat(backupErrorText(failed.backupError)).isEqualTo(UiText.Res(R.string.drive_error_api_disabled))

        vm.setMode(BackupMode.ALL)
        vm.update { it.copy(backupIncludePlateMetadata = true) }
        eventually { vm.preferences.value.run { backupMode == BackupMode.ALL && backupIncludePlateMetadata } }

        vm.loadQuota()
        eventually { vm.quota.value == UiState.Ready(DriveQuota(100, 100)) }

        assertThat(vm.deleteOnDrive(done.id).isSuccess).isTrue()
        val kept = runBlocking { f.item(done.id)!! }
        assertThat(kept.backupState).isEqualTo(BackupState.NONE)
        assertThat(kept.localFile!!.isFile).isTrue()
        assertThat(kept.recorderPath).isNotNull()
    }

    /** Lets Room, WorkManager and the observer run for a moment (real time). */
    private fun TestScope.settle() = repeat(20) {
        testScheduler.runCurrent()
        ShadowLooper.idleMainLooper()
        Thread.sleep(10)
    }

    private fun Set<String>.idOf() = first { it.startsWith("backup-id:") }.removePrefix("backup-id:")
}
