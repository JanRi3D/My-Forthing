package to.axolotl.cam.dashcam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import to.axolotl.cam.BuildConfig
import to.axolotl.cam.R
import to.axolotl.cam.core.theme.LocalAxoColors
import to.axolotl.cam.core.ui.AxoTopBar
import to.axolotl.cam.core.ui.ListGroup
import to.axolotl.cam.core.ui.ListRow
import to.axolotl.cam.core.ui.SectionHeader
import to.axolotl.cam.core.ui.asString
import to.axolotl.cam.dashcam.RecorderConnectionState.Connecting
import to.axolotl.cam.dashcam.RecorderConnectionState.Disconnected
import to.axolotl.cam.dashcam.RecorderConnectionState.Error
import to.axolotl.cam.dashcam.RecorderConnectionState.Negotiating
import to.axolotl.cam.dashcam.RecorderConnectionState.NoWifi
import to.axolotl.cam.dashcam.RecorderConnectionState.Ready
import to.axolotl.cam.dashcam.RecorderConnectionState.TcpConnected
import to.axolotl.cam.dashcam.RecorderConnectionState.WrongWifi
import to.axolotl.cam.recorder.ErrorCodes
import to.axolotl.cam.recorder.NormalInfo
import to.axolotl.cam.recorder.StorageInfo
import javax.inject.Inject

/** Shared by the Connection screen and the Home card. */
@HiltViewModel
class ConnectionViewModel @Inject constructor(private val manager: RecorderConnectionManager) : ViewModel() {
    val state: StateFlow<RecorderConnectionState> = manager.state
    val ssid: StateFlow<String?> = manager.ssid
    val network: StateFlow<Network?> = manager.recorderNetwork
    val recStatus: StateFlow<NormalInfo.RecStatus?> = manager.recStatus
    val simulator: StateFlow<Boolean> = manager.simulator

    /** 4099 read once per session by the manager, for "SD frei" on the card. */
    val storage: StateFlow<StorageInfo?> = manager.storage

    /** Phone mobile data, read on resume and whenever the connection fails (for the routing hint). */
    private val _mobileData = MutableStateFlow<Boolean?>(null)
    val mobileData: StateFlow<Boolean?> = _mobileData.asStateFlow()

    private var userDisconnected = false

    init {
        viewModelScope.launch {
            manager.state.collect { if (it == NoWifi || it is Error) _mobileData.value = manager.mobileDataEnabled() }
        }
    }

    /** Like the original connection screen's onResume: try again unless busy, connected or stopped by the user. */
    fun onResume() {
        manager.refreshSsid()
        _mobileData.value = manager.mobileDataEnabled()
        val s = state.value
        val idle = s == Disconnected || s == NoWifi || s is WrongWifi || (s is Error && s.retry)
        if (idle && !userDisconnected) connect()
    }

    fun connect(ignoreSsid: Boolean = false) {
        userDisconnected = false
        viewModelScope.launch { manager.connect(ignoreSsid) }
    }

    fun disconnect() {
        userDisconnected = true
        viewModelScope.launch { manager.disconnect() }
    }

    fun refreshSsid() = manager.refreshSsid()

    fun setSimulator(enabled: Boolean) {
        viewModelScope.launch {
            manager.disconnect()
            manager.setSimulator(enabled)
            userDisconnected = false
            manager.connect()
        }
    }
}

@Composable
fun ConnectionScreen(
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    viewModel: ConnectionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ssid by viewModel.ssid.collectAsStateWithLifecycle()
    val network by viewModel.network.collectAsStateWithLifecycle()
    val simulator by viewModel.simulator.collectAsStateWithLifecycle()
    val mobileData by viewModel.mobileData.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permitted by remember { mutableStateOf(hasSsidPermission(context)) }
    var denied by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permitted = hasSsidPermission(context)
        denied = !permitted
        viewModel.onResume()
    }
    LifecycleResumeEffect(Unit) {
        permitted = hasSsidPermission(context)
        viewModel.onResume()
        onPauseOrDispose { }
    }

    Scaffold(topBar = { AxoTopBar(stringResource(R.string.dashcam_connection_title), onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(state, ssid, network, simulator, permitted, mobileData == true)
            Actions(
                state = state,
                simulator = simulator,
                onConnect = { viewModel.connect() },
                onConnectAnyway = { viewModel.connect(ignoreSsid = true) },
                onDisconnect = viewModel::disconnect,
                onWifiSettings = { openWifiSettings(context) },
            )
            if (!permitted && !simulator) {
                PermissionCard(
                    denied = denied,
                    onRequest = { permissionLauncher.launch(AndroidRecorderWifi.PERMISSIONS) },
                    onAppSettings = { openAppSettings(context) },
                )
            }

            Column {
                SectionHeader(stringResource(R.string.dashcam_help_title))
                ListGroup(
                    listOf(
                        { shape -> HelpRow(R.string.dashcam_help_ignition, R.string.dashcam_help_ignition_text, shape) },
                        { shape -> HelpRow(R.string.dashcam_help_join, R.string.dashcam_help_join_text, shape) },
                        { shape -> HelpRow(R.string.dashcam_help_password, R.string.dashcam_help_password_text, shape) },
                        { shape -> HelpRow(R.string.dashcam_help_mobile_data, R.string.dashcam_help_mobile_data_text, shape) },
                    ),
                )
            }
            ListRow(
                stringResource(R.string.dashcam_diagnostics_title),
                supporting = stringResource(R.string.dashcam_diagnostics_row),
                icon = R.drawable.ic_info,
                onClick = onOpenDiagnostics,
                trailing = { Chevron() },
            )
            if (BuildConfig.DEBUG) {
                Column {
                    SectionHeader(stringResource(R.string.dashcam_developer))
                    ListRow(
                        stringResource(R.string.dashcam_simulator),
                        supporting = stringResource(R.string.dashcam_simulator_text),
                        onClick = { viewModel.setSimulator(!simulator) },
                        trailing = { Switch(checked = simulator, onCheckedChange = null) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    state: RecorderConnectionState,
    ssid: String?,
    network: Network?,
    simulator: Boolean,
    permitted: Boolean,
    mobileDataOn: Boolean,
) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(state.headline),
                modifier = Modifier.semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                stringResource(R.string.dashcam_wifi_line, ssidText(ssid, simulator, permitted)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val steps = steps(state, network != null || simulator)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StepRow(R.string.dashcam_step_wifi, steps[0])
                StepRow(R.string.dashcam_step_tcp, steps[1])
                StepRow(R.string.dashcam_step_session, steps[2])
            }
            when (state) {
                is WrongWifi -> Text(
                    stringResource(R.string.dashcam_wrong_wifi_text, state.ssid.orEmpty()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                NoWifi -> Text(stringResource(R.string.dashcam_no_wifi_text), style = MaterialTheme.typography.bodyMedium)
                TcpConnected -> Text(stringResource(R.string.dashcam_tcp_only_text), style = MaterialTheme.typography.bodyMedium)
                is Error -> Text(
                    errorText(state.error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                is Ready -> {
                    val info = state.info
                    val missing = stringResource(R.string.dashcam_value_missing)
                    Text(
                        if (info == null) stringResource(R.string.dashcam_ready_no_info)
                        else stringResource(R.string.dashcam_ready_info, info.productModel ?: missing, info.fwVersion ?: missing),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                else -> Unit
            }
            val unreachable = state == NoWifi || (state is Error && state.error.code == ErrorCodes.CONNECT_FAILED)
            if (unreachable && mobileDataOn && !simulator) {
                Text(stringResource(R.string.dashcam_mobile_data_on), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private enum class Step(@StringRes val label: Int) {
    PENDING(R.string.dashcam_step_pending),
    ACTIVE(R.string.dashcam_step_active),
    DONE(R.string.dashcam_step_done),
    WARNING(R.string.dashcam_step_warning),
    FAILED(R.string.dashcam_step_failed),
}

/** Wi-Fi, TCP and session as three separate steps: TCP-connected is not session-ready. */
private fun steps(state: RecorderConnectionState, networkBound: Boolean): List<Step> = when (state) {
    Disconnected -> listOf(Step.PENDING, Step.PENDING, Step.PENDING)
    NoWifi -> listOf(Step.FAILED, Step.PENDING, Step.PENDING)
    is WrongWifi -> listOf(Step.WARNING, Step.PENDING, Step.PENDING)
    Connecting -> if (networkBound) listOf(Step.DONE, Step.ACTIVE, Step.PENDING) else listOf(Step.ACTIVE, Step.PENDING, Step.PENDING)
    TcpConnected, Negotiating -> listOf(Step.DONE, Step.DONE, Step.ACTIVE)
    is Ready -> listOf(Step.DONE, Step.DONE, Step.DONE)
    is Error -> when {
        isConfigurationError(state.error) -> listOf(Step.PENDING, Step.PENDING, Step.FAILED) // nothing was tried
        state.error.code == ErrorCodes.CONNECT_FAILED -> listOf(Step.DONE, Step.FAILED, Step.PENDING)
        state.error.code == ErrorCodes.SESSION_KEY_INVALID || state.error.code == ErrorCodes.SESSION_TIMEOUT ->
            listOf(Step.DONE, Step.DONE, Step.FAILED)
        else -> listOf(if (networkBound) Step.DONE else Step.PENDING, Step.FAILED, Step.FAILED)
    }
}

@Composable
private fun StepRow(@StringRes title: Int, step: Step) {
    Row(
        Modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (step) {
                Step.ACTIVE -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Step.DONE -> Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = LocalAxoColors.current.ok)
                Step.FAILED, Step.WARNING -> Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Step.PENDING -> Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.outline, CircleShape))
            }
        }
        Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(step.label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Actions(
    state: RecorderConnectionState,
    simulator: Boolean,
    onConnect: () -> Unit,
    onConnectAnyway: () -> Unit,
    onDisconnect: () -> Unit,
    onWifiSettings: () -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state) {
            Disconnected, NoWifi -> Button(onClick = onConnect) { Text(stringResource(R.string.dashcam_connect)) }
            is WrongWifi -> Button(onClick = onConnectAnyway) { Text(stringResource(R.string.dashcam_connect_anyway)) }
            is Error -> if (state.retry) Button(onClick = onConnect) { Text(stringResource(R.string.action_retry)) }
            Connecting, TcpConnected, Negotiating -> OutlinedButton(onClick = onDisconnect) { Text(stringResource(R.string.action_cancel)) }
            is Ready -> OutlinedButton(onClick = onDisconnect) { Text(stringResource(R.string.dashcam_disconnect)) }
        }
        if (!simulator && state !is Ready) {
            OutlinedButton(onClick = onWifiSettings) { Text(stringResource(R.string.dashcam_open_wifi_settings)) }
        }
    }
}

@Composable
private fun PermissionCard(denied: Boolean, onRequest: () -> Unit, onAppSettings: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dashcam_permission_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.dashcam_permission_rationale), style = MaterialTheme.typography.bodyMedium)
            if (denied) {
                Text(stringResource(R.string.dashcam_permission_denied), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onAppSettings) { Text(stringResource(R.string.dashcam_permission_app_settings)) }
            } else {
                Button(onClick = onRequest) { Text(stringResource(R.string.dashcam_permission_grant)) }
            }
        }
    }
}

@Composable
private fun HelpRow(@StringRes title: Int, @StringRes text: Int, shape: androidx.compose.ui.graphics.Shape) {
    ListRow(stringResource(title), supporting = stringResource(text), shape = shape, verticalAlignment = Alignment.Top)
}

@Composable
internal fun Chevron() {
    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Home card (replaces the foundation placeholder via the homeGraph slot). Tap opens the Connection screen. */
@Composable
fun DashcamHomeCard(onClick: () -> Unit, viewModel: ConnectionViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ssid by viewModel.ssid.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val recStatus by viewModel.recStatus.collectAsStateWithLifecycle()
    val simulator by viewModel.simulator.collectAsStateWithLifecycle()
    val ready = state is Ready
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.home_dashcam_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            IconLine(if (ready) R.drawable.ic_wifi else R.drawable.ic_wifi_off, stringResource(state.cardLine(simulator)))
            val rec = recStatus
            // Text only, no recording indicator: the recStatus meaning is an unconfirmed SDK reading.
            if (ready && rec != null) Text(recStatusText(rec).asString(), style = MaterialTheme.typography.bodyMedium)
            if (ready) {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    CardFact(stringResource(R.string.dashcam_card_wifi), if (simulator) stringResource(R.string.dashcam_simulator_short) else ssid ?: stringResource(R.string.dashcam_ssid_unknown))
                    CardFact(
                        stringResource(R.string.dashcam_card_sd),
                        storage?.available?.let { stringResource(R.string.dashcam_card_sd_free, it.toString()) }
                            ?: stringResource(R.string.dashcam_value_missing),
                    )
                }
            } else {
                Text(
                    stringResource(R.string.dashcam_card_hint),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun IconLine(@DrawableRes icon: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CardFact(label: String, value: String) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@get:StringRes
private val RecorderConnectionState.headline: Int
    get() = when (this) {
        Disconnected -> R.string.home_dashcam_disconnected
        NoWifi -> R.string.dashcam_state_no_wifi
        is WrongWifi -> R.string.dashcam_state_wrong_wifi
        Connecting -> R.string.dashcam_state_connecting
        TcpConnected -> R.string.dashcam_state_tcp
        Negotiating -> R.string.dashcam_state_negotiating
        is Ready -> R.string.dashcam_state_ready
        is Error -> R.string.dashcam_state_error
    }

@StringRes
private fun RecorderConnectionState.cardLine(simulator: Boolean): Int =
    if (this is Ready) (if (simulator) R.string.dashcam_card_ready_simulator else R.string.dashcam_card_ready) else headline

@Composable
private fun ssidText(ssid: String?, simulator: Boolean, permitted: Boolean): String = when {
    simulator -> stringResource(R.string.dashcam_simulator_short)
    ssid != null -> ssid
    !permitted -> stringResource(R.string.dashcam_ssid_no_permission)
    else -> stringResource(R.string.dashcam_ssid_unknown)
}

/** The SSID needs precise location; Android 13+ additionally offers NEARBY_WIFI_DEVICES (requested together). */
fun hasSsidPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

fun openWifiSettings(context: Context) {
    val panel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_WIFI) else null
    runCatching { context.startActivity(panel ?: Intent(Settings.ACTION_WIFI_SETTINGS)) }
        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } }
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }
}
