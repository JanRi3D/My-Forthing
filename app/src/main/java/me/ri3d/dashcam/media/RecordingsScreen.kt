package me.ri3d.dashcam.media

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import me.ri3d.dashcam.R
import me.ri3d.dashcam.backup.BackupStateTag
import me.ri3d.dashcam.backup.DriveLibraryViewModel
import me.ri3d.dashcam.core.ui.AxoTopBar
import me.ri3d.dashcam.core.ui.ConfirmDialog
import me.ri3d.dashcam.core.ui.LocalSnackbarHostState
import me.ri3d.dashcam.core.ui.UiText
import me.ri3d.dashcam.core.ui.listRowShape
import me.ri3d.dashcam.core.log.Log
import me.ri3d.dashcam.dashcam.RecorderConnectionState
import me.ri3d.dashcam.dashcam.errorText
import me.ri3d.dashcam.drive.DriveAuthState
import me.ri3d.dashcam.drive.DriveStatusCard
import java.io.File
import java.time.LocalDate

/** Phase 4 slot in selection mode (e.g. "Sichern"): the selected items and a way to leave selection mode. */
typealias SelectionActions = @Composable RowScope.(items: List<MediaItem>, clearSelection: () -> Unit) -> Unit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(
    initialTab: RecordingsTab,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onOpen: (mediaId: String) -> Unit,
    onRawList: (RecordingsTab) -> Unit,
    selectionActions: SelectionActions,
    onConnectDrive: () -> Unit = {},
    viewModel: RecordingsViewModel = hiltViewModel(),
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    LaunchedEffect(tab) { viewModel.show(tab) }
    val firstContent = rememberFirstContent { "tab ${tab.name}" }
    val saveable = rememberSaveableStateHolder()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val ready = connection is RecorderConnectionState.Ready
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val selectedItems by viewModel.selectedItems.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()
    var showTransfers by rememberSaveable { mutableStateOf(false) }
    var details by remember { mutableStateOf<MediaItem?>(null) }
    var confirmRecorderDelete by remember { mutableStateOf<Set<String>?>(null) }
    var confirmLocalDelete by remember { mutableStateOf<Set<String>?>(null) }
    val download = rememberDownload(viewModel::download)
    val downloadFromDrive = rememberDownload(viewModel::downloadFromDrive)
    val drive: DriveLibraryViewModel = hiltViewModel()
    val driveAuth by drive.authState.collectAsStateWithLifecycle()
    NoticeSnackbars(viewModel)
    BackHandler(enabled = selection.isNotEmpty()) { viewModel.clearSelection() }

    Scaffold(
        topBar = {
            if (selection.isEmpty()) {
                AxoTopBar(stringResource(R.string.home_tile_recordings), onBack = onBack) {
                    TransfersButton(transfers.values.count { it.state in DownloadQueue.ACTIVE }) { showTransfers = true }
                }
            } else {
                TopAppBar(
                    title = { Text(pluralStringResource(R.plurals.media_selected, selection.size, selection.size)) },
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(painterResource(R.drawable.ic_media_close), stringResource(R.string.media_selection_end))
                        }
                    },
                    actions = {
                        when (tab) {
                            RecordingsTab.PHONE -> IconButton(onClick = { confirmLocalDelete = selection }) {
                                Icon(painterResource(R.drawable.ic_media_delete), stringResource(R.string.media_delete_local))
                            }
                            RecordingsTab.DRIVE -> IconButton(onClick = { downloadFromDrive(selection) }, enabled = driveAuth is DriveAuthState.Connected) {
                                Icon(painterResource(R.drawable.ic_media_download), stringResource(R.string.media_drive_download))
                            }
                            else -> {
                                IconButton(onClick = { download(selection) }, enabled = ready) {
                                    Icon(painterResource(R.drawable.ic_media_download), stringResource(R.string.media_download))
                                }
                                IconButton(onClick = { confirmRecorderDelete = selection }, enabled = ready) {
                                    Icon(painterResource(R.drawable.ic_media_delete), stringResource(R.string.media_delete_recorder))
                                }
                            }
                        }
                        selectionActions(selectedItems, viewModel::clearSelection)
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            // Five tabs: scrollable, so every label stays whole on narrow phones.
            PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 16.dp) {
                RecordingsTab.entries.forEach { t ->
                    Tab(selected = t == tab, onClick = { tab = t }, text = { Text(stringResource(t.label), maxLines = 1) })
                }
            }
            val type = tab.type
            val onTap: (MediaItem) -> Unit = { item ->
                when {
                    selection.isNotEmpty() -> viewModel.toggle(item.id)
                    item.localUri != null || tab == RecordingsTab.DRIVE -> onOpen(item.id) // the clip screen shows the Drive copy
                    else -> details = item
                }
            }
            // Each tab keeps its own scroll position (saved state) while another tab is shown.
            saveable.SaveableStateProvider(tab.name) {
                when {
                    tab == RecordingsTab.DRIVE -> DriveListing(viewModel, drive, selection, onTap, downloadFromDrive, onConnectDrive, firstContent)
                    type == null -> LocalLibrary(viewModel, selection, onTap, firstContent)
                    else -> RecorderListing(
                        type, grid = tab == RecordingsTab.USER, ready, viewModel, selection, onTap, download, onConnect,
                        onRawList = { onRawList(tab) }, onFirstContent = firstContent,
                    )
                }
            }
        }
    }

    if (showTransfers) TransfersSheet(transfers.values.toList(), viewModel, onOpen, download, downloadFromDrive, onDismiss = { showTransfers = false })
    details?.let { item ->
        DetailsSheet(
            item, transfers[item.id], ready,
            onDownload = { download(listOf(item.id)) },
            onOpen = { onOpen(item.id) },
            onDeleteOnRecorder = { confirmRecorderDelete = setOf(item.id) },
            onDismiss = { details = null },
        )
    }
    confirmRecorderDelete?.let { ids ->
        ConfirmDialog(
            title = stringResource(R.string.media_delete_recorder_title),
            text = pluralStringResource(R.plurals.media_delete_recorder_text, ids.size, ids.size),
            confirmLabel = stringResource(R.string.media_delete),
            onConfirm = {
                confirmRecorderDelete = null
                details = null
                viewModel.deleteOnRecorder(ids)
            },
            onDismiss = { confirmRecorderDelete = null },
            danger = true,
        )
    }
    confirmLocalDelete?.let { ids ->
        val lastCopy = selectedItems.any { it.recorderPath == null && it.driveFileId == null }
        ConfirmDialog(
            title = stringResource(R.string.media_delete_local_title),
            text = pluralStringResource(if (lastCopy) R.plurals.media_delete_local_last_text else R.plurals.media_delete_local_text, ids.size, ids.size),
            confirmLabel = stringResource(R.string.media_delete),
            onConfirm = {
                confirmLocalDelete = null
                viewModel.deleteLocal(ids)
            },
            onDismiss = { confirmLocalDelete = null },
            danger = lastCopy,
        )
    }
}

/** The raw 4100 list of one type with the recorder's counts (reached from the SD card screen). */
@Composable
fun SdFilesScreen(
    category: String,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onOpen: (mediaId: String) -> Unit,
    viewModel: RecordingsViewModel = hiltViewModel(),
) {
    val tab = RecordingsTab.of(category).takeIf { it.type != null } ?: RecordingsTab.NORMAL
    val type = tab.type!!
    LaunchedEffect(tab) { viewModel.show(tab) }
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val ready = connection is RecorderConnectionState.Ready
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()
    var details by remember { mutableStateOf<MediaItem?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val download = rememberDownload(viewModel::download)
    NoticeSnackbars(viewModel)

    Scaffold(topBar = { AxoTopBar(stringResource(R.string.media_sd_files_title, tab.name), onBack = onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            val onTap: (MediaItem) -> Unit = { if (it.localUri != null) onOpen(it.id) else details = it }
            RecorderListing(type, grid = false, ready, viewModel, emptySet(), onTap, download, onConnect, onRawList = null)
        }
    }
    details?.let { item ->
        DetailsSheet(
            item, transfers[item.id], ready,
            onDownload = { download(listOf(item.id)) },
            onOpen = { onOpen(item.id) },
            onDeleteOnRecorder = { confirmDelete = item.id },
            onDismiss = { details = null },
        )
    }
    confirmDelete?.let { id ->
        ConfirmDialog(
            title = stringResource(R.string.media_delete_recorder_title),
            text = pluralStringResource(R.plurals.media_delete_recorder_text, 1, 1),
            confirmLabel = stringResource(R.string.media_delete),
            onConfirm = {
                confirmDelete = null
                details = null
                viewModel.deleteOnRecorder(listOf(id))
            },
            onDismiss = { confirmDelete = null },
            danger = true,
        )
    }
}

/**
 * Debug measurement (docs/features/media.md, "Snappiness"): logs once per screen how long the first rows took from the
 * screen's first composition (about one frame after the tap on "Aufnahmen") to the frame after they were composed.
 */
@Composable
private fun rememberFirstContent(label: () -> String): () -> Unit {
    val openedAt = remember { SystemClock.uptimeMillis() }
    var reported by remember { mutableStateOf(false) }
    return {
        if (!reported) {
            reported = true
            Log.d(PERF_TAG, "first content after ${SystemClock.uptimeMillis() - openedAt} ms (${label()})")
        }
    }
}

/** Calls [onFirstContent] once the frame with the content has been produced. */
@Composable
private fun ReportFirstContent(onFirstContent: () -> Unit) {
    LaunchedEffect(Unit) {
        withFrameMillis { }
        onFirstContent()
    }
}

@Composable
private fun NoticeSnackbars(viewModel: RecordingsViewModel) {
    val snackbar = LocalSnackbarHostState.current
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.notices.collect { snackbar.showSnackbar(noticeText(context, it)) } }
}

/** Download action ([download]: from the recorder or Drive) that first asks for the notification permission (Android 13+); downloads run either way. */
@Composable
private fun rememberDownload(download: (Collection<String>) -> Unit): (Collection<String>) -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return { ids ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        download(ids)
    }
}

@Composable
private fun TransfersButton(active: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        BadgedBox(badge = { if (active > 0) Badge { Text(active.toString()) } }) {
            Icon(painterResource(R.drawable.ic_media_transfers), pluralStringResource(R.plurals.media_transfers_button, active, active))
        }
    }
}

/**
 * One recorder type as the library knows it (also offline): "Stand" header, day groups newest first. While connected
 * the browser lists the recorder again page by page and the rows change in place (keys are media ids, so the scroll
 * position stays). Reports the visible rows and scrolling for the thumbnail prefetch.
 */
@Composable
private fun RecorderListing(
    type: Int,
    grid: Boolean,
    ready: Boolean,
    viewModel: RecordingsViewModel,
    selection: Set<String>,
    onTap: (MediaItem) -> Unit,
    download: (Collection<String>) -> Unit,
    onConnect: () -> Unit,
    onRawList: (() -> Unit)?,
    onFirstContent: () -> Unit = {},
) {
    val state by viewModel.browser(type).collectAsStateWithLifecycle()
    val loaded by viewModel.entries(type).collectAsStateWithLifecycle()
    val listedAt by remember(type) { viewModel.listedAt(type) }.collectAsStateWithLifecycle(null)
    val entries = loaded ?: return // the library answers within a frame or two; nothing to show meanwhile
    val listing = state.listing
    if (entries.isEmpty()) {
        val error = state.error
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            when {
                !ready -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NotConnectedCard(onConnect)
                    Text(stringResource(R.string.media_offline_hint), style = MaterialTheme.typography.bodyMedium)
                }
                error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(errorText(error), color = MaterialTheme.colorScheme.error)
                    FilledTonalButton(onClick = { viewModel.retry(type) }) { Text(stringResource(R.string.action_retry)) }
                }
                listing.end != null -> Text(stringResource(R.string.media_empty), style = MaterialTheme.typography.bodyLarge)
                else -> {
                    val loading = stringResource(R.string.state_view_loading)
                    CircularProgressIndicator(Modifier.semantics { contentDescription = loading })
                }
            }
        }
        return
    }
    ReportFirstContent(onFirstContent)
    val context = LocalContext.current
    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState, type) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.filter { it.contentType == ENTRY }.map { it.key as String } }
            .distinctUntilChanged()
            .collect { viewModel.visible(type, it) }
    }
    LaunchedEffect(gridState) { snapshotFlow { gridState.isScrollInProgress }.collect(viewModel::scrolling) }
    DisposableEffect(gridState) { onDispose { viewModel.scrolling(false) } }
    LazyVerticalGrid(
        columns = GridCells.Fixed(if (grid) 3 else 1),
        state = gridState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        fullWidth("header", HEADER) {
            ListingHeader(type, entries.size, listing, listedAt, ready, state.refreshing, { viewModel.refresh(type) }, onRawList, onConnect)
        }
        // Newest first, consecutive entries of one day form a group.
        var day: LocalDate? = null
        val dayKeys = HashSet<String>()
        entries.forEachIndexed { index, entry ->
            val entryDay = dayOf(entry.item, entry.item.recorderTime)
            if (index == 0 || entryDay != day) {
                day = entryDay
                val key = "day-$entryDay".let { if (dayKeys.add(it)) it else "$it-$index" } // an unparsable time may repeat a day
                fullWidth(key, DAY) { DayHeader(dayLabel(context, entryDay)) }
            }
            item(key = entry.item.id, contentType = ENTRY) {
                if (grid) {
                    PhotoCell(entry, viewModel, selection, onTap)
                } else {
                    RecorderRow(entry, ready, viewModel, selection, onTap, download)
                }
            }
        }
        fullWidth("footer", FOOTER) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                val error = state.error
                when {
                    error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(errorText(error), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { viewModel.retry(type) }) { Text(stringResource(R.string.action_retry)) }
                    }
                    listing.end == ListingEnd.STOPPED -> Text(stringResource(R.string.media_listing_stopped), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * "Stand: <time> · wird aktualisiert…" while connected and listing, "· nicht verbunden" offline; the recorder's totals
 * once a page of this session arrived, else how many files the list holds. Fixed line count: no jump when it changes.
 */
@Composable
private fun ListingHeader(
    type: Int,
    count: Int,
    listing: Listing,
    listedAt: Long?,
    ready: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onRawList: (() -> Unit)?,
    onConnect: () -> Unit,
) {
    val context = LocalContext.current
    val stand = listedAt?.let { DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_NUMERIC_DATE) } ?: "–"
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(
            stringResource(
                when {
                    !ready -> R.string.media_stand_offline
                    refreshing -> R.string.media_stand_refreshing
                    else -> R.string.media_stand
                },
                stand,
            ),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            if (ready && listing.totalFileNum != null) {
                stringResource(R.string.media_totals, listing.totalFileNum.toString(), listing.totalFileSize?.toString() ?: "–", type)
            } else {
                pluralStringResource(R.plurals.media_known_files, count, count)
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Text(stringResource(R.string.media_time_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
            if (ready) {
                TextButton(onClick = onRefresh, enabled = !refreshing) { Text(stringResource(R.string.dashcam_refresh)) }
            } else {
                TextButton(onClick = onConnect) { Text(stringResource(R.string.dashcam_open_connection)) }
            }
            if (onRawList != null) TextButton(onClick = onRawList) { Text(stringResource(R.string.media_raw_list)) }
        }
    }
}

private fun LazyGridScope.fullWidth(key: String, contentType: String, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = contentType) { content() }

@Composable
private fun DayHeader(text: String) {
    Text(
        text,
        Modifier.padding(top = 16.dp, bottom = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** Accessibility labels of a row: open, toggle in selection mode, long press to select. */
private class RowLabels(val open: String, val toggle: String, val select: String)

@Composable
private fun rowLabels() = RowLabels(
    stringResource(R.string.media_open), stringResource(R.string.media_select_toggle), stringResource(R.string.media_select),
)

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.selectable(item: MediaItem, selection: Set<String>, onTap: (MediaItem) -> Unit, onToggle: (String) -> Unit, labels: RowLabels) =
    semantics { if (selection.isNotEmpty()) selected = item.id in selection }
        .combinedClickable(
            onClickLabel = if (selection.isNotEmpty()) labels.toggle else labels.open,
            onLongClickLabel = labels.select,
            onLongClick = { onToggle(item.id) },
            onClick = { onTap(item) },
        )

/** A recorder file, or (Drive tab) a Drive copy: [ready] enables [download], [title] defaults to the recorder clock. */
@Composable
private fun RecorderRow(
    entry: RecorderEntry,
    ready: Boolean,
    viewModel: RecordingsViewModel,
    selection: Set<String>,
    onTap: (MediaItem) -> Unit,
    download: (Collection<String>) -> Unit,
    title: String = recorderClock(entry.item.recorderTime) ?: "–",
    @StringRes downloadLabel: Int = R.string.media_download_named,
    showBackupState: Boolean = true,
) {
    val item = entry.item
    Row(
        Modifier
            .fillMaxWidth()
            .clip(listRowShape(1, 3))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .selectable(item, selection, onTap, viewModel::toggle, rowLabels())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box {
            MediaThumb(
                entry.thumb, placeholderFor(item.kind),
                Modifier.size(width = 96.dp, height = 54.dp).clip(MaterialTheme.shapes.small), viewModel.http.imageLoader,
            )
            SelectionMark(item, selection, Modifier.align(Alignment.TopStart))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(item.originalFileName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (item.category == MediaCategory.EVENT) MediaTag(stringResource(R.string.media_category_event))
                if (item.isDerived) MediaTag(stringResource(R.string.media_reconstructed_short))
                if (item.localUri != null) MediaTag(stringResource(R.string.media_on_phone))
                if (showBackupState) BackupStateTag(item)
            }
        }
        TransferControl(entry, ready, download, downloadLabel)
    }
}

@Composable
private fun PhotoCell(entry: RecorderEntry, viewModel: RecordingsViewModel, selection: Set<String>, onTap: (MediaItem) -> Unit) {
    val item = entry.item
    val clock = recorderClock(item.recorderTime) ?: "–"
    val description = stringResource(R.string.media_photo_description, clock, item.originalFileName)
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.extraSmall)
            .semantics { contentDescription = description }
            .selectable(item, selection, onTap, viewModel::toggle, rowLabels()),
    ) {
        MediaThumb(entry.thumb, R.drawable.ic_media_photo, Modifier.fillMaxSize(), viewModel.http.imageLoader)
        Text(
            clock,
            Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 2.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
        )
        SelectionMark(item, selection, Modifier.align(Alignment.TopStart))
        Box(Modifier.align(Alignment.TopEnd)) {
            val transfer = entry.transfer
            when {
                item.localUri != null -> Icon(
                    painterResource(R.drawable.ic_phone), stringResource(R.string.media_on_phone),
                    Modifier.padding(4.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape).padding(4.dp).size(16.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                transfer != null && transfer.state in DownloadQueue.ACTIVE -> CircularProgressIndicator(Modifier.padding(4.dp).size(20.dp))
            }
        }
    }
}

@Composable
private fun SelectionMark(item: MediaItem, selection: Set<String>, modifier: Modifier) {
    if (selection.isEmpty()) return
    Icon(
        painterResource(if (item.id in selection) R.drawable.ic_media_selected else R.drawable.ic_media_unselected),
        contentDescription = null,
        modifier = modifier.padding(4.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
        tint = MaterialTheme.colorScheme.primary,
    )
}

/** Download button (only with a session / Drive connection), running transfer, or "auf dem Handy". 48 dp in every state. */
@Composable
private fun TransferControl(entry: RecorderEntry, ready: Boolean, download: (Collection<String>) -> Unit, @StringRes downloadLabel: Int) {
    val item = entry.item
    val transfer = entry.transfer
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        when {
            item.localUri != null -> Icon(painterResource(R.drawable.ic_check), stringResource(R.string.media_on_phone), tint = MaterialTheme.colorScheme.primary)
            transfer != null && transfer.state in DownloadQueue.ACTIVE -> {
                val label = stringResource(R.string.media_downloading)
                val total = transfer.totalBytes
                if (total != null && total > 0 && transfer.state == TransferState.RUNNING) {
                    CircularProgressIndicator(progress = { transfer.bytes.toFloat() / total }, modifier = Modifier.size(28.dp).semantics { contentDescription = label })
                } else {
                    CircularProgressIndicator(Modifier.size(28.dp).semantics { contentDescription = label })
                }
            }
            else -> IconButton(onClick = { download(listOf(item.id)) }, enabled = ready) {
                Icon(painterResource(R.drawable.ic_media_download), stringResource(downloadLabel, item.originalFileName))
            }
        }
    }
}

/** Downloaded files, screenshots and enhanced outputs: everything with a phone copy (works offline). */
@Composable
private fun LocalLibrary(viewModel: RecordingsViewModel, selection: Set<String>, onTap: (MediaItem) -> Unit, onFirstContent: () -> Unit) {
    val loaded by viewModel.local.collectAsStateWithLifecycle()
    val items = loaded ?: return
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.media_local_empty), style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    ReportFirstContent(onFirstContent)
    val context = LocalContext.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(1),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        var day: LocalDate? = null
        val dayKeys = HashSet<String>()
        items.forEachIndexed { index, item ->
            val itemDay = dayOf(item, item.recorderTime)
            if (index == 0 || itemDay != day) {
                day = itemDay
                val key = "day-$itemDay".let { if (dayKeys.add(it)) it else "$it-$index" }
                fullWidth(key, DAY) { DayHeader(dayLabel(context, itemDay)) }
            }
            item(key = item.id, contentType = LOCAL) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(listRowShape(1, 3))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .selectable(item, selection, onTap, viewModel::toggle, rowLabels())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box {
                        MediaThumb(item.localThumbPath?.let(::File), placeholderFor(item.kind), Modifier.size(width = 96.dp, height = 54.dp).clip(MaterialTheme.shapes.small))
                        SelectionMark(item, selection, Modifier.align(Alignment.TopStart))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(itemClock(context, item), style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOfNotNull(kindLabel(context, item), item.localSizeBytes?.let { Formatter.formatShortFileSize(context, it) }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (item.isDerived) MediaTag(stringResource(R.string.media_reconstructed_short))
                        BackupStateTag(item)
                    }
                }
            }
        }
    }
}

/**
 * Drive tab (drive-restore): everything backed up in the connected Drive account, newest first, day groups, with
 * "Stand" of the last import and "Aktualisieren"; without a connection the Drive card leads to the Google Drive screen
 * (rows already known stay below it). Opening a row shows the clip screen with the Drive copy.
 */
@Composable
private fun DriveListing(
    viewModel: RecordingsViewModel,
    drive: DriveLibraryViewModel,
    selection: Set<String>,
    onTap: (MediaItem) -> Unit,
    download: (Collection<String>) -> Unit,
    onConnectDrive: () -> Unit,
    onFirstContent: () -> Unit,
) {
    val auth by drive.authState.collectAsStateWithLifecycle()
    val loaded by drive.items.collectAsStateWithLifecycle()
    val importing by drive.importing.collectAsStateWithLifecycle()
    val lastImport by drive.lastImport.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val resources = LocalResources.current
    LaunchedEffect(drive) {
        drive.errors.collect { error ->
            snackbar.showSnackbar(
                when (error) {
                    is UiText.Res -> resources.getString(error.id, *error.args.toTypedArray())
                    is UiText.Dynamic -> error.text
                },
            )
        }
    }
    val connected = auth is DriveAuthState.Connected
    LaunchedEffect(connected) { drive.shown() }
    val items = loaded ?: return
    ReportFirstContent(onFirstContent)
    val context = LocalContext.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(1),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        fullWidth("header", HEADER) {
            if (connected) {
                DriveHeader(lastImport, importing, items.size, onRefresh = drive::refresh)
            } else {
                Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DriveStatusCard(auth, quota = null, notConnectedText = stringResource(R.string.media_drive_not_connected))
                    Button(onClick = onConnectDrive, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(if (auth is DriveAuthState.NeedsReconnect) R.string.drive_reconnect else R.string.drive_connect))
                    }
                }
            }
        }
        if (items.isEmpty() && connected) {
            fullWidth("empty", FOOTER) {
                Text(
                    stringResource(if (importing) R.string.media_drive_loading else R.string.media_drive_empty),
                    Modifier.padding(vertical = 24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        var day: LocalDate? = null
        val dayKeys = HashSet<String>()
        items.forEachIndexed { index, item ->
            val itemDay = dayOf(item, item.recorderTime)
            if (index == 0 || itemDay != day) {
                day = itemDay
                val key = "day-$itemDay".let { if (dayKeys.add(it)) it else "$it-$index" }
                fullWidth(key, DAY) { DayHeader(dayLabel(context, itemDay)) }
            }
            item(key = item.id, contentType = ENTRY) {
                RecorderRow(
                    RecorderEntry(item, transfers[item.id], drive.thumb(item)), connected, viewModel, selection, onTap, download,
                    title = itemClock(context, item), downloadLabel = R.string.media_drive_download_named,
                    showBackupState = false, // every row here is in Drive
                )
            }
        }
    }
}

/** "Stand: <last import> · wird aktualisiert…", how many backups, and "Aktualisieren" (progress while it runs). */
@Composable
private fun DriveHeader(lastImport: Long?, importing: Boolean, count: Int, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val stand = lastImport?.let { DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_NUMERIC_DATE) } ?: "–"
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(if (importing) R.string.media_stand_refreshing else R.string.media_stand, stand),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(pluralStringResource(R.plurals.media_drive_count, count, count), style = MaterialTheme.typography.bodySmall)
        }
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (importing) {
                val label = stringResource(R.string.media_drive_refreshing)
                CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = label })
            } else {
                IconButton(onClick = onRefresh) { Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.media_drive_refresh)) }
            }
        }
    }
}

/** Raw recorder time of day, else the phone's creation time. */
fun itemClock(context: android.content.Context, item: MediaItem): String =
    recorderClock(item.recorderTime) ?: DateUtils.formatDateTime(context, item.createdAt, DateUtils.FORMAT_SHOW_TIME)

/** Raw values of one recorder file (the report's names) and its actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsSheet(
    item: MediaItem,
    transfer: TransferProgress?,
    ready: Boolean,
    onDownload: () -> Unit,
    onOpen: () -> Unit,
    onDeleteOnRecorder: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(item.originalFileName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Detail(stringResource(R.string.media_detail_type), stringResource(R.string.media_detail_type_value, item.recorderType?.toString() ?: "–", kindLabel(context, item)))
            Detail(stringResource(R.string.media_detail_time), stringResource(R.string.media_detail_time_value, item.recorderTime ?: "–"))
            Detail(
                stringResource(R.string.media_detail_time_guess),
                item.recorderTimeEpochGuess?.let { DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR) }
                    ?: stringResource(R.string.media_detail_time_unreadable),
            )
            Detail(stringResource(R.string.media_detail_path), item.recorderPath ?: "–")
            Detail(stringResource(R.string.media_detail_thumb), item.recorderThumbPath ?: "–")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    item.localUri != null -> FilledTonalButton(onClick = onOpen) { Text(stringResource(R.string.media_open)) }
                    transfer != null && transfer.state in DownloadQueue.ACTIVE -> Text(stringResource(R.string.media_downloading))
                    else -> FilledTonalButton(onClick = onDownload, enabled = ready) { Text(stringResource(R.string.media_download)) }
                }
                OutlinedButton(onClick = onDeleteOnRecorder, enabled = ready && item.recorderPath != null) { Text(stringResource(R.string.media_delete_recorder)) }
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Every download WorkManager knows about (finished ones until it prunes them). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransfersSheet(
    transfers: List<TransferProgress>,
    viewModel: RecordingsViewModel,
    onOpen: (String) -> Unit,
    download: (Collection<String>) -> Unit,
    downloadFromDrive: (Collection<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.media_transfers_title),
            Modifier.padding(horizontal = 24.dp).semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        if (transfers.isEmpty()) {
            Text(stringResource(R.string.media_transfers_empty), Modifier.padding(24.dp))
        }
        LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(transfers.sortedBy { it.state.ordinal }, key = { it.mediaId }, contentType = { "transfer" }) { t ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val name = t.name.ifBlank { "–" }
                    Text(if (t.fromDrive) stringResource(R.string.media_transfer_from_drive, name) else name, style = MaterialTheme.typography.bodyLarge)
                    // Second line of a failure: HTTP status and the raw detail (Content-Type or "Exception: message").
                    val reason = listOfNotNull(t.httpCode?.let { stringResource(R.string.media_failure_http_code, it) }, t.detail)
                        .joinToString(" · ").let { if (it.isEmpty()) "" else "\n" + it }
                    val failure = t.failure
                    Text(
                        when (t.state) {
                            TransferState.QUEUED -> stringResource(R.string.media_transfer_queued)
                            TransferState.RUNNING -> transferText(context, t.bytes, t.totalBytes, t.bytesPerSecond, t.retryInSeconds)
                            TransferState.WAITING -> if (failure == null) {
                                stringResource(if (t.fromDrive) R.string.media_transfer_waiting_network else R.string.media_transfer_waiting)
                            } else {
                                stringResource(R.string.media_transfer_retrying, stringResource(failure.text)) + reason
                            }
                            TransferState.DONE -> stringResource(R.string.media_transfer_done)
                            TransferState.FAILED -> stringResource(failure?.text ?: R.string.media_failure_network) + reason
                            TransferState.CANCELLED -> stringResource(R.string.media_transfer_cancelled)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val total = t.totalBytes
                    if (t.state == TransferState.RUNNING && total != null && total > 0) {
                        LinearProgressIndicator(progress = { t.bytes.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                    }
                    Row {
                        when (t.state) {
                            TransferState.QUEUED, TransferState.RUNNING, TransferState.WAITING ->
                                TextButton(onClick = { viewModel.cancelTransfer(t) }) { Text(stringResource(R.string.action_cancel)) }
                            TransferState.FAILED, TransferState.CANCELLED ->
                                TextButton(onClick = { (if (t.fromDrive) downloadFromDrive else download)(listOf(t.mediaId)) }) {
                                    Text(stringResource(R.string.action_retry))
                                }
                            TransferState.DONE -> TextButton(onClick = { onDismiss(); onOpen(t.mediaId) }) { Text(stringResource(R.string.media_open)) }
                        }
                    }
                }
            }
        }
    }
}

// Lazy grid content types: rows of one type share compositions when they scroll.
private const val HEADER = "header"
private const val DAY = "day"
private const val ENTRY = "entry"
private const val LOCAL = "local"
private const val FOOTER = "footer"
private const val PERF_TAG = "RecordingsPerf"
