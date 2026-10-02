package me.ri3d.dashcam.media

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import me.ri3d.dashcam.R
import me.ri3d.dashcam.dashcam.RecorderConnectionManager
import me.ri3d.dashcam.dashcam.RecorderConnectionState
import me.ri3d.dashcam.dashcam.outcomeUnknown
import me.ri3d.dashcam.recorder.NormalInfo
import me.ri3d.dashcam.recorder.RecorderError
import me.ri3d.dashcam.recorder.RecorderFile
import me.ri3d.dashcam.recorder.RecorderNotification
import me.ri3d.dashcam.recorder.RecorderResult
import me.ri3d.dashcam.recorder.RecorderValues
import java.io.File
import javax.inject.Inject

/** Recorder listing types 0/1/2 plus the phone library. The route argument is the enum name. */
enum class RecordingsTab(val type: Int?, @StringRes val label: Int) {
    NORMAL(RecorderValues.FILES_NORMAL, R.string.media_tab_loop),
    EVENT(RecorderValues.FILES_EVENT, R.string.media_tab_events),
    USER(RecorderValues.FILES_USER, R.string.media_tab_photos),
    PHONE(null, R.string.media_tab_phone);

    companion object {
        fun of(name: String?): RecordingsTab = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: NORMAL
    }
}

/**
 * One recorder copy as the library knows it, and its download. [thumb]: the local thumbnail once the file is on the
 * phone (also the fallback should a `.thm` be no image), else the cached recorder thumbnail ([RecorderThumb]) – which
 * asks the recorder only with a session and while no download runs, so the recorder serves only that one.
 */
data class RecorderEntry(val item: MediaItem, val transfer: TransferProgress?, val thumb: Any? = null)

/** One-shot results shown as snackbar. */
sealed interface MediaNotice {
    data class DeletedOnRecorder(val count: Int) : MediaNotice
    data class DeletedLocal(val count: Int) : MediaNotice
    data class RecorderFailed(val error: RecorderError) : MediaNotice
    data object NothingToDownload : MediaNotice
}

/**
 * Recordings and SD files: the recorder tabs show the library's recorder copies at once (also offline) while one
 * [RecorderBrowser] per type lists the recorder again page by page; phone library, selection, thumbnail prefetch.
 */
@HiltViewModel
class RecordingsViewModel @Inject constructor(
    private val manager: RecorderConnectionManager,
    private val repository: MediaRepository,
    private val downloads: DownloadQueue,
    val http: RecorderHttp,
    prefetcher: ThumbnailPrefetcher,
) : ViewModel() {
    val connection: StateFlow<RecorderConnectionState> = manager.state
    val transfers: StateFlow<Map<String, TransferProgress>> = downloads.progress
    /** Everything with a phone copy; null until the library answered (nothing to show, rather than "empty"). */
    val local: StateFlow<List<MediaItem>?> = repository.observeLocal().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val ready = manager.state.map { it is RecorderConnectionState.Ready }.distinctUntilChanged()
    private val downloading = downloads.progress.map { p -> p.values.any { it.state == TransferState.RUNNING } }.distinctUntilChanged()

    private val browsers = RecordingsTab.entries.mapNotNull { it.type }.associateWith { type ->
        RecorderBrowser(
            type, manager, repository, viewModelScope,
            inTransfer = { id -> downloads.progress.value[id]?.state in DownloadQueue.ACTIVE },
            awaitTurn = { downloading.first { !it } },
        )
    }
    private var shown: Int? = null // recorder type of the visible tab

    // Kept while the tab is away (WhileSubscribed keeps the last value), so coming back shows the list at once; a
    // reopened screen starts with the rows the process last saw, so its first frame has them.
    private val entries = browsers.keys.associateWith { type ->
        val transfersNow = transfers.value
        val initial = repository.lastRecorderRows(type)?.let { rows ->
            toEntries(rows, transfersNow, manager.state.value is RecorderConnectionState.Ready && transfersNow.values.none { it.state == TransferState.RUNNING })
        }
        combine(repository.observeRecorderType(type), transfers, ready, downloading) { items, transfers, ready, downloading ->
            toEntries(items, transfers, ready && !downloading)
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)
    }

    private fun toEntries(items: List<MediaItem>, transfers: Map<String, TransferProgress>, network: Boolean) =
        items.map { item -> RecorderEntry(item, transfers[item.id], item.localThumbPath?.let(::File) ?: http.thumb(item, network)) }

    private val focus = MutableStateFlow(PrefetchFocus())
    private val scrolling = MutableStateFlow(false)

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    /** Selected media ids. */
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()
    val selectedItems: StateFlow<List<MediaItem>> = _selection
        .map { ids -> ids.mapNotNull { repository.get(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _notices = Channel<MediaNotice>(Channel.BUFFERED)
    val notices: Flow<MediaNotice> = _notices.receiveAsFlow()

    init {
        repository.watchScreenshots()
        viewModelScope.launch { repository.importScreenshots() }
        viewModelScope.launch {
            ready.collect { ready ->
                if (ready) shown?.let { browsers[it]?.refresh() } else browsers.values.forEach { it.reset() }
            }
        }
        viewModelScope.launch { manager.notifications.collect(::onNotification) }
        viewModelScope.launch { prefetcher.run(focus, scrolling) }
    }

    fun browser(type: Int): StateFlow<BrowserState> = browsers.getValue(type).state

    /**
     * The recorder copies of [type] as the library knows them (newest recorder time first) with their transfers;
     * null until the first read. Listing pages, `fileNew`, `fileDel` and deletions change it in place.
     */
    fun entries(type: Int): StateFlow<List<RecorderEntry>?> = entries.getValue(type)

    /** When [type] was last listed to its end ("Stand"); null before the first time. */
    fun listedAt(type: Int): Flow<Long?> = repository.listedAt(type)

    /** The tab became visible: its type is listed once per session (refresh and notifications list it again). */
    fun show(tab: RecordingsTab) {
        if (shown != tab.type) _selection.value = emptySet()
        shown = tab.type
        if (focus.value.type != tab.type) focus.value = PrefetchFocus(tab.type)
        val browser = tab.type?.let(browsers::get) ?: return
        if (manager.state.value is RecorderConnectionState.Ready && !browser.state.value.started) browser.refresh()
    }

    /** The media ids the recorder list of [type] shows right now, top first: their thumbnails are prefetched first. */
    fun visible(type: Int, ids: List<String>) {
        if (type == shown) focus.value = PrefetchFocus(type, ids)
    }

    /** While the list is scrolled the prefetch pauses, so the rows coming into view load first. */
    fun scrolling(active: Boolean) {
        scrolling.value = active
    }

    fun refresh(type: Int) = browsers[type]?.refresh()
    fun retry(type: Int) = browsers[type]?.retry()

    fun toggle(id: String) = _selection.update { if (id in it) it - id else it + id }
    fun clearSelection() {
        _selection.value = emptySet()
    }

    fun download(ids: Collection<String>) {
        viewModelScope.launch {
            if (ids.count { downloads.enqueue(it) } == 0) _notices.send(MediaNotice.NothingToDownload)
        }
        clearSelection()
    }

    /** 4101 for the selected recorder copies; phone and Drive copies stay. */
    fun deleteOnRecorder(ids: Collection<String>) {
        clearSelection()
        viewModelScope.launch {
            val paths = ids.mapNotNull { repository.get(it)?.recorderPath }
            if (paths.isEmpty()) return@launch
            when (val result = repository.deleteOnRecorder(paths)) {
                is RecorderResult.Ok -> {
                    browsers.values.forEach { it.removeAll(paths) }
                    _notices.send(MediaNotice.DeletedOnRecorder(paths.size))
                }
                is RecorderResult.Failed -> {
                    _notices.send(MediaNotice.RecorderFailed(result.error))
                    if (outcomeUnknown(result.error)) browsers.values.filter { it.state.value.started }.forEach { it.refresh() }
                }
            }
        }
    }

    /** Phone copies only; rows with a recorder or Drive copy stay. */
    fun deleteLocal(ids: Collection<String>) {
        clearSelection()
        viewModelScope.launch {
            ids.forEach { repository.deleteLocalCopy(it) }
            _notices.send(MediaNotice.DeletedLocal(ids.size))
        }
    }

    fun cancelTransfer(id: String) {
        viewModelScope.launch { downloads.cancel(id) }
    }

    /**
     * `fileNew` goes to the top of its tab (row + listing of this session), `fileDel` forgets the recorder copy; neither
     * lists again. `updateFileList`, or a notification without a usable type or name, lists the affected type again
     * (all listed types when it is unknown).
     */
    private fun onNotification(notification: RecorderNotification) {
        when (val info = (notification as? RecorderNotification.Normal)?.info) {
            is NormalInfo.FileNew -> {
                val browser = info.fileType?.let(browsers::get)
                val name = info.fileName
                if (browser == null || name == null) return relist(info.fileType)
                viewModelScope.launch {
                    val file = RecorderFile(name, info.fileThm, info.fileTime, JsonObject(emptyMap()))
                    repository.upsertFromRecorderListing(browser.type, listOf(file))
                    browser.add(file)
                }
            }
            is NormalInfo.FileDel -> {
                val name = info.fileName ?: return relist(info.fileType)
                browsers.values.forEach { it.removeAll(listOf(name)) }
                viewModelScope.launch { repository.markRecorderDeleted(name) }
            }
            is NormalInfo.UpdateFileList -> relist(null)
            else -> Unit
        }
    }

    private fun relist(type: Int?) = browsers.values
        .filter { it.state.value.started && (type == null || type !in browsers || it.type == type) }
        .forEach { it.refresh() }
}
