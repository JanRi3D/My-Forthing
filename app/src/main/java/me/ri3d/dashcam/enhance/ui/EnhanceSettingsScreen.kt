package me.ri3d.dashcam.enhance.ui

import android.content.Context
import androidx.annotation.StringRes
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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ri3d.dashcam.R
import me.ri3d.dashcam.core.model.ExportQuality
import me.ri3d.dashcam.core.ui.AxoTopBar
import me.ri3d.dashcam.core.ui.ListGroup
import me.ri3d.dashcam.core.ui.ListRow
import me.ri3d.dashcam.core.ui.SectionHeader
import me.ri3d.dashcam.enhance.LiveUpscaleDecision
import me.ri3d.dashcam.enhance.LiveUpscaleProbe

/** Settings → "Verbesserung & Hochskalierung": export quality, live sharpening, honesty note, model licences. */
@Composable
fun EnhanceSettingsScreen(onBack: () -> Unit, viewModel: EnhanceSettingsViewModel = hiltViewModel()) {
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val decision by viewModel.decision.collectAsStateWithLifecycle()
    Scaffold(topBar = { AxoTopBar(stringResource(R.string.enhance_settings_title), onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeader(stringResource(R.string.enhance_settings_export))
            ListGroup(
                ExportQuality.entries.map { quality ->
                    { shape ->
                        ChoiceRow(stringResource(quality.label), null, selected = prefs.exportQuality == quality, enabled = true, shape = shape) {
                            viewModel.setExportQuality(quality)
                        }
                    }
                },
                Modifier.selectableGroup(),
            )
            Note(stringResource(R.string.enhance_settings_export_text))

            SectionHeader(stringResource(R.string.enhance_settings_live))
            val offered = decision.offered()
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .toggleable(offered && prefs.liveUpscale, enabled = offered, role = Role.Switch, onValueChange = viewModel::setLiveSharpen)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.enhance_settings_live_toggle), style = MaterialTheme.typography.bodyLarge)
                    Text(liveText(LocalContext.current, decision), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = offered && prefs.liveUpscale, onCheckedChange = null, enabled = offered)
            }

            SectionHeader(stringResource(R.string.enhance_settings_honesty_title))
            Note(stringResource(R.string.enhance_settings_honesty))

            SectionHeader(stringResource(R.string.enhance_settings_licences))
            Note(stringResource(R.string.enhance_settings_model))
            Licences()
        }
    }
}

internal val ExportQuality.label
    get() = when (this) {
        ExportQuality.Q1080 -> R.string.upscale_p1080
        ExportQuality.Q1440 -> R.string.upscale_p1440
        ExportQuality.Q2160 -> R.string.upscale_p2160
    }

/** Why live sharpening is (not) offered, from the probe on this phone. */
@StringRes
internal fun liveTextRes(decision: LiveUpscaleDecision?) = when {
    decision == null -> R.string.enhance_settings_live_checking
    decision.offered() -> R.string.enhance_settings_live_text
    decision.reason == LiveUpscaleDecision.Reason.GPU_TOO_SLOW ->
        if (decision.classicalMs720p != null) R.string.enhance_settings_live_slow else R.string.enhance_settings_live_slow_plain
    decision.reason == LiveUpscaleDecision.Reason.PROBE_FAILED -> R.string.enhance_settings_live_failed
    else -> R.string.enhance_settings_live_api
}

private fun liveText(context: Context, decision: LiveUpscaleDecision?): String =
    context.getString(liveTextRes(decision), decision?.classicalMs720p ?: 0f, LiveUpscaleProbe.FRAME_BUDGET_MS)

/** `assets/models/LICENSE-*.txt` and `NOTICE-*.txt`, each expandable. */
@Composable
private fun Licences() {
    val context = LocalContext.current
    val texts by produceState(emptyList<Pair<String, String>>()) {
        value = withContext(Dispatchers.IO) {
            context.assets.list(MODELS).orEmpty()
                .filter { it.endsWith(".txt") && (it.startsWith("LICENSE-") || it.startsWith("NOTICE-")) }
                .sorted()
                .map { name -> name to context.assets.open("$MODELS/$name").bufferedReader().use { it.readText() } }
        }
    }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    texts.forEach { (name, text) ->
        val expanded = open == name
        ListRow(
            name,
            supporting = stringResource(if (expanded) R.string.enhance_settings_licence_hide else R.string.enhance_settings_licence_show),
            onClick = { open = if (expanded) null else name },
        )
        if (expanded) {
            Text(text, Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

private const val MODELS = "models"

@Composable
private fun Note(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
