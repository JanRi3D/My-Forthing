package me.ri3d.cam.dashcam

import android.net.Network
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import me.ri3d.cam.BuildConfig
import me.ri3d.cam.core.log.Log
import me.ri3d.cam.recorder.DeviceInfo
import me.ri3d.cam.recorder.ErrorCodes
import me.ri3d.cam.recorder.NormalInfo
import me.ri3d.cam.recorder.RecorderClient
import me.ri3d.cam.recorder.RecorderCommand
import me.ri3d.cam.recorder.RecorderDiagnostic
import me.ri3d.cam.recorder.RecorderError
import me.ri3d.cam.recorder.RecorderNotification
import me.ri3d.cam.recorder.RecorderReply
import me.ri3d.cam.recorder.RecorderResult
import me.ri3d.cam.recorder.RecorderTransport
import me.ri3d.cam.recorder.SessionCrypto
import me.ri3d.cam.recorder.SessionState
import me.ri3d.cam.recorder.SocketTransport
import me.ri3d.cam.recorder.StorageInfo
import me.ri3d.cam.recorder.parseDeviceInfo
import me.ri3d.cam.recorder.parseStorageInfo
import java.io.IOException
import java.net.Socket

/** CONTRACTS §7, plus [Disconnected] (idle: before the first connect and after [RecorderConnectionManager.disconnect]). */
sealed interface RecorderConnectionState {
    data object Disconnected : RecorderConnectionState

    /** No Wi-Fi network appeared within a few seconds, or the bound one was lost. */
    data object NoWifi : RecorderConnectionState

    /** Wi-Fi is up but its name does not start with FORTHING; a hint, `connect(ignoreSsid = true)` goes ahead. */
    data class WrongWifi(val ssid: String?) : RecorderConnectionState
    data object Connecting : RecorderConnectionState

    /** Socket open, no session: commands do not work yet. */
    data object TcpConnected : RecorderConnectionState
    data object Negotiating : RecorderConnectionState

    /**
     * [info] is the 4098 reply, null until it arrived (or when it failed). [network] is the Wi-Fi the control socket
     * is bound to; null only in simulator mode.
     */
    data class Ready(val info: DeviceInfo?, val session: SessionState.Ready, val network: Network?) : RecorderConnectionState
    data class Error(val error: RecorderError, val retry: Boolean) : RecorderConnectionState
}

/** [RecorderConnectionManager.httpClient] without a bound recorder Wi-Fi: nothing may go out over mobile data. */
class RecorderNotBoundException : IOException("recorder Wi-Fi network is not bound")

interface RecorderConnectionManager {
    val state: StateFlow<RecorderConnectionState>

    /** 16384 / 16385 (already acknowledged) / unmatched replies, straight from the client. */
    val notifications: SharedFlow<RecorderNotification>

    /** Latest `sdStatus` / `recStatus` notification of the current session, null until one arrived. */
    val sdStatus: StateFlow<NormalInfo.SdStatus?>
    val recStatus: StateFlow<NormalInfo.RecStatus?>

    /** 4099 read once per session right after 4098; null until it arrived or when it failed. */
    val storage: StateFlow<StorageInfo?>

    /** Wi-Fi network used for binding HTTP and RTSP; null when not bound. */
    val recorderNetwork: StateFlow<Network?>

    /** SSID seen at the last check ([refreshSsid], connect), null when unknown. */
    val ssid: StateFlow<String?>

    /** Debug builds only: the desktop simulator at 10.0.2.2:7878, without Wi-Fi check or binding. */
    val simulator: StateFlow<Boolean>
    fun setSimulator(enabled: Boolean)

    /** Requests the recorder Wi-Fi, checks the SSID hint, then connects and negotiates. Returns when settled. */
    suspend fun connect(ignoreSsid: Boolean = false)
    suspend fun disconnect()

    suspend fun <T> request(
        cmd: RecorderCommand,
        parse: (RecorderReply) -> T,
        timeoutMs: Long = RecorderClient.DEFAULT_REQUEST_TIMEOUT_MS,
    ): RecorderResult<T>

    /**
     * Bound to [recorderNetwork]; cleartext is allowed for 192.168.42.1 only (network_security_config).
     * Throws [RecorderNotBoundException] when no recorder Wi-Fi is bound (except in simulator mode).
     */
    fun httpClient(): OkHttpClient

    /** "http://192.168.42.1" + recorder path. */
    fun mediaUrl(recorderPath: String): String

    fun refreshSsid()
    fun mobileDataEnabled(): Boolean?

    /** Redacted frame and state log (last [RecorderConnectionManagerImpl.LOG_SIZE] events, session reply kept). */
    fun diagnosticLog(): List<RecorderDiagnostic>
}

/**
 * One [RecorderClient] for the app's lifetime. Wi-Fi binding: the network from [RecorderWifi.network]
 * (`requestNetwork`, never `bindProcessToNetwork`) is bound to every socket via `network.bindSocket`; without a
 * bound network nothing connects (outside simulator mode), so mobile data keeps serving everything else and is never
 * used to reach the recorder. The session lives while the app is in the foreground: [processObserver] disconnects
 * on process ON_STOP and reconnects on ON_START after a session was Ready. Connect, stop and network loss are
 * serialised by one mutex. Network loss stops the session ([RecorderConnectionState.NoWifi]); there is no
 * automatic reconnect loop, as in the original app.
 */
class RecorderConnectionManagerImpl(
    private val wifi: RecorderWifi,
    rsaKey: String,
    private val scope: CoroutineScope,
    private val newTransport: (Network?) -> RecorderTransport = ::boundSocketTransport,
    private val networkWaitMs: Long = NETWORK_WAIT_MS,
) : RecorderConnectionManager {

    private val keyError: RecorderError? = when {
        rsaKey.isBlank() -> RecorderError(ErrorCodes.SESSION_KEY_INVALID, RecorderError.Source.LOCAL, null, KEY_MISSING)
        runCatching { SessionCrypto.loadPrivateKey(rsaKey.toByteArray()) }.isFailure ->
            RecorderError(ErrorCodes.SESSION_KEY_INVALID, RecorderError.Source.LOCAL, null, KEY_INVALID)
        else -> null
    }

    private val log = ArrayDeque<RecorderDiagnostic>() // guarded by itself
    private var sessionReply: RecorderDiagnostic? = null // guarded by log; survives the ring buffer

    // Set by attempt() before start(): the socket is bound to exactly the network that attempt checked.
    @Volatile private var sessionNetwork: Network? = null
    @Volatile private var sessionSimulator = false

    private val client = RecorderClient(
        transportFactory = {
            when {
                sessionSimulator -> newTransport(null)
                else -> sessionNetwork?.let(newTransport) ?: NotBoundTransport
            }
        },
        rsaPrivateKey = rsaKey.toByteArray(),
        scope = scope,
        diagnostics = { event ->
            synchronized(log) {
                if (log.size == LOG_SIZE) log.removeFirst()
                log.addLast(event)
                if (event is RecorderDiagnostic.FrameReceived && event.seq == 1 && event.json?.let(SESSION_REPLY::containsMatchIn) == true) {
                    sessionReply = event
                }
            }
        },
    )

    private val _state = MutableStateFlow<RecorderConnectionState>(RecorderConnectionState.Disconnected)
    override val state: StateFlow<RecorderConnectionState> = _state.asStateFlow()
    override val notifications: SharedFlow<RecorderNotification> = client.notifications
    private val _sdStatus = MutableStateFlow<NormalInfo.SdStatus?>(null)
    override val sdStatus: StateFlow<NormalInfo.SdStatus?> = _sdStatus.asStateFlow()
    private val _recStatus = MutableStateFlow<NormalInfo.RecStatus?>(null)
    override val recStatus: StateFlow<NormalInfo.RecStatus?> = _recStatus.asStateFlow()
    private val _storage = MutableStateFlow<StorageInfo?>(null)
    override val storage: StateFlow<StorageInfo?> = _storage.asStateFlow()
    private val _network = MutableStateFlow<Network?>(null)
    override val recorderNetwork: StateFlow<Network?> = _network.asStateFlow()
    private val _ssid = MutableStateFlow<String?>(null)
    override val ssid: StateFlow<String?> = _ssid.asStateFlow()
    private val _simulator = MutableStateFlow(false)
    override val simulator: StateFlow<Boolean> = _simulator.asStateFlow()

    override fun setSimulator(enabled: Boolean) {
        _simulator.value = enabled && BuildConfig.DEBUG
    }

    /** Serialises connect starts, stops and network loss, so a stop in flight cannot tear down a newer attempt. */
    private val ops = Mutex()
    private val lock = Any()
    private var overlay: RecorderConnectionState? = RecorderConnectionState.Disconnected // guarded; null = follow the client
    private var starting = false // guarded; between clearing the overlay and start() returning
    private var attemptJob: Job? = null // guarded; the running connect attempt
    private var generation = 0 // guarded; bumped by connect and stop
    private var info: DeviceInfo? = null // guarded
    private var infoFor: SessionState.Ready? = null
    private var networkJob: Job? = null // guarded
    private var http: Pair<Network?, OkHttpClient>? = null // guarded

    /** True once a session was Ready and the user has not disconnected: ON_START then reconnects. */
    @Volatile private var wantConnected = false

    /** Registered on ProcessLifecycleOwner by [DashcamModule]. */
    val processObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            if (wantConnected) scope.launch { connect() }
        }

        override fun onStop(owner: LifecycleOwner) {
            scope.launch { stopSession(RecorderConnectionState.Disconnected) }
        }
    }

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.state.collect { s ->
                publish()
                if (s is SessionState.Ready && s !== infoFor) {
                    infoFor = s
                    wantConnected = true
                    launch { loadSessionInfo(s) }
                }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.notifications.collect { n ->
                when (val i = (n as? RecorderNotification.Normal)?.info) {
                    is NormalInfo.SdStatus -> _sdStatus.value = i
                    is NormalInfo.RecStatus -> _recStatus.value = i
                    else -> Unit
                }
            }
        }
    }

    /** A second call while an attempt runs joins that attempt instead of starting another one. */
    override suspend fun connect(ignoreSsid: Boolean) {
        val job = ops.withLock {
            synchronized(lock) {
                attemptJob?.takeIf { it.isActive } ?: run {
                    val s = _state.value
                    if (s is RecorderConnectionState.Ready || s == RecorderConnectionState.TcpConnected ||
                        s == RecorderConnectionState.Negotiating
                    ) return
                    val gen = ++generation
                    // The manager's scope: leaving the screen that asked for it does not cancel the connection.
                    scope.launch { attempt(gen, ignoreSsid) }.also { attemptJob = it }
                }
            }
        }
        job.join()
    }

    private suspend fun attempt(gen: Int, ignoreSsid: Boolean) {
        keyError?.let { return show(RecorderConnectionState.Error(it, retry = false)) }
        clearSessionValues()
        show(RecorderConnectionState.Connecting)
        val sim = _simulator.value
        var network: Network? = null
        if (!sim) {
            network = awaitNetwork()
            val ssid = wifi.currentSsid().also { _ssid.value = it }
            if (gen != synchronized(lock) { generation }) return
            if (network == null) return show(RecorderConnectionState.NoWifi)
            if (!ignoreSsid && ssid != null && !looksLikeRecorderSsid(ssid)) return show(RecorderConnectionState.WrongWifi(ssid))
        }
        client.stop() // clears a Failed state of an earlier attempt, so the published state cannot jump back to it
        synchronized(lock) {
            if (gen != generation) return
            sessionSimulator = sim
            sessionNetwork = network
            overlay = null
            starting = true
        }
        publish()
        try {
            if (sim) client.start(SIMULATOR_HOST, RecorderClient.DEFAULT_PORT) else client.start()
        } finally {
            synchronized(lock) { starting = false }
            publish()
        }
    }

    override suspend fun disconnect() {
        wantConnected = false
        stopSession(RecorderConnectionState.Disconnected)
    }

    private suspend fun stopSession(final: RecorderConnectionState) = ops.withLock {
        val running = synchronized(lock) {
            generation++
            attemptJob.also { attemptJob = null }
        }
        running?.cancelAndJoin()
        show(final)
        client.stop()
        clearSessionValues()
        synchronized(lock) {
            networkJob?.cancel()
            networkJob = null
        }
        _network.value = null
    }

    override suspend fun <T> request(cmd: RecorderCommand, parse: (RecorderReply) -> T, timeoutMs: Long): RecorderResult<T> =
        client.request(cmd, parse, timeoutMs)

    override fun httpClient(): OkHttpClient = synchronized(lock) {
        val network = _network.value
        if (network == null && !_simulator.value) throw RecorderNotBoundException()
        http?.takeIf { it.first == network }?.second ?: OkHttpClient.Builder().apply {
            if (network != null) {
                socketFactory(network.socketFactory)
                dns { host -> network.getAllByName(host).toList() }
            }
        }.build().also { http = network to it }
    }

    override fun mediaUrl(recorderPath: String): String =
        "http://${RecorderClient.DEFAULT_HOST}" + if (recorderPath.startsWith("/")) recorderPath else "/$recorderPath"

    override fun refreshSsid() {
        _ssid.value = wifi.currentSsid()
    }

    override fun mobileDataEnabled(): Boolean? = wifi.mobileDataEnabled()

    override fun diagnosticLog(): List<RecorderDiagnostic> = synchronized(log) {
        val events = log.toList()
        val reply = sessionReply
        if (reply != null && reply !in events) listOf(reply) + events else events
    }

    /** Starts the Wi-Fi request once (kept until disconnect / app stop) and waits briefly for a network. */
    private suspend fun awaitNetwork(): Network? {
        synchronized(lock) {
            if (networkJob?.isActive != true) {
                networkJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { wifi.network().collect(::onNetwork) }
            }
        }
        return withTimeoutOrNull(networkWaitMs) { _network.first { it != null } }
    }

    /** [RecorderWifi.network] emits null only when the current network is lost (a switch emits the new one). */
    private fun onNetwork(network: Network?) {
        val lost = network == null && _network.value != null
        _network.value = network
        if (lost) scope.launch { onNetworkLost() }
    }

    private suspend fun onNetworkLost() = ops.withLock {
        if (sessionSimulator || synchronized(lock) { overlay != null }) return@withLock // no session on that network
        Log.i(TAG, "recorder Wi-Fi lost, stopping the session")
        show(RecorderConnectionState.NoWifi)
        client.stop()
        clearSessionValues()
    }

    private suspend fun loadSessionInfo(session: SessionState.Ready) {
        val device = client.request(RecorderCommand.GetDeviceInfo, ::parseDeviceInfo)
        if (device is RecorderResult.Ok && client.state.value === session) {
            synchronized(lock) { info = device.value }
            publish()
        }
        val storage = client.request(RecorderCommand.GetStorageInfo(), ::parseStorageInfo)
        if (storage is RecorderResult.Ok && client.state.value === session) _storage.value = storage.value
    }

    private fun clearSessionValues() {
        synchronized(lock) { info = null }
        _sdStatus.value = null
        _recStatus.value = null
        _storage.value = null
    }

    private fun show(state: RecorderConnectionState) {
        synchronized(lock) { overlay = state }
        publish()
    }

    /** Recomputes the published state from the overlay and the client's current state (order-independent). */
    private fun publish() = synchronized(lock) {
        val next = overlay ?: when (val s = client.state.value) {
            SessionState.Idle -> if (starting) RecorderConnectionState.Connecting else RecorderConnectionState.Disconnected
            SessionState.Connecting -> RecorderConnectionState.Connecting
            SessionState.TcpConnected -> RecorderConnectionState.TcpConnected
            SessionState.Negotiating -> RecorderConnectionState.Negotiating
            is SessionState.Ready -> RecorderConnectionState.Ready(info, s, sessionNetwork.takeUnless { sessionSimulator })
            // A key that does not unwrap the recorder's aescode will not work on a second try either.
            is SessionState.Failed -> RecorderConnectionState.Error(s.reason, retry = s.reason.code != ErrorCodes.SESSION_KEY_INVALID)
        }
        if (next != _state.value) Log.d(TAG, "state $next")
        _state.value = next
    }

    /** Outside simulator mode without a network: fails like an unreachable recorder (-101), never connects unbound. */
    private object NotBoundTransport : RecorderTransport {
        override suspend fun connect(host: String, port: Int, timeoutMs: Int) = throw IOException("recorder Wi-Fi not bound")
        override suspend fun write(bytes: ByteArray) = throw IOException("not connected")
        override suspend fun read(buf: ByteArray): Int = -1
        override fun close() = Unit
    }

    companion object {
        private const val TAG = "RecorderConnection"
        const val NETWORK_WAIT_MS = 5_000L
        const val LOG_SIZE = 400
        private val SESSION_REPLY = Regex("\"msgId\"\\s*:\\s*1[,}\\s]")

        /** The emulator's alias for the development machine, where `:recorder:runSimulator` listens. */
        const val SIMULATOR_HOST = "10.0.2.2"

        /** [RecorderError.message] markers for the configuration error (no rsaKey in local.properties). */
        const val KEY_MISSING = "dashcam.rsaKey is not configured"
        const val KEY_INVALID = "dashcam.rsaKey could not be loaded"
    }
}

/**
 * Plain socket bound to the recorder [network] before connecting; if binding fails the socket is closed, so the
 * connect fails instead of going out over mobile data. A null [network] (simulator mode only) is an unbound socket.
 */
fun boundSocketTransport(network: Network?): RecorderTransport = SocketTransport {
    Socket().also { socket ->
        if (network != null) runCatching { network.bindSocket(socket) }.onFailure { socket.close() }
    }
}
