package me.ri3d.dashcam.recorder

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Test fixture: the recorder's RTSP preview server, shaped like the physical recorder's answers (raw capture of app
 * 1.0.1, 2026-10-02): OPTIONS with `Public: OPTIONS, DESCRIBE, SETUP, TEARDOWN, PLAY, PAUSE`; DESCRIBE with an SDP of
 * only `v=`, `m=video 0 RTP/AVP 96`, `a=rtpmap`, `a=fmtp` – no `o=`/`s=`/`t=`/`c=`, no `a=control`, and
 * `sprop-parameter-sets` holding the parameter sets *with* Annex-B start codes and an SPS cut inside its VUI
 * ([SDP_SPS]), as the recorder sends them; any path is
 * served (the recorder answers `/ch1/sub` and `/ch1/sub/av_stream`). SETUP only over TCP (UDP: 461) and only on the
 * aggregate URL – the one DESCRIBEd on that connection; a per-track URL gets 404 unless [trackUrls] – answered with
 * `Transport: RTP/AVP/TCP;unicast;interleaved=0-1`. After PLAY the frames of [CLIP] go out as RTP (RFC 6184: single NAL
 * unit or FU-A, 90 kHz, marker bit on the last packet of a frame) on interleaved channel 0 at [FPS] frames per second,
 * in a loop. OPTIONS / GET_PARAMETER keep the session, TEARDOWN ends the connection. Every request is [log]ged.
 *
 * The parameter sets are the clip's own (320x176 Baseline); the recorder's (880x496 Main, `profile-level-id=4DE028`)
 * would not match the frames sent here. In-band, before every IDR, the complete SPS and the PPS go out.
 */
class SimulatorRtspServer(
    port: Int = 0,
    host: InetAddress = InetAddress.getLoopbackAddress(),
    private val trackUrls: Boolean = false,
    private val log: (String) -> Unit = {},
) : AutoCloseable {
    private val server = ServerSocket(port, 8, host)
    private val sessions = CopyOnWriteArrayList<Socket>()

    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true, name = "sim-rtsp") {
            while (true) {
                val socket = try { server.accept() } catch (e: IOException) { break }
                sessions += socket
                thread(isDaemon = true, name = "sim-rtsp-session") { session(socket) }
            }
        }
    }

    override fun close() {
        server.close()
        sessions.forEach { runCatching { it.close() } }
    }

    private fun session(socket: Socket) {
        log("rtsp client connected: ${socket.remoteSocketAddress}")
        val input = BufferedInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        var aggregate: String? = null
        var setUp = false
        var stream: Thread? = null
        try {
            while (true) {
                input.mark(1)
                val first = input.read()
                if (first < 0) break
                if (first == '$'.code) { // a client's interleaved frame (Media3 sends none): skipped
                    val head = ByteArray(3).also { DataInputStream(input).readFully(it) }
                    input.skipNBytesCompat(((head[1].toInt() and 0xFF) shl 8) or (head[2].toInt() and 0xFF))
                    continue
                }
                input.reset()
                val head = generateSequence { readLine(input).takeIf { it.isNotEmpty() } }.toList()
                if (head.isEmpty()) continue
                val headers = head.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
                headers["content-length"]?.toIntOrNull()?.let { input.skipNBytesCompat(it) }
                val (method, url) = head.first().split(' ').let { it[0] to it.getOrElse(1) { "" } }
                val cseq = "CSeq: ${headers["cseq"]}"
                val response = when (method) {
                    "OPTIONS" -> reply(200, cseq, "Public: OPTIONS, DESCRIBE, SETUP, TEARDOWN, PLAY, PAUSE")
                    "DESCRIBE" -> {
                        aggregate = url
                        reply(200, cseq, "Content-Type: application/sdp", "Content-Length: ${SDP.length}", body = SDP)
                    }
                    "SETUP" -> when {
                        "TCP" !in headers["transport"].orEmpty() -> reply(461, cseq)
                        url.trimEnd('/') != (aggregate ?: url).trimEnd('/') && !(trackUrls && url.startsWith(aggregate.orEmpty())) -> reply(404, cseq)
                        else -> reply(200, cseq, "Session: $SESSION;timeout=60", "Transport: RTP/AVP/TCP;unicast;interleaved=0-1").also { setUp = true }
                    }
                    "PLAY" -> if (!setUp) reply(455, cseq) else reply(200, cseq, "Session: $SESSION", "Range: npt=0.000-")
                    "PAUSE", "GET_PARAMETER", "TEARDOWN" -> reply(200, cseq, "Session: $SESSION")
                    else -> reply(501, cseq)
                }
                log("rtsp <- $method $url -> ${response.substringAfter(' ').substringBefore(' ')}")
                synchronized(out) {
                    out.write(response.toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                }
                if (method == "PLAY" && setUp && stream == null) stream = thread(isDaemon = true, name = "sim-rtp") { stream(out) }
                if (method == "PAUSE") stream = stream?.let { it.interrupt(); null }
                if (method == "TEARDOWN") break
            }
        } catch (e: IOException) {
            // client gone
        } finally {
            stream?.interrupt()
            sessions -= socket
            runCatching { socket.close() }
            log("rtsp client disconnected")
        }
    }

    private fun reply(status: Int, vararg headers: String, body: String = ""): String =
        (listOf("RTSP/1.0 $status ${REASONS[status]}") + headers + "").joinToString("") { "$it\r\n" } + body

    /** [CLIP] frame by frame, looped, until interrupted or the connection breaks. */
    private fun stream(out: OutputStream) {
        var sequence = 0
        var timestamp = 0x1000L
        try {
            while (!Thread.currentThread().isInterrupted) {
                for (frame in FRAMES) {
                    val payloads = frame.flatMap(::payloads)
                    synchronized(out) {
                        payloads.forEachIndexed { i, payload ->
                            out.write(interleaved(rtp(payload, sequence++ and 0xFFFF, timestamp, marker = i == payloads.lastIndex)))
                        }
                        out.flush()
                    }
                    timestamp = (timestamp + 90_000 / FPS) and 0xFFFFFFFFL
                    Thread.sleep(1_000L / FPS)
                }
            }
        } catch (e: InterruptedException) {
            // PAUSE, TEARDOWN or the connection ended
        } catch (e: IOException) {
            // client gone
        }
    }

    companion object {
        /** RTP payload bytes per packet: one NAL unit up to this size, FU-A fragments above. */
        const val MAX_PAYLOAD = 1400
        const val FPS = 25
        private const val SESSION = "53494D31"
        private val REASONS = mapOf(200 to "OK", 404 to "Not Found", 455 to "Method Not Valid in This State", 461 to "Unsupported Transport", 501 to "Not Implemented")

        /** `sim/clip.h264`: the NAL units of `sim/clip.mp4` (SPS and PPS of its avcC, then its 25 frames) in Annex B. */
        val CLIP: List<ByteArray> by lazy {
            annexB(SimulatorRtspServer::class.java.getResourceAsStream("/sim/clip.h264")!!.use { it.readBytes() })
        }

        /** Frames: every VCL NAL unit (types 1–5) with the parameter sets before it (SPS and PPS before the IDR). */
        val FRAMES: List<List<ByteArray>> by lazy {
            val frames = mutableListOf<List<ByteArray>>()
            val pending = mutableListOf<ByteArray>()
            CLIP.forEach { nal ->
                pending += nal
                if ((nal[0].toInt() and 0x1F) in 1..5) frames += pending.toList().also { pending.clear() }
            }
            frames
        }

        /**
         * The SPS of the SDP: the clip's, cut like the recorder's (2026-10-02) two bits after its VUI's
         * `bitstream_restriction_flag` (payload bit 96), so the fields that flag announces are missing.
         */
        val SDP_SPS: ByteArray by lazy { CLIP[0].copyOf(14).also { it[13] = (it[13].toInt() and 0xE0).toByte() } }

        /** The recorder's SDP shape (CRLF lines, as the recorder's Content-Length shows) with the clip's parameter sets. */
        val SDP: String by lazy {
            fun startCoded(nal: ByteArray) = Base64.getEncoder().encodeToString(byteArrayOf(0, 0, 0, 1) + nal)
            val sps = CLIP.first { (it[0].toInt() and 0x1F) == 7 }
            val profileLevelId = (1..3).joinToString("") { "%02X".format(sps[it]) }
            listOf(
                "v=0",
                "m=video 0 RTP/AVP 96",
                "a=rtpmap:96 H264/90000",
                "a=fmtp:96 profile-level-id=$profileLevelId;packetization-mode=1;sprop-parameter-sets=${startCoded(SDP_SPS)},${startCoded(CLIP[1])}",
            ).joinToString("") { "$it\r\n" }
        }

        /** NAL units of an Annex-B byte stream (3- and 4-byte start codes). */
        fun annexB(bytes: ByteArray): List<ByteArray> {
            val starts = (0..bytes.size - 3).filter { bytes[it].toInt() == 0 && bytes[it + 1].toInt() == 0 && bytes[it + 2].toInt() == 1 }
            return starts.mapIndexed { i, start ->
                val next = starts.getOrNull(i + 1)?.let { if (bytes[it - 1].toInt() == 0) it - 1 else it } ?: bytes.size
                bytes.copyOfRange(start + 3, next)
            }
        }

        /** RFC 6184 payloads for one NAL unit: itself, or FU-A fragments (indicator, header with S/E bits, data). */
        fun payloads(nal: ByteArray): List<ByteArray> {
            if (nal.size <= MAX_PAYLOAD) return listOf(nal)
            val indicator = (nal[0].toInt() and 0xE0) or 28
            val type = nal[0].toInt() and 0x1F
            return (1 until nal.size step MAX_PAYLOAD - 2).map { start ->
                val end = minOf(start + MAX_PAYLOAD - 2, nal.size)
                val header = (if (start == 1) 0x80 else 0) or (if (end == nal.size) 0x40 else 0) or type
                byteArrayOf(indicator.toByte(), header.toByte()) + nal.copyOfRange(start, end)
            }
        }

        private fun rtp(payload: ByteArray, sequence: Int, timestamp: Long, marker: Boolean): ByteArray {
            val header = byteArrayOf(
                0x80.toByte(), ((if (marker) 0x80 else 0) or 96).toByte(),
                (sequence shr 8).toByte(), sequence.toByte(),
                (timestamp shr 24).toByte(), (timestamp shr 16).toByte(), (timestamp shr 8).toByte(), timestamp.toByte(),
                0x53, 0x49, 0x4D, 0x31, // SSRC "SIM1"
            )
            return header + payload
        }

        private fun interleaved(packet: ByteArray): ByteArray =
            byteArrayOf('$'.code.toByte(), 0, (packet.size shr 8).toByte(), packet.size.toByte()) + packet

        private fun readLine(input: InputStream): String {
            val line = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) throw EOFException()
                if (b == '\n'.code) return line.toString().trimEnd('\r')
                line.append(b.toChar())
            }
        }

        /** `InputStream.skipNBytes` is Java 12+; this module targets 11. */
        private fun InputStream.skipNBytesCompat(n: Int) = DataInputStream(this).readFully(ByteArray(n))
    }
}
