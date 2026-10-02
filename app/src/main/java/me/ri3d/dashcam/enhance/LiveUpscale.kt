package me.ri3d.dashcam.enhance

import android.graphics.HardwareRenderer
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ri3d.dashcam.core.log.Log
import javax.inject.Inject

/**
 * Live-view "upscale": the compositor already scales the stream to the view; this AGSL unsharp mask (radius ≈ the
 * scale factor) restores edge contrast on the GPU. Apply it to the TextureView/PlayerView layer
 * (`View.setRenderEffect` or Compose `graphicsLayer { renderEffect = … }`) only when [LiveUpscaleProbe] offers it.
 */
object LiveSharpen {
    const val AMOUNT = 0.6f

    private const val AGSL = """
uniform shader content;
uniform float amount;
uniform float radius;
half4 main(float2 p) {
    half4 c = content.eval(p);
    half3 blur = (content.eval(p + float2(-radius, 0.0)).rgb + content.eval(p + float2(radius, 0.0)).rgb
        + content.eval(p + float2(0.0, -radius)).rgb + content.eval(p + float2(0.0, radius)).rgb) * 0.25;
    return half4(clamp(c.rgb + half(amount) * (c.rgb - blur), 0.0, 1.0), c.a);
}
"""

    /** Null below Android 13 (no RuntimeShader). [scale] = displayed size ÷ stream size. */
    fun effect(scale: Float, amount: Float = AMOUNT): RenderEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val shader = RuntimeShader(AGSL).apply {
            setFloatUniform("amount", amount)
            setFloatUniform("radius", scale.coerceAtLeast(1f))
        }
        return RenderEffect.createRuntimeShaderEffect(shader, "content")
    }
}

data class LiveUpscaleDecision(
    val offer: Boolean,
    val reason: Reason,
    /** GPU scale ×2 + [LiveSharpen], median ms per frame (null if not measurable here). */
    val classicalMs360p: Float?,
    val classicalMs720p: Float?,
    /** ML ×2 per frame, from the measured [FrameEnhancer] cost (null without a model). */
    val mlMs360p: Float?,
    val mlMs720p: Float?,
) {
    enum class Reason { OK, API_TOO_OLD, GPU_TOO_SLOW, PROBE_FAILED }
}

/** Measures on this device whether `AppPreferences.liveUpscale` may be offered. Takes about a second. */
class LiveUpscaleProbe @Inject constructor(private val frameEnhancer: FrameEnhancer) {

    suspend fun run(): LiveUpscaleDecision = withContext(Dispatchers.Default) {
        val caps = frameEnhancer.capabilities()
        fun ml(w: Int, h: Int) = caps.estimateMs(w, h, 2, EnhanceEngine.ML)?.toFloat()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return@withContext LiveUpscaleDecision(false, LiveUpscaleDecision.Reason.API_TOO_OLD, null, null, ml(640, 360), ml(1280, 720))
        }
        val (gpu360, gpu720) = try {
            measureGpu(640, 360) to measureGpu(1280, 720)
        } catch (e: Exception) {
            Log.w(TAG, "live probe failed: ${e.javaClass.simpleName}")
            return@withContext LiveUpscaleDecision(false, LiveUpscaleDecision.Reason.PROBE_FAILED, null, null, ml(640, 360), ml(1280, 720))
        }
        val offer = gpu720 <= FRAME_BUDGET_MS
        LiveUpscaleDecision(
            offer, if (offer) LiveUpscaleDecision.Reason.OK else LiveUpscaleDecision.Reason.GPU_TOO_SLOW,
            gpu360, gpu720, ml(640, 360), ml(1280, 720),
        )
    }

    companion object {
        private const val TAG = "Enhance"

        /** A quarter of a 30 fps frame, leaving room for decoding, compositing and plate recognition. */
        const val FRAME_BUDGET_MS = 8f
        private const val WARMUP = 5
        private const val FRAMES = 30

        /** Median wall time until the GPU finished one ×2 frame with [LiveSharpen] through HWUI. */
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        internal fun measureGpu(srcW: Int, srcH: Int): Float {
            val outW = srcW * 2
            val outH = srcH * 2
            val src = createBitmap(srcW, srcH).apply {
                android.graphics.Canvas(this).drawPaint(Paint().apply {
                    shader = LinearGradient(0f, 0f, srcW.toFloat(), srcH.toFloat(), 0xff203040.toInt(), 0xffe0d0c0.toInt(), Shader.TileMode.MIRROR)
                })
            }
            val reader = ImageReader.newInstance(
                outW, outH, PixelFormat.RGBA_8888, 2,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
            )
            val node = RenderNode("live-probe").apply {
                setPosition(0, 0, outW, outH)
                setRenderEffect(LiveSharpen.effect(2f))
            }
            val renderer = HardwareRenderer().apply {
                setSurface(reader.surface)
                setContentRoot(node)
            }
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val times = FloatArray(FRAMES)
            try {
                repeat(WARMUP + FRAMES) { i ->
                    node.beginRecording().drawBitmap(src, null, Rect(0, 0, outW, outH), paint)
                    node.endRecording()
                    val start = System.nanoTime()
                    renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
                    reader.acquireLatestImage()?.use { it.fence.awaitForever() }
                    if (i >= WARMUP) times[i - WARMUP] = (System.nanoTime() - start) / 1e6f
                }
            } finally {
                renderer.destroy()
                reader.close()
                src.recycle()
            }
            times.sort()
            return times[FRAMES / 2]
        }
    }
}
