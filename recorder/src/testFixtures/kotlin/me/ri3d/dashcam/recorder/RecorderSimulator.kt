package me.ri3d.dashcam.recorder

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher

/**
 * Test fixture (`testFixtures(project(":recorder"))`), not part of the production artifact.
 *
 * In-memory recorder speaking the real wire format: FAAB frames, plaintext session start, RSA-wrapped session
 * key, AES-128-ECB zero-padded Base64 bodies. It generates its own 1024-bit RSA key pair; give
 * [privateKeyPkcs8] to [RecorderClient]. This is NOT the vendor key: a physical recorder will not accept it.
 *
 * Use one instance as the transport for every attempt (`RecorderTransportFactory { sim }`); [connect] resets it.
 * Replies carry the request's sequence and are encrypted (the session reply is plaintext). Fault injection:
 * [failConnectAttempts], [connectDelayMs], [failWrites], [fragmentSize], [replyDelayMs], [rvalOverrides],
 * [silentMsgIds]; unsolicited traffic via [inject] / [injectRaw].
 */
class RecorderSimulator(
    val sessionKeyHex: String = "00112233445566778899aabbccddeeff",
    val token: Int = 123,
    keyPair: KeyPair = generateKeyPair(),
) : RecorderTransport {
    val privateKeyPkcs8: ByteArray = keyPair.private.encoded
    private val publicKey = keyPair.public

    /** The first N connect calls fail. */
    @Volatile var failConnectAttempts = 0

    /** Time each connect call takes; above the client's timeout the attempt times out at exactly that timeout. */
    @Volatile var connectDelayMs = 0L

    /** Every write throws, like a broken socket. */
    @Volatile var failWrites = false

    /** > 0: every outgoing frame is delivered in chunks of this many bytes. */
    @Volatile var fragmentSize = 0

    /** Delay before each delivered chunk (virtual time under kotlinx-coroutines-test). */
    @Volatile var replyDelayMs = 0L

    /** msgId → rval to answer with instead of the scripted one. */
    val rvalOverrides = ConcurrentHashMap<Int, Int>()

    /** msgIds that are never answered, e.g. 3 for heartbeat loss or 1 for a silent session start. */
    val silentMsgIds: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** msgId → reply body (plain JSON); default `{"rval":0,"msgId":<id>}`. */
    val replies = ConcurrentHashMap<Int, String>()

    /** msgId → reply computed from the request (e.g. 4100 paging by cursor, see [SimulatedFiles]); wins over [replies]. */
    val handlers = ConcurrentHashMap<Int, (RecorderReply) -> String>()

    /** Every request received, decrypted, in order. */
    val received = CopyOnWriteArrayList<Received>()

    data class Received(val seq: Int, val json: String, val encrypted: Boolean) {
        val msgId: Int? get() = RecorderReply.parse(json)?.msgId
    }

    var connectAttempts = 0
        private set

    @Volatile var closed = true
        private set

    private var outbox = Channel<ByteArray>(Channel.UNLIMITED)
    private var decoder = FrameCodec.Decoder()
    private var chunk = ByteArray(0)
    private var chunkPos = 0

    override suspend fun connect(host: String, port: Int, timeoutMs: Int) {
        connectAttempts++
        if (connectDelayMs > timeoutMs) {
            delay(timeoutMs.toLong())
            throw SocketTimeoutException("simulated connect timeout")
        }
        if (connectDelayMs > 0) delay(connectDelayMs)
        if (connectAttempts <= failConnectAttempts) throw IOException("simulated connect failure")
        outbox = Channel(Channel.UNLIMITED)
        decoder = FrameCodec.Decoder()
        chunk = ByteArray(0)
        chunkPos = 0
        closed = false
    }

    override suspend fun write(bytes: ByteArray) {
        if (closed || failWrites) throw IOException("simulated write failure")
        for (frame in decoder.feed(bytes)) answer(frame)
    }

    override suspend fun read(buf: ByteArray): Int {
        if (chunkPos >= chunk.size) {
            chunk = outbox.receiveCatching().getOrNull() ?: return -1
            chunkPos = 0
            if (replyDelayMs > 0) delay(replyDelayMs)
        }
        val n = minOf(buf.size, chunk.size - chunkPos)
        System.arraycopy(chunk, chunkPos, buf, 0, n)
        chunkPos += n
        return n
    }

    /** Client side close, or call it to simulate the recorder dropping the connection. */
    override fun close() {
        closed = true
        outbox.close()
    }

    /** Sends an unsolicited message, e.g. a 16384 notification or a 16385 event. */
    fun inject(json: String, seq: Int = 0, encrypt: Boolean = true) = enqueue(seq, json, encrypt)

    /** Sends raw bytes (garbage, hand-built frames). */
    fun injectRaw(bytes: ByteArray) {
        outbox.trySend(bytes)
    }

    /** The plaintext session reply with `aescode` = RSA(public key, [sessionKeyHex]). */
    fun sessionReply(): String = buildJsonObject {
        put("rval", 0)
        put("msgId", 1)
        putJsonObject("param") {
            put("tokenNum", token)
            put("version", "SIM")
            put("productType", 0)
            put("aescode", wrapSessionKey())
            put("timeOut", 10)
        }
    }.toString()

    fun wrapSessionKey(): String {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return Base64.getEncoder().encodeToString(cipher.doFinal(sessionKeyHex.toByteArray(Charsets.UTF_8)))
    }

    private fun answer(frame: Frame) {
        val text = String(frame.body, Charsets.UTF_8)
        val encrypted = !text.startsWith("{")
        val json = if (encrypted) SessionCrypto.decrypt(text, sessionKeyHex) else text
        received += Received(frame.seq, json, encrypted)
        val request = RecorderReply.parse(json) ?: return
        val msgId = request.msgId
        if (msgId in silentMsgIds || msgId == RecorderNotification.MSG_EVENT) return // event acks get no answer
        var reply = if (msgId == 1) sessionReply() else handlers[msgId]?.invoke(request) ?: replies[msgId] ?: """{"rval":0,"msgId":$msgId}"""
        rvalOverrides[msgId]?.let { rval ->
            val o = Json.parseToJsonElement(reply).jsonObject
            reply = JsonObject(o + ("rval" to JsonPrimitive(rval))).toString()
        }
        enqueue(frame.seq, reply, encrypt = msgId != 1)
    }

    private fun enqueue(seq: Int, json: String, encrypt: Boolean) {
        val body = if (encrypt) SessionCrypto.encrypt(json, sessionKeyHex) else json
        val bytes = FrameCodec.encode(seq, body.toByteArray(Charsets.UTF_8))
        val size = fragmentSize
        if (size <= 0) outbox.trySend(bytes)
        else bytes.toList().chunked(size).forEach { outbox.trySend(it.toByteArray()) }
    }

    companion object {
        fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
    }
}
