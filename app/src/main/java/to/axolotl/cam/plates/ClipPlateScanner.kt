package to.axolotl.cam.plates

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Provider

data class ScanProgress(val positionMs: Long, val durationMs: Long, val detections: List<PlateDetection>) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 1f
}

data class ScanSummary(
    val clipDurationMs: Long,
    val framesScanned: Int,
    /** Distinct normalized plates found in the clip. */
    val plates: Set<String>,
    val sightingsWritten: Int,
    val avgDecodeMs: Float,
    val avgRecognizeMs: Float,
    val elapsedMs: Long,
)

/** Scans a saved clip at [scan]'s fps and records CLIP sightings linked to the clip's mediaId and position. */
class ClipPlateScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: PlateRepository,
    private val recognizers: Provider<PlateRecognizer>,
) {
    /**
     * Cancellable between frames (cancel the calling coroutine; sightings written so far stay). [clipStartMs] is
     * the wall-clock time of position 0 when known; sightings then carry the time the plate was on the road,
     * otherwise the scan time. Boxes are in video pixels; the last progress has fraction 1.0.
     */
    suspend fun scan(
        uri: Uri,
        mediaId: String,
        fps: Int = 2,
        clipStartMs: Long? = null,
        onProgress: (ScanProgress) -> Unit = {},
    ): ScanSummary = withContext(Dispatchers.Default) {
        require(fps in 1..30) { "fps must be in 1..30" }
        val started = now()
        val retriever = MediaMetadataRetriever()
        val recognizer = recognizers.get()
        var frames = 0
        var written = 0
        var decodeMs = 0L
        var recognizeMs = 0L
        val plates = mutableSetOf<String>()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: error("clip has no duration")
            val videoWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            // ponytail: OPTION_CLOSEST decodes from the previous key frame for every sample; at 2 fps that is about
            // one full decode of the clip. Switch to a sequential MediaCodec decode if higher fps is needed.
            var position = 0L
            while (position < durationMs) {
                ensureActive()
                val t0 = now()
                val frame = retriever.frameAt(position * 1000, videoWidth)
                val t1 = now()
                val toVideo = if (frame != null && videoWidth > frame.width) videoWidth.toFloat() / frame.width else 1f
                val found = frame?.let { recognizer.recognize(it, position) }.orEmpty()
                    .map { if (toVideo == 1f) it else it.copy(box = it.box.times(toVideo)) }
                recognizeMs += now() - t1
                decodeMs += t1 - t0
                frames++
                if (found.isNotEmpty()) {
                    plates += found.map { it.normalized }
                    written += repository.recordSightings(
                        found, SightingSource.CLIP, mediaId, position, frame,
                        seenAt = clipStartMs?.plus(position) ?: System.currentTimeMillis(), frameScale = 1f / toVideo,
                    )
                }
                frame?.recycle()
                onProgress(ScanProgress(position, durationMs, found))
                position += 1000L / fps
            }
            onProgress(ScanProgress(durationMs, durationMs, emptyList()))
            ScanSummary(
                clipDurationMs = durationMs,
                framesScanned = frames,
                plates = plates,
                sightingsWritten = written,
                avgDecodeMs = if (frames > 0) decodeMs.toFloat() / frames else 0f,
                avgRecognizeMs = if (frames > 0) recognizeMs.toFloat() / frames else 0f,
                elapsedMs = now() - started,
            )
        } finally {
            retriever.release()
            recognizer.close()
            withContext(NonCancellable) { repository.forgetClip(mediaId) }
        }
    }

    /** Decodes straight to OCR width on API 27+ (less memory and copying); full size on API 26. */
    private fun MediaMetadataRetriever.frameAt(timeUs: Long, videoWidth: Int): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && videoWidth > DECODE_WIDTH) {
            getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, DECODE_WIDTH, DECODE_WIDTH)
        } else {
            getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
        }

    private fun RectF.times(f: Float) = RectF(left * f, top * f, right * f, bottom * f)

    private fun now() = System.nanoTime() / 1_000_000

    private companion object {
        const val DECODE_WIDTH = 1280 // = MlKitPlateRecognizer default OCR width
    }
}
