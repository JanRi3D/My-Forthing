package me.ri3d.dashcam.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ri3d.dashcam.dashcam.RecorderConnectionManager
import me.ri3d.dashcam.dashcam.RecorderConnectionManagerImpl
import me.ri3d.dashcam.dashcam.RecorderConnectionState
import me.ri3d.dashcam.recorder.CapabilityGroup
import me.ri3d.dashcam.recorder.parseBasicCapabilities
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.URI
import javax.net.SocketFactory

/*
 * RTSP plumbing for the recorder's preview. Hardware 2026-10-02: the recorder answers DESCRIBE, but its SDP has no
 * `a=control` in the media section(s), which Media3 1.11 requires (`RtspMediaTrack`: "missing attribute control").
 * The raw DESCRIBE exchange goes into the Diagnose export so the next step can be decided on the real SDP.
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

private const val MAX_LINE = 8 * 1024
private const val MAX_BODY = 256 * 1024

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
