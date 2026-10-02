package me.ri3d.dashcam.recorder

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.Socket
import java.util.Base64

/** [SimulatorRtspServer] through a tiny RTSP client: the recorder's SDP shape, SETUP rules, RTP after PLAY. */
class SimulatorRtspServerTest {
    private val server = SimulatorRtspServer()
    private val url = "rtsp://127.0.0.1:${server.port}/ch1/sub"

    @After
    fun tearDown() = server.close()

    private class Response(val status: Int, val headers: Map<String, String>, val body: String)

    private class Client(port: Int) {
        val socket = Socket(InetAddress.getLoopbackAddress(), port).apply { soTimeout = 5_000 }
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        private var cseq = 0

        fun send(method: String, url: String, vararg headers: String): Response {
            val request = (listOf("$method $url RTSP/1.0", "CSeq: ${++cseq}") + headers + "").joinToString("") { "$it\r\n" }
            socket.getOutputStream().apply { write(request.toByteArray()); flush() }
            val head = generateSequence { line().takeIf { it.isNotEmpty() } }.toList()
            val fields = head.drop(1).associate { it.substringBefore(':') to it.substringAfter(':').trim() }
            assertThat(fields["CSeq"]).isEqualTo("$cseq")
            val body = ByteArray(fields["Content-Length"]?.toInt() ?: 0).also(input::readFully)
            return Response(head.first().split(' ')[1].toInt(), fields, String(body, Charsets.ISO_8859_1))
        }

        private fun line() = generateSequence { input.read().takeIf { it != '\n'.code } }.map(Int::toChar).joinToString("").trimEnd('\r')

        /** One interleaved frame: channel and the RTP packet. */
        fun frame(): Pair<Int, ByteArray> {
            assertThat(input.readUnsignedByte()).isEqualTo('$'.code)
            val channel = input.readUnsignedByte()
            return channel to ByteArray(input.readUnsignedShort()).also(input::readFully)
        }
    }

    @Test
    fun `DESCRIBE answers the recorder's SDP shape with start-coded parameter sets`() {
        val client = Client(server.port)
        val options = client.send("OPTIONS", url)
        val describe = client.send("DESCRIBE", "$url/av_stream", "Accept: application/sdp") // any path, as on the recorder

        assertThat(options.headers["Public"]).isEqualTo("OPTIONS, DESCRIBE, SETUP, TEARDOWN, PLAY, PAUSE")
        assertThat(describe.status).isEqualTo(200)
        val lines = describe.body.split("\r\n").dropLast(1)
        assertThat(lines.map { it.take(2) }).containsExactly("v=", "m=", "a=", "a=").inOrder() // no o=, s=, t=, c=, a=control
        assertThat(lines[1]).isEqualTo("m=video 0 RTP/AVP 96")
        assertThat(lines[3]).startsWith("a=fmtp:96 profile-level-id=42C029;packetization-mode=1;sprop-parameter-sets=")
        val (sps, pps) = lines[3].substringAfter("sprop-parameter-sets=").split(',').map { Base64.getDecoder().decode(it) }
        assertThat(sps.take(5).map(Byte::toInt)).containsExactly(0, 0, 0, 1, 0x67).inOrder()
        assertThat(pps.take(5).map(Byte::toInt)).containsExactly(0, 0, 0, 1, 0x68).inOrder()
        assertThat(sps.drop(4)).containsExactlyElementsIn(SimulatorRtspServer.SDP_SPS.toList()).inOrder()
        assertThat(pps.drop(4)).containsExactlyElementsIn(SimulatorRtspServer.CLIP[1].toList()).inOrder()
        // The SDP's SPS is the clip's cut short (its first 3 bits after byte 12 kept), as the recorder's is.
        assertThat(SimulatorRtspServer.SDP_SPS.size).isLessThan(SimulatorRtspServer.CLIP[0].size)
        assertThat(SimulatorRtspServer.SDP_SPS.copyOf(13).toList()).isEqualTo(SimulatorRtspServer.CLIP[0].copyOf(13).toList())
    }

    @Test
    fun `SETUP works on the aggregate URL only, over TCP, and PLAY streams the clip as RTP on channel 0`() {
        val client = Client(server.port)
        client.send("DESCRIBE", url)
        assertThat(client.send("SETUP", "$url/trackID=0", "Transport: RTP/AVP/TCP;unicast;interleaved=0-1").status).isEqualTo(404)
        assertThat(client.send("SETUP", url, "Transport: RTP/AVP;unicast;client_port=5000-5001").status).isEqualTo(461)
        assertThat(client.send("PLAY", url).status).isEqualTo(455)
        val setup = client.send("SETUP", url, "Transport: RTP/AVP/TCP;unicast;interleaved=0-1")
        val play = client.send("PLAY", url, "Session: ${setup.headers["Session"]!!.substringBefore(';')}", "Range: npt=0.000-")

        assertThat(setup.status).isEqualTo(200)
        assertThat(setup.headers["Transport"]).isEqualTo("RTP/AVP/TCP;unicast;interleaved=0-1")
        assertThat(play.status).isEqualTo(200)

        // The first frame: SPS, PPS, then the IDR in FU-A fragments, the marker on the last one; one timestamp.
        val packets = generateSequence { client.frame() }.map { (channel, rtp) -> assertThat(channel).isEqualTo(0); rtp }
            .takeWhileInclusive { (it[1].toInt() and 0x80) == 0 }.toList()
        assertThat(packets.map { it[0].toInt() and 0xC0 }.distinct()).containsExactly(0x80) // RTP version 2
        assertThat(packets.map { it[1].toInt() and 0x7F }.distinct()).containsExactly(96)
        assertThat(packets.map { it.copyOfRange(4, 8).toList() }.distinct()).hasSize(1)
        assertThat(packets.map { it[12].toInt() and 0x1F }).containsExactly(7, 8, 28, 28, 28).inOrder()
        val idr = byteArrayOf(((packets[2][12].toInt() and 0xE0) or (packets[2][13].toInt() and 0x1F)).toByte()) +
            packets.drop(2).flatMap { it.copyOfRange(14, it.size).toList() }
        assertThat(packets[2][13].toInt() and 0xC0).isEqualTo(0x80) // start
        assertThat(packets.last()[13].toInt() and 0xC0).isEqualTo(0x40) // end
        assertThat(idr.toList()).isEqualTo(SimulatorRtspServer.FRAMES[0].last().toList())
        // The next frame: one single-NAL packet, 1/25 s later (90 kHz).
        val next = client.frame().second
        assertThat(next[12].toInt() and 0x1F).isEqualTo(1)
        assertThat(timestamp(next) - timestamp(packets[0])).isEqualTo(3_600)
        // Keep-alive while streaming: the answer arrives between RTP frames.
        client.socket.getOutputStream().apply { write("GET_PARAMETER $url RTSP/1.0\r\nCSeq: 99\r\n\r\n".toByteArray()); flush() }
        val answer = generateSequence { client.input.read().takeIf { it >= 0 } }.map(Int::toChar).windowed(15) { it.joinToString("") }
            .first { it == "RTSP/1.0 200 OK" }
        assertThat(answer).isEqualTo("RTSP/1.0 200 OK")
    }

    @Test
    fun `per-track URLs are accepted when configured, and TEARDOWN ends the connection`() {
        SimulatorRtspServer(trackUrls = true).use { lenient ->
            val client = Client(lenient.port)
            val url = "rtsp://127.0.0.1:${lenient.port}/ch1/sub"
            client.send("DESCRIBE", url)

            assertThat(client.send("SETUP", "$url/trackID=0", "Transport: RTP/AVP/TCP;unicast;interleaved=0-1").status).isEqualTo(200)
            assertThat(client.send("TEARDOWN", url, "Session: 53494D31").status).isEqualTo(200)
            assertThat(client.input.read()).isEqualTo(-1) // closed by the server
        }
    }

    private fun timestamp(rtp: ByteArray) = DataInputStream(rtp.inputStream(4, 4)).readInt().toLong() and 0xFFFFFFFFL

    private fun <T> Sequence<T>.takeWhileInclusive(predicate: (T) -> Boolean): Sequence<T> = sequence {
        for (item in this@takeWhileInclusive) {
            yield(item)
            if (!predicate(item)) break
        }
    }
}
