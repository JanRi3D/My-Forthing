package me.ri3d.dashcam.dashcam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.ri3d.dashcam.R
import me.ri3d.dashcam.core.ui.AxoTopBar
import me.ri3d.dashcam.core.ui.ConfirmDialog
import me.ri3d.dashcam.core.ui.ListGroup
import me.ri3d.dashcam.core.ui.ListRow
import me.ri3d.dashcam.core.ui.SectionHeader
import me.ri3d.dashcam.core.ui.UiText
import me.ri3d.dashcam.core.ui.asString
import me.ri3d.dashcam.recorder.NormalInfo
import me.ri3d.dashcam.recorder.RecorderCommand
import me.ri3d.dashcam.recorder.RecorderResult
import me.ri3d.dashcam.recorder.StorageInfo
import me.ri3d.dashcam.recorder.parseStorageInfo
import javax.inject.Inject

@HiltViewModel
class SdCardViewModel @Inject constructor(private val manager: RecorderConnectionManager) : ViewModel() {
    val state: StateFlow<RecorderConnectionState> = manager.state
    val sdStatus: StateFlow<NormalInfo.SdStatus?> = manager.sdStatus

    /** Last 4099 result; null while not loaded. */
    private val _storage = MutableStateFlow<RecorderResult<StorageInfo>?>(null)
    val storage: StateFlow<RecorderResult<StorageInfo>?> = _storage.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Last 12288 result; null before formatting. */
    private val _format = MutableStateFlow<RecorderResult<Unit>?>(null)
    val format: StateFlow<RecorderResult<Unit>?> = _format.asStateFlow()
    private val _formatting = MutableStateFlow(false)
    val formatting: StateFlow<Boolean> = _formatting.asStateFlow()

    init {
        viewModelScope.launch {
            manager.state.map { it is RecorderConnectionState.Ready }.distinctUntilChanged().collect { ready ->
                if (ready) load() else _storage.value = null
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            _storage.value = manager.request(RecorderCommand.GetStorageInfo(), ::parseStorageInfo)
            _loading.value = false
        }
    }

    // ponytail: the request dies with this screen; a format still running then reports as an unmatched reply.
    // Timeout untested on hardware; raise if real cards take longer.
    fun formatCard() {
        viewModelScope.launch {
            _formatting.value = true
            _format.value = manager.request(RecorderCommand.FormatStorage(), { }, FORMAT_TIMEOUT_MS)
            _formatting.value = false
            if (_format.value is RecorderResult.Ok) load()
        }
    }

    companion object {
        const val FORMAT_TIMEOUT_MS = 60_000L
    }
}

@Composable
fun SdCardScreen(onBack: () -> Unit, onConnect: () -> Unit, viewModel: SdCardViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sdStatus by viewModel.sdStatus.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val format by viewModel.format.collectAsStateWithLifecycle()
    val formatting by viewModel.formatting.collectAsStateWithLifecycle()
    val ready = state is RecorderConnectionState.Ready
    var confirmFormat by rememberSaveable { mutableStateOf(false) }

    Scaffold(topBar = { AxoTopBar(stringResource(R.string.home_tile_sd_card), onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!ready) NotConnectedNotice(onConnect)

            Column {
                SectionHeader(stringResource(R.string.dashcam_sd_storage))
                val info = (storage as? RecorderResult.Ok)?.value
                ListGroup(
                    listOf(
                        { shape -> ValueRow(R.string.dashcam_sd_total, rawValue(info?.totalSpace), shape) },
                        { shape -> ValueRow(R.string.dashcam_sd_available, rawValue(info?.available), shape) },
                        { shape -> ValueRow(R.string.dashcam_sd_residual_life, rawValue(info?.residualLife), shape) },
                        { shape -> ValueRow(R.string.dashcam_sd_health, rawValue(info?.healthStatus), shape) },
                    ),
                )
                Text(
                    stringResource(R.string.dashcam_sd_units_note),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                (storage as? RecorderResult.Failed)?.let {
                    Text(errorText(it.error), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                }
                OutlinedButton(onClick = viewModel::load, enabled = ready && !loading, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(stringResource(if (loading) R.string.state_view_loading else R.string.dashcam_refresh))
                }
            }

            Column {
                SectionHeader(stringResource(R.string.dashcam_sd_status_title))
                ListRow(
                    sdStatusText(sdStatus).asString(),
                    supporting = stringResource(if (sdStatus == null) R.string.dashcam_sd_status_hint_none else R.string.dashcam_sd_status_hint),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader(stringResource(R.string.dashcam_sd_manage))
                Text(
                    stringResource(R.string.dashcam_sd_format_text),
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = { confirmFormat = true },
                    enabled = ready && !formatting,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(stringResource(if (formatting) R.string.dashcam_sd_formatting else R.string.dashcam_sd_format)) }
                val result = format
                if (result != null && !formatting) {
                    Text(
                        if (result is RecorderResult.Failed) commandFailedText(result.error) else stringResource(R.string.dashcam_sd_format_done),
                        Modifier
                            .padding(horizontal = 16.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        color = if (result is RecorderResult.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }

    if (confirmFormat) {
        ConfirmDialog(
            title = stringResource(R.string.dashcam_sd_format_confirm_title),
            text = stringResource(R.string.dashcam_sd_format_confirm_text),
            confirmLabel = stringResource(R.string.dashcam_sd_format),
            onConfirm = {
                confirmFormat = false
                viewModel.formatCard()
            },
            onDismiss = { confirmFormat = false },
            danger = true,
        )
    }
}

@Composable
internal fun ValueRow(title: Int, value: UiText, shape: androidx.compose.ui.graphics.Shape) {
    ListRow(stringResource(title), supporting = value.asString(), shape = shape)
}

/** Shown on recorder screens while there is no session; everything else stays disabled. */
@Composable
internal fun NotConnectedNotice(onConnect: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.dashcam_not_connected_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.dashcam_not_connected_text), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onConnect) { Text(stringResource(R.string.dashcam_open_connection)) }
            }
        }
    }
}
