package to.axolotl.cam.enhance

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import to.axolotl.cam.core.log.Log
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

enum class EnhanceEngine { ML, CLASSICAL }

/**
 * CONTRACTS §12. Output pixels are reconstructed, never evidence; the original bitmap is not modified.
 * Failures that depend on the input or the phone throw [EnhanceException]. Results above ~100 MB (1080p×4 is
 * 133 MB) cannot be drawn by a Canvas: show a downsampled preview or the saved JPEG.
 */
interface FrameEnhancer {
    /** [scale] is 2 or 4. Uses the best available engine; cancellable between tiles. */
    suspend fun enhance(src: Bitmap, scale: Int, onProgress: (Float) -> Unit): Bitmap =
        enhanceFrame(src, scale, onProgress = onProgress).bitmap

    /**
     * Like [enhance], plus which engine produced the pixels (for [EnhancementInfo]). [engine] null = best available;
     * ML falls back to CLASSICAL (whole frame) if the model is missing or fails. `onProgress` runs on a
     * `Dispatchers.Default` thread.
     */
    suspend fun enhanceFrame(
        src: Bitmap,
        scale: Int,
        engine: EnhanceEngine? = null,
        onProgress: (Float) -> Unit = {},
    ): EnhancedFrame

    /** Engines, limits and measured cost; the first call on a device runs a short benchmark (≈ 1 s on the emulator). */
    suspend fun capabilities(): EnhancerCapabilities
}

data class EnhancedFrame(val bitmap: Bitmap, val engine: EnhanceEngine, val model: String?)

/** Typed frame failures for German UI messages. */
sealed interface EnhanceError {
    /** Output above [EnhancerCapabilities.maxOutputPixels] for this phone. */
    data object TooLarge : EnhanceError

    /** The phone ran out of memory while enhancing. */
    data object Memory : EnhanceError
}

class EnhanceException(val error: EnhanceError) : Exception(error.toString())

data class EngineCost(val engine: EnhanceEngine, val scale: Int, val msPerMegapixel: Float)

data class EnhancerCapabilities(
    /** Preferred first; CLASSICAL is always present. */
    val engines: List<EnhanceEngine>,
    val model: String?,
    val maxOutputPixels: Long,
    /** Measured on this device, per source megapixel (tile overlap included). */
    val costs: List<EngineCost>,
) {
    fun maxInputPixels(scale: Int): Long = maxOutputPixels / (scale * scale)

    /** Null when this engine/scale was not measured: the UI then shows no estimate. */
    fun estimateMs(width: Int, height: Int, scale: Int, engine: EnhanceEngine = engines.first()): Long? =
        costs.firstOrNull { it.engine == engine && it.scale == scale }
            ?.let { (width.toLong() * height / 1e6 * it.msPerMegapixel).toLong() }
}

/**
 * The shipped model (QuickSRNet Medium ×4, BSD-3-Clause, provenance in docs/features/enhance.md and
 * assets/models/LICENSE-quicksrnetmedium.txt). Changing the file means changing [ID]: measurements are cached per id.
 */
internal object ShippedModel {
    const val ID = "quicksrnetmedium-x4-float-qaihub-0.63.0"
    const val ASSET = "models/quicksrnetmedium_float.tflite"
}

/** Cubic taps (2) + denoise (1) reach 3 px: with that margin tiled output equals untiled output. */
internal val CLASSICAL_TILING = TileSpec(tile = 256, overlap = 8, margin = 3)

/** CNN zero-padding artefacts measured at ≈ 2 source px from a tile edge; 4 leaves headroom. */
internal fun SrModel.tiling() = TileSpec(tile = inputSize, overlap = 16, margin = 4)

@Singleton
class DefaultFrameEnhancer @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named(ENHANCE_STORE) private val store: DataStore<Preferences>,
) : FrameEnhancer {
    private val mutex = Mutex()
    private var model: SrModel? = null
    private var modelTried = false

    @Volatile
    private var cached: EnhancerCapabilities? = null

    /** Lazily loads the shipped model; null (classical only) when the asset is absent or LiteRT fails. */
    private fun model(): SrModel? {
        if (!modelTried) {
            modelTried = true
            model = try {
                context.assets.open(ShippedModel.ASSET).use { SrModel(it.readBytes(), threads()) }
            } catch (e: Exception) {
                Log.w(TAG, "ML model unavailable, classical only: ${e.javaClass.simpleName}")
                null
            }
        }
        return model
    }

    override suspend fun enhanceFrame(
        src: Bitmap,
        scale: Int,
        engine: EnhanceEngine?,
        onProgress: (Float) -> Unit,
    ): EnhancedFrame = withContext(Dispatchers.Default) {
        require(scale == 2 || scale == 4) { "scale must be 2 or 4" }
        if (src.width.toLong() * src.height * scale * scale > maxOutputPixels(context)) throw EnhanceException(EnhanceError.TooLarge)
        try {
            enhanceInto(src, scale, engine, onProgress)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "enhance failed: out of memory")
            throw EnhanceException(EnhanceError.Memory)
        }
    }

    private suspend fun enhanceInto(src: Bitmap, scale: Int, engine: EnhanceEngine?, onProgress: (Float) -> Unit): EnhancedFrame {
        val input = if (src.config == Bitmap.Config.ARGB_8888) src else src.copy(Bitmap.Config.ARGB_8888, false)
        val out = try {
            createBitmap(src.width * scale, src.height * scale)
        } catch (t: Throwable) {
            if (input !== src) input.recycle()
            throw t
        }
        return try {
            mutex.withLock {
                val ml = if (engine != EnhanceEngine.CLASSICAL) model() else null
                if (ml != null) {
                    try {
                        upscaleTiled(BitmapPixels(input), BitmapPixels(out), scale, ml.tiling(), ml, onProgress)
                        return@withLock EnhancedFrame(out, EnhanceEngine.ML, ShippedModel.ID)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "ML enhance failed, falling back to classical: ${e.javaClass.simpleName}")
                    }
                }
                upscaleTiled(BitmapPixels(input), BitmapPixels(out), scale, CLASSICAL_TILING, Classical, onProgress)
                EnhancedFrame(out, EnhanceEngine.CLASSICAL, null)
            }
        } catch (t: Throwable) {
            out.recycle()
            throw t
        } finally {
            if (input !== src) input.recycle()
        }
    }

    override suspend fun capabilities(): EnhancerCapabilities = cached ?: withContext(Dispatchers.Default) {
        // The measurement shares the model with running enhancements, so it waits for the lock; later calls don't.
        mutex.withLock {
            cached ?: run {
                val ml = model()
                val engines = listOfNotNull(EnhanceEngine.ML.takeIf { ml != null }, EnhanceEngine.CLASSICAL)
                val stored = store.data.first()
                // ML cost is the model run (the 2× box-downsample is negligible): measured once at 4×.
                val mlCost = ml?.let { cost(stored, EnhanceEngine.ML, 4) { measure(it, it.tiling(), 4) } }
                val costs = listOf(2, 4).flatMap { scale ->
                    listOfNotNull(
                        cost(stored, EnhanceEngine.CLASSICAL, scale) { measure(Classical, CLASSICAL_TILING, scale) },
                        mlCost?.copy(scale = scale),
                    )
                }
                EnhancerCapabilities(engines, ShippedModel.ID.takeIf { ml != null }, maxOutputPixels(context), costs)
                    .also { cached = it }
            }
        }
    }

    private suspend fun cost(stored: Preferences, engine: EnhanceEngine, scale: Int, measure: suspend () -> Float): EngineCost {
        val key = floatPreferencesKey("ms_per_mp_${engine.name.lowercase()}_x${scale}_${ShippedModel.ID}")
        val value = stored[key] ?: measure().also { v -> store.edit { it[key] = v } }
        return EngineCost(engine, scale, value)
    }

    companion object {
        private const val TAG = "Enhance"

        /**
         * ms per source megapixel: one warm-up tile, then one timed 2×2-tile image through the real tiling path.
         * ponytail: single run; take the median of several if estimates prove noisy.
         */
        internal suspend fun measure(engine: TileUpscaler, spec: TileSpec, scale: Int): Float {
            engine.upscale(IntArray(spec.tile * spec.tile), spec.tile, spec.tile, scale)
            val size = 2 * spec.tile - spec.overlap - 2 * spec.margin // with the virtual border: exactly 2×2 tiles
            val pixels = IntArray(size * size) { i -> argb(i % size * 255 / size, i / size * 255 / size, (i % size xor i / size) and 0xff) }
            val src = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
            val dst = createBitmap(size * scale, size * scale)
            try {
                val start = System.nanoTime()
                upscaleTiled(BitmapPixels(src), BitmapPixels(dst), scale, spec, engine) {}
                return (System.nanoTime() - start) / 1e6f / (size * size / 1e6f)
            } finally {
                src.recycle()
                dst.recycle()
            }
        }

        internal fun threads() = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

        internal fun maxOutputPixels(context: Context): Long {
            val am = context.getSystemService(ActivityManager::class.java)
            val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            return maxOutputPixels(info.totalMem, am.isLowRamDevice)
        }

        /**
         * The ARGB output may use at most 1/16 of the phone's RAM, capped at 1080p×4 (≈ 33 MP, 133 MB) and at 2160p
         * on low-RAM devices: 2 GB phones keep 1080p×4, a 1.5 GB phone gets ≈ 25 MP.
         */
        internal fun maxOutputPixels(totalMemBytes: Long, lowRam: Boolean): Long =
            minOf(if (lowRam) 3840L * 2160 else 7680L * 4320, totalMemBytes / 16 / 4)
    }
}

internal class BitmapPixels(private val bitmap: Bitmap) : Pixels {
    override val width get() = bitmap.width
    override val height get() = bitmap.height
    override fun read(x: Int, y: Int, w: Int, h: Int, out: IntArray, offset: Int, stride: Int) =
        bitmap.getPixels(out, offset, stride, x, y, w, h)
    override fun write(x: Int, y: Int, w: Int, h: Int, src: IntArray, offset: Int, stride: Int) =
        bitmap.setPixels(src, offset, stride, x, y, w, h)
}
