package me.ri3d.dashcam.live

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import javax.net.SocketFactory
import kotlin.concurrent.thread

/** [rtspDescribe] against a fake RTSP server shaped like the recorder (SDP without `a=control`). */
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
