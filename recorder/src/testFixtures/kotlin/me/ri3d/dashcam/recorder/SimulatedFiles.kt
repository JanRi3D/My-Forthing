package me.ri3d.dashcam.recorder

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO

/**
 * Test fixture: a fictional SD card for 4100 (list), 4101 (delete) and the media HTTP server
 * ([SimulatorHttpServer]). Paths, names and times are invented; the real recorder's layout is not established.
 *
 * Simulated paging: newest first per type, `lastFileName` is exclusive (the page starts after that entry), an unknown
 * cursor yields an empty page. Pages hold at most [maxPage] entries (default [RECORDER_PAGE] = 20: the physical
 * recorder answers `pageNum` 50 with 20 entries, 2026-10-02) and, like the recorder, no `totalFileSize`. Order and
 * cursor inclusivity of the real recorder are not established. Videos are the committed 3 s clip `sim/clip.mp4` padded with an MP4 `free` box to
 * [Entry.size]; JPEGs (thumbnails and photos) are drawn at runtime. Thumbnails are `<name>.thm` like on the physical
 * recorder (2026-10-02: `/sd/DCIM/ch1_20261002_091128_0782.thm` next to the `.mp4`).
 */
class SimulatedFiles(entries: List<Entry> = defaults(), private val maxPage: Int = RECORDER_PAGE) {
    data class Entry(val type: Int, val fileName: String, val fileThm: String, val fileTime: String, val size: Long)

    private val entries = CopyOnWriteArrayList(entries)
    private val images = ConcurrentHashMap<String, ByteArray>()

    fun entries(type: Int): List<Entry> = entries.filter { it.type == type }

    /** 4100 reply for [request] (`type`, `lastFileName`, `pageNum`). */
    fun listReply(request: RecorderReply): String {
        val p = request.param as? JsonObject
        val type = p?.get("type")?.jsonPrimitive?.int ?: 0
        val cursor = p?.get("lastFileName")?.jsonPrimitive?.content.orEmpty()
        val pageNum = p?.get("pageNum")?.jsonPrimitive?.int ?: 50
        val all = entries(type)
        val start = if (cursor.isEmpty()) 0 else all.indexOfFirst { it.fileName == cursor }.let { if (it < 0) all.size else it + 1 }
        return listReply(all, all.drop(start).take(minOf(pageNum, maxPage)))
    }

    /** 4101: deletes the listed paths; rval 107 ("no file") if none of them exists. */
    fun deleteReply(request: RecorderReply): String {
        val paths = (request.param as? JsonObject)?.get("fileList")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        val removed = entries.filter { it.fileName in paths }
        entries.removeAll(removed.toSet())
        return """{"msgId":4101,"rval":${if (removed.isEmpty()) 107 else 0}}"""
    }

    /** Bytes served for [path] (a file or a thumbnail), null if the card has no such file. */
    fun content(path: String): ByteArray? {
        entries.firstOrNull { it.fileName == path }?.let { e ->
            return if (e.fileName.endsWith(".mp4")) paddedClip(e.size) else images.getOrPut(path) { jpeg(1280, 720, e.fileTime) }
        }
        val e = entries.firstOrNull { it.fileThm == path } ?: return null
        return images.getOrPut(path) { jpeg(320, 180, e.fileTime) }
    }

    companion object {
        /** Entries per 4100 page of the physical recorder (asked for 50). */
        const val RECORDER_PAGE = 20

        private val NAME_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        private val FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        private val clip: ByteArray by lazy {
            SimulatedFiles::class.java.getResourceAsStream("/sim/clip.mp4")!!.use { it.readBytes() }
        }

        fun listReply(all: List<Entry>, page: List<Entry>): String = buildJsonObject {
            put("msgId", 4100)
            put("rval", 0)
            putJsonObject("param") {
                put("totalFileNum", all.size) // no totalFileSize: the physical recorder sends none
                putJsonArray("fileList") {
                    page.forEach { addJsonObject { put("fileName", it.fileName); put("fileThm", it.fileThm); put("fileTime", it.fileTime) } }
                }
            }
        }.toString()

        /** 120 loop clips (6 pages of 20, across midnight), 3 incidents, 12 photos; newest first. */
        fun defaults(newest: LocalDateTime = LocalDateTime.of(2026, 10, 1, 1, 0, 0)): List<Entry> =
            series(0, "normal", "N", ".mp4", 120, newest, 60, 6L shl 20) +
                series(1, "event", "E", ".mp4", 3, newest.minusMinutes(7), 1500, 3L shl 20) +
                series(2, "photo", "P", ".jpg", 12, newest.minusMinutes(3), 600, 0)

        fun series(type: Int, dir: String, prefix: String, ext: String, count: Int, newest: LocalDateTime, stepSeconds: Long, size: Long) =
            (0 until count).map { i ->
                val time = newest.minusSeconds(i * stepSeconds)
                val base = "/sim/$dir/$prefix${NAME_TIME.format(time)}"
                Entry(type, base + ext, "$base.thm", FILE_TIME.format(time), size) // `.thm` (a JPEG) as on the real recorder
            }

        /** The real clip followed by a `free` box: still a valid MP4 of [size] bytes. */
        fun paddedClip(size: Long): ByteArray {
            val pad = (size - clip.size).toInt()
            if (pad < 8) return clip
            val out = ByteArray(clip.size + pad)
            System.arraycopy(clip, 0, out, 0, clip.size)
            val box = clip.size
            out[box] = (pad ushr 24).toByte(); out[box + 1] = (pad ushr 16).toByte(); out[box + 2] = (pad ushr 8).toByte(); out[box + 3] = pad.toByte()
            "free".toByteArray().copyInto(out, box + 4)
            return out
        }

        fun jpeg(width: Int, height: Int, label: String): ByteArray {
            System.setProperty("java.awt.headless", "true")
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            g.paint = GradientPaint(0f, 0f, Color(0x26, 0x47, 0x77), width.toFloat(), height.toFloat(), Color(0x8f, 0xd9, 0x9a))
            g.fillRect(0, 0, width, height)
            g.color = Color.WHITE
            g.font = Font(Font.SANS_SERIF, Font.BOLD, height / 8)
            g.drawString("SIM", width / 16, height / 4)
            g.drawString(label, width / 16, height * 3 / 4)
            g.dispose()
            return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
        }
    }
}
