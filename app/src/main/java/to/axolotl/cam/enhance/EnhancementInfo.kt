package to.axolotl.cam.enhance

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

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
    val sourceMediaId: String?,
    val sourcePositionMs: Long?,
    val createdAt: Long,
) {
    fun toJson(): String = JSONObject()
        .put("format", 1)
        .put("kind", kind.name)
        .put("engine", engine.name)
        .put("model", model ?: JSONObject.NULL)
        .put("scale", scale.toDouble())
        .put("sourceMediaId", sourceMediaId ?: JSONObject.NULL)
        .put("sourcePositionMs", sourcePositionMs ?: JSONObject.NULL)
        .put("createdAt", OffsetDateTime.ofInstant(Instant.ofEpochMilli(createdAt), ZoneId.systemDefault()).toString())
        .put("reconstructed", true)
        .put("note", NOTE)
        .toString()

    companion object {
        const val NOTE = "rekonstruiert, kein Beweis"

        fun sidecarOf(output: File) = File(output.path + ".enhance.json")

        /** Null when the sidecar is missing or unreadable. */
        fun read(output: File): EnhancementInfo? = runCatching {
            val o = JSONObject(sidecarOf(output).readText())
            EnhancementInfo(
                kind = EnhancedKind.valueOf(o.getString("kind")),
                engine = EnhanceEngine.valueOf(o.getString("engine")),
                model = o.optString("model").takeUnless { o.isNull("model") },
                scale = o.getDouble("scale").toFloat(),
                sourceMediaId = o.optString("sourceMediaId").takeUnless { o.isNull("sourceMediaId") },
                sourcePositionMs = if (o.isNull("sourcePositionMs")) null else o.getLong("sourcePositionMs"),
                createdAt = OffsetDateTime.parse(o.getString("createdAt")).toInstant().toEpochMilli(),
            )
        }.getOrNull()
    }
}

/** A finished output in `filesDir/enhance/`; [id] is a fresh UUID the UI phase can reuse as `MediaItem.id`. */
data class EnhancedOutput(val id: String, val file: File, val info: EnhancementInfo)

internal fun enhanceDir(context: Context) = File(context.filesDir, "enhance").apply { mkdirs() }

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

/** Saves an enhanced frame as JPEG (quality 95) plus sidecar; the original is untouched. */
suspend fun saveEnhancedFrame(
    context: Context,
    frame: EnhancedFrame,
    scale: Int,
    sourceMediaId: String?,
    sourcePositionMs: Long?,
): EnhancedOutput = withContext(Dispatchers.IO) {
    val id = UUID.randomUUID().toString()
    val file = File(enhanceDir(context), "$id.jpg")
    val tmp = File(file.path + ".tmp")
    val info = EnhancementInfo(
        EnhancedKind.ENHANCED_FRAME, frame.engine, frame.model, scale.toFloat(), sourceMediaId, sourcePositionMs,
        System.currentTimeMillis(),
    )
    try {
        tmp.outputStream().use { check(frame.bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) { "JPEG encode failed" } }
        writeSidecar(file, info)
        moveAtomic(tmp, file)
    } catch (t: Throwable) {
        tmp.delete()
        EnhancementInfo.sidecarOf(file).delete()
        throw t
    }
    EnhancedOutput(id, file, info)
}
