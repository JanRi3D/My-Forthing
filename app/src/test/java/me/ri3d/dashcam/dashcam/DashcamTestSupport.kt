package me.ri3d.dashcam.dashcam

import android.net.Network
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.TestScope
import me.ri3d.dashcam.recorder.RecorderSimulator
import me.ri3d.dashcam.recorder.RecorderTransport
import java.util.Base64

/** Wi-Fi without a radio: tests set [network] and [ssid]; [activeRequests] counts open network requests. */
class FakeWifi : RecorderWifi {
    val network = MutableStateFlow<Network?>(null)
    var ssid: String? = null
    var activeRequests = 0

    override fun network(): Flow<Network?> = network.onStart { activeRequests++ }.onCompletion { activeRequests-- }
    override fun currentSsid() = ssid
    override fun mobileDataEnabled() = true
}

/** The simulator's private key in the form local.properties carries it. */
fun RecorderSimulator.keyText(): String = Base64.getEncoder().encodeToString(privateKeyPkcs8)

fun TestScope.managerFor(
    sim: RecorderSimulator,
    wifi: FakeWifi = FakeWifi(),
    rsaKey: String = sim.keyText(),
    transport: (Network?) -> RecorderTransport = { sim },
) = RecorderConnectionManagerImpl(wifi, rsaKey, backgroundScope, transport)

/** Holds the first write (the session start) back, so TcpConnected lasts long enough to be observed. */
class SlowFirstWrite(private val sim: RecorderSimulator, private val holdMs: Long) : RecorderTransport by sim {
    private var first = true

    override suspend fun write(bytes: ByteArray) {
        if (first) {
            first = false
            delay(holdMs)
        }
        sim.write(bytes)
    }
}

// Fictional readback in the shape of the protocol report (4097: withoutChan object + withChan array).
fun settingsReply(
    normalVideoTime: Int = 3,
    wifi: String = """{"mode":1,"ssid":"FORTHING-OLD","passwd":"Old12345","frequency":1}""",
    osd: String = """{"enableOSD":1,"osdContent":[0,1,7]}""",
) = """{"msgId":4097,"rval":0,"param":{"withoutChan":{"poweroffDelay":10,"recordSwitch":1,"parkMonitor":0,""" +
    """"eventRecCycle":1,"normalVideoTime":$normalVideoTime,"gSensorSensitivity":2,"wifi":$wifi},""" +
    """"withChan":[{"chanNo":1,"videoResolution":0,"soundSwitch":1,"wdrSwitch":0,"frameRate":0,"osd":$osd,"futureField":7}]}}"""
