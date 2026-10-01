package me.ri3d.cam.plates.ui

import android.media.MediaMetadataRetriever
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import me.ri3d.cam.R
import me.ri3d.cam.core.navigation.PlateDetail
import me.ri3d.cam.core.navigation.Route
import me.ri3d.cam.core.ui.SectionHeader
import me.ri3d.cam.core.ui.listRowShape
import me.ri3d.cam.media.MediaItem
import me.ri3d.cam.media.MediaKind
import me.ri3d.cam.plates.PlateRepository
import me.ri3d.cam.plates.PlateSighting
import java.io.File
import javax.inject.Inject
import kotlin.math.abs

/** Plates of the clip on the [me.ri3d.cam.core.navigation.Clip] screen (`mediaId` from its route). */
@HiltViewModel
class ClipPlatesViewModel @Inject constructor(
    handle: SavedStateHandle,
    repository: PlateRepository,
    private val scans: ClipScans,
) : ViewModel() {
    val mediaId: String = checkNotNull(handle["mediaId"])

    val sightings: StateFlow<List<PlateSighting>> = repository.sightingsFor(mediaId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val scan: StateFlow<ClipScans.State> = scans.state

    fun check(item: MediaItem) = scans.enqueue(item)

    fun cancel() = scans.cancel(mediaId)
}

/** Sightings drawn by the clip overlay: within this distance of the playback position. */
const val CLIP_OVERLAY_WINDOW_MS = 500L

/** Sightings near [positionMs] (± [CLIP_OVERLAY_WINDOW_MS]). */
fun List<PlateSighting>.near(positionMs: Long) = filter { s -> s.positionMs?.let { abs(it - positionMs) <= CLIP_OVERLAY_WINDOW_MS } == true }

/**
 * `clipExtras`: "Kennzeichen in diesem Clip" – the sightings by plate with jump links, and "Clip auf Kennzeichen
 * prüfen" with progress and cancel. Derived items are refused with a short explanation.
 */
@Composable
fun ClipPlates(
    item: MediaItem,
    seekTo: (Long) -> Unit,
    onNavigate: (Route) -> Unit,
    viewModel: ClipPlatesViewModel = hiltViewModel(),
) {
    if (item.kind != MediaKind.ORIGINAL_VIDEO && !item.isDerived) return // photos and screenshots: no clip check
    val sightings by viewModel.sightings.collectAsStateWithLifecycle()
    val scan by viewModel.scan.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(stringResource(R.string.plates_clip_title))
        if (item.isDerived) {
            Hint(stringResource(R.string.plates_clip_derived), Modifier.padding(horizontal = 16.dp))
            return@Column
        }
        CheckState(item, scan, viewModel)
        val byPlate = sightings.groupBy { it.plateId }.entries.toList()
        byPlate.forEachIndexed { index, (plateId, list) ->
            Surface(
                onClick = { onNavigate(PlateDetail(plateId)) },
                shape = listRowShape(index, byPlate.size),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        PlateChip(list.first().display)
                        Text(
                            pluralStringResource(R.plurals.plates_seen_in_clip, list.size, list.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FlowRow {
                        list.mapNotNull { it.positionMs }.forEach { ms ->
                            val time = DateUtils.formatElapsedTime(ms / 1000)
                            val jump = stringResource(R.string.plates_clip_jump, time)
                            TextButton(onClick = { seekTo(ms) }, modifier = Modifier.semantics { contentDescription = jump }) { Text(time) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckState(item: MediaItem, scan: ClipScans.State, viewModel: ClipPlatesViewModel) {
    val note = Modifier.padding(horizontal = 16.dp)
    when (item.id) {
        scan.running -> Column(note, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.plates_clip_checking), style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(progress = { scan.fraction }, modifier = Modifier.weight(1f))
                TextButton(onClick = viewModel::cancel) { Text(stringResource(R.string.action_cancel)) }
            }
        }
        in scan.queued -> Row(note, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.plates_clip_queued), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = viewModel::cancel) { Text(stringResource(R.string.action_cancel)) }
        }
        else -> Column(note, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            scan.results[item.id]?.let { Hint(resultText(it)) }
            val local = ClipScans.scannable(item)
            OutlinedButton(onClick = { viewModel.check(item) }, enabled = local) { Text(stringResource(R.string.plates_clip_check)) }
            if (!local) Hint(stringResource(R.string.plates_clip_download_first))
        }
    }
}

@Composable
private fun resultText(result: ClipScans.Result): String = when (result) {
    is ClipScans.Result.Done ->
        if (result.plates == 0) stringResource(R.string.plates_clip_none) else pluralStringResource(R.plurals.plates_clip_found, result.plates, result.plates)
    ClipScans.Result.Cancelled -> stringResource(R.string.plates_clip_cancelled)
    ClipScans.Result.Failed -> stringResource(R.string.plates_clip_failed)
}

/** `clipOverlay`: boxes of sightings within ± 500 ms of [positionMs], fitted like the player (letterboxed). */
@Composable
fun BoxScope.ClipPlatesOverlay(item: MediaItem, positionMs: Long, viewModel: ClipPlatesViewModel = hiltViewModel()) {
    if (item.kind != MediaKind.ORIGINAL_VIDEO) return
    val file = item.localFile ?: return
    val video by produceState<IntSize?>(null, file) { value = withContext(Dispatchers.IO) { videoSize(file) } }
    val sightings by viewModel.sightings.collectAsStateWithLifecycle()
    val size = video ?: return
    val near = sightings.near(positionMs)
    if (near.isEmpty()) return
    BoxWithConstraints(Modifier.matchParentSize()) {
        val rect = fitRect(Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()), size)
        PlateOverlay(near.map { OverlayPlate(it.display, it.confidence == null, Rect(it.boxLeft, it.boxTop, it.boxRight, it.boxBottom)) }, size, rect)
    }
}

/** Video pixels as `ClipPlateScanner` stores boxes. ponytail: rotation and pixel aspect are ignored (dashcam clips have none). */
private fun videoSize(file: File): IntSize? = runCatching {
    MediaMetadataRetriever().run {
        try {
            setDataSource(file.path)
            val w = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            if (w != null && h != null && w > 0 && h > 0) IntSize(w, h) else null
        } finally {
            release()
        }
    }
}.getOrNull()

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PlatesEntryPoint {
    fun clipScans(): ClipScans
}

/** Starts the automatic clip check (`platesClips`) once per process; placed once in the app's root composable. */
@Composable
fun PlatesAutoScan() {
    val context = LocalContext.current.applicationContext
    LaunchedEffect(Unit) { EntryPointAccessors.fromApplication(context, PlatesEntryPoint::class.java).clipScans().startAutoScan() }
}
