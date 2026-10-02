package me.ri3d.dashcam.media

import android.content.Context
import android.util.Log
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import me.ri3d.dashcam.backup.FakeDriveApi
import me.ri3d.dashcam.backup.md5
import me.ri3d.dashcam.dashcam.managerFor
import me.ri3d.dashcam.recorder.RecorderSimulator
import java.io.File

/** "Vom Drive laden": the content lands where a recorder download would, checked against driveMd5, as WorkManager work. */
@RunWith(RobolectricTestRunner::class)
class DriveDownloadsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db = memoryDb(context)
    private val api = FakeDriveApi()
    private val content = ByteArray(4000) { (it % 13).toByte() }
    private lateinit var queue: DriveDownloadQueue
    private lateinit var repository: MediaRepository
    private lateinit var downloader: MediaDownloader
    private val factory = object : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
            DriveDownloadWorker(appContext, workerParameters, queue)
    }

    @Before
    fun setUp() {
        val config = Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor())
            .setTaskExecutor(SynchronousExecutor()).setWorkerFactory(factory).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        val manager = kotlinx.coroutines.test.TestScope().managerFor(RecorderSimulator())
        repository = MediaRepository(context, db, manager)
        downloader = MediaDownloader(repository, RecorderHttp(manager, "http://127.0.0.1:1", context))
        queue = DriveDownloadQueue(context, repository, downloader, api)
    }

    @After
    fun tearDown() {
        db.close()
        listOf("media", "thumbs").forEach { File(context.filesDir, it).deleteRecursively() }
    }

    /** A Drive-only row (as the import leaves it) whose Drive file holds [driveContent]. */
    private suspend fun driveOnly(driveContent: ByteArray = content, md5: String = md5(content)): MediaItem {
        val item = recorderItem("11111111-2222-4333-8444-555555555555", 1, "/sd/EVENT/e.mp4")
            .copy(recorderPath = null, recorderThumbPath = null, driveFileId = "f1", driveMd5 = md5, backupState = BackupState.DONE)
        db.mediaDao().insert(item)
        api.contents["f1"] = driveContent
        return item
    }

    @Test
    fun `the Drive copy becomes the phone copy where a recorder download would put it`() = runTest {
        val item = driveOnly()

        val done = queue.transfer(item.id)

        val target = downloader.targetFile(item)
        assertThat(target.path).endsWith("media/${item.id}/e.mp4".replace('/', File.separatorChar))
        assertThat(target.readBytes()).isEqualTo(content)
        assertThat(done.localFile).isEqualTo(target)
        assertThat(done.localSizeBytes).isEqualTo(content.size.toLong())
        assertThat(done.driveFileId).isEqualTo("f1") // copies stay independent
        assertThat(DriveDownloadQueue.staged(target).exists()).isFalse()
        assertThat(queue.enqueue(item.id)).isFalse() // on the phone now
    }

    @Test
    fun `content that does not match driveMd5 is discarded with a German reason`() = runTest {
        val item = driveOnly(driveContent = ByteArray(4000) { 1 })

        val error = assertThrows(DownloadException::class.java) { kotlinx.coroutines.runBlocking { queue.transfer(item.id) } }

        assertThat(error.failure).isEqualTo(DownloadFailure.CHECKSUM)
        val target = downloader.targetFile(item)
        assertThat(target.exists()).isFalse()
        assertThat(DriveDownloadQueue.staged(target).exists()).isFalse()
        assertThat(MediaDownloader.partFile(DriveDownloadQueue.staged(target)).exists()).isFalse()
        assertThat(repository.get(item.id)!!.localUri).isNull()
    }

    @Test
    fun `the queue runs the download as work and reports it as a Drive transfer`() = runTest {
        val item = driveOnly()

        assertThat(queue.enqueue(item.id)).isTrue()
        constraintsMet()

        eventually { queue.progress.value[item.id]?.state == TransferState.DONE }
        assertThat(queue.progress.value[item.id]!!.fromDrive).isTrue()
        assertThat(kotlinx.coroutines.runBlocking { repository.get(item.id) }!!.localFile!!.readBytes()).isEqualTo(content)
    }

    @Test
    fun `a file gone from Drive fails for good`() = runTest {
        val item = driveOnly()
        api.contents.clear()

        queue.enqueue(item.id)
        constraintsMet()

        eventually { queue.progress.value[item.id]?.state == TransferState.FAILED }
        assertThat(queue.progress.value[item.id]!!.failure).isEqualTo(DownloadFailure.NOT_ON_DRIVE)
        assertThat(queue.progress.value[item.id]!!.httpCode).isEqualTo(404)
    }

    private fun constraintsMet() = WorkManager.getInstance(context).getWorkInfosByTag(DriveDownloadQueue.TAG).get()
        .filter { it.state == WorkInfo.State.ENQUEUED }
        .forEach { WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(it.id) }
}
