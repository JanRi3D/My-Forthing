package me.ri3d.dashcam.live

import android.content.Context
import android.view.TextureView
import androidx.annotation.StringRes
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ri3d.dashcam.R
import me.ri3d.dashcam.dashcam.RecorderConnectionManager
import me.ri3d.dashcam.dashcam.RecorderConnectionManagerImpl
import me.ri3d.dashcam.dashcam.RecorderConnectionState
import me.ri3d.dashcam.dashcam.errorMeaning
import me.ri3d.dashcam.dashcam.outcomeUnknown
import me.ri3d.dashcam.recorder.CapabilityGroup
import me.ri3d.dashcam.recorder.CaptureResult
import me.ri3d.dashcam.recorder.RecorderClient
import me.ri3d.dashcam.recorder.RecorderCommand
import me.ri3d.dashcam.recorder.RecorderError
import me.ri3d.dashcam.recorder.RecorderNotification
import me.ri3d.dashcam.recorder.RecorderReply
import me.ri3d.dashcam.recorder.RecorderResult
import me.ri3d.dashcam.recorder.RecorderValues
import me.ri3d.dashcam.recorder.parseBasicCapabilities
import me.ri3d.dashcam.recorder.parseCaptureResult
import java.io.IOException
import javax.inject.Inject
import javax.net.SocketFactory

sealed interface StreamState {
    /** No session, screen in the background, or stopped. */
    data object Off : StreamState

    /** Opening the stream (or waiting for the automatic retry), nothing shown yet. */
    data object Loading : StreamState
    data object Playing : StreamState

    /** Stalled after it had played. */
    data object Buffering : StreamState

    /** Failed again after the one automatic retry. */
    data class Failed(val error: StreamError) : StreamState
}

/** German reason for a stream failure, by Media3's error code group; the raw name stays visible separately. */
@get:StringRes
val StreamError.reason: Int
    get() = when {
        this == StreamError.NOT_BOUND -> R.string.live_err_not_bound
        this == StreamError.ENDED -> R.string.live_err_ended
        code in 2000..2999 -> R.string.live_err_network // ERROR_CODE_IO_*
        code in 3000..3999 -> R.string.live_err_format // ERROR_CODE_PARSING_*
        code in 4000..4999 -> R.string.live_err_decoder // ERROR_CODE_DECODER_* / DECODING_*
        else -> R.string.live_err_other
    }

enum class LiveAction(@StringRes val label: Int, val msgId: Int) {
    PHOTO(R.string.live_photo, PHOTO_MSG_ID),
    BURST(R.string.live_burst, PHOTO_MSG_ID),
    RECORD(R.string.live_record, RECORD_MSG_ID),
}

private const val PHOTO_MSG_ID = 12292
private const val RECORD_MSG_ID = 12293

/** [late]: the reply arrived after the request had ended without an answer and replaces "Ergebnis unbekannt". */
data class CommandOutcome(val action: LiveAction, val result: RecorderResult<CaptureResult>, val late: Boolean = false)

/** Replies nobody waited for (e.g. further burst replies), counted since the last command of that kind. */
data class ExtraReplies(val action: LiveAction, val count: Int, val last: RecorderResult<CaptureResult>)

data class CommandUi(
    /** 12292 in flight. */
    val photoBusy: Boolean = false,
    /** The app's 10 s recording countdown (UI only, as traced), null when not running. */
    val recordSecondsLeft: Int? = null,
    val last: CommandOutcome? = null,
    val extra: ExtraReplies? = null,
)

sealed interface LiveEvent {
    data object NoFrame : LiveEvent
    data class ScreenshotSaved(val inGallery: Boolean) : LiveEvent
    data object ScreenshotFailed : LiveEvent
}

/**
 * Plays the recorder's RTSP preview while the connection is Ready and the screen is started (the original app
 * opens the preview after the control session): each start tries every RTSP candidate (TCP, then UDP after a 461),
 * then one delayed automatic retry of the whole list, then [StreamState.Failed]. Every attempt is noted for Diagnose;
 * a final failure on the stream description also captures the raw DESCRIBE exchange once ([captureRtspDescribe]).
 * Photo/burst/record commands go through the connection manager; replies are shown as reported.
 */
@HiltViewModel
class LiveViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manager: RecorderConnectionManager,
    private val player: LivePlayer,
    private val frameSource: LiveFrameSource,
) : ViewModel() {
    val connection: StateFlow<RecorderConnectionState> = manager.state

    private val _stream = MutableStateFlow<StreamState>(StreamState.Off)
    val stream: StateFlow<StreamState> = _stream.asStateFlow()

    /** Published once the stream plays. */
    val videoSize: StateFlow<IntSize?> = frameSource.videoSize

    private val _command = MutableStateFlow(CommandUi())
    val command: StateFlow<CommandUi> = _command.asStateFlow()

    private val _events = Channel<LiveEvent>(Channel.BUFFERED)
    val events: Flow<LiveEvent> = _events.receiveAsFlow()

    private val foreground = MutableStateFlow(false)
    private var retried = false
    private var played = false
    private var retryJob: Job? = null
    private var startJob: Job? = null
    private var pendingSize: IntSize? = null

    /** One RTSP try: TCP interleaved ([tcp]) or Media3's UDP-first transport. */
    private data class Attempt(val url: String, val tcp: Boolean) {
        override fun toString() = url + if (tcp) " tcp" else " udp"
    }

    /** Tries left in this start, after [attempt]. */
    private val attempts = ArrayDeque<Attempt>()
    private var attempt: Attempt? = null
    private var sockets: SocketFactory = SocketFactory.getDefault()

    /** The URL that played last: tried first on the next start. */
    private var worked: String? = null

    /** The raw DESCRIBE exchange was captured for Diagnose after Media3 rejected a description (once per screen). */
    private var described = false
    private var photoAction = LiveAction.PHOTO

    init {
        player.listener = ::onPlayerEvent
        viewModelScope.launch {
            combine(manager.state, foreground) { state, fg -> state is RecorderConnectionState.Ready && fg }
                .distinctUntilChanged()
                .collect { if (it) start() else if (_stream.value != StreamState.Off) stopStream() }
        }
        viewModelScope.launch {
            manager.notifications.collect { if (it is RecorderNotification.Unmatched) onUnmatched(it.reply) }
        }
    }

    /** The screen is started (true) or stopped (false); configuration changes are not stops. */
    fun onForeground(started: Boolean) {
        foreground.value = started
    }

    fun attach(view: TextureView) = player.attach(view)
    fun detach(view: TextureView) = player.detach(view)

    /** "Erneut versuchen" after [StreamState.Failed]: a fresh start with its own automatic retry. */
    fun retry() {
        if (manager.state.value is RecorderConnectionState.Ready && foreground.value) {
            retried = false
            start()
        }
    }

    /** Every candidate URL ([rtspCandidates]) over TCP; a 461 adds the same URL over UDP right after it. */
    private fun start() {
        retryJob?.cancel()
        startJob?.cancel()
        val network = (manager.state.value as? RecorderConnectionState.Ready)?.network
        // Ready without a network exists only in simulator mode; otherwise never an unbound (mobile data) socket.
        if (network == null && !manager.simulator.value) return run { _stream.value = StreamState.Failed(StreamError.NOT_BOUND) }
        played = false
        pendingSize = null
        _stream.value = StreamState.Loading
        startJob = viewModelScope.launch {
            val basic = manager.capabilities(CapabilityGroup.BASIC)?.let { runCatching { parseBasicCapabilities(it) }.getOrNull() }
            val urls = rtspCandidates(basic, worked, simulator = network == null)
            note("start: " + urls.joinToString())
            sockets = network?.socketFactory ?: SocketFactory.getDefault()
            attempts.clear()
            urls.mapTo(attempts) { Attempt(it, tcp = true) }
            next()
        }
    }

    private fun next() {
        val next = attempts.removeFirst()
        attempt = next
        player.play(next.url, sockets, next.tcp)
    }

    private fun note(message: String) = manager.note(RecorderConnectionManagerImpl.RTSP_NOTES, message)

    private fun stopStream() {
        retryJob?.cancel()
        startJob?.cancel()
        player.stop()
        releaseFrames()
        retried = false
        _stream.value = StreamState.Off
    }

    private fun onPlayerEvent(event: PlayerEvent) {
        if (_stream.value == StreamState.Off) return // late event after a stop
        when (event) {
            PlayerEvent.Buffering -> _stream.value = if (played) StreamState.Buffering else StreamState.Loading
            PlayerEvent.Playing -> {
                if (!played) attempt?.let {
                    note("$it: playing")
                    worked = it.url
                }
                attempts.clear() // a later drop starts over (retry), with the URL that worked first
                played = true
                retried = false
                _stream.value = StreamState.Playing
                frameSource.player.value = player
                frameSource.size.value = pendingSize
            }
            is PlayerEvent.Size -> {
                note("$attempt: video ${event.width}x${event.height}")
                pendingSize = IntSize(event.width, event.height)
                if (frameSource.player.value === player) frameSource.size.value = pendingSize
            }
            is PlayerEvent.Failed -> {
                releaseFrames()
                val failed = attempt
                val error = if (failed?.tcp == false) event.error.copy(udp = true) else event.error
                note("$failed: ${error.describe()}")
                // TCP interleaved refused: the same URL with Media3's UDP transport before the next URL.
                if (failed?.tcp == true && error.rtspStatus == LiveStream.UNSUPPORTED_TRANSPORT) attempts.addFirst(failed.copy(tcp = false))
                if (attempts.isNotEmpty()) {
                    _stream.value = StreamState.Loading
                    next()
                } else if (retried) {
                    _stream.value = StreamState.Failed(error)
                    // The SDP Media3 refused goes into the next Diagnose export (notes.rtsp), once; no player is running now.
                    if (error.sdpProblem && !described) {
                        described = true
                        viewModelScope.launch { captureRtspDescribe(manager) }
                    }
                } else {
                    retried = true
                    _stream.value = StreamState.Loading
                    retryJob = viewModelScope.launch {
                        delay(RETRY_DELAY_MS)
                        start()
                    }
                }
            }
        }
    }

    private fun releaseFrames() {
        if (frameSource.player.value === player) {
            frameSource.player.value = null
            frameSource.size.value = null
        }
    }

    fun takePhoto(burst: Boolean) {
        if (_command.value.photoBusy) return
        val action = if (burst) LiveAction.BURST else LiveAction.PHOTO
        photoAction = action
        _command.update { it.copy(photoBusy = true, extra = it.extra?.takeUnless { e -> e.action.msgId == PHOTO_MSG_ID }) }
        viewModelScope.launch {
            val result = manager.request(
                RecorderCommand.TakePhoto(number = if (burst) BURST_COUNT else 1),
                ::parseCaptureResult,
                if (burst) BURST_TIMEOUT_MS else RecorderClient.DEFAULT_REQUEST_TIMEOUT_MS,
            )
            _command.update { it.copy(photoBusy = false, last = CommandOutcome(action, result)) }
        }
    }

    /** 12293 recType 1 plus the traced 10 s UI countdown; no stop command. Its end or a reply ends the UI state. */
    fun record() {
        if (_command.value.recordSecondsLeft != null) return
        _command.update { it.copy(extra = it.extra?.takeUnless { e -> e.action.msgId == RECORD_MSG_ID }) }
        val countdown = viewModelScope.launch {
            for (s in RECORD_COUNTDOWN_S downTo 1) {
                _command.update { it.copy(recordSecondsLeft = s) }
                delay(1_000)
            }
            _command.update { it.copy(recordSecondsLeft = null) }
        }
        viewModelScope.launch {
            val result = manager.request(RecorderCommand.StartRecord(RecorderValues.RECORD_MANUAL), ::parseCaptureResult)
            countdown.cancel()
            _command.update { it.copy(recordSecondsLeft = null, last = CommandOutcome(LiveAction.RECORD, result)) }
        }
    }

    /**
     * A 12292/12293 reply without a waiting request: it resolves the last command of that kind if that one ended
     * without an answer ("Ergebnis unbekannt"), otherwise it is counted as a further reply.
     */
    private fun onUnmatched(reply: RecorderReply) {
        if (reply.msgId != PHOTO_MSG_ID && reply.msgId != RECORD_MSG_ID) return
        val result: RecorderResult<CaptureResult> =
            if (reply.rval == 0) RecorderResult.Ok(parseCaptureResult(reply), reply)
            else RecorderResult.Failed(RecorderError(reply.rval, RecorderError.Source.RECORDER, null, null))
        _command.update { ui ->
            val last = ui.last
            val unknown = (last?.result as? RecorderResult.Failed)?.error?.let(::outcomeUnknown) == true
            if (last != null && unknown && !last.late && last.action.msgId == reply.msgId) {
                ui.copy(last = last.copy(result = result, late = true))
            } else {
                val action = if (reply.msgId == RECORD_MSG_ID) LiveAction.RECORD else photoAction
                val count = ui.extra?.takeIf { it.action.msgId == reply.msgId }?.count ?: 0
                ui.copy(extra = ExtraReplies(action, count + 1, result))
            }
        }
    }

    fun screenshot() {
        val bitmap = player.capture() ?: run {
            _events.trySend(LiveEvent.NoFrame)
            return
        }
        viewModelScope.launch {
            val event = try {
                LiveEvent.ScreenshotSaved(withContext(Dispatchers.IO) { saveScreenshot(context, bitmap) }.inGallery)
            } catch (e: IOException) {
                LiveEvent.ScreenshotFailed
            }
            _events.send(event)
        }
    }

    override fun onCleared() {
        player.listener = null
        releaseFrames()
        player.release()
    }

    companion object {
        const val RECORD_COUNTDOWN_S = 10
        const val BURST_COUNT = 5
        const val RETRY_DELAY_MS = 1_500L

        // ponytail: reply timing of a burst is unverified; a later reply still shows up (late or further reply).
        const val BURST_TIMEOUT_MS = 30_000L
    }
}

/** Resolves a string resource with format arguments (`Resources.getString`). */
typealias Strings = (id: Int, args: Array<out Any>) -> String

/** One reply in short: the file path as reported, "OK ohne Dateipfad (rval 0)", or "<meaning> (Code n)". */
fun replyText(result: RecorderResult<CaptureResult>, text: Strings): String = when (result) {
    is RecorderResult.Ok -> result.value.filePath ?: text(R.string.live_reply_no_path, arrayOf(result.reply.rval))
    is RecorderResult.Failed ->
        text(R.string.dashcam_error_with_code, arrayOf<Any>(text(errorMeaning(result.error), emptyArray()), result.error.code))
}

/**
 * One line per reply, as reported: path and time "laut Recorder", errors with the app-table meaning and raw code.
 * A command without an answer (timeout, connection lost) is "Ergebnis unbekannt", never "fehlgeschlagen".
 */
fun commandMessage(outcome: CommandOutcome, text: Strings): String {
    fun t(@StringRes id: Int, vararg args: Any) = text(id, args)
    val label = t(outcome.action.label).let { if (outcome.late) t(R.string.live_reply_late, it) else it }
    return when (val r = outcome.result) {
        is RecorderResult.Ok -> {
            val line = t(R.string.live_reply_ok, label, replyText(r, text))
            r.value.fileTime?.let { line + "\n" + t(R.string.live_reply_time, it) } ?: line
        }
        is RecorderResult.Failed -> when {
            r.error.source == RecorderError.Source.RECORDER -> t(R.string.live_reply_error, label, replyText(r, text))
            outcomeUnknown(r.error) -> t(R.string.live_reply_local, label, t(R.string.dashcam_status_unknown, replyText(r, text)))
            else -> t(R.string.live_reply_local, label, replyText(r, text))
        }
    }
}
