package to.axolotl.cam.enhance

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.random.Random

/** Synthetic test media: no real recordings exist in the repo, so references are drawn. */
internal object TestMedia {

    /** Dashcam-like scene with plate text, thin lines, a zone pattern and texture; deterministic. */
    fun reference(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        val c = Canvas(this)
        val s = w / 1024f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.55f, Color.rgb(120, 160, 210), Color.rgb(220, 225, 230), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h * 0.55f, p)
        p.shader = null
        p.color = Color.rgb(70, 72, 75)
        c.drawRect(0f, h * 0.55f, w.toFloat(), h.toFloat(), p)
        p.color = Color.WHITE
        for (i in 0 until 8) c.drawRect((i * 140 + 20) * s, h * 0.8f, (i * 140 + 90) * s, h * 0.8f + 6 * s, p)
        p.color = Color.rgb(150, 30, 35)
        c.drawRoundRect(RectF(80 * s, 230 * s, 520 * s, 470 * s), 30 * s, 30 * s, p)
        p.color = Color.rgb(30, 30, 30)
        c.drawRoundRect(RectF(120 * s, 260 * s, 480 * s, 330 * s), 10 * s, 10 * s, p)
        p.color = Color.WHITE
        c.drawRect(170 * s, 385 * s, 430 * s, 440 * s, p)
        p.color = Color.BLACK
        p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        p.textSize = 44 * s
        c.drawText("B-MK 4821", 178 * s, 430 * s, p)
        p.textSize = 22 * s
        c.drawText("P-RS 1193  FORTHING", 600 * s, 120 * s, p)
        p.textSize = 13 * s
        c.drawText("2026-09-28 17:42:08  48 km/h", 600 * s, 160 * s, p)
        p.strokeWidth = 1.5f * s
        for (i in 0 until 12) c.drawLine((600 + i * 30) * s, 200 * s, (640 + i * 34) * s, 360 * s, p)
        p.style = Paint.Style.STROKE
        for (r in 1..14) c.drawCircle(820 * s, 440 * s, r * 7 * s, p)
        p.style = Paint.Style.FILL
        val rnd = Random(42)
        for (y in 0 until (90 * s).toInt()) for (x in 0 until (200 * s).toInt()) {
            val v = 90 + rnd.nextInt(60)
            setPixel(((560 * s).toInt() + x).coerceAtMost(w - 1), ((470 * s).toInt() + y).coerceAtMost(h - 1), Color.rgb(v, v, v + 10))
        }
    }

    /** Horizontal and vertical luma ramps: any tile seam shows up as a step. */
    fun gradient(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        val row = IntArray(w)
        for (y in 0 until h) {
            for (x in 0 until w) row[x] = Color.rgb(x * 255 / (w - 1), y * 255 / (h - 1), 128)
            setPixels(row, 0, w, 0, y, w, 1)
        }
    }

    /** Area-average downscale by an integer factor (the "camera" that produced the low-res input). */
    fun boxDown(src: Bitmap, f: Int): Bitmap {
        val w = src.width / f
        val h = src.height / f
        val inPx = IntArray(src.width * src.height).also { src.getPixels(it, 0, src.width, 0, 0, src.width, src.height) }
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0
            var g = 0
            var b = 0
            for (dy in 0 until f) for (dx in 0 until f) {
                val p = inPx[(y * f + dy) * src.width + x * f + dx]
                r += p shr 16 and 0xff
                g += p shr 8 and 0xff
                b += p and 0xff
            }
            val n = f * f
            out[y * w + x] = argb(r / n, g / n, b / n)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    fun luma(b: Bitmap): DoubleArray {
        val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        return DoubleArray(px.size) { 0.299 * (px[it] shr 16 and 0xff) + 0.587 * (px[it] shr 8 and 0xff) + 0.114 * (px[it] and 0xff) }
    }

    /** PSNR of the luma channel, [border] pixels ignored. */
    fun psnr(a: Bitmap, b: Bitmap, border: Int): Double {
        val ya = luma(a)
        val yb = luma(b)
        var sum = 0.0
        var n = 0
        for (y in border until a.height - border) for (x in border until a.width - border) {
            val d = ya[y * a.width + x] - yb[y * a.width + x]
            sum += d * d
            n++
        }
        return 10 * log10(255.0 * 255.0 / (sum / n))
    }

    /** Mean SSIM of the luma channel over 8×8 windows (stride 4), [border] pixels ignored. */
    fun ssim(a: Bitmap, b: Bitmap, border: Int): Double {
        val ya = luma(a)
        val yb = luma(b)
        val c1 = (0.01 * 255) * (0.01 * 255)
        val c2 = (0.03 * 255) * (0.03 * 255)
        var total = 0.0
        var count = 0
        var y0 = border
        while (y0 + 8 <= a.height - border) {
            var x0 = border
            while (x0 + 8 <= a.width - border) {
                var ma = 0.0
                var mb = 0.0
                for (y in y0 until y0 + 8) for (x in x0 until x0 + 8) {
                    ma += ya[y * a.width + x]
                    mb += yb[y * a.width + x]
                }
                ma /= 64
                mb /= 64
                var va = 0.0
                var vb = 0.0
                var cov = 0.0
                for (y in y0 until y0 + 8) for (x in x0 until x0 + 8) {
                    val da = ya[y * a.width + x] - ma
                    val db = yb[y * a.width + x] - mb
                    va += da * da
                    vb += db * db
                    cov += da * db
                }
                va /= 63
                vb /= 63
                cov /= 63
                total += ((2 * ma * mb + c1) * (2 * cov + c2)) / ((ma * ma + mb * mb + c1) * (va + vb + c2))
                count++
                x0 += 4
            }
            y0 += 4
        }
        return total / count
    }

    /** Upscales a whole bitmap with any tile engine (as the FrameEnhancer does). */
    suspend fun upscale(src: Bitmap, scale: Int, tile: Int, overlap: Int, engine: TileUpscaler): Bitmap {
        val out = Bitmap.createBitmap(src.width * scale, src.height * scale, Bitmap.Config.ARGB_8888)
        upscaleTiled(BitmapPixels(src), BitmapPixels(out), scale, tile, overlap, engine) {}
        return out
    }

    private class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)

    /**
     * Writes an H.264 MP4 (moving ramps, YUV input with exact timestamps) and optionally an AAC 440 Hz tone,
     * using the device's encoders and MediaMuxer.
     */
    fun writeClip(file: File, w: Int, h: Int, fps: Int, seconds: Double, audio: Boolean) {
        val frames = (fps * seconds).toInt()
        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val video = encode(videoFormat, frames) { codec, index, i ->
            val image = codec.getInputImage(index)!!
            image.planes.forEachIndexed { plane, p ->
                val pw = if (plane == 0) w else w / 2
                val ph = if (plane == 0) h else h / 2
                for (y in 0 until ph) for (x in 0 until pw) {
                    val v = if (plane == 0) (x * 255 / pw + i * 4) and 0xff else 128 + (if (plane == 1) y else -y) * 64 / ph
                    p.buffer.put(y * p.rowStride + x * p.pixelStride, v.toByte())
                }
            }
            w * h * 3 / 2 to i * 1_000_000L / fps
        }
        val sampleRate = 44_100
        val chunk = 1024
        val audioTrack = if (!audio) null else {
            val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            }
            encode(audioFormat, (sampleRate * seconds / chunk).toInt()) { codec, index, i ->
                val buffer = codec.getInputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                buffer.clear()
                for (n in 0 until chunk) {
                    val t = (i * chunk + n).toDouble() / sampleRate
                    buffer.putShort((sin(2 * PI * 440 * t) * 8000).toInt().toShort())
                }
                chunk * 2 to i * chunk * 1_000_000L / sampleRate
            }
        }
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val vt = muxer.addTrack(video.first)
        val at = audioTrack?.let { muxer.addTrack(it.first) }
        muxer.start()
        val info = MediaCodec.BufferInfo()
        fun write(track: Int, s: Sample) {
            info.set(0, s.data.size, s.ptsUs, s.flags)
            muxer.writeSampleData(track, ByteBuffer.wrap(s.data), info)
        }
        video.second.forEach { write(vt, it) }
        if (at != null) audioTrack.second.forEach { write(at, it) }
        muxer.stop()
        muxer.release()
    }

    private fun encode(
        format: MediaFormat,
        inputs: Int,
        fill: (MediaCodec, Int, Int) -> Pair<Int, Long>,
    ): Pair<MediaFormat, List<Sample>> {
        val codec = MediaCodec.createEncoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val samples = mutableListOf<Sample>()
        var outFormat: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        var fed = 0
        var eosQueued = false
        try {
            while (true) {
                if (!eosQueued) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        if (fed == inputs) {
                            codec.queueInputBuffer(index, 0, 0, fed.toLong() * 33_333, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosQueued = true
                        } else {
                            val (size, pts) = fill(codec, index, fed++)
                            codec.queueInputBuffer(index, 0, size, pts, 0)
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 10_000)
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outFormat = codec.outputFormat
                if (out >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val buffer = codec.getOutputBuffer(out)!!
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(bytes)
                        samples += Sample(bytes, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }
        return outFormat!! to samples
    }
}
