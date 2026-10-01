package to.axolotl.cam.recorder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.Properties

/**
 * Test fixture: TCP front end for [RecorderSimulator], for the app's debug "Simulator (10.0.2.2:7878)" mode on the
 * emulator (`./gradlew :recorder:runSimulator`). One simulator per accepted connection, real wire format, scripted
 * fictional replies. 8192 changes are applied to the scripted 4097 state so the app's readback confirms them;
 * 12288 frees the card, 12289 restores the defaults. After the first encrypted request it sends an sdStatus and a
 * recStatus notification and one manual-record event. 20485 is never answered (exercises the app's -205 path).
 * 4100 / 4101 page and delete [files], which [SimulatorHttpServer] serves (`main` starts both).
 */
class SimulatorTcpServer(
    private val keyPair: KeyPair,
    private val files: SimulatedFiles = SimulatedFiles(),
    private val log: (String) -> Unit = ::println,
) {
    private val lock = Any()
    private var global = DEFAULT_GLOBAL // guarded by lock
    private var channel = DEFAULT_CHANNEL // guarded by lock
    private var available = 12034L // guarded by lock

    /** Accepts connections until [server] is closed. */
    fun serve(server: ServerSocket) = runBlocking(Dispatchers.IO) {
        while (!server.isClosed) {
            val socket = try { server.accept() } catch (e: IOException) { break }
            launch { handle(socket) }
        }
    }

    private suspend fun handle(socket: Socket) = coroutineScope {
        log("client connected: ${socket.remoteSocketAddress}")
        val sim = RecorderSimulator(keyPair = keyPair)
        sim.silentMsgIds += 20485
        sim.handlers[4100] = files::listReply
        sim.handlers[4101] = files::deleteReply
        sim.connect("simulator", socket.localPort, 0)
        script(sim)
        val out = socket.getOutputStream()
        val pump = launch {
            val buf = ByteArray(8192)
            try {
                while (true) {
                    val n = sim.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    out.flush()
                }
            } catch (e: IOException) { /* client gone */ }
        }
        try {
            val input = socket.getInputStream()
            val buf = ByteArray(8192)
            var seen = 0
            var notified = false
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                sim.write(buf.copyOf(n))
                while (seen < sim.received.size) {
                    val request = sim.received[seen++]
                    if (request.msgId != RecorderCommand.KeepAlive.msgId) log("<- ${Redactor.redact(request.json)}")
                    apply(request.json)
                    script(sim) // before the next request is read, so a following 4097 sees the change
                }
                if (!notified && sim.received.any { it.encrypted }) { // the client has the session key now
                    notified = true
                    NOTIFICATIONS.forEach { sim.inject(it) }
                }
            }
        } catch (e: IOException) {
            // connection reset
        } finally {
            sim.close()
            socket.close()
            pump.cancel()
            log("client disconnected")
        }
    }

    private fun apply(json: String) {
        val request = RecorderReply.parse(json) ?: return
        synchronized(lock) {
            when (request.msgId) {
                8192 -> {
                    val (chan, glob) = (request.param as? JsonObject ?: return).filterKeys { it != "chanNo" }.entries
                        .partition { it.key in CHANNEL_KEYS }
                    channel = JsonObject(channel + chan.map { it.toPair() })
                    global = JsonObject(global + glob.map { it.toPair() })
                }
                12288 -> available = TOTAL_SPACE
                12289 -> {
                    global = DEFAULT_GLOBAL
                    channel = DEFAULT_CHANNEL
                }
            }
        }
    }

    private fun script(sim: RecorderSimulator) = synchronized(lock) {
        sim.replies[4097] = """{"msgId":4097,"rval":0,"param":{"withoutChan":$global,"withChan":[$channel]}}"""
        sim.replies[4099] =
            """{"msgId":4099,"rval":0,"param":{"totalSpace":$TOTAL_SPACE,"available":$available,"residualLife":"90","healthStatus":"good"}}"""
        sim.replies.putAll(STATIC_REPLIES)
    }

    companion object {
        private const val TOTAL_SPACE = 30528L
        private val CHANNEL_KEYS = setOf("videoResolution", "frameRate", "soundSwitch", "wdrSwitch", "distCorr", "faceDetect", "privateInfo", "osd")
        private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject

        // Fictional values in the shapes of the protocol report (same as the :recorder test fixtures).
        private val DEFAULT_GLOBAL = obj(
            """{"poweroffDelay":10,"recordSwitch":1,"parkMonitor":0,"eventRecCycle":1,"picCycle":1,"normalVideoTime":3,""" +
                """"manualVideoTime":10,"gSensorSensitivity":2,"wifi":{"mode":0,"ssid":"FORTHING-SIM001","passwd":"Sim12345","frequency":0},""" +
                """"timeLapseVideo":{"sampleInterval":1,"playFrameRate":25,"totalRecordTime":60}}""",
        )
        private val DEFAULT_CHANNEL = obj(
            """{"chanNo":1,"videoResolution":0,"frameRate":0,"soundSwitch":1,"wdrSwitch":0,"distCorr":0,"privateInfo":0,""" +
                """"faceDetect":0,"osd":{"enableOSD":1,"osdContent":[0,1]}}""",
        )
        private val STATIC_REPLIES = mapOf(
            4098 to """{"msgId":4098,"rval":0,"param":{"productModel":"SIMULATOR","productSN":"SIM-0001","fwVersion":"SIM 0.0.0",""" +
                """"fwBuildDate":"2026-01-01","hwVersion":"0x0000","mcuFwVersion":"MCU-SIM","paramVersion":"P-SIM","verifyCode":"SIM",""" +
                """"dateTime":"2026-10-01 12:00:00","semifinishProductSN":"SIM-SEMI"}}""",
            20480 to """{"msgId":20480,"rval":0,"param":{"basic":1,"imageEncode":1,"network":1,"storage":1,"intelligence":0}}""",
            20481 to """{"msgId":20481,"rval":0,"param":{"totalSensor":1,"poweroffDelay":[0,10,60],"factoryRestore":1,""" +
                """"rtspServer":[{"chanNo":1,"url":"rtsp://192.168.42.1/ch1/sub/av_stream","auth":0}],"deleteFile":1,""" +
                """"supportReboot":0,"recordSwitch":1,"supportShutdown":0,"supportCanComm":0,"gSensorSensitivity":[1,2,3],"parkMonitor":1}}""",
            20482 to """{"msgId":20482,"rval":0,"param":[{"chanNo":1,"videoResolution":[0,1],"frameRate":[0]}]}""",
            20483 to """{"msgId":20483,"rval":0,"param":{"type":0,"wifi":{"mode":[0]},"wifiFrequency":[0],"wifiPwdSetting":1,"wifiSsidSetting":1}}""",
            20484 to """{"msgId":20484,"rval":0,"param":{"SDStatus":1,"sdDriver":[1],"normalVideoTime":[1,3,5]}}""",
        )
        private val NOTIFICATIONS = listOf(
            """{"msgId":16384,"param":{"type":"sdStatus","info":{"driver":1,"status":2}}}""",
            """{"msgId":16384,"param":{"type":"recStatus","info":{"chanNo":1,"status":1}}}""",
            """{"msgId":16385,"param":[{"type":6,"filePath":"/sim/manual.mp4","fileThm":"/sim/manual.jpg","time":"2026-10-01 12:00:00"}]}""",
        )

        /** The public half is derived from the private CRT key, so the app's configured key works unchanged. */
        fun keyPairFrom(privateKeyMaterial: String): KeyPair {
            val private = SessionCrypto.loadPrivateKey(privateKeyMaterial.toByteArray()) as? RSAPrivateCrtKey
                ?: error("dashcam.rsaKey has no CRT parameters; the public key cannot be derived")
            val public = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(private.modulus, private.publicExponent))
            return KeyPair(public, private)
        }
    }
}

/**
 * `./gradlew :recorder:runSimulator` → args: path of local.properties, optional port (default 7878), optional media
 * HTTP port (default 8080), optional HTTP throttle in bytes per second (default 0 = unthrottled).
 * Reads `dashcam.rsaKey` itself (never printed). Without it, a throwaway key is generated and printed with
 * instructions; that key is a test key, not the vendor key.
 */
fun main(args: Array<String>) {
    val properties = args.getOrNull(0)?.let(::File)?.takeIf(File::isFile)?.let { file ->
        Properties().apply { file.reader().use(::load) }
    }
    val configured = properties?.getProperty("dashcam.rsaKey")?.takeIf { it.isNotBlank() }
    val keyPair = if (configured != null) {
        println("Using dashcam.rsaKey from local.properties (not printed).")
        SimulatorTcpServer.keyPairFrom(configured)
    } else {
        RecorderSimulator.generateKeyPair().also {
            println("local.properties has no dashcam.rsaKey. Generated a throwaway test key. For the app to talk to this")
            println("simulator, add the line below to local.properties, rebuild the debug app and restart this task.")
            println("Remove it again before connecting to a real recorder (it needs the vendor key).")
            println("dashcam.rsaKey=" + Base64.getEncoder().encodeToString(it.private.encoded))
        }
    }
    val port = args.getOrNull(1)?.toIntOrNull() ?: RecorderClient.DEFAULT_PORT
    val httpPort = args.getOrNull(2)?.toIntOrNull() ?: 8080
    val throttle = args.getOrNull(3)?.toLongOrNull() ?: 0L
    val files = SimulatedFiles()
    val http = try {
        SimulatorHttpServer(files, httpPort, throttle, log = ::println).also {
            println("Media HTTP on 127.0.0.1:${it.port} (emulator: http://10.0.2.2:${it.port})" + if (throttle > 0) ", $throttle bytes/s" else "")
        }
    } catch (e: BindException) {
        println("Port $httpPort is busy: continuing without the media HTTP server (listings work, downloads do not).")
        null
    }
    try {
        ServerSocket(port, 50, InetAddress.getLoopbackAddress()).use { server ->
            println("Recorder simulator listening on ${server.inetAddress.hostAddress}:$port (emulator: 10.0.2.2:$port). Ctrl+C stops it.")
            SimulatorTcpServer(keyPair, files).serve(server)
        }
    } finally {
        http?.close()
    }
}
