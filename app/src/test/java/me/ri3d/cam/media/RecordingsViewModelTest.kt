package me.ri3d.cam.media

import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import me.ri3d.cam.dashcam.RecorderConnectionManagerImpl
import me.ri3d.cam.dashcam.managerFor
import me.ri3d.cam.recorder.RecorderSimulator
import me.ri3d.cam.recorder.SimulatedFiles
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
    }

    private fun TestScope.viewModel(manager: RecorderConnectionManagerImpl): RecordingsViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = MediaRepository(context, db, manager)
        val http = RecorderHttp(manager, "http://127.0.0.1:1", context)
        return RecordingsViewModel(manager, repository, DownloadQueue(context, repository, MediaDownloader(repository, http), manager), http)
    }

    private fun listRequests() = sim.received.count { it.msgId == 4100 }

    @Test
    fun `the shown tab is listed once connected and cleared on disconnect`() = runTest {
        val manager = managerFor(sim).apply { setSimulator(true) }
        val vm = viewModel(manager)
        vm.show(RecordingsTab.EVENT)
        runCurrent()
        assertThat(listRequests()).isEqualTo(0) // not connected: nothing is requested

        manager.connect()
        eventually { vm.browser(1).value.listing.end == ListingEnd.COMPLETE }
        val entries = vm.entries(1).first()
        assertThat(entries.map { it.file.fileName }).isEqualTo(files.entries(1).map { it.fileName })
        assertThat(entries.all { it.item.category == MediaCategory.EVENT }).isTrue()
        assertThat(vm.browser(0).value.started).isFalse() // other tabs wait until shown

        vm.show(RecordingsTab.NORMAL)
        eventually { vm.browser(0).value.listing.end == ListingEnd.COMPLETE }
        vm.show(RecordingsTab.EVENT) // already listed this session: no new request
        runCurrent()
        assertThat(listRequests()).isEqualTo(2)

        manager.disconnect()
        eventually { !vm.browser(1).value.started }
        assertThat(vm.browser(1).value.listing.files).isEmpty()
    }

    @Test
    fun `fileNew and fileDel list the affected type again`() = runTest {
        val manager = managerFor(sim).apply { setSimulator(true) }
        val vm = viewModel(manager)
        manager.connect()
        vm.show(RecordingsTab.NORMAL)
        eventually { vm.browser(0).value.listing.end == ListingEnd.COMPLETE }
        val gone = files.entries(0).first().fileName

        sim.inject("""{"msgId":16384,"param":{"type":"fileNew","info":{"driver":1,"fileType":0,"fileName":"/n.mp4","fileThm":"/n.jpg","fileTime":"2026-10-01 01:01:00","pathType":0}}}""")
        eventually { listRequests() == 2 && vm.browser(0).value.listing.end != null }

        sim.inject("""{"msgId":16384,"param":{"type":"fileNew","info":{"driver":1,"fileType":1,"fileName":"/e.mp4","pathType":0}}}""")
        runCurrent()
        assertThat(listRequests()).isEqualTo(2) // type 1 was not listed

        files.deleteReply(me.ri3d.cam.recorder.RecorderReply.parse("""{"msgId":4101,"param":{"fileList":["$gone"]}}""")!!)
        sim.inject("""{"msgId":16384,"param":{"type":"fileDel","info":{"driver":1,"fileType":0,"fileName":"$gone","pathType":0}}}""")
        eventually { listRequests() == 3 && vm.browser(0).value.listing.end != null }
        eventually { kotlinx.coroutines.runBlocking { db.mediaDao().byRecorderPath(gone) } == null }
        assertThat(vm.browser(0).value.listing.files.map { it.fileName }).doesNotContain(gone)
    }

    @Test
    fun `deleting on the recorder removes the files from the listing`() = runTest {
        val manager = managerFor(sim).apply { setSimulator(true) }
        val vm = viewModel(manager)
        manager.connect()
        vm.show(RecordingsTab.NORMAL)
        eventually { vm.browser(0).value.listing.end == ListingEnd.COMPLETE }
        val ids = vm.entries(0).first().take(2).map { it.item.id }
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

        // Deleted elsewhere (clip screen): the listed entry disappears without a new listing.
        MediaRepository(context, db, manager).markRecorderDeleted(files.entries(0).single().fileName)
        assertThat(vm.entries(0).first()).isEmpty()
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

        eventually { vm.local.value.isNotEmpty() }
        assertThat(vm.local.value.single().run { id to kind }).isEqualTo(id to MediaKind.SCREENSHOT)
        assertThat(listRequests()).isEqualTo(0)
    }
}
