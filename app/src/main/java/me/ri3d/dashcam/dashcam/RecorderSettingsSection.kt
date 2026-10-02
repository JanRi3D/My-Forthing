package me.ri3d.dashcam.dashcam

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import me.ri3d.dashcam.R
import me.ri3d.dashcam.core.log.redact
import me.ri3d.dashcam.core.navigation.Connection
import me.ri3d.dashcam.core.navigation.Diagnostics
import me.ri3d.dashcam.core.navigation.Route
import me.ri3d.dashcam.core.ui.ConfirmDialog
import me.ri3d.dashcam.core.ui.ListGroup
import me.ri3d.dashcam.core.ui.ListRow
import me.ri3d.dashcam.core.ui.SectionHeader
import me.ri3d.dashcam.core.ui.UiText
import me.ri3d.dashcam.core.ui.asString
import me.ri3d.dashcam.recorder.CapabilityGroup
import me.ri3d.dashcam.recorder.ChannelSettings
import me.ri3d.dashcam.recorder.DeviceInfo
import me.ri3d.dashcam.recorder.OsdInfo
import me.ri3d.dashcam.recorder.RecorderCommand
import me.ri3d.dashcam.recorder.RecorderError
import me.ri3d.dashcam.recorder.RecorderResult
import me.ri3d.dashcam.recorder.RecorderSettings
import me.ri3d.dashcam.recorder.RecorderValues
import me.ri3d.dashcam.recorder.SettingsPatch
import me.ri3d.dashcam.recorder.WifiParam
import me.ri3d.dashcam.recorder.parseNetworkCapabilities
import me.ri3d.dashcam.recorder.parseSettings
import javax.inject.Inject

data class SettingOption(val value: Int, @StringRes val label: Int)

private val SWITCH = listOf(
    SettingOption(RecorderValues.ON, R.string.dashcam_opt_on),
    SettingOption(RecorderValues.OFF, R.string.dashcam_opt_off),
)

/**
 * Exactly the settings of the protocol report's table, with the values the original app offers. Labels only
 * where the report gives a meaning; anything else read back is shown as a raw number.
 */
enum class RecorderSetting(val key: String, @StringRes val title: Int, val options: List<SettingOption>) {
    VIDEO_RESOLUTION(
        "videoResolution", R.string.dashcam_set_resolution,
        listOf(SettingOption(RecorderValues.RESOLUTION_1080P, R.string.dashcam_opt_1080p), SettingOption(RecorderValues.RESOLUTION_720P, R.string.dashcam_opt_720p)),
    ),
    NORMAL_VIDEO_TIME(
        "normalVideoTime", R.string.dashcam_set_loop,
        listOf(SettingOption(1, R.string.dashcam_opt_1_min), SettingOption(3, R.string.dashcam_opt_3_min), SettingOption(5, R.string.dashcam_opt_5_min)),
    ),
    SOUND("soundSwitch", R.string.dashcam_set_sound, SWITCH),
    WDR("wdrSwitch", R.string.dashcam_set_wdr, SWITCH),

    /** Raw 1/2/3 with the original app's labels; the SDK enum names them the other way round (see RecorderValues). */
    G_SENSOR(
        "gSensorSensitivity", R.string.dashcam_set_gsensor,
        listOf(
            SettingOption(RecorderValues.GSENSOR_HIGH, R.string.dashcam_opt_high),
            SettingOption(RecorderValues.GSENSOR_MEDIUM, R.string.dashcam_opt_medium),
            SettingOption(RecorderValues.GSENSOR_LOW, R.string.dashcam_opt_low),
        ),
    ),
    PARK_MONITOR("parkMonitor", R.string.dashcam_set_park, SWITCH),
    EVENT_REC_CYCLE("eventRecCycle", R.string.dashcam_set_event_cycle, SWITCH),

    /** enableOSD; osdContent is resubmitted exactly as read. */
    OSD("osd", R.string.dashcam_set_osd, SWITCH),
    POWEROFF_DELAY(
        "poweroffDelay", R.string.dashcam_set_poweroff,
        listOf(SettingOption(0, R.string.dashcam_opt_0_s), SettingOption(10, R.string.dashcam_opt_10_s), SettingOption(60, R.string.dashcam_opt_60_s)),
    ),
    ;

    val isSwitch get() = options === SWITCH
}

/** The front channel (chanNo 1) the original app addresses; the first channel if none says 1. */
fun RecorderSettings.channel(): ChannelSettings? = channels.firstOrNull { it.chanNo == RecorderValues.CHANNEL_FRONT } ?: channels.firstOrNull()

fun RecorderSettings.value(setting: RecorderSetting): Int? = when (setting) {
    RecorderSetting.VIDEO_RESOLUTION -> channel()?.videoResolution
    RecorderSetting.NORMAL_VIDEO_TIME -> global.normalVideoTime
    RecorderSetting.SOUND -> channel()?.soundSwitch
    RecorderSetting.WDR -> channel()?.wdrSwitch
    RecorderSetting.G_SENSOR -> global.gSensorSensitivity
    RecorderSetting.PARK_MONITOR -> global.parkMonitor
    RecorderSetting.EVENT_REC_CYCLE -> global.eventRecCycle
    RecorderSetting.OSD -> ((channel()?.raw?.get("osd") as? JsonObject)?.get("enableOSD") as? JsonPrimitive)?.intOrNull
    RecorderSetting.POWEROFF_DELAY -> global.poweroffDelay
}

/** osdContent exactly as read, or null when it is not a list of numbers (then the overlay cannot be changed safely). */
fun RecorderSettings.osdContent(): List<Int>? {
    val array = (channel()?.raw?.get("osd") as? JsonObject)?.get("osdContent") as? JsonArray ?: return null
    return array.map { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.intOrNull ?: return null }
}

/** 8192 patch with only the changed field plus chanNo = 1, as the original UI controls send it. */
fun RecorderSettings.patch(setting: RecorderSetting, value: Int): SettingsPatch? {
    val ch = RecorderValues.CHANNEL_FRONT
    return when (setting) {
        RecorderSetting.VIDEO_RESOLUTION -> SettingsPatch(chanNo = ch, videoResolution = value)
        RecorderSetting.NORMAL_VIDEO_TIME -> SettingsPatch(chanNo = ch, normalVideoTime = value)
        RecorderSetting.SOUND -> SettingsPatch(chanNo = ch, soundSwitch = value)
        RecorderSetting.WDR -> SettingsPatch(chanNo = ch, wdrSwitch = value)
        RecorderSetting.G_SENSOR -> SettingsPatch(chanNo = ch, gSensorSensitivity = value)
        RecorderSetting.PARK_MONITOR -> SettingsPatch(chanNo = ch, parkMonitor = value)
        RecorderSetting.EVENT_REC_CYCLE -> SettingsPatch(chanNo = ch, eventRecCycle = value)
        RecorderSetting.OSD -> SettingsPatch(chanNo = ch, osd = OsdInfo(value, osdContent() ?: return null))
        RecorderSetting.POWEROFF_DELAY -> SettingsPatch(chanNo = ch, poweroffDelay = value)
    }
}

/**
 * The Wi-Fi dialog changes only the password and resubmits the whole object read back (no chanNo): `ssid` and
 * `frequency` exactly as read, never converted. `mode`: the only value capability 20483 lists ([supportedModes]),
 * else as read – the physical recorder reads back mode 1 while it supports only mode 0 (2026-10-02), and resending
 * the read value could switch it to an unsupported mode.
 */
fun wifiToSend(read: WifiParam, password: String, supportedModes: List<Int>?): WifiParam =
    read.copy(passwd = password, mode = supportedModes?.singleOrNull() ?: read.mode)

/**
 * Hotspot password rule: 8–16 characters (the original dialog's message; WPA2 allows 63 at most), printable ASCII
 * without space (0x21–0x7E), at least one letter A–Z/a–z and one digit 0–9.
 */
fun isValidWifiPassword(password: String): Boolean =
    password.length in 8..16 &&
        password.all { it in '!'..'~' } &&
        password.any { it in 'A'..'Z' || it in 'a'..'z' } &&
        password.any { it in '0'..'9' }

/** Readback fields this screen does not show, as `path = raw JSON` (secrets masked). */
fun extraValues(settings: RecorderSettings): List<Pair<String, String>> {
    val raw = runCatching { Json.parseToJsonElement(redact(settings.raw.toString())).jsonObject }.getOrNull() ?: return emptyList()
    val shownGlobal = setOf("normalVideoTime", "gSensorSensitivity", "parkMonitor", "eventRecCycle", "poweroffDelay", "wifi")
    val shownChannel = setOf("chanNo", "videoResolution", "soundSwitch", "wdrSwitch", "osd")
    val front = settings.channel()
    return buildList {
        raw.forEach { (key, value) ->
            when (key) {
                "withoutChan" -> (value as? JsonObject)?.forEach { (k, v) -> if (k !in shownGlobal) add("$key.$k" to v.toString()) }
                "withChan" -> (value as? JsonArray)?.forEachIndexed { i, c ->
                    val isFront = front != null && i == settings.channels.indexOf(front)
                    (c as? JsonObject)?.forEach { (k, v) -> if (!isFront || k !in shownChannel) add("$key[$i].$k" to v.toString()) }
                }
                else -> add(key to value.toString())
            }
        }
    }
}

sealed interface ChangeStatus {
    data object Sending : ChangeStatus

    /** rval 0 and the readback shows the sent value. */
    data object Confirmed : ChangeStatus

    /** rval 0 and nothing to read back (factory reset): "vom Recorder angenommen". */
    data object Accepted : ChangeStatus

    /** rval 0 but the readback differs. */
    data class Mismatch(val sent: UiText, val read: UiText) : ChangeStatus

    /** The recorder rejected the change (rval ≠ 0) or it was not sent. */
    data class Failed(val error: RecorderError) : ChangeStatus

    /** No recorder answer (timeout, connection lost): it may or may not have been applied. */
    data class Unknown(val error: RecorderError) : ChangeStatus

    /** rval 0, but the readback failed (e.g. the recorder restarted its Wi-Fi). */
    data class Unconfirmed(val error: RecorderError) : ChangeStatus
}

/** Failed (definitely not applied) or Unknown (may have been applied), see [outcomeUnknown]. */
private fun failure(error: RecorderError): ChangeStatus =
    if (outcomeUnknown(error)) ChangeStatus.Unknown(error) else ChangeStatus.Failed(error)

data class RecorderSettingsUi(
    val settings: RecorderSettings? = null,
    val loading: Boolean = false,
    val loadError: RecorderError? = null,
    /** By [RecorderSetting.key], or [WIFI_KEY]. */
    val status: Map<String, ChangeStatus> = emptyMap(),
    val reset: ChangeStatus? = null,
    /** After a Wi-Fi change or factory reset that may have been applied: ask the user to rejoin the recorder Wi-Fi. */
    val rejoinWifi: Boolean = false,
    /** `wifi.mode` values of capability 20483, loaded with the settings; null when the query failed. */
    val wifiModes: List<Int>? = null,
) {
    companion object {
        const val WIFI_KEY = "wifi"
    }
}

@HiltViewModel
class RecorderSettingsViewModel @Inject constructor(private val manager: RecorderConnectionManager) : ViewModel() {
    val connection: StateFlow<RecorderConnectionState> = manager.state

    /** Readback and device info of the last read recorder: shown (not editable) until this session's readback. */
    val cached: StateFlow<CachedFacts?> = manager.cachedFacts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _ui = MutableStateFlow(RecorderSettingsUi())
    val ui: StateFlow<RecorderSettingsUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            manager.state.map { it is RecorderConnectionState.Ready }.distinctUntilChanged().collect { ready ->
                if (ready) load() else _ui.update { RecorderSettingsUi(rejoinWifi = it.rejoinWifi) }
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            when (val r = manager.request(RecorderCommand.GetAllSettings, ::parseSettings)) {
                is RecorderResult.Ok -> {
                    // Before the settings appear, so the Wi-Fi dialog always knows which mode it will send.
                    val modes = manager.capabilities(CapabilityGroup.NETWORK)?.let { runCatching { parseNetworkCapabilities(it).wifiModes }.getOrNull() }
                    _ui.update { it.copy(settings = r.value, loading = false, loadError = null, wifiModes = modes) }
                }
                is RecorderResult.Failed -> _ui.update { it.copy(loading = false, loadError = r.error) }
            }
        }
    }

    fun change(setting: RecorderSetting, value: Int) {
        val patch = _ui.value.settings?.patch(setting, value) ?: return
        viewModelScope.launch {
            sendAndConfirm(setting.key, patch) { read ->
                val got = read.value(setting)
                if (got == value) ChangeStatus.Confirmed else ChangeStatus.Mismatch(label(setting, value), label(setting, got))
            }
        }
    }

    /** Password only (the SSID stays as read); the dialog has validated it and had it entered twice. */
    fun changeWifi(password: String) {
        val read = _ui.value.settings?.global?.wifi ?: return
        if (!isValidWifiPassword(password)) return
        val sent = wifiToSend(read, password, _ui.value.wifiModes)
        viewModelScope.launch {
            val status = sendAndConfirm(RecorderSettingsUi.WIFI_KEY, SettingsPatch(wifi = sent)) { readback ->
                if (readback.global.wifi?.passwd == sent.passwd) {
                    ChangeStatus.Confirmed
                } else {
                    ChangeStatus.Mismatch(UiText.Res(R.string.dashcam_wifi_password_sent), UiText.Res(R.string.dashcam_wifi_password_differs))
                }
            }
            // The hotspot may already use the new password unless the recorder clearly refused it.
            if (status != null && status !is ChangeStatus.Failed) _ui.update { it.copy(rejoinWifi = true) }
        }
    }

    fun factoryReset() {
        viewModelScope.launch {
            _ui.update { it.copy(reset = ChangeStatus.Sending) }
            val r = manager.request(RecorderCommand.FactoryReset, { })
            // No readback: what the reset changes is up to the firmware (Wi-Fi may restart).
            val status = if (r is RecorderResult.Failed) failure(r.error) else ChangeStatus.Accepted
            _ui.update { it.copy(reset = status, rejoinWifi = it.rejoinWifi || status !is ChangeStatus.Failed) }
            if (r is RecorderResult.Ok) load()
        }
    }

    fun dismissRejoin() = _ui.update { it.copy(rejoinWifi = false) }

    /** 8192, then 4097 readback; returns the final status, or null while a change of [key] is still being sent. */
    private suspend fun sendAndConfirm(key: String, patch: SettingsPatch, check: (RecorderSettings) -> ChangeStatus): ChangeStatus? {
        if (_ui.value.status[key] == ChangeStatus.Sending) return null
        setStatus(key, ChangeStatus.Sending)
        val sent = manager.request(RecorderCommand.SetSettings(patch), { })
        val status = if (sent is RecorderResult.Failed) {
            failure(sent.error)
        } else {
            when (val read = manager.request(RecorderCommand.GetAllSettings, ::parseSettings)) {
                is RecorderResult.Ok -> {
                    _ui.update { it.copy(settings = read.value) }
                    check(read.value)
                }
                is RecorderResult.Failed -> ChangeStatus.Unconfirmed(read.error)
            }
        }
        setStatus(key, status)
        return status
    }

    private fun setStatus(key: String, status: ChangeStatus) = _ui.update { it.copy(status = it.status + (key to status)) }
}

/** Option label, or the raw number for values the original app does not offer. */
fun label(setting: RecorderSetting, value: Int?): UiText {
    if (value == null) return UiText.Res(R.string.dashcam_value_missing)
    return setting.options.firstOrNull { it.value == value }?.let { UiText.Res(it.label) }
        ?: UiText.Res(R.string.dashcam_value_unknown, listOf(value))
}

/** Rendered by core Settings below the app section (the settingsGraph `recorderSettingsSection` slot). */
@Composable
fun DashcamSettingsSection(onNavigate: (Route) -> Unit, viewModel: RecorderSettingsViewModel = hiltViewModel()) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val cached by viewModel.cached.collectAsStateWithLifecycle()
    val ready = connection is RecorderConnectionState.Ready
    // Editable only with a session and this session's readback; until then the last readback is shown, disabled.
    val cachedSettings = cached?.settings?.takeIf { ui.settings == null }
    val settings = ui.settings ?: cachedSettings?.value
    val enabled = ready && ui.settings != null
    var optionDialog by rememberSaveable { mutableStateOf<RecorderSetting?>(null) }
    var wifiDialog by rememberSaveable { mutableStateOf(false) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            !ready -> NotConnectedNotice { onNavigate(Connection) }
            ui.settings == null && ui.loadError != null -> {
                Text(errorText(ui.loadError!!), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = viewModel::load, Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.action_retry)) }
            }
            ui.settings == null -> Text(stringResource(R.string.state_view_loading), Modifier.padding(horizontal = 16.dp))
        }
        if (cachedSettings != null) {
            Text(
                stringResource(R.string.dashcam_settings_cached, readTimeText(cachedSettings.readAt)),
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val onRow: (RecorderSetting) -> Unit = { setting ->
            if (setting.isSwitch) {
                when (settings?.value(setting)) { // rows with an unknown raw value are disabled in SettingRow
                    RecorderValues.ON -> viewModel.change(setting, RecorderValues.OFF)
                    RecorderValues.OFF -> viewModel.change(setting, RecorderValues.ON)
                }
            } else {
                optionDialog = setting
            }
        }
        SettingsGroup(R.string.dashcam_group_recording, listOf(RecorderSetting.VIDEO_RESOLUTION, RecorderSetting.NORMAL_VIDEO_TIME, RecorderSetting.SOUND, RecorderSetting.WDR), settings, ui, enabled, onRow)
        SettingsGroup(R.string.dashcam_group_incidents, listOf(RecorderSetting.G_SENSOR, RecorderSetting.PARK_MONITOR, RecorderSetting.EVENT_REC_CYCLE), settings, ui, enabled, onRow)

        SectionHeader(stringResource(R.string.dashcam_group_camera))
        val wifi = settings?.global?.wifi
        ListGroup(
            listOf(
                { shape -> SettingRow(RecorderSetting.OSD, settings, ui.status[RecorderSetting.OSD.key], enabled && settings?.osdContent() != null, shape) { onRow(RecorderSetting.OSD) } },
                { shape -> SettingRow(RecorderSetting.POWEROFF_DELAY, settings, ui.status[RecorderSetting.POWEROFF_DELAY.key], enabled, shape) { onRow(RecorderSetting.POWEROFF_DELAY) } },
                { shape ->
                    val current = if (wifi == null) stringResource(R.string.dashcam_value_missing)
                    else stringResource(R.string.dashcam_wifi_current, wifi.ssid ?: stringResource(R.string.dashcam_value_missing), bandText(wifi.frequency))
                    ListRow(
                        stringResource(R.string.dashcam_set_wifi),
                        modifier = Modifier.alpha(if (enabled && wifi != null) 1f else DISABLED_ALPHA),
                        supporting = statusLine(current, ui.status[RecorderSettingsUi.WIFI_KEY]),
                        shape = shape,
                        onClick = if (enabled && wifi != null) ({ wifiDialog = true }) else null,
                        trailing = { Chevron() },
                    )
                },
            ),
        )

        SectionHeader(stringResource(R.string.dashcam_group_device))
        val liveInfo = (connection as? RecorderConnectionState.Ready)?.info
        val cachedInfo = cached?.deviceInfo?.takeIf { liveInfo == null }
        val info: DeviceInfo? = liveInfo ?: cachedInfo?.value
        if (cachedInfo != null) {
            Text(lastReadText(cachedInfo.readAt), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        }
        ListGroup(
            listOf(
                { shape -> ValueRow(R.string.dashcam_device_model, rawText(info?.productModel), shape) },
                { shape -> ValueRow(R.string.dashcam_device_serial, rawText(info?.productSN), shape) },
                { shape -> ValueRow(R.string.dashcam_device_firmware, rawText(info?.fwVersion), shape) },
                { shape -> ValueRow(R.string.dashcam_device_hardware, rawText(info?.hwVersion), shape) },
                { shape -> ValueRow(R.string.dashcam_device_mcu, rawText(info?.mcuFwVersion), shape) },
                { shape ->
                    ListRow(
                        stringResource(R.string.dashcam_diagnostics_title),
                        supporting = stringResource(R.string.dashcam_diagnostics_row),
                        shape = shape,
                        onClick = { onNavigate(Diagnostics) },
                        trailing = { Chevron() },
                    )
                },
            ),
        )

        val extras = settings?.let(::extraValues).orEmpty()
        if (extras.isNotEmpty()) {
            SectionHeader(stringResource(R.string.dashcam_extra_values))
            ListGroup(extras.map { (key, value) -> { shape -> ListRow(key, supporting = value, shape = shape) } })
        }

        Text(
            stringResource(R.string.dashcam_reset_text),
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { confirmReset = true },
            enabled = enabled && ui.reset != ChangeStatus.Sending,
            modifier = Modifier.padding(horizontal = 16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
        ) { Text(stringResource(R.string.dashcam_reset)) }
        ui.reset?.let { Text(statusText(it), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium) }
    }

    optionDialog?.let { setting ->
        OptionDialog(setting, settings?.value(setting), onSelect = {
            optionDialog = null
            viewModel.change(setting, it)
        }, onDismiss = { optionDialog = null })
    }
    if (wifiDialog && settings?.global?.wifi != null) {
        WifiDialog(settings.global.wifi!!, ui.wifiModes, onSubmit = { password ->
            wifiDialog = false
            viewModel.changeWifi(password)
        }, onDismiss = { wifiDialog = false })
    }
    if (confirmReset) {
        ConfirmDialog(
            title = stringResource(R.string.dashcam_reset_confirm_title),
            text = stringResource(R.string.dashcam_reset_confirm_text),
            confirmLabel = stringResource(R.string.dashcam_reset),
            onConfirm = {
                confirmReset = false
                viewModel.factoryReset()
            },
            onDismiss = { confirmReset = false },
            danger = true,
        )
    }
    if (ui.rejoinWifi) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRejoin,
            title = { Text(stringResource(R.string.dashcam_rejoin_title)) },
            text = { Text(stringResource(R.string.dashcam_rejoin_text)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissRejoin()
                    openWifiSettings(context)
                }) { Text(stringResource(R.string.dashcam_open_wifi_settings)) }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissRejoin) { Text(stringResource(R.string.dashcam_later)) } },
        )
    }
}

private const val DISABLED_ALPHA = 0.38f

@Composable
private fun SettingsGroup(
    @StringRes title: Int,
    rows: List<RecorderSetting>,
    settings: RecorderSettings?,
    ui: RecorderSettingsUi,
    enabled: Boolean,
    onRow: (RecorderSetting) -> Unit,
) {
    SectionHeader(stringResource(title))
    ListGroup(rows.map { setting -> { shape -> SettingRow(setting, settings, ui.status[setting.key], enabled, shape) { onRow(setting) } } })
}

@Composable
private fun SettingRow(
    setting: RecorderSetting,
    settings: RecorderSettings?,
    status: ChangeStatus?,
    enabled: Boolean,
    shape: Shape,
    onClick: () -> Unit,
) {
    val value = settings?.value(setting)
    // A switch reading neither 1 nor 0 is shown raw and not toggled: the app would not know what "on" replaces.
    val usable = enabled && (!setting.isSwitch || value == RecorderValues.ON || value == RecorderValues.OFF)
    val rowEnabled = usable && status != ChangeStatus.Sending
    var supporting = statusLine(label(setting, value).asString(), status)
    if (setting == RecorderSetting.OSD && settings != null) {
        val content = (settings.channel()?.raw?.get("osd") as? JsonObject)?.get("osdContent")?.toString()
            ?: stringResource(R.string.dashcam_value_missing)
        supporting += "\n" + stringResource(R.string.dashcam_osd_content, content)
    }
    ListRow(
        stringResource(setting.title),
        modifier = Modifier.alpha(if (usable) 1f else DISABLED_ALPHA),
        supporting = supporting,
        shape = shape,
        onClick = if (rowEnabled) onClick else null,
        trailing = if (setting.isSwitch) {
            { Switch(checked = value == RecorderValues.ON, onCheckedChange = null, enabled = rowEnabled) }
        } else {
            { Chevron() }
        },
    )
}

/** Current (read back) value plus the change status, e.g. "3 Minuten · bestätigt". */
@Composable
private fun statusLine(value: String, status: ChangeStatus?): String =
    if (status == null) value else stringResource(R.string.dashcam_status_line, value, statusText(status))

@Composable
private fun statusText(status: ChangeStatus): String = when (status) {
    ChangeStatus.Sending -> stringResource(R.string.dashcam_status_sending)
    ChangeStatus.Confirmed -> stringResource(R.string.dashcam_status_confirmed)
    ChangeStatus.Accepted -> stringResource(R.string.dashcam_status_accepted)
    is ChangeStatus.Mismatch -> stringResource(R.string.dashcam_status_mismatch, status.sent.asString(), status.read.asString())
    is ChangeStatus.Failed -> stringResource(R.string.dashcam_status_failed, errorText(status.error))
    is ChangeStatus.Unknown -> stringResource(R.string.dashcam_status_unknown, errorText(status.error))
    is ChangeStatus.Unconfirmed -> stringResource(R.string.dashcam_status_unconfirmed, errorText(status.error))
}

@Composable
private fun bandText(frequency: Int?): String = when (frequency) {
    RecorderValues.WIFI_2_4_GHZ -> stringResource(R.string.dashcam_band_24)
    RecorderValues.WIFI_5_GHZ -> stringResource(R.string.dashcam_band_5)
    null -> stringResource(R.string.dashcam_value_missing)
    else -> stringResource(R.string.dashcam_value_unknown, frequency)
}

private fun rawText(value: String?): UiText = value?.let(UiText::Dynamic) ?: UiText.Res(R.string.dashcam_value_missing)

@Composable
private fun OptionDialog(setting: RecorderSetting, current: Int?, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(setting.title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                setting.options.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(option.value == current, role = Role.RadioButton) { onSelect(option.value) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = option.value == current, onClick = null)
                        Text(stringResource(option.label))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Changes the hotspot password only, like the vendor dialog; the SSID is shown read-only. The password is typed
 * twice and validated with [isValidWifiPassword]; it is kept out of saved state. Shows the mode and band that will
 * be sent ([wifiToSend]) and a warning: the change is untested on the recorder.
 */
@Composable
private fun WifiDialog(current: WifiParam, supportedModes: List<Int>?, onSubmit: (password: String) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val invalid = password.isNotEmpty() && !isValidWifiPassword(password)
    val differs = repeat.isNotEmpty() && repeat != password
    val missing = stringResource(R.string.dashcam_value_missing)
    val transformation = if (visible) VisualTransformation.None else PasswordVisualTransformation()
    val keyboard = KeyboardOptions(keyboardType = KeyboardType.Password)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dashcam_wifi_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.dashcam_wifi_ssid_readonly, current.ssid ?: missing))
                OutlinedTextField(
                    password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.dashcam_wifi_password)) },
                    supportingText = {
                        Text(stringResource(if (password.any { it !in '!'..'~' }) R.string.dashcam_wifi_password_ascii else R.string.dashcam_wifi_password_rule))
                    },
                    isError = invalid,
                    singleLine = true,
                    visualTransformation = transformation,
                    keyboardOptions = keyboard,
                )
                OutlinedTextField(
                    repeat,
                    onValueChange = { repeat = it },
                    label = { Text(stringResource(R.string.dashcam_wifi_password_repeat)) },
                    supportingText = if (differs) ({ Text(stringResource(R.string.dashcam_wifi_password_not_equal)) }) else null,
                    isError = differs,
                    singleLine = true,
                    visualTransformation = transformation,
                    keyboardOptions = keyboard,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(visible, role = Role.Checkbox) { visible = it },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = visible, onCheckedChange = null)
                    Text(stringResource(R.string.dashcam_wifi_password_show))
                }
                val sent = wifiToSend(current, password, supportedModes)
                Text(
                    stringResource(R.string.dashcam_wifi_sent, sent.mode?.toString() ?: missing, sent.frequency?.toString() ?: missing) +
                        if (sent.mode != current.mode) {
                            "\n" + stringResource(R.string.dashcam_wifi_mode_from_capability, sent.mode.toString(), current.mode?.toString() ?: missing)
                        } else {
                            ""
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stringResource(R.string.dashcam_wifi_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(password) }, enabled = isValidWifiPassword(password) && repeat == password) {
                Text(stringResource(R.string.dashcam_send))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
