package me.ri3d.cam.recorder

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A decoded reply or unsolicited message. [rval] is -1 when the field is absent (as traced: missing rval is a
 * failure). [rawJson] is the full decrypted body; [toString] shows it redacted only.
 */
data class RecorderReply(val msgId: Int, val rval: Int, val param: JsonElement?, val rawJson: String) {
    override fun toString() = "RecorderReply(msgId=$msgId, rval=$rval, json=${Redactor.redact(rawJson)})"

    companion object {
        /**
         * Null unless [json] is an object with an integer msgId (the SDK drops such messages too). Anything after
         * the last `}` (e.g. non-NUL block padding left by the decryption) is cut off first.
         */
        fun parse(json: String): RecorderReply? {
            val text = json.lastIndexOf('}').let { if (it >= 0) json.substring(0, it + 1) else json }
            val o = parseObject(text) ?: return null
            return RecorderReply(o.int("msgId") ?: return null, o.int("rval") ?: -1, o["param"], text)
        }
    }
}

sealed interface RecorderResult<out T> {
    data class Ok<T>(val value: T, val reply: RecorderReply) : RecorderResult<T>
    data class Failed(val error: RecorderError) : RecorderResult<Nothing>
}

/** [code] is the recorder's rval or a local code from [ErrorCodes]; interpret it with [ErrorCodes] only. */
data class RecorderError(val code: Int, val source: Source, val rawJson: String?, val message: String?) {
    enum class Source { RECORDER, LOCAL, TIMEOUT }

    override fun toString() =
        "RecorderError(code=$code, source=$source, message=$message, json=${rawJson?.let(Redactor::redact)})"
}

class RecorderException(val error: RecorderError) : Exception(error.message ?: "recorder error ${error.code}")

// ---- Typed replies. Every model keeps its raw JSON object; parsers never throw and never drop unknown fields.

/** 4098. productType of the session (0 HIKVISION / 1 OTHER) does not identify the OEM model; this does. */
data class DeviceInfo(
    val productModel: String?,
    val productSN: String?,
    val fwVersion: String?,
    val fwBuildDate: String?,
    val hwVersion: String?,
    val mcuFwVersion: String?,
    val paramVersion: String?,
    val verifyCode: String?,
    val dateTime: String?,
    val semifinishProductSN: String?,
    val raw: JsonObject,
)

fun parseDeviceInfo(reply: RecorderReply): DeviceInfo = reply.param.asObject().let {
    DeviceInfo(
        productModel = it.str("productModel"), productSN = it.str("productSN"), fwVersion = it.str("fwVersion"),
        fwBuildDate = it.str("fwBuildDate"), hwVersion = it.str("hwVersion"), mcuFwVersion = it.str("mcuFwVersion"),
        paramVersion = it.str("paramVersion"), verifyCode = it.str("verifyCode"), dateTime = it.str("dateTime"),
        semifinishProductSN = it.str("semifinishProductSN"), raw = it,
    )
}

/** 4099. Units of the space values are not established. */
data class StorageInfo(
    val totalSpace: Long?,
    val available: Long?,
    val residualLife: String?,
    val healthStatus: String?,
    val raw: JsonObject,
)

fun parseStorageInfo(reply: RecorderReply): StorageInfo = reply.param.asObject().let {
    StorageInfo(it.long("totalSpace"), it.long("available"), it.str("residualLife"), it.str("healthStatus"), it)
}

/**
 * 4097: `param.withoutChan` (object) + `param.withChan` (array of channel objects). [raw] holds the Wi-Fi
 * password in clear text (needed to resubmit it); [toString] prints it redacted.
 */
data class RecorderSettings(val global: GlobalSettings, val channels: List<ChannelSettings>, val raw: JsonObject) {
    override fun toString() = "RecorderSettings(global=$global, channels=$channels, raw=${Redactor.redact(raw.toString())})"
}

/** [toString] redacts [raw] (it contains `wifi.passwd`); [wifi] redacts itself. */
data class GlobalSettings(
    val poweroffDelay: Int?,
    val recordSwitch: Int?,
    val parkMonitor: Int?,
    val eventRecCycle: Int?,
    val picCycle: Int?,
    val normalVideoTime: Int?,
    val manualVideoTime: Int?,
    val gSensorSensitivity: Int?,
    val wifi: WifiParam?,
    val timeLapseVideo: TimeLapse?,
    val raw: JsonObject,
) {
    override fun toString() = "GlobalSettings(poweroffDelay=$poweroffDelay, recordSwitch=$recordSwitch, " +
        "parkMonitor=$parkMonitor, eventRecCycle=$eventRecCycle, picCycle=$picCycle, normalVideoTime=$normalVideoTime, " +
        "manualVideoTime=$manualVideoTime, gSensorSensitivity=$gSensorSensitivity, wifi=$wifi, " +
        "timeLapseVideo=$timeLapseVideo, raw=${Redactor.redact(raw.toString())})"
}

data class ChannelSettings(
    val chanNo: Int?,
    val videoResolution: Int?,
    val frameRate: Int?,
    val soundSwitch: Int?,
    val wdrSwitch: Int?,
    val distCorr: Int?,
    val faceDetect: Int?,
    val privateInfo: Int?,
    val osd: OsdInfo?,
    val raw: JsonObject,
)

/** Wi-Fi mode is taken verbatim: no inversion (the SDK's read-all parser swaps 0 and 1). */
fun parseSettings(reply: RecorderReply): RecorderSettings {
    val p = reply.param.asObject()
    val g = p.obj("withoutChan") ?: EMPTY_OBJECT
    val global = GlobalSettings(
        poweroffDelay = g.int("poweroffDelay"), recordSwitch = g.int("recordSwitch"), parkMonitor = g.int("parkMonitor"),
        eventRecCycle = g.int("eventRecCycle"), picCycle = g.int("picCycle"), normalVideoTime = g.int("normalVideoTime"),
        manualVideoTime = g.int("manualVideoTime"), gSensorSensitivity = g.int("gSensorSensitivity"),
        wifi = g.obj("wifi")?.let { WifiParam(it.int("mode"), it.str("ssid"), it.str("passwd"), it.int("frequency")) },
        timeLapseVideo = g.obj("timeLapseVideo")?.let {
            TimeLapse(it.int("sampleInterval"), it.int("playFrameRate"), it.int("totalRecordTime"))
        },
        raw = g,
    )
    val channels = p.objects("withChan").map { c ->
        ChannelSettings(
            chanNo = c.int("chanNo"), videoResolution = c.int("videoResolution"), frameRate = c.int("frameRate"),
            soundSwitch = c.int("soundSwitch"), wdrSwitch = c.int("wdrSwitch"), distCorr = c.int("distCorr"),
            faceDetect = c.int("faceDetect"), privateInfo = c.int("privateInfo"),
            // a malformed osdContent (e.g. the SDK's "[I@…" string echoed back) reads as empty; raw keeps it
            osd = c.obj("osd")?.let { OsdInfo(it.int("enableOSD") ?: 0, it.ints("osdContent")) },
            raw = c,
        )
    }
    return RecorderSettings(global, channels, p)
}

/** 4100 entry: `fileName` / `fileThm` / `fileTime` (not the command-reply or event spellings). */
data class RecorderFile(val fileName: String?, val fileThm: String?, val fileTime: String?, val raw: JsonObject)

/** [totalFileSize] unit unknown. The next page's cursor is the last entry's exact [RecorderFile.fileName]. */
data class FileList(val totalFileNum: Int?, val totalFileSize: Long?, val fileList: List<RecorderFile>, val raw: JsonObject)

fun parseFileList(reply: RecorderReply): FileList {
    val p = reply.param.asObject()
    val files = p.objects("fileList").map { RecorderFile(it.str("fileName"), it.str("fileThm"), it.str("fileTime"), it) }
    return FileList(p.int("totalFileNum"), p.long("totalFileSize"), files, p)
}

/**
 * 12292 photo (chanNo, filePath, thmPath, fileTime), 12293 record start (filePath, thmPath, fileTime),
 * 12294 record stop (chanNo). Note `thmPath`, unlike `fileThm` in file lists and events.
 */
data class CaptureResult(val chanNo: Int?, val filePath: String?, val thmPath: String?, val fileTime: String?, val raw: JsonObject)

fun parseCaptureResult(reply: RecorderReply): CaptureResult = reply.param.asObject().let {
    CaptureResult(it.int("chanNo"), it.str("filePath"), it.str("thmPath"), it.str("fileTime"), it)
}
