package me.ri3d.cam.plates

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import me.ri3d.cam.core.data.AppDatabase
import java.io.File
import javax.inject.Provider

/** ClipPlateScanner on a 3 s H.264 clip generated here (MediaCodec + MediaMuxer) with two rendered plates. */
@RunWith(AndroidJUnit4::class)
class ClipPlateScannerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val repository = PlateRepository(context, db.plateDao())
    private val scanner = ClipPlateScanner(context, repository, Provider<PlateRecognizer> { MlKitPlateRecognizer() })
    private val clip = File(context.cacheDir, "plates-test-clip.mp4")

    @After
    fun tearDown() = runBlocking {
        repository.clear()
        db.close()
        clip.delete()
        Unit
    }

    @Test
    fun scansClipAndLinksSightingsToPositions() = runBlocking {
        writeClip(clip)
        val progress = mutableListOf<ScanProgress>()
        val summary = scanner.scan(Uri.fromFile(clip), "clip-1", fps = 2) { progress += it }
        Log.i("PlateEval", "clip scan: $summary")
        progress.forEach { p -> Log.i("PlateEval", "clip ${p.positionMs}: ${p.detections.joinToString { "${it.text} [${it.format}, ${it.confidence}]" }}") }

        assertEquals(6, summary.framesScanned)
        assertEquals(listOf(0L, 500L, 1000L, 1500L, 2000L, 2500L, 3000L), progress.map { it.positionMs })
        assertEquals(1f, progress.last().fraction)
        assertTrue(summary.plates.containsAll(setOf("BMK4821", "HHJK553")))
        val detections = progress.flatMap { it.detections }
        assertTrue(detections.all { it.frameTimestampMs in 0..2500 })
        // Any other reading is one with '?' (e.g. the seal read as an unsure letter: "HH? JK 553", key HHSJK553).
        assertTrue(detections.filter { it.normalized !in setOf("BMK4821", "HHJK553") }.all { '?' in it.text && it.confidence == null })

        val rows = db.plateDao().sightingsForMedia("clip-1")
        assertEquals(1, rows.count { it.normalized == "BMK4821" }) // one continuous sighting per reading
        assertEquals(1, rows.count { it.normalized == "HHJK553" })
        val bmk = rows.single { it.normalized == "BMK4821" }
        val hh = rows.single { it.normalized == "HHJK553" }
        assertTrue(bmk.positionMs!! in 0L..1000L)
        assertTrue(hh.positionMs!! in 1500L..2500L)
        // plate drawn around x 640 ± 190, y 446 ± 40 in the 1280×720 clip
        assertTrue("box $bmk", bmk.boxLeft in 400f..700f && bmk.boxRight in 600f..900f && bmk.boxTop in 380f..480f)

        val plate = repository.history().first().single { it.normalized == "BMK4821" }
        val sighting = repository.plate(plate.id).first()!!.sightings.single()
        assertEquals(SightingSource.CLIP, sighting.source)
        assertTrue(File(context.filesDir, sighting.cropPath!!).length() > 0)

        // Scanning again adds nothing.
        assertEquals(0, scanner.scan(Uri.fromFile(clip), "clip-1", fps = 2).sightingsWritten)
    }

    @Test
    fun scanIsCancellable() = runBlocking {
        writeClip(clip)
        var frames = 0
        try {
            coroutineScope {
                val scope = this
                scanner.scan(Uri.fromFile(clip), "clip-2", fps = 2) { if (++frames == 2) scope.coroutineContext.job.cancel() }
            }
            fail("scan was not cancelled")
        } catch (_: CancellationException) {
        }
        assertEquals(2, frames)
    }

    /** 1280×720, 10 fps, 3 s: "B-MK 4821" for 0–1.4 s, "HH-JK 553" for 1.5–2.9 s. */
    private fun writeClip(file: File, width: Int = 1280, height: Int = 720, fps: Int = 10) {
        val a = SyntheticPlates.scene(SyntheticPlates.Spec(listOf("B", "MK", "4821")), SyntheticPlates.Look(plateWidth = 560))
        val b = SyntheticPlates.scene(SyntheticPlates.Spec(listOf("HH", "JK", "553")), SyntheticPlates.Look(plateWidth = 560))
        val frames = listOf(a, b).map { Bitmap.createScaledBitmap(it, width, height, true) }
        val count = fps * 3

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val info = MediaCodec.BufferInfo()
        var track = -1
        var queued = 0
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val pts = queued * 1_000_000L / fps
                        if (queued == count) {
                            codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            fillYuv(codec.getInputImage(i)!!, frames[if (queued < count / 2) 0 else 1])
                            codec.queueInputBuffer(i, 0, width * height * 3 / 2, pts, 0)
                            queued++
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                } else if (o >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0) muxer.writeSampleData(track, codec.getOutputBuffer(o)!!, info)
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            codec.stop()
            codec.release()
            muxer.stop()
            muxer.release()
        }
    }

    /** ARGB → YUV 4:2:0 (BT.601 limited range) into whatever plane layout the encoder hands out. */
    private fun fillYuv(image: Image, bitmap: Bitmap) {
        val w = image.width
        val h = image.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val (yp, up, vp) = image.planes
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = px[y * w + x]
                val r = c shr 16 and 0xff
                val g = c shr 8 and 0xff
                val b = c and 0xff
                yp.buffer.put(y * yp.rowStride + x * yp.pixelStride, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())
                if (x % 2 == 0 && y % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    up.buffer.put(y / 2 * up.rowStride + x / 2 * up.pixelStride, u.toByte())
                    vp.buffer.put(y / 2 * vp.rowStride + x / 2 * vp.pixelStride, v.toByte())
                }
            }
        }
    }
}
