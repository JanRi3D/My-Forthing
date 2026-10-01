package to.axolotl.cam.recorder

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Control commands with the msgIds and `param` shapes of the recovered serializers (protocol report, "Command
 * reference"). Commands the original app sends without `param` have [param] = null and their body is only
 * `{"token":…,"msgId":…}`. Defaults are the values the original app uses.
 */
sealed interface RecorderCommand {
    val msgId: Int

    /** `param` object, or null when the original sends none. */
    val param: JsonObject? get() = null

    /** Plaintext session start (token 0, clientType 1 = PHONE_APP). Sent by [RecorderClient.start] only. */
    data object StartSession : RecorderCommand {
        override val msgId = 1
        override val param = buildJsonObject { put("clientType", 1) }
    }

    /** SDK-only: the original app never sends it (it just disconnects). Receiving msgId 2 ends the session. */
    data object StopSession : RecorderCommand { override val msgId = 2 }

    /** Sent by the client's heartbeat; exposed for completeness. */
    data object KeepAlive : RecorderCommand { override val msgId = 3 }

    /** Read one named setting, e.g. `type = "soundSwitch"`. The reply's `param` shape is not established. */
    data class GetSetting(val type: String, val chanNo: Int = 1) : RecorderCommand {
        override val msgId = 4096
        override val param get() = buildJsonObject { put("chanNo", chanNo); put("type", type) }
    }

    data object GetAllSettings : RecorderCommand { override val msgId = 4097 }

    data object GetDeviceInfo : RecorderCommand { override val msgId = 4098 }

    data class GetStorageInfo(val driver: Int = 1) : RecorderCommand {
        override val msgId = 4099
        override val param get() = buildJsonObject { put("driver", driver) }
    }

    /**
     * Cursor paging as traced: [pageNum] is a batch size (50), not a page index. Start with an empty
     * [lastFileName]; continue with the exact `fileName` of the last returned entry. [type]: see [RecorderValues].
     */
    data class ListFiles(
        val type: Int,
        val lastFileName: String = "",
        val pageNum: Int = 50,
        val driver: Int = 1,
    ) : RecorderCommand {
        override val msgId = 4100
        override val param get() = buildJsonObject {
            put("driver", driver); put("type", type); put("lastFileName", lastFileName); put("pageNum", pageNum)
        }
    }

    /** Device paths (the HTTP base already stripped). An empty list is rejected: the SDK would send a malformed body. */
    data class DeleteFiles(val fileList: List<String>) : RecorderCommand {
        init { require(fileList.isNotEmpty()) { "fileList must not be empty" } }
        override val msgId = 4101
        override val param get() = buildJsonObject { putJsonArray("fileList") { fileList.forEach { add(it) } } }
    }

    data class SetSettings(val patch: SettingsPatch) : RecorderCommand {
        override val msgId = 8192
        override val param get() = RecorderJson.encodeToJsonElement(SettingsPatch.serializer(), patch).jsonObject
    }

    data class FormatStorage(val driver: Int = 1) : RecorderCommand {
        override val msgId = 12288
        override val param get() = buildJsonObject { put("driver", driver) }
    }

    data object FactoryReset : RecorderCommand { override val msgId = 12289 }

    /** Single photo `number = 1`, burst `number = 5` (one request). The unit of [interval] is not established. */
    data class TakePhoto(val chanNo: Int = 1, val interval: Int = 3, val number: Int = 1) : RecorderCommand {
        override val msgId = 12292
        override val param get() = buildJsonObject { put("chanNo", chanNo); put("interval", interval); put("number", number) }
    }

    /** [recType] 1 = manual, 2 = timelapse (the app's buttons). */
    data class StartRecord(val recType: Int, val chanNo: Int = 1) : RecorderCommand {
        override val msgId = 12293
        override val param get() = buildJsonObject { put("chanNo", chanNo); put("recType", recType) }
    }

    /** The app only stops timelapse (recType 2). */
    data class StopRecord(val recType: Int = RecorderValues.RECORD_TIMELAPSE, val chanNo: Int = 1) : RecorderCommand {
        override val msgId = 12294
        override val param get() = buildJsonObject { put("chanNo", chanNo); put("recType", recType) }
    }

    /** 20480..20485, no param. Only BASIC is queried by the original app. */
    data class GetCapabilities(val group: CapabilityGroup) : RecorderCommand {
        override val msgId get() = group.msgId
    }
}

enum class CapabilityGroup(val msgId: Int) {
    ALL(20480), BASIC(20481), IMAGE(20482), NETWORK(20483), STORAGE(20484), INTELLIGENT(20485)
}

/**
 * Logical body before encryption, in the field order the recovered code produces: DTOs with a param write
 * msgId, token, param; parameterless helpers write token, msgId; the session start always uses token 0.
 */
internal fun RecorderCommand.body(token: Int): String {
    val p = param
    return buildJsonObject {
        when {
            this@body is RecorderCommand.StartSession -> { put("token", 0); put("msgId", msgId); put("param", p!!) }
            p == null -> { put("token", token); put("msgId", msgId) }
            else -> { put("msgId", msgId); put("token", token); put("param", p) }
        }
    }.toString()
}

/** Automatic event acknowledgement, exactly as the SDK builds it (no param, no echo of the event). */
internal fun eventAckBody(token: Int) = """{"rval":0,"msgId":16385,"token":$token}"""

/**
 * Settings change (8192). Only non-null fields are serialised, in the SDK serializer's order. UI controls of
 * the original app send `chanNo = 1`; the Wi-Fi password dialog sends only [wifi] (no chanNo).
 * Switches are raw ints (1 on, 0 off); value meanings are in [RecorderValues].
 */
@Serializable
data class SettingsPatch(
    val osd: OsdInfo? = null,
    val timeLapseVideo: TimeLapse? = null,
    val wifi: WifiParam? = null,
    val chanNo: Int? = null,
    val poweroffDelay: Int? = null,
    val videoResolution: Int? = null,
    val frameRate: Int? = null,
    val soundSwitch: Int? = null,
    val normalVideoTime: Int? = null,
    val wdrSwitch: Int? = null,
    val recordSwitch: Int? = null,
    val manualVideoTime: Int? = null,
    val parkMonitor: Int? = null,
    val distCorr: Int? = null,
    val gSensorSensitivity: Int? = null,
    val privateInfo: Int? = null,
    val eventRecCycle: Int? = null,
    val picCycle: Int? = null,
    val faceDetect: Int? = null,
    val activeUploadEnabled: Int? = null,
    val sdDriverId: Int? = null,
)

/**
 * Recorder Wi-Fi. [mode] is kept exactly as received (SDK names 0 = AP, 1 = STA). The SDK's read-all parser
 * inverts it and the original password dialog resubmits that inverted value; this type never converts it, so
 * `current.copy(passwd = new)` resubmits the mode that was read. [frequency]: 0 = 2.4 GHz, 1 = 5 GHz.
 */
@Serializable
data class WifiParam(
    val mode: Int? = null,
    val ssid: String? = null,
    val passwd: String? = null,
    val frequency: Int? = null,
) {
    override fun toString() = "WifiParam(mode=$mode, ssid=$ssid, passwd=${passwd?.let { "***" }}, frequency=$frequency)"
}

/**
 * Overlay. [osdContent] is written as a real JSON array of overlay indexes (0 TIME, 1 SPEED, 2 LIGHT, 3 BRAKE,
 * 4 ACCELERATOR, 5 HIGH_BEAM, 6 LOW_BEAM, 7 LOGO). The SDK wrote a Java array identity ("[I@…") instead.
 */
@Serializable
data class OsdInfo(
    @SerialName("enableOSD") val enableOsd: Int,
    val osdContent: List<Int>,
)

/** SDK-only settings object; units and ranges unknown. */
@Serializable
data class TimeLapse(val sampleInterval: Int? = null, val playFrameRate: Int? = null, val totalRecordTime: Int? = null)

/** Raw protocol values. "App path" = offered by the original app; anything else exists only in the SDK. */
object RecorderValues {
    const val ON = 1
    const val OFF = 0

    /**
     * gSensorSensitivity as the original app's buttons send it; app UI labels (German UI): 1 = Hoch, 2 = Mittel,
     * 3 = Niedrig. The SDK enum names the same numbers the other way round (SENSOR_TYPE_LOW = 1,
     * SENSOR_TYPE_HIGH = 3) and adds 0 = off. Which direction is physically more sensitive needs recorder
     * verification; this module follows the traced app behaviour and sends the raw int.
     */
    const val GSENSOR_HIGH = 1
    const val GSENSOR_MEDIUM = 2
    const val GSENSOR_LOW = 3

    const val RESOLUTION_1080P = 0
    const val RESOLUTION_720P = 1
    val LOOP_MINUTES = listOf(1, 3, 5)
    val POWEROFF_DELAY_SECONDS = listOf(0, 10, 60) // SDK also names 30

    const val WIFI_MODE_AP = 0
    const val WIFI_MODE_STA = 1
    const val WIFI_2_4_GHZ = 0
    const val WIFI_5_GHZ = 1

    /** ListFiles type / file categories. 3 = AP_VIDEO exists in the SDK only. */
    const val FILES_NORMAL = 0
    const val FILES_EVENT = 1
    const val FILES_USER = 2

    const val RECORD_MANUAL = 1
    const val RECORD_TIMELAPSE = 2

    const val CHANNEL_FRONT = 1
}
