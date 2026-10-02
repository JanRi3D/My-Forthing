package me.ri3d.dashcam.media

import androidx.lifecycle.SavedStateHandle
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import me.ri3d.dashcam.core.ui.UiState
import me.ri3d.dashcam.dashcam.managerFor
import me.ri3d.dashcam.enhance.EnhanceEngine
import me.ri3d.dashcam.enhance.EnhancedKind
import me.ri3d.dashcam.enhance.EnhancementInfo
import me.ri3d.dashcam.recorder.RecorderSimulator
import java.io.File
import java.util.UUID

/** [SIM] Clip: explicit delete targets that never cascade, and the enhancement sidecar of derived items. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ClipViewModelTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db = memoryDb(context)
    private val sim = RecorderSimulator()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
        listOf("media", "enhance", "thumbs").forEach { File(context.filesDir, it).deleteRecursively() }
    }

    @Test
    fun `phone and recorder copies are deleted one at a time`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val manager = managerFor(sim).apply { setSimulator(true) }
        manager.connect()
        val repository = MediaRepository(context, db, manager)
        val http = RecorderHttp(manager, "http://127.0.0.1:1", context)
        repository.upsertFromRecorderListing(0, listOf(recorderFile("/sim/a.mp4")))
        val id = db.mediaDao().byRecorderPath("/sim/a.mp4")!!.id
        val file = File(repository.mediaDir, "$id/a.mp4").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(10)) }
        repository.markDownloaded(id, file)
        val vm = ClipViewModel(SavedStateHandle(mapOf("mediaId" to id)), repository, DownloadQueue(context, repository, MediaDownloader(repository, http), manager), manager)
        backgroundScope.launch { vm.item.collect {} }

        vm.deleteOnRecorder()
        assertThat(vm.notices.first()).isEqualTo(MediaNotice.DeletedOnRecorder(1))
        eventually { (vm.item.value as? UiState.Ready)?.data?.recorderPath == null && vm.item.value is UiState.Ready }
        assertThat(file.isFile).isTrue()
        assertThat(sim.received.count { it.msgId == 4101 }).isEqualTo(1)

        vm.deleteLocal()
        assertThat(vm.notices.first()).isEqualTo(MediaNotice.DeletedLocal(1))
        eventually { vm.item.value == UiState.Empty } // last copy gone: the item is gone
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `a derived item shows its sidecar and its original`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val manager = managerFor(sim)
        val repository = MediaRepository(context, db, manager)
        val http = RecorderHttp(manager, "http://127.0.0.1:1", context)
        repository.upsertFromRecorderListing(1, listOf(recorderFile("/sim/e.mp4")))
        val parent = db.mediaDao().byRecorderPath("/sim/e.mp4")!!
        val output = File(context.filesDir, "enhance/${UUID.randomUUID()}.mp4").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(10)) }
        val info = EnhancementInfo(EnhancedKind.UPSCALED_CLIP, EnhanceEngine.CLASSICAL, null, 1.5f, parent.id, null, 5_000)
        EnhancementInfo.sidecarOf(output).writeText(info.toJson())
        val derived = repository.registerDerived(MediaKind.UPSCALED_CLIP, output, parent.id, null, info)

        val vm = ClipViewModel(SavedStateHandle(mapOf("mediaId" to derived.id)), repository, DownloadQueue(context, repository, MediaDownloader(repository, http), manager), manager)
        backgroundScope.launch { vm.enhancement.collect {} }
        backgroundScope.launch { vm.parent.collect {} }
        eventually { vm.enhancement.value != null && vm.parent.value != null }

        assertThat(vm.enhancement.value!!.run { sourceMediaId to engine }).isEqualTo(parent.id to EnhanceEngine.CLASSICAL)
        assertThat(vm.parent.value!!.id).isEqualTo(parent.id)

        val parentVm = ClipViewModel(SavedStateHandle(mapOf("mediaId" to parent.id)), repository, DownloadQueue(context, repository, MediaDownloader(repository, http), manager), manager)
        backgroundScope.launch { parentVm.children.collect {} }
        eventually { parentVm.children.value.isNotEmpty() }
        assertThat(parentVm.children.value.single().id).isEqualTo(derived.id)
    }
}
