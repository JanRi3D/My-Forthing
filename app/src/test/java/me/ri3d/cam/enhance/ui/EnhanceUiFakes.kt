package me.ri3d.cam.enhance.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.graphics.Bitmap
import me.ri3d.cam.core.branding.Branding
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import me.ri3d.cam.enhance.ClipUpscaler
import me.ri3d.cam.enhance.EngineCost
import me.ri3d.cam.enhance.EnhanceEngine
import me.ri3d.cam.enhance.EnhancedFrame
import me.ri3d.cam.enhance.EnhancedKind
import me.ri3d.cam.enhance.EnhancedOutput
import me.ri3d.cam.enhance.EnhancementInfo
import me.ri3d.cam.enhance.EnhancerCapabilities
import me.ri3d.cam.enhance.FrameEnhancer
import me.ri3d.cam.enhance.UpscaleError
import me.ri3d.cam.enhance.UpscaleEstimate
import me.ri3d.cam.enhance.UpscaleFailure
import me.ri3d.cam.enhance.UpscaleProgress
import me.ri3d.cam.enhance.UpscaleRequest
import me.ri3d.cam.enhance.UpscaleResult
import me.ri3d.cam.enhance.outputSize
import me.ri3d.cam.enhance.writeSidecar
import me.ri3d.cam.media.BackupState
import me.ri3d.cam.media.MediaCategory
import me.ri3d.cam.media.MediaItem
import me.ri3d.cam.media.MediaKind
import java.io.File
import java.util.UUID

fun caps(
    engines: List<EnhanceEngine> = listOf(EnhanceEngine.ML, EnhanceEngine.CLASSICAL),
    maxOutputPixels: Long = 7680L * 4320,
    costs: List<EngineCost> = emptyList(),
) = EnhancerCapabilities(engines, "test-model".takeIf { EnhanceEngine.ML in engines }, maxOutputPixels, costs)

/** Scales by pixel repetition; [engineUsed] simulates the ML → classical fallback, [failWith] typed errors. */
class FakeFrameEnhancer(var caps: EnhancerCapabilities = caps()) : FrameEnhancer {
    var engineUsed: EnhanceEngine? = null
    var failWith: Throwable? = null
    var block: (suspend () -> Unit)? = null
    var capabilityCalls = 0

    override suspend fun enhanceFrame(src: Bitmap, scale: Int, engine: EnhanceEngine?, onProgress: (Float) -> Unit): EnhancedFrame {
        failWith?.let { throw it }
        onProgress(0.5f)
        block?.invoke()
        onProgress(1f)
        val used = engineUsed ?: engine ?: caps.engines.first()
        return EnhancedFrame(Bitmap.createScaledBitmap(src, src.width * scale, src.height * scale, false), used, "test-model".takeIf { used == EnhanceEngine.ML })
    }

    var capabilitiesError: Exception? = null

    override suspend fun capabilities(): EnhancerCapabilities {
        capabilityCalls++
        capabilitiesError?.let { throw it }
        return caps
    }
}

/**
 * These Robolectric tests run without the app's resources; `saveEnhancedFrame` reads the app name (EXIF "Software"),
 * so this context answers that one string.
 */
@Suppress("DEPRECATION") // the Resources constructor; fine for a test double
fun withAppName(base: Context): Context = object : ContextWrapper(base) {
    private val res = object : Resources(base.assets, base.resources.displayMetrics, base.resources.configuration) {
        override fun getString(id: Int): String = if (id == Branding.appName) "My Forthing" else super.getString(id)
    }

    override fun getResources(): Resources = res
    override fun getApplicationContext(): Context = this
}

/**
 * Estimates follow the pipeline's TargetNotLarger rule for a [sourceW]×[sourceH] clip; [etaMs] per engine (null =
 * not measured). [jobs] hands out the deferred each upscale returns (default: never completes).
 */
class FakeClipUpscaler(private val sourceW: Int = 1920, private val sourceH: Int = 1080) : ClipUpscaler {
    var etaMs: Map<EnhanceEngine, Long> = emptyMap()
    val requests = mutableListOf<UpscaleRequest>()
    var progress: UpscaleProgress? = null
    var job: (UpscaleRequest) -> Deferred<UpscaleResult> = { CompletableDeferred() }
    val handedOut = mutableListOf<Deferred<UpscaleResult>>()
    val last get() = handedOut.lastOrNull()

    override fun upscale(request: UpscaleRequest, onProgress: (UpscaleProgress) -> Unit): Deferred<UpscaleResult> {
        requests += request
        progress?.let(onProgress)
        return job(request).also { handedOut += it }
    }

    override suspend fun estimate(request: UpscaleRequest): Result<UpscaleEstimate> {
        if (request.target.height <= minOf(sourceW, sourceH)) return Result.failure(UpscaleFailure(UpscaleError.TargetNotLarger("test")))
        val (w, h) = outputSize(sourceW, sourceH, request.target.height)
        return Result.success(UpscaleEstimate(w, h, 60_000, etaMs[request.engine], w.toLong() * h))
    }
}

/** A finished output in `filesDir/enhance/` with its sidecar, as the real pipeline leaves it. */
fun upscaledOutput(context: Context, sourceMediaId: String): UpscaleResult.Done {
    val id = UUID.randomUUID().toString()
    val file = File(context.filesDir, "enhance/$id.mp4").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(100)) }
    val info = EnhancementInfo(EnhancedKind.UPSCALED_CLIP, EnhanceEngine.CLASSICAL, null, 1.333f, sourceMediaId, null, System.currentTimeMillis())
    writeSidecar(file, info)
    return UpscaleResult.Done(EnhancedOutput(id, file, info), 2560, 1440, true, 100)
}

/** A library row with a phone copy at [file]. */
fun localItem(kind: MediaKind, file: File?, id: String = UUID.randomUUID().toString()) = MediaItem(
    id = id, kind = kind, category = MediaCategory.NORMAL, recorderType = 0, recorderPath = null, recorderThumbPath = null,
    originalFileName = file?.name ?: "a.mp4", recorderTime = null, recorderTimeEpochGuess = null,
    localUri = file?.toURI()?.toString(), localSizeBytes = file?.length(), localThumbPath = null, downloadedAt = null,
    parentId = null, parentPositionMs = null, driveFileId = null, backupState = BackupState.NONE, backupError = null, driveMd5 = null,
    createdAt = 0,
)
