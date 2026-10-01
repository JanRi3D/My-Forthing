package to.axolotl.cam.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.axolotl.cam.dashcam.RecorderConnectionManager
import to.axolotl.cam.recorder.FileList
import to.axolotl.cam.recorder.RecorderCommand
import to.axolotl.cam.recorder.RecorderError
import to.axolotl.cam.recorder.RecorderFile
import to.axolotl.cam.recorder.RecorderResult
import to.axolotl.cam.recorder.parseFileList

/** How a listing ended: the recorder ran out of files, or paging had to stop (cursor repeated or missing). */
enum class ListingEnd { COMPLETE, STOPPED }

/**
 * The 4100 listing of one type as far as it has been paged, in the recorder's order. Totals are the raw values of
 * the latest page ("laut Recorder"; size unit unknown).
 */
data class Listing(
    val files: List<RecorderFile> = emptyList(),
    val totalFileNum: Int? = null,
    val totalFileSize: Long? = null,
    val end: ListingEnd? = null,
    /** Cursor for the next request: the exact fileName of the last entry of the last page ("" = first page). */
    val cursor: String = "",
) {
    /**
     * Appends one page (report "Cursor-based browsing"). Entries already listed are dropped (the recorder's cursor
     * inclusivity is unknown) and entries without fileName are skipped. The listing ends on an empty page or one
     * shorter than [pageNum]; it stops when the page's last fileName is missing or was listed before, which guards
     * against a recorder that ignores or repeats the cursor.
     */
    fun append(page: FileList, pageNum: Int = PAGE_SIZE): Listing {
        val before = files.mapTo(HashSet()) { it.fileName }
        val last = page.fileList.lastOrNull()?.fileName
        val seen = HashSet(before)
        val fresh = page.fileList.filter { it.fileName != null && seen.add(it.fileName) }
        val end = when {
            page.fileList.size < pageNum -> ListingEnd.COMPLETE
            last == null || last in before -> ListingEnd.STOPPED
            else -> null
        }
        return Listing(files + fresh, page.totalFileNum ?: totalFileNum, page.totalFileSize ?: totalFileSize, end, last ?: cursor)
    }

    companion object {
        /** The original app's batch size. */
        const val PAGE_SIZE = 50
    }
}

data class BrowserState(
    val listing: Listing = Listing(),
    val loading: Boolean = false,
    val error: RecorderError? = null,
    /** True once the first page was requested since the last reset. */
    val started: Boolean = false,
)

/**
 * Pages one recorder type with 4100 and registers every page in the library before showing it. [refresh] starts
 * again with an empty cursor; [loadMore] requests the next page unless the listing ended or failed.
 */
class RecorderBrowser(
    val type: Int,
    private val manager: RecorderConnectionManager,
    private val repository: MediaRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(BrowserState())
    val state: StateFlow<BrowserState> = _state.asStateFlow()
    private var job: Job? = null

    fun refresh() {
        job?.cancel()
        _state.value = BrowserState()
        loadMore()
    }

    /** After an error the next page is requested only from here, so a failing recorder is not polled. */
    fun retry() {
        _state.update { it.copy(error = null) }
        loadMore()
    }

    fun loadMore() {
        val current = _state.value
        if (current.loading || current.listing.end != null || current.error != null) return
        _state.value = current.copy(loading = true, started = true)
        job = scope.launch {
            val result = manager.request(RecorderCommand.ListFiles(type, current.listing.cursor, Listing.PAGE_SIZE), ::parseFileList)
            when (result) {
                is RecorderResult.Ok -> {
                    repository.upsertFromRecorderListing(type, result.value.fileList)
                    val listing = current.listing.append(result.value)
                    if (listing.end == ListingEnd.COMPLETE) {
                        repository.reconcileRecorderListing(type, listing.files.mapNotNullTo(HashSet()) { it.fileName })
                    }
                    _state.value = BrowserState(listing, loading = false, error = null, started = true)
                }
                is RecorderResult.Failed -> _state.update { it.copy(loading = false, error = result.error) }
            }
        }
    }

    /** After a 4101: the files are gone from the recorder, the rest of the listing stays. */
    fun removeAll(paths: Collection<String>) {
        _state.update { s -> s.copy(listing = s.listing.copy(files = s.listing.files.filterNot { it.fileName in paths })) }
    }

    /** Disconnected: nothing listed is current any more. */
    fun reset() {
        job?.cancel()
        _state.value = BrowserState()
    }
}
