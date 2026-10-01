package me.ri3d.cam.enhance

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ConditionVariable
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.storage.StorageManager
import android.view.Surface
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.ri3d.cam.core.log.Log
import me.ri3d.cam.core.model.ExportQuality
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.math.roundToInt

/** Target height of the shorter side. P1080 serves 720p sources (e.g. a rear camera). */
enum class Resolution(val height: Int) { P1080(1080), P1440(1440), P2160(2160) }

/** The export-quality preference as an upscale target. */
val ExportQuality.resolution: Resolution
    get() = when (this) {
        ExportQuality.Q1080 -> Resolution.P1080
        ExportQuality.Q1440 -> Resolution.P1440
        ExportQuality.Q2160 -> Resolution.P2160
    }

enum class VideoCodec(val mime: String) { H264(MediaFormat.MIMETYPE_VIDEO_AVC), HEVC(MediaFormat.MIMETYPE_VIDEO_HEVC) }

/**
 * [sourceMediaId] is the original's `MediaItem.id` (required: outputs stay linked to their source).
 * [engine]: CLASSICAL = GPU sharpened-cubic (default); ML = model per frame on the CPU (very slow, see docs).
 */
data class UpscaleRequest(
    val input: Uri,
    val target: Resolution,
    val sourceMediaId: String,
    val engine: EnhanceEngine = EnhanceEngine.CLASSICAL,
    val codec: VideoCodec = VideoCodec.H264,
)

/** CONTRACTS §12: [etaMs] and [outputBytesEstimate] only once ≥ 5 % has been measured. */
data class UpscaleProgress(val fraction: Float, val etaMs: Long?, val outputBytesEstimate: Long?)

/** Typed failures for German UI messages; [detail] is technical and for diagnostics only. */
sealed interface UpscaleError {
    val detail: String?

    data class Decoder(override val detail: String?) : UpscaleError
    data class Encoder(override val detail: String?) : UpscaleError
    data class Storage(override val detail: String?) : UpscaleError

    /** The phone ran out of memory (large target, ML path). */
    data class Memory(override val detail: String?) : UpscaleError

    /** The target is not larger than the source: nothing to upscale. */
    data class TargetNotLarger(override val detail: String?) : UpscaleError

    data object Cancelled : UpscaleError {
        override val detail: String? = null
    }
}

sealed interface UpscaleResult {
    data class Done(val output: EnhancedOutput, val width: Int, val height: Int, val audioCopied: Boolean, val bytes: Long) :
        UpscaleResult

    data class Failed(val error: UpscaleError) : UpscaleResult
}

/** Before starting: [outputBytes] from the configured bitrates; [etaMs] only after a clip has been measured. */
data class UpscaleEstimate(val width: Int, val height: Int, val durationMs: Long, val etaMs: Long?, val outputBytes: Long)

/**
 * CONTRACTS §12. Outputs go to `filesDir/enhance/<uuid>.mp4` with a sidecar; the input is only read.
 * The returned [Deferred] is the cancellable job: either it completes with the result, or (cancelled) nothing of
 * the job stays on disk. `onProgress` is called on the job's pipeline thread, not the main thread.
 */
interface ClipUpscaler {
    /** Contract §12 form, with the source id added so the output stays linked. */
    fun upscale(input: Uri, target: Resolution, sourceMediaId: String, onProgress: (UpscaleProgress) -> Unit): Deferred<UpscaleResult> =
        upscale(UpscaleRequest(input, target, sourceMediaId), onProgress)

    fun upscale(request: UpscaleRequest, onProgress: (UpscaleProgress) -> Unit): Deferred<UpscaleResult>

    suspend fun estimate(request: UpscaleRequest): Result<UpscaleEstimate>
}

/** Awaits the job and maps its cancellation to [UpscaleError.Cancelled] (the awaiting coroutine's own cancellation still propagates). */
suspend fun Deferred<UpscaleResult>.awaitResult(): UpscaleResult = try {
    await()
} catch (e: CancellationException) {
    currentCoroutineContext().ensureActive()
    UpscaleResult.Failed(UpscaleError.Cancelled)
}

internal const val MIN_MEASURED_FRACTION = 0.05f
private const val AUDIO_BITS_PER_SECOND = 128_000L

internal fun progressOf(fraction: Float, elapsedMs: Long, bytesSoFar: Long): UpscaleProgress {
    val f = fraction.coerceIn(0f, 1f)
    if (f < MIN_MEASURED_FRACTION) return UpscaleProgress(f, null, null)
    return UpscaleProgress(f, (elapsedMs * (1 - f) / f).toLong(), (bytesSoFar / f).toLong())
}

/** Scales so the shorter side equals [target], keeping the aspect ratio; both sides even (encoder requirement). */
internal fun outputSize(srcW: Int, srcH: Int, targetHeight: Int): Pair<Int, Int> {
    val factor = targetHeight.toDouble() / minOf(srcW, srcH)
    fun even(v: Double) = ((v / 2).roundToInt() * 2)
    return even(srcW * factor) to even(srcH * factor)
}

/** ≈ 0.12 bit per pixel for H.264 (1440p30 ≈ 13 Mbit/s, 2160p30 ≈ 30 Mbit/s); HEVC gets 60 % of that. */
internal fun bitrateFor(w: Int, h: Int, fps: Int, codec: VideoCodec): Int {
    val bpp = if (codec == VideoCodec.HEVC) 0.072 else 0.12
    return (w.toLong() * h * fps * bpp).roundToInt()
}

internal fun estimateBytes(videoBitrate: Int, hasAudio: Boolean, durationUs: Long): Long =
    (videoBitrate + if (hasAudio) AUDIO_BITS_PER_SECOND else 0L) * durationUs / 8_000_000L

@Singleton
class DefaultClipUpscaler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val frameEnhancer: FrameEnhancer,
    @Named(ENHANCE_STORE) private val store: DataStore<Preferences>,
) : ClipUpscaler {
    // ponytail: process scope – survives leaving the screen, not process death; WorkManager + foreground service
    // in the UI phase if that matters.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex() // hardware codecs are scarce: one job at a time, later jobs wait

    override fun upscale(request: UpscaleRequest, onProgress: (UpscaleProgress) -> Unit): Deferred<UpscaleResult> =
        upscale(request, request.target.height, onProgress)

    /** Any even [targetHeight]; tests use 720p/1080p because emulator encoders stop at ≈ 2048×1024. */
    internal fun upscale(request: UpscaleRequest, targetHeight: Int, onProgress: (UpscaleProgress) -> Unit): Deferred<UpscaleResult> {
        val committed = AtomicReference<File?>()
        return scope.async {
            mutex.withLock {
                // EGL contexts are bound to a thread, so the whole pipeline runs on one.
                Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { thread ->
                    withContext(thread) { run(request, targetHeight, onProgress, committed) }
                }
            }
        }.apply {
            // Cancelled after the output was renamed into place: the caller never sees Done, so nothing may remain.
            invokeOnCompletion { cause ->
                if (cause != null) committed.get()?.let { it.delete(); EnhancementInfo.sidecarOf(it).delete() }
            }
        }
    }

    override suspend fun estimate(request: UpscaleRequest): Result<UpscaleEstimate> = withContext(Dispatchers.IO) {
        runCatching {
            val source = Source.open(context, request.input)
            try {
                source.requireLarger(request.target.height)
                val (w, h) = outputSize(source.width, source.height, request.target.height)
                val perMp = store.data.first()[clipCostKey(request.engine)]
                val frames = source.durationUs * source.fps / 1_000_000.0
                UpscaleEstimate(
                    width = w, height = h, durationMs = source.durationUs / 1000,
                    etaMs = perMp?.let { (it * frames * w * h / 1e6).toLong() },
                    outputBytes = estimateBytes(bitrateFor(w, h, source.fps, request.codec), source.audioTrack >= 0, source.durationUs),
                )
            } finally {
                source.release()
            }
        }
    }

    private suspend fun run(
        request: UpscaleRequest,
        targetHeight: Int,
        onProgress: (UpscaleProgress) -> Unit,
        committed: AtomicReference<File?>,
    ): UpscaleResult {
        val id = UUID.randomUUID().toString()
        val out = File(enhanceDir(context), "$id.mp4")
        val tmp = File(out.path + ".tmp")
        var done = false
        return try {
            val useMl = request.engine == EnhanceEngine.ML && EnhanceEngine.ML in frameEnhancer.capabilities().engines
            val stats = Transcode(context, request, targetHeight, tmp, if (useMl) frameEnhancer else null, onProgress).run()
            val info = EnhancementInfo(
                EnhancedKind.UPSCALED_CLIP, stats.engine, stats.model, stats.width.toFloat() / stats.sourceWidth,
                request.sourceMediaId, null, System.currentTimeMillis(),
            )
            store.edit { it[clipCostKey(stats.engine)] = stats.msPerOutputMegapixelFrame }
            writeSidecar(out, info)
            committed.set(out)
            moveAtomic(tmp, out)
            done = true
            UpscaleResult.Done(EnhancedOutput(id, out, info), stats.width, stats.height, stats.audioCopied, out.length())
        } catch (e: UpscaleFailure) {
            Log.w(TAG, "upscale failed: ${e.error}")
            UpscaleResult.Failed(e.error)
        } catch (e: IOException) {
            UpscaleResult.Failed(UpscaleError.Storage(e.javaClass.simpleName))
        } catch (e: CancellationException) {
            throw e
        } catch (e: EnhanceException) {
            UpscaleResult.Failed(if (e.error == EnhanceError.Memory) UpscaleError.Memory("ML frame") else UpscaleError.Encoder("${e.error}"))
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "upscale failed: out of memory")
            UpscaleResult.Failed(UpscaleError.Memory(null))
        } catch (e: Exception) {
            // Anything not attributed to a stage happened while producing frames (e.g. the ML path).
            Log.w(TAG, "upscale failed: ${e.javaClass.simpleName}")
            UpscaleResult.Failed(UpscaleError.Encoder(e.javaClass.simpleName))
        } finally {
            if (!done) {
                tmp.delete()
                EnhancementInfo.sidecarOf(out).delete()
            }
        }
    }

    private fun clipCostKey(engine: EnhanceEngine) =
        floatPreferencesKey("clip_ms_per_mp_frame_${engine.name.lowercase()}_${ShippedModel.ID}")

    private companion object {
        const val TAG = "Enhance"
    }
}

internal class UpscaleFailure(val error: UpscaleError) : Exception(error.toString())

private inline fun <T> stage(error: (String) -> UpscaleError, block: () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: UpscaleFailure) {
    throw e
} catch (e: Exception) {
    throw UpscaleFailure(error("${e.javaClass.simpleName}: ${e.message}"))
}

private inline fun <T> decoding(block: () -> T) = stage({ UpscaleError.Decoder(it) }, block)
private inline fun <T> encoding(block: () -> T) = stage({ UpscaleError.Encoder(it) }, block)
private inline fun <T> storing(block: () -> T) = stage({ UpscaleError.Storage(it) }, block)

/** Video/audio tracks of the input; the extractor is positioned on the video track. */
private class Source(val extractor: MediaExtractor, val videoTrack: Int, val audioTrack: Int) {
    val format: MediaFormat = extractor.getTrackFormat(videoTrack)
    val width = format.getInteger(MediaFormat.KEY_WIDTH)
    val height = format.getInteger(MediaFormat.KEY_HEIGHT)
    val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
    val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
    val fps: Int = when {
        !format.containsKey(MediaFormat.KEY_FRAME_RATE) -> 30
        else -> runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE) }
            .getOrElse { format.getFloat(MediaFormat.KEY_FRAME_RATE).roundToInt() }.coerceIn(1, 120)
    }

    fun release() = extractor.release()

    fun requireLarger(targetHeight: Int) {
        val shorter = minOf(width, height)
        if (targetHeight <= shorter) throw UpscaleFailure(UpscaleError.TargetNotLarger("source ${width}x$height, target ${targetHeight}p"))
    }

    companion object {
        fun open(context: Context, uri: Uri): Source = decoding {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                val tracks = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
                val video = tracks.indexOfFirst { it.startsWith("video/") }
                if (video < 0) throw UpscaleFailure(UpscaleError.Decoder("no video track"))
                extractor.selectTrack(video)
                Source(extractor, video, tracks.indexOfFirst { it.startsWith("audio/") })
            } catch (t: Throwable) {
                extractor.release()
                throw t
            }
        }
    }
}

private class Stats(
    val width: Int, val height: Int, val sourceWidth: Int, val engine: EnhanceEngine, val model: String?,
    val audioCopied: Boolean, val msPerOutputMegapixelFrame: Float,
)

/**
 * Extractor → decoder → OES texture → GL (cubic, or CPU ML per frame) → encoder input surface → muxer, with the
 * audio track copied unchanged and interleaved by timestamp. Synchronous MediaCodec loop on the calling thread.
 */
private class Transcode(
    private val context: Context,
    private val request: UpscaleRequest,
    private val targetHeight: Int,
    private val tmp: File,
    private val ml: FrameEnhancer?,
    private val onProgress: (UpscaleProgress) -> Unit,
) {
    private val info = MediaCodec.BufferInfo()
    private var muxer: MediaMuxer? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var bytes = 0L
    private var audio: MediaExtractor? = null
    private var audioBuffer: ByteBuffer? = null

    suspend fun run(): Stats {
        val source = Source.open(context, request.input)
        try {
            source.requireLarger(targetHeight)
        } catch (e: UpscaleFailure) {
            source.release()
            throw e
        }
        // Coded orientation throughout: sizes from the coded frame, rotation only as the MP4 orientation hint.
        val (outW, outH) = outputSize(source.width, source.height, targetHeight)
        val fps = source.fps
        val format = MediaFormat.createVideoFormat(request.codec.mime, outW, outH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val needed = estimateBytes(bitrateFor(outW, outH, fps, request.codec), source.audioTrack >= 0, source.durationUs)
        val dir = tmp.parentFile!!
        // Allocatable space counts cache the system may clear for us; plain usable space is the fallback.
        val free = runCatching {
            context.getSystemService(StorageManager::class.java).let { it.getAllocatableBytes(it.getUuidForPath(dir)) }
        }.getOrDefault(dir.usableSpace)
        if (free < needed + needed / 5 + FREE_SPACE_MARGIN) {
            source.release()
            throw UpscaleFailure(UpscaleError.Storage("not enough space for ≈$needed bytes"))
        }
        var encoder: MediaCodec? = null
        var decoder: MediaCodec? = null
        var gl: GlScaler? = null
        var texture: SurfaceTexture? = null
        var callbacks: HandlerThread? = null
        var inputSurface: Surface? = null
        var decoderSurface: Surface? = null
        try {
            val encoderName = encoding { MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) }
                ?: throw UpscaleFailure(UpscaleError.Encoder("no ${request.codec} encoder for ${outW}x$outH@$fps"))
            // Assigned before configure so a failing configure still releases the (scarce) codec in finally.
            encoder = encoding { MediaCodec.createByCodecName(encoderName) }
            encoding {
                val bitrate = bitrateFor(outW, outH, fps, request.codec)
                val range = encoder.codecInfo.getCapabilitiesForType(request.codec.mime).videoCapabilities?.bitrateRange
                format.setInteger(MediaFormat.KEY_BIT_RATE, range?.clamp(bitrate) ?: bitrate)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = encoder.createInputSurface()
                encoder.start()
            }
            gl = encoding { GlScaler(inputSurface!!) }
            val frameReady = ConditionVariable()
            callbacks = HandlerThread("enhance-frames").apply { start() }
            texture = SurfaceTexture(gl.oesTexture).apply {
                setOnFrameAvailableListener({ frameReady.open() }, Handler(callbacks.looper))
            }
            decoderSurface = Surface(texture)
            decoder = decoding { MediaCodec.createDecoderByType(source.format.getString(MediaFormat.KEY_MIME)!!) }
            decoding {
                // A surface decoder would apply rotation-degrees itself; the muxer hint below already carries it.
                source.format.setInteger(MediaFormat.KEY_ROTATION, 0)
                decoder.configure(source.format, decoderSurface, null, 0)
                decoder.start()
            }
            if (source.audioTrack >= 0) {
                audio = decoding {
                    MediaExtractor().apply {
                        setDataSource(context, request.input, null)
                        selectTrack(source.audioTrack)
                    }
                }
                val fmt = audio!!.getTrackFormat(source.audioTrack)
                val max = if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
                audioBuffer = ByteBuffer.allocateDirect(maxOf(max, 256 * 1024))
            }
            muxer = storing {
                MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply { setOrientationHint(source.rotation) }
            }

            val start = SystemClock.elapsedRealtime()
            val st = FloatArray(16)
            val mlScale = if (outH <= source.height * 2 && outW <= source.width * 2) 2 else 4
            var frames = 0
            var inputDone = false
            var decodeDone = false
            var encodeDone = false
            while (!encodeDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) decoding {
                    val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val size = source.extractor.readSampleData(decoder.getInputBuffer(index)!!, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, source.extractor.sampleTime, 0)
                            source.extractor.advance()
                        }
                    }
                }
                // Once the decoder is done only the encoder has work left: wait for it instead of spinning.
                encodeDone = drainEncoder(encoder, source, if (decodeDone) TIMEOUT_US else 0)
                if (decodeDone) continue
                val index = decoding { decoder.dequeueOutputBuffer(info, TIMEOUT_US) }
                if (index < 0) continue
                val pts = info.presentationTimeUs
                val render = info.size > 0
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                decoding { decoder.releaseOutputBuffer(index, render) }
                if (render) {
                    if (!frameReady.block(FRAME_TIMEOUT_MS)) throw UpscaleFailure(UpscaleError.Decoder("frame timeout"))
                    frameReady.close()
                    encoding {
                        texture.updateTexImage()
                        texture.getTransformMatrix(st)
                    }
                    if (ml != null) {
                        val frame = encoding { gl.readFrame(st, source.width, source.height) }
                        val enhanced = try {
                            ml.enhanceFrame(frame, mlScale, EnhanceEngine.ML)
                        } finally {
                            frame.recycle()
                        }
                        if (enhanced.engine != EnhanceEngine.ML) {
                            // Never mix engines within one clip: the sidecar names exactly one.
                            enhanced.bitmap.recycle()
                            throw UpscaleFailure(UpscaleError.Encoder("ML engine failed on a frame"))
                        }
                        try {
                            encoding { gl.drawBitmap(enhanced.bitmap, outW, outH) }
                        } finally {
                            enhanced.bitmap.recycle()
                        }
                    } else {
                        encoding { gl.drawCubic(st, source.width, source.height, outW, outH) }
                    }
                    encoding { gl.swap(pts * 1000) }
                    frames++
                    val fraction = if (source.durationUs > 0) pts.toFloat() / source.durationUs else 0f
                    onProgress(progressOf(fraction, SystemClock.elapsedRealtime() - start, bytes))
                }
                if (eos) {
                    encoding { encoder.signalEndOfInputStream() }
                    decodeDone = true
                }
            }
            copyAudio(Long.MAX_VALUE)
            storing { muxer!!.stop() }
            onProgress(UpscaleProgress(1f, 0, bytes))
            val elapsed = SystemClock.elapsedRealtime() - start
            return Stats(
                width = outW, height = outH, sourceWidth = source.width,
                engine = if (ml != null) EnhanceEngine.ML else EnhanceEngine.CLASSICAL,
                model = ShippedModel.ID.takeIf { ml != null },
                audioCopied = audioTrack >= 0,
                msPerOutputMegapixelFrame = elapsed / (maxOf(frames, 1) * outW * outH / 1e6f),
            )
        } finally {
            runCatching { decoder?.stop() }
            decoder?.release()
            runCatching { encoder?.stop() }
            encoder?.release()
            decoderSurface?.release()
            texture?.release()
            callbacks?.quitSafely()
            runCatching { gl?.close() }
            inputSurface?.release()
            runCatching { muxer?.release() } // stops a started muxer, which throws without samples
            audio?.release()
            source.release()
        }
    }

    /** Writes all available encoder output; returns true at end of stream. */
    private fun drainEncoder(encoder: MediaCodec, source: Source, timeoutUs: Long): Boolean {
        while (true) {
            val index = encoding { encoder.dequeueOutputBuffer(info, timeoutUs) }
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> storing {
                    val mux = muxer!!
                    videoTrack = mux.addTrack(encoder.outputFormat)
                    audioTrack = audio?.let {
                        // Formats MP4 cannot carry (e.g. some PCM variants) are dropped and reported, not fatal.
                        runCatching { mux.addTrack(it.getTrackFormat(source.audioTrack)) }.getOrDefault(-1)
                    } ?: -1
                    mux.start()
                }
                index >= 0 -> {
                    val buffer = encoding { encoder.getOutputBuffer(index)!! }
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0) {
                        val pts = info.presentationTimeUs
                        storing { muxer!!.writeSampleData(videoTrack, buffer, info) }
                        bytes += info.size
                        copyAudio(pts)
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoding { encoder.releaseOutputBuffer(index, false) }
                    if (eos) return true
                }
                else -> return false
            }
        }
    }

    /** Copies audio samples up to [untilUs] so the muxer can interleave without buffering a whole track. */
    private fun copyAudio(untilUs: Long) {
        val extractor = audio ?: return
        if (audioTrack < 0) return
        val buffer = audioBuffer!!
        val sample = MediaCodec.BufferInfo()
        storing {
            while (true) {
                val time = extractor.sampleTime
                if (time < 0 || time > untilUs) break
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val key = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                sample.set(0, size, time, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer!!.writeSampleData(audioTrack, buffer, sample)
                bytes += size
                extractor.advance()
            }
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val FRAME_TIMEOUT_MS = 2_500L
        const val FREE_SPACE_MARGIN = 50L * 1024 * 1024
    }
}
