package me.ri3d.dashcam.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ri3d.dashcam.dashcam.RecorderConnectionManager
import me.ri3d.dashcam.dashcam.RecorderConnectionManagerImpl
import me.ri3d.dashcam.dashcam.RecorderConnectionState
import me.ri3d.dashcam.recorder.CapabilityGroup
import me.ri3d.dashcam.recorder.parseBasicCapabilities
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.SocketFactory
import kotlin.concurrent.thread

/*
 * RTSP plumbing for the recorder's preview. Hardware 2026-10-02: the recorder answers DESCRIBE with
 *   v=0 / m=video 0 RTP/AVP 96 / a=rtpmap:96 H264/90000 /
 *   a=fmtp:96 profile-level-id=4DE028;packetization-mode=1;sprop-parameter-sets=AAAAAWdNAB+NjUBuH9CAAALuAACvyA8=,AAAAAWjuOIA=
 * – no `o=`/`s=`/`t=`/`c=`, no `a=control` (Media3 1.11's `RtspMediaTrack`: "missing attribute control"), and parameter
 * sets carrying Annex-B start codes (`00 00 00 01 67 …`), which Media3 would read as part of the NAL unit. Media3 has
 * no option for either (RtspMediaSource.Factory offers only TCP, user agent, socket factory, debug logging, timeout),
 * so [RtspSdpProxy] repairs the description on the way.
 */

/** One RTSP request or response: start line, header lines as sent, body bytes (by Content-Length). */
internal data class RtspMessage(val startLine: String, val headers: List<String>, val body: ByteArray = ByteArray(0)) {
    fun header(name: String): String? =
        headers.firstOrNull { it.substringBefore(':').trim().equals(name, ignoreCase = true) }?.substringAfter(':')?.trim()

    /** Status of a response ("RTSP/1.0 200 OK" → 200), null for a request. */
    val status: Int? get() = startLine.takeIf { it.startsWith("RTSP/") }?.split(' ')?.getOrNull(1)?.toIntOrNull()

    fun bytes(): ByteArray = (listOf(startLine) + headers + "").joinToString("") { "$it\r\n" }.toByteArray(Charsets.ISO_8859_1) + body

    /** For the Diagnose transcript: start line, headers (`Authorization` masked), a blank line, the body's lines. */
    fun lines(): List<String> = listOf(startLine) +
        headers.map { if (it.substringBefore(':').trim().equals("Authorization", ignoreCase = true)) "Authorization: ***" else it } +
        if (body.isEmpty()) emptyList() else listOf("") + String(body, Charsets.ISO_8859_1).lines().map { it.trimEnd('\r') }.dropLastWhile { it.isEmpty() }
}

/** Reads one RTSP message (lines end with CRLF or LF); null at the end of the stream. Peek for `$` frames first. */
internal fun readRtspMessage(input: InputStream): RtspMessage? {
    var start = readLine(input) ?: return null
    while (start.isEmpty()) start = readLine(input) ?: return null // stray line breaks between messages
    val headers = generateSequence { readLine(input)?.takeIf { it.isNotEmpty() } }.toList()
    val message = RtspMessage(start, headers)
    val length = message.header("Content-Length")?.toIntOrNull() ?: 0
    if (length !in 0..MAX_BODY) throw IOException("RTSP Content-Length $length")
    return message.copy(body = ByteArray(length).also { DataInputStream(input).readFully(it) })
}

private fun readLine(input: InputStream): String? {
    val line = StringBuilder()
    while (true) {
        val b = input.read()
        if (b < 0) return if (line.isEmpty()) null else throw EOFException("RTSP message cut off")
        if (b == '\n'.code) return line.toString().trimEnd('\r')
        if (line.length == MAX_LINE) throw IOException("RTSP line longer than $MAX_LINE")
        line.append(b.toChar()) // ISO-8859-1: one byte, one char, so bodies and odd bytes survive the round trip
    }
}

/** The first byte without consuming it (-1 at the end); [input] must support mark. */
private fun peek(input: InputStream): Int {
    input.mark(1)
    return input.read().also { input.reset() }
}

private const val MAX_LINE = 8 * 1024
private const val MAX_BODY = 256 * 1024
private const val INTERLEAVED = '$'.code

/**
 * [kept]: the media lines left in the description, [dropped]: the removed ones, [injected]: how many got `a=control:*`,
 * [added]: the session lines added (`o=`, `s=`, `c=`, `t=`), [stripped]: parameter sets that lost their start code,
 * [spsCut]: SPS whose VUI was cut ([repairSps]).
 */
internal data class SdpRewrite(
    val sdp: String,
    val kept: List<String>,
    val dropped: List<String>,
    val injected: Int,
    val added: List<String> = emptyList(),
    val stripped: Int = 0,
    val spsCut: Int = 0,
)

/**
 * Makes the recorder's SDP acceptable to Media3 (and RFC 4566): only the first `m=video` section is kept (live sound
 * is muted anyway, and a second `*` track would SETUP the same URL twice); without a video section every section
 * stays. A kept section without a control attribute gets `a=control:*` – the aggregate (session) URL, which RFC 2326
 * C.1.1 prescribes when there is no per-track control; a control attribute in other letter case is written as
 * `a=control` (Media3 matches it exactly). Every `sprop-parameter-sets` entry that starts with an Annex-B start code
 * (`00 00 00 01` / `00 00 01`) is re-encoded without it (RFC 6184 carries bare NAL units; Media3 adds its own start
 * code), and an SPS that ends inside its VUI is cut there ([repairSps]); `profile-level-id`, `packetization-mode` and
 * anything else stay. Missing session lines are added with [host] (the recorder's address): `o=- 0 0 IN IP4 <host>`,
 * `s=My Forthing`, `c=IN IP4 <host>`, `t=0 0`, the session part in RFC order. Lines end with CRLF.
 */
internal fun rewriteSdp(sdp: String, host: String): SdpRewrite {
    val lines = sdp.lines().map { it.trimEnd('\r') }.filter { it.isNotBlank() }
    val given = lines.takeWhile { !it.startsWith("m=") }
    val added = listOf("o=- 0 0 IN IP4 $host", "s=My Forthing", "c=IN IP4 $host", "t=0 0")
        .filter { line -> given.none { it.startsWith(line.take(2)) } }
    val session = (given + added).sortedBy { SESSION_ORDER.indexOf(it[0]).takeIf { i -> i >= 0 } ?: SESSION_ORDER.length }
    val sections = lines.drop(given.size).fold(mutableListOf<MutableList<String>>()) { acc, line ->
        if (line.startsWith("m=")) acc += mutableListOf(line) else acc.last() += line
        acc
    }
    val keep = sections.firstOrNull { it.first().startsWith("m=video") }?.let(::listOf) ?: sections
    val control = Regex("^a=control(?=:|$)", RegexOption.IGNORE_CASE)
    var injected = 0
    var stripped = 0
    var spsCut = 0
    val media = keep.flatMap { section ->
        if (section.any(control::containsMatchIn)) {
            section.map { control.replace(it, "a=control") }
        } else {
            injected++
            section + "a=control:*"
        }
    }.map { line ->
        SPROP.replace(line) { m ->
            m.groupValues[1] + m.groupValues[2].split(',').joinToString(",") { set ->
                val bytes = runCatching { Base64.getDecoder().decode(set) }.getOrNull() ?: return@joinToString set
                val code = START_CODES.firstOrNull { bytes.size > it.size && bytes.copyOf(it.size).contentEquals(it) }
                val nal = if (code == null) bytes else bytes.copyOfRange(code.size, bytes.size).also { stripped++ }
                val sps = nal.takeIf { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 }?.let(::repairSps)?.also { spsCut++ }
                if (code == null && sps == null) set else Base64.getEncoder().encodeToString(sps ?: nal)
            }
        }
    }
    return SdpRewrite(
        sdp = (session + media).joinToString("") { "$it\r\n" },
        kept = keep.map { it.first() },
        dropped = sections.filter { s -> keep.none { it === s } }.map { it.first() },
        injected = injected,
        added = added.map { it.take(2) },
        stripped = stripped,
        spsCut = spsCut,
    )
}

/** RFC 4566 order of the session-level lines. */
private const val SESSION_ORDER = "vosiuepcbtrzka"
private val SPROP = Regex("""(sprop-parameter-sets=)([^;\s]+)""")
private val START_CODES = listOf(byteArrayOf(0, 0, 0, 1), byteArrayOf(0, 0, 1))

/**
 * An H.264 SPS ([nal], no start code) whose VUI sets `bitstream_restriction_flag` but ends before the fields that flag
 * announces, rewritten with the flag 0 and the RBSP ending right there; null when the SPS is complete or has no VUI.
 * The recorder's SPS (2026-10-02, 19 bytes, 880x496 Main) ends two bits after that flag: Media3 1.11 reads the
 * missing fields unchecked (`ArrayIndexOutOfBoundsException` on its playback thread, which ends the app), and strict
 * decoders reject such an SPS. Cutting there invents no values; decoders then assume the defaults for the absent
 * restriction fields (output reordering up to the DPB size).
 * ponytail: SPS with scaling matrices or HRD parameters are not walked and stay as sent; walk them when a recorder
 * sends one cut like this.
 */
internal fun repairSps(nal: ByteArray): ByteArray? {
    val bits = Bits(nal)
    val flag = runCatching { bits.restrictionFlag() }.getOrNull() ?: return null
    if (bits.u(1) == 0) return null
    // motion_vectors_over_pic_boundaries_flag, six ue(v) fields, then the rbsp_stop_one_bit
    val complete = runCatching { bits.u(1); repeat(6) { bits.ue() }; '1' in bits.text.substring(bits.pos) }.getOrDefault(false)
    if (complete) return null
    val rbsp = (bits.text.substring(0, flag) + "01").let { it.padEnd((it.length + 7) / 8 * 8, '0') } // flag 0, stop bit
    val out = ByteArrayOutputStream().apply { write(nal[0].toInt()) }
    var zeros = 0
    rbsp.chunked(8).map { it.toInt(2) }.forEach { b -> // emulation prevention back in
        if (zeros >= 2 && b <= 3) {
            out.write(3)
            zeros = 0
        }
        out.write(b)
        zeros = if (b == 0) zeros + 1 else 0
    }
    return out.toByteArray()
}

/** The RBSP of a NAL unit (header byte and emulation prevention bytes removed) as '0'/'1' text, read in order. */
private class Bits(nal: ByteArray) {
    val text: String = buildString {
        var zeros = 0
        for (i in 1 until nal.size) {
            val b = nal[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) { zeros = 0; continue }
            zeros = if (b == 0) zeros + 1 else 0
            append(Integer.toBinaryString(b or 0x100).substring(1))
        }
    }
    var pos = 0

    /** [n] bits (n ≤ 30); past the end throws. */
    fun u(n: Int): Int = text.substring(pos, pos + n).let { pos += n; if (n == 0) 0 else it.toInt(2) }

    fun ue(): Int {
        var zeros = 0
        while (text[pos] == '0') { zeros++; pos++ }
        pos++
        return (1 shl zeros) - 1 + u(zeros)
    }

    fun skip(n: Int) {
        require(pos + n <= text.length)
        pos += n
    }

    /**
     * Walks an SPS (ITU-T H.264 7.3.2.1.1, E.1.1) to `bitstream_restriction_flag`: its position; null without VUI or
     * with parts not walked.
     */
    fun restrictionFlag(): Int? {
        val profile = u(8)
        skip(16) // constraint flags, level_idc
        ue() // seq_parameter_set_id
        if (profile in HIGH_PROFILES) {
            if (ue() == 3) skip(1) // chroma_format_idc, separate_colour_plane_flag
            ue(); ue(); skip(1) // bit depths, qpprime_y_zero_transform_bypass_flag
            if (u(1) == 1) return null // seq_scaling_matrix_present_flag: not walked
        }
        ue() // log2_max_frame_num_minus4
        when (ue()) { // pic_order_cnt_type
            0 -> ue()
            1 -> { skip(1); ue(); ue(); repeat(ue()) { ue() } } // se(v) skipped like ue(v)
        }
        ue(); skip(1); ue(); ue() // max_num_ref_frames, gaps, width, height
        if (u(1) == 0) skip(1) // frame_mbs_only_flag, mb_adaptive_frame_field_flag
        skip(1) // direct_8x8_inference_flag
        if (u(1) == 1) repeat(4) { ue() } // frame cropping
        if (u(1) == 0) return null // vui_parameters_present_flag
        if (u(1) == 1 && u(8) == 255) skip(32) // aspect ratio, extended SAR
        if (u(1) == 1) skip(1) // overscan
        if (u(1) == 1) { skip(4); if (u(1) == 1) skip(24) } // video signal type, colour description
        if (u(1) == 1) { ue(); ue() } // chroma location
        if (u(1) == 1) skip(65) // timing info
        if (u(1) == 1 || u(1) == 1) return null // HRD parameters: not walked
        skip(1) // pic_struct_present_flag
        return pos
    }

    private companion object {
        val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
    }
}

/**
 * Local RTSP proxy for one recorder URL ([target]), so Media3 accepts the recorder's description. Listens on
 * `127.0.0.1:<ephemeral>` from construction on (connections queue until accepted); Media3 plays [url]. Bound to the
 * IPv4 literal on purpose: Android's `InetAddress.getLoopbackAddress()` is `::1`, so a proxy bound there refused
 * Media3's connection to `127.0.0.1` (ECONNREFUSED on hardware and emulator, 1.0.1–1.0.2). Each connection Media3
 * opens gets one socket to the recorder from [sockets] (bound to the recorder Wi-Fi). Requests are forwarded verbatim
 * except their request line (`rtsp://127.0.0.1:<port>/…` → the recorder's origin). Responses are forwarded
 * verbatim except the DESCRIBE answer: its SDP goes through [rewriteSdp], Content-Length is recomputed and
 * Content-Base / Content-Location point to the proxy. After the PLAY answer (or as soon as an interleaved `$` frame
 * arrives) the recorder's side is piped raw; the phone's side stays message-aware, so keep-alives and TEARDOWN
 * still reach the recorder's URL. Every step is reported to [note] for the Diagnose export.
 *
 * Any app on the phone could reach the recorder's RTSP through the loopback port while it is open, as it could over
 * the Wi-Fi itself; the port lives only while the live view plays.
 */
class RtspSdpProxy(
    private val target: String,
    private val sockets: SocketFactory,
    private val note: (String) -> Unit,
) : Closeable {
    private val targetUri = URI(target)
    private val targetOrigin = "rtsp://" + targetUri.rawAuthority
    private val server = ServerSocket(0, 4, InetAddress.getByName(LOOPBACK)) // an IP literal: no lookup
    private val origin = "rtsp://$LOOPBACK:${server.localPort}"
    private val open = CopyOnWriteArrayList<Socket>()

    @Volatile private var closed = false

    /** What Media3 plays: the recorder's path and query on the proxy. */
    val url: String = origin + targetUri.rawPath.orEmpty() + (targetUri.rawQuery?.let { "?$it" } ?: "")

    init {
        note("listening $LOOPBACK:${server.localPort}")
        thread(isDaemon = true, name = "rtsp-proxy") {
            while (!closed) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    if (!closed) note("accept failed: ${e.javaClass.simpleName}: ${e.message}")
                    break
                }
                note("accepted")
                open += client
                thread(isDaemon = true, name = "rtsp-proxy-in") { relay(client) }
            }
        }
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        open.forEach { runCatching { it.close() } }
        open.clear()
    }

    /**
     * Stops accepting at once; open connections get [lingerMs] to end by themselves, then [close]. Media3 stops on its
     * playback thread and sends TEARDOWN through the proxy before it closes its socket, which ends the connection.
     */
    fun close(lingerMs: Long) {
        closed = true
        runCatching { server.close() }
        if (open.isNotEmpty()) thread(isDaemon = true, name = "rtsp-proxy-linger") { Thread.sleep(lingerMs); close() }
    }

    private fun note(message: String) = note.invoke("proxy $target: $message")

    private fun relay(client: Socket) {
        val recorder = try {
            sockets.createSocket().apply {
                open += this
                connect(InetSocketAddress(targetUri.host, targetUri.port.takeIf { it > 0 } ?: RTSP_PORT), CONNECT_TIMEOUT_MS)
            }
        } catch (e: IOException) {
            if (!closed) note("connect failed: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { client.close() }
            return
        }
        val methods = ConcurrentHashMap<String, String>() // CSeq → method of the request in flight
        // Either side closing ends both pumps: each closes both sockets when it stops.
        fun closeBoth() {
            listOf(client, recorder).forEach { runCatching { it.close() }; open -= it }
        }
        if (closed) return closeBoth() // stopped while connecting
        thread(isDaemon = true, name = "rtsp-proxy-out") {
            try {
                responses(BufferedInputStream(recorder.getInputStream()), client.getOutputStream(), methods)
            } finally {
                closeBoth()
            }
        }
        try {
            requests(BufferedInputStream(client.getInputStream()), recorder.getOutputStream(), methods)
            // Media3 closed its side (after a TEARDOWN): the recorder gets the end of the requests and may answer; the
            // response pump ends the connection when the recorder closes or the phone's side is gone.
            recorder.shutdownOutput()
        } catch (e: IOException) {
            closeBoth() // closed by either side
        }
    }

    /** Phone → recorder: request lines rewritten to the recorder's URL; interleaved frames (Media3 sends none) pass. */
    private fun requests(input: InputStream, output: OutputStream, methods: MutableMap<String, String>) {
        while (true) {
            val first = peek(input)
            if (first < 0) return
            if (first == INTERLEAVED) {
                val head = ByteArray(4).also { DataInputStream(input).readFully(it) }
                val payload = ByteArray(((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)).also { DataInputStream(input).readFully(it) }
                output.write(head + payload)
            } else {
                val request = readRtspMessage(input) ?: return
                val method = request.startLine.substringBefore(' ')
                request.header("CSeq")?.let { methods[it] = method }
                if (method == "TEARDOWN") note("TEARDOWN forwarded")
                output.write(request.copy(startLine = request.startLine.replace(origin, targetOrigin)).bytes())
            }
            output.flush()
        }
    }

    /** Recorder → phone: DESCRIBE repaired, statuses noted; raw after PLAY or the first interleaved frame. */
    private fun responses(input: InputStream, output: OutputStream, methods: MutableMap<String, String>) {
        var streamed = 0L
        try {
            while (true) {
                val first = peek(input)
                if (first < 0) return
                if (first == INTERLEAVED) break
                val response = readRtspMessage(input) ?: return
                val method = response.header("CSeq")?.let(methods::remove) ?: "?"
                val forwarded = if (method == "DESCRIBE" && response.status == 200) describe(response) else response.also {
                    note("$method ${response.status}" + (response.header("Transport")?.let { t -> " (Transport: $t)" } ?: ""))
                }
                output.write(forwarded.bytes())
                output.flush()
                if (method == "PLAY") break
            }
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
                streamed += n
            }
        } catch (e: IOException) {
            // Closed by either side.
        } finally {
            if (streamed > 0) note("stream connection ended after $streamed bytes")
        }
    }

    private fun describe(response: RtspMessage): RtspMessage {
        val rewrite = rewriteSdp(String(response.body, Charsets.ISO_8859_1), targetUri.host)
        val body = rewrite.sdp.toByteArray(Charsets.ISO_8859_1)
        val headers = response.headers.map { line ->
            val name = line.substringBefore(':').trim()
            when {
                name.equals("Content-Length", ignoreCase = true) -> "Content-Length: ${body.size}"
                name.equals("Content-Base", ignoreCase = true) || name.equals("Content-Location", ignoreCase = true) ->
                    "$name: ${toProxy(line.substringAfter(':').trim())}"
                else -> line
            }
        }
        // Media3 also needs sprop-parameter-sets for H.264 (else "missing attribute fmtp" / "missing sprop parameter").
        val h264 = rewrite.sdp.contains(" H264/", ignoreCase = true)
        note(
            "DESCRIBE ${response.status}: kept ${rewrite.kept.joinToString().ifEmpty { "nothing" }}" +
                (if (rewrite.injected > 0) ", a=control:* added to ${rewrite.injected}" else ", control present") +
                (if (rewrite.added.isNotEmpty()) ", added ${rewrite.added.joinToString(" ")}" else "") +
                (if (rewrite.stripped > 0) ", start codes removed from ${rewrite.stripped} sprop-parameter-sets" else "") +
                (if (rewrite.spsCut > 0) ", SPS cut before its missing bitstream_restriction fields" else "") +
                (if (rewrite.dropped.isNotEmpty()) ", dropped ${rewrite.dropped.joinToString()}" else "") +
                (if (h264 && !rewrite.sdp.contains("sprop-parameter-sets=")) ", H264 without sprop-parameter-sets" else "") +
                (response.header("Content-Base")?.let { ", Content-Base $it" } ?: ""),
        )
        return response.copy(headers = headers, body = body)
    }

    /** An absolute rtsp URL with the proxy's origin (path and query kept); anything else unchanged. */
    private fun toProxy(value: String): String {
        val uri = runCatching { URI(value) }.getOrNull()?.takeIf { it.scheme.equals("rtsp", ignoreCase = true) } ?: return value
        return origin + uri.rawPath.orEmpty() + (uri.rawQuery?.let { "?$it" } ?: "")
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private const val RTSP_PORT = 554
        private const val CONNECT_TIMEOUT_MS = 5_000
    }
}

/**
 * OPTIONS, then DESCRIBE with `Accept: application/sdp`, on one socket from [sockets], [timeoutMs] for connect and
 * each answer. Returns whether DESCRIBE answered 200 and the exchange as lines: `# url`, `> ` sent, `< ` received
 * (headers and SDP in full, `Authorization` masked), `! ` an error.
 */
internal fun rtspDescribe(url: String, sockets: SocketFactory, timeoutMs: Int = DESCRIBE_TIMEOUT_MS): Pair<Boolean, List<String>> {
    val lines = mutableListOf("# $url")
    var ok = false
    try {
        val uri = URI(url)
        sockets.createSocket().use { socket ->
            socket.connect(InetSocketAddress(uri.host, uri.port.takeIf { it > 0 } ?: 554), timeoutMs)
            socket.soTimeout = timeoutMs
            val input = BufferedInputStream(socket.getInputStream())
            listOf("OPTIONS", "DESCRIBE").forEachIndexed { i, method ->
                val headers = listOf("CSeq: ${i + 1}", "User-Agent: MyForthing") + if (method == "DESCRIBE") listOf("Accept: application/sdp") else emptyList()
                val request = RtspMessage("$method $url RTSP/1.0", headers)
                socket.getOutputStream().apply { write(request.bytes()); flush() }
                lines += request.lines().map { "> $it" }
                val response = readRtspMessage(input) ?: throw EOFException("connection closed")
                lines += response.lines().map { "< $it" }
                if (method == "DESCRIBE") ok = response.status == 200
            }
        }
    } catch (e: Exception) { // IOException, or a malformed URL
        lines += "! ${e.javaClass.simpleName}: ${e.message}"
    }
    return ok to lines
}

private const val DESCRIBE_TIMEOUT_MS = 5_000

/**
 * The raw DESCRIBE exchange for the live view's URLs (the recorder's own first, the traced one only if that fails), on
 * the recorder Wi-Fi of the Ready session; each try is noted under `rtsp`. Empty without such a session (also in
 * simulator mode). Used by Diagnose and once after Media3 rejected a description.
 */
suspend fun captureRtspDescribe(manager: RecorderConnectionManager): List<String> {
    val network = (manager.state.value as? RecorderConnectionState.Ready)?.network ?: return emptyList()
    val basic = manager.capabilities(CapabilityGroup.BASIC)?.let { runCatching { parseBasicCapabilities(it) }.getOrNull() }
    val lines = mutableListOf<String>()
    for (url in rtspCandidates(basic)) {
        val (ok, exchange) = withContext(Dispatchers.IO) { rtspDescribe(url, network.socketFactory) }
        manager.note(RecorderConnectionManagerImpl.RTSP_NOTES, "describe\n" + exchange.joinToString("\n"))
        lines += exchange
        if (ok) break
    }
    return lines
}
