package me.ri3d.cam.dashcam

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import me.ri3d.cam.recorder.RecorderSimulator
import me.ri3d.cam.recorder.RecorderTransport
import java.io.ByteArrayOutputStream

/** [SIM] The diagnostics export must not carry any secret the recorder or the app handled. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DiagnosticsTest {
    @Test
    fun `the export contains no token, aescode, session key or Wi-Fi password`() = runTest {
        val sim = RecorderSimulator().apply {
            replies[4097] = settingsReply(wifi = """{"mode":0,"ssid":"FORTHING-X","passwd":"Secret123","frequency":0}""")
        }
        val received = ByteArrayOutputStream() // what the client read, incl. the plaintext session reply
        val recording = object : RecorderTransport by sim {
            override suspend fun read(buf: ByteArray): Int = sim.read(buf).also { if (it > 0) received.write(buf, 0, it) }
        }
        val manager = managerFor(sim, transport = { recording }).apply { setSimulator(true) }
        manager.connect()
        runCurrent()

        val export = captureDiagnostics(manager).toString()

        val aescode = checkNotNull(Regex("\"aescode\":\"([^\"]+)\"").find(received.toString(Charsets.ISO_8859_1.name()))).groupValues[1]
        val secrets = listOf(aescode, "Secret123", sim.sessionKeyHex)
        for (secret in secrets) {
            assertThat(export).doesNotContain(secret)
            assertThat(export).doesNotContain(secret.toByteArray().joinToString("") { "%02x".format(it) }) // frame hex
        }
        assertThat(Regex("\"(token|tokenNum)\"\\s*:\\s*${sim.token}\\b").containsMatchIn(export)).isFalse()
        assertThat(export).contains("\"passwd\":\"***\"") // the 4097 reply is there, masked
        assertThat(export).contains("\"aescode\":\"***\"") // so is the session reply
    }
}
