package me.ri3d.dashcam.plates.ui

import android.content.Context
import android.text.format.DateUtils
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.ri3d.dashcam.R
import me.ri3d.dashcam.core.navigation.Clip
import me.ri3d.dashcam.core.navigation.Route
import me.ri3d.dashcam.core.ui.AxoTopBar
import me.ri3d.dashcam.core.ui.ConfirmDialog
import me.ri3d.dashcam.core.ui.SectionHeader
import me.ri3d.dashcam.core.ui.StateView
import me.ri3d.dashcam.core.ui.UiState
import me.ri3d.dashcam.core.ui.listRowShape
import me.ri3d.dashcam.media.MediaCategory
import me.ri3d.dashcam.media.recorderClock
import me.ri3d.dashcam.plates.Plate
import me.ri3d.dashcam.plates.PlateRepository
import me.ri3d.dashcam.plates.SightingRow
import me.ri3d.dashcam.plates.SightingSource
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class PlateDetailData(val plate: Plate, val incident: Boolean, val sightings: List<SightingRow>)

/** Route `PlateDetail(plateId)`. */
@HiltViewModel
class PlateDetailViewModel @Inject constructor(handle: SavedStateHandle, private val repository: PlateRepository) : ViewModel() {
    val plateId: Long = checkNotNull(handle["plateId"])

    val state: StateFlow<UiState<PlateDetailData>> = combine(repository.plate(plateId), repository.sightingRows(plateId)) { p, rows ->
        if (p == null) UiState.Empty else UiState.Ready(PlateDetailData(p.plate, rows.any { it.mediaCategory == MediaCategory.EVENT }, rows))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    /** Deletes this plate's history (sightings and crops); the screen then closes. */
    fun delete() {
        viewModelScope.launch { repository.delete(plateId) }
    }
}

@Composable
fun PlateDetailScreen(onBack: () -> Unit, onNavigate: (Route) -> Unit, viewModel: PlateDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Deleting removes the plate: leave instead of showing "gone".
    var wasReady by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state is UiState.Ready) wasReady = true else if (state is UiState.Empty && wasReady) onBack()
    }
    Scaffold(topBar = { AxoTopBar(stringResource(R.string.plates_detail_title), onBack = onBack) }) { padding ->
        StateView(state, Modifier.padding(padding), emptyText = stringResource(R.string.plates_detail_gone)) { data ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            ) {
                Summary(data)
                SectionHeader(stringResource(R.string.plates_sightings), Modifier.padding(top = 16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    data.sightings.forEachIndexed { i, row -> Sighting(data.plate, row, listRowShape(i, data.sightings.size), onNavigate) }
                }
                DeleteButton(data.plate.display, viewModel::delete)
            }
        }
    }
}

@Composable
private fun Summary(data: PlateDetailData) {
    val context = LocalContext.current
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PlateChip(data.plate.display, Modifier.semantics { heading() }, large = true)
                if (data.incident) Box(Modifier.align(Alignment.CenterVertically)) { IncidentBadge() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(stringResource(R.string.plates_stat_sightings), data.plate.count.toString(), Modifier.weight(1f))
                Stat(stringResource(R.string.plates_stat_first), shortWhen(context, data.plate.firstSeen), Modifier.weight(1f))
                Stat(stringResource(R.string.plates_stat_last), shortWhen(context, data.plate.lastSeen), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}

/** Time of day for today, otherwise the date ("12. Sept."). */
private fun shortWhen(context: Context, epochMs: Long): String {
    val today = LocalDate.now()
    val day = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
    val flags = if (day == today) DateUtils.FORMAT_SHOW_TIME else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or
        (if (day.year == today.year) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR)
    return DateUtils.formatDateTime(context, epochMs, flags)
}

/** When, source (live / which clip, position), crop, "unsicher gelesen" without confidence; opens the clip if on the phone. */
@Composable
private fun Sighting(plate: Plate, row: SightingRow, shape: Shape, onNavigate: (Route) -> Unit) {
    val context = LocalContext.current
    val s = row.sighting
    val clip = s.source == SightingSource.CLIP && s.mediaId != null
    val open = clip && row.mediaLocalUri != null
    val content: @Composable () -> Unit = {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier
                    .size(width = 96.dp, height = 54.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_plates), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                s.cropPath?.let { AsyncImage(File(context.filesDir, it), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(seenText(context, s.seenAt, seconds = true), style = MaterialTheme.typography.bodyLarge)
                Text(sourceText(context, row), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (s.display != plate.display) {
                    Text(stringResource(R.string.plates_read_as, s.display), style = MaterialTheme.typography.bodySmall)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (row.mediaCategory == MediaCategory.EVENT) IncidentBadge()
                    // Honesty rule: no confidence figure, only whether the reading was unsure.
                    if (s.confidence == null) UnsureBadge()
                }
                if (clip && !open) Hint(stringResource(R.string.plates_not_on_phone))
            }
        }
    }
    if (open) {
        Surface(
            onClick = { onNavigate(Clip(s.mediaId!!, s.positionMs ?: 0)) },
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
            content = content,
        )
    } else {
        Surface(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, content = content)
    }
}

/** "Live-Ansicht" or "Schleife 17:41:08 · bei 0:37" (raw recorder clock). */
private fun sourceText(context: Context, row: SightingRow): String {
    val s = row.sighting
    if (s.source == SightingSource.LIVE) return context.getString(R.string.plates_source_live)
    val kind = context.getString(
        when (row.mediaCategory) {
            MediaCategory.NORMAL -> R.string.media_category_normal
            MediaCategory.EVENT -> R.string.media_category_event
            MediaCategory.USER -> R.string.media_category_user_video
            else -> R.string.plates_source_clip
        },
    )
    val label = recorderClock(row.mediaRecorderTime)?.let { "$kind $it" } ?: kind
    val position = s.positionMs ?: return label
    return context.getString(R.string.plates_source_position, label, context.getString(R.string.media_at_position, DateUtils.formatElapsedTime(position / 1000)))
}

@Composable
private fun UnsureBadge() {
    Text(
        stringResource(R.string.plates_unsure_read),
        Modifier
            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
    )
}

@Composable
private fun DeleteButton(display: String, onDelete: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(
        onClick = { confirm = true },
        modifier = Modifier.padding(top = 24.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) { Text(stringResource(R.string.plates_delete_one)) }
    if (confirm) {
        ConfirmDialog(
            title = stringResource(R.string.plates_delete_one_title),
            text = stringResource(R.string.plates_delete_one_text, display),
            confirmLabel = stringResource(R.string.plates_delete),
            onConfirm = {
                confirm = false
                onDelete()
            },
            onDismiss = { confirm = false },
        )
    }
}
