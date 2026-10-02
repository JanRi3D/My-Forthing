package me.ri3d.cam.plates.ui

import android.net.Uri
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ri3d.cam.core.data.PreferencesRepository
import me.ri3d.cam.core.log.Log
import me.ri3d.cam.media.MediaItem
import me.ri3d.cam.media.MediaKind
import me.ri3d.cam.media.MediaRepository
import me.ri3d.cam.plates.ClipPlateScanner
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Clip checks ("Clip auf Kennzeichen prüfen"), one at a time in an app-wide queue, so a check goes on after leaving
 * the clip screen (not after process death). Only phone copies of ORIGINAL_VIDEO are scanned: enhanced and upscaled
 * outputs are reconstructions and never become sightings (CONTRACTS §12).
 */
@Singleton
class ClipScans internal constructor(
    private val media: MediaRepository,
    private val scope: CoroutineScope,
    private val processStartMs: Long,
    private val clock: () -> Long,
    /** `AppPreferences.platesClips`. */
    private val autoEnabled: Flow<Boolean>,
    private val scan: suspend (item: MediaItem, onProgress: (Float) -> Unit) -> Int,
) {
    @Inject
    constructor(media: MediaRepository, preferences: PreferencesRepository, scanner: ClipPlateScanner) : this(
        media, CoroutineScope(SupervisorJob() + Dispatchers.Default),
        // Wall time at which this process started: downloads finished before the UI came up count as new.
        System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()),
        System::currentTimeMillis,
        preferences.preferences.map { it.platesClips },
        { item, onProgress ->
            // Sightings get the road time when the recorder time is known (a guess in the phone's zone).
            scanner.scan(Uri.fromFile(checkNotNull(item.localFile)), item.id, clipStartMs = item.recorderTimeEpochGuess) { onProgress(it.fraction) }
                .plates.size
        },
    )

    sealed interface Result {
        /** [plates] distinct plates found (0 = "keine Kennzeichen gefunden"). */
        data class Done(val plates: Int) : Result
        data object Cancelled : Result
        data object Failed : Result
    }

    data class State(
        val running: String? = null,
        val fraction: Float = 0f,
        val queued: List<String> = emptyList(),
        /** Outcome of the last check per mediaId in this process. */
        val results: Map<String, Result> = emptyMap(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // cancel() writes cancelled, then reads current; check() writes current, then reads cancelled: one of them sees it.
    @Volatile
    private var current: Deferred<Int>? = null

    @Volatile
    private var cancelled: String? = null
    private val autoStarted = AtomicBoolean(false)

    init {
        scope.launch {
            while (true) {
                _state.first { it.queued.isNotEmpty() }
                var id: String? = null
                cancelled = null
                _state.update { s ->
                    id = s.queued.firstOrNull()
                    if (id == null) s else s.copy(running = id, fraction = 0f, queued = s.queued.drop(1))
                }
                val next = id ?: continue
                val result = check(next)
                _state.update {
                    // null: the phone copy went away while queued, so there is nothing to report
                    it.copy(running = null, fraction = 0f, results = if (result == null) it.results - next else it.results + (next to result))
                }
            }
        }
    }

    /** Queues a check of [item]; false (nothing queued) for anything but a phone copy of an original video. */
    fun enqueue(item: MediaItem): Boolean {
        if (!scannable(item)) return false
        _state.update { s ->
            if (item.id == s.running || item.id in s.queued) s else s.copy(queued = s.queued + item.id, results = s.results - item.id)
        }
        return true
    }

    /** Cancels the running check of [mediaId] (sightings written so far stay) or removes it from the queue. */
    fun cancel(mediaId: String) {
        if (_state.value.running == mediaId) {
            cancelled = mediaId
            current?.cancel()
        }
        _state.update { it.copy(queued = it.queued - mediaId) }
    }

    /**
     * While `platesClips` is on, checks every original video downloaded since it was switched on (since process start
     * when it was already on). Downloads made while it was off are never checked automatically. Idempotent; runs for
     * the life of the process.
     */
    fun startAutoScan() {
        if (!autoStarted.compareAndSet(false, true)) return
        scope.launch {
            val handled = HashSet<String>()
            var first = true
            autoEnabled.distinctUntilChanged().collectLatest { on ->
                val from = if (first) processStartMs else clock()
                first = false
                if (!on) return@collectLatest
                media.observe(MediaKind.ORIGINAL_VIDEO).collect { items ->
                    items.filter { (it.downloadedAt ?: -1L) >= from && it.localUri != null && handled.add(it.id) }.forEach(::enqueue)
                }
            }
        }
    }

    private suspend fun check(id: String): Result? {
        val item = media.get(id)?.takeIf(::scannable) ?: return null
        val job = scope.async { scan(item) { f -> _state.update { if (it.running == id) it.copy(fraction = f) else it } } }
        current = job
        if (cancelled == id) job.cancel() // cancelled before the scan started
        return try {
            Result.Done(job.await())
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // the queue itself was stopped
            Result.Cancelled
        } catch (e: Exception) {
            Log.w(TAG, "clip check failed: ${e.javaClass.simpleName}")
            Result.Failed
        } finally {
            current = null
        }
    }

    companion object {
        private const val TAG = "ClipScans"

        fun scannable(item: MediaItem) = item.kind == MediaKind.ORIGINAL_VIDEO && item.localFile?.isFile == true
    }
}
