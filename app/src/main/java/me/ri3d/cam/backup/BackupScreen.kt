package me.ri3d.cam.backup

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ri3d.cam.R
import me.ri3d.cam.core.data.PreferencesRepository
import me.ri3d.cam.core.log.Log
import me.ri3d.cam.core.model.AppPreferences
import me.ri3d.cam.core.model.BackupMode
import me.ri3d.cam.core.navigation.Backup
import me.ri3d.cam.core.navigation.DriveAccount
import me.ri3d.cam.core.ui.AxoTopBar
import me.ri3d.cam.core.ui.ListGroup
import me.ri3d.cam.core.ui.LocalSnackbarHostState
import me.ri3d.cam.core.ui.SectionHeader
import me.ri3d.cam.core.ui.UiState
import me.ri3d.cam.core.ui.UiText
import me.ri3d.cam.core.ui.asString
import me.ri3d.cam.drive.DriveApi
import me.ri3d.cam.drive.DriveAuth
import me.ri3d.cam.drive.DriveAuthState
import me.ri3d.cam.drive.DriveError
import me.ri3d.cam.drive.DriveQuota
import me.ri3d.cam.drive.DriveStatusCard
import me.ri3d.cam.drive.driveMessage
import me.ri3d.cam.media.BackupState
import me.ri3d.cam.media.DeleteTarget
import me.ri3d.cam.media.MediaItem
import me.ri3d.cam.media.MediaRepository
import me.ri3d.cam.media.MediaTag
import me.ri3d.cam.media.transferText
import javax.inject.Inject

fun NavGraphBuilder.backupGraph(navController: NavController) {
    composable<Backup> {
        BackupScreen(onBack = { navController.navigateUp() }, onDrive = { navController.navigate(DriveAccount) { launchSingleTop = true } })
    }
}

/** Settings → "Sicherung": Drive state, rules, conditions, plate metadata, queue and Drive check. */
@Composable
fun BackupScreen(onBack: () -> Unit, onDrive: () -> Unit, viewModel: BackupViewModel = hiltViewModel()) {
    val auth by viewModel.authState.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val storageFull by viewModel.storageFull.collectAsStateWithLifecycle()
    val quota by viewModel.quota.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarHostState.current
    val resources = LocalResources.current
    LaunchedEffect(auth, storageFull) { viewModel.loadQuota() }

    Scaffold(topBar = { AxoTopBar(stringResource(R.string.backup_title), onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DriveStatusCard(auth, quota)
            when (auth) {
                DriveAuthState.NotConnected -> WideButton(R.string.drive_connect, onDrive)
                is DriveAuthState.NeedsReconnect -> WideButton(R.string.drive_reconnect, onDrive)
                is DriveAuthState.Connected -> OutlinedButton(onClick = onDrive, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.backup_drive_manage))
                }
            }
            PauseNote(auth, storageFull, queue.items.any { it.backupState in BackupRules.PENDING }, quota, viewModel::resume)

            SectionHeader(stringResource(R.string.backup_section_mode))
            ListGroup(
                BackupMode.entries.map { mode ->
                    { shape ->
                        ChoiceRow(stringResource(mode.title), stringResource(mode.text), shape, prefs.backupMode == mode, radio = true) { viewModel.setMode(mode) }
                    }
                },
                Modifier.selectableGroup(),
            )
            Note(stringResource(R.string.backup_phone_only_note))
            OutlinedButton(
                onClick = { scope.launch { viewModel.checkNow() } },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                enabled = auth is DriveAuthState.Connected,
            ) { Text(stringResource(R.string.backup_check_now)) }

            SectionHeader(stringResource(R.string.backup_section_conditions))
            ListGroup(
                listOf(
                    { shape ->
                        ChoiceRow(
                            stringResource(R.string.backup_wifi_only), stringResource(R.string.backup_wifi_only_text), shape, prefs.backupRequireInternetWifi,
                        ) { viewModel.update { it.copy(backupRequireInternetWifi = !it.backupRequireInternetWifi) } }
                    },
                    { shape ->
                        ChoiceRow(
                            stringResource(R.string.backup_mobile_data),
                            stringResource(if (prefs.backupRequireInternetWifi) R.string.backup_mobile_data_disabled else R.string.backup_mobile_data_text),
                            shape,
                            prefs.backupOnMobileData && !prefs.backupRequireInternetWifi,
                            enabled = !prefs.backupRequireInternetWifi,
                        ) { viewModel.update { it.copy(backupOnMobileData = !it.backupOnMobileData) } }
                    },
                ),
            )

            SectionHeader(stringResource(R.string.backup_section_plates))
            ListGroup(
                listOf { shape ->
                    ChoiceRow(stringResource(R.string.backup_plates), stringResource(R.string.backup_plates_text), shape, prefs.backupIncludePlateMetadata) {
                        viewModel.update { it.copy(backupIncludePlateMetadata = !it.backupIncludePlateMetadata) }
                    }
                },
            )

            SectionHeader(stringResource(R.string.backup_section_queue))
            if (queue.items.isEmpty()) {
                Note(stringResource(R.string.backup_queue_empty))
            } else {
                ListGroup(queue.items.map { item -> { shape -> QueueRow(item, progress[item.id], prefs, shape, viewModel) } })
            }
            Note(pluralStringResource(R.plurals.backup_done_count, queue.done, queue.done) + "\n" + stringResource(R.string.backup_done_meaning))

            SectionHeader(stringResource(R.string.drive_title))
            Note(stringResource(R.string.backup_verify_text))
            if (busy) {
                val label = stringResource(R.string.backup_verifying)
                LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = label })
            }
            OutlinedButton(
                onClick = {
                    busy = true
                    scope.launch {
                        val text = viewModel.verify().fold(
                            { missing ->
                                if (missing == 0) resources.getString(R.string.backup_verify_ok)
                                else resources.getQuantityString(R.plurals.backup_verify_missing, missing, missing)
                            },
                            { resources.getString(R.string.backup_verify_failed, it.driveMessage().resolve(resources)) },
                        )
                        busy = false
                        snackbar.showSnackbar(text)
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                enabled = !busy && auth is DriveAuthState.Connected,
            ) { Text(stringResource(R.string.backup_verify)) }
        }
    }
}

private val BackupMode.title: Int
    get() = when (this) {
        BackupMode.MANUAL -> R.string.backup_mode_manual
        BackupMode.INCIDENTS -> R.string.backup_mode_incidents
        BackupMode.ALL -> R.string.backup_mode_all
    }

private val BackupMode.text: Int
    get() = when (this) {
        BackupMode.MANUAL -> R.string.backup_mode_manual_text
        BackupMode.INCIDENTS -> R.string.backup_mode_incidents_text
        BackupMode.ALL -> R.string.backup_mode_all_text
    }

/** Why nothing uploads right now, if anything waits. */
@Composable
private fun PauseNote(auth: DriveAuthState, storageFull: Boolean, waiting: Boolean, quota: UiState<DriveQuota>?, onResume: () -> Unit) {
    val text = when {
        auth is DriveAuthState.NeedsReconnect -> stringResource(R.string.backup_paused_reconnect)
        auth is DriveAuthState.NotConnected && waiting -> stringResource(R.string.backup_paused_not_connected)
        storageFull -> {
            val limit = (quota as? UiState.Ready)?.data?.limit
            val context = LocalContext.current
            stringResource(R.string.backup_paused_storage) + if (limit != null && quota is UiState.Ready) {
                " " + stringResource(
                    R.string.backup_paused_storage_quota,
                    android.text.format.Formatter.formatShortFileSize(context, quota.data.usage),
                    android.text.format.Formatter.formatShortFileSize(context, limit),
                )
            } else {
                ""
            }
        }
        else -> return
    }
    Surface(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.backup_paused_title), style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (storageFull && auth is DriveAuthState.Connected) {
                TextButton(onClick = onResume) { Text(stringResource(R.string.backup_resume)) }
            }
        }
    }
}

@Composable
private fun QueueRow(item: MediaItem, progress: BackupProgress?, prefs: AppPreferences, shape: Shape, viewModel: BackupViewModel) {
    val context = LocalContext.current
    val failed = item.backupState == BackupState.FAILED
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(item.originalFileName, style = MaterialTheme.typography.bodyLarge)
        Text(
            when {
                failed -> backupErrorText(item.backupError).asString()
                progress?.running == true && progress.total > 0 -> transferText(context, progress.bytes, progress.total)
                progress?.running == true -> stringResource(R.string.backup_uploading)
                progress != null -> stringResource(
                    when {
                        prefs.backupRequireInternetWifi -> R.string.backup_waiting_wifi
                        !prefs.backupOnMobileData -> R.string.backup_waiting_unmetered
                        else -> R.string.backup_waiting_internet
                    },
                )
                else -> stringResource(R.string.backup_queued_state)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (progress?.running == true && progress.total > 0) {
            val label = stringResource(R.string.backup_uploading)
            LinearProgressIndicator(
                progress = { progress.bytes.toFloat() / progress.total },
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp).semantics { contentDescription = label },
            )
        }
        Row(Modifier.align(Alignment.End)) {
            if (failed) TextButton(onClick = { viewModel.retry(item.id) }) { Text(stringResource(R.string.action_retry)) }
            TextButton(onClick = { viewModel.cancel(item.id) }) {
                Text(stringResource(if (failed) R.string.backup_remove else R.string.action_cancel))
            }
        }
    }
}

/** A switch row, or with [radio] a radio button row; the whole row is the touch target. */
@Composable
private fun ChoiceRow(
    title: String,
    text: String,
    shape: Shape,
    checked: Boolean,
    radio: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(
                if (radio) Modifier.selectable(checked, enabled, Role.RadioButton, onClick = onClick)
                else Modifier.toggleable(checked, enabled, Role.Switch) { onClick() },
            )
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (radio) RadioButton(selected = checked, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!radio) Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

private const val DISABLED_ALPHA = 0.38f

@Composable
private fun WideButton(label: Int, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(label)) }
}

@Composable
private fun Note(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** "Sichern" in the Recordings selection (`mediaGraph(selectionActions = …)`). Only phone copies are queued. */
@Composable
fun BackupSelectionAction(items: List<MediaItem>, clearSelection: () -> Unit, onConnectDrive: () -> Unit, viewModel: BackupViewModel = hiltViewModel()) {
    val auth by viewModel.authState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val resources = LocalResources.current
    // The screen's scope: selection mode (and this composable) ends before the snackbar does.
    val scope = LocalLifecycleOwner.current.lifecycleScope
    var askConnect by remember { mutableStateOf(false) }
    IconButton(onClick = {
        if (auth is DriveAuthState.NotConnected) {
            askConnect = true
        } else {
            val reconnect = auth is DriveAuthState.NeedsReconnect
            val ids = items.map { it.id }
            clearSelection()
            scope.launch {
                val result = viewModel.enqueue(ids)
                val action = if (reconnect && result.queued > 0) resources.getString(R.string.drive_reconnect) else null
                val text = enqueueText(resources, result, reconnect)
                if (text.isNotEmpty() && snackbar.showSnackbar(text, actionLabel = action) == SnackbarResult.ActionPerformed) onConnectDrive()
            }
        }
    }) {
        Icon(painterResource(R.drawable.ic_cloud_upload), stringResource(R.string.backup_action))
    }
    if (askConnect) {
        AlertDialog(
            onDismissRequest = { askConnect = false },
            title = { Text(stringResource(R.string.backup_connect_title)) },
            text = { Text(stringResource(R.string.backup_connect_text)) },
            confirmButton = {
                TextButton(onClick = {
                    askConnect = false
                    clearSelection()
                    onConnectDrive()
                }) { Text(stringResource(R.string.drive_connect)) }
            },
            dismissButton = { TextButton(onClick = { askConnect = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun enqueueText(resources: Resources, result: EnqueueResult, reconnect: Boolean): String = buildList {
    if (result.queued > 0) {
        add(resources.getQuantityString(if (reconnect) R.plurals.backup_queued_reconnect else R.plurals.backup_queued, result.queued, result.queued))
    }
    if (result.notOnPhone > 0) add(resources.getQuantityString(R.plurals.backup_not_on_phone, result.notOnPhone, result.notOnPhone))
    if (result.alreadyDone > 0) add(resources.getQuantityString(R.plurals.backup_already_done, result.alreadyDone, result.alreadyDone))
}.joinToString(" ")

/** "Drive-Kopie löschen" for the clip screen's chooser (`mediaGraph(clipDeleteTargets = …)`). */
@Composable
fun driveDeleteTargets(item: MediaItem, viewModel: BackupViewModel = hiltViewModel()): List<DeleteTarget> {
    val auth by viewModel.authState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val resources = LocalResources.current
    val scope = LocalLifecycleOwner.current.lifecycleScope
    val onDrive = item.driveFileId != null || item.backupError == BackupErrors.DRIVE_CONFLICT
    return listOf(
        DeleteTarget(
            label = stringResource(R.string.backup_delete_drive),
            enabled = onDrive && auth is DriveAuthState.Connected,
            title = stringResource(R.string.backup_delete_drive_title),
            text = stringResource(R.string.backup_delete_drive_text),
            danger = true,
            onConfirm = {
                scope.launch {
                    val text = viewModel.deleteOnDrive(item.id).fold(
                        { resources.getString(R.string.backup_deleted_drive) },
                        { resources.getString(R.string.backup_delete_drive_failed, it.driveMessage().resolve(resources)) },
                    )
                    snackbar.showSnackbar(text)
                }
            },
        ),
    )
}

/** "In Drive gesichert" / "Wird gesichert" / "Sicherung fehlgeschlagen" for media rows; only DONE counts as backed up. */
@Composable
fun BackupStateTag(item: MediaItem) {
    when (item.backupState) {
        BackupState.DONE -> MediaTag(stringResource(R.string.backup_chip_done))
        BackupState.QUEUED, BackupState.UPLOADING -> MediaTag(stringResource(R.string.backup_chip_running))
        BackupState.FAILED -> MediaTag(
            stringResource(R.string.backup_chip_failed),
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer,
        )
        BackupState.NONE -> Unit
    }
}

/** German text for a `MediaItem.backupError` ([BackupErrors]). */
fun backupErrorText(error: String?): UiText = when {
    error == BackupErrors.NO_LOCAL_COPY -> UiText.Res(R.string.backup_failure_no_local)
    error == BackupErrors.MD5_MISMATCH -> UiText.Res(R.string.backup_failure_md5)
    error == BackupErrors.DRIVE_CONFLICT -> UiText.Res(R.string.backup_failure_conflict)
    error?.startsWith("HTTP:") == true -> error.split(':', limit = 3)
        .let { DriveError.Http(it[1].toIntOrNull() ?: -1, it.getOrNull(2)?.ifEmpty { null }).driveMessage() }
    error?.startsWith("AUTH:") == true -> DriveError.Authorization(error.substringAfter(':').toIntOrNull() ?: -1).driveMessage()
    else -> UiText.Res(R.string.drive_error_unknown)
}

private fun UiText.resolve(resources: Resources): String = when (this) {
    is UiText.Res -> resources.getString(id, *args.toTypedArray())
    is UiText.Dynamic -> text
}

/** Queued, uploading and failed items, and how many are backed up. */
data class BackupQueueUi(val items: List<MediaItem> = emptyList(), val done: Int = 0)

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupQueue: BackupQueue,
    private val preferencesRepository: PreferencesRepository,
    private val api: DriveApi,
    auth: DriveAuth,
    repository: MediaRepository,
) : ViewModel() {
    val authState: StateFlow<DriveAuthState> = auth.state
    val preferences: StateFlow<AppPreferences> =
        preferencesRepository.preferences.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences())
    val queue: StateFlow<BackupQueueUi> = repository.observe()
        .map { all -> BackupQueueUi(all.filter { it.backupState in BackupRules.PENDING || it.backupState == BackupState.FAILED }, all.count { it.backupState == BackupState.DONE }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupQueueUi())
    val progress: StateFlow<Map<String, BackupProgress>> = backupQueue.progress
    val storageFull: StateFlow<Boolean> = backupQueue.storageFull

    private val _quota = MutableStateFlow<UiState<DriveQuota>?>(null)
    val quota: StateFlow<UiState<DriveQuota>?> = _quota.asStateFlow()

    /** Quota while connected (shown on the status card and with the storage pause). */
    fun loadQuota() {
        if (authState.value !is DriveAuthState.Connected) {
            _quota.value = null
            return
        }
        _quota.value = UiState.Loading
        viewModelScope.launch { _quota.value = api.about().fold({ UiState.Ready(it) }, { UiState.Error(it.driveMessage()) }) }
    }

    fun setMode(mode: BackupMode) = update { it.copy(backupMode = mode) }

    /** A failed DataStore write keeps the old value. */
    fun update(transform: (AppPreferences) -> AppPreferences) {
        viewModelScope.launch {
            runCatching { preferencesRepository.update(transform) }
                .onFailure { if (it is CancellationException) throw it else Log.w(TAG, "Saving backup settings failed", it) }
        }
    }

    fun cancel(id: String) {
        viewModelScope.launch { backupQueue.cancel(id) }
    }

    fun retry(id: String) {
        viewModelScope.launch { backupQueue.retry(id) }
    }

    fun resume() {
        viewModelScope.launch { backupQueue.resume() }
    }

    suspend fun checkNow() = backupQueue.checkNow()

    suspend fun verify(): Result<Int> = backupQueue.reconcile()

    suspend fun enqueue(ids: Collection<String>): EnqueueResult = backupQueue.enqueue(ids)

    /** Finishes even when the screen goes away meanwhile (the last copy gone closes the clip screen). */
    suspend fun deleteOnDrive(id: String): Result<Unit> = withContext(NonCancellable) { backupQueue.deleteOnDrive(id) }

    private companion object {
        const val TAG = "Backup"
    }
}
