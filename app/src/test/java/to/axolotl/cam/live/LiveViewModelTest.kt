package to.axolotl.cam.live

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.TextureView
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import org.w3c.dom.Element
import to.axolotl.cam.R
import to.axolotl.cam.dashcam.RecorderConnectionManagerImpl
import to.axolotl.cam.dashcam.managerFor
import to.axolotl.cam.plates.Frame
import to.axolotl.cam.recorder.RecorderResult
import to.axolotl.cam.recorder.RecorderSimulator
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import javax.net.SocketFactory

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
    var stops = 0
    var released = false
    var frame: Bitmap? = null

    override fun play(url: String, socketFactory: SocketFactory) {
        plays += url
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

/** [SIM] Live view logic against the real connection manager in simulator mode and a fake player. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveViewModelTest {
    private val context = RuntimeEnvironment.getApplication()
    private val sim = RecorderSimulator()
    private val player = FakePlayer()
    private val frames = LiveFrameSource()

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.setUp(): Pair<RecorderConnectionManagerImpl, LiveViewModel> {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val manager = managerFor(sim).apply { setSimulator(true) }
        return manager to LiveViewModel(context, manager, player, frames)
    }

    private fun bitmap(w: Int = 64, h: Int = 36) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }

    @Test
    fun `stream starts only when Ready and the screen is started`() = runTest {
        val (manager, vm) = setUp()
        vm.onForeground(true)
        runCurrent()
        assertThat(player.plays).isEmpty() // Disconnected

        manager.connect()
        runCurrent()
        assertThat(player.plays).containsExactly(LiveStream.SIMULATOR_URL)
        assertThat(vm.stream.value).isEqualTo(StreamState.Loading)
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
        player.emit(PlayerEvent.Playing)
        assertThat(vm.stream.value).isEqualTo(StreamState.Playing)
        assertThat(frames.player).isSameInstanceAs(player)

        vm.onForeground(false)
        runCurrent()
        assertThat(player.stops).isEqualTo(1)
        assertThat(vm.stream.value).isEqualTo(StreamState.Off)
        assertThat(frames.player).isNull()
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
    fun `one automatic retry, then an error state with a manual retry`() = runTest {
        val (manager, vm) = setUp()
        manager.connect()
        vm.onForeground(true)
        runCurrent()

        player.emit(PlayerEvent.Failed("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"))
        assertThat(player.plays).hasSize(2)
        assertThat(vm.stream.value).isEqualTo(StreamState.Loading)

        player.emit(PlayerEvent.Failed("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"))
        assertThat(player.plays).hasSize(2)
        assertThat(vm.stream.value).isEqualTo(StreamState.Failed("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"))

        vm.retry()
        assertThat(player.plays).hasSize(3)
        assertThat(vm.stream.value).isEqualTo(StreamState.Loading)

        // After it played, a later drop gets its own automatic retry; buffering after playing is Buffering.
        player.emit(PlayerEvent.Playing)
        player.emit(PlayerEvent.Buffering)
        assertThat(vm.stream.value).isEqualTo(StreamState.Buffering)
        player.emit(PlayerEvent.Failed(ExoLivePlayer.STREAM_ENDED))
        assertThat(player.plays).hasSize(4)
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
        assertThat(commandMessage(vm.command.value.last!!, GermanStrings::get))
            .isEqualTo("Foto – Recorder meldet: /mnt/sd/photo/P001.jpg\nZeit laut Recorder: 2026-10-01 12:00:00")

        vm.record() // the simulator's default reply: rval 0, no param
        runCurrent()
        assertThat(commandMessage(vm.command.value.last!!, GermanStrings::get)).isEqualTo("Aufnahme – Recorder meldet: OK ohne Dateipfad (rval 0)")
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
        assertThat(sim.received.map { it.msgId }).doesNotContain(12294) // no stop command
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(commandMessage(vm.command.value.last!!, GermanStrings::get))
            .isEqualTo("Aufnahme: Ergebnis unbekannt – neu verbinden und prüfen (Keine Antwort innerhalb der Wartezeit (Code -205))")

        sim.silentMsgIds -= 12293
        vm.record()
        runCurrent()
        assertThat(vm.command.value.recordSecondsLeft).isNull()
        assertThat(vm.command.value.last!!.result).isInstanceOf(RecorderResult.Ok::class.java)
    }

    @Test
    fun `further burst replies arrive unmatched and are counted`() = runTest {
        val (manager, vm) = setUp()
        manager.connect()
        vm.takePhoto(burst = true)
        runCurrent()
        sim.inject("""{"msgId":12292,"rval":0,"param":{"chanNo":1,"filePath":"/b2.jpg"}}""", seq = 4242)
        sim.inject("""{"msgId":12292,"rval":0,"param":{"chanNo":1,"filePath":"/b3.jpg"}}""", seq = 4243)
        runCurrent()
        assertThat(vm.command.value.extraReplies).isEqualTo(2)
        assertThat(vm.command.value.lastExtraPath).isEqualTo("/b3.jpg")

        vm.takePhoto(burst = false)
        runCurrent()
        assertThat(vm.command.value.extraReplies).isEqualTo(0)
    }

    @Test
    fun `recorder errors keep the app meaning and the raw code`() = runTest {
        sim.rvalOverrides[12292] = 303
        sim.rvalOverrides[12293] = 311
        val (manager, vm) = setUp()
        manager.connect()
        vm.takePhoto(burst = true)
        runCurrent()
        assertThat(commandMessage(vm.command.value.last!!, GermanStrings::get))
            .isEqualTo("5er-Serie – Recorder meldet Fehler: Foto fehlgeschlagen (Code 303)")
        vm.record()
        runCurrent()
        assertThat(commandMessage(vm.command.value.last!!, GermanStrings::get))
            .isEqualTo("Aufnahme – Recorder meldet Fehler: Manuelle Aufnahme fehlgeschlagen (Code 311)")
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
    fun `frames are sampled only while the stream plays`() = runTest {
        val (manager, vm) = setUp()
        manager.connect()
        vm.onForeground(true)
        runCurrent()
        player.frame = bitmap(1280, 720)
        val got = mutableListOf<Frame>()
        backgroundScope.launch { frames.frames(targetFps = 10).collect { got += it } }
        advanceTimeBy(500)
        assertThat(got).isEmpty()

        player.emit(PlayerEvent.Playing)
        advanceTimeBy(500)
        assertThat(got).isNotEmpty()
        assertThat(got.first().bitmap.width).isAtMost(LiveStream.MAX_FRAME_WIDTH)

        vm.onForeground(false)
        runCurrent()
        val count = got.size
        advanceTimeBy(500)
        assertThat(got).hasSize(count)
    }
}
