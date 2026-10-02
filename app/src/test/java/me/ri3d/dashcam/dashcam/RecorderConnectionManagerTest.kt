package me.ri3d.dashcam.dashcam

import android.net.Network
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowNetwork
import me.ri3d.dashcam.R
import me.ri3d.dashcam.core.ui.UiText
import me.ri3d.dashcam.dashcam.RecorderConnectionState.Connecting
import me.ri3d.dashcam.dashcam.RecorderConnectionState.Disconnected
import me.ri3d.dashcam.dashcam.RecorderConnectionState.Error
import me.ri3d.dashcam.dashcam.RecorderConnectionState.Negotiating
import me.ri3d.dashcam.dashcam.RecorderConnectionState.NoWifi
import me.ri3d.dashcam.dashcam.RecorderConnectionState.Ready
import me.ri3d.dashcam.dashcam.RecorderConnectionState.TcpConnected
import me.ri3d.dashcam.dashcam.RecorderConnectionState.WrongWifi
import me.ri3d.dashcam.recorder.ErrorCodes
import me.ri3d.dashcam.recorder.RecorderCommand
import me.ri3d.dashcam.recorder.RecorderDiagnostic
import me.ri3d.dashcam.recorder.RecorderError
import me.ri3d.dashcam.recorder.RecorderResult
import me.ri3d.dashcam.recorder.RecorderSimulator

/** [SIM] Connection manager state machine against RecorderSimulator. Robolectric only for android.net.Network. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RecorderConnectionManagerTest {
    private val sim = RecorderSimulator()
    private val deviceInfo = """{"msgId":4098,"rval":0,"param":{"productModel":"EXAMPLE-MODEL","fwVersion":"V0.0.0"}}"""

    @Test
    fun `missing key is a configuration error before any Wi-Fi or socket work`() = runTest {
        val wifi = FakeWifi()
        val manager = managerFor(sim, wifi, rsaKey = "")

        manager.connect()

        val state = manager.state.value as Error
        assertThat(state.retry).isFalse()
        assertThat(state.error.code).isEqualTo(ErrorCodes.SESSION_KEY_INVALID)
        assertThat(errorMeaning(state.error)).isEqualTo(R.string.dashcam_err_key_missing)
        assertThat(wifi.activeRequests).isEqualTo(0)
        assertThat(sim.connectAttempts).isEqualTo(0)
    }

    @Test
    fun `an unreadable key is a configuration error too`() = runTest {
        val manager = managerFor(sim, rsaKey = "not a key")

        manager.connect()

        assertThat(errorMeaning((manager.state.value as Error).error)).isEqualTo(R.string.dashcam_err_key_unreadable)
    }

    @Test
    fun `TCP connected, negotiating and ready are separate states, then device info arrives`() = runTest {
        sim.replies[4098] = deviceInfo
        sim.replyDelayMs = 500
        val slow = SlowFirstWrite(sim, holdMs = 1_000)
        val manager = managerFor(sim, transport = { slow })
        manager.setSimulator(true)
        val states = mutableListOf<RecorderConnectionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { manager.state.toList(states) }

        manager.connect()
        advanceTimeBy(1_000)
        runCurrent()

        assertThat(states.map { it::class }).containsAtLeast(Disconnected::class, Connecting::class, TcpConnected::class, Negotiating::class, Ready::class).inOrder()
        val ready = manager.state.value as Ready
        assertThat(ready.info?.productModel).isEqualTo("EXAMPLE-MODEL")
        assertThat(ready.network).isNull() // simulator mode: no binding
        assertThat(states.filterIsInstance<Ready>().first().info).isNull() // Ready is published before 4098 returns
    }

    @Test
    fun `an unanswered session start ends in the 4096 session timeout with retry`() = runTest {
        sim.silentMsgIds += 1
        val manager = managerFor(sim).apply { setSimulator(true) }

        manager.connect()

        val state = manager.state.value as Error
        assertThat(state.error.code).isEqualTo(ErrorCodes.SESSION_TIMEOUT)
        assertThat(state.retry).isTrue()
        assertThat(errorMeaning(state.error)).isEqualTo(R.string.dashcam_err_session_timeout)
    }

    @Test
    fun `no Wi-Fi network within the wait is NoWifi`() = runTest {
        val manager = managerFor(sim)

        manager.connect()

        assertThat(manager.state.value).isEqualTo(NoWifi)
        assertThat(sim.connectAttempts).isEqualTo(0)
    }

    @Test
    fun `a foreign SSID is a hint and connecting anyway binds the Wi-Fi network`() = runTest {
        val network = ShadowNetwork.newInstance(7)
        val wifi = FakeWifi().apply {
            this.network.value = network
            ssid = "HomeWifi"
        }
        var boundTo: Network? = null
        val manager = managerFor(sim, wifi, transport = { boundTo = it; sim })

        manager.connect()
        assertThat(manager.state.value).isEqualTo(WrongWifi("HomeWifi"))
        assertThat(sim.connectAttempts).isEqualTo(0)

        manager.connect(ignoreSsid = true)
        val ready = manager.state.value as Ready
        assertThat(ready.network).isEqualTo(network)
        assertThat(boundTo).isEqualTo(network)
        assertThat(manager.recorderNetwork.value).isEqualTo(network)
        assertThat(manager.ssid.value).isEqualTo("HomeWifi")
    }

    @Test
    fun `unknown SSID (no permission) connects without asking`() = runTest {
        val wifi = FakeWifi().apply { network.value = ShadowNetwork.newInstance(3) }
        val manager = managerFor(sim, wifi)

        manager.connect()

        assertThat(manager.state.value).isInstanceOf(Ready::class.java)
    }

    @Test
    fun `losing the recorder network stops the session`() = runTest {
        val wifi = FakeWifi().apply {
            network.value = ShadowNetwork.newInstance(5)
            ssid = "FORTHING-ABC123"
        }
        val manager = managerFor(sim, wifi)
        manager.connect()
        assertThat(manager.state.value).isInstanceOf(Ready::class.java)

        wifi.network.value = null
        advanceTimeBy(100)
        runCurrent()

        assertThat(manager.state.value).isEqualTo(NoWifi)
        assertThat(sim.closed).isTrue()
        assertThat(manager.recorderNetwork.value).isNull()
        val failed = manager.request(RecorderCommand.GetDeviceInfo, { it })
        assertThat((failed as RecorderResult.Failed).error.code).isEqualTo(ErrorCodes.SEND_FAILED)
    }

    @Test
    fun `heartbeat loss after ready is an error with retry`() = runTest {
        val manager = managerFor(sim).apply { setSimulator(true) }
        manager.connect()
        sim.silentMsgIds += 3

        advanceTimeBy(15_000)
        runCurrent()

        val state = manager.state.value as Error
        assertThat(state.error).isEqualTo(RecorderError(ErrorCodes.HEARTBEAT_LOST, RecorderError.Source.TIMEOUT, null, "no successful keepalive for more than 10 ticks"))
        assertThat(state.retry).isTrue()
    }

    @Test
    fun `disconnect releases the Wi-Fi request and returns to Disconnected`() = runTest {
        val wifi = FakeWifi().apply { network.value = ShadowNetwork.newInstance(9) }
        val manager = managerFor(sim, wifi)
        manager.connect()
        runCurrent()
        assertThat(wifi.activeRequests).isEqualTo(1)

        manager.disconnect()
        runCurrent()

        assertThat(manager.state.value).isEqualTo(Disconnected)
        assertThat(wifi.activeRequests).isEqualTo(0)
        assertThat(sim.closed).isTrue()
    }

    @Test
    fun `disconnect cancels a pending attempt so the next connect is not swallowed`() = runTest {
        val manager = managerFor(sim) // waits for a Wi-Fi network that never comes
        val first = launch { manager.connect() }
        runCurrent()
        assertThat(manager.state.value).isEqualTo(Connecting)

        manager.disconnect()
        manager.setSimulator(true)
        manager.connect()

        assertThat(manager.state.value).isInstanceOf(Ready::class.java)
        assertThat(first.isCompleted).isTrue()
    }

    @Test
    fun `sdStatus and recStatus notifications are kept, unknown values raw`() = runTest {
        val manager = managerFor(sim).apply { setSimulator(true) }
        manager.connect()

        sim.inject("""{"msgId":16384,"param":{"type":"sdStatus","info":{"driver":1,"status":9}}}""")
        sim.inject("""{"msgId":16384,"param":{"type":"recStatus","info":{"chanNo":1,"status":1}}}""")
        runCurrent()

        assertThat(manager.sdStatus.value?.rawStatus).isEqualTo(9)
        assertThat(sdStatusText(manager.sdStatus.value)).isEqualTo(UiText.Res(R.string.dashcam_sd_status_unknown, listOf(9)))
        assertThat(recStatusText(manager.recStatus.value!!)).isEqualTo(UiText.Res(R.string.dashcam_rec_status_normal, listOf(1)))

        manager.disconnect()

        assertThat(manager.sdStatus.value).isNull()
        assertThat(manager.recStatus.value).isNull()
    }

    @Test
    fun `a second connect joins the running attempt instead of starting another`() = runTest {
        val slow = SlowFirstWrite(sim, holdMs = 1_000)
        val manager = managerFor(sim, transport = { slow }).apply { setSimulator(true) }

        val first = launch { manager.connect() }
        val second = launch { manager.connect() }
        first.join()
        second.join()

        assertThat(manager.state.value).isInstanceOf(Ready::class.java)
        assertThat(sim.connectAttempts).isEqualTo(1)
        assertThat(sim.received.count { it.msgId == 1 }).isEqualTo(1)
    }

    @Test
    fun `a make-before-break Wi-Fi switch is not a loss`() = runTest {
        val a = ShadowNetwork.newInstance(11)
        val b = ShadowNetwork.newInstance(12)
        val wifi = FakeWifi().apply { network.value = a }
        val manager = managerFor(sim, wifi)
        manager.connect()

        wifi.network.value = b // the callback emits the new network; the old loss emits nothing (LatestNetworkCallbackTest)
        advanceTimeBy(100)
        runCurrent()

        val ready = manager.state.value as Ready
        assertThat(ready.network).isEqualTo(a) // the control socket stays bound to the network it was opened on
        assertThat(manager.recorderNetwork.value).isEqualTo(b)
        assertThat(sim.closed).isFalse()
    }

    @Test
    fun `no HTTP client without the recorder Wi-Fi outside simulator mode`() = runTest {
        val manager = managerFor(sim)

        assertThrows(RecorderNotBoundException::class.java) { manager.httpClient() }

        manager.setSimulator(true)
        assertThat(manager.httpClient()).isNotNull()
    }

    @Test
    fun `the session reply stays in the diagnostics log after the ring buffer rolled over`() = runTest {
        val manager = managerFor(sim).apply { setSimulator(true) }
        manager.connect()

        advanceTimeBy(4L * 4_000 * RecorderConnectionManagerImpl.LOG_SIZE) // keepalives fill the log many times over
        runCurrent()

        val log = manager.diagnosticLog()
        assertThat(log.size).isEqualTo(RecorderConnectionManagerImpl.LOG_SIZE + 1)
        val first = log.first() as RecorderDiagnostic.FrameReceived
        assertThat(first.seq).isEqualTo(1)
        assertThat(first.json).contains("\"aescode\":\"***\"")
    }

    @Test
    fun `requests pass through and media URLs use the recorder address`() = runTest {
        sim.replies[4098] = deviceInfo
        val manager = managerFor(sim).apply { setSimulator(true) }
        manager.connect()

        val reply = manager.request(RecorderCommand.GetDeviceInfo, { it.rawJson })

        assertThat((reply as RecorderResult.Ok).value).isEqualTo(deviceInfo)
        assertThat(manager.mediaUrl("/DCIM/clip.mp4")).isEqualTo("http://192.168.42.1/DCIM/clip.mp4")
        assertThat(manager.mediaUrl("DCIM/clip.jpg")).isEqualTo("http://192.168.42.1/DCIM/clip.jpg")
        assertThat(manager.diagnosticLog()).isNotEmpty()
    }
}
