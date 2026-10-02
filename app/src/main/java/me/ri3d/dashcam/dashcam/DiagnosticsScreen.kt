package me.ri3d.dashcam.dashcam

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.ri3d.dashcam.BuildConfig
import me.ri3d.dashcam.R
import me.ri3d.dashcam.core.log.redact
import me.ri3d.dashcam.core.ui.AxoTopBar
import me.ri3d.dashcam.recorder.CapabilityGroup
import me.ri3d.dashcam.recorder.RecorderClient
import me.ri3d.dashcam.recorder.RecorderCommand
import me.ri3d.dashcam.recorder.RecorderDiagnostic
import me.ri3d.dashcam.recorder.RecorderError
import me.ri3d.dashcam.recorder.RecorderReply
import me.ri3d.dashcam.recorder.RecorderResult
import java.io.File
import java.time.Instant
import javax.inject.Inject

/** Shares cacheDir/diagnostics (paths in the manifest meta-data); own subclass so features do not clash in the manifest. */
class DiagnosticsFileProvider : FileProvider()

/** Read-only queries of the capture: the app path ones, then SDK-only capability queries with a short timeout. */
private val CAPTURE: List<Pair<RecorderCommand, Long>> = listOf(
    RecorderCommand.GetDeviceInfo to RecorderClient.DEFAULT_REQUEST_TIMEOUT_MS,
    RecorderCommand.GetAllSettings to RecorderClient.DEFAULT_REQUEST_TIMEOUT_MS,
    RecorderCommand.GetStorageInfo() to RecorderClient.DEFAULT_REQUEST_TIMEOUT_MS,
    RecorderCommand.GetCapabilities(CapabilityGroup.BASIC) to RecorderClient.DEFAULT_REQUEST_TIMEOUT_MS,
) + listOf(CapabilityGroup.ALL, CapabilityGroup.IMAGE, CapabilityGroup.NETWORK, CapabilityGroup.STORAGE, CapabilityGroup.INTELLIGENT)
    .map { RecorderCommand.GetCapabilities(it) to BEST_EFFORT_TIMEOUT_MS }

private const val BEST_EFFORT_TIMEOUT_MS = 5_000L

/**
 * The owner's hardware-verification capture: Wi-Fi facts, session reply, raw replies of the read-only queries
 * (errors and -205 timeouts kept), and the client's frame log. Every JSON text passes `redact` (token, tokenNum,
 * aescode, passwd, password, key, …); nothing is ever sent that changes the recorder.
 */
suspend fun captureDiagnostics(manager: RecorderConnectionManager, onStep: (Int) -> Unit = {}): JsonObject {
    manager.refreshSsid()
    val state = manager.state.value
    val commands = if (state is RecorderConnectionState.Ready) {
        CAPTURE.map { (cmd, timeout) ->
            onStep(cmd.msgId)
            cmd.msgId.toString() to when (val r = manager.request(cmd, { it }, timeout)) {
                is RecorderResult.Ok -> buildJsonObject {
                    put("rval", r.reply.rval)
                    put("reply", redactedJson(r.reply.rawJson))
                }
                is RecorderResult.Failed -> r.error.toJson()
            }
        }
    } else {
        emptyList()
    }
    val log = manager.diagnosticLog()
    return buildJsonObject {
        put("format", "myforthing-diagnostics/1")
        put("capturedAt", Instant.now().toString())
        put("appVersion", BuildConfig.VERSION_NAME)
        put("androidSdk", Build.VERSION.SDK_INT)
        putJsonObject("network") {
            put("ssid", manager.ssid.value)
            put("recorderNetworkBound", manager.recorderNetwork.value != null)
            put("mobileDataEnabled", manager.mobileDataEnabled())
            put("simulator", manager.simulator.value)
        }
        put("connectionState", state::class.simpleName)
        (state as? RecorderConnectionState.Error)?.let { put("connectionError", it.error.toJson()) }
        putJsonObject("session") {
            (state as? RecorderConnectionState.Ready)?.session?.let {
                put("version", it.version)
                put("productType", it.productType)
                put("timeOut", it.timeoutSec)
            }
            val reply = log.lastOrNull { it is RecorderDiagnostic.FrameReceived && it.json?.let(RecorderReply::parse)?.msgId == 1 }
            put("reply", (reply as? RecorderDiagnostic.FrameReceived)?.json?.let(::redactedJson) ?: JsonNull)
        }
        putJsonObject("commands") {
            if (commands.isEmpty()) put("skipped", "not connected")
            commands.forEach { (msgId, result) -> put(msgId, result) }
        }
        putJsonArray("frames") { log.forEach { add(it.toJson()) } }
    }
}

private fun RecorderError.toJson(): JsonObject = buildJsonObject {
    put("errorCode", code)
    put("source", source.name)
    put("message", message)
    rawJson?.let { put("reply", redactedJson(it)) }
}

private fun redactedJson(raw: String): JsonElement {
    val text = redact(raw)
    return runCatching { Json.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }
}

/** Events arrive redacted from :recorder; `redact` again covers the app's wider key list. */
private fun RecorderDiagnostic.toJson(): JsonObject = buildJsonObject {
    put("atMs", atMs)
    when (val e = this@toJson) {
        is RecorderDiagnostic.FrameSent -> {
            put("type", "sent"); put("seq", e.seq); put("hex", e.hex); put("json", redactedJson(e.json))
        }
        is RecorderDiagnostic.FrameReceived -> {
            put("type", "received"); put("seq", e.seq); put("hex", e.hex); put("json", e.json?.let(::redactedJson) ?: JsonNull)
        }
        is RecorderDiagnostic.Garbage -> {
            put("type", "garbage"); put("hex", e.hex)
        }
        is RecorderDiagnostic.Dropped -> {
            put("type", "dropped"); put("seq", e.seq); put("reason", e.reason)
        }
        is RecorderDiagnostic.Info -> {
            put("type", "info"); put("message", e.message)
        }
    }
}

private val prettyJson = Json { prettyPrint = true }

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(private val manager: RecorderConnectionManager) : ViewModel() {
    val state: StateFlow<RecorderConnectionState> = manager.state
    private val _report = MutableStateFlow<String?>(null)
    val report: StateFlow<String?> = _report.asStateFlow()

    /** msgId being queried while a capture runs, 0 for the local part; null when idle. */
    private val _step = MutableStateFlow<Int?>(null)
    val step: StateFlow<Int?> = _step.asStateFlow()

    fun capture() {
        if (_step.value != null) return
        viewModelScope.launch {
            _step.value = 0
            val json = captureDiagnostics(manager) { _step.value = it }
            _report.value = prettyJson.encodeToString(JsonObject.serializer(), json)
            _step.value = null
        }
    }
}

@Composable
fun DiagnosticsScreen(onBack: () -> Unit, viewModel: DiagnosticsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val report by viewModel.report.collectAsStateWithLifecycle()
    val step by viewModel.step.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(topBar = { AxoTopBar(stringResource(R.string.dashcam_diagnostics_title), onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.dashcam_diagnostics_text), style = MaterialTheme.typography.bodyMedium)
            if (state !is RecorderConnectionState.Ready) {
                Text(
                    stringResource(R.string.dashcam_diagnostics_not_connected),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::capture, enabled = step == null) { Text(stringResource(R.string.dashcam_diagnostics_capture)) }
                val json = report
                if (json != null) {
                    OutlinedButton(onClick = { shareDiagnostics(context, json) }, enabled = step == null) {
                        Text(stringResource(R.string.dashcam_diagnostics_share))
                    }
                }
            }
            step?.let {
                Text(
                    if (it == 0) stringResource(R.string.state_view_loading) else stringResource(R.string.dashcam_diagnostics_step, it),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            report?.let { json ->
                Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                    SelectionContainer {
                        Text(
                            json,
                            Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

/** Writes the report to cacheDir/diagnostics and opens the share sheet. */
private fun shareDiagnostics(context: Context, json: String) {
    val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() } // only the latest capture is kept
    val file = File(dir, "myforthing-diagnose-${Instant.now().toString().replace(':', '-')}.json").apply { writeText(json) }
    val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.dashcam.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/json")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.dashcam_diagnostics_share)))
}
