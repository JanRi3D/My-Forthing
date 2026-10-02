package me.ri3d.dashcam.plates

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
 * [PlateText]); [box] is in the coordinates of the bitmap passed to [PlateRecognizer.recognize].
 */
data class PlateDetection(
    val text: String,
    val normalized: String,
    /**
     * ML Kit's own character score averaged over the plate's characters; null when ML Kit gives none, any character
     * is `?`, or a glyph was left out as the seal. It does **not** separate right from wrong readings (synthetic
     * set: wrong 0.76–0.90, right 0.68–0.93), so never show it as a percentage or a probability.
     */
    val confidence: Float?,
    val box: RectF,
    val frameTimestampMs: Long,
    val format: PlateFormat,
)

interface PlateRecognizer {
    /** [frame] must be a software bitmap (not `Bitmap.Config.HARDWARE`); it is not modified or recycled. */
    suspend fun recognize(frame: Bitmap, timestampMs: Long): List<PlateDetection>
    fun close()
}

/**
 * ML Kit Text Recognition v2 (Latin, bundled model, on-device) plus the [PlateText] filter. Frames wider than
 * [maxWidth] are downscaled before OCR; boxes are mapped back to the original frame. Run one [recognize] at a time
 * per instance.
 *
 * Glyph rules (measured on the synthetic set, see docs/features/plates.md): a glyph whose ink is coloured (EU band,
 * coloured stickers) is not a plate character and becomes a separator, unless most glyphs of the line are coloured
 * (green or red plates) or ML Kit read the glyph with confidence (≥ [UNSURE_BELOW]); a letter at least as wide as it is high may have the seal
 * merged into it ([PlateText.SEAL_GAP]); a glyph ML Kit scores below [UNSURE_BELOW] is shown as `?`. A reading
 * that left out any glyph has no confidence.
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
        private const val SEAL_WIDTH_PER_HEIGHT = 1.0f // plate letters are narrower (measured ≤ 0.91)
    }

    /** One OCR element as aligned shown/raw text, the scores of the glyphs that count as read, coloured glyph left out. */
    private class Glyphs(val shown: String, val raw: String, val sureScores: List<Float>, val dropped: Boolean)

    private fun Text.Line.plates(input: Bitmap, toFrame: Float, timestampMs: Long): List<PlateDetection> {
        val elements = elements
        val shares = elements.map { e -> e.symbols.map { it.boundingBox?.let { box -> inkColoredShare(input, box) } ?: 0f } }
        val all = shares.flatten()
        val colouredInk = all.count { it >= COLORED_SHARE } * 2 > all.size // green/red plate: keep every glyph
        val glyphs = elements.mapIndexed { i, e ->
            e.glyphs(e.symbols.mapIndexed { k, sym -> !colouredInk && shares[i][k] >= COLORED_SHARE && sym.confidence < UNSURE_BELOW })
        }
        return PlateText.find(glyphs.map { it.shown }, glyphs.map { it.raw }).mapNotNull { found ->
            val window = found.first..found.last
            val boxes = window.mapNotNull { elements[it].boundingBox }
            if (boxes.isEmpty()) return@mapNotNull null
            PlateDetection(
                text = found.match.display,
                normalized = found.match.normalized,
                confidence = if (found.match.uncertain || found.match.glyphDropped || window.any { glyphs[it].dropped }) null
                else meanConfidence(window.flatMap { glyphs[it].sureScores }),
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

    private fun Text.Element.glyphs(colored: List<Boolean>): Glyphs {
        if (symbols.isEmpty()) return Glyphs(text, text, listOf(confidence), dropped = false)
        val shown = StringBuilder()
        val raw = StringBuilder()
        val scores = mutableListOf<Float>()
        symbols.forEachIndexed { i, symbol ->
            val unsure = symbol.confidence > 0f && symbol.confidence < UNSURE_BELOW
            for (c in symbol.text) {
                shown.append(if (colored[i]) ' ' else if (unsure) '?' else c)
                raw.append(if (colored[i]) ' ' else c)
            }
            if (!colored[i] && !unsure) scores += symbol.confidence
            val box = symbol.boundingBox
            if (box != null && i < symbols.lastIndex && symbol.text.all { it.isLetter() } &&
                box.width() >= SEAL_WIDTH_PER_HEIGHT * box.height()
            ) {
                shown.append(PlateText.SEAL_GAP)
                raw.append(PlateText.SEAL_GAP)
            }
        }
        return Glyphs(shown.toString(), raw.toString(), scores, dropped = colored.any { it })
    }
}

/**
 * Share of clearly coloured pixels among the glyph's ink (pixels darker than 0.8 × the box's median brightness), so a
 * black character on a yellow plate is not coloured; when that ink is under 10 % of the box (thin glyph, or the
 * evenly blue EU band) the darkest 10 % of the box count as ink.
 */
internal fun inkColoredShare(bitmap: Bitmap, box: Rect): Float {
    val r = Rect(box)
    if (!r.intersect(0, 0, bitmap.width, bitmap.height) || r.isEmpty) return 0f
    val px = IntArray(r.width() * r.height())
    bitmap.getPixels(px, 0, r.width(), r.left, r.top, r.width(), r.height())
    val luma = px.map { (299 * (it shr 16 and 0xff) + 587 * (it shr 8 and 0xff) + 114 * (it and 0xff)) / 1000 }
    val sorted = luma.sorted()
    val median = sorted[sorted.size / 2]
    val limit = if (luma.count { it < median * 0.8f } * 10 >= px.size) median * 0.8f else sorted[sorted.size / 10] + 0.5f
    val ink = px.filterIndexed { i, _ -> luma[i] < limit }.ifEmpty { px.toList() }
    return ink.count { c ->
        val max = maxOf(c shr 16 and 0xff, c shr 8 and 0xff, c and 0xff)
        val min = minOf(c shr 16 and 0xff, c shr 8 and 0xff, c and 0xff)
        max > 64 && (max - min) > 0.35f * max
    }.toFloat() / ink.size
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
