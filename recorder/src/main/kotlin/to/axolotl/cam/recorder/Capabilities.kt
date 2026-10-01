package to.axolotl.cam.recorder

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

// Capability replies 20480..20485. All SDK-only except BASIC (queried by the app's preview, which only logs it).
// Enum-like values stay raw ints (e.g. frameRate 0 = 25 fps, 1 = 30 fps; videoCodec 0 = H264, 1 = H265):
// they are representable values, not proof of support. Presence flags mirror the SDK's `has(key)` checks.
// Fixed SDK defects: network pwd/ssid flags no longer overwrite each other; frameRate is no longer read into
// the aspect-ratio list; image osdContent is read inside "osd" (the SDK read it from the outer object).

/** 20480: section flags (1 = present). */
data class AllCapabilities(
    val basic: Boolean,
    val imageEncode: Boolean,
    val network: Boolean,
    val storage: Boolean,
    val intelligence: Boolean,
    val raw: JsonObject,
)

fun parseAllCapabilities(reply: RecorderReply): AllCapabilities = reply.param.asObject().let {
    AllCapabilities(it.flag("basic"), it.flag("imageEncode"), it.flag("network"), it.flag("storage"), it.flag("intelligence"), it)
}

data class RtspServer(val chanNo: Int?, val url: String?, val auth: Int?, val raw: JsonObject)

/** 20481. */
data class BasicCapabilities(
    val totalSensor: Int?,
    val poweroffDelay: List<Int>,
    val factoryRestore: Boolean,
    val rtspServer: List<RtspServer>,
    val deleteFile: Boolean,
    val supportReboot: Boolean,
    val recordSwitch: Boolean,
    val supportShutdown: Boolean,
    val supportCanComm: Boolean,
    val gSensorSensitivity: List<Int>,
    val parkMonitor: Boolean,
    val privateInfo: Boolean,
    val updateFirmwarePath: String?,
    val downloadPath: String?,
    val raw: JsonObject,
)

fun parseBasicCapabilities(reply: RecorderReply): BasicCapabilities = reply.param.asObject().let {
    BasicCapabilities(
        totalSensor = it.int("totalSensor"),
        poweroffDelay = it.ints("poweroffDelay"),
        factoryRestore = it.containsKey("factoryRestore"),
        rtspServer = it.objects("rtspServer").map { s -> RtspServer(s.int("chanNo"), s.str("url"), s.int("auth"), s) },
        deleteFile = it.flag("deleteFile"),
        supportReboot = it.flag("supportReboot"),
        recordSwitch = it.containsKey("recordSwitch"),
        supportShutdown = it.flag("supportShutdown"),
        supportCanComm = it.flag("supportCanComm"),
        gSensorSensitivity = it.ints("gSensorSensitivity"),
        parkMonitor = it.containsKey("parkMonitor"),
        privateInfo = it.flag("privateInfo"),
        updateFirmwarePath = it.str("updateFirmwarePath"),
        downloadPath = it.str("downloadPath"),
        raw = it,
    )
}

/** One entry of the 20482 reply, whose `param` is an array of per-channel objects. */
data class ImageCapabilities(
    val chanNo: Int?,
    val videoResolution: List<Int>,
    val frameRate: List<Int>,
    val aspectRatio: List<Int>,
    val videoCodec: List<Int>,
    val audioCodec: List<Int>,
    val smartCodec: List<Int>,
    val avContainerFormat: List<Int>,
    val subRec: Boolean,
    /** Raw `osd.supportOSD` (SDK: presence) or `osd.supportOsd` (other SDK class: `== 1`); needs recorder verification. */
    val supportOsd: Int?,
    val hasOsd: Boolean,
    val osdContent: List<Int>,
    val distCorr: Boolean,
    val wdrSwitch: Boolean,
    val soundSwitch: Boolean,
    val raw: JsonObject,
)

fun parseImageCapabilities(reply: RecorderReply): List<ImageCapabilities> = (reply.param as? JsonArray).objects().map {
    val osd = it.obj("osd")
    ImageCapabilities(
        chanNo = it.int("chanNo"),
        videoResolution = it.ints("videoResolution"),
        frameRate = it.ints("frameRate"),
        aspectRatio = it.ints("aspectRatio"),
        videoCodec = it.ints("videoCodec"),
        audioCodec = it.ints("audioCodec"),
        smartCodec = it.ints("smartCodec"),
        avContainerFormat = it.ints("avContainerFormat"),
        subRec = it.containsKey("subRec"),
        supportOsd = osd?.let { o -> o.int("supportOSD") ?: o.int("supportOsd") },
        hasOsd = osd != null,
        osdContent = osd?.ints("osdContent")?.takeIf { c -> c.isNotEmpty() } ?: it.ints("osdContent"),
        distCorr = it.containsKey("distCorr"),
        wdrSwitch = it.containsKey("wdrSwitch"),
        soundSwitch = it.containsKey("soundSwitch"),
        raw = it,
    )
}

/** 20483. */
data class NetworkCapabilities(
    val type: Int?,
    val wifiModes: List<Int>,
    val wifiFrequencies: List<Int>,
    val wifiPwdSetting: Boolean,
    val wifiSsidSetting: Boolean,
    val raw: JsonObject,
)

fun parseNetworkCapabilities(reply: RecorderReply): NetworkCapabilities = reply.param.asObject().let {
    val wifi = it.obj("wifi") ?: EMPTY_OBJECT
    NetworkCapabilities(
        type = it.int("type"),
        wifiModes = wifi.ints("mode"),
        wifiFrequencies = wifi.ints("wifiFrequency"),
        wifiPwdSetting = wifi.flag("wifiPwdSetting"),
        wifiSsidSetting = wifi.flag("wifiSsidSetting"),
        raw = it,
    )
}

data class SdDriverCapability(val supportDriver: Int?, val supportFormat: Boolean, val raw: JsonObject)

/** 20484. Ranges are `[min, max]` arrays in the SDK parser. */
data class StorageCapabilities(
    val sdStatus: List<Int>,
    val sdDriver: List<SdDriverCapability>,
    val normalVideoTime: List<Int>,
    val manualVideoTime: List<Int>,
    val timeLapseSampleInterval: IntRange?,
    val timeLapsePlayFrameRate: IntRange?,
    val timeLapseTotalRecordTime: IntRange?,
    val eventRecCycle: Boolean,
    val picCycle: Boolean,
    val raw: JsonObject,
)

fun parseStorageCapabilities(reply: RecorderReply): StorageCapabilities = reply.param.asObject().let {
    val timeLapse = it.obj("timeLapseVideo") ?: EMPTY_OBJECT
    fun range(key: String) = timeLapse.ints(key).takeIf { r -> r.size >= 2 }?.let { r -> r[0]..r[1] }
    StorageCapabilities(
        sdStatus = it.ints("SDStatus"),
        sdDriver = it.objects("sdDriver").map { d -> SdDriverCapability(d.int("supportDriver"), d.flag("supportFormat"), d) },
        normalVideoTime = it.ints("normalVideoTime"),
        manualVideoTime = it.ints("manualVideoTime"),
        timeLapseSampleInterval = range("sampleInterval"),
        timeLapsePlayFrameRate = range("playFrameRate"),
        timeLapseTotalRecordTime = range("totalRecordTime"),
        eventRecCycle = it.containsKey("eventRecCycle"),
        picCycle = it.containsKey("picCycle"),
        raw = it,
    )
}

/** 20485: presence flags. */
data class IntelligentCapabilities(
    val faceDetect: Boolean,
    val trafficLightDetect: Boolean,
    val frontCarReminding: Boolean,
    val raw: JsonObject,
)

fun parseIntelligentCapabilities(reply: RecorderReply): IntelligentCapabilities = reply.param.asObject().let {
    IntelligentCapabilities(it.containsKey("faceDetect"), it.containsKey("trafficLightDetect"), it.containsKey("frontCarReminding"), it)
}
