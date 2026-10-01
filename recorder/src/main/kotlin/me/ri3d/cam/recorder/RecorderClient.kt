package me.ri3d.cam.recorder

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.ri3d.cam.recorder.RecorderError.Source.LOCAL
import me.ri3d.cam.recorder.RecorderError.Source.RECORDER
import me.ri3d.cam.recorder.RecorderError.Source.TIMEOUT
import java.security.PrivateKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Time source for diagnostics timestamps; tests pass the virtual scheduler time. All timers use `delay`. */
fun interface Clock {
    fun nowMs(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

sealed interface SessionState {
    data object Idle : SessionState
    data object Connecting : SessionState

    /** TCP is up, no session yet: not usable for commands (the original app navigated on this alone). */
    data object TcpConnected : SessionState

    /** Session start sent, waiting for token and key. */
    data object Negotiating : SessionState

    data class Ready(val token: Int, val version: String?, val productType: Int?, val timeoutSec: Int?) : SessionState {
        override fun toString() = "Ready(token=***, version=$version, productType=$productType, timeoutSec=$timeoutSec)"
    }

    data class Failed(val reason: RecorderError) : SessionState
}

/**
 * Control-channel client with the traced lifecycle: 3 s connect with one immediate retry, plaintext session
 * start with the 5 s session deadline, keepalive about 4 s after key initialisation and then every 4 s,
 * disconnect after more than 10 s without a successful keepalive, disconnect on a received msgId 2.
 * Duplicate [start] calls while connecting or connected are ignored. All coroutines run in [scope]; cancelling
 * [scope] closes the connection like [stop].
 *
 * Requests time out after `timeoutMs` (default [DEFAULT_REQUEST_TIMEOUT_MS], local code -205). The SDK has no
 * request timeout; without one, a command the recorder ignores would wait forever while keepalives succeed.
 * A late reply to a timed-out request arrives as [RecorderNotification.Unmatched].
 */
class RecorderClient(
    private val transportFactory: RecorderTransportFactory,
    rsaPrivateKey: ByteArray,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.SYSTEM,
    private val diagnostics: RecorderDiagnostics = RecorderDiagnostics.NONE,
) {
    private val rsaKey: PrivateKey? = try { SessionCrypto.loadPrivateKey(rsaPrivateKey) } catch (e: Exception) { null }

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    // ponytail: a collector more than 64 notifications behind loses the oldest; the socket reader never waits on UI.
    private val _notifications =
        MutableSharedFlow<RecorderNotification>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 16384 normal + 16385 events (already acknowledged) + [RecorderNotification.Unmatched] replies. */
    val notifications: SharedFlow<RecorderNotification> = _notifications.asSharedFlow()

    private val lock = Any()
    private var connection: Connection? = null // guarded by lock
    private var generation = 0 // guarded by lock; bumped by every start and stop

    /** Connects and negotiates; returns once the state is [SessionState.Ready] or [SessionState.Failed]. */
    suspend fun start(host: String = DEFAULT_HOST, port: Int = DEFAULT_PORT) {
        val gen = synchronized(lock) {
            when (val s = _state.value) {
                SessionState.Connecting, SessionState.TcpConnected, SessionState.Negotiating, is SessionState.Ready -> {
                    note("start ignored, already $s")
                    return
                }
                else -> {
                    setState(SessionState.Connecting)
                    ++generation
                }
            }
        }
        try {
            val key = rsaKey ?: return failStart(gen, RecorderError(ErrorCodes.SESSION_KEY_INVALID, LOCAL, null, "RSA private key missing or invalid"))
            val transport = connectWithRetry(host, port)
                ?: return failStart(gen, RecorderError(ErrorCodes.CONNECT_FAILED, LOCAL, null, "TCP connect failed"))
            val c = synchronized(lock) {
                if (gen != generation) { // stopped while connecting
                    transport.close()
                    return
                }
                Connection(transport).also {
                    connection = it
                    setState(SessionState.TcpConnected)
                }
            }
            c.connScope.launch { c.readLoop() }
            c.negotiate(key)
        } catch (e: CancellationException) {
            cancelSession(gen)
            throw e
        }
    }

    /** Disconnects directly, as the original app does on exit (it never sends msgId 2). */
    suspend fun stop() = cancelSession(null)

    /**
     * Sends an encrypted command and returns the sequence-matched reply, whatever its rval. Throws
     * [RecorderException] when not ready, on write failure, disconnect or after [timeoutMs] (code -205).
     * [RecorderCommand.StopSession] stops the heartbeat first (as traced) and always closes the session afterwards.
     */
    suspend fun send(cmd: RecorderCommand, timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS): RecorderReply {
        require(cmd !is RecorderCommand.StartSession) { "the session start is sent by start()" }
        val c = synchronized(lock) { connection.takeIf { _state.value is SessionState.Ready } }
            ?: throw RecorderException(RecorderError(ErrorCodes.SEND_FAILED, LOCAL, null, "session not ready"))
        if (cmd !is RecorderCommand.StopSession) return c.send(cmd, timeoutMs)
        c.heartbeat?.cancel()
        try {
            return c.send(cmd, timeoutMs)
        } finally {
            c.close(SessionState.Idle) // without a heartbeat nothing else would notice a recorder ignoring msgId 2
        }
    }

    /**
     * [send] plus the traced success rule (rval == 0; a missing rval reads as -1 and fails) and a typed parse.
     * Raise [timeoutMs] for slow operations (e.g. a burst photo or formatting).
     */
    suspend fun <T> request(
        cmd: RecorderCommand,
        parse: (RecorderReply) -> T,
        timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    ): RecorderResult<T> {
        val reply = try { send(cmd, timeoutMs) } catch (e: RecorderException) { return RecorderResult.Failed(e.error) }
        if (reply.rval != 0) return RecorderResult.Failed(RecorderError(reply.rval, RECORDER, reply.rawJson, null))
        return try {
            RecorderResult.Ok(parse(reply), reply)
        } catch (e: Exception) {
            RecorderResult.Failed(RecorderError(ErrorCodes.BAD_REPLY, LOCAL, reply.rawJson, "unparsable reply (${e.javaClass.simpleName})"))
        }
    }

    private suspend fun connectWithRetry(host: String, port: Int): RecorderTransport? {
        // ponytail: the SDK never resets its retry counter after a success, so later sessions may get no retry;
        // here every start() gets the intended two attempts.
        repeat(CONNECT_ATTEMPTS) { attempt ->
            val t = transportFactory.create()
            try {
                t.connect(host, port, CONNECT_TIMEOUT_MS)
                return t
            } catch (e: CancellationException) {
                t.close()
                throw e
            } catch (e: Exception) {
                t.close()
                note("connect attempt ${attempt + 1}/$CONNECT_ATTEMPTS failed: ${e.javaClass.simpleName}")
            }
        }
        return null
    }

    private fun cancelSession(onlyGeneration: Int?) {
        val c = synchronized(lock) {
            if (onlyGeneration != null && onlyGeneration != generation) return
            generation++
            connection ?: run { setState(SessionState.Idle); null }
        }
        c?.close(SessionState.Idle)
    }

    private fun failStart(gen: Int, error: RecorderError) {
        synchronized(lock) { if (gen == generation) setState(SessionState.Failed(error)) }
    }

    private fun setState(s: SessionState) {
        _state.value = s
        note("state $s")
    }

    private fun note(message: String) = diag { RecorderDiagnostic.Info(it, message) }

    private fun diag(event: (Long) -> RecorderDiagnostic) {
        if (diagnostics === RecorderDiagnostics.NONE) return
        try { diagnostics.emit(event(clock.nowMs())) } catch (e: Exception) { /* diagnostics must never break the session */ }
    }

    /** One TCP connection and the session on it. Closing it is final; a new start() builds a new one. */
    private inner class Connection(val transport: RecorderTransport) {
        val connScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<RecorderReply>>()
        private val writeLock = Mutex()
        private val closed = AtomicBoolean(false)
        private val beatTime = AtomicInteger(0)
        private var outSeq = 0 // guarded by writeLock; the session start therefore goes out as sequence 1

        @Volatile private var token = 0
        @Volatile private var aesKey: String? = null
        @Volatile var heartbeat: Job? = null

        init {
            // Cancelling the client's scope without stop() must still close the socket and fail pending requests.
            // ATOMIC: runs the finally even if the scope is already cancelled, and only after this connection is
            // registered (dispatched), so close() can update the state.
            @OptIn(DelicateCoroutinesApi::class)
            connScope.launch(start = CoroutineStart.ATOMIC) {
                try { awaitCancellation() } finally { close(SessionState.Idle) }
            }
        }

        suspend fun negotiate(key: PrivateKey) {
            val reply = CompletableDeferred<RecorderReply>()
            val timeout = RecorderError(ErrorCodes.SESSION_TIMEOUT, TIMEOUT, null, "session start not answered within $SESSION_MONITOR_MS ms")
            // The SDK runs two monitors with the same 5 s deadline: SessionApi's reports the synthetic result 4096,
            // DashcamApi's disconnects. One timer does both: close() fails the pending reply with 4096.
            val monitor = connScope.launch { delay(SESSION_MONITOR_MS); close(SessionState.Failed(timeout)) }
            val r = try {
                write(RecorderCommand.StartSession.body(0), encrypt = false, pendingReply = reply)
                setIfCurrent(SessionState.Negotiating)
                reply.await()
            } catch (e: RecorderException) {
                return close(SessionState.Failed(e.error))
            } finally {
                monitor.cancel()
            }
            // The plaintext session reply holds aescode and tokenNum: errors keep only a redacted copy.
            if (r.rval != 0) {
                return close(SessionState.Failed(RecorderError(r.rval, RECORDER, Redactor.redact(r.rawJson), "session start rejected")))
            }
            val p = r.param.asObject()
            val hexKey = p.str("aescode")?.let { runCatching { SessionCrypto.unwrapAesKey(it, key) }.getOrNull() }
            if (hexKey == null || !SessionCrypto.isUsableKey(hexKey)) {
                return close(SessionState.Failed(RecorderError(ErrorCodes.SESSION_KEY_INVALID, LOCAL, Redactor.redact(r.rawJson), "session key missing or not decryptable")))
            }
            token = p.int("tokenNum") ?: 0
            aesKey = hexKey
            if (setIfCurrent(SessionState.Ready(token, p.str("version"), p.int("productType"), p.int("timeOut")))) {
                heartbeat = connScope.launch { heartbeatLoop() }
            }
        }

        /** The SDK's HeartBeatThread, tick for tick. The recorder-returned timeOut is parsed but, as traced, unused. */
        private suspend fun heartbeatLoop() = coroutineScope {
            var tick = 0
            beatTime.set(0)
            while (true) {
                if (tick == KEEPALIVE_EVERY_TICKS) {
                    launch { if (keepAliveSucceeded()) beatTime.set(0) } // failures leave beatTime running
                    tick = 0
                }
                tick++
                beatTime.incrementAndGet()
                delay(HEARTBEAT_TICK_MS)
                if (beatTime.get() > HEARTBEAT_LOSS_TICKS) {
                    close(SessionState.Failed(RecorderError(ErrorCodes.HEARTBEAT_LOST, TIMEOUT, null, "no successful keepalive for more than $HEARTBEAT_LOSS_TICKS ticks")))
                    return@coroutineScope
                }
            }
        }

        private suspend fun keepAliveSucceeded() =
            try { send(RecorderCommand.KeepAlive, DEFAULT_REQUEST_TIMEOUT_MS).rval == 0 } catch (e: RecorderException) { false }

        suspend fun send(cmd: RecorderCommand, timeoutMs: Long): RecorderReply {
            val reply = CompletableDeferred<RecorderReply>()
            val seq = write(cmd.body(token), encrypt = true, pendingReply = reply)
            try {
                return withTimeoutOrNull(timeoutMs) { reply.await() }
                    ?: throw RecorderException(RecorderError(ErrorCodes.REQUEST_TIMEOUT, TIMEOUT, null, "no reply to msgId ${cmd.msgId} within $timeoutMs ms"))
            } finally {
                pending.remove(seq)
            }
        }

        /** Takes the next sequence and writes one frame; replies to it complete [pendingReply]. */
        private suspend fun write(json: String, encrypt: Boolean, pendingReply: CompletableDeferred<RecorderReply>?): Int =
            writeLock.withLock {
                val seq = ++outSeq
                if (pendingReply != null) pending[seq] = pendingReply
                if (closed.get()) { // checked after registering, so close() either fails the entry or we do
                    pending.remove(seq)
                    throw RecorderException(RecorderError(ErrorCodes.SEND_FAILED, LOCAL, null, "not connected"))
                }
                val body = (if (encrypt) SessionCrypto.encrypt(json, checkNotNull(aesKey)) else json).toByteArray(Charsets.UTF_8)
                diag { RecorderDiagnostic.FrameSent(it, seq, Redactor.frameHex(seq, body), Redactor.redact(json)) }
                try {
                    transport.write(FrameCodec.encode(seq, body))
                } catch (e: CancellationException) {
                    pending.remove(seq)
                    throw e
                } catch (e: Exception) {
                    pending.remove(seq)
                    close(SessionState.Failed(RecorderError(ErrorCodes.DISCONNECTED, LOCAL, null, "write failed (${e.javaClass.simpleName})")))
                    throw RecorderException(RecorderError(ErrorCodes.SEND_FAILED, LOCAL, null, "write failed"))
                }
                seq
            }

        suspend fun readLoop() {
            val decoder = FrameCodec.Decoder { garbage -> diag { RecorderDiagnostic.Garbage(it, Redactor.redactedHex(garbage)) } }
            val buf = ByteArray(8192)
            try {
                while (true) {
                    val n = transport.read(buf)
                    if (n < 0) break
                    for (frame in decoder.feed(buf, 0, n)) onFrame(frame)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                note("read failed: ${e.javaClass.simpleName}")
            }
            close(SessionState.Failed(RecorderError(ErrorCodes.DISCONNECTED, LOCAL, null, "connection closed")))
        }

        private suspend fun onFrame(frame: Frame) {
            val json = decode(frame.body)
            diag { RecorderDiagnostic.FrameReceived(it, frame.seq, Redactor.frameHex(frame.seq, frame.body), json?.let(Redactor::redact)) }
            val reply = json?.let(RecorderReply::parse) ?: return diag {
                RecorderDiagnostic.Dropped(it, frame.seq, if (json == null) "body could not be decoded" else "no JSON object with msgId")
            }
            when (reply.msgId) {
                RecorderNotification.MSG_NORMAL -> _notifications.tryEmit(parseNormalNotification(reply))
                RecorderNotification.MSG_EVENT -> {
                    // Acknowledge before dispatching, with a fresh sequence, even without listeners (as traced).
                    if (aesKey != null) {
                        try { write(eventAckBody(token), encrypt = true, pendingReply = null) } catch (e: RecorderException) { /* closing */ }
                    }
                    _notifications.tryEmit(parseEventNotification(reply))
                }
                RecorderCommand.StopSession.msgId -> {
                    // A received 2 disconnects before its result is handled (as traced).
                    val requested = pending.remove(frame.seq)
                    close(
                        if (requested != null) SessionState.Idle
                        else SessionState.Failed(RecorderError(ErrorCodes.DISCONNECTED, LOCAL, reply.rawJson, "recorder ended the session")),
                    )
                    requested?.complete(reply)
                }
                else -> {
                    val waiting = pending.remove(frame.seq)
                    when {
                        waiting != null -> waiting.complete(reply)
                        reply.msgId == RecorderCommand.StartSession.msgId -> diag { RecorderDiagnostic.Dropped(it, frame.seq, "unexpected session reply") }
                        else -> _notifications.tryEmit(RecorderNotification.Unmatched(frame.seq, reply))
                    }
                }
            }
        }

        /** Plain JSON (the session reply; the SDK keys this on sequence 1) or Base64 ciphertext, which never holds '{'. */
        private fun decode(body: ByteArray): String? {
            val text = String(body, Charsets.UTF_8)
            if (text.trimStart().startsWith("{")) return text
            val key = aesKey ?: return null
            return try { SessionCrypto.decrypt(text, key) } catch (e: Exception) { null }
        }

        private fun setIfCurrent(s: SessionState): Boolean = synchronized(lock) {
            (connection === this && !closed.get()).also { if (it) setState(s) }
        }

        fun close(final: SessionState) {
            if (!closed.compareAndSet(false, true)) return
            try { transport.close() } catch (e: Exception) { /* already gone */ }
            val error = (final as? SessionState.Failed)?.reason ?: RecorderError(ErrorCodes.DISCONNECTED, LOCAL, null, "session stopped")
            pending.values.forEach { it.completeExceptionally(RecorderException(error)) }
            pending.clear()
            synchronized(lock) {
                if (connection === this) {
                    connection = null
                    setState(final)
                }
            }
            connScope.cancel()
        }
    }

    companion object {
        const val DEFAULT_HOST = "192.168.42.1"
        const val DEFAULT_PORT = 7878
        const val CONNECT_TIMEOUT_MS = 3000
        const val CONNECT_ATTEMPTS = 2 // one immediate retry, no backoff
        const val SESSION_MONITOR_MS = 5000L
        const val HEARTBEAT_TICK_MS = 1000L
        const val KEEPALIVE_EVERY_TICKS = 4 // first keepalive ~4 s after key initialisation, then every 4 ticks
        const val HEARTBEAT_LOSS_TICKS = 10 // disconnect when beatTime > 10, ~11 s after the last successful keepalive
        const val DEFAULT_REQUEST_TIMEOUT_MS = 10_000L // not traced; see class KDoc
    }
}
