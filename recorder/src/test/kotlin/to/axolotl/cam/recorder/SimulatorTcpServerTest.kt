package to.axolotl.cam.recorder

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import kotlin.concurrent.thread

/** [SIM] The debug TCP simulator against the real client over a loopback socket. */
class SimulatorTcpServerTest {
    @Test
    fun `a settings change is applied to the readback and notifications follow the session`() = runBlocking {
        val keyPair = RecorderSimulator.generateKeyPair()
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) { SimulatorTcpServer(keyPair) { }.serve(server) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = RecorderClient({ SocketTransport() }, keyPair.private.encoded, scope)
        try {
            withTimeout(10_000) {
                val sdStatus = async(start = CoroutineStart.UNDISPATCHED) {
                    client.notifications.filterIsInstance<RecorderNotification.Normal>().first { it.type == "sdStatus" }
                }
                client.start("127.0.0.1", server.localPort)
                assertThat(client.state.value).isInstanceOf(SessionState.Ready::class.java)

                val before = client.request(RecorderCommand.GetAllSettings, ::parseSettings) as RecorderResult.Ok
                assertThat(before.value.global.normalVideoTime).isEqualTo(3)
                val set = client.request(RecorderCommand.SetSettings(SettingsPatch(chanNo = 1, normalVideoTime = 5, soundSwitch = 0)), { })
                assertThat(set).isInstanceOf(RecorderResult.Ok::class.java)
                val after = client.request(RecorderCommand.GetAllSettings, ::parseSettings) as RecorderResult.Ok
                assertThat(after.value.global.normalVideoTime).isEqualTo(5)
                assertThat(after.value.channels.single().soundSwitch).isEqualTo(0)
                assertThat(after.value.global.raw).doesNotContainKey("soundSwitch") // channel field stays in withChan

                assertThat((sdStatus.await().info as NormalInfo.SdStatus).status).isEqualTo(SdCardStatus.NORMAL)
                assertThat(client.request(RecorderCommand.GetCapabilities(CapabilityGroup.INTELLIGENT), { it }, timeoutMs = 300))
                    .isEqualTo(RecorderResult.Failed(RecorderError(ErrorCodes.REQUEST_TIMEOUT, RecorderError.Source.TIMEOUT, null, "no reply to msgId 20485 within 300 ms")))
            }
        } finally {
            client.stop()
            scope.cancel()
            server.close()
        }
    }

    @Test
    fun `the public key is derived from the configured private key`() {
        val keyPair = RecorderSimulator.generateKeyPair()
        val derived = SimulatorTcpServer.keyPairFrom(Base64.getEncoder().encodeToString(keyPair.private.encoded))
        assertThat(derived.public).isEqualTo(keyPair.public)
    }
}
