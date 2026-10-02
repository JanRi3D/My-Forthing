package me.ri3d.dashcam.live

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.SocketFactory
import kotlin.concurrent.thread

/** [RtspSdpProxy] and [rtspDescribe] against a fake RTSP server shaped like the recorder (SDP without `a=control`). */
class RtspSdpProxyTest {
    /** Video and audio, neither with a control attribute (the shape that made Media3 fail on hardware). */
    private val recorderSdp = listOf(
        "v=0", "o=- 1 1 IN IP4 192.168.42.1", "s=Live", "c=IN IP4 0.0.0.0", "t=0 0",
        "m=video 0 RTP/AVP 96", "a=rtpmap:96 H264/90000", "a=fmtp:96 packetization-mode=1;profile-level-id=64001F;sprop-parameter-sets=Z2QAH6zZQPARabIAAAMACAAAAwGcHjBjLA==,aOvjyyLA",
        "m=audio 0 RTP/AVP 8", "a=rtpmap:8 PCMA/8000",
    ).joinToString("\r\n", postfix = "\r\n")

    private val frame = byteArrayOf('$'.code.toByte(), 0, 0, 4, 1, 2, 3, 4)
    private val closeables = mutableListOf<AutoCloseable>()

    @After
    fun tearDown() = closeables.forEach { it.close() }

    /** Answers OPTIONS, DESCRIBE ([sdp], [contentBase]), SETUP, PLAY (then one interleaved frame), GET_PARAMETER. */
    private inner class FakeRecorder(private val sdp: String, private val contentBase: String?) : AutoCloseable {
        val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val url = "rtsp://127.0.0.1:${server.localPort}/ch1/sub"

        init {
            closeables += this
            thread(isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        val input = BufferedInputStream(socket.getInputStream())
                        val out = socket.getOutputStream()
                        while (true) {
                            val request = readRtspMessage(input) ?: break
                            requests += request.startLine
                            val cseq = "CSeq: ${request.header("CSeq")}"
                            val method = request.startLine.substringBefore(' ')
                            val response = when (method) {
                                "OPTIONS" -> RtspMessage("RTSP/1.0 200 OK", listOf(cseq, "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER"))
                                "DESCRIBE" -> sdp.toByteArray().let { body ->
                                    RtspMessage(
                                        "RTSP/1.0 200 OK",
                                        listOfNotNull(cseq, contentBase?.let { "Content-Base: $it" }, "Content-Type: application/sdp", "Content-Length: ${body.size}"),
                                        body,
                                    )
                                }
                                "SETUP" -> RtspMessage("RTSP/1.0 200 OK", listOf(cseq, "Session: 1234;timeout=60", "Transport: ${request.header("Transport")}"))
                                else -> RtspMessage("RTSP/1.0 200 OK", listOf(cseq, "Session: 1234"))
                            }
                            out.write(response.bytes())
                            if (method == "PLAY") out.write(frame)
                            out.flush()
                        }
                    }
                }
            }
        }

        override fun close() = server.close()
    }

    private class Client(url: String) {
        private val port = Regex("""127\.0\.0\.1:(\d+)""").find(url)!!.groupValues[1].toInt()
        val socket = Socket(InetAddress.getLoopbackAddress(), port)
        private val input = BufferedInputStream(socket.getInputStream())
        private var cseq = 0

        fun send(method: String, url: String, vararg headers: String): RtspMessage {
            socket.getOutputStream().apply { write(RtspMessage("$method $url RTSP/1.0", listOf("CSeq: ${++cseq}") + headers).bytes()); flush() }
            return readRtspMessage(input)!!
        }

        fun raw(n: Int) = ByteArray(n).also { DataInputStream(input).readFully(it) }
    }

    private fun proxy(recorder: FakeRecorder, notes: MutableList<String>) =
        RtspSdpProxy(recorder.url, SocketFactory.getDefault()) { notes += it }.also { closeables += it }

    @Test
    fun `the SDP gets a control attribute, keeps only the video section and an exact Content-Length`() {
        val recorder = FakeRecorder(recorderSdp, contentBase = null)
        val notes = CopyOnWriteArrayList<String>()
        val proxy = proxy(recorder, notes)
        val client = Client(proxy.url)

        assertThat(client.send("OPTIONS", proxy.url).status).isEqualTo(200)
        val describe = client.send("DESCRIBE", proxy.url, "Accept: application/sdp")
        val sdp = String(describe.body)

        assertThat(describe.status).isEqualTo(200)
        assertThat(describe.header("Content-Length")!!.toInt()).isEqualTo(describe.body.size)
        assertThat(sdp).contains("m=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n")
        assertThat(sdp).endsWith("a=control:*\r\n")
        assertThat(sdp).doesNotContain("m=audio")
        assertThat(sdp).startsWith("v=0\r\no=- 1 1 IN IP4 192.168.42.1\r\n") // session part untouched
        // Media3 sends every request to the proxy; the recorder sees its own URL.
        assertThat(recorder.requests).containsExactly("OPTIONS ${recorder.url} RTSP/1.0", "DESCRIBE ${recorder.url} RTSP/1.0").inOrder()
        assertThat(notes.single { "DESCRIBE" in it }).isEqualTo(
            "proxy ${recorder.url}: DESCRIBE 200: kept m=video 0 RTP/AVP 96, a=control:* added to 1, dropped m=audio 0 RTP/AVP 8",
        )
    }

    @Test
    fun `SETUP and PLAY reach the recorder's URL and interleaved data passes byte for byte, keep-alives too`() {
        val recorder = FakeRecorder(recorderSdp, contentBase = null)
        val notes = CopyOnWriteArrayList<String>()
        val proxy = proxy(recorder, notes)
        val client = Client(proxy.url)
        client.send("DESCRIBE", proxy.url)

        val setup = client.send("SETUP", proxy.url, "Transport: RTP/AVP/TCP;unicast;interleaved=0-1")
        val play = client.send("PLAY", proxy.url, "Session: 1234")
        val data = client.raw(frame.size)
        val keepAlive = client.send("GET_PARAMETER", proxy.url, "Session: 1234") // raw mode: answered verbatim

        assertThat(setup.status).isEqualTo(200)
        assertThat(setup.header("Transport")).isEqualTo("RTP/AVP/TCP;unicast;interleaved=0-1")
        assertThat(play.status).isEqualTo(200)
        assertThat(data).isEqualTo(frame)
        assertThat(keepAlive.status).isEqualTo(200)
        assertThat(recorder.requests.drop(1)).containsExactly(
            "SETUP ${recorder.url} RTSP/1.0", "PLAY ${recorder.url} RTSP/1.0", "GET_PARAMETER ${recorder.url} RTSP/1.0",
        ).inOrder()
        assertThat(notes).containsAtLeast(
            "proxy ${recorder.url}: SETUP 200 (Transport: RTP/AVP/TCP;unicast;interleaved=0-1)",
            "proxy ${recorder.url}: PLAY 200",
        ).inOrder()
    }

    @Test
    fun `an absolute Content-Base points to the proxy and a present control is kept`() {
        val sdp = recorderSdp.replace("a=rtpmap:96 H264/90000", "a=rtpmap:96 H264/90000\r\na=Control:trackID=0")
        val recorder = FakeRecorder(sdp, contentBase = "rtsp://192.168.42.1:554/ch1/sub/")
        val notes = CopyOnWriteArrayList<String>()
        val proxy = proxy(recorder, notes)
        val client = Client(proxy.url)

        val describe = client.send("DESCRIBE", proxy.url)

        val origin = proxy.url.substringBefore("/ch1")
        assertThat(describe.header("Content-Base")).isEqualTo("$origin/ch1/sub/")
        assertThat(String(describe.body)).contains("a=control:trackID=0\r\n") // Media3 matches the name exactly
        assertThat(String(describe.body)).doesNotContain("a=control:*")
        assertThat(notes.single { "DESCRIBE" in it }).contains("control present")
    }

    @Test
    fun `without a video section every section is kept, each with a control`() {
        val rewrite = rewriteSdp("v=0\nm=audio 0 RTP/AVP 8\na=rtpmap:8 PCMA/8000\nm=audio 0 RTP/AVP 0\na=control:track2\n")
        assertThat(rewrite.kept).containsExactly("m=audio 0 RTP/AVP 8", "m=audio 0 RTP/AVP 0").inOrder()
        assertThat(rewrite.dropped).isEmpty()
        assertThat(rewrite.injected).isEqualTo(1)
        assertThat(rewrite.sdp).isEqualTo("v=0\r\nm=audio 0 RTP/AVP 8\r\na=rtpmap:8 PCMA/8000\r\na=control:*\r\nm=audio 0 RTP/AVP 0\r\na=control:track2\r\n")
    }

    @Test
    fun `the DESCRIBE capture records headers and SDP in full, and an unreachable URL as an error line`() {
        val recorder = FakeRecorder(recorderSdp, contentBase = null)

        val (ok, lines) = rtspDescribe(recorder.url, SocketFactory.getDefault())

        assertThat(ok).isTrue()
        assertThat(lines.first()).isEqualTo("# ${recorder.url}")
        assertThat(lines).containsAtLeast("> OPTIONS ${recorder.url} RTSP/1.0", "> Accept: application/sdp", "< RTSP/1.0 200 OK", "< m=audio 0 RTP/AVP 8").inOrder()
        assertThat(lines).contains("< Content-Type: application/sdp")

        val closed = ServerSocket(0).use { it.localPort } // nothing listens there any more
        val (failed, error) = rtspDescribe("rtsp://127.0.0.1:$closed/ch1/sub", SocketFactory.getDefault(), timeoutMs = 1_000)
        assertThat(failed).isFalse()
        assertThat(error.last()).startsWith("! ")
    }

    @Test
    fun `Authorization headers are masked in the transcript`() {
        val lines = RtspMessage("DESCRIBE rtsp://x/ch1 RTSP/1.0", listOf("CSeq: 2", "Authorization: Basic YWRtaW46c2VjcmV0")).lines()
        assertThat(lines).containsExactly("DESCRIBE rtsp://x/ch1 RTSP/1.0", "CSeq: 2", "Authorization: ***").inOrder()
    }
}
