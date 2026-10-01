package to.axolotl.cam.dashcam

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import to.axolotl.cam.R
import to.axolotl.cam.core.log.redact
import to.axolotl.cam.core.navigation.Connection
import to.axolotl.cam.core.navigation.Diagnostics
import to.axolotl.cam.core.navigation.Route
import to.axolotl.cam.core.ui.ConfirmDialog
import to.axolotl.cam.core.ui.ListGroup
import to.axolotl.cam.core.ui.ListRow
import to.axolotl.cam.core.ui.SectionHeader
import to.axolotl.cam.core.ui.UiText
import to.axolotl.cam.core.ui.asString
import to.axolotl.cam.recorder.ChannelSettings
import to.axolotl.cam.recorder.DeviceInfo
import to.axolotl.cam.recorder.OsdInfo
import to.axolotl.cam.recorder.RecorderCommand
import to.axolotl.cam.recorder.RecorderError
import to.axolotl.cam.recorder.RecorderResult
import to.axolotl.cam.recorder.RecorderSettings
import to.axolotl.cam.recorder.RecorderValues
import to.axolotl.cam.recorder.SettingsPatch
import to.axolotl.cam.recorder.WifiParam
import to.axolotl.cam.recorder.parseSettings
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
 * The Wi-Fi dialog resubmits the whole object read back (no chanNo): `mode` and `frequency` exactly as read,
 * never converted. An empty [password] keeps the current one.
 */
fun wifiToSend(read: WifiParam, ssid: String, password: String): WifiParam =
    read.copy(ssid = ssid, passwd = password.ifEmpty { read.passwd })

/** The original dialog's check: at least 8 characters with a letter and a digit. */
fun isValidWifiPassword(password: String): Boolean =
    password.length >= 8 && password.any(Char::isLetter) && password.any(Char::isDigit)

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

    /** rval 0 but the readback differs. */
    data class Mismatch(val sent: UiText, val read: UiText) : ChangeStatus

    /** The change itself failed. */
    data class Failed(val error: RecorderError) : ChangeStatus

    /** rval 0, but the readback failed (e.g. the recorder restarted its Wi-Fi). */
    data class Unconfirmed(val error: RecorderError) : ChangeStatus
}

data class RecorderSettingsUi(
    val settings: RecorderSettings? = null,
    val loading: Boolean = false,
    val loadError: RecorderError? = null,
    /** By [RecorderSetting.key], or [WIFI_KEY]. */
    val status: Map<String, ChangeStatus> = emptyMap(),
    val reset: ChangeStatus? = null,
    /** After a successful Wi-Fi change or factory reset: ask the user to rejoin the recorder Wi-Fi. */
    val rejoinWifi: Boolean = false,
) {
    companion object {
        const val WIFI_KEY = "wifi"
    }
}

@HiltViewModel
class RecorderSettingsViewModel @Inject constructor(private val manager: RecorderConnectionManager) : ViewModel() {
    val connection: StateFlow<RecorderConnectionState> = manager.state
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
                is RecorderResult.Ok -> _ui.update { it.copy(settings = r.value, loading = false, loadError = null) }
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

    fun changeWifi(ssid: String, password: String) {
        val read = _ui.value.settings?.global?.wifi ?: return
        val sent = wifiToSend(read, ssid, password)
        viewModelScope.launch {
            val accepted = sendAndConfirm(RecorderSettingsUi.WIFI_KEY, SettingsPatch(wifi = sent)) { readback ->
                val got = readback.global.wifi
                when {
                    got?.ssid == sent.ssid && got?.passwd == sent.passwd -> ChangeStatus.Confirmed
                    got?.ssid != sent.ssid -> ChangeStatus.Mismatch(UiText.Dynamic(sent.ssid.orEmpty()), got?.ssid?.let(UiText::Dynamic) ?: UiText.Res(R.string.dashcam_value_missing))
                    else -> ChangeStatus.Mismatch(UiText.Res(R.string.dashcam_wifi_password_sent), UiText.Res(R.string.dashcam_wifi_password_differs))
                }
            }
            if (accepted) _ui.update { it.copy(rejoinWifi = true) }
        }
    }

    fun factoryReset() {
        viewModelScope.launch {
            _ui.update { it.copy(reset = ChangeStatus.Sending) }
            val r = manager.request(RecorderCommand.FactoryReset, { })
            // No readback: what the reset changes is up to the firmware (Wi-Fi may restart).
            _ui.update {
                it.copy(
                    reset = if (r is RecorderResult.Failed) ChangeStatus.Failed(r.error) else ChangeStatus.Confirmed,
                    rejoinWifi = it.rejoinWifi || r is RecorderResult.Ok,
                )
            }
            if (r is RecorderResult.Ok) load()
        }
    }

    fun dismissRejoin() = _ui.update { it.copy(rejoinWifi = false) }

    /** 8192, then 4097 readback; returns whether the recorder accepted the change (rval 0). */
    private suspend fun sendAndConfirm(key: String, patch: SettingsPatch, check: (RecorderSettings) -> ChangeStatus): Boolean {
        if (_ui.value.status[key] == ChangeStatus.Sending) return false
        setStatus(key, ChangeStatus.Sending)
        val sent = manager.request(RecorderCommand.SetSettings(patch), { })
        if (sent is RecorderResult.Failed) {
            setStatus(key, ChangeStatus.Failed(sent.error))
            return false
        }
        when (val read = manager.request(RecorderCommand.GetAllSettings, ::parseSettings)) {
            is RecorderResult.Ok -> {
                _ui.update { it.copy(settings = read.value) }
                setStatus(key, check(read.value))
            }
            is RecorderResult.Failed -> setStatus(key, ChangeStatus.Unconfirmed(read.error))
        }
        return true
    }

    private fun setStatus(key: String, status: ChangeStatus) = _ui.update { it.copy(status = it.status + (key to status)) }
}

/** Option label, or the raw number for values the original app does not offer. */
fun label(setting: RecorderSetting, value: Int?): UiText {
    if (value == null) return UiText.Res(R.string.dashcam_value_missing)
    return setting.options.firstOrNull { it.value == value }?.let { UiText.Res(it.label) }
        ?: UiText.Res(R.string.dashcam_value_unknown, listOf(value))
}

/** Rendered by core Settings below the app section (settingsGraph slot). */
@Composable
fun RecorderSettingsSection(onNavigate: (Route) -> Unit, viewModel: RecorderSettingsViewModel = hiltViewModel()) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val ready = connection is RecorderConnectionState.Ready
    val settings = ui.settings
    val enabled = ready && settings != null
    var optionDialog by rememberSaveable { mutableStateOf<RecorderSetting?>(null) }
    var wifiDialog by rememberSaveable { mutableStateOf(false) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            !ready -> NotConnectedNotice { onNavigate(Connection) }
            settings == null && ui.loadError != null -> {
                Text(errorText(ui.loadError!!), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = viewModel::load, Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.action_retry)) }
            }
            settings == null -> Text(stringResource(R.string.state_view_loading), Modifier.padding(horizontal = 16.dp))
        }

        val onRow: (RecorderSetting) -> Unit = { setting ->
            if (setting.isSwitch) {
                val current = settings?.value(setting)
                viewModel.change(setting, if (current == RecorderValues.ON) RecorderValues.OFF else RecorderValues.ON)
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
                    else stringResource(R.string.dashcam_wifi_current, wifi.ssid ?: "–", bandText(wifi.frequency))
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
        val info: DeviceInfo? = (connection as? RecorderConnectionState.Ready)?.info
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
        WifiDialog(settings.global.wifi!!, onSubmit = { ssid, password ->
            wifiDialog = false
            viewModel.changeWifi(ssid, password)
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
    val rowEnabled = enabled && status != ChangeStatus.Sending
    var supporting = statusLine(label(setting, value).asString(), status)
    if (setting == RecorderSetting.OSD && settings != null) {
        val content = (settings.channel()?.raw?.get("osd") as? JsonObject)?.get("osdContent")?.toString() ?: "–"
        supporting += "\n" + stringResource(R.string.dashcam_osd_content, content)
    }
    ListRow(
        stringResource(setting.title),
        modifier = Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA),
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
    is ChangeStatus.Mismatch -> stringResource(R.string.dashcam_status_mismatch, status.sent.asString(), status.read.asString())
    is ChangeStatus.Failed -> stringResource(R.string.dashcam_status_failed, errorText(status.error))
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

@Composable
private fun WifiDialog(current: WifiParam, onSubmit: (ssid: String, password: String) -> Unit, onDismiss: () -> Unit) {
    var ssid by rememberSaveable { mutableStateOf(current.ssid.orEmpty()) }
    var password by remember { mutableStateOf("") } // not saveable: the password stays out of saved state
    val passwordInvalid = password.isNotEmpty() && !isValidWifiPassword(password)
    val passwordOk = if (password.isEmpty()) current.passwd != null else !passwordInvalid
    val changed = ssid != current.ssid || password.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dashcam_set_wifi)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    ssid,
                    onValueChange = { ssid = it },
                    label = { Text(stringResource(R.string.dashcam_wifi_ssid)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.dashcam_wifi_password)) },
                    supportingText = { Text(stringResource(R.string.dashcam_wifi_password_rule)) },
                    isError = passwordInvalid,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Text(
                    stringResource(R.string.dashcam_wifi_preserved, current.mode?.toString() ?: "–", current.frequency?.toString() ?: "–"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(ssid, password) }, enabled = ssid.isNotBlank() && passwordOk && changed) {
                Text(stringResource(R.string.dashcam_send))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
