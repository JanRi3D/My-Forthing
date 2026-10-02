package me.ri3d.dashcam.media

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.ri3d.dashcam.dashcam.RecorderConnectionManager
import me.ri3d.dashcam.dashcam.RecorderConnectionState
import javax.inject.Inject
import javax.inject.Singleton

/** What the Recordings screen shows: the recorder type of the visible tab and the media ids on screen, top first. */
data class PrefetchFocus(val type: Int? = null, val visible: List<String> = emptyList())

/** Prefetch priority: the visible items of the focused tab, the rest of that tab, then the other tabs, each in list order. */
internal fun prefetchOrder(rows: List<MediaItem>, focus: PrefetchFocus): List<MediaItem> {
    val (tab, others) = rows.partition { it.recorderType == focus.type }
    val position = focus.visible.withIndex().associate { (i, id) -> id to i }
    val (shown, rest) = tab.partition { it.id in position }
    return shown.sortedBy { position[it.id] } + rest + others.sortedBy { it.recorderType ?: Int.MAX_VALUE }
}

/** Per recorder session: thumbnails looked at (fetched, failed or found cached) and how many were fetched. */
internal class PrefetchProgress {
    private var session: Any? = null
    val seen = HashSet<String>()
    var fetched = 0

    fun startSession(session: Any) {
        if (session === this.session) return
        this.session = session
        seen.clear()
        fetched = 0
    }
}

/**
 * Fetches missing thumbnails ([candidates], in priority order) one at a time while [open] carries a session, i.e.
 * while prefetching may run; a gate that closes (download started, list scrolled) cancels the request in flight at
 * once, and that thumbnail is asked again later. Only asks when [idle] (no other recorder HTTP request runs or waits),
 * at most [cap] fetches per session. One `notes.http` line per [NOTE_EVERY] fetches.
 */
internal suspend fun prefetchLoop(
    open: Flow<Any?>,
    candidates: StateFlow<List<RecorderThumb>>,
    progress: PrefetchProgress,
    isCached: suspend (key: String) -> Boolean,
    idle: () -> Boolean,
    fetch: suspend (RecorderThumb) -> Unit,
    note: (String) -> Unit,
    cap: Int = ThumbnailPrefetcher.CAP,
) = open.distinctUntilChanged { a, b -> a === b }.collectLatest { session ->
    if (session == null) return@collectLatest
    progress.startSession(session)
    while (progress.fetched < cap) {
        val next = candidates.value.firstOrNull { it.key !in progress.seen }
        if (next == null) {
            candidates.first { list -> list.any { it.key !in progress.seen } } // new rows or another tab
            continue
        }
        if (!idle()) {
            delay(ThumbnailPrefetcher.IDLE_POLL_MS)
            continue
        }
        progress.seen += next.key
        if (isCached(next.key)) continue
        try {
            fetch(next)
        } catch (e: CancellationException) {
            progress.seen -= next.key
            throw e
        }
        if (++progress.fetched % NOTE_EVERY == 0) note("thumbnail prefetch: ${progress.fetched} fetched (${progress.seen.size} checked, cap $cap)")
    }
}

private const val NOTE_EVERY = 20

/**
 * Lowest-priority thumbnail prefetch for the recorder tabs: while a session is Ready, no download runs and the list is
 * not being scrolled, missing thumbnails of the known recorder copies go through the recorder client's single-request
 * dispatcher one by one into the disk cache (visible items of the current tab first, then the rest of that tab, then
 * the other tabs; [CAP] per session). The session count survives the screen; one loop runs at a time.
 */
@Singleton
class ThumbnailPrefetcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: RecorderHttp,
    private val manager: RecorderConnectionManager,
    private val downloads: DownloadQueue,
    private val repository: MediaRepository,
) {
    private val mutex = Mutex()
    private val progress = PrefetchProgress()

    /** Runs until cancelled (the screen's scope). */
    suspend fun run(focus: StateFlow<PrefetchFocus>, scrolling: StateFlow<Boolean>) = mutex.withLock {
        coroutineScope {
            val candidates = combine(repository.observeRecorderCopies(), focus) { rows, f ->
                prefetchOrder(rows.filter { it.localThumbPath == null }, f).mapNotNull { http.thumb(it, network = true) }
            }.flowOn(Dispatchers.Default).stateIn(this)
            val open = combine(manager.state, downloads.progress, scrolling) { state, transfers, scroll ->
                (state as? RecorderConnectionState.Ready)?.session
                    ?.takeIf { !scroll && transfers.values.none { it.state == TransferState.RUNNING } }
            }
            prefetchLoop(open, candidates, progress, ::isCached, http::idle, ::fetch, http::note)
        }
    }

    private suspend fun isCached(key: String) = withContext(Dispatchers.IO) {
        http.imageLoader.diskCache?.openSnapshot(key)?.use { true } ?: false
    }

    private suspend fun fetch(thumb: RecorderThumb) {
        http.imageLoader.execute(thumb.request(context, memory = false))
    }

    companion object {
        // ponytail: soft cap per session (≈ 1.5 MB of .thm); raise once the recorder proves it does not mind.
        const val CAP = 300
        const val IDLE_POLL_MS = 250L
    }
}
