package to.axolotl.cam.recorder

/**
 * Hook for the app's diagnostic capture. Everything handed out here is already redacted: session tokens,
 * `aescode`, keys and passwords never leave this module in clear text. Called synchronously from the
 * client's coroutines; implementations must be cheap and must not block.
 */
fun interface RecorderDiagnostics {
    fun emit(event: RecorderDiagnostic)

    companion object {
        val NONE = RecorderDiagnostics { }
    }
}

sealed interface RecorderDiagnostic {
    val atMs: Long

    /** [hex]: frame as written (header + body; a plaintext body redacted). [json]: redacted logical body. */
    data class FrameSent(override val atMs: Long, val seq: Int, val hex: String, val json: String) : RecorderDiagnostic

    /** [json] is null when the body could not be decoded (see the following [Dropped]). */
    data class FrameReceived(override val atMs: Long, val seq: Int, val hex: String, val json: String?) : RecorderDiagnostic

    /** Bytes skipped while resynchronising on the next "FAAB" marker. */
    data class Garbage(override val atMs: Long, val hex: String) : RecorderDiagnostic

    data class Dropped(override val atMs: Long, val seq: Int, val reason: String) : RecorderDiagnostic

    /** State transitions, connect attempts and similar notes. */
    data class Info(override val atMs: Long, val message: String) : RecorderDiagnostic
}

/** Same field list as the app's `core/log` redaction (CONTRACTS §5), plus the session reply's `tokenNum`. */
object Redactor {
    private val SECRET = Regex(
        "\"(token|tokenNum|aescode|passwd|key|access_token|refresh_token|idToken)\"\\s*:\\s*" +
            "(\"(?:[^\"\\\\]|\\\\.)*\"|-?\\d+(?:\\.\\d+)?)",
    )

    fun redact(json: String): String = SECRET.replace(json) { "\"${it.groupValues[1]}\":\"***\"" }

    /** Hex of arbitrary bytes with any JSON secrets inside masked first (ISO-8859-1 round-trips every byte). */
    fun redactedHex(bytes: ByteArray): String = hex(redact(String(bytes, Charsets.ISO_8859_1)).toByteArray(Charsets.ISO_8859_1))

    /** Wire frame as hex: the 12-byte header unchanged, then the (redacted) body. */
    fun frameHex(seq: Int, body: ByteArray): String =
        hex(FrameCodec.encode(seq, body).copyOf(FrameCodec.HEADER_SIZE)) + " " + redactedHex(body)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
