package me.ri3d.cam.plates.ui

import android.graphics.Bitmap
import android.view.TextureView
import androidx.compose.ui.unit.IntSize
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.ri3d.cam.live.LiveFrameSource
import me.ri3d.cam.live.LivePlayer
import me.ri3d.cam.live.PlayerEvent
import me.ri3d.cam.media.memoryDb
import me.ri3d.cam.plates.PlateDetection
import me.ri3d.cam.plates.PlateRecognizer
import me.ri3d.cam.plates.PlateRepository
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider
import javax.net.SocketFactory

/** Live frames are collected only while the toggle is on, the screen is resumed and the stream plays. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LivePlatesViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val db = memoryDb(context)
    private val repository = PlateRepository(context, db.plateDao())

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
        File(context.filesDir, "plates").deleteRecursively()
    }

    private class FakePlayer : LivePlayer {
        val grabs = AtomicInteger()
        override var listener: ((PlayerEvent) -> Unit)? = null
        override fun play(url: String, socketFactory: SocketFactory) = Unit
        override fun stop() = Unit
        override fun release() = Unit
        override fun attach(view: TextureView) = Unit
        override fun detach(view: TextureView) = Unit
        override fun capture(maxWidth: Int): Bitmap {
            grabs.incrementAndGet()
            return Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888)
        }
    }

    private class FakeRecognizer(private val result: List<PlateDetection>) : PlateRecognizer {
        val calls = AtomicInteger()
        @Volatile var closed = 0
        override suspend fun recognize(frame: Bitmap, timestampMs: Long): List<PlateDetection> {
            calls.incrementAndGet()
            return result
        }
        override fun close() { closed++ }
    }

    /** Virtual time for the frame loop (5 fps), real time for the processor thread and Room. */
    private fun TestScope.runFor(ms: Long, until: () -> Boolean = { false }) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end && !until()) {
            testScheduler.advanceTimeBy(200)
            testScheduler.runCurrent()
            ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
    }

    @Test
    fun `frames go to the recognizer only while on, resumed and playing`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val preferences = preferencesIn(tmp.root)
        val source = LiveFrameSource()
        val player = FakePlayer()
        val recognizer = FakeRecognizer(listOf(detection("B-MK 4821", box = android.graphics.RectF(8f, 20f, 40f, 30f))))
        val vm = LivePlatesViewModel(preferences, repository, Provider { recognizer }, source)
        backgroundScope.launch { vm.recent.collect {} }

        // Playing and resumed, but the toggle is off: nothing is grabbed.
        source.player.value = player
        source.size.value = IntSize(64, 36)
        vm.setResumed(true)
        runFor(300)
        assertThat(player.grabs.get()).isEqualTo(0)
        assertThat(vm.enabled.value).isFalse()

        vm.toggle()
        runFor(3_000) { vm.recent.value.isNotEmpty() }
        assertThat(vm.enabled.value).isTrue()
        assertThat(recognizer.calls.get()).isAtLeast(1)
        assertThat(vm.frameSize.value).isEqualTo(IntSize(64, 36))
        assertThat(vm.detections.value.map { it.text }).containsExactly("B-MK 4821")
        assertThat(vm.recent.value.map { it.display }).containsExactly("B-MK 4821") // recorded as a LIVE sighting

        // A pause shorter than STOP_DELAY_MS (normal <-> full screen) keeps the processor.
        vm.setResumed(false)
        vm.setResumed(true)
        testScheduler.advanceTimeBy(LivePlatesViewModel.STOP_DELAY_MS * 2)
        testScheduler.runCurrent()
        assertThat(recognizer.closed).isEqualTo(0)

        // Not resumed: collection stops (after STOP_DELAY_MS), the recognizer is closed, no more grabs.
        vm.setResumed(false)
        runFor(3_000) { recognizer.closed == 1 }
        assertThat(recognizer.closed).isEqualTo(1)
        assertThat(vm.detections.value).isEmpty()
        val grabs = player.grabs.get()
        runFor(300)
        assertThat(player.grabs.get()).isEqualTo(grabs)

        // Resumed again, then the stream stops playing (videoSize null): stops again.
        vm.setResumed(true)
        runFor(3_000) { player.grabs.get() > grabs }
        assertThat(player.grabs.get()).isGreaterThan(grabs)
        source.size.value = null
        runFor(3_000) { recognizer.closed == 2 }
        assertThat(recognizer.closed).isEqualTo(2)

        // Toggle off while playing: stays stopped, preference written.
        source.size.value = IntSize(64, 36)
        runFor(3_000) { player.grabs.get() > grabs + 1 }
        vm.toggle()
        runFor(3_000) { recognizer.closed == 3 }
        assertThat(vm.enabled.value).isFalse()
        assertThat(recognizer.closed).isEqualTo(3)
    }
}
