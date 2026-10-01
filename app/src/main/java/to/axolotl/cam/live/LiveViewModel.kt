package to.axolotl.cam.live

import android.content.Context
import android.view.TextureView
import androidx.annotation.StringRes
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
import to.axolotl.cam.R
import to.axolotl.cam.dashcam.RecorderConnectionManager
import to.axolotl.cam.dashcam.RecorderConnectionState
import to.axolotl.cam.dashcam.errorMeaning
import to.axolotl.cam.dashcam.outcomeUnknown
import to.axolotl.cam.recorder.CaptureResult
import to.axolotl.cam.recorder.RecorderClient
import to.axolotl.cam.recorder.RecorderCommand
import to.axolotl.cam.recorder.RecorderError
import to.axolotl.cam.recorder.RecorderNotification
import to.axolotl.cam.recorder.RecorderResult
import to.axolotl.cam.recorder.RecorderValues
import to.axolotl.cam.recorder.parseCaptureResult
import java.io.IOException
import javax.inject.Inject
import javax.net.SocketFactory

sealed interface StreamState {
    /** No session, screen in the background, or stopped. */
    data object Off : StreamState

    /** Opening the stream, nothing shown yet. */
    data object Loading : StreamState
    data object Playing : StreamState

    /** Stalled after it had played. */
    data object Buffering : StreamState

    /** Failed again after the one automatic retry; [code] is raw (Media3 error code name). */
    data class Failed(val code: String) : StreamState
}

enum class LiveAction(@StringRes val label: Int) { PHOTO(R.string.live_photo), BURST(R.string.live_burst), RECORD(R.string.live_record) }

data class CommandOutcome(val action: LiveAction, val result: RecorderResult<CaptureResult>)

data class CommandUi(
    /** 12292 in flight. */
    val photoBusy: Boolean = false,
    /** The app's 10 s recording countdown (UI only, as traced), null when not running. */
    val recordSecondsLeft: Int? = null,
    val last: CommandOutcome? = null,
    /** 12292 replies without a waiting request (e.g. further burst replies) since the last photo command. */
    val extraReplies: Int = 0,
    val lastExtraPath: String? = null,
)

sealed interface LiveEvent {
    data object NoFrame : LiveEvent
    data class ScreenshotSaved(val inGallery: Boolean) : LiveEvent
    data object ScreenshotFailed : LiveEvent
}

/**
 * Plays the recorder's RTSP preview while the connection is Ready and the screen is started (the original app
 * opens the preview after the control session), one automatic retry per start, then [StreamState.Failed].
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

    val videoSize: StateFlow<IntSize?> = frameSource.videoSize

    private val _command = MutableStateFlow(CommandUi())
    val command: StateFlow<CommandUi> = _command.asStateFlow()

    private val _events = Channel<LiveEvent>(Channel.BUFFERED)
    val events: Flow<LiveEvent> = _events.receiveAsFlow()

    private val foreground = MutableStateFlow(false)
    private var retried = false
    private var played = false

    init {
        player.listener = ::onPlayerEvent
        viewModelScope.launch {
            combine(manager.state, foreground) { state, fg -> state is RecorderConnectionState.Ready && fg }
                .distinctUntilChanged()
                .collect { if (it) start() else if (_stream.value != StreamState.Off) stopStream() }
        }
        viewModelScope.launch {
            manager.notifications.collect { n ->
                if (n is RecorderNotification.Unmatched && n.reply.msgId == TAKE_PHOTO_MSG_ID) {
                    val path = runCatching { parseCaptureResult(n.reply).filePath }.getOrNull()
                    _command.update { it.copy(extraReplies = it.extraReplies + 1, lastExtraPath = path) }
                }
            }
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

    private fun start() {
        val url: String
        val sockets: SocketFactory
        if (manager.simulator.value) {
            url = LiveStream.SIMULATOR_URL
            sockets = SocketFactory.getDefault()
        } else {
            // Never unbound: without the recorder Wi-Fi the stream would go out over mobile data.
            val network = manager.recorderNetwork.value ?: return run { _stream.value = StreamState.Failed(NOT_BOUND) }
            url = LiveStream.URL
            sockets = network.socketFactory
        }
        played = false
        _stream.value = StreamState.Loading
        player.play(url, sockets)
    }

    private fun stopStream() {
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
                played = true
                retried = false
                _stream.value = StreamState.Playing
                frameSource.player = player
            }
            is PlayerEvent.Size -> frameSource.size.value = IntSize(event.width, event.height)
            is PlayerEvent.Failed -> {
                releaseFrames()
                if (!retried) {
                    retried = true
                    start()
                } else {
                    _stream.value = StreamState.Failed(event.code)
                }
            }
        }
    }

    private fun releaseFrames() {
        if (frameSource.player === player) {
            frameSource.player = null
            frameSource.size.value = null
        }
    }

    fun takePhoto(burst: Boolean) {
        if (_command.value.photoBusy) return
        val action = if (burst) LiveAction.BURST else LiveAction.PHOTO
        _command.update { it.copy(photoBusy = true, extraReplies = 0, lastExtraPath = null) }
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

        // ponytail: reply timing of a burst is unverified; a later reply still shows up as an extra reply.
        const val BURST_TIMEOUT_MS = 30_000L
        const val NOT_BOUND = "RECORDER_WIFI_NOT_BOUND"
        private val TAKE_PHOTO_MSG_ID = RecorderCommand.TakePhoto().msgId
    }
}

/**
 * One line per reply, as reported: path and time "laut Recorder", errors with the app-table meaning and raw code.
 * A command without an answer (timeout, connection lost) is "Ergebnis unbekannt", never "fehlgeschlagen".
 * [text] resolves a string resource with format arguments (`Resources.getString`).
 */
fun commandMessage(outcome: CommandOutcome, text: (id: Int, args: Array<out Any>) -> String): String {
    fun t(@StringRes id: Int, vararg args: Any) = text(id, args)
    val label = t(outcome.action.label)
    return when (val r = outcome.result) {
        is RecorderResult.Ok -> {
            val line = t(R.string.live_reply_ok, label, r.value.filePath ?: t(R.string.live_reply_no_path, r.reply.rval))
            r.value.fileTime?.let { line + "\n" + t(R.string.live_reply_time, it) } ?: line
        }
        is RecorderResult.Failed -> {
            val e = r.error
            val error = t(R.string.dashcam_error_with_code, t(errorMeaning(e)), e.code)
            when {
                e.source == RecorderError.Source.RECORDER -> t(R.string.live_reply_error, label, error)
                outcomeUnknown(e) -> t(R.string.live_reply_local, label, t(R.string.dashcam_status_unknown, error))
                else -> t(R.string.live_reply_local, label, error)
            }
        }
    }
}
