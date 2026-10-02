package me.ri3d.cam.enhance.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ri3d.cam.R
import me.ri3d.cam.core.data.PreferencesRepository
import me.ri3d.cam.core.ui.AxoTopBar
import me.ri3d.cam.core.ui.ListGroup
import me.ri3d.cam.core.ui.SectionHeader
import me.ri3d.cam.enhance.ClipUpscaler
import me.ri3d.cam.enhance.EnhanceEngine
import me.ri3d.cam.enhance.FrameEnhancer
import me.ri3d.cam.enhance.Resolution
import me.ri3d.cam.enhance.UpscaleError
import me.ri3d.cam.enhance.UpscaleFailure
import me.ri3d.cam.enhance.UpscaleRequest
import me.ri3d.cam.enhance.VideoCodec
import me.ri3d.cam.enhance.resolution
import me.ri3d.cam.media.MediaKind
import me.ri3d.cam.media.MediaRepository
import java.io.File
import javax.inject.Inject
import kotlin.math.roundToInt

/** Android 15+ stops `dataSync` work after 6 h per day: a measured estimate above 5 h (ML in practice) is not offered. */
internal const val DATA_SYNC_BUDGET_MS = 5 * 3_600_000L

/** Source facts as the pipeline reads them (coded size, track duration, frame rate). */
data class ClipFacts(val width: Int, val height: Int, val durationMs: Long, val fps: Int, val bytes: Long)

/** A target above the source; [encoder] = this phone has an H.264 encoder for that size. [etaMs] only when measured. */
data class UpscaleOption(
    val target: Resolution,
    val width: Int,
    val height: Int,
    val encoder: Boolean,
    val bytes: Long,
    val etaMs: Long?,
    /** Measured time beyond what Android 15+ lets a `dataSync` job run (6 h per day), with a margin. */
    val tooLong: Boolean = false,
) {
    val usable get() = encoder && !tooLong
}

data class UpscaleUiState(
    val loading: Boolean = true,
    @StringRes val loadError: Int? = null,
    val source: ClipFacts? = null,
    /** Targets not larger than the source are left out (the pipeline's `TargetNotLarger` rule, via its estimate). */
    val options: List<UpscaleOption> = emptyList(),
    val target: Resolution? = null,
    val mlAvailable: Boolean = false,
    val engine: EnhanceEngine = EnhanceEngine.CLASSICAL,
    /** This clip's latest upscale (running or finished). */
    val job: UpscaleJob? = null,
    /** Another clip's upscale that is still pending or running: only one at a time. */
    val otherJob: UpscaleJob? = null,
) {
    val canStart get() = !loading && loadError == null && options.any { it.target == target && it.usable } && job?.active != true && otherJob == null
}

@HiltViewModel
class UpscaleViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repository: MediaRepository,
    private val upscaler: ClipUpscaler,
    private val enhancer: FrameEnhancer,
    private val jobs: UpscaleJobs,
    private val preferences: PreferencesRepository,
) : ViewModel() {
    private val mediaId: String = checkNotNull(handle["mediaId"])
    private val form = MutableStateFlow(UpscaleUiState())
    private var file: File? = null

    val state: StateFlow<UpscaleUiState> = combine(form, jobs.current) { f, job ->
        f.copy(job = job?.takeIf { it.mediaId == mediaId }, otherJob = job?.takeIf { it.mediaId != mediaId && it.active })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UpscaleUiState())

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val item = repository.get(mediaId)
        // Only original recordings are upscaled; an output is never processed again.
        if (item != null && item.kind != MediaKind.ORIGINAL_VIDEO) return form.update { it.copy(loading = false, loadError = R.string.upscale_failure_not_original) }
        val f = item?.localFile?.takeIf { it.isFile }
            ?: return form.update { it.copy(loading = false, loadError = R.string.enhance_error_no_copy) }
        val facts = withContext(Dispatchers.IO) { readFacts(f) }
            ?: return form.update { it.copy(loading = false, loadError = R.string.upscale_failure_decoder) }
        file = f
        // Coming back to a finished or running upscale of this clip: show its choice.
        val last = jobs.current.first()?.takeIf { it.mediaId == mediaId }
        val engine = last?.engine ?: EnhanceEngine.CLASSICAL
        form.update { it.copy(source = facts, engine = engine) }
        val options = options(engine) ?: return
        val preferred = last?.target ?: preferences.preferences.first().exportQuality.resolution
        val usable = options.filter { it.usable }
        form.update {
            it.copy(loading = false, options = options, target = (usable.firstOrNull { o -> o.target == preferred } ?: usable.firstOrNull())?.target)
        }
        val ml = try {
            EnhanceEngine.ML in enhancer.capabilities().engines
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        form.update { it.copy(mlAvailable = ml) }
    }

    /** Null after an unreadable source (state then shows the error). */
    private suspend fun options(engine: EnhanceEngine): List<UpscaleOption>? {
        val f = file ?: return null
        val facts = form.value.source ?: return null
        return Resolution.entries.mapNotNull { target ->
            val estimate = upscaler.estimate(UpscaleRequest(Uri.fromFile(f), target, mediaId, engine)).getOrElse { e ->
                if ((e as? UpscaleFailure)?.error is UpscaleError.TargetNotLarger) return@mapNotNull null
                form.update { it.copy(loading = false, loadError = R.string.upscale_failure_decoder) }
                return null
            }
            val encoder = withContext(Dispatchers.IO) { hasEncoder(estimate.width, estimate.height, facts.fps) }
            val tooLong = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && (estimate.etaMs ?: 0) > DATA_SYNC_BUDGET_MS
            UpscaleOption(target, estimate.width, estimate.height, encoder, estimate.outputBytes, estimate.etaMs, tooLong)
        }
    }

    fun setTarget(target: Resolution) = form.update { it.copy(target = target) }

    /** The time estimate depends on the engine (measured per engine). */
    fun setEngine(engine: EnhanceEngine) {
        form.update { it.copy(engine = engine) }
        viewModelScope.launch { options(engine)?.let { o -> form.update { it.copy(options = o) } } }
    }

    fun start() {
        val s = state.value
        val target = s.target ?: return
        if (s.canStart) jobs.start(mediaId, target, s.engine)
    }

    /** After an ML failure. */
    fun retryClassical() {
        setEngine(EnhanceEngine.CLASSICAL)
        val s = state.value
        val target = s.target ?: return
        if (s.canStart) jobs.start(mediaId, target, EnhanceEngine.CLASSICAL)
    }

    fun cancel() = jobs.cancel()

    internal companion object {
        fun readFacts(file: File): ClipFacts? = runCatching {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.path)
                val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                    .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return@runCatching null
                val fps = if (!format.containsKey(MediaFormat.KEY_FRAME_RATE)) 30
                else runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrElse { format.getFloat(MediaFormat.KEY_FRAME_RATE).roundToInt() }
                ClipFacts(
                    width = format.getInteger(MediaFormat.KEY_WIDTH),
                    height = format.getInteger(MediaFormat.KEY_HEIGHT),
                    durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0,
                    fps = fps.coerceIn(1, 120),
                    bytes = file.length(),
                )
            } finally {
                extractor.release()
            }
        }.getOrNull()

        /** The pipeline's own query: an H.264 encoder taking a surface at this size and frame rate. */
        fun hasEncoder(width: Int, height: Int, fps: Int): Boolean = runCatching {
            val format = MediaFormat.createVideoFormat(VideoCodec.H264.mime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            }
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) != null
        }.getOrDefault(false)
    }
}

@Composable
fun UpscaleScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onOpenJob: (String) -> Unit, viewModel: UpscaleViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Android 13+: the progress notification needs the permission; the upscale runs either way.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val start: (() -> Unit) -> Unit = { action ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        action()
    }
    Scaffold(topBar = { AxoTopBar(stringResource(R.string.upscale_title), onBack = onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val s = state
            s.source?.let { Text(sourceText(context, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            s.loadError?.let {
                Text(stringResource(it), style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
            if (s.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                return@Column
            }
            val idle = s.job?.active != true
            Targets(s, idle, viewModel)
            if (s.options.isNotEmpty()) Engines(s, idle, viewModel)
            Text(stringResource(R.string.upscale_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            s.otherJob?.let { other ->
                Text(stringResource(R.string.upscale_other_running), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { onOpenJob(other.mediaId) }) { Text(stringResource(R.string.upscale_show_other)) }
            }
            s.job?.let { JobCard(it) }
            val job = s.job
            when {
                job?.active == true -> OutlinedButton(onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.action_cancel))
                }
                else -> {
                    if (job?.state == WorkInfo.State.SUCCEEDED && job.outputId != null) {
                        Button(onClick = { onOpen(job.outputId) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text(stringResource(R.string.upscale_open))
                        }
                    }
                    val retry = job?.state == WorkInfo.State.FAILED || job?.state == WorkInfo.State.CANCELLED
                    if (s.options.isNotEmpty()) {
                        Button(onClick = { start(viewModel::start) }, enabled = s.canStart, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text(stringResource(if (retry) R.string.action_retry else R.string.upscale_start))
                        }
                    }
                    if (job?.state == WorkInfo.State.FAILED && job.engine == EnhanceEngine.ML) {
                        OutlinedButton(onClick = { start(viewModel::retryClassical) }, enabled = s.otherJob == null, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.upscale_retry_classical))
                        }
                    }
                }
            }
        }
    }
}

private fun sourceText(context: Context, f: ClipFacts) = context.getString(
    R.string.upscale_source, f.width, f.height, DateUtils.formatElapsedTime(f.durationMs / 1000), Formatter.formatShortFileSize(context, f.bytes),
)

/** Size always; computing time only when measured on this phone; the reason when the phone cannot encode it. */
@StringRes
internal fun optionTextRes(o: UpscaleOption) = when {
    !o.encoder -> R.string.upscale_no_encoder
    o.tooLong -> R.string.upscale_too_long
    o.etaMs == null -> R.string.upscale_option_size
    else -> R.string.upscale_option_size_time
}

internal fun optionText(context: Context, o: UpscaleOption): String = context.getString(
    optionTextRes(o), o.width, o.height, Formatter.formatShortFileSize(context, o.bytes), o.etaMs?.let { durationText(context, it) }.orEmpty(),
)

@Composable
private fun Targets(s: UpscaleUiState, idle: Boolean, viewModel: UpscaleViewModel) {
    if (s.options.isEmpty()) {
        Text(stringResource(R.string.upscale_nothing_larger), style = MaterialTheme.typography.bodyLarge)
        return
    }
    val context = LocalContext.current
    Column {
        SectionHeader(stringResource(R.string.upscale_output))
        ListGroup(
            s.options.map { o ->
                { shape ->
                    ChoiceRow(
                        stringResource(
                            when (o.target) {
                                Resolution.P1080 -> R.string.upscale_p1080
                                Resolution.P1440 -> R.string.upscale_p1440
                                Resolution.P2160 -> R.string.upscale_p2160
                            },
                        ),
                        optionText(context, o),
                        selected = s.target == o.target, enabled = o.usable && idle, shape = shape,
                    ) { viewModel.setTarget(o.target) }
                }
            },
            Modifier.selectableGroup(),
        )
        if (s.options.any { it.usable && it.etaMs == null }) {
            Text(
                stringResource(R.string.upscale_time_unknown),
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Engines(s: UpscaleUiState, idle: Boolean, viewModel: UpscaleViewModel) {
    Column {
        SectionHeader(stringResource(R.string.upscale_engine))
        ListGroup(
            listOfNotNull(EnhanceEngine.CLASSICAL, EnhanceEngine.ML.takeIf { s.mlAvailable }).map { engine ->
                { shape ->
                    val ml = engine == EnhanceEngine.ML
                    ChoiceRow(
                        stringResource(if (ml) R.string.upscale_engine_ml else R.string.upscale_engine_classical),
                        stringResource(if (ml) R.string.upscale_engine_ml_warning else R.string.upscale_engine_classical_text),
                        selected = s.engine == engine, enabled = idle, shape = shape,
                        supportingColor = if (ml) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    ) { viewModel.setEngine(engine) }
                }
            },
            Modifier.selectableGroup(),
        )
    }
}

@Composable
private fun JobCard(job: UpscaleJob) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val color = MaterialTheme.colorScheme.onPrimaryContainer
        // Only the status line is announced when it changes, not every percent and ETA update.
        val status = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        val p = job.progress
        when (job.state) {
            WorkInfo.State.SUCCEEDED -> Text(stringResource(R.string.upscale_done), status, color = color, style = MaterialTheme.typography.titleMedium)
            WorkInfo.State.FAILED -> {
                Text(stringResource(R.string.upscale_failed_title), status, color = color, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(job.failure?.text ?: R.string.upscale_failure_unknown), color = color, style = MaterialTheme.typography.bodyMedium)
            }
            WorkInfo.State.CANCELLED -> Text(stringResource(R.string.upscale_cancelled), status, color = color, style = MaterialTheme.typography.titleMedium)
            else -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(if (p == null) R.string.upscale_waiting else R.string.upscale_running),
                        color = color,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = status.weight(1f),
                    )
                    if (p != null) Text(stringResource(R.string.upscale_percent, (p.fraction * 100).toInt()), color = color)
                }
                if (p == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                p?.etaMs?.let { Text(stringResource(R.string.upscale_eta, durationText(context, it)), color = color) }
                p?.outputBytesEstimate?.let { Text(stringResource(R.string.upscale_output_estimate, Formatter.formatShortFileSize(context, it)), color = color) }
            }
        }
    }
}
