package me.ri3d.dashcam.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ri3d.dashcam.dashcam.RecorderConnectionManager
import me.ri3d.dashcam.dashcam.RecorderConnectionManagerImpl
import me.ri3d.dashcam.dashcam.RecorderConnectionState
import me.ri3d.dashcam.recorder.CapabilityGroup
import me.ri3d.dashcam.recorder.parseBasicCapabilities
import java.io.BufferedInputStream
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.SocketFactory
import kotlin.concurrent.thread

/*
 * RTSP plumbing for the recorder's preview. Hardware 2026-10-02: the recorder answers DESCRIBE, but its SDP has no
 * `a=control` in the media section(s), which Media3 1.11 requires (`RtspMediaTrack`: "missing attribute control").
 * Media3 has no option to accept that (RtspMediaSource.Factory offers only TCP, user agent, socket factory, debug
 * logging, timeout), so [RtspSdpProxy] repairs the description on the way.
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
 * [kept]: the media lines left in the description, [dropped]: the removed ones, [injected]: how many got `a=control:*`.
 */
internal data class SdpRewrite(val sdp: String, val kept: List<String>, val dropped: List<String>, val injected: Int)

/**
 * Makes the recorder's SDP acceptable to Media3: only the first `m=video` section is kept (live sound is muted anyway,
 * and a second `*` track would SETUP the same URL twice); without a video section every section stays. A kept section
 * without a control attribute gets `a=control:*` – the aggregate (session) URL, which RFC 2326 C.1.1 prescribes when
 * there is no per-track control; a control attribute in other letter case is written as `a=control` (Media3 matches it
 * exactly). Session-level lines are untouched; lines end with CRLF.
 */
internal fun rewriteSdp(sdp: String): SdpRewrite {
    val lines = sdp.lines().map { it.trimEnd('\r') }.filter { it.isNotBlank() }
    val session = lines.takeWhile { !it.startsWith("m=") }
    val sections = lines.drop(session.size).fold(mutableListOf<MutableList<String>>()) { acc, line ->
        if (line.startsWith("m=")) acc += mutableListOf(line) else acc.last() += line
        acc
    }
    val keep = sections.firstOrNull { it.first().startsWith("m=video") }?.let(::listOf) ?: sections
    val control = Regex("^a=control(?=:|$)", RegexOption.IGNORE_CASE)
    var injected = 0
    val media = keep.flatMap { section ->
        if (section.any(control::containsMatchIn)) {
            section.map { control.replace(it, "a=control") }
        } else {
            injected++
            section + "a=control:*"
        }
    }
    return SdpRewrite(
        sdp = (session + media).joinToString("") { "$it\r\n" },
        kept = keep.map { it.first() },
        dropped = sections.filter { s -> keep.none { it === s } }.map { it.first() },
        injected = injected,
    )
}

/**
 * Local RTSP proxy for one recorder URL ([target]), so Media3 accepts the recorder's description. Listens on
 * `127.0.0.1:<ephemeral>`; Media3 plays [url]. Each connection Media3 opens gets one socket to the recorder from
 * [sockets] (bound to the recorder Wi-Fi). Requests are forwarded verbatim except their request line
 * (`rtsp://127.0.0.1:<port>/…` → the recorder's origin). Responses are forwarded verbatim except the DESCRIBE answer:
 * its SDP goes through [rewriteSdp], Content-Length is recomputed and Content-Base / Content-Location point to the
 * proxy. After the PLAY answer (or as soon as an interleaved `$` frame arrives) the recorder's side is piped raw;
 * the phone's side stays message-aware, so keep-alives and TEARDOWN still reach the recorder's URL. Every step is
 * reported to [note] for the Diagnose export.
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
    private val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
    private val origin = "rtsp://127.0.0.1:${server.localPort}"
    private val open = CopyOnWriteArrayList<Socket>()

    @Volatile private var closed = false

    /** What Media3 plays: the recorder's path and query on the proxy. */
    val url: String = origin + targetUri.rawPath.orEmpty() + (targetUri.rawQuery?.let { "?$it" } ?: "")

    init {
        thread(isDaemon = true, name = "rtsp-proxy") {
            while (!closed) {
                val client = try { server.accept() } catch (e: IOException) { break }
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
        } catch (e: IOException) {
            // Closed by either side.
        } finally {
            closeBoth()
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
                request.header("CSeq")?.let { methods[it] = request.startLine.substringBefore(' ') }
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
        val rewrite = rewriteSdp(String(response.body, Charsets.ISO_8859_1))
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
 * the recorder Wi-Fi of the Ready session; each try is noted under `rtsp`. Empty without such a session (simulator
 * mode has no RTSP server). Used by Diagnose and once after Media3 rejected a description.
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
