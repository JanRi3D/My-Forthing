package me.ri3d.dashcam.plates.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import me.ri3d.dashcam.R
import me.ri3d.dashcam.core.navigation.PlateDetail
import me.ri3d.dashcam.core.navigation.Route
import me.ri3d.dashcam.core.ui.listRowShape
import me.ri3d.dashcam.plates.Plate
import me.ri3d.dashcam.plates.PlateRepository
import javax.inject.Inject

data class PlateListRow(val plate: Plate, val incident: Boolean)

/** What the list shows; [rows] already filtered. */
data class PlateList(val query: String, val incidentsOnly: Boolean, val rows: List<PlateListRow>)

/** Route `Plates(query)`: partial search on `normalized`, "Alle" / "Vorfälle". The query survives process death. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlatesViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    repository: PlateRepository,
    private val scans: ClipScans,
) : ViewModel() {
    init {
        if (handle.get<String?>(QUERY) == null) handle[QUERY] = "" // Plates() carries query = null
    }

    val query: StateFlow<String> = handle.getStateFlow(QUERY, "")
    private val incidentsOnly = handle.getStateFlow(INCIDENTS, false)

    /** null while loading. */
    val list: StateFlow<PlateList?> = combine(
        query.flatMapLatest { repository.search(it) },
        repository.incidentPlateIds(),
        query,
        incidentsOnly,
    ) { plates, incidentIds, q, onlyIncidents ->
        val incidents = incidentIds.toSet()
        val rows = plates.map { PlateListRow(it, it.id in incidents) }
        PlateList(q, onlyIncidents, if (onlyIncidents) rows.filter { it.incident } else rows)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val scan: StateFlow<ClipScans.State> = scans.state

    /** Plates are uppercase; the field shows what is searched. */
    fun setQuery(text: String) {
        handle[QUERY] = text.uppercase()
    }

    fun setIncidentsOnly(on: Boolean) {
        handle[INCIDENTS] = on
    }

    fun cancelScan(mediaId: String) = scans.cancel(mediaId)

    private companion object {
        const val QUERY = "query" // = the route argument
        const val INCIDENTS = "incidentsOnly"
    }
}

@Composable
fun PlatesScreen(onBack: () -> Unit, onNavigate: (Route) -> Unit, viewModel: PlatesViewModel = hiltViewModel()) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val list by viewModel.list.collectAsStateWithLifecycle()
    val scan by viewModel.scan.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 16.dp),
    ) {
        SearchField(query, viewModel::setQuery, onBack)
        val incidentsOnly = list?.incidentsOnly == true
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !incidentsOnly, onClick = { viewModel.setIncidentsOnly(false) }, label = { Text(stringResource(R.string.plates_filter_all)) })
            FilterChip(selected = incidentsOnly, onClick = { viewModel.setIncidentsOnly(true) }, label = { Text(stringResource(R.string.plates_filter_incidents)) })
        }
        scan.running?.let { ScanCard(scan, onCancel = { viewModel.cancelScan(it) }) }
        val l = list ?: return@Column
        Text(
            pluralStringResource(R.plurals.plates_count, l.rows.size, l.rows.size),
            Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (l.rows.isEmpty()) {
            Empty(l)
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(l.rows, key = { _, row -> row.plate.id }) { index, row ->
                    PlateRow(
                        row.plate,
                        listRowShape(index, l.rows.size),
                        trailing = stringResource(R.string.plates_last_seen, seenText(context, row.plate.lastSeen)),
                        incident = row.incident,
                    ) { onNavigate(PlateDetail(row.plate.id)) }
                }
            }
        }
    }
}

/** Back button plus a plain search field (uppercase monospace, as on the artboard). */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, onBack: () -> Unit) {
    val label = stringResource(R.string.plates_search)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp)) {
            Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.action_back))
        }
        val style = PlateTextStyle.copy(fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
        val keyboard = LocalSoftwareKeyboardController.current
        // Opened from Home without a query: type right away, once (not again after rotation or coming back).
        val focus = remember { FocusRequester() }
        var focused by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            if (!focused && query.isEmpty()) focus.requestFocus()
            focused = true
        }
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp)
                .focusRequester(focus)
                .semantics { contentDescription = label },
            textStyle = style,
            singleLine = true,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }), // results update while typing
            decorationBox = { field ->
                if (query.isEmpty()) {
                    Text(
                        label,
                        Modifier.clearAndSetSemantics { }, // the field already carries the label
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                field()
            },
        )
    }
}

/** The running clip check, visible here as well as on the clip screen. */
@Composable
private fun ScanCard(scan: ClipScans.State, onCancel: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(top = 16.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(stringResource(R.string.plates_clip_checking), style = MaterialTheme.typography.bodyMedium)
            if (scan.queued.isNotEmpty()) {
                Text(
                    pluralStringResource(R.plurals.plates_clip_more_queued, scan.queued.size, scan.queued.size),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(progress = { scan.fraction }, modifier = Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }
}

@Composable
private fun Empty(list: PlateList) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(R.drawable.ic_search), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        val (title, text) = when {
            list.query.isNotBlank() -> stringResource(R.string.plates_no_match, list.query) to stringResource(R.string.plates_no_match_text)
            list.incidentsOnly -> stringResource(R.string.plates_no_incidents) to stringResource(R.string.plates_no_incidents_text)
            else -> stringResource(R.string.plates_empty) to stringResource(R.string.plates_empty_text)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
