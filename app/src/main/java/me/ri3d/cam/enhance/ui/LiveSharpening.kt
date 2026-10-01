package me.ri3d.cam.enhance.ui

import android.graphics.RenderEffect
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.ri3d.cam.R
import me.ri3d.cam.core.data.PreferencesRepository
import me.ri3d.cam.core.log.Log
import me.ri3d.cam.core.model.AppPreferences
import me.ri3d.cam.core.model.ExportQuality
import me.ri3d.cam.enhance.ENHANCE_STORE
import me.ri3d.cam.enhance.LiveSharpen
import me.ri3d.cam.enhance.LiveUpscaleDecision
import me.ri3d.cam.enhance.LiveUpscaleProbe
import me.ri3d.cam.live.LiveFrameSource
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Whether live sharpening may be offered on this phone. [LiveUpscaleProbe] runs at most once per process, on the
 * first screen that asks ([ensureProbed]: Live view or the settings); until it finishes, the decision of the previous
 * start (stored in the enhance DataStore) applies.
 */
@Singleton
class LiveSharpening @Inject constructor(
    private val probe: LiveUpscaleProbe,
    @Named(ENHANCE_STORE) private val store: DataStore<Preferences>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)

    /** Null until any probe has finished on this phone. */
    val decision: StateFlow<LiveUpscaleDecision?> = store.data
        .catch { Log.w(TAG, "reading the live probe failed", it) }
        .map(::read)
        .stateIn(scope, SharingStarted.Eagerly, null)

    // ponytail: on the Live screen the probe can overlap the stream start (≈ 1 s of GPU work); a busy GPU errs towards "no".
    fun ensureProbed(): Job? {
        if (!started.compareAndSet(false, true)) return null
        return scope.launch {
            val d = try {
                probe.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "live probe failed: ${e.javaClass.simpleName}")
                LiveUpscaleDecision(false, LiveUpscaleDecision.Reason.PROBE_FAILED, null, null, null, null)
            }
            runCatching {
                store.edit {
                    it[OFFER] = d.offer
                    it[REASON] = d.reason.name
                    d.classicalMs720p?.let { ms -> it[MS_720P] = ms } ?: it.remove(MS_720P)
                }
            }.onFailure { Log.w(TAG, "storing the live probe failed", it) }
        }
    }

    private companion object {
        const val TAG = "LiveSharpening"
        val OFFER = booleanPreferencesKey("live_probe_offer")
        val REASON = stringPreferencesKey("live_probe_reason")
        val MS_720P = floatPreferencesKey("live_probe_classical_ms_720p")

        fun read(p: Preferences): LiveUpscaleDecision? {
            val reason = LiveUpscaleDecision.Reason.entries.firstOrNull { it.name == p[REASON] } ?: return null
            return LiveUpscaleDecision(p[OFFER] == true, reason, null, p[MS_720P], null, null)
        }
    }
}

/** Shared by the live toggle/effect and the settings screen; starts the probe once per process. */
@HiltViewModel
class EnhanceSettingsViewModel @Inject constructor(
    sharpening: LiveSharpening,
    private val preferences: PreferencesRepository,
    frames: LiveFrameSource,
) : ViewModel() {
    val decision: StateFlow<LiveUpscaleDecision?> = sharpening.decision
    val prefs: StateFlow<AppPreferences> = preferences.preferences.stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferences())
    val videoSize: StateFlow<IntSize?> = frames.videoSize

    init {
        sharpening.ensureProbed()
    }

    fun setLiveSharpen(on: Boolean) = update { it.copy(liveUpscale = on) }

    fun setExportQuality(quality: ExportQuality) = update { it.copy(exportQuality = quality) }

    private fun update(transform: (AppPreferences) -> AppPreferences) {
        viewModelScope.launch {
            // A failed DataStore write must not take the process down; the old value stays.
            try {
                preferences.update(transform)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("EnhanceSettings", "saving failed", e)
            }
        }
    }
}

/** The probe said yes on this phone; `RuntimeShader` needs Android 13. */
internal fun LiveUpscaleDecision?.offered() = this?.offer == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/** Live view trailing control (M3Live "Upscale"): toggles `AppPreferences.liveUpscale`; disabled with a note when not offered. */
@Composable
fun LiveSharpenControl(viewModel: EnhanceSettingsViewModel = hiltViewModel()) {
    val decision by viewModel.decision.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val offered = decision.offered()
    val on = offered && prefs.liveUpscale
    Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledIconToggleButton(
            checked = on,
            onCheckedChange = viewModel::setLiveSharpen,
            enabled = offered,
            // 56 dp, 12 dp lower than the 80 dp screenshot button: centred on it as in the artboard.
            modifier = Modifier.padding(top = 12.dp).size(56.dp),
            shape = RoundedCornerShape(if (on) 16.dp else 28.dp),
        ) {
            Icon(painterResource(R.drawable.ic_upscale), contentDescription = stringResource(R.string.enhance_live_sharpen_action))
        }
        Text(
            stringResource(R.string.enhance_live_sharpen),
            modifier = Modifier.clearAndSetSemantics { }, // the button already says it
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val note = when {
            on -> R.string.enhance_live_sharpen_on_note
            decision == null -> R.string.enhance_live_sharpen_checking
            !offered -> R.string.enhance_live_sharpen_unavailable
            else -> null
        }
        note?.let { Text(stringResource(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
    }
}

/** Live view `renderEffect` slot: [LiveSharpen] scaled to the displayed size, or null when off / not offered / no stream. */
@Composable
fun liveSharpenEffect(videoRect: Rect, viewModel: EnhanceSettingsViewModel = hiltViewModel()): RenderEffect? {
    val decision by viewModel.decision.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val videoSize by viewModel.videoSize.collectAsStateWithLifecycle()
    val scale = videoSize?.takeIf { decision.offered() && prefs.liveUpscale && videoRect.width > 0 }?.let { videoRect.width / it.width }
    return remember(scale) { scale?.let { LiveSharpen.effect(it) } }
}
