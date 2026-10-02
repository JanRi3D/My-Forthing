package me.ri3d.dashcam.recorder

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** The fixtures behind the app's simulator mode: 4100 paging, 4101 delete, media HTTP with ranges. */
class SimulatedMediaTest {
    private val files = SimulatedFiles()

    private fun page(type: Int, cursor: String, pageNum: Int = 50): FileList =
        parseFileList(RecorderReply.parse(files.listReply(request(RecorderCommand.ListFiles(type, cursor, pageNum))))!!)

    private fun request(cmd: RecorderCommand) = RecorderReply.parse("""{"msgId":${cmd.msgId},"token":1,"param":${cmd.param}}""")!!

    @Test
    fun `pages follow the exclusive cursor, hold 20 entries when 50 are asked, and carry no totalFileSize`() {
        val pages = generateSequence(page(0, "")) { prev -> prev.fileList.lastOrNull()?.let { page(0, it.fileName!!) } }
            .takeWhile { it.fileList.isNotEmpty() }.toList()
        assertThat(pages.map { it.fileList.size }).containsExactly(20, 20, 20, 20, 20, 20).inOrder() // as on the recorder
        assertThat(pages[1].fileList.first().fileName).isNotEqualTo(pages[0].fileList.last().fileName)
        assertThat(pages.flatMap { it.fileList }.map { it.fileName }.distinct()).hasSize(120)
        assertThat(pages[0].totalFileNum).isEqualTo(120)
        assertThat(pages[0].totalFileSize).isNull()
        assertThat(page(0, "/unknown").fileList).isEmpty()
        assertThat(parseFileList(RecorderReply.parse(SimulatedFiles(maxPage = 50).listReply(request(RecorderCommand.ListFiles(0, "", 50))))!!).fileList)
            .hasSize(50)
    }

    @Test
    fun `delete removes listed files and answers 107 for unknown paths`() {
        val path = files.entries(1).first().fileName
        val ok = RecorderReply.parse(files.deleteReply(request(RecorderCommand.DeleteFiles(listOf(path)))))!!
        val again = RecorderReply.parse(files.deleteReply(request(RecorderCommand.DeleteFiles(listOf(path)))))!!
        assertThat(ok.rval).isEqualTo(0)
        assertThat(again.rval).isEqualTo(107)
        assertThat(files.entries(1).map { it.fileName }).doesNotContain(path)
    }

    @Test
    fun `http serves full files, ranges and 416`() {
        SimulatorHttpServer(files).use { server ->
            val clip = files.entries(0).first()
            val base = "http://127.0.0.1:${server.port}"

            val full = URL(base + clip.fileName).openConnection() as HttpURLConnection
            assertThat(full.responseCode).isEqualTo(200)
            val bytes = full.inputStream.use { it.readBytes() }
            assertThat(bytes.size.toLong()).isEqualTo(clip.size)
            assertThat(String(bytes, 4, 4)).isEqualTo("ftyp")

            val tail = (URL(base + clip.fileName).openConnection() as HttpURLConnection).apply { setRequestProperty("Range", "bytes=1000-") }
            assertThat(tail.responseCode).isEqualTo(206)
            assertThat(tail.getHeaderField("Content-Range")).isEqualTo("bytes 1000-${clip.size - 1}/${clip.size}")
            assertThat(tail.inputStream.use { it.readBytes() }).isEqualTo(bytes.copyOfRange(1000, bytes.size))

            val past = (URL(base + clip.fileName).openConnection() as HttpURLConnection).apply { setRequestProperty("Range", "bytes=${clip.size}-") }
            assertThat(past.responseCode).isEqualTo(416)

            assertThat(clip.fileThm).endsWith(".thm") // as on the real recorder; a JPEG by content
            val thumb = URL(base + clip.fileThm).openConnection() as HttpURLConnection
            assertThat(thumb.contentType).isEqualTo("application/octet-stream")
            assertThat(thumb.inputStream.use { it.readBytes() }.take(2)).containsExactly(0xFF.toByte(), 0xD8.toByte()).inOrder()

            assertThat((URL("$base/nope.mp4").openConnection() as HttpURLConnection).responseCode).isEqualTo(404)
        }
    }
}
