package me.ri3d.dashcam.live

import android.content.Context
import android.graphics.Bitmap
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.ui.unit.IntSize
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import me.ri3d.dashcam.core.log.Log
import me.ri3d.dashcam.dashcam.RecorderConnectionManager
import me.ri3d.dashcam.dashcam.RecorderConnectionManagerImpl
import me.ri3d.dashcam.plates.Frame
import me.ri3d.dashcam.recorder.BasicCapabilities
import me.ri3d.dashcam.recorder.RecorderClient
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory
import kotlin.math.roundToInt

object LiveStream {
    /**
     * The original app's hardcoded preview URL (protocol report, "Live view"): port 554, no credentials. The fallback
     * after the recorder's own URL ([rtspCandidates]).
     */
    const val URL = "rtsp://192.168.42.1/ch1/sub/av_stream"

    /**
     * Debug simulator mode: the traced path on `:recorder:runSimulator`'s RTSP server (port 7554 of the development
     * machine as the emulator sees it); the fallback after the simulator's capability URL.
     */
    const val SIMULATOR_URL = "rtsp://${RecorderConnectionManagerImpl.SIMULATOR_HOST}:7554/ch1/sub/av_stream"

    /** Upper bound for [LiveFrameSource] frames. */
    const val MAX_FRAME_WIDTH = 1280

    /** RTSP 461 Unsupported Transport: the server refused the transport asked for in SETUP. */
    const val UNSUPPORTED_TRANSPORT = 461
}

/**
 * RTSP URLs in the order they are tried: the recorder's own URL for its first channel (capability 20481
 * `rtspServer`; on hardware `rtsp://192.168.42.1:554/ch1/sub`), then the traced [LiveStream.URL]. A reported URL is
 * used only when it is rtsp:// on the recorder's address without credentials. [preferred] (the URL that played
 * last) moves to the front. In debug [simulator] mode the address is the simulator host and the fallback
 * [LiveStream.SIMULATOR_URL].
 */
fun rtspCandidates(basic: BasicCapabilities?, preferred: String? = null, simulator: Boolean = false): List<String> {
    val host = if (simulator) RecorderConnectionManagerImpl.SIMULATOR_HOST else RecorderClient.DEFAULT_HOST
    val reported = basic?.rtspServer?.minByOrNull { it.chanNo ?: Int.MAX_VALUE }?.url?.takeIf { isRtspUrlOn(it, host) }
    val urls = listOfNotNull(reported, if (simulator) LiveStream.SIMULATOR_URL else LiveStream.URL).distinct()
    return if (preferred != null && preferred in urls) listOf(preferred) + (urls - preferred) else urls
}

private fun isRtspUrlOn(url: String, host: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.scheme.equals("rtsp", ignoreCase = true) && uri.host == host && uri.rawUserInfo == null
}

/** The status of Media3's RTSP error message ("SETUP 461"), null for any other message. */
fun rtspStatus(message: String?): Int? = message?.let(RTSP_STATUS::matchEntire)?.groupValues?.get(1)?.toInt()

private val RTSP_STATUS = Regex("""[A-Z_]+ (\d{3})""")

/** What the live view needs from a player: [ExoLivePlayer] in the app, a fake in tests. Main thread only. */
interface LivePlayer {
    var listener: ((PlayerEvent) -> Unit)?

    /**
     * (Re)starts the stream; the connection to the recorder (and with [tcp] its interleaved RTP) uses sockets from
     * [socketFactory]. Without [tcp] Media3 asks for UDP first: those RTP sockets are not bound to the recorder network.
     */
    fun play(url: String, socketFactory: SocketFactory, tcp: Boolean = true)
    fun stop()
    fun release()

    /** Renders into [view] (a second call with the same view is a no-op). */
    fun attach(view: TextureView)

    /** Detaches [view] if it is the current one. */
    fun detach(view: TextureView)

    /** The current frame as a software ARGB_8888 bitmap, stream size capped at [maxWidth]; null without a frame. */
    fun capture(maxWidth: Int = Int.MAX_VALUE): Bitmap?
}

sealed interface PlayerEvent {
    data object Buffering : PlayerEvent
    data object Playing : PlayerEvent

    /** Display size of the stream (pixel aspect ratio applied). */
    data class Size(val width: Int, val height: Int) : PlayerEvent

    data class Failed(val error: StreamError) : PlayerEvent
}

/**
 * A stream failure: Media3's `PlaybackException.errorCode` and its name, or a local reason ([code] null). [cause]:
 * the innermost cause as "Class: message" (raw, e.g. "RtspPlaybackException: SETUP 461"); [rtspStatus] from Media3's
 * RTSP error; [udp]: the failed attempt used UDP.
 */
data class StreamError(
    val name: String,
    val code: Int? = null,
    val cause: String? = null,
    val rtspStatus: Int? = null,
    val udp: Boolean = false,
) {
    /** One line for the Diagnose export. */
    fun describe(): String = name + (code?.let { " ($it)" } ?: "") + (rtspStatus?.let { ", RTSP $it" } ?: "") + (cause?.let { ": $it" } ?: "")

    /**
     * Media3 rejected the stream description: a parsing code, or (as on hardware 2026-10-02, code 2000) an innermost
     * `IllegalArgumentException` / `ParserException` such as "missing attribute control".
     */
    val sdpProblem: Boolean
        get() = code in 3000..3999 || cause?.substringBefore(':') in setOf("IllegalArgumentException", "ParserException")

    companion object {
        /** The stream ended; the original app stops the preview on stream closure. */
        val ENDED = StreamError("STREAM_ENDED")

        /** Ready without a bound recorder Wi-Fi (outside simulator mode): nothing is started unbound. */
        val NOT_BOUND = StreamError("RECORDER_WIFI_NOT_BOUND")
    }
}

/**
 * Media3 ExoPlayer with RTSP over TCP (interleaved, as traced; UDP on request), no credentials, live sound disabled (the original
 * app turns preview sound off; this says nothing about the recordings). Hardware decoders first, with Media3's
 * decoder fallback to the next (software) decoder when one fails to initialise. The ExoPlayer is created on the
 * first [play] and lives until [release]. Media3 talks to an [RtspSdpProxy] on the loopback interface, which repairs
 * the recorder's description and reports its steps to [note].
 */
@OptIn(UnstableApi::class) // RTSP source options, decoder fallback and buffer sizes are Media3 "unstable" API
class ExoLivePlayer(private val context: Context, private val note: (String) -> Unit = {}) : LivePlayer {
    override var listener: ((PlayerEvent) -> Unit)? = null
    private var player: ExoPlayer? = null
    private var view: TextureView? = null
    private var size: PlayerEvent.Size? = null
    private var proxy: RtspSdpProxy? = null

    private val events = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> listener?.invoke(PlayerEvent.Buffering)
                // The original app stops the preview when the stream closes; here that counts as a stream error.
                Player.STATE_ENDED -> listener?.invoke(PlayerEvent.Failed(StreamError.ENDED))
                else -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) listener?.invoke(PlayerEvent.Playing)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            val s = PlayerEvent.Size((videoSize.width * videoSize.pixelWidthHeightRatio).roundToInt(), videoSize.height)
            size = s
            listener?.invoke(s)
        }

        override fun onPlayerError(error: PlaybackException) {
            val causes = generateSequence(error.cause) { it.cause }.toList()
            val failure = StreamError(
                name = error.errorCodeName,
                code = error.errorCode,
                cause = causes.lastOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }, // the URLs carry no credentials
                rtspStatus = causes.filterIsInstance<RtspMediaSource.RtspPlaybackException>().firstNotNullOfOrNull { rtspStatus(it.message) },
            )
            Log.w(TAG, "stream error ${failure.describe()}")
            listener?.invoke(PlayerEvent.Failed(failure))
        }
    }

    private fun player(): ExoPlayer = player ?: ExoPlayer.Builder(
        context,
        DefaultRenderersFactory(context).setEnableDecoderFallback(true),
    )
        // ponytail: small buffers keep the preview close to live; tune on the recorder (latency vs. stutter).
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, REBUFFER_MS)
                .build(),
        )
        .build()
        .apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            addListener(events)
            view?.let(::setVideoTextureView)
            player = this
        }

    override fun play(url: String, socketFactory: SocketFactory, tcp: Boolean) {
        size = null
        proxy?.close()
        // The proxy's socket to the recorder comes from socketFactory; Media3 itself only reaches 127.0.0.1.
        val local = RtspSdpProxy(url, socketFactory, note).also { proxy = it }
        player().apply {
            setMediaSource(
                RtspMediaSource.Factory()
                    .setForceUseRtpTcp(tcp)
                    .createMediaSource(MediaItem.fromUri(local.url)),
            )
            playWhenReady = true
            prepare()
        }
    }

    override fun stop() {
        player?.stop()
        proxy?.close()
        proxy = null
        size = null
    }

    override fun release() {
        player?.release()
        player = null
        proxy?.close()
        proxy = null
        view = null
    }

    override fun attach(view: TextureView) {
        if (this.view === view) return // setVideoTextureView would create a new Surface every time
        this.view = view
        player?.setVideoTextureView(view)
    }

    override fun detach(view: TextureView) {
        if (this.view !== view) return
        this.view = null
        player?.clearVideoTextureView(view)
    }

    override fun capture(maxWidth: Int): Bitmap? {
        val v = view?.takeIf { it.isAvailable } ?: return null
        val s = size ?: return null
        val scale = minOf(1f, maxWidth.toFloat() / s.width)
        return v.getBitmap((s.width * scale).roundToInt().coerceAtLeast(1), (s.height * scale).roundToInt().coerceAtLeast(1))
    }

    companion object {
        private const val TAG = "LivePlayer"
        private const val MIN_BUFFER_MS = 1_000
        private const val MAX_BUFFER_MS = 3_000
        private const val START_BUFFER_MS = 500
        private const val REBUFFER_MS = 1_000
    }
}

/**
 * Phase 4 hook (plates-ui, enhance-ui): frames of the running live view. [frames] grabs from the player surface
 * only while collected and only while the stream plays (it suspends otherwise); each [Frame] is a fresh software
 * ARGB_8888 bitmap at most [LiveStream.MAX_FRAME_WIDTH] px wide, owned by the collector (a slow collector delays
 * the next grab).
 */
@Singleton
class LiveFrameSource @Inject constructor() {
    /** Set by [LiveViewModel] while its stream plays (Playing or Buffering). */
    internal val player = MutableStateFlow<LivePlayer?>(null)

    internal val size = MutableStateFlow<IntSize?>(null)

    /** Stream display size while a stream plays, else null (e.g. for `LiveSharpen.effect(scale)`). */
    val videoSize: StateFlow<IntSize?> = size.asStateFlow()

    /**
     * Each grab is a GPU readback on the main thread, so [wanted] is asked first: pass the consumer's own check
     * (`processor::wantsFrame` of `LivePlateProcessor`) and frames it would drop are never grabbed.
     */
    fun frames(targetFps: Int, wanted: () -> Boolean = { true }): Flow<Frame> {
        require(targetFps in 1..MAX_FPS) { "targetFps must be in 1..$MAX_FPS" }
        return flow {
            while (true) {
                val source = player.filterNotNull().first()
                val bitmap = if (wanted()) withContext(Dispatchers.Main) { source.capture(LiveStream.MAX_FRAME_WIDTH) } else null
                if (bitmap != null) emit(Frame(bitmap, System.currentTimeMillis()))
                delay(1000L / targetFps)
            }
        }
    }

    companion object {
        const val MAX_FPS = 30
    }
}

@Module
@InstallIn(SingletonComponent::class)
object LiveModule {
    /** Unscoped: every live screen gets its own player. Its proxy's steps go to the Diagnose notes (`rtsp`). */
    @Provides
    fun livePlayer(@ApplicationContext context: Context, manager: RecorderConnectionManager): LivePlayer =
        ExoLivePlayer(context) { manager.note(RecorderConnectionManagerImpl.RTSP_NOTES, it) }
}
