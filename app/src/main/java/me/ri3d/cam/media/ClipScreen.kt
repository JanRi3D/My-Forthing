package me.ri3d.cam.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.webkit.MimeTypeMap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem.fromUri
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ri3d.cam.BuildConfig
import me.ri3d.cam.R
import me.ri3d.cam.core.ui.AxoTopBar
import me.ri3d.cam.core.ui.ConfirmDialog
import me.ri3d.cam.core.ui.ListGroup
import me.ri3d.cam.core.ui.ListRow
import me.ri3d.cam.core.ui.LocalSnackbarHostState
import me.ri3d.cam.core.ui.SectionHeader
import me.ri3d.cam.core.ui.UiState
import me.ri3d.cam.core.ui.StateView
import me.ri3d.cam.dashcam.RecorderConnectionManager
import me.ri3d.cam.dashcam.RecorderConnectionState
import me.ri3d.cam.enhance.EnhanceEngine
import me.ri3d.cam.enhance.EnhancementInfo
import me.ri3d.cam.recorder.RecorderResult
import java.io.File
import javax.inject.Inject

/** Phase 4: "Bild verbessern" / "Clip hochskalieren" (enhance-ui) at the current playback position. */
typealias ClipActions = @Composable (item: MediaItem, positionMs: Long) -> Unit

/** Phase 4: "Kennzeichen in diesem Clip" (plates-ui); [seekTo] jumps the player (no-op for images). */
typealias ClipExtras = @Composable (item: MediaItem, positionMs: Long, seekTo: (Long) -> Unit) -> Unit

/** Phase 4: drawn over the video/image area (16:9 box), e.g. plate boxes at the current position (plates-ui). */
typealias ClipOverlay = @Composable BoxScope.(item: MediaItem, positionMs: Long) -> Unit

/**
 * One copy that can be deleted. The clip screen lists every target in its chooser and shows the confirmation itself
 * ([title], [text], [danger] = extra acknowledgement); [onConfirm] deletes that copy only.
 */
data class DeleteTarget(
    val label: String,
    val enabled: Boolean,
    val title: String,
    val text: String,
    val danger: Boolean,
    val onConfirm: () -> Unit,
)

/** Phase 4: further delete targets (drive-backup: "Drive-Kopie löschen") as data. */
typealias ClipDeleteTargets = @Composable (item: MediaItem) -> List<DeleteTarget>

/** Shares a phone copy through [MediaFileProvider] (`files/media`, `files/screenshots`, `files/enhance`). */
class MediaFileProvider : FileProvider()

@HiltViewModel
class ClipViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repository: MediaRepository,
    private val downloads: DownloadQueue,
    manager: RecorderConnectionManager,
) : ViewModel() {
    val mediaId: String = checkNotNull(handle["mediaId"])

    val item: StateFlow<UiState<MediaItem>> = repository.observe(mediaId)
        .map { if (it == null) UiState.Empty else UiState.Ready(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    /** The original of a derived item; null when there is none or it was deleted. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val parent: StateFlow<MediaItem?> = repository.observe(mediaId)
        .flatMapLatest { it?.parentId?.let(repository::observe) ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val children: StateFlow<List<MediaItem>> = repository.observeChildren(mediaId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** `<file>.enhance.json` of a derived item with a phone copy. */
    val enhancement: StateFlow<EnhancementInfo?> = repository.observe(mediaId)
        .map { item -> item?.takeIf { it.isDerived }?.localFile?.let { withContext(Dispatchers.IO) { EnhancementInfo.read(it) } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val transfer: StateFlow<TransferProgress?> = downloads.progress.map { it[mediaId] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val ready: StateFlow<Boolean> = manager.state.map { it is RecorderConnectionState.Ready }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _notices = Channel<MediaNotice>(Channel.BUFFERED)
    val notices: Flow<MediaNotice> = _notices.receiveAsFlow()

    fun download() {
        viewModelScope.launch { if (!downloads.enqueue(mediaId)) _notices.send(MediaNotice.NothingToDownload) }
    }

    fun deleteLocal() {
        viewModelScope.launch {
            repository.deleteLocalCopy(mediaId)
            _notices.send(MediaNotice.DeletedLocal(1))
        }
    }

    fun deleteOnRecorder() {
        viewModelScope.launch {
            val path = repository.get(mediaId)?.recorderPath ?: return@launch
            when (val result = repository.deleteOnRecorder(listOf(path))) {
                is RecorderResult.Ok -> _notices.send(MediaNotice.DeletedOnRecorder(1))
                is RecorderResult.Failed -> _notices.send(MediaNotice.RecorderFailed(result.error))
            }
        }
    }
}

@Composable
fun ClipScreen(
    positionMs: Long,
    onBack: () -> Unit,
    onOpen: (mediaId: String, positionMs: Long) -> Unit,
    clipActions: ClipActions,
    clipExtras: ClipExtras,
    clipOverlay: ClipOverlay,
    clipDeleteTargets: ClipDeleteTargets,
    viewModel: ClipViewModel = hiltViewModel(),
) {
    val state by viewModel.item.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = LocalSnackbarHostState.current
    LaunchedEffect(viewModel) { viewModel.notices.collect { snackbar.showSnackbar(noticeText(context, it)) } }
    val title = (state as? UiState.Ready)?.data?.let { itemClock(context, it) } ?: ""
    // Deleting the last copy removes the item: leave the screen instead of showing "gone".
    var wasReady by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state is UiState.Ready) wasReady = true else if (state is UiState.Empty && wasReady) onBack()
    }

    Scaffold(topBar = { AxoTopBar(title, onBack = onBack) }) { padding ->
        StateView(state, Modifier.padding(padding), emptyText = stringResource(R.string.media_clip_gone)) { item ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                var position by rememberSaveable(item.id) { mutableLongStateOf(positionMs) }
                val file = item.localFile?.takeIf { it.isFile }
                val player = if (file != null && item.isVideo) rememberClipPlayer(Uri.fromFile(file), position) { position = it } else null
                val seekTo: (Long) -> Unit = { ms ->
                    if (player != null) {
                        player.seekTo(ms)
                        position = ms
                    }
                }
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                    when {
                        file == null -> NotOnPhone(item, viewModel)
                        player != null -> AndroidView(
                            factory = { PlayerView(it).apply { this.player = player } },
                            modifier = Modifier.fillMaxSize(),
                        )
                        else -> AsyncImage(
                            file, contentDescription = stringResource(R.string.media_image_description, item.originalFileName),
                            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                        )
                    }
                    clipOverlay(item, position)
                }
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Header(item, viewModel)
                    Actions(item, file != null, viewModel, clipDeleteTargets(item))
                    clipActions(item, position)
                    Relations(item, viewModel, onOpen)
                    clipExtras(item, position, seekTo)
                }
            }
        }
    }
}

/** Media3 player for a phone copy; reports the playback position every 250 ms and pauses when the app stops. */
@Composable
private fun rememberClipPlayer(uri: Uri, startMs: Long, onPosition: (Long) -> Unit): ExoPlayer {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(fromUri(uri))
            seekTo(startMs)
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }
    LaunchedEffect(player) {
        while (true) {
            onPosition(player.currentPosition)
            delay(250)
        }
    }
    return player
}

@Composable
private fun NotOnPhone(item: MediaItem, viewModel: ClipViewModel) {
    val transfer by viewModel.transfer.collectAsStateWithLifecycle()
    val ready by viewModel.ready.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MediaThumb(item.localThumbPath?.let(::File), placeholderFor(item.kind), Modifier.fillMaxSize())
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val t = transfer
            when {
                t != null && t.state in DownloadQueue.ACTIVE -> {
                    val label = stringResource(R.string.media_downloading)
                    CircularProgressIndicator(Modifier.semantics { contentDescription = label })
                }
                item.recorderPath != null -> FilledTonalButton(onClick = viewModel::download, enabled = ready) { Text(stringResource(R.string.media_download)) }
                else -> Text(stringResource(R.string.media_not_on_phone))
            }
        }
    }
}

/** Time (raw recorder time labelled, or the phone's), kind/category and the "reconstructed" label. */
@Composable
private fun Header(item: MediaItem, viewModel: ClipViewModel) {
    val context = LocalContext.current
    val enhancement by viewModel.enhancement.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val day = dayLabel(context, dayOf(item, item.recorderTime))
        Text("$day · ${kindLabel(context, item)}", style = MaterialTheme.typography.titleMedium)
        Text(
            if (item.recorderTime != null) stringResource(R.string.media_detail_time_value, item.recorderTime)
            else stringResource(R.string.media_phone_time, DateUtils.formatDateTime(context, item.createdAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.category == MediaCategory.EVENT) MediaTag(stringResource(R.string.media_category_event))
            if (item.isDerived) {
                MediaTag(
                    stringResource(if (item.kind == MediaKind.UPSCALED_CLIP) R.string.media_reconstructed_upscaled else R.string.media_reconstructed_enhanced),
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    content = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            if (item.recorderPath != null) MediaTag(stringResource(R.string.media_copy_recorder))
            if (item.localUri != null) MediaTag(stringResource(R.string.media_on_phone))
        }
        enhancement?.let { info ->
            Text(
                stringResource(
                    R.string.media_enhancement_info,
                    stringResource(if (info.engine == EnhanceEngine.ML) R.string.media_engine_ml else R.string.media_engine_classical),
                    info.model ?: "–",
                    info.scale,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "Teilen" and "Löschen" with explicit targets; each target has its own confirmation and none cascades. */
@Composable
private fun Actions(item: MediaItem, onPhone: Boolean, viewModel: ClipViewModel, extraTargets: List<DeleteTarget>) {
    val context = LocalContext.current
    val ready by viewModel.ready.collectAsStateWithLifecycle()
    var chooseTarget by rememberSaveable { mutableStateOf(false) }
    var pending by remember { mutableStateOf<DeleteTarget?>(null) }
    val lastCopy = item.recorderPath == null && item.driveFileId == null
    val targets = listOf(
        DeleteTarget(
            label = stringResource(R.string.media_delete_local),
            enabled = item.localUri != null,
            title = stringResource(R.string.media_delete_local_title),
            text = pluralStringResource(if (lastCopy) R.plurals.media_delete_local_last_text else R.plurals.media_delete_local_text, 1, 1),
            danger = lastCopy,
            onConfirm = viewModel::deleteLocal,
        ),
        DeleteTarget(
            label = stringResource(R.string.media_delete_recorder),
            enabled = item.recorderPath != null && ready,
            title = stringResource(R.string.media_delete_recorder_title),
            text = pluralStringResource(R.plurals.media_delete_recorder_text, 1, 1),
            danger = true,
            onConfirm = viewModel::deleteOnRecorder,
        ),
    ) + extraTargets
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { share(context, item) }, enabled = onPhone) {
            Icon(painterResource(R.drawable.ic_media_share), contentDescription = null)
            Text(stringResource(R.string.media_share), Modifier.padding(start = 8.dp))
        }
        OutlinedButton(onClick = { chooseTarget = true }) {
            Icon(painterResource(R.drawable.ic_media_delete), contentDescription = null)
            Text(stringResource(R.string.media_delete), Modifier.padding(start = 8.dp))
        }
    }
    if (chooseTarget) {
        val dismiss = { chooseTarget = false }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.media_delete_which)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.media_delete_which_text), style = MaterialTheme.typography.bodyMedium)
                    targets.forEach { target ->
                        TextButton(onClick = { dismiss(); pending = target }, enabled = target.enabled) { Text(target.label) }
                    }
                    if (item.recorderPath != null && !ready) {
                        Text(stringResource(R.string.media_delete_recorder_offline), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    pending?.let { target ->
        ConfirmDialog(
            title = target.title,
            text = target.text,
            confirmLabel = stringResource(R.string.media_delete),
            onConfirm = {
                pending = null
                target.onConfirm()
            },
            onDismiss = { pending = null },
            danger = target.danger,
        )
    }
}

/** Original of a derived item, and outputs derived from this one. */
@Composable
private fun Relations(item: MediaItem, viewModel: ClipViewModel, onOpen: (String, Long) -> Unit) {
    val parent by viewModel.parent.collectAsStateWithLifecycle()
    val children by viewModel.children.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val parentId = item.parentId
    if (parentId != null) {
        Column {
            SectionHeader(stringResource(R.string.media_original))
            val p = parent
            if (p == null) {
                ListRow(stringResource(R.string.media_original_gone))
            } else {
                ListRow(
                    "${itemClock(context, p)} · ${kindLabel(context, p)}",
                    supporting = item.parentPositionMs?.let { stringResource(R.string.media_at_position, DateUtils.formatElapsedTime(it / 1000)) },
                    onClick = { onOpen(p.id, item.parentPositionMs ?: 0) },
                )
            }
        }
    }
    if (children.isNotEmpty()) {
        Column {
            SectionHeader(stringResource(R.string.media_derived))
            ListGroup(
                children.map { child ->
                    { shape ->
                        ListRow(
                            "${itemClock(context, child)} · ${kindLabel(context, child)}",
                            supporting = stringResource(R.string.media_reconstructed_short),
                            shape = shape,
                            onClick = { onOpen(child.id, 0) },
                        )
                    }
                },
            )
        }
    }
}

private fun share(context: Context, item: MediaItem) {
    val file = item.localFile ?: return
    val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.media.files", file)
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: if (item.isVideo) "video/mp4" else "image/jpeg"
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(send, context.getString(R.string.media_share)))
    } catch (e: ActivityNotFoundException) {
        // ponytail: no share target at all is not reported; every phone has at least one.
    }
}
