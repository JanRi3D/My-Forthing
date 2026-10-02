package me.ri3d.dashcam.plates

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ri3d.dashcam.core.log.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong

/**
 * A live frame: a software ARGB_8888 bitmap (e.g. `TextureView.getBitmap()` or a PixelCopy target; not HARDWARE).
 * The processor does not recycle [bitmap], so hand over a fresh (or no longer used) one.
 */
class Frame(val bitmap: Bitmap, val timestampMs: Long)

/** Measured over the last [LivePlateProcessor.STATS_WINDOW_MS]: frames processed per second, mean ms per frame, busy share of wall time. */
data class ProcessingStats(
    val processedFps: Float = 0f,
    val avgMs: Float = 0f,
    val busyFraction: Float = 0f,
    val dropped: Long = 0,
)

/**
 * Keeps processing at about [budget] of wall time: after a frame that took d ms (smoothed), the next one is
 * accepted d × (1 / budget − 1) ms later, so busy / (busy + idle) ≈ budget whatever the device speed.
 */
class AdaptiveThrottle(
    private val budget: Float = 0.3f,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    init {
        require(budget > 0f && budget <= 1f) { "budget must be in (0, 1]" }
    }

    // Read by the submitting thread, written by the processing coroutine.
    @Volatile
    var avgMs = 0f
        private set

    @Volatile
    private var nextAt = Long.MIN_VALUE

    fun ready(now: Long = clock()) = now >= nextAt

    fun onProcessed(durationMs: Long, endedAt: Long = clock()) {
        avgMs = if (avgMs == 0f) durationMs.toFloat() else avgMs * 0.7f + durationMs * 0.3f
        nextAt = endedAt + (avgMs * (1f / budget - 1f)).roundToLong()
    }
}

/**
 * Live plate recognition with drop-if-busy semantics: [submit] processes a frame only when no frame is in
 * progress and [throttle] allows it; everything else is dropped and counted. Detections of the latest processed
 * frame are in [detections] (frame coordinates); with a [repository] they are also recorded as LIVE sightings.
 */
class LivePlateProcessor(
    private val recognizer: PlateRecognizer,
    private val repository: PlateRepository?,
    private val scope: CoroutineScope,
    private val throttle: AdaptiveThrottle = AdaptiveThrottle(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val busy = AtomicBoolean(false)

    @Volatile
    private var closed = false

    @Volatile
    private var job: Job? = null
    // Touched only by the processing coroutine (one at a time).
    private val recent = ArrayDeque<LongArray>() // [endedAt, durationMs]
    private var firstStart = -1L

    private val _detections = MutableStateFlow<List<PlateDetection>>(emptyList())
    val detections: StateFlow<List<PlateDetection>> = _detections.asStateFlow()

    private val _stats = MutableStateFlow(ProcessingStats())
    val stats: StateFlow<ProcessingStats> = _stats.asStateFlow()

    /** True if a frame submitted now would be processed: check it before grabbing a bitmap from the player. */
    val wantsFrame: Boolean get() = !closed && !busy.get() && throttle.ready(clock())

    /** Returns false (frame dropped) while busy or throttled, and silently after [close]. */
    fun submit(frame: Frame): Boolean {
        if (closed) return false
        if (!throttle.ready(clock()) || !busy.compareAndSet(false, true)) {
            _stats.update { it.copy(dropped = it.dropped + 1) }
            return false
        }
        job = scope.launch(dispatcher) {
            val start = clock()
            if (firstStart < 0) firstStart = start
            try {
                val found = recognizer.recognize(frame.bitmap, frame.timestampMs)
                _detections.value = found
                if (found.isNotEmpty()) repository?.recordSightings(found, SightingSource.LIVE, frame = frame.bitmap)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "frame failed: ${e.javaClass.simpleName}")
            } finally {
                val end = clock()
                throttle.onProcessed(end - start, end)
                publish(end, end - start)
                busy.set(false)
            }
        }
        return true
    }

    /** Stops processing and releases the recognizer. */
    fun close() {
        closed = true
        job?.cancel()
        recognizer.close()
        _detections.value = emptyList()
    }

    private fun publish(now: Long, durationMs: Long) {
        recent.addLast(longArrayOf(now, durationMs))
        while (now - recent.first()[0] >= STATS_WINDOW_MS) recent.removeFirst()
        val span = (now - firstStart).coerceIn(1L, STATS_WINDOW_MS)
        val busyMs = recent.sumOf { it[1] }
        _stats.update {
            it.copy(
                processedFps = recent.size * 1000f / span,
                avgMs = busyMs.toFloat() / recent.size,
                busyFraction = (busyMs.toFloat() / span).coerceAtMost(1f),
            )
        }
    }

    companion object {
        const val STATS_WINDOW_MS = 5_000L
        private const val TAG = "LivePlates"
    }
}
