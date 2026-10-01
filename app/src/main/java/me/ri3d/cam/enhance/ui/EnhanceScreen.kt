package me.ri3d.cam.enhance.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.scale
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ri3d.cam.R
import me.ri3d.cam.core.log.Log
import me.ri3d.cam.core.ui.AxoTopBar
import me.ri3d.cam.core.ui.ListGroup
import me.ri3d.cam.core.ui.SectionHeader
import me.ri3d.cam.enhance.EnhanceEngine
import me.ri3d.cam.enhance.EnhanceError
import me.ri3d.cam.enhance.EnhanceException
import me.ri3d.cam.enhance.EnhancedFrame
import me.ri3d.cam.enhance.EnhancerCapabilities
import me.ri3d.cam.enhance.FrameEnhancer
import me.ri3d.cam.enhance.saveEnhancedFrame
import me.ri3d.cam.media.MediaKind
import me.ri3d.cam.media.MediaRepository
import javax.inject.Inject
import kotlin.math.roundToInt

/** Longest side of anything drawn: 4096 px ARGB ≈ 38 MB, far below the ~100 MB a Canvas refuses (1080p×4 is 133 MB). */
internal const val PREVIEW_MAX_SIDE = 4096

internal fun previewOf(bitmap: Bitmap): Bitmap {
    val f = PREVIEW_MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
    return if (f >= 1f) bitmap else bitmap.scale((bitmap.width * f).roundToInt(), (bitmap.height * f).roundToInt())
}

/** A finished enhancement; [preview] is what is drawn, the full bitmap stays in the ViewModel for saving. */
data class EnhanceResult(val preview: Bitmap, val scale: Int, val engine: EnhanceEngine, val model: String?, val fallback: Boolean)

data class EnhanceUiState(
    val loading: Boolean = true,
    @StringRes val loadError: Int? = null,
    val fileName: String = "",
    /** Null for photos and screenshots. */
    val positionMs: Long? = null,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val original: Bitmap? = null,
    /** Preferred first; empty until the capabilities are known. */
    val engines: List<EnhanceEngine> = emptyList(),
    /** Scale → fits this phone's memory limit. */
    val scales: Map<Int, Boolean> = emptyMap(),
    val scale: Int = 4,
    val engine: EnhanceEngine = EnhanceEngine.CLASSICAL,
    /** Only when measured on this phone. */
    val estimateMs: Long? = null,
    /** Non-null while enhancing. */
    val progress: Float? = null,
    val result: EnhanceResult? = null,
    val saving: Boolean = false,
    @StringRes val error: Int? = null,
) {
    val canStart get() = !loading && loadError == null && engines.isNotEmpty() && scales[scale] == true && progress == null && !saving
}

@HiltViewModel
class EnhanceViewModel @Inject constructor(
    handle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val repository: MediaRepository,
    private val enhancer: FrameEnhancer,
) : ViewModel() {
    private val mediaId: String = checkNotNull(handle["mediaId"])
    private val requestedPositionMs: Long = handle["positionMs"] ?: 0L
    private val _state = MutableStateFlow(EnhanceUiState())
    val state: StateFlow<EnhanceUiState> = _state.asStateFlow()
    private val _saved = Channel<String>(Channel.BUFFERED)

    /** Id of the saved item (open its clip screen). */
    val saved: Flow<String> = _saved.receiveAsFlow()

    private var source: Bitmap? = null
    private var caps: EnhancerCapabilities? = null
    private var frame: EnhancedFrame? = null
    private var job: Job? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val item = repository.get(mediaId)
        val file = item?.localFile?.takeIf { it.isFile }
        if (file == null) return _state.update { it.copy(loading = false, loadError = R.string.enhance_error_no_copy) }
        val position = requestedPositionMs.takeIf { item.isVideo }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { if (position != null) frameAt(file.path, position) else BitmapFactory.decodeFile(file.path) }.getOrNull()
        } ?: return _state.update { it.copy(loading = false, loadError = R.string.enhance_error_source) }
        source = bitmap
        val original = withContext(Dispatchers.Default) { previewOf(bitmap) }
        _state.update {
            it.copy(fileName = item.originalFileName, positionMs = position, sourceWidth = bitmap.width, sourceHeight = bitmap.height, original = original)
        }
        val c = enhancer.capabilities().also { caps = it }
        val scales = listOf(4, 2).associateWith { bitmap.width.toLong() * bitmap.height <= c.maxInputPixels(it) }
        _state.update { s ->
            s.copy(loading = false, engines = c.engines, engine = c.engines.first(), scales = scales, scale = scales.entries.firstOrNull { it.value }?.key ?: 4)
                .withEstimate()
        }
    }

    private fun EnhanceUiState.withEstimate() = copy(estimateMs = caps?.estimateMs(sourceWidth, sourceHeight, scale, engine))

    fun setScale(scale: Int) = _state.update { it.copy(scale = scale).withEstimate() }

    fun setEngine(engine: EnhanceEngine) = _state.update { it.copy(engine = engine).withEstimate() }

    fun enhance() {
        val src = source ?: return
        val s = _state.value
        if (!s.canStart) return
        dropResult()
        _state.update { it.copy(progress = 0f, result = null, error = null) }
        job = viewModelScope.launch {
            try {
                val out = enhancer.enhanceFrame(src, s.scale, s.engine) { p -> _state.update { it.copy(progress = p) } }
                frame = out
                val preview = withContext(Dispatchers.Default) { previewOf(out.bitmap) }
                val fallback = s.engine == EnhanceEngine.ML && out.engine != EnhanceEngine.ML
                _state.update { it.copy(progress = null, result = EnhanceResult(preview, s.scale, out.engine, out.model, fallback)) }
            } catch (e: CancellationException) {
                _state.update { it.copy(progress = null) }
                throw e
            } catch (e: EnhanceException) {
                fail(if (e.error == EnhanceError.TooLarge) R.string.enhance_error_too_large else R.string.enhance_error_memory)
            } catch (e: OutOfMemoryError) {
                fail(R.string.enhance_error_memory)
            } catch (e: Exception) {
                Log.w(TAG, "enhance failed: ${e.javaClass.simpleName}")
                fail(R.string.enhance_error_failed)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** JPEG + sidecar, then a derived library item linked to the original; the original is untouched. */
    fun save() {
        val out = frame ?: return
        val result = _state.value.result ?: return
        if (_state.value.saving) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            // Finishes even when the screen is left meanwhile, so no output is left without its library item.
            val id = withContext(NonCancellable) {
                try {
                    val output = saveEnhancedFrame(context, out, result.scale, mediaId, _state.value.positionMs)
                    repository.registerDerived(MediaKind.ENHANCED_FRAME, output.file, mediaId, output.info.sourcePositionMs, output.info).id
                } catch (e: Exception) {
                    Log.w(TAG, "saving the enhanced frame failed: ${e.javaClass.simpleName}")
                    null
                }
            }
            _state.update { it.copy(saving = false, error = if (id == null) R.string.enhance_error_save else null) }
            if (id != null) _saved.send(id)
        }
    }

    private fun fail(@StringRes message: Int) = _state.update { it.copy(progress = null, error = message) }

    /** The full output is never drawn (only its preview), so it can be recycled at once. */
    private fun dropResult() {
        val old = frame ?: return
        frame = null
        if (old.bitmap !== _state.value.result?.preview) old.bitmap.recycle()
    }

    override fun onCleared() {
        job?.cancel()
        dropResult()
    }

    private companion object {
        const val TAG = "EnhanceUi"

        /** The exact frame at [positionMs] (OPTION_CLOSEST decodes from the previous key frame), not the nearest key frame. */
        fun frameAt(path: String, positionMs: Long): Bitmap? = MediaMetadataRetriever().run {
            try {
                setDataSource(path)
                getFrameAtTime(positionMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
            } finally {
                release()
            }
        }
    }
}

@Composable
fun EnhanceScreen(onBack: () -> Unit, onSaved: (String) -> Unit, viewModel: EnhanceViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.saved.collect(onSaved) }
    Scaffold(topBar = { AxoTopBar(stringResource(R.string.enhance_title), onBack = onBack) }) { padding ->
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
            s.loadError?.let {
                Text(stringResource(it), style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
            val original = s.original
            if (original == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                return@Column
            }
            Comparison(original, s.result)
            Text(sourceLine(LocalContext.current, s), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            s.result?.let { ResultInfo(it) }
            if (s.result == null) Options(s, viewModel)
            s.progress?.let { p ->
                Text(stringResource(R.string.enhance_running), style = MaterialTheme.typography.titleSmall)
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel)) }
            }
            s.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            when {
                s.result != null -> {
                    Button(onClick = viewModel::save, enabled = !s.saving, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(stringResource(if (s.saving) R.string.enhance_saving else R.string.enhance_save))
                    }
                    OutlinedButton(onClick = viewModel::enhance, enabled = !s.saving, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.enhance_again))
                    }
                }
                s.progress == null -> Button(onClick = viewModel::enhance, enabled = s.canStart, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.enhance_start))
                }
            }
            Text(stringResource(R.string.enhance_honesty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun sourceLine(context: Context, s: EnhanceUiState): String {
    val size = context.getString(R.string.enhance_source_size, s.sourceWidth, s.sourceHeight)
    return s.positionMs?.let { context.getString(R.string.enhance_frame_at, DateUtils.formatElapsedTime(it / 1000), s.fileName, size) }
        ?: context.getString(R.string.enhance_photo_source, s.fileName, size)
}

/** Original or enhanced preview in one box (same framing), pinch to zoom; the toggle keeps the zoom. */
@Composable
private fun Comparison(original: Bitmap, result: EnhanceResult?) {
    var showEnhanced by rememberSaveable { mutableStateOf(true) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val transform = rememberTransformableState { zoomChange, pan, _ ->
        zoom = (zoom * zoomChange).coerceIn(1f, MAX_ZOOM)
        val maxX = box.width * (zoom - 1) / 2
        val maxY = box.height * (zoom - 1) / 2
        offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
    }
    val enhanced = result?.takeIf { showEnhanced }?.preview
    val shown = enhanced ?: original
    val image = remember(shown) { shown.asImageBitmap() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(original.width.toFloat() / original.height)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black)
                .onSizeChanged { box = it }
                .transformable(transform),
        ) {
            Image(
                image,
                contentDescription = stringResource(if (enhanced != null) R.string.enhance_preview_enhanced else R.string.enhance_preview_original),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = offset.x
                    translationY = offset.y
                },
            )
        }
        if (result != null) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = !showEnhanced, onClick = { showEnhanced = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                    Text(stringResource(R.string.enhance_show_original))
                }
                SegmentedButton(selected = showEnhanced, onClick = { showEnhanced = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                    Text(stringResource(R.string.enhance_show_enhanced, result.scale))
                }
            }
            Text(stringResource(R.string.enhance_zoom_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val MAX_ZOOM = 8f

@Composable
private fun ResultInfo(result: EnhanceResult) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.tertiaryContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val color = MaterialTheme.colorScheme.onTertiaryContainer
        Text(stringResource(R.string.enhance_result_label, result.scale), style = MaterialTheme.typography.titleMedium, color = color)
        Text(
            if (result.engine == EnhanceEngine.ML) stringResource(R.string.enhance_result_ml, result.model ?: "–")
            else stringResource(R.string.enhance_result_classical),
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
        if (result.fallback) Text(stringResource(R.string.enhance_result_fallback), style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun Options(s: EnhanceUiState, viewModel: EnhanceViewModel) {
    val busy = s.progress != null
    Column {
        SectionHeader(stringResource(R.string.enhance_scale))
        ListGroup(
            listOf(4, 2).map { scale ->
                { shape ->
                    val fits = s.scales[scale] == true
                    ChoiceRow(
                        stringResource(R.string.enhance_scale_option, scale),
                        if (fits) null else stringResource(R.string.enhance_scale_too_large),
                        selected = s.scale == scale, enabled = fits && !busy, shape = shape,
                    ) { viewModel.setScale(scale) }
                }
            },
            Modifier.selectableGroup(),
        )
    }
    if (s.engines.isEmpty()) return
    Column {
        SectionHeader(stringResource(R.string.enhance_engine))
        ListGroup(
            s.engines.map { engine ->
                { shape ->
                    val ml = engine == EnhanceEngine.ML
                    ChoiceRow(
                        stringResource(if (ml) R.string.enhance_engine_ml else R.string.enhance_engine_classical),
                        stringResource(if (ml) R.string.enhance_engine_ml_text else R.string.enhance_engine_classical_text),
                        selected = s.engine == engine, enabled = !busy, shape = shape,
                    ) { viewModel.setEngine(engine) }
                }
            },
            Modifier.selectableGroup(),
        )
        if (EnhanceEngine.ML !in s.engines) {
            Text(
                stringResource(R.string.enhance_engine_ml_unavailable),
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    s.estimateMs?.let {
        Text(stringResource(R.string.enhance_estimate, durationText(LocalContext.current, it)), style = MaterialTheme.typography.bodyMedium)
    }
}
