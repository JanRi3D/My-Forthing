package me.ri3d.cam.plates

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class AdaptiveThrottleTest {
    @Test
    fun `waits so that processing takes the budget share of wall time`() {
        val throttle = AdaptiveThrottle(budget = 0.3f)
        assertThat(throttle.ready(0)).isTrue()
        throttle.onProcessed(durationMs = 300, endedAt = 1_000)
        assertThat(throttle.ready(1_699)).isFalse()
        assertThat(throttle.ready(1_700)).isTrue() // 300 busy / (300 + 700) = 30 %
    }

    @Test
    fun `slower frames stretch the interval`() {
        val throttle = AdaptiveThrottle(budget = 0.5f)
        throttle.onProcessed(durationMs = 100, endedAt = 0)
        throttle.onProcessed(durationMs = 1_100, endedAt = 1_000) // smoothed: 0.7 * 100 + 0.3 * 1100 = 400
        assertThat(throttle.avgMs).isWithin(0.01f).of(400f)
        assertThat(throttle.ready(1_399)).isFalse()
        assertThat(throttle.ready(1_400)).isTrue()
    }
}

@RunWith(RobolectricTestRunner::class)
class LivePlateProcessorTest {
    private val plate = PlateDetection("B-MK 4821", "BMK4821", 0.9f, RectF(1f, 2f, 3f, 4f), 0, PlateFormat.GERMAN)

    private class GatedRecognizer(private val result: List<PlateDetection>) : PlateRecognizer {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        var closed = false
        override suspend fun recognize(frame: Bitmap, timestampMs: Long): List<PlateDetection> {
            calls++
            gate.await()
            return result
        }
        override fun close() { closed = true }
    }

    @Test
    fun `drops frames while busy and publishes detections and stats`() = runTest {
        var now = 0L
        val recognizer = GatedRecognizer(listOf(plate))
        val processor = LivePlateProcessor(
            recognizer, repository = null, scope = backgroundScope,
            throttle = AdaptiveThrottle(budget = 0.25f) { now },
            dispatcher = StandardTestDispatcher(testScheduler), clock = { now },
        )
        val frame = Frame(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888), 0)

        assertThat(processor.submit(frame)).isTrue()
        runCurrent()
        assertThat(processor.wantsFrame).isFalse()
        assertThat(processor.submit(frame)).isFalse() // busy
        assertThat(processor.stats.value.dropped).isEqualTo(1)

        now = 100
        recognizer.gate.complete(Unit)
        runCurrent()
        assertThat(processor.detections.value).containsExactly(plate)
        assertThat(processor.stats.value.avgMs).isEqualTo(100f)

        now = 399 // 100 ms busy at a 25 % budget: next frame from 400
        assertThat(processor.submit(frame)).isFalse()
        now = 400
        assertThat(processor.wantsFrame).isTrue()
        assertThat(processor.submit(frame)).isTrue()
        runCurrent()
        assertThat(recognizer.calls).isEqualTo(2)
        assertThat(processor.stats.value.dropped).isEqualTo(2)

        processor.close()
        assertThat(recognizer.closed).isTrue()
        assertThat(processor.detections.value).isEmpty()
        now = 10_000
        assertThat(processor.wantsFrame).isFalse()
        assertThat(processor.submit(frame)).isFalse() // after close: dropped silently, not counted
        assertThat(processor.stats.value.dropped).isEqualTo(2)
        assertThat(recognizer.calls).isEqualTo(2)
    }
}
