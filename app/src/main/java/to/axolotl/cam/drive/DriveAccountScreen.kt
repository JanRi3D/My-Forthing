package to.axolotl.cam.drive

import android.app.Activity
import androidx.annotation.StringRes
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.axolotl.cam.R
import to.axolotl.cam.core.branding.Branding
import to.axolotl.cam.core.navigation.DriveAccount
import to.axolotl.cam.core.ui.AxoTopBar
import to.axolotl.cam.core.ui.ConfirmDialog
import to.axolotl.cam.core.ui.ListGroup
import to.axolotl.cam.core.ui.ListRow
import to.axolotl.cam.core.ui.UiState
import to.axolotl.cam.core.ui.UiText
import to.axolotl.cam.core.ui.asString
import javax.inject.Inject

fun NavGraphBuilder.driveGraph(navController: NavController) {
    composable<DriveAccount> { DriveAccountScreen(onBack = { navController.navigateUp() }) }
}

/** Settings → App → "Google Drive". */
@Composable
fun DriveSettingsRow(shape: Shape, onClick: () -> Unit, viewModel: DriveAccountViewModel = hiltViewModel()) {
    val state by viewModel.authState.collectAsStateWithLifecycle()
    ListRow(
        stringResource(R.string.drive_title),
        supporting = state.summary(),
        icon = R.drawable.ic_cloud_upload,
        shape = shape,
        onClick = onClick,
        trailing = {
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}

@Composable
fun DriveAccountScreen(onBack: () -> Unit, viewModel: DriveAccountViewModel = hiltViewModel()) {
    val state by viewModel.authState.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    // The consent wait belongs to this screen: leaving it (or an activity recreation) cancels it.
    val scope = rememberCoroutineScope()
    val connect = { chooseAccount: Boolean -> activity?.let { scope.launch { viewModel.connect(it, chooseAccount) } }; Unit }
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state) { viewModel.loadQuota() }

    Scaffold(topBar = { AxoTopBar(stringResource(R.string.drive_title), onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DriveStatusCard(state, ui.quota)
            if (ui.busy) {
                val busy = stringResource(R.string.drive_busy)
                LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = busy })
            }
            ui.error?.let { ErrorNote(it.asString()) }
            when (state) {
                DriveAuthState.NotConnected -> {
                    ListGroup(
                        listOf(
                            { shape -> InfoRow(R.string.drive_info_folder_title, stringResource(R.string.drive_info_folder_text, Branding.driveRootFolderName), shape) },
                            { shape -> InfoRow(R.string.drive_info_independent_title, stringResource(R.string.drive_info_independent_text), shape) },
                        ),
                    )
                    WideButton(R.string.drive_connect, enabled = !ui.busy) { connect(false) }
                }
                is DriveAuthState.NeedsReconnect -> {
                    WideButton(R.string.drive_reconnect, enabled = !ui.busy) { connect(false) }
                    ListGroup(listOf { shape -> DisconnectRow(shape, enabled = !ui.busy) { confirmDisconnect = true } })
                }
                is DriveAuthState.Connected -> ListGroup(
                    listOf(
                        { shape ->
                            ActionRow(R.string.drive_switch_account, R.string.drive_switch_account_text, shape, enabled = !ui.busy) {
                                connect(true)
                            }
                        },
                        { shape -> DisconnectRow(shape, enabled = !ui.busy) { confirmDisconnect = true } },
                    ),
                )
            }
        }
    }

    if (confirmDisconnect) {
        ConfirmDialog(
            title = stringResource(R.string.drive_disconnect_title),
            text = stringResource(R.string.drive_disconnect_confirm_text, Branding.driveRootFolderName),
            confirmLabel = stringResource(R.string.drive_disconnect),
            onConfirm = {
                confirmDisconnect = false
                viewModel.disconnect()
            },
            onDismiss = { confirmDisconnect = false },
        )
    }
}

@Composable
private fun InfoRow(@StringRes title: Int, text: String, shape: Shape) {
    ListRow(stringResource(title), supporting = text, shape = shape, verticalAlignment = Alignment.Top)
}

@Composable
private fun DisconnectRow(shape: Shape, enabled: Boolean, onClick: () -> Unit) {
    ActionRow(R.string.drive_disconnect, R.string.drive_disconnect_text, shape, enabled, onClick)
}

/** While [enabled] is false the row is dimmed, not clickable and announced as disabled. */
@Composable
private fun ActionRow(@StringRes title: Int, @StringRes text: Int, shape: Shape, enabled: Boolean, onClick: () -> Unit) {
    ListRow(
        stringResource(title),
        modifier = if (enabled) Modifier else Modifier.alpha(DISABLED_ALPHA).semantics { disabled() },
        supporting = stringResource(text),
        shape = shape,
        onClick = onClick.takeIf { enabled },
    )
}

private const val DISABLED_ALPHA = 0.38f

@Composable
private fun WideButton(@StringRes label: Int, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(label))
    }
}

@Composable
private fun ErrorNote(text: String) {
    Surface(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

data class DriveAccountUi(val quota: UiState<DriveQuota>? = null, val busy: Boolean = false, val error: UiText? = null)

@HiltViewModel
class DriveAccountViewModel @Inject constructor(private val auth: DriveAuth, private val api: DriveApi) : ViewModel() {
    val authState: StateFlow<DriveAuthState> = auth.state

    private val _ui = MutableStateFlow(DriveAccountUi())
    val ui: StateFlow<DriveAccountUi> = _ui.asStateFlow()

    /** Loads the quota while connected; clears it otherwise. */
    fun loadQuota() {
        if (auth.state.value !is DriveAuthState.Connected) {
            _ui.update { it.copy(quota = null) }
            return
        }
        _ui.update { it.copy(quota = UiState.Loading) }
        viewModelScope.launch {
            val quota = api.about().fold({ UiState.Ready(it) }, { UiState.Error(it.driveMessage()) })
            _ui.update { it.copy(quota = quota) }
        }
    }

    /** Suspends while Google's consent screen is open; runs in the screen's scope (see [DriveAccountScreen]). */
    suspend fun connect(activity: Activity, chooseAccount: Boolean) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, error = null) }
        try {
            auth.connect(activity, chooseAccount).onFailure { error ->
                if (error !is DriveError.Cancelled) _ui.update { it.copy(error = error.driveMessage()) }
            }
        } finally {
            _ui.update { it.copy(busy = false) }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            auth.disconnect()
            _ui.update { it.copy(busy = false) }
        }
    }
}
