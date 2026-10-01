package to.axolotl.cam.core.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import to.axolotl.cam.R
import to.axolotl.cam.account.AccountSettingsRow
import to.axolotl.cam.core.FeatureFlags
import to.axolotl.cam.core.data.PreferencesRepository
import to.axolotl.cam.core.log.Log
import to.axolotl.cam.core.model.AppPreferences
import to.axolotl.cam.core.model.AppTheme
import to.axolotl.cam.core.navigation.Appearance
import to.axolotl.cam.core.navigation.Backup
import to.axolotl.cam.core.navigation.Route
import to.axolotl.cam.core.ui.AxoTopBar
import to.axolotl.cam.core.ui.ListGroup
import to.axolotl.cam.core.ui.ListRow
import to.axolotl.cam.core.ui.SectionHeader
import javax.inject.Inject

@Composable
fun SettingsScreen(
    recorderSettingsSection: RecorderSettingsSection?,
    onBack: () -> Unit,
    onNavigate: (Route) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    Scaffold(topBar = { AxoTopBar(stringResource(R.string.settings_title), onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        ) {
            SectionHeader(stringResource(R.string.settings_section_app))
            ListGroup(
                buildList {
                    if (FeatureFlags.accounts) {
                        add { shape -> AccountSettingsRow(shape, onNavigate) }
                    }
                    add { shape ->
                        ListRow(
                            stringResource(R.string.settings_appearance),
                            supporting = stringResource(preferences.theme.label),
                            icon = R.drawable.ic_palette,
                            shape = shape,
                            onClick = { onNavigate(Appearance) },
                            trailing = { Chevron() },
                        )
                    }
                    if (FeatureFlags.backup) {
                        add { shape ->
                            ListRow(
                                stringResource(R.string.settings_backup),
                                supporting = stringResource(R.string.settings_backup_text),
                                icon = R.drawable.ic_cloud_upload,
                                shape = shape,
                                onClick = { onNavigate(Backup) },
                                trailing = { Chevron() },
                            )
                        }
                    }
                },
            )
            recorderSettingsSection?.invoke()
        }
    }
}

@Composable
private fun Chevron() {
    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal val AppTheme.label: Int
    get() = when (this) {
        AppTheme.MATERIAL_YOU -> R.string.theme_material_you
        AppTheme.BLACK -> R.string.theme_black
    }

/** Shared by Settings and Appearance. */
@HiltViewModel
class SettingsViewModel @Inject constructor(private val repository: PreferencesRepository) : ViewModel() {
    val preferences: StateFlow<AppPreferences> =
        repository.preferences.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences())

    fun setTheme(theme: AppTheme) {
        viewModelScope.launch {
            // A failed DataStore write (IOException) must not take the process down; the old theme stays.
            runCatching { repository.update { it.copy(theme = theme) } }
                .onFailure { if (it is CancellationException) throw it else Log.w(TAG, "Saving the theme failed", it) }
        }
    }

    private companion object {
        const val TAG = "Settings"
    }
}
