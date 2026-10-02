package me.ri3d.dashcam.plates.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ri3d.dashcam.R
import me.ri3d.dashcam.core.data.PreferencesRepository
import me.ri3d.dashcam.core.navigation.PlateDetail
import me.ri3d.dashcam.core.navigation.Plates
import me.ri3d.dashcam.core.navigation.Route
import me.ri3d.dashcam.core.ui.ListGroup
import me.ri3d.dashcam.core.ui.SectionHeader
import me.ri3d.dashcam.live.LiveFrameSource
import me.ri3d.dashcam.plates.LivePlateProcessor
import me.ri3d.dashcam.plates.Plate
import me.ri3d.dashcam.plates.PlateDetection
import me.ri3d.dashcam.plates.PlateRecognizer
import me.ri3d.dashcam.plates.PlateRepository
import me.ri3d.dashcam.plates.ProcessingStats
import javax.inject.Inject
import javax.inject.Provider

/**
 * Plates in the live view (one per Live back-stack entry, shared by its three slots). Frames are collected only
 * while `platesLive` is on, the screen is resumed and the stream plays; then each run gets its own processor
 * (recognizer closed when the run ends). Sightings are recorded as LIVE through the repository.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class LivePlatesViewModel @Inject constructor(
    private val preferences: PreferencesRepository,
    private val repository: PlateRepository,
    private val recognizers: Provider<PlateRecognizer>,
    private val frames: LiveFrameSource,
) : ViewModel() {
    val enabled: StateFlow<Boolean> = preferences.preferences.map { it.platesLive }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val resumed = MutableStateFlow(false)
    private val processor = MutableStateFlow<LivePlateProcessor?>(null)

    /** Size of the frames handed to the processor: detection boxes are in these pixels. */
    val frameSize = MutableStateFlow<IntSize?>(null)

    /** Detections of the last processed frame; empty while not processing. */
    val detections: StateFlow<List<PlateDetection>> = processor.flatMapLatest { it?.detections ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val stats: StateFlow<ProcessingStats?> = processor.flatMapLatest { p -> p?.stats ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Plates (normalized) seen during this visit. */
    private val seen = MutableStateFlow<Set<String>>(emptySet())

    /** The last three distinct plates of this visit with their history counts. */
    val recent: StateFlow<List<Plate>> = combine(repository.history(), seen, ::recentPlates)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var run: Job? = null

    init {
        viewModelScope.launch {
            combine(enabled, resumed, frames.videoSize) { on, r, size -> on && r && size != null }
                .distinctUntilChanged()
                // The overlay moves between normal and full screen in one frame: a short "off" is not a stop.
                .debounce { active -> if (active) 0 else STOP_DELAY_MS }
                .distinctUntilChanged()
                .collect { active -> if (active) start() else stop() }
        }
        viewModelScope.launch {
            detections.collect { found ->
                if (found.isNotEmpty()) seen.update { keys -> keys + found.map { it.normalized } }
            }
        }
    }

    private fun start() {
        val p = LivePlateProcessor(recognizers.get(), repository, viewModelScope)
        processor.value = p
        run = viewModelScope.launch {
            try {
                frames.frames(FPS, wanted = { p.wantsFrame }).collect { frame ->
                    frameSize.value = IntSize(frame.bitmap.width, frame.bitmap.height)
                    p.submit(frame) // a dropped frame is left to the GC
                }
            } finally {
                p.close()
            }
        }
    }

    private fun stop() {
        run?.cancel()
        run = null
        processor.value = null
    }

    fun setResumed(value: Boolean) {
        resumed.value = value
    }

    fun toggle() {
        viewModelScope.launch { runCatching { preferences.update { it.copy(platesLive = !it.platesLive) } } } // a failed write keeps the old value
    }

    companion object {
        /** Offered rate; the processor's throttle decides how many are processed. */
        const val FPS = 5

        /** How long collection outlives a pause or disposal (switch between normal and full screen). */
        const val STOP_DELAY_MS = 500L
    }
}

/** The plates of [seen] in [history] order (newest first), at most three. */
internal fun recentPlates(history: List<Plate>, seen: Set<String>): List<Plate> = history.filter { it.normalized in seen }.take(3)

/** `leadingControls`: the "Kennzeichen" toggle left of Screenshot (state = `platesLive`). */
@Composable
fun LivePlatesToggle(viewModel: LivePlatesViewModel = hiltViewModel()) {
    val on by viewModel.enabled.collectAsStateWithLifecycle()
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 12 dp above and below: centred on the 80 dp screenshot button, label in line with its label.
        FilledIconToggleButton(
            checked = on,
            onCheckedChange = { viewModel.toggle() },
            modifier = Modifier.padding(vertical = 12.dp).size(56.dp),
            shape = if (on) RoundedCornerShape(16.dp) else CircleShape,
            colors = IconButtonDefaults.filledIconToggleButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Icon(painterResource(R.drawable.ic_plates), contentDescription = stringResource(R.string.plates_recognition))
        }
        Text(
            stringResource(R.string.plates_live_toggle),
            modifier = Modifier.clearAndSetSemantics { }, // the toggle already says it
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** `overlay`: boxes and chips of the last processed frame, mapped from frame pixels into [videoRect]. */
@Composable
fun LivePlatesOverlay(videoRect: Rect, viewModel: LivePlatesViewModel = hiltViewModel()) {
    // Present in normal and full screen while connected: it reports whether the screen is resumed.
    val activity = LocalActivity.current
    LifecycleResumeEffect(viewModel) {
        viewModel.setResumed(true)
        // A rotation keeps the processor; the view model outlives the activity.
        onPauseOrDispose { if (activity?.isChangingConfigurations != true) viewModel.setResumed(false) }
    }
    val detections by viewModel.detections.collectAsStateWithLifecycle()
    val frame by viewModel.frameSize.collectAsStateWithLifecycle()
    val f = frame ?: return
    PlateOverlay(detections.map { OverlayPlate(it.text, it.confidence == null, it.box.toRect()) }, f, videoRect)
}

/** `belowControls`: "Erkannte Kennzeichen" of this visit + "Verlauf", the off note and the processing rate. */
@Composable
fun LivePlatesList(onNavigate: (Route) -> Unit, viewModel: LivePlatesViewModel = hiltViewModel()) {
    val on by viewModel.enabled.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(stringResource(R.string.plates_live_recognized), Modifier.weight(1f))
            TextButton(onClick = { onNavigate(Plates()) }) { Text(stringResource(R.string.plates_history)) }
        }
        val note = Modifier.padding(horizontal = 16.dp)
        when {
            !on -> Hint(stringResource(R.string.plates_live_off), note)
            recent.isEmpty() -> Hint(stringResource(R.string.plates_live_none), note)
            else -> ListGroup(
                recent.map { plate ->
                    { shape -> PlateRow(plate, shape, trailing = timeOfDay(plate.lastSeen)) { onNavigate(PlateDetail(plate.id)) } }
                },
            )
        }
        val fps = stats?.processedFps
        if (on && fps != null && fps > 0f) {
            Hint(stringResource(R.string.plates_live_rate, fps), note)
        }
    }
}

@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** One plate: chip, "x-mal gesehen", [trailing] (time), optional incident badge; opens the detail. */
@Composable
internal fun PlateRow(
    plate: Plate,
    shape: Shape,
    trailing: String?,
    incident: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.widthIn(min = 120.dp)) { PlateChip(plate.display) } // aligns the text column, as on the artboard
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pluralStringResource(R.plurals.plates_seen, plate.count, plate.count),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (incident) IncidentBadge()
                }
                if (trailing != null) {
                    Text(trailing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun IncidentBadge() {
    Text(
        stringResource(R.string.plates_incident),
        Modifier
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
    )
}
