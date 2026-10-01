package me.ri3d.cam.plates

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.core.graphics.scale
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt

/** Local plate history (Room) plus one small JPEG crop per sighting under `filesDir/plates/`. */
@Singleton
class PlateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: PlateDao,
) {
    private val mutex = Mutex()

    // Dedupe key -> time of the last detection (seenAt for LIVE, positionMs for CLIP), recorded or not.
    // ponytail: in memory only; CLIP rescans after a restart are caught by the DB check instead.
    private val lastDetection = HashMap<String, Long>()

    fun history(): Flow<List<Plate>> = dao.history()

    /** Partial match on the normalized plate, e.g. city letters ("BMK") or last digits ("4821"). */
    fun search(query: String): Flow<List<Plate>> =
        PlateText.normalize(query).let { if (it.isEmpty()) dao.history() else dao.search(it) }

    /** The plate with its sightings (unordered; sort by `seenAt` or `positionMs` in the UI). */
    fun plate(id: Long): Flow<PlateWithSightings?> = dao.plate(id)

    /**
     * Stores the detections of one frame; returns how many sightings were written. A plate detected again within
     * [DEDUPE_WINDOW_MS] of its previous detection (LIVE: wall time; CLIP: position in the same clip, also across
     * rescans) is one continuous sighting and is skipped. [frame] (not recycled here) is used for the crops;
     * [frameScale] = frame pixels per box unit when the boxes refer to a larger original (clip decoded downscaled).
     */
    suspend fun recordSightings(
        detections: List<PlateDetection>,
        source: SightingSource,
        mediaId: String? = null,
        positionMs: Long? = null,
        frame: Bitmap? = null,
        seenAt: Long = System.currentTimeMillis(),
        frameScale: Float = 1f,
    ): Int = mutex.withLock {
        if (source == SightingSource.LIVE) {
            lastDetection.entries.removeAll { it.key.startsWith("${SightingSource.LIVE}|") && seenAt - it.value >= DEDUPE_WINDOW_MS }
        }
        var written = 0
        for (d in detections.distinctBy { it.normalized }) {
            val key = "$source|$mediaId|${d.normalized}"
            val t = if (source == SightingSource.CLIP) positionMs ?: seenAt else seenAt
            val previous = lastDetection.put(key, t)
            if (previous != null && abs(t - previous) < DEDUPE_WINDOW_MS) continue
            if (source == SightingSource.CLIP && mediaId != null && positionMs != null &&
                dao.hasClipSightingNear(d.normalized, mediaId, positionMs, DEDUPE_WINDOW_MS)
            ) continue
            val crop = frame?.let { saveCrop(it, d, frameScale) }
            try {
                dao.addSighting(
                    d.normalized,
                    PlateSighting(
                        plateId = 0, display = d.text, mediaId = mediaId, positionMs = positionMs, source = source,
                        seenAt = seenAt, confidence = d.confidence, cropPath = crop,
                        boxLeft = d.box.left, boxTop = d.box.top, boxRight = d.box.right, boxBottom = d.box.bottom,
                    ),
                )
            } catch (t: Throwable) { // failed or cancelled: no orphan crop
                crop?.let { withContext(NonCancellable + Dispatchers.IO) { File(context.filesDir, it).delete() } }
                throw t
            }
            written++
        }
        written
    }

    /** Deletes the whole history and every crop. */
    suspend fun clear() = mutex.withLock {
        dao.clear()
        lastDetection.clear()
        withContext(Dispatchers.IO) { cropDir().deleteRecursively() }
    }

    /** Drops the in-memory dedupe state of a finished clip scan (a later rescan is deduped by the database). */
    suspend fun forgetClip(mediaId: String) = mutex.withLock {
        lastDetection.keys.removeAll { it.startsWith("${SightingSource.CLIP}|$mediaId|") }
        Unit
    }

    /** Deletes the sightings of one recording (and plates left without sightings) with their crops. */
    suspend fun clearForMedia(mediaId: String) = mutex.withLock {
        val crops = dao.cropsForMedia(mediaId)
        dao.clearForMedia(mediaId)
        lastDetection.keys.removeAll { it.startsWith("${SightingSource.CLIP}|$mediaId|") }
        withContext(Dispatchers.IO) { crops.forEach { File(context.filesDir, it).delete() } }
    }

    private suspend fun saveCrop(frame: Bitmap, d: PlateDetection, scale: Float): String? = withContext(Dispatchers.IO) {
        val padX = d.box.width() * 0.1f
        val padY = d.box.height() * 0.3f
        val r = Rect(
            ((d.box.left - padX) * scale).roundToInt().coerceAtLeast(0),
            ((d.box.top - padY) * scale).roundToInt().coerceAtLeast(0),
            ((d.box.right + padX) * scale).roundToInt().coerceAtMost(frame.width),
            ((d.box.bottom + padY) * scale).roundToInt().coerceAtMost(frame.height),
        )
        if (r.width() <= 0 || r.height() <= 0) return@withContext null
        val cut = Bitmap.createBitmap(frame, r.left, r.top, r.width(), r.height())
        val small = if (cut.width > CROP_MAX_WIDTH) {
            cut.scale(CROP_MAX_WIDTH, cut.height * CROP_MAX_WIDTH / cut.width)
        } else {
            cut
        }
        val path = "$CROP_DIR/${UUID.randomUUID()}.jpg"
        val ok = runCatching {
            File(context.filesDir, path).apply { parentFile?.mkdirs() }.outputStream()
                .use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }.getOrDefault(false)
        if (small !== cut) small.recycle()
        if (cut !== frame) cut.recycle()
        if (ok) path else null
    }

    private fun cropDir() = File(context.filesDir, CROP_DIR)

    companion object {
        const val DEDUPE_WINDOW_MS = 2_000L
        const val CROP_MAX_WIDTH = 320
        private const val CROP_DIR = "plates"
    }
}
