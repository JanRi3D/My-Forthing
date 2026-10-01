package to.axolotl.cam.plates

import android.graphics.Bitmap
import android.graphics.RectF
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
 * One plate in one frame. [text] is the display reading (`?` = ambiguous character, see [PlateText]); [box] is in the
 * coordinates of the bitmap passed to [PlateRecognizer.recognize]. [confidence] is ML Kit's element score averaged
 * over the plate's elements, null when ML Kit gives none or the reading is uncertain.
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
 * [maxWidth] are downscaled before OCR; boxes are mapped back to the original frame. Not thread-confined, but
 * callers should not run more than one [recognize] at a time per instance.
 */
class MlKitPlateRecognizer(private val maxWidth: Int = 1280) : PlateRecognizer {
    private val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognize(frame: Bitmap, timestampMs: Long): List<PlateDetection> {
        val scale = if (frame.width > maxWidth) maxWidth.toFloat() / frame.width else 1f
        // ponytail: the scaled copy is left to the GC; ML Kit may still read it if this coroutine is cancelled.
        val input = if (scale < 1f) {
            Bitmap.createScaledBitmap(frame, maxWidth, (frame.height * scale).roundToInt(), true)
        } else {
            frame
        }
        val text = client.process(InputImage.fromBitmap(input, 0)).await()
        return text.textBlocks.flatMap { it.lines }.flatMap { line -> line.plates(1f / scale, timestampMs) }
    }

    override fun close() = client.close()
}

private fun Text.Line.plates(toFrame: Float, timestampMs: Long): List<PlateDetection> {
    val elements = elements
    return PlateText.find(elements.map { it.text }).mapNotNull { found ->
        val window = elements.subList(found.first, found.last + 1)
        val boxes = window.mapNotNull { it.boundingBox }
        if (boxes.isEmpty()) return@mapNotNull null
        val box = RectF(
            boxes.minOf { it.left } * toFrame,
            boxes.minOf { it.top } * toFrame,
            boxes.maxOf { it.right } * toFrame,
            boxes.maxOf { it.bottom } * toFrame,
        )
        PlateDetection(
            text = found.match.display,
            normalized = found.match.normalized,
            confidence = if (found.match.uncertain) null else meanConfidence(window.map { it.confidence }),
            box = box,
            frameTimestampMs = timestampMs,
            format = found.match.format,
        )
    }
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
