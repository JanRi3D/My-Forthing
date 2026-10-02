package me.ri3d.dashcam.media

import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import me.ri3d.dashcam.dashcam.RecorderConnectionManagerImpl
import me.ri3d.dashcam.backup.FakeDriveApi
import me.ri3d.dashcam.dashcam.managerFor
import me.ri3d.dashcam.recorder.RecorderReply
import me.ri3d.dashcam.recorder.RecorderSimulator
import me.ri3d.dashcam.recorder.SimulatedFiles
import java.io.File
import java.time.LocalDateTime
import java.util.UUID

/** [SIM] Recordings against the real connection manager in simulator mode. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecordingsViewModelTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db = memoryDb(context)
    private val files = SimulatedFiles(
        SimulatedFiles.series(0, "normal", "N", ".mp4", 3, LocalDateTime.of(2026, 10, 1, 1, 0), 60, 1000) +
            SimulatedFiles.series(1, "event", "E", ".mp4", 2, LocalDateTime.of(2026, 9, 30, 9, 0), 60, 1000),
    )
    private val sim = RecorderSimulator().apply {
        handlers[4100] = files::listReply
        handlers[4101] = files::deleteReply
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
        listOf("media", "screenshots", "thumbs").forEach { File(context.filesDir, it).deleteRecursively() }
        File(context.cacheDir, MediaModule.THUMB_CACHE_DIR).deleteRecursively()
    }

    private fun TestScope.viewModel(manager: RecorderConnectionManagerImpl): RecordingsViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = MediaRepository(context, db, manager)
        val http = RecorderHttp(manager, "http://127.0.0.1:1", context)
        val downloads = DownloadQueue(context, repository, MediaDownloader(repository, http), manager)
        val driveDownloads = DriveDownloadQueue(context, repository, MediaDownloader(repository, http), FakeDriveApi(), testPreferences())
        return RecordingsViewModel(manager, repository, downloads, http, ThumbnailPrefetcher(context, http, manager, downloads, repository), driveDownloads)
    }

    /** The tab's entries once the library answered (the screen collects them the same way). */
    private fun TestScope.entries(vm: RecordingsViewModel, type: Int): List<RecorderEntry> {
        backgroundScope.launch { vm.entries(type).collect {} }
        eventually { vm.entries(type).value != null }
        return vm.entries(type).value!!
    }

    private fun paths(vm: RecordingsViewModel, type: Int) = vm.entries(type).value.orEmpty().map { it.item.recorderPath }

    private fun listRequests() = sim.received.count { it.msgId == 4100 }

    private fun notify(type: String, info: String) = sim.inject("""{"msgId":16384,"param":{"type":"$type","info":$info}}""")

    @Test
    fun `the shown tab is listed once connected, newest first, and stays offline`() = runTest {
        val manager = managerFor(sim).apply { setSimulator(true) }
        val vm = viewModel(manager)
        vm.show(RecordingsTab.EVENT)
        runCurrent()
        assertThat(listRequests()).isEqualTo(0) // not connected: nothing is requested
        assertThat(entries(vm, 1)).isEmpty()

        manager.connect()
        eventually { vm.browser(1).value.listing.end == ListingEnd.COMPLETE }
        eventually { paths(vm, 1).size == 2 }
        assertThat(paths(vm, 1)).isEqualTo(files.entries(1).map { it.fileName }) // newest recorder time first
        assertThat(vm.entries(1).value!!.all { it.item.category == MediaCategory.EVENT }).isTrue()
        assertThat(vm.browser(0).value.started).isFalse() // other tabs wait until shown

        vm.show(RecordingsTab.NORMAL)
        eventually { vm.browser(0).value.listing.end == ListingEnd.COMPLETE }
        vm.show(RecordingsTab.EVENT) // already listed this session: no new request
        runCurrent()
        assertThat(listRequests()).isEqualTo(2)

        manager.disconnect()
        eventually { !vm.browser(1).value.started }
        assertThat(paths(vm, 1)).hasSize(2) // the list stays, from the library
        assertThat(vm.entries(1).value!!.all { (it.thumb as RecorderThumb).network.not() }).isTrue() // cache only
    }

    @Test
    fun `cached rows show at once and a refresh merges into them`() = runTest {
        val repository = MediaRepository(context, db, managerFor(sim))
        val known = files.entries(0).drop(1).map { recorderFile(it.fileName, it.fileTime) } // the newest is not known yet
        repository.upsertFromRecorderListing(0, known + recorderFile("/sim/gone.mp4", "2026-09-01 00:00:00"))
        val ids = db.mediaDao().recorderType(0).associate { it.recorderPath to it.id }
        val manager = managerFor(sim).apply { setSimulator(true) }
        val vm = viewModel(manager)
        vm.show(RecordingsTab.NORMAL)

        assertThat(entries(vm, 0).map { it.item.recorderPath }).isEqualTo(known.map { it.fileName } + "/sim/gone.mp4")
        assertThat(listRequests()).isEqualTo(0)
        assertThat(vm.listedAt(0).first()).isNull()

        manager.connect()
        eventually { vm.browser(0).value.listing.end == ListingEnd.COMPLETE }
        eventually { paths(vm, 0) == files.entries(0).map { it.fileName } }
        // Same rows (ids are the list keys), the new one merged in at the top, the stale one reconciled at the total.
        val after = vm.entries(0).value!!.associate { it.item.recorderPath to it.item.id }
        known.forEach { assertThat(after[it.fileName]).isEqualTo(ids[it.fileName]) }
        assertThat(vm.listedAt(0).first()).isNotNull()
    }

    @Test
    fun `offline nothing is listed or reconciled`() = runTest {
        val repository = MediaRepository(context, db, managerFor(sim))
        repository.upsertFromRecorderListing(0, listOf(recorderFile("/sim/old.mp4", "2026-09-01 00:00:00")))
        val vm = viewModel(managerFor(sim)) // never connected
        vm.show(RecordingsTab.NORMAL)
        vm.refresh(0)
        runCurrent()

        assertThat(entries(vm, 0).map { it.item.recorderPath }).containsExactly("/sim/old.mp4")
        assertThat(listRequests()).isEqualTo(0)
        assertThat(db.mediaDao().byRecorderPath("/sim/old.mp4")).isNotNull()
    }

    @Test
    fun `fileNew goes to the top and fileDel removes the row, without listing again`() = runTest {
        val manager = managerFor(sim).apply { setSimulator(true) }
        val vm = viewModel(manager)
        manager.connect()
        vm.show(RecordingsTab.NORMAL)
        eventually { vm.browser(0).value.listing.end == ListingEnd.COMPLETE }
        entries(vm, 0)
        val gone = files.entries(0).last().fileName

        notify("fileNew", """{"driver":1,"fileType":0,"fileName":"/sim/normal/new.mp4","fileThm":"/sim/normal/new.thm","fileTime":"2026-10-01 01:01:00","pathType":0}""")
        eventually { paths(vm, 0).firstOrNull() == "/sim/normal/new.mp4" }
        assertThat(vm.browser(0).value.listing.files.first().fileName).isEqualTo("/sim/normal/new.mp4")

        files.deleteReply(RecorderReply.parse("""{"msgId":4101,"param":{"fileList":["$gone"]}}""")!!)
        notify("fileDel", """{"driver":1,"fileType":0,"fileName":"$gone","pathType":0}""")
        eventually { gone !in paths(vm, 0) }
        eventually { runBlocking { db.mediaDao().byRecorderPath(gone) } == null }
        assertThat(vm.browser(0).value.listing.files.map { it.fileName }).doesNotContain(gone)
        assertThat(listRequests()).isEqualTo(1)

        notify("fileNew", """{"driver":1,"fileType":7,"fileName":"/x.mp4","pathType":0}""") // unknown type: list again
        eventually { listRequests() == 2 && vm.browser(0).value.listing.end != null }
    }

    @Test
    fun `deleting on the recorder removes the files from the listing`() = runTest {
        val manager = managerFor(sim).apply { setSimulator(true) }
        val vm = viewModel(manager)
        manager.connect()
        vm.show(RecordingsTab.NORMAL)
        eventually { vm.browser(0).value.listing.end == ListingEnd.COMPLETE }
        val ids = entries(vm, 0).take(2).map { it.item.id }
        ids.forEach(vm::toggle)
        assertThat(vm.selection.value).containsExactlyElementsIn(ids)

        sim.handlers[4101] = { """{"msgId":4101,"rval":310}""" }
        vm.deleteOnRecorder(vm.selection.value)
        assertThat((vm.notices.first() as MediaNotice.RecorderFailed).error.code).isEqualTo(310)
        assertThat(vm.browser(0).value.listing.files).hasSize(3)
        assertThat(vm.selection.value).isEmpty()

        sim.handlers[4101] = files::deleteReply
        vm.deleteOnRecorder(ids)
        assertThat(vm.notices.first()).isEqualTo(MediaNotice.DeletedOnRecorder(2))
        assertThat(vm.browser(0).value.listing.files.map { it.fileName }).containsExactly(files.entries(0).single().fileName)
        assertThat(ids.map { db.mediaDao().get(it) }).containsExactly(null, null) // no other copy: rows gone

        // Deleted elsewhere (clip screen): the entry disappears without a new listing.
        MediaRepository(context, db, manager).markRecorderDeleted(files.entries(0).single().fileName)
        eventually { vm.entries(0).value!!.isEmpty() }
    }

    @Test
    fun `the phone tab shows screenshots without a recorder`() = runTest {
        val id = UUID.randomUUID().toString()
        File(context.filesDir, "screenshots").apply { mkdirs() }.let { dir ->
            File(dir, "$id.jpg").writeBytes(SimulatedFiles.jpeg(64, 36, "shot"))
            File(dir, "$id.json").writeText("""{"id":"$id","capturedAt":1790848800000,"source":"live","width":64,"height":36}""")
        }
        val vm = viewModel(managerFor(sim))
        backgroundScope.launch { vm.local.collect {} }

        eventually { !vm.local.value.isNullOrEmpty() }
        assertThat(vm.local.value!!.single().run { id to kind }).isEqualTo(id to MediaKind.SCREENSHOT)
        assertThat(listRequests()).isEqualTo(0)
    }
}
