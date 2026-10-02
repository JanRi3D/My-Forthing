package me.ri3d.dashcam.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.ri3d.dashcam.R
import me.ri3d.dashcam.core.ui.UiText
import me.ri3d.dashcam.drive.DriveAuth
import me.ri3d.dashcam.drive.DriveAuthState
import me.ri3d.dashcam.drive.DriveRestApi
import me.ri3d.dashcam.drive.driveMessage
import me.ri3d.dashcam.media.DownloadQueue
import me.ri3d.dashcam.media.DriveDownloadQueue
import me.ri3d.dashcam.media.MediaItem
import me.ri3d.dashcam.media.MediaKind
import me.ri3d.dashcam.media.MediaRepository
import me.ri3d.dashcam.media.MediaThumb
import me.ri3d.dashcam.media.RecorderHttp
import me.ri3d.dashcam.media.TransferProgress
import me.ri3d.dashcam.media.TransferState
import me.ri3d.dashcam.media.placeholderFor
import java.io.File
import javax.inject.Inject

/** The Drive copies in the app: Drive tab of Aufnahmen and the Drive copy in the clip screen. */
@HiltViewModel
class DriveLibraryViewModel @Inject constructor(
    auth: DriveAuth,
    repository: MediaRepository,
    private val restore: DriveRestore,
    private val downloads: DriveDownloadQueue,
    http: RecorderHttp,
) : ViewModel() {
    val authState: StateFlow<DriveAuthState> = auth.state

    /** Everything with a Drive copy, newest first; null until the library answered. */
    val items: StateFlow<List<MediaItem>?> = repository.observeDrive().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val importing: StateFlow<Boolean> = restore.importing
    val lastImport: StateFlow<Long?> = restore.lastImport
    val transfers: StateFlow<Map<String, TransferProgress>> = downloads.progress

    /** The app's media loader: Drive thumbnails and photos share its disk cache with the recorder thumbnails. */
    val imageLoader = http.imageLoader

    private val _errors = Channel<UiText>(Channel.BUFFERED)

    /** Failures of an import the user started ("Aktualisieren"), German. */
    val errors: Flow<UiText> = _errors.receiveAsFlow()

    /** The local thumbnail once the file is on the phone, else Drive's. */
    fun thumb(item: MediaItem): Any? = item.localThumbPath?.let(::File) ?: restore.thumbnail(item)

    /** "Aktualisieren" on the Drive tab. */
    fun refresh() {
        viewModelScope.launch { restore.importFromDrive().onFailure { _errors.send(it.driveMessage()) } }
    }

    /** The tab is shown: the first time in a process it lists Drive again (fresh thumbnail links); errors only logged. */
    fun shown() {
        if (authState.value is DriveAuthState.Connected && !restore.importedThisProcess) viewModelScope.launch { restore.importFromDrive() }
    }

    fun download(id: String) {
        viewModelScope.launch { downloads.enqueue(id) }
    }
}

/**
 * The Drive copy of an item without phone copy in the clip screen: photos come straight from Drive, videos show
 * Drive's thumbnail (they play once on the phone).
 */
@Composable
fun DriveMedia(item: MediaItem, modifier: Modifier = Modifier, viewModel: DriveLibraryViewModel = hiltViewModel()) {
    val context = LocalContext.current
    Box(modifier) {
        MediaThumb(viewModel.thumb(item), placeholderFor(item.kind), Modifier.fillMaxSize(), viewModel.imageLoader)
        val fileId = item.driveFileId
        if (fileId != null && item.kind in PHOTO_KINDS) {
            val request = remember(fileId) {
                ImageRequest.Builder(context)
                    .data(DriveRestApi.contentUrl(fileId))
                    .memoryCacheKey("drive-file:$fileId")
                    .diskCachePolicy(CachePolicy.DISABLED) // full photos would push the thumbnails out of the cache
                    .build()
            }
            AsyncImage(
                request, contentDescription = stringResource(R.string.media_drive_photo_description, item.originalFileName),
                imageLoader = viewModel.imageLoader, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * "Vom Drive laden" ("Auf dem Handy speichern" for photos) while Drive is connected, its progress while it runs and
 * the reason when it failed.
 */
@Composable
fun DriveDownloadAction(item: MediaItem, viewModel: DriveLibraryViewModel = hiltViewModel()) {
    val auth by viewModel.authState.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()
    val transfer = transfers[item.id]
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (transfer != null && transfer.state in DownloadQueue.ACTIVE) {
            val label = stringResource(R.string.media_downloading)
            val total = transfer.totalBytes
            if (transfer.state == TransferState.RUNNING && total != null && total > 0) {
                CircularProgressIndicator(progress = { transfer.bytes.toFloat() / total }, modifier = Modifier.semantics { contentDescription = label })
            } else {
                CircularProgressIndicator(Modifier.semantics { contentDescription = label })
            }
            if (transfer.state == TransferState.WAITING) Note(stringResource(R.string.media_transfer_waiting_network))
        } else {
            val connected = auth is DriveAuthState.Connected
            FilledTonalButton(onClick = { viewModel.download(item.id) }, enabled = connected) {
                Text(stringResource(if (item.kind in PHOTO_KINDS) R.string.media_drive_save else R.string.media_drive_download))
            }
            val failure = transfer?.failure
            when {
                !connected -> Note(stringResource(R.string.media_drive_download_not_connected)) // why the button is off
                transfer?.state == TransferState.FAILED && failure != null -> Note(stringResource(failure.text), error = true)
            }
        }
    }
}

/** A short line over the photo or thumbnail, on its own background so it stays readable. */
@Composable
private fun Note(text: String, error: Boolean = false) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
    }
}

private val PHOTO_KINDS = setOf(MediaKind.ORIGINAL_PHOTO, MediaKind.SCREENSHOT, MediaKind.ENHANCED_FRAME)
