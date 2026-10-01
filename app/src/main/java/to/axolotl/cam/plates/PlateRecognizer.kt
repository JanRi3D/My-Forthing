package to.axolotl.cam.plates

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.scale
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/**
 * One plate in one frame. [text] is the display reading (`?` = unreadable or ambiguous character, see
 * [PlateText]); [box] is in the coordinates of the bitmap passed to [PlateRecognizer.recognize]. [confidence] is
 * ML Kit's score averaged over the plate's characters, null when ML Kit gives none or the reading is uncertain.
 */
data class PlateDetection(
    val text: String,
    val normalized: String,
    val confidence: Float?,
    val box: RectF,
    val frameTimestampMs: Long,
    val format: PlateFormat,
)

interface PlateRecognizer {
    suspend fun recognize(frame: Bitmap, timestampMs: Long): List<PlateDetection>
    fun close()
}

/**
 * ML Kit Text Recognition v2 (Latin, bundled model, on-device) plus the [PlateText] filter. Frames wider than
 * [maxWidth] are downscaled before OCR; boxes are mapped back to the original frame. Run one [recognize] at a time
 * per instance.
 *
 * Glyph rules (measured on the synthetic set, see docs/features/plates.md): a coloured glyph (EU band, coloured
 * stickers) is not a plate character and becomes a separator; a glyph ML Kit scores below [UNSURE_BELOW] is shown
 * as `?` (the seal between city code and letters is read like that).
 */
class MlKitPlateRecognizer(private val maxWidth: Int = 1280) : PlateRecognizer {
    private val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognize(frame: Bitmap, timestampMs: Long): List<PlateDetection> {
        val factor = if (frame.width > maxWidth) maxWidth.toFloat() / frame.width else 1f
        // ponytail: the scaled copy is left to the GC; ML Kit may still read it if this coroutine is cancelled.
        val input = if (factor < 1f) frame.scale(maxWidth, (frame.height * factor).roundToInt()) else frame
        val text = client.process(InputImage.fromBitmap(input, 0)).await()
        return text.textBlocks.flatMap { it.lines }.flatMap { line -> line.plates(input, 1f / factor, timestampMs) }
    }

    override fun close() = client.close()

    companion object {
        const val UNSURE_BELOW = 0.4f
        private const val COLORED_SHARE = 0.4f
    }

    /** One OCR element as aligned shown/raw text plus the scores of the glyphs that count as read. */
    private class Glyphs(val shown: String, val raw: String, val sureScores: List<Float>)

    private fun Text.Line.plates(input: Bitmap, toFrame: Float, timestampMs: Long): List<PlateDetection> {
        val elements = elements
        val glyphs = elements.map { it.glyphs(input) }
        return PlateText.find(glyphs.map { it.shown }, glyphs.map { it.raw }).mapNotNull { found ->
            val window = found.first..found.last
            val boxes = window.mapNotNull { elements[it].boundingBox }
            if (boxes.isEmpty()) return@mapNotNull null
            PlateDetection(
                text = found.match.display,
                normalized = found.match.normalized,
                confidence = if (found.match.uncertain) null else meanConfidence(window.flatMap { glyphs[it].sureScores }),
                box = RectF(
                    boxes.minOf { it.left } * toFrame,
                    boxes.minOf { it.top } * toFrame,
                    boxes.maxOf { it.right } * toFrame,
                    boxes.maxOf { it.bottom } * toFrame,
                ),
                frameTimestampMs = timestampMs,
                format = found.match.format,
            )
        }
    }

    private fun Text.Element.glyphs(input: Bitmap): Glyphs {
        if (symbols.isEmpty()) return Glyphs(text, text, listOf(confidence))
        val shown = StringBuilder()
        val raw = StringBuilder()
        val scores = mutableListOf<Float>()
        for (symbol in symbols) {
            val colored = symbol.boundingBox?.let { coloredShare(input, it) >= COLORED_SHARE } == true
            val unsure = symbol.confidence > 0f && symbol.confidence < UNSURE_BELOW
            for (c in symbol.text) {
                shown.append(if (colored) ' ' else if (unsure) '?' else c)
                raw.append(if (colored) ' ' else c)
            }
            if (!colored && !unsure) scores += symbol.confidence
        }
        return Glyphs(shown.toString(), raw.toString(), scores)
    }
}

/** Share of clearly coloured pixels in [box]; plate characters are black on white, EU band and stickers are not. */
private fun coloredShare(bitmap: Bitmap, box: Rect): Float {
    val r = Rect(box)
    if (!r.intersect(0, 0, bitmap.width, bitmap.height) || r.isEmpty) return 0f
    val px = IntArray(r.width() * r.height())
    bitmap.getPixels(px, 0, r.width(), r.left, r.top, r.width(), r.height())
    val colored = px.count { c ->
        val red = c shr 16 and 0xff
        val green = c shr 8 and 0xff
        val blue = c and 0xff
        val max = maxOf(red, green, blue)
        val min = minOf(red, green, blue)
        max > 64 && (max - min) > 0.35f * max
    }
    return colored.toFloat() / px.size
}

/**
 * ML Kit returns a primitive float even when its model gives no score (then 0, or NaN); only a full set of
 * scores in (0, 1] is averaged, anything else means "no confidence".
 */
internal fun meanConfidence(scores: List<Float>): Float? =
    if (scores.isNotEmpty() && scores.all { it > 0f && it <= 1f }) scores.average().toFloat() else null

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener(Runnable::run) { task ->
        val error = task.exception
        when {
            error != null -> cont.resumeWithException(error)
            task.isCanceled -> cont.cancel(CancellationException("ML Kit task cancelled"))
            else -> cont.resume(task.result)
        }
    }
}
