package to.axolotl.cam.dashcam

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
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import to.axolotl.cam.BuildConfig
import to.axolotl.cam.core.log.Log
import to.axolotl.cam.recorder.DeviceInfo
import to.axolotl.cam.recorder.ErrorCodes
import to.axolotl.cam.recorder.NormalInfo
import to.axolotl.cam.recorder.RecorderClient
import to.axolotl.cam.recorder.RecorderCommand
import to.axolotl.cam.recorder.RecorderDiagnostic
import to.axolotl.cam.recorder.RecorderError
import to.axolotl.cam.recorder.RecorderNotification
import to.axolotl.cam.recorder.RecorderReply
import to.axolotl.cam.recorder.RecorderResult
import to.axolotl.cam.recorder.RecorderTransport
import to.axolotl.cam.recorder.SessionCrypto
import to.axolotl.cam.recorder.SessionState
import to.axolotl.cam.recorder.SocketTransport
import to.axolotl.cam.recorder.parseDeviceInfo
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

    /** [info] is the 4098 reply, null until it arrived (or when it failed). [network] is null in simulator mode. */
    data class Ready(val info: DeviceInfo?, val session: SessionState.Ready, val network: Network?) : RecorderConnectionState
    data class Error(val error: RecorderError, val retry: Boolean) : RecorderConnectionState
}

interface RecorderConnectionManager {
    val state: StateFlow<RecorderConnectionState>

    /** 16384 / 16385 (already acknowledged) / unmatched replies, straight from the client. */
    val notifications: SharedFlow<RecorderNotification>

    /** Latest `sdStatus` / `recStatus` notification of the current session, null until one arrived. */
    val sdStatus: StateFlow<NormalInfo.SdStatus?>
    val recStatus: StateFlow<NormalInfo.RecStatus?>

    /** Wi-Fi network used for binding the control socket, HTTP and RTSP; null when not bound. */
    val recorderNetwork: StateFlow<Network?>

    /** SSID seen at the last check ([refreshSsid], connect), null when unknown. */
    val ssid: StateFlow<String?>

    /** Debug builds only: talk to the desktop simulator at 10.0.2.2:7878 without Wi-Fi check or binding. */
    var simulator: Boolean

    /** Requests the recorder Wi-Fi, checks the SSID hint, then connects and negotiates. Returns when settled. */
    suspend fun connect(ignoreSsid: Boolean = false)
    suspend fun disconnect()

    suspend fun <T> request(
        cmd: RecorderCommand,
        parse: (RecorderReply) -> T,
        timeoutMs: Long = RecorderClient.DEFAULT_REQUEST_TIMEOUT_MS,
    ): RecorderResult<T>

    /** Bound to [recorderNetwork]; cleartext is allowed for 192.168.42.1 only (network_security_config). */
    fun httpClient(): OkHttpClient

    /** "http://192.168.42.1" + recorder path. */
    fun mediaUrl(recorderPath: String): String

    fun refreshSsid()
    fun mobileDataEnabled(): Boolean?

    /** Redacted frame and state log of the client (last [RecorderConnectionManagerImpl.LOG_SIZE] events). */
    fun diagnosticLog(): List<RecorderDiagnostic>
}

/**
 * One [RecorderClient] for the app's lifetime. Wi-Fi binding: the network from [RecorderWifi.network]
 * (`requestNetwork`, never `bindProcessToNetwork`) is bound to every socket via `network.bindSocket`, so mobile
 * data keeps working for everything else. The session lives while the app is in the foreground:
 * [processObserver] disconnects on process ON_STOP and reconnects on ON_START if the user had connected.
 * Network loss stops the session (state [RecorderConnectionState.NoWifi]); there is no automatic reconnect loop,
 * as in the original app.
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

    private val log = ArrayDeque<RecorderDiagnostic>()

    private val client = RecorderClient(
        transportFactory = { newTransport(if (simulator) null else _network.value) },
        rsaPrivateKey = rsaKey.toByteArray(),
        scope = scope,
        diagnostics = { event ->
            synchronized(log) {
                if (log.size == LOG_SIZE) log.removeFirst()
                log.addLast(event)
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
    private val _network = MutableStateFlow<Network?>(null)
    override val recorderNetwork: StateFlow<Network?> = _network.asStateFlow()
    private val _ssid = MutableStateFlow<String?>(null)
    override val ssid: StateFlow<String?> = _ssid.asStateFlow()

    @Volatile
    override var simulator = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    private val lock = Any()
    private var overlay: RecorderConnectionState? = RecorderConnectionState.Disconnected // guarded; null = follow the client
    private var starting = false // guarded; between clearing the overlay and start() returning
    private var attemptJob: Job? = null // guarded; the running connect attempt
    private var generation = 0 // guarded; bumped by connect and disconnect
    private var info: DeviceInfo? = null // guarded
    private var infoFor: SessionState.Ready? = null
    private var networkJob: Job? = null // guarded
    private var http: Pair<Network?, OkHttpClient>? = null // guarded

    @Volatile private var wantConnected = false

    /** Registered on ProcessLifecycleOwner by [DashcamModule]. */
    val processObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            if (wantConnected) scope.launch { connect() }
        }

        override fun onStop(owner: LifecycleOwner) {
            scope.launch { stopSession() }
        }
    }

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.state.collect { s ->
                publish()
                if (s is SessionState.Ready && s !== infoFor) {
                    infoFor = s
                    launch { loadDeviceInfo(s) }
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

    override suspend fun connect(ignoreSsid: Boolean) {
        val job = synchronized(lock) {
            val s = _state.value
            attemptJob?.takeIf { it.isActive } ?: run {
                if (s is RecorderConnectionState.Ready || s == RecorderConnectionState.TcpConnected ||
                    s == RecorderConnectionState.Negotiating
                ) return
                wantConnected = true
                val gen = ++generation
                // The manager's scope: leaving the screen that asked for it does not cancel the connection.
                scope.launch(start = CoroutineStart.LAZY) { attempt(gen, ignoreSsid) }.also { attemptJob = it }
            }
        }
        job.start()
        job.join()
    }

    private suspend fun attempt(gen: Int, ignoreSsid: Boolean) {
        keyError?.let { return show(RecorderConnectionState.Error(it, retry = false)) }
        synchronized(lock) { info = null }
        _sdStatus.value = null
        _recStatus.value = null
        show(RecorderConnectionState.Connecting)
        val sim = simulator
        if (!sim) {
            val network = awaitNetwork()
            val ssid = wifi.currentSsid().also { _ssid.value = it }
            if (gen != synchronized(lock) { generation }) return
            if (network == null) return show(RecorderConnectionState.NoWifi)
            if (!ignoreSsid && ssid != null && !looksLikeRecorderSsid(ssid)) return show(RecorderConnectionState.WrongWifi(ssid))
        }
        client.stop() // clears a Failed state of an earlier attempt, so the published state cannot jump back to it
        synchronized(lock) {
            if (gen != generation) return
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
        stopSession()
    }

    private suspend fun stopSession() {
        val running = synchronized(lock) {
            generation++
            attemptJob.also { attemptJob = null }
        }
        running?.cancelAndJoin()
        show(RecorderConnectionState.Disconnected)
        client.stop()
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

    override fun diagnosticLog(): List<RecorderDiagnostic> = synchronized(log) { log.toList() }

    /** Starts the Wi-Fi request once (kept until disconnect / app stop) and waits briefly for a network. */
    private suspend fun awaitNetwork(): Network? {
        synchronized(lock) {
            if (networkJob?.isActive != true) {
                networkJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { wifi.network().collect(::onNetwork) }
            }
        }
        return withTimeoutOrNull(networkWaitMs) { _network.first { it != null } }
    }

    private fun onNetwork(network: Network?) {
        val lost = network == null && _network.value != null
        _network.value = network
        if (lost) scope.launch { onNetworkLost() }
    }

    private suspend fun onNetworkLost() {
        if (simulator || synchronized(lock) { overlay != null }) return // no session on that network
        Log.i(TAG, "recorder Wi-Fi lost, stopping the session")
        show(RecorderConnectionState.NoWifi)
        client.stop()
    }

    private suspend fun loadDeviceInfo(session: SessionState.Ready) {
        val result = client.request(RecorderCommand.GetDeviceInfo, ::parseDeviceInfo)
        if (result is RecorderResult.Ok && client.state.value === session) {
            synchronized(lock) { info = result.value }
            publish()
        }
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
            is SessionState.Ready -> RecorderConnectionState.Ready(info, s, _network.value)
            // A key that does not unwrap the recorder's aescode will not work on a second try either.
            is SessionState.Failed -> RecorderConnectionState.Error(s.reason, retry = s.reason.code != ErrorCodes.SESSION_KEY_INVALID)
        }
        if (next != _state.value) Log.d(TAG, "state $next")
        _state.value = next
    }

    companion object {
        private const val TAG = "RecorderConnection"
        const val NETWORK_WAIT_MS = 5_000L
        const val LOG_SIZE = 400

        /** The emulator's alias for the development machine, where `:recorder:runSimulator` listens. */
        const val SIMULATOR_HOST = "10.0.2.2"

        /** [RecorderError.message] markers for the configuration error (no rsaKey in local.properties). */
        const val KEY_MISSING = "dashcam.rsaKey is not configured"
        const val KEY_INVALID = "dashcam.rsaKey could not be loaded"
    }
}

/**
 * Plain socket, bound to the recorder [network] before connecting. If binding fails the socket is closed, so the
 * connect fails instead of silently going out over mobile data.
 */
fun boundSocketTransport(network: Network?): RecorderTransport = SocketTransport {
    Socket().also { socket ->
        if (network != null) runCatching { network.bindSocket(socket) }.onFailure { socket.close() }
    }
}
