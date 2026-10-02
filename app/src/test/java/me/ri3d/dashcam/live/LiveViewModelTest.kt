package me.ri3d.dashcam.live

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.TextureView
import androidx.compose.ui.unit.IntSize
import androidx.exifinterface.media.ExifInterface
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowNetwork
import org.w3c.dom.Element
import me.ri3d.dashcam.R
import me.ri3d.dashcam.dashcam.FakeWifi
import me.ri3d.dashcam.dashcam.RecorderConnectionManagerImpl
import me.ri3d.dashcam.dashcam.managerFor
import me.ri3d.dashcam.plates.Frame
import me.ri3d.dashcam.recorder.RecorderResult
import me.ri3d.dashcam.recorder.RecorderSimulator
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import javax.net.SocketFactory
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The German texts straight from res/values/strings.xml: these Robolectric tests run without merged app
 * resources (no `isIncludeAndroidResources`), so `Resources.getString` cannot resolve app strings.
 */
object GermanStrings {
    private val texts: Map<String, String> = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(File("src/main/res/values/strings.xml")).getElementsByTagName("string").let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as Element }.associate { it.getAttribute("name") to it.textContent }
        }
    private val names: Map<Int, String> = R.string::class.java.fields.associate { it.getInt(null) to it.name }

    fun get(id: Int, args: Array<out Any>): String = String.format(Locale.GERMAN, texts.getValue(names.getValue(id)), *args)
}

class FakePlayer : LivePlayer {
    override var listener: ((PlayerEvent) -> Unit)? = null
    val plays = mutableListOf<String>()
    val sockets = mutableListOf<SocketFactory>()
    var stops = 0
    var released = false
    var frame: Bitmap? = null

    override fun play(url: String, socketFactory: SocketFactory) {
        plays += url
        sockets += socketFactory
    }

    override fun stop() {
        stops++
    }

    override fun release() {
        released = true
    }

    override fun attach(view: TextureView) = Unit
    override fun detach(view: TextureView) = Unit
    override fun capture(maxWidth: Int): Bitmap? = frame

    fun emit(event: PlayerEvent) = listener!!.invoke(event)
}

/** [SIM] Live view logic against the real connection manager (simulator transport) and a fake player. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveViewModelTest {
    private val context = RuntimeEnvironment.getApplication()
    private val sim = RecorderSimulator()
    private val player = FakePlayer()
    private val frames = LiveFrameSource()
    private val ioError = StreamError("ERROR_CODE_IO_UNSPECIFIED", 2000)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.setUp(): Pair<RecorderConnectionManagerImpl, LiveViewModel> {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val manager = managerFor(sim).apply { setSimulator(true) }
        return manager to LiveViewModel(context, manager, player, frames)
    }

    private fun bitmap(w: Int = 64, h: Int = 36) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }

    private fun message(vm: LiveViewModel) = commandMessage(vm.command.value.last!!, GermanStrings::get)

    @Test
    fun `stream starts only when Ready and the screen is started`() = runTest {
        val (manager, vm) = setUp()
        vm.onForeground(true)
        runCurrent()
        assertThat(player.plays).isEmpty() // Disconnected

        manager.connect()
        runCurrent()
        assertThat(player.plays).containsExactly(LiveStream.SIMULATOR_URL)
        assertThat(player.sockets.single()).isSameInstanceAs(SocketFactory.getDefault())
        assertThat(vm.stream.value).isEqualTo(StreamState.Loading)
    }

    @Test
    fun `outside simulator mode RTSP uses the socket factory of the Ready network`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val network = ShadowNetwork.newInstance(7)
        val manager = managerFor(sim, FakeWifi().apply { this.network.value = network })
        val vm = LiveViewModel(context, manager, player, frames)
        manager.connect()
        vm.onForeground(true)
        runCurrent()

        assertThat(player.plays).containsExactly(LiveStream.URL)
        assertThat(player.sockets.single()).isSameInstanceAs(network.socketFactory)
    }

    @Test
    fun `nothing starts in the background, even when Ready`() = runTest {
        val (manager, vm) = setUp()
        manager.connect()
        runCurrent()
        assertThat(player.plays).isEmpty()

        vm.onForeground(true)
        runCurrent()
        assertThat(player.plays).hasSize(1)
    }

    @Test
    fun `stop on background and on disconnect, restart on foreground`() = runTest {
        val (manager, vm) = setUp()
        manager.connect()
        vm.onForeground(true)
        runCurrent()
        player.emit(PlayerEvent.Size(1280, 720))
        assertThat(frames.videoSize.value).isNull() // published only once it plays
        player.emit(PlayerEvent.Playing)
        assertThat(vm.stream.value).isEqualTo(StreamState.Playing)
        assertThat(frames.player.value).isSameInstanceAs(player)
        assertThat(frames.videoSize.value).isEqualTo(IntSize(1280, 720))

        vm.onForeground(false)
        runCurrent()
        assertThat(player.stops).isEqualTo(1)
        assertThat(vm.stream.value).isEqualTo(StreamState.Off)
        assertThat(frames.player.value).isNull()
        assertThat(frames.videoSize.value).isNull()

        vm.onForeground(true)
        runCurrent()
        assertThat(player.plays).hasSize(2)

        manager.disconnect()
        runCurrent()
        assertThat(player.stops).isEqualTo(2)
        assertThat(vm.stream.value).isEqualTo(StreamState.Off)
    }

    @Test
    fun `one delayed automatic retry, then an error state with a manual retry`() = runTest {
        val (manager, vm) = setUp()
        manager.connect()
        vm.onForeground(true)
        runCurrent()

        player.emit(PlayerEvent.Failed(ioError))
        assertThat(vm.stream.value).isEqualTo(StreamState.Loading)
        advanceTimeBy(LiveViewModel.RETRY_DELAY_MS - 1)
        assertThat(player.plays).hasSize(1)
        advanceTimeBy(2)
        assertThat(player.plays).hasSize(2)

        player.emit(PlayerEvent.Failed(ioError))
        advanceTimeBy(LiveViewModel.RETRY_DELAY_MS * 2)
        assertThat(player.plays).hasSize(2)
        assertThat(vm.stream.value).isEqualTo(StreamState.Failed(ioError))

        vm.retry()
        assertThat(player.plays).hasSize(3)
        assertThat(vm.stream.value).isEqualTo(StreamState.Loading)

        // After it played, a later drop gets its own automatic retry; buffering after playing is Buffering.
        player.emit(PlayerEvent.Playing)
        player.emit(PlayerEvent.Buffering)
        assertThat(vm.stream.value).isEqualTo(StreamState.Buffering)
        player.emit(PlayerEvent.Failed(StreamError.ENDED))
        advanceTimeBy(LiveViewModel.RETRY_DELAY_MS + 1)
        assertThat(player.plays).hasSize(4)
    }

    @Test
    fun `a stop cancels the pending automatic retry`() = runTest {
        val (manager, vm) = setUp()
        manager.connect()
        vm.onForeground(true)
        runCurrent()
        player.emit(PlayerEvent.Failed(ioError))

        vm.onForeground(false)
        advanceTimeBy(LiveViewModel.RETRY_DELAY_MS * 2)
        assertThat(player.plays).hasSize(1)
        assertThat(vm.stream.value).isEqualTo(StreamState.Off)
    }

    @Test
    fun `stream errors get a German reason by Media3 code group`() {
        assertThat(StreamError("ERROR_CODE_IO_UNSPECIFIED", 2000).reason).isEqualTo(R.string.live_err_network)
        assertThat(StreamError("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", 2001).reason).isEqualTo(R.string.live_err_network)
        assertThat(StreamError("ERROR_CODE_PARSING_CONTAINER_MALFORMED", 3001).reason).isEqualTo(R.string.live_err_format)
        assertThat(StreamError("ERROR_CODE_DECODER_INIT_FAILED", 4001).reason).isEqualTo(R.string.live_err_decoder)
        assertThat(StreamError("ERROR_CODE_DECODING_FORMAT_UNSUPPORTED", 4005).reason).isEqualTo(R.string.live_err_decoder)
        assertThat(StreamError.ENDED.reason).isEqualTo(R.string.live_err_ended)
        assertThat(StreamError.NOT_BOUND.reason).isEqualTo(R.string.live_err_not_bound)
        assertThat(StreamError("ERROR_CODE_UNSPECIFIED", 1000).reason).isEqualTo(R.string.live_err_other)
    }

    @Test
    fun `photo, burst and record send the traced bodies`() = runTest {
        val (manager, vm) = setUp()
        manager.connect()
        runCurrent()

        vm.takePhoto(burst = false)
        runCurrent()
        vm.takePhoto(burst = true)
        runCurrent()
        vm.record()
        runCurrent()

        assertThat(sim.received.filter { it.msgId == 12292 || it.msgId == 12293 }.map { it.json }).containsExactly(
            """{"msgId":12292,"token":123,"param":{"chanNo":1,"interval":3,"number":1}}""",
            """{"msgId":12292,"token":123,"param":{"chanNo":1,"interval":3,"number":5}}""",
            """{"msgId":12293,"token":123,"param":{"chanNo":1,"recType":1}}""",
        ).inOrder()
    }

    @Test
    fun `replies are shown as reported`() = runTest {
        sim.replies[12292] =
            """{"msgId":12292,"rval":0,"param":{"chanNo":1,"filePath":"/mnt/sd/photo/P001.jpg","thmPath":"/t.jpg","fileTime":"2026-10-01 12:00:00"}}"""
        val (manager, vm) = setUp()
        manager.connect()
        vm.takePhoto(burst = false)
        runCurrent()
        assertThat(message(vm)).isEqualTo("Foto – Recorder meldet: /mnt/sd/photo/P001.jpg\nZeit laut Recorder: 2026-10-01 12:00:00")

        vm.record() // the simulator's default reply: rval 0, no param
        runCurrent()
        assertThat(message(vm)).isEqualTo("Aufnahme – Recorder meldet: OK ohne Dateipfad (rval 0)")
    }

    @Test
    fun `the record countdown is UI only and a reply ends it`() = runTest {
        sim.silentMsgIds += 12293
        val (manager, vm) = setUp()
        manager.connect()
        vm.record()
        runCurrent()
        assertThat(vm.command.value.recordSecondsLeft).isEqualTo(10)
        advanceTimeBy(3_000)
        runCurrent()
        assertThat(vm.command.value.recordSecondsLeft).isEqualTo(7)
        advanceTimeBy(7_000)
        runCurrent()
        assertThat(vm.command.value.recordSecondsLeft).isNull()
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(message(vm)).isEqualTo("Aufnahme: Ergebnis unbekannt – neu verbinden und prüfen (Keine Antwort innerhalb der Wartezeit (Code -205))")

        sim.silentMsgIds -= 12293
        vm.record()
        runCurrent()
        assertThat(vm.command.value.recordSecondsLeft).isNull()
        assertThat(vm.command.value.last!!.result).isInstanceOf(RecorderResult.Ok::class.java)
        assertThat(sim.received.map { it.msgId }).doesNotContain(12294) // never a stop command
    }

    @Test
    fun `a late record reply resolves the unknown outcome`() = runTest {
        sim.silentMsgIds += 12293
        val (manager, vm) = setUp()
        manager.connect()
        vm.record()
        advanceTimeBy(11_000)
        runCurrent()
        assertThat(message(vm)).startsWith("Aufnahme: Ergebnis unbekannt")

        sim.inject("""{"msgId":12293,"rval":0,"param":{"filePath":"/mnt/sd/manual/M001.mp4"}}""", seq = 4321)
        runCurrent()
        assertThat(message(vm)).isEqualTo("Aufnahme, verspätete Antwort – Recorder meldet: /mnt/sd/manual/M001.mp4")
        assertThat(vm.command.value.extra).isNull()
    }

    @Test
    fun `further burst replies arrive unmatched and are counted, with rval meaning`() = runTest {
        val (manager, vm) = setUp()
        manager.connect()
        vm.takePhoto(burst = true)
        runCurrent()
        sim.inject("""{"msgId":12292,"rval":0,"param":{"chanNo":1,"filePath":"/b2.jpg"}}""", seq = 4242)
        runCurrent()
        assertThat(vm.command.value.extra!!.count).isEqualTo(1)
        assertThat(replyText(vm.command.value.extra!!.last, GermanStrings::get)).isEqualTo("/b2.jpg")

        sim.inject("""{"msgId":12292,"rval":303}""", seq = 4243)
        runCurrent()
        val extra = vm.command.value.extra!!
        assertThat(extra.action).isEqualTo(LiveAction.BURST)
        assertThat(extra.count).isEqualTo(2)
        assertThat(replyText(extra.last, GermanStrings::get)).isEqualTo("Foto fehlgeschlagen (Code 303)")

        vm.takePhoto(burst = false)
        runCurrent()
        assertThat(vm.command.value.extra).isNull()
    }

    @Test
    fun `recorder errors keep the app meaning and the raw code`() = runTest {
        sim.rvalOverrides[12292] = 303
        sim.rvalOverrides[12293] = 311
        val (manager, vm) = setUp()
        manager.connect()
        vm.takePhoto(burst = true)
        runCurrent()
        assertThat(message(vm)).isEqualTo("5er-Serie – Recorder meldet Fehler: Foto fehlgeschlagen (Code 303)")
        vm.record()
        runCurrent()
        assertThat(message(vm)).isEqualTo("Aufnahme – Recorder meldet Fehler: Manuelle Aufnahme fehlgeschlagen (Code 311)")
    }

    @Test
    fun `screenshot files follow the folder contract`() {
        val id = "0f8fad5b-d9cb-469f-a165-70867728950e"
        val at = ZonedDateTime.of(2026, 10, 1, 17, 42, 8, 123_000_000, ZoneId.of("Europe/Berlin"))
        val shot = saveScreenshot(context, bitmap(), at, id)

        val dir = File(context.filesDir, "screenshots")
        assertThat(shot.file).isEqualTo(File(dir, "$id.jpg"))
        assertThat(dir.list()!!.sorted()).containsExactly("$id.jpg", "$id.json")
        assertThat(File(dir, "$id.json").readText())
            .isEqualTo("""{"id":"$id","capturedAt":"2026-10-01T17:42:08+02:00","source":"live","width":64,"height":36}""")
        val exif = ExifInterface(shot.file)
        assertThat(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)).isEqualTo("2026:10:01 17:42:08")
        assertThat(exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)).isEqualTo("+02:00")
        val decoded = BitmapFactory.decodeFile(shot.file.path)
        assertThat(decoded.width to decoded.height).isEqualTo(64 to 36)
    }

    @Test
    fun `screenshot grabs the current frame, or says there is none`() = runTest {
        val (_, vm) = setUp()
        vm.screenshot()
        assertThat(vm.events.first()).isEqualTo(LiveEvent.NoFrame)

        player.frame = bitmap()
        vm.screenshot()
        assertThat(vm.events.first()).isInstanceOf(LiveEvent.ScreenshotSaved::class.java)
        assertThat(File(context.filesDir, "screenshots").list()!!.count { it.endsWith(".json") }).isEqualTo(1)
    }

    @Test
    fun `frames are sampled only while the stream plays and only when wanted`() = runTest {
        val (manager, vm) = setUp()
        manager.connect()
        vm.onForeground(true)
        runCurrent()
        player.frame = bitmap(1280, 720)
        val got = mutableListOf<Frame>()
        var wanted = true
        backgroundScope.launch { frames.frames(targetFps = 10, wanted = { wanted }).collect { got += it } }
        advanceTimeBy(500)
        assertThat(got).isEmpty()

        player.emit(PlayerEvent.Playing)
        advanceTimeBy(500)
        assertThat(got).isNotEmpty()
        assertThat(got.first().bitmap.width).isAtMost(LiveStream.MAX_FRAME_WIDTH)

        wanted = false
        var count = got.size
        advanceTimeBy(500)
        assertThat(got).hasSize(count)

        wanted = true
        vm.onForeground(false)
        runCurrent()
        count = got.size
        advanceTimeBy(500)
        assertThat(got).hasSize(count)

        assertThrows(IllegalArgumentException::class.java) { frames.frames(0) }
        assertThrows(IllegalArgumentException::class.java) { frames.frames(LiveFrameSource.MAX_FPS + 1) }
    }
}
