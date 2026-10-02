package me.ri3d.dashcam.media

import android.content.Context
import android.os.StatFs
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ri3d.dashcam.R
import me.ri3d.dashcam.core.ui.AxoTopBar
import me.ri3d.dashcam.core.ui.ConfirmDialog
import me.ri3d.dashcam.core.ui.ListGroup
import me.ri3d.dashcam.core.ui.ListRow
import me.ri3d.dashcam.core.ui.SectionHeader
import java.io.File
import javax.inject.Inject

/** What the storage screen can free; plate crops belong to the plate history and are only shown. */
enum class StorageKind(@StringRes val label: Int, @StringRes val confirm: Int, val kinds: Set<MediaKind>) {
    DOWNLOADS(R.string.media_storage_downloads, R.string.media_storage_free_downloads_text, setOf(MediaKind.ORIGINAL_VIDEO, MediaKind.ORIGINAL_PHOTO)),
    SCREENSHOTS(R.string.media_storage_screenshots, R.string.media_storage_free_screenshots_text, setOf(MediaKind.SCREENSHOT)),
    ENHANCED(R.string.media_storage_enhanced, R.string.media_storage_free_enhanced_text, setOf(MediaKind.ENHANCED_FRAME, MediaKind.UPSCALED_CLIP)),
    CACHE(R.string.media_storage_cache, R.string.media_storage_free_cache_text, emptySet()),
}

/**
 * Bytes on this phone per [StorageKind], plate crops and free space. [lastCopies]: phone copies per kind that have
 * neither a recorder nor a Drive copy (freeing deletes them for good).
 */
data class StorageUsage(val bytes: Map<StorageKind, Long>, val lastCopies: Map<StorageKind, Int>, val plateCrops: Long, val free: Long)

@HiltViewModel
class StorageViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MediaRepository,
    private val downloads: DownloadQueue,
    private val http: RecorderHttp,
    private val driveDownloads: DriveDownloadQueue,
) : ViewModel() {
    private val _usage = MutableStateFlow<StorageUsage?>(null)
    val usage: StateFlow<StorageUsage?> = _usage.asStateFlow()

    init {
        viewModelScope.launch { _usage.value = measure() }
    }

    /** Deletes phone copies only (each via [MediaRepository.deleteLocalCopy]); recorder and Drive copies stay. */
    fun free(kind: StorageKind) {
        viewModelScope.launch {
            when (kind) {
                // Only the media image cache: other features' cache files (diagnostics export, avatar preview) stay.
                StorageKind.CACHE -> withContext(Dispatchers.IO) {
                    http.imageLoader.memoryCache?.clear()
                    http.imageLoader.diskCache?.clear()
                }
                else -> {
                    repository.localItems(kind.kinds).forEach { repository.deleteLocalCopy(it.id) }
                    if (kind == StorageKind.DOWNLOADS) withContext(Dispatchers.IO) { partFiles().filterNot(::inTransfer).forEach { it.delete() } }
                }
            }
            _usage.value = measure()
        }
    }

    private suspend fun measure(): StorageUsage = withContext(Dispatchers.IO) {
        repository.importScreenshots() // screenshots taken since the library was last open count too
        val local = repository.localItems(MediaKind.entries.toSet())
        fun sizeOf(kinds: Set<MediaKind>) = local.filter { it.kind in kinds }
            .sumOf { (it.localFile?.length() ?: 0) + (it.localThumbPath?.let(::File)?.length() ?: 0) }
        StorageUsage(
            bytes = StorageKind.entries.associateWith { kind ->
                when (kind) {
                    StorageKind.CACHE -> http.imageLoader.diskCache?.size ?: 0
                    StorageKind.DOWNLOADS -> sizeOf(kind.kinds) + partFiles().sumOf { it.length() }
                    else -> sizeOf(kind.kinds)
                }
            },
            lastCopies = StorageKind.entries.associateWith { kind ->
                local.count { it.kind in kind.kinds && it.recorderPath == null && it.driveFileId == null }
            },
            plateCrops = dirSize(File(context.filesDir, "plates")),
            free = StatFs(context.filesDir.path).availableBytes,
        )
    }

    /** Interrupted downloads (`media/<id>/<name>.part` and its `.part.size`; from Drive `<name>.drive` and its `.part`). */
    private fun partFiles() = repository.mediaDir.walk()
        .filter { it.isFile && (it.name.endsWith(".part") || it.name.endsWith(".part.size") || it.name.endsWith(".drive")) }.toList()

    private fun inTransfer(part: File): Boolean {
        val id = part.parentFile?.name
        return downloads.progress.value[id]?.state in DownloadQueue.ACTIVE || driveDownloads.progress.value[id]?.state in DownloadQueue.ACTIVE
    }

    private fun dirSize(dir: File) = dir.walk().filter { it.isFile }.sumOf { it.length() }
}

@Composable
fun StorageScreen(onBack: () -> Unit, viewModel: StorageViewModel = hiltViewModel()) {
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirm by rememberSaveable { mutableStateOf<StorageKind?>(null) }
    fun size(bytes: Long?) = bytes?.let { Formatter.formatShortFileSize(context, it) } ?: "…"

    Scaffold(topBar = { AxoTopBar(stringResource(R.string.media_storage_title), onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeader(stringResource(R.string.media_storage_on_phone))
            ListGroup(
                StorageKind.entries.map<StorageKind, @Composable (androidx.compose.ui.graphics.Shape) -> Unit> { kind ->
                    { shape ->
                        val bytes = usage?.bytes?.get(kind)
                        ListRow(
                            stringResource(kind.label),
                            supporting = size(bytes),
                            shape = shape,
                            trailing = {
                                TextButton(onClick = { confirm = kind }, enabled = (bytes ?: 0) > 0) { Text(stringResource(R.string.media_storage_free)) }
                            },
                        )
                    }
                } + { shape ->
                    ListRow(
                        stringResource(R.string.media_storage_plates),
                        supporting = stringResource(R.string.media_storage_plates_text, size(usage?.plateCrops)),
                        shape = shape,
                    )
                },
            )
            Text(
                stringResource(R.string.media_storage_free_space, size(usage?.free)),
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.media_storage_note),
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    confirm?.let { kind ->
        ConfirmDialog(
            title = stringResource(R.string.media_storage_free_title, stringResource(kind.label)),
            text = stringResource(kind.confirm) + (usage?.lastCopies?.get(kind)?.takeIf { it > 0 }
                ?.let { "\n\n" + pluralStringResource(R.plurals.media_storage_last_copies, it, it) } ?: ""),
            confirmLabel = stringResource(R.string.media_storage_free),
            onConfirm = {
                confirm = null
                viewModel.free(kind)
            },
            onDismiss = { confirm = null },
            danger = kind != StorageKind.CACHE,
        )
    }
}
