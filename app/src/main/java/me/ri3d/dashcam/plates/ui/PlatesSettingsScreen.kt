package me.ri3d.dashcam.plates.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.ri3d.dashcam.R
import me.ri3d.dashcam.core.data.PreferencesRepository
import me.ri3d.dashcam.core.model.AppPreferences
import me.ri3d.dashcam.core.ui.AxoTopBar
import me.ri3d.dashcam.core.ui.ConfirmDialog
import me.ri3d.dashcam.core.ui.ListGroup
import me.ri3d.dashcam.core.ui.ListRow
import me.ri3d.dashcam.core.ui.SectionHeader
import me.ri3d.dashcam.plates.PlateRepository
import javax.inject.Inject

/** Route `PlatesSettings`: `platesLive`, `platesClips`, `backupIncludePlateMetadata`, clearing the history. */
@HiltViewModel
class PlatesSettingsViewModel @Inject constructor(
    private val preferences: PreferencesRepository,
    private val repository: PlateRepository,
) : ViewModel() {
    val state: StateFlow<AppPreferences?> = preferences.preferences.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun update(transform: (AppPreferences) -> AppPreferences) {
        viewModelScope.launch { runCatching { preferences.update(transform) } } // a failed write keeps the old value
    }

    /** Every plate, sighting and crop. */
    fun clearHistory() {
        viewModelScope.launch { repository.clear() }
    }
}

/** The one row in Settings → App. */
@Composable
fun PlatesSettingsRow(shape: Shape, onClick: () -> Unit) {
    ListRow(
        stringResource(R.string.plates_recognition),
        supporting = stringResource(R.string.plates_settings_row_text),
        icon = R.drawable.ic_plates,
        shape = shape,
        onClick = onClick,
        trailing = {
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}

@Composable
fun PlatesSettingsScreen(onBack: () -> Unit, viewModel: PlatesSettingsViewModel = hiltViewModel()) {
    val p by viewModel.state.collectAsStateWithLifecycle()
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    Scaffold(topBar = { AxoTopBar(stringResource(R.string.plates_recognition), onBack = onBack) }) { padding ->
        val prefs = p ?: return@Scaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        ) {
            ListGroup(
                listOf(
                    { shape ->
                        SwitchRow(stringResource(R.string.plates_setting_live), stringResource(R.string.plates_setting_live_text), prefs.platesLive, shape) { on ->
                            viewModel.update { it.copy(platesLive = on) }
                        }
                    },
                    { shape ->
                        SwitchRow(stringResource(R.string.plates_setting_clips), stringResource(R.string.plates_setting_clips_text), prefs.platesClips, shape) { on ->
                            viewModel.update { it.copy(platesClips = on) }
                        }
                    },
                ),
            )
            Hint(stringResource(R.string.plates_honesty), Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            SectionHeader(stringResource(R.string.plates_section_history))
            ListGroup(
                listOf(
                    { shape ->
                        SwitchRow(
                            stringResource(R.string.plates_setting_backup),
                            stringResource(R.string.plates_setting_backup_text),
                            prefs.backupIncludePlateMetadata,
                            shape,
                        ) { on -> viewModel.update { it.copy(backupIncludePlateMetadata = on) } }
                    },
                ),
            )
            TextButton(onClick = { confirmClear = true }, modifier = Modifier.padding(top = 12.dp)) {
                Text(stringResource(R.string.plates_clear), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.plates_clear_title),
            text = stringResource(R.string.plates_clear_text),
            confirmLabel = stringResource(R.string.plates_delete),
            onConfirm = {
                confirmClear = false
                viewModel.clearHistory()
            },
            onDismiss = { confirmClear = false },
            danger = true,
        )
    }
}

/** Whole row toggles (48 dp+, read as one switch). */
@Composable
private fun SwitchRow(title: String, text: String, checked: Boolean, shape: Shape, onChange: (Boolean) -> Unit) {
    Box(Modifier.clip(shape).toggleable(checked, role = Role.Switch, onValueChange = onChange)) {
        ListRow(title, supporting = text, shape = shape, verticalAlignment = Alignment.Top, trailing = { Switch(checked = checked, onCheckedChange = null) })
    }
}
