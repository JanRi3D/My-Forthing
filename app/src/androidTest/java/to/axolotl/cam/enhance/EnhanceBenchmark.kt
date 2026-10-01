package to.axolotl.cam.enhance

import android.graphics.Bitmap
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * Model/engine evaluation (docs/features/enhance.md). Opt-in, slow:
 * `-Pandroid.testInstrumentationRunnerArguments.enhanceBench=1`. Candidate models are read from the test APK's
 * assets `candidates/<file>.tflite` (not committed: download them as listed in the doc); missing ones are skipped.
 * Results go to logcat tag `EnhanceBench`.
 */
@RunWith(AndroidJUnit4::class)
class EnhanceBenchmark {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private class Candidate(val name: String, val file: String, val valueRange: Float = 1f, val overlap: Int = 16)

    private val candidates = listOf(
        Candidate("Real-ESRGAN-General-x4v3 float (QAIHub)", "real_esrgan_general_x4v3_float.tflite"),
        Candidate("Real-ESRGAN-General-x4v3 w8a8 (QAIHub)", "real_esrgan_general_x4v3_w8a8.tflite"),
        Candidate("ESRGAN-tf2 50x50 (Kaggle)", "esrgan_tf2_50.tflite", valueRange = 255f, overlap = 8),
        Candidate("XLSR float (QAIHub)", "xlsr_float.tflite"),
        Candidate("QuickSRNetSmall float (QAIHub)", "quicksrnetsmall_float.tflite"),
        Candidate("QuickSRNetSmall w8a8 (QAIHub)", "quicksrnetsmall_w8a8.tflite"),
        Candidate("QuickSRNetMedium float (QAIHub)", "quicksrnetmedium_float.tflite"),
        Candidate("QuickSRNetLarge float (QAIHub)", "quicksrnetlarge_float.tflite"),
        Candidate("SESR-M5 float (QAIHub)", "sesr_m5_float.tflite"),
    )

    @Before
    fun optIn() = assumeTrue(InstrumentationRegistry.getArguments().getString("enhanceBench") == "1")

    private fun log(line: String) {
        Log.i("EnhanceBench", line)
    }

    private fun f(v: Double) = String.format(Locale.US, "%.2f", v)

    @Test
    fun candidates() = runBlocking<Unit> {
        val hr = TestMedia.reference(1024, 576)
        val lr4 = TestMedia.boxDown(hr, 4)
        val lr2 = TestMedia.boxDown(hr, 2)
        fun quality(name: String, x4: Bitmap, x2: Bitmap) = log(
            "QUALITY | $name | x4 PSNR ${f(TestMedia.psnr(hr, x4, 16))} SSIM ${f(TestMedia.ssim(hr, x4, 16))}" +
                " | x2 PSNR ${f(TestMedia.psnr(hr, x2, 8))} SSIM ${f(TestMedia.ssim(hr, x2, 8))}",
        )
        quality(
            "bilinear (Bitmap.createScaledBitmap)",
            Bitmap.createScaledBitmap(lr4, 1024, 576, true),
            Bitmap.createScaledBitmap(lr2, 1024, 576, true),
        )
        val bicubic = TileUpscaler { src, w, h, s -> Classical.resample(src, w, h, s, 0f) }
        quality("bicubic Catmull-Rom (a=0, no denoise)", TestMedia.upscale(lr4, 4, CLASSICAL_TILING, bicubic), TestMedia.upscale(lr2, 2, CLASSICAL_TILING, bicubic))
        quality("classical (denoise + sharpened cubic a=0.5)", TestMedia.upscale(lr4, 4, CLASSICAL_TILING, Classical), TestMedia.upscale(lr2, 2, CLASSICAL_TILING, Classical))

        val frame = TestMedia.reference(1920, 1080)
        for (scale in listOf(2, 4)) {
            val times = (0 until 3).map {
                val t = SystemClock.elapsedRealtime()
                TestMedia.upscale(frame, scale, CLASSICAL_TILING, Classical).recycle()
                SystemClock.elapsedRealtime() - t
            }.sorted()
            log("SPEED | classical | 1080p x$scale measured ${times[1]} ms (median of 3)")
        }

        val assets = instrumentation.context.assets
        for (c in candidates) {
            val bytes = runCatching { assets.open("candidates/${c.file}").use { it.readBytes() } }.getOrNull()
            if (bytes == null) {
                log("SKIP | ${c.name} | asset candidates/${c.file} missing")
                continue
            }
            val heapBefore = Debug.getNativeHeapAllocatedSize()
            val loadStart = SystemClock.elapsedRealtime()
            val model = SrModel(bytes, DefaultFrameEnhancer.threads(), c.valueRange)
            val loadMs = SystemClock.elapsedRealtime() - loadStart
            try {
                val tile = model.inputSize
                val spec = TileSpec(tile, c.overlap, margin = c.overlap / 4)
                val px = IntArray(tile * tile) { argb(it % 255, it / tile % 255, 90) }
                model.upscale(px, tile, tile, model.nativeScale)
                val heapMb = (Debug.getNativeHeapAllocatedSize() - heapBefore) / 1048576.0
                val tileMs = (0 until 5).map {
                    val t = System.nanoTime()
                    model.upscale(px, tile, tile, model.nativeScale)
                    (System.nanoTime() - t) / 1e6
                }.sorted()[2]
                val tiles = planTiles(1920 + 2 * spec.margin, 1080 + 2 * spec.margin, tile, c.overlap).size
                log(
                    "SPEED | ${c.name} | ${bytes.size / 1024} KiB | in ${tile}x$tile x${model.nativeScale} | load $loadMs ms" +
                        " | ${f(tileMs)} ms/tile | 1080p: $tiles tiles ≈ ${(tiles * tileMs).toLong()} ms (x4 and x2)" +
                        " | native heap +${f(heapMb)} MB",
                )
                if (model.nativeScale == 4) {
                    quality(c.name, TestMedia.upscale(lr4, 4, spec, model), TestMedia.upscale(lr2, 2, spec, model))
                } else {
                    log("QUALITY | ${c.name} | native x${model.nativeScale}, not comparable at x2/x4")
                }
            } finally {
                model.close()
            }
        }
    }

    /** Shipped model through the real FrameEnhancer on a full 1080p frame (validates the per-tile projection). */
    @Test
    fun shippedModelFullFrame() = runBlocking<Unit> {
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
            File(context.cacheDir, "bench-${System.nanoTime()}.preferences_pb")
        }
        val enhancer = DefaultFrameEnhancer(context, store)
        val caps = enhancer.capabilities()
        log("CAPS | engines ${caps.engines} | model ${caps.model} | costs ${caps.costs}")
        val frame = TestMedia.reference(1920, 1080)
        for (scale in listOf(4, 2)) {
            val t = SystemClock.elapsedRealtime()
            val out = enhancer.enhanceFrame(frame, scale)
            val ms = SystemClock.elapsedRealtime() - t
            log("FULL | ${out.engine} ${out.model} | 1080p x$scale $ms ms | estimate ${caps.estimateMs(1920, 1080, scale, out.engine)} ms")
            out.bitmap.recycle()
        }
    }

    /**
     * GPU classical clip path end to end (decode → GL cubic → H.264 → mux). 1080p → 1440p/2160p are the real cases
     * (they fail with `Encoder` on the emulator); the smaller ones fit the emulator's software encoder.
     * Runner argument `enhanceClipSeconds` (default 4) lengthens the clip for battery/thermal runs on a phone.
     */
    @Test
    fun clipThroughput() = runBlocking<Unit> {
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
            File(context.cacheDir, "bench-${System.nanoTime()}.preferences_pb")
        }
        val upscaler = DefaultClipUpscaler(context, DefaultFrameEnhancer(context, store), store)
        val seconds = InstrumentationRegistry.getArguments().getString("enhanceClipSeconds")?.toDouble() ?: 4.0
        val frames = (30 * seconds).toInt()
        val cases = listOf(Pair(1920 to 1080, 1440), Pair(1920 to 1080, 2160), Pair(960 to 540, 1080), Pair(640 to 360, 720))
        for ((src, target) in cases) {
            val input = File(context.cacheDir, "bench-${src.first}.mp4")
            TestMedia.writeClip(input, src.first, src.second, 30, seconds, audio = true)
            val t = SystemClock.elapsedRealtime()
            val result = upscaler.upscale(UpscaleRequest(Uri.fromFile(input), Resolution.P1440), target) {}.awaitResult()
            val ms = SystemClock.elapsedRealtime() - t
            log("CLIP | ${src.first}x${src.second} -> ${target}p classical | $frames frames | $ms ms | ${ms / frames} ms/frame | $result")
            (result as? UpscaleResult.Done)?.output?.file?.let { it.delete(); EnhancementInfo.sidecarOf(it).delete() }
            input.delete()
        }
    }

    @Test
    fun liveProbe() = runBlocking<Unit> {
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
            File(context.cacheDir, "bench-${System.nanoTime()}.preferences_pb")
        }
        log("LIVE | ${LiveUpscaleProbe(DefaultFrameEnhancer(context, store)).run()}")
    }
}
