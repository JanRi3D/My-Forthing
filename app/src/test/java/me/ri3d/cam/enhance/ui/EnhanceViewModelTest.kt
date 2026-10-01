package me.ri3d.cam.enhance.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import me.ri3d.cam.R
import me.ri3d.cam.dashcam.managerFor
import me.ri3d.cam.enhance.EngineCost
import me.ri3d.cam.enhance.EnhanceEngine
import me.ri3d.cam.enhance.EnhanceError
import me.ri3d.cam.enhance.EnhanceException
import me.ri3d.cam.media.MediaItem
import me.ri3d.cam.media.MediaKind
import me.ri3d.cam.media.MediaRepository
import me.ri3d.cam.media.eventually
import me.ri3d.cam.media.memoryDb
import me.ri3d.cam.recorder.RecorderSimulator
import java.io.File

/** Enhance flow with a fake [me.ri3d.cam.enhance.FrameEnhancer]: source frame, options, estimate, fallback, typed errors, cancel. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EnhanceViewModelTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db = memoryDb(context)
    private val enhancer = FakeFrameEnhancer()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
        listOf("media", "enhance", "thumbs").forEach { File(context.filesDir, it).deleteRecursively() }
    }

    private fun photo(): File = File(context.filesDir, "media/p/photo.jpg").apply {
        parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    private class Setup(val vm: EnhanceViewModel, val repository: MediaRepository, val item: MediaItem)

    private suspend fun TestScope.setup(item: MediaItem = localItem(MediaKind.ORIGINAL_PHOTO, photo()), positionMs: Long = 0): Setup {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = MediaRepository(context, db, managerFor(RecorderSimulator()).apply { setSimulator(true) })
        db.mediaDao().insert(item)
        val vm = EnhanceViewModel(SavedStateHandle(mapOf("mediaId" to item.id, "positionMs" to positionMs)), context, repository, enhancer)
        eventually { !vm.state.value.loading }
        return Setup(vm, repository, item)
    }

    // Saving (saveEnhancedFrame → registerDerived) needs the app's resources (EXIF "Software" = app name), which these
    // Robolectric tests run without: it is covered on the emulator (see docs/features/enhance.md, UI → Validation).

    @Test
    fun `a photo is enhanced on the defaults, the original untouched`() = runTest {
        val s = setup()
        val originalBytes = s.item.localFile!!.readBytes()
        val loaded = s.vm.state.value
        assertThat(loaded.sourceWidth to loaded.sourceHeight).isEqualTo(64 to 36)
        assertThat(loaded.positionMs).isNull()
        assertThat(loaded.engine).isEqualTo(EnhanceEngine.ML)
        assertThat(loaded.scale).isEqualTo(4)

        s.vm.enhance()
        eventually { s.vm.state.value.result != null }
        val result = s.vm.state.value.result!!
        assertThat(result.scale).isEqualTo(4)
        assertThat(result.engine).isEqualTo(EnhanceEngine.ML)
        assertThat(result.fallback).isFalse()
        assertThat(result.preview.width).isEqualTo(256)
        assertThat(s.vm.state.value.progress).isNull()
        assertThat(s.item.localFile!!.readBytes()).isEqualTo(originalBytes)
    }

    @Test
    fun `a video frame is taken at the exact position`() = runTest {
        val clip = File(context.filesDir, "media/v/clip.mp4").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(10)) }
        ShadowMediaMetadataRetriever.addFrame(clip.path, 37_000_000L, Bitmap.createBitmap(48, 27, Bitmap.Config.ARGB_8888))
        val s = setup(localItem(MediaKind.ORIGINAL_VIDEO, clip), positionMs = 37_000)
        assertThat(s.vm.state.value.loadError).isNull()
        assertThat(s.vm.state.value.positionMs).isEqualTo(37_000)
        assertThat(s.vm.state.value.sourceWidth to s.vm.state.value.sourceHeight).isEqualTo(48 to 27)
    }

    @Test
    fun `a frame that cannot be decoded is reported`() = runTest {
        val clip = File(context.filesDir, "media/v/broken.mp4").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(10)) }
        val s = setup(localItem(MediaKind.ORIGINAL_VIDEO, clip), positionMs = 1_000)
        assertThat(s.vm.state.value.loadError).isEqualTo(R.string.enhance_error_source)
    }

    @Test
    fun `an ML failure falls back to classical and says so`() = runTest {
        enhancer.engineUsed = EnhanceEngine.CLASSICAL
        val s = setup()
        s.vm.enhance()
        eventually { s.vm.state.value.result != null }
        assertThat(s.vm.state.value.result!!.engine).isEqualTo(EnhanceEngine.CLASSICAL)
        assertThat(s.vm.state.value.result!!.fallback).isTrue()
    }

    @Test
    fun `without the model only classical is offered`() = runTest {
        enhancer.caps = caps(engines = listOf(EnhanceEngine.CLASSICAL))
        val s = setup()
        assertThat(s.vm.state.value.engines).containsExactly(EnhanceEngine.CLASSICAL)
        assertThat(s.vm.state.value.engine).isEqualTo(EnhanceEngine.CLASSICAL)
    }

    @Test
    fun `a scale beyond this phone's memory limit is disabled`() = runTest {
        enhancer.caps = caps(maxOutputPixels = 64L * 36 * 4) // 2× fits, 4× does not
        val s = setup()
        assertThat(s.vm.state.value.scales).containsExactly(4, false, 2, true)
        assertThat(s.vm.state.value.scale).isEqualTo(2)
        s.vm.setScale(4)
        assertThat(s.vm.state.value.canStart).isFalse()
    }

    @Test
    fun `the time estimate appears only when measured`() = runTest {
        enhancer.caps = caps(costs = listOf(EngineCost(EnhanceEngine.CLASSICAL, 4, 1_000_000f)))
        val s = setup()
        assertThat(s.vm.state.value.estimateMs).isNull() // ML not measured
        s.vm.setEngine(EnhanceEngine.CLASSICAL)
        assertThat(s.vm.state.value.estimateMs).isEqualTo(2304L) // 64×36 px = 0.002304 MP × 1e6 ms
        s.vm.setScale(2)
        assertThat(s.vm.state.value.estimateMs).isNull()
    }

    @Test
    fun `typed frame errors map to German messages`() = runTest {
        val s = setup()
        enhancer.failWith = EnhanceException(EnhanceError.Memory)
        s.vm.enhance()
        eventually { s.vm.state.value.error != null }
        assertThat(s.vm.state.value.error).isEqualTo(R.string.enhance_error_memory)
        assertThat(s.vm.state.value.progress).isNull()

        enhancer.failWith = EnhanceException(EnhanceError.TooLarge)
        s.vm.enhance()
        eventually { s.vm.state.value.error == R.string.enhance_error_too_large }
    }

    @Test
    fun `cancel stops enhancing without a result`() = runTest {
        enhancer.block = { awaitCancellation() }
        val s = setup()
        s.vm.enhance()
        eventually { s.vm.state.value.progress == 0.5f }
        s.vm.cancel()
        eventually { s.vm.state.value.progress == null }
        assertThat(s.vm.state.value.result).isNull()
        assertThat(s.vm.state.value.canStart).isTrue()
    }

    @Test
    fun `without a phone copy nothing is loaded`() = runTest {
        val s = setup(localItem(MediaKind.ORIGINAL_PHOTO, file = null))
        assertThat(s.vm.state.value.loadError).isEqualTo(R.string.enhance_error_no_copy)
        assertThat(s.vm.state.value.canStart).isFalse()
    }
}

