package to.axolotl.cam.enhance

import android.content.Context
import android.graphics.Bitmap
import android.os.Process
import android.os.SystemClock
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import to.axolotl.cam.core.branding.Branding
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Names match `MediaKind` (CONTRACTS §8) so the UI phase can map 1:1. */
enum class EnhancedKind { ENHANCED_FRAME, UPSCALED_CLIP }

/**
 * Sidecar `<output>.enhance.json` next to every output: what produced it and from which original. It labels the
 * file as reconstructed; it never claims verification and carries no source metadata beyond the link.
 */
data class EnhancementInfo(
    val kind: EnhancedKind,
    val engine: EnhanceEngine,
    val model: String?,
    /** Output size ÷ source size (e.g. 4.0, or 1.333 for 1080p → 1440p). */
    val scale: Float,
    /** `MediaItem.id` of the original; always set, so an output never loses its source. */
    val sourceMediaId: String,
    val sourcePositionMs: Long?,
    val createdAt: Long,
) {
    fun toJson(): String = JSONObject()
        .put("format", 1)
        .put("kind", kind.name)
        .put("engine", engine.name)
        .put("model", model ?: JSONObject.NULL)
        .put("scale", scale.toDouble())
        .put("sourceMediaId", sourceMediaId)
        .put("sourcePositionMs", sourcePositionMs ?: JSONObject.NULL)
        .put("createdAt", OffsetDateTime.ofInstant(Instant.ofEpochMilli(createdAt), ZoneId.systemDefault()).toString())
        .put("reconstructed", true)
        .put("note", NOTE)
        .toString()

    companion object {
        const val NOTE = "rekonstruiert, kein Beweis"
        internal const val SIDECAR_SUFFIX = ".enhance.json"

        fun sidecarOf(output: File) = File(output.path + SIDECAR_SUFFIX)

        /** Null when the sidecar is missing or unreadable. */
        fun read(output: File): EnhancementInfo? = runCatching {
            val o = JSONObject(sidecarOf(output).readText())
            EnhancementInfo(
                kind = EnhancedKind.valueOf(o.getString("kind")),
                engine = EnhanceEngine.valueOf(o.getString("engine")),
                model = o.optString("model").takeUnless { o.isNull("model") },
                scale = o.getDouble("scale").toFloat(),
                sourceMediaId = o.getString("sourceMediaId"),
                sourcePositionMs = if (o.isNull("sourcePositionMs")) null else o.getLong("sourcePositionMs"),
                createdAt = OffsetDateTime.parse(o.getString("createdAt")).toInstant().toEpochMilli(),
            )
        }.getOrNull()
    }
}

/** A finished output in `filesDir/enhance/`; [id] is a fresh UUID the UI phase can reuse as `MediaItem.id`. */
data class EnhancedOutput(val id: String, val file: File, val info: EnhancementInfo)

private val swept = AtomicBoolean(false)

/** `filesDir/enhance/`; the first access per process removes what an earlier, killed process left behind. */
internal fun enhanceDir(context: Context): File {
    val dir = File(context.filesDir, "enhance").apply { mkdirs() }
    if (swept.compareAndSet(false, true)) {
        val processStart = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
        sweepLeftovers(dir, processStart)
    }
    return dir
}

/**
 * Deletes files older than [before] (i.e. from an earlier process, never a running job's): temp files, outputs
 * without a sidecar and sidecars without an output. Returns the deleted names.
 */
internal fun sweepLeftovers(dir: File, before: Long): List<String> =
    dir.listFiles().orEmpty().filter { f ->
        f.lastModified() < before && when {
            f.name.endsWith(".tmp") -> true
            f.name.endsWith(EnhancementInfo.SIDECAR_SUFFIX) -> !File(f.path.removeSuffix(EnhancementInfo.SIDECAR_SUFFIX)).exists()
            else -> !EnhancementInfo.sidecarOf(f).exists()
        } && f.delete()
    }.map { it.name }

/** Labels the JPEG itself, so the note survives when the file travels without its sidecar. */
internal fun stampExif(jpeg: File, info: EnhancementInfo, software: String) {
    ExifInterface(jpeg).apply {
        setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, EnhancementInfo.NOTE)
        setAttribute(
            ExifInterface.TAG_USER_COMMENT,
            "${EnhancementInfo.NOTE}; engine=${info.engine}; model=${info.model ?: "-"}; source=${info.sourceMediaId}",
        )
        setAttribute(ExifInterface.TAG_SOFTWARE, software)
        saveAttributes()
    }
}

/** Writes the sidecar for [output] atomically (temp file + rename). */
internal fun writeSidecar(output: File, info: EnhancementInfo) {
    val sidecar = EnhancementInfo.sidecarOf(output)
    val tmp = File(sidecar.path + ".tmp")
    tmp.writeText(info.toJson())
    moveAtomic(tmp, sidecar)
}

internal fun moveAtomic(from: File, to: File) {
    Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
}

/**
 * Saves an enhanced frame as JPEG (quality 95) plus sidecar; the original is untouched. [sourceMediaId] is the
 * original's `MediaItem.id` (required: outputs stay linked).
 */
suspend fun saveEnhancedFrame(
    context: Context,
    frame: EnhancedFrame,
    scale: Int,
    sourceMediaId: String,
    sourcePositionMs: Long?,
): EnhancedOutput {
    val id = UUID.randomUUID().toString()
    val file = File(enhanceDir(context), "$id.jpg")
    val info = EnhancementInfo(
        EnhancedKind.ENHANCED_FRAME, frame.engine, frame.model, scale.toFloat(), sourceMediaId, sourcePositionMs,
        System.currentTimeMillis(),
    )
    try {
        withContext(Dispatchers.IO) {
            val tmp = File(file.path + ".tmp")
            try {
                tmp.outputStream().use { check(frame.bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) { "JPEG encode failed" } }
                stampExif(tmp, info, context.getString(Branding.appName))
                writeSidecar(file, info)
                moveAtomic(tmp, file)
            } finally {
                tmp.delete()
            }
        }
    } catch (t: Throwable) {
        // Includes a cancellation after the rename: the caller never sees the output, so it must not remain.
        file.delete()
        EnhancementInfo.sidecarOf(file).delete()
        throw t
    }
    return EnhancedOutput(id, file, info)
}
