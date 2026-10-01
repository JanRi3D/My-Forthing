package to.axolotl.cam.media

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.axolotl.cam.R
import to.axolotl.cam.dashcam.RecorderConnectionManager
import to.axolotl.cam.dashcam.RecorderConnectionState
import to.axolotl.cam.dashcam.outcomeUnknown
import to.axolotl.cam.recorder.NormalInfo
import to.axolotl.cam.recorder.RecorderError
import to.axolotl.cam.recorder.RecorderFile
import to.axolotl.cam.recorder.RecorderNotification
import to.axolotl.cam.recorder.RecorderResult
import to.axolotl.cam.recorder.RecorderValues
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

/** One listed recorder file, its library row and its download. */
data class RecorderEntry(val file: RecorderFile, val item: MediaItem, val transfer: TransferProgress?)

/** One-shot results shown as snackbar. */
sealed interface MediaNotice {
    data class DeletedOnRecorder(val count: Int) : MediaNotice
    data class DeletedLocal(val count: Int) : MediaNotice
    data class RecorderFailed(val error: RecorderError) : MediaNotice
    data object NothingToDownload : MediaNotice
}

/** Recordings and SD files: recorder listings (one [RecorderBrowser] per type), phone library, selection. */
@HiltViewModel
class RecordingsViewModel @Inject constructor(
    private val manager: RecorderConnectionManager,
    private val repository: MediaRepository,
    private val downloads: DownloadQueue,
    val http: RecorderHttp,
) : ViewModel() {
    val connection: StateFlow<RecorderConnectionState> = manager.state
    val transfers: StateFlow<Map<String, TransferProgress>> = downloads.progress
    val local: StateFlow<List<MediaItem>> = repository.observeLocal().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val browsers = RecordingsTab.entries.mapNotNull { it.type }.associateWith { type ->
        RecorderBrowser(type, manager, repository, viewModelScope) { id -> downloads.progress.value[id]?.state in DownloadQueue.ACTIVE }
    }
    private var shown: Int? = null // recorder type of the visible tab

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
            manager.state.map { it is RecorderConnectionState.Ready }.distinctUntilChanged().collect { ready ->
                if (ready) shown?.let { browsers[it]?.refresh() } else browsers.values.forEach { it.reset() }
            }
        }
        viewModelScope.launch { manager.notifications.collect(::onNotification) }
    }

    fun browser(type: Int): StateFlow<BrowserState> = browsers.getValue(type).state

    /**
     * The listing of [type] joined with library rows (by recorder path) and transfers. Every page is registered
     * before it is shown, so a listed file without a row was deleted on the recorder meanwhile (e.g. from the clip
     * screen) and is left out.
     */
    fun entries(type: Int): Flow<List<RecorderEntry>> =
        combine(browsers.getValue(type).state, repository.observeRecorderType(type), transfers) { state, items, transfers ->
            val byPath = items.associateBy { it.recorderPath }
            state.listing.files.mapNotNull { file -> byPath[file.fileName]?.let { RecorderEntry(file, it, transfers[it.id]) } }
        }

    /** The tab became visible: its type is listed once per session (refresh and notifications list it again). */
    fun show(tab: RecordingsTab) {
        if (shown != tab.type) _selection.value = emptySet()
        shown = tab.type
        val browser = tab.type?.let(browsers::get) ?: return
        if (manager.state.value is RecorderConnectionState.Ready && !browser.state.value.started) browser.refresh()
    }

    fun refresh(type: Int) = browsers[type]?.refresh()
    fun loadMore(type: Int) = browsers[type]?.loadMore()
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

    /** `fileNew` / `fileDel` / `updateFileList` list the affected type again (all listed types when it is unknown). */
    private fun onNotification(notification: RecorderNotification) {
        val type = when (val info = (notification as? RecorderNotification.Normal)?.info) {
            is NormalInfo.FileNew -> info.fileType
            is NormalInfo.FileDel -> {
                info.fileName?.let { viewModelScope.launch { repository.markRecorderDeleted(it) } }
                info.fileType
            }
            is NormalInfo.UpdateFileList -> null
            else -> return
        }
        browsers.values
            .filter { it.state.value.started && (type == null || type !in browsers || it.type == type) }
            .forEach { it.refresh() }
    }
}
