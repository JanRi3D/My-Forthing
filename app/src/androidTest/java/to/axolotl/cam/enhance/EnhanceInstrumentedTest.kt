package to.axolotl.cam.enhance

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import androidx.core.graphics.createBitmap
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

/** Runs on a device/emulator (`ANDROID_SERIAL=emulator-5556`). Emulator encoders stop at ≈ 2048×1024, so clips go to 720p. */
@RunWith(AndroidJUnit4::class)
class EnhanceInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
        File(context.cacheDir, "enhance-test-${System.nanoTime()}.preferences_pb")
    }
    private val enhancer = DefaultFrameEnhancer(context, store)
    private val upscaler = DefaultClipUpscaler(context, enhancer, store)
    private val created = mutableListOf<File>()

    @After
    fun cleanUp() {
        created.forEach { it.delete(); EnhancementInfo.sidecarOf(it).delete() }
    }

    private fun leftovers() = enhanceDir(context).listFiles().orEmpty().filter { it.name.endsWith(".tmp") }

    /** Largest step between neighbouring pixels of one channel, scanned row by row (no full-image IntArray). */
    private fun maxStep(b: Bitmap, horizontal: Boolean): Int {
        val row = IntArray(b.width)
        val prev = IntArray(b.width)
        var worst = 0
        for (y in 0 until b.height) {
            b.getPixels(row, 0, b.width, 0, y, b.width, 1)
            for (x in 0 until b.width) {
                worst = if (horizontal) {
                    if (x == 0) worst else maxOf(worst, abs((row[x] shr 16 and 0xff) - (row[x - 1] shr 16 and 0xff)))
                } else {
                    if (y == 0) worst else maxOf(worst, abs((row[x] shr 8 and 0xff) - (prev[x] shr 8 and 0xff)))
                }
            }
            row.copyInto(prev)
        }
        return worst
    }

    @Test
    fun capabilitiesListBothEnginesWithMeasuredCosts() = runBlocking<Unit> {
        val caps = enhancer.capabilities()
        assertEquals(listOf(EnhanceEngine.ML, EnhanceEngine.CLASSICAL), caps.engines)
        assertEquals(ShippedModel.ID, caps.model)
        for (engine in EnhanceEngine.entries) for (scale in listOf(2, 4)) {
            assertNotNull("$engine x$scale", caps.estimateMs(1920, 1080, scale, engine))
        }
        assertTrue(caps.maxInputPixels(4) >= 1920L * 1080 || context.getSystemService(android.app.ActivityManager::class.java).isLowRamDevice)
    }

    @Test
    fun mlEnhances1080pTo4xWithoutSeams() = runBlocking<Unit> {
        val src = TestMedia.gradient(1920, 1080)
        var last = 0f
        val out = enhancer.enhanceFrame(src, 4) { last = it }
        try {
            assertEquals(EnhanceEngine.ML, out.engine)
            assertEquals(7680, out.bitmap.width)
            assertEquals(4320, out.bitmap.height)
            assertEquals(1f, last)
            // Ramp gains ≈ 0.03 levels per output pixel; a tile seam would show as a jump of several levels.
            val h = maxStep(out.bitmap, horizontal = true)
            val v = maxStep(out.bitmap, horizontal = false)
            assertTrue("horizontal step $h", h <= 3)
            assertTrue("vertical step $v", v <= 3)
            assertTrue(!src.isRecycled)
        } finally {
            out.bitmap.recycle()
        }
    }

    @Test
    fun classicalEnhancesWithoutSeams() = runBlocking<Unit> {
        val out = enhancer.enhanceFrame(TestMedia.gradient(1920, 1080), 2, EnhanceEngine.CLASSICAL)
        try {
            assertEquals(EnhanceEngine.CLASSICAL, out.engine)
            assertNull(out.model)
            assertEquals(3840, out.bitmap.width)
            assertTrue(maxStep(out.bitmap, horizontal = true) <= 2)
            assertTrue(maxStep(out.bitmap, horizontal = false) <= 2)
        } finally {
            out.bitmap.recycle()
        }
    }

    @Test
    fun frameEnhancementIsCancellable() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val job = async(Dispatchers.Default) {
            enhancer.enhanceFrame(TestMedia.gradient(1920, 1080), 4) { started.complete(Unit) }
        }
        started.await()
        job.cancel()
        try {
            job.await().bitmap.recycle()
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun savedFrameHasReadableSidecar() = runBlocking<Unit> {
        val frame = enhancer.enhanceFrame(TestMedia.reference(320, 180), 2)
        val saved = saveEnhancedFrame(context, frame, 2, "media-1", 37_000)
        created += saved.file
        assertTrue(saved.file.length() > 0)
        val info = EnhancementInfo.read(saved.file)!!
        assertEquals(EnhancedKind.ENHANCED_FRAME, info.kind)
        assertEquals(frame.engine, info.engine)
        assertEquals("media-1", info.sourceMediaId)
        assertEquals(37_000L, info.sourcePositionMs)
        assertEquals(saved.info.createdAt, info.createdAt)
        assertTrue(EnhancementInfo.sidecarOf(saved.file).readText().contains("\"reconstructed\":true"))
        assertTrue(leftovers().isEmpty())
    }

    private fun clip(name: String, w: Int, h: Int, seconds: Double, audio: Boolean = true) =
        File(context.cacheDir, name).also { TestMedia.writeClip(it, w, h, 30, seconds, audio) }

    @Test
    fun clipUpscalesTo720pWithAudioAndSidecar() = runBlocking<Unit> {
        val input = clip("in-180p.mp4", 320, 180, 2.0)
        val progress = mutableListOf<UpscaleProgress>()
        val result = upscaler.upscale(UpscaleRequest(Uri.fromFile(input), Resolution.P1440, sourceMediaId = "clip-1"), 720) {
            progress += it
        }.awaitResult()
        val done = result as? UpscaleResult.Done ?: return@runBlocking fail("$result")
        created += done.output.file
        assertEquals(1280 to 720, done.width to done.height)
        assertTrue(done.audioCopied)
        assertEquals(4f, done.output.info.scale)
        assertEquals("clip-1", EnhancementInfo.read(done.output.file)!!.sourceMediaId)
        assertTrue(progress.filter { it.fraction < MIN_MEASURED_FRACTION }.all { it.etaMs == null && it.outputBytesEstimate == null })
        assertTrue(progress.any { it.etaMs != null && it.outputBytesEstimate != null })
        assertEquals(1f, progress.last().fraction)
        assertTrue(leftovers().isEmpty())

        val extractor = MediaExtractor().apply { setDataSource(done.output.file.path) }
        val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
        val video = formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        assertEquals(1280, formats[video].getInteger(MediaFormat.KEY_WIDTH))
        assertEquals(720, formats[video].getInteger(MediaFormat.KEY_HEIGHT))
        assertTrue(formats.any { it.getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") })
        extractor.release()
        val (frames, upright) = decode(done.output.file)
        assertTrue("decoded frames $frames", frames >= 55)
        assertTrue("orientation", upright)
        input.delete()
    }

    @Test
    fun clipWithoutAudioUsesMlEngine() = runBlocking<Unit> {
        val input = clip("in-ml.mp4", 320, 180, 0.4, audio = false)
        val result = upscaler.upscale(UpscaleRequest(Uri.fromFile(input), Resolution.P1440, "clip-ml", engine = EnhanceEngine.ML), 720) {}
            .awaitResult()
        val done = result as? UpscaleResult.Done ?: return@runBlocking fail("$result")
        created += done.output.file
        assertEquals(EnhanceEngine.ML, done.output.info.engine)
        assertEquals(ShippedModel.ID, done.output.info.model)
        assertTrue(!done.audioCopied)
        val (frames, upright) = decode(done.output.file)
        assertEquals(12, frames)
        assertTrue("orientation", upright)
        input.delete()
    }

    @Test
    fun targetBeyondEncoderLimitsFailsTyped() = runBlocking<Unit> {
        val input = clip("in-limit.mp4", 640, 360, 0.5)
        val result = upscaler.upscale(Uri.fromFile(input), Resolution.P2160, "clip-limit") {}.awaitResult()
        when (result) {
            is UpscaleResult.Done -> {
                created += result.output.file
                assertEquals(3840 to 2160, result.width to result.height)
            }
            is UpscaleResult.Failed -> assertTrue("${result.error}", result.error is UpscaleError.Encoder)
        }
        assertTrue(leftovers().isEmpty())
        input.delete()
    }

    @Test
    fun cancellingAClipLeavesNoTempFile() = runBlocking<Unit> {
        val input = clip("in-cancel.mp4", 640, 360, 6.0, audio = false)
        val before = enhanceDir(context).listFiles().orEmpty().toSet()
        val firstFrame = CompletableDeferred<Unit>()
        var last = 0f
        val job = upscaler.upscale(UpscaleRequest(Uri.fromFile(input), Resolution.P1440, "clip-cancel"), 1080) {
            last = it.fraction
            if (it.fraction > 0) firstFrame.complete(Unit)
        }
        firstFrame.await()
        val cancelledAt = SystemClock.elapsedRealtime()
        job.cancel()
        assertEquals(UpscaleResult.Failed(UpscaleError.Cancelled), job.awaitResult())
        job.join() // the pipeline has released codecs and files once the job is complete
        val stopMs = SystemClock.elapsedRealtime() - cancelledAt
        assertTrue("stopped after $stopMs ms", stopMs < 3_000) // at most one frame plus the 2.5 s frame wait
        assertTrue("progress $last", last < 1f) // 180 frames: cancelled long before the end
        assertEquals(before, enhanceDir(context).listFiles().orEmpty().toSet())
        input.delete()
    }

    @Test
    fun rotatedSourcesKeepCodedOrientationAndHint() = runBlocking<Unit> {
        val plain = clip("in-rot.mp4", 320, 180, 0.5, audio = false)
        for (rotation in listOf(90, 180)) {
            val rotated = File(context.cacheDir, "in-rot$rotation.mp4").also { remux(plain, it, rotation) }
            for (engine in EnhanceEngine.entries) {
                val result = upscaler.upscale(UpscaleRequest(Uri.fromFile(rotated), Resolution.P1440, "rot", engine = engine), 720) {}
                    .awaitResult()
                val done = result as? UpscaleResult.Done ?: return@runBlocking fail("$rotation $engine: $result")
                created += done.output.file
                val format = videoFormat(done.output.file)
                assertEquals("$rotation $engine", 1280 to 720, format.getInteger(MediaFormat.KEY_WIDTH) to format.getInteger(MediaFormat.KEY_HEIGHT))
                assertEquals("$rotation $engine", rotation, format.getInteger(MediaFormat.KEY_ROTATION))
                assertTrue("$rotation $engine orientation", decode(done.output.file).second)
            }
            rotated.delete()
        }
        plain.delete()
    }

    @Test
    fun hevcIsEncodedOrFailsTyped() = runBlocking<Unit> {
        val input = clip("in-hevc.mp4", 320, 180, 0.5)
        when (val result = upscaler.upscale(UpscaleRequest(Uri.fromFile(input), Resolution.P1440, "hevc", codec = VideoCodec.HEVC), 720) {}.awaitResult()) {
            is UpscaleResult.Done -> {
                created += result.output.file
                assertEquals(MediaFormat.MIMETYPE_VIDEO_HEVC, videoFormat(result.output.file).getString(MediaFormat.KEY_MIME))
            }
            // The emulator's HEVC encoder stops at 512×512.
            is UpscaleResult.Failed -> assertTrue("${result.error}", result.error is UpscaleError.Encoder)
        }
        assertTrue(leftovers().isEmpty())
        input.delete()
    }

    @Test
    fun singleFrameClipCompletes() = runBlocking<Unit> {
        val input = clip("in-one.mp4", 320, 180, 1.0 / 30, audio = false)
        val progress = mutableListOf<UpscaleProgress>()
        val result = upscaler.upscale(UpscaleRequest(Uri.fromFile(input), Resolution.P1440, "one"), 720) { progress += it }.awaitResult()
        val done = result as? UpscaleResult.Done ?: return@runBlocking fail("$result")
        created += done.output.file
        assertEquals(1, decode(done.output.file).first)
        assertEquals(1f, progress.last().fraction)
        input.delete()
    }

    @Test
    fun targetNotAboveSourceFailsTyped() = runBlocking<Unit> {
        val input = clip("in-same.mp4", 320, 180, 0.5)
        val result = upscaler.upscale(UpscaleRequest(Uri.fromFile(input), Resolution.P1440, "same"), 180) {}.awaitResult()
        assertTrue("$result", (result as UpscaleResult.Failed).error is UpscaleError.TargetNotLarger)
        assertTrue(leftovers().isEmpty())
        input.delete()
    }

    @Test
    fun frameAboveTheMemoryLimitFailsTyped() = runBlocking<Unit> {
        val caps = enhancer.capabilities()
        val side = kotlin.math.sqrt(caps.maxInputPixels(4).toDouble()).toInt() + 2
        val src = createBitmap(side, side)
        try {
            enhancer.enhanceFrame(src, 4).bitmap.recycle()
            fail("expected EnhanceException")
        } catch (e: EnhanceException) {
            assertEquals(EnhanceError.TooLarge, e.error)
        } finally {
            src.recycle()
        }
    }

    /** Copies all tracks unchanged into a new MP4 with a rotation hint (how phones store portrait/rotated video). */
    private fun remux(src: File, dst: File, rotation: Int) {
        val extractor = MediaExtractor().apply { setDataSource(src.path) }
        val muxer = MediaMuxer(dst.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply { setOrientationHint(rotation) }
        val tracks = (0 until extractor.trackCount).associateWith { muxer.addTrack(extractor.getTrackFormat(it)) }
        tracks.keys.forEach { extractor.selectTrack(it) }
        muxer.start()
        val buffer = ByteBuffer.allocate(1 shl 20)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val n = extractor.readSampleData(buffer, 0)
            if (n < 0) break
            val key = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
            info.set(0, n, extractor.sampleTime, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            muxer.writeSampleData(tracks.getValue(extractor.sampleTrackIndex), buffer, info)
            extractor.advance()
        }
        muxer.stop()
        muxer.release()
        extractor.release()
    }

    private fun videoFormat(file: File): MediaFormat {
        val extractor = MediaExtractor().apply { setDataSource(file.path) }
        val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        return extractor.getTrackFormat(track).also { extractor.release() }
    }

    @Test
    fun unreadableInputFailsAsDecoder() = runBlocking<Unit> {
        val junk = File(context.cacheDir, "junk.mp4").apply { writeText("not a video") }
        val result = upscaler.upscale(Uri.fromFile(junk), Resolution.P1440, "junk") {}.awaitResult()
        assertTrue("$result", (result as UpscaleResult.Failed).error is UpscaleError.Decoder)
        assertTrue(upscaler.estimate(UpscaleRequest(Uri.fromFile(junk), Resolution.P1440, "junk")).isFailure)
        junk.delete()
    }

    @Test
    fun estimateGivesSizeAlwaysAndTimeOnlyAfterAMeasuredRun() = runBlocking<Unit> {
        val input = clip("in-estimate.mp4", 320, 180, 1.0)
        val request = UpscaleRequest(Uri.fromFile(input), Resolution.P1440, "clip-estimate")
        val before = upscaler.estimate(request).getOrThrow()
        assertEquals(2560 to 1440, before.width to before.height)
        assertNull(before.etaMs)
        assertTrue(before.outputBytes > 0)
        val done = upscaler.upscale(request, 720) {}.awaitResult() as UpscaleResult.Done
        created += done.output.file
        assertNotNull(upscaler.estimate(request).getOrThrow().etaMs)
        input.delete()
    }

    @Test
    fun liveProbeDecides() = runBlocking<Unit> {
        val decision = LiveUpscaleProbe(enhancer).run()
        assertNotNull(decision.mlMs360p)
        if (decision.reason != LiveUpscaleDecision.Reason.API_TOO_OLD) assertNotNull(decision.classicalMs720p)
        assertEquals(decision.offer, decision.reason == LiveUpscaleDecision.Reason.OK)
    }

    /**
     * Decodes the video track; returns the frame count and whether frame 0 is upright and unmirrored: the synthetic
     * source has luma rising left→right and Cb rising top→bottom.
     */
    private fun decode(file: File): Pair<Int, Boolean> {
        val extractor = MediaExtractor().apply { setDataSource(file.path) }
        val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        val format = extractor.getTrackFormat(track)
        extractor.selectTrack(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var frames = 0
        var upright = false
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    if (info.size > 0 && frames++ == 0) {
                        val image = codec.getOutputImage(o)!!
                        fun at(plane: Int, x: Int, y: Int) = image.planes[plane].let {
                            it.buffer.get(y * it.rowStride + x * it.pixelStride).toInt() and 0xff
                        }
                        val w = image.width
                        val h = image.height
                        upright = at(0, w / 8, h / 2) < at(0, w * 7 / 8, h / 2) && at(1, w / 8, h / 16) < at(1, w / 8, h * 7 / 16)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return frames to upright
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
    }
}
