package me.ri3d.cam.recorder

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Messages the recorder sends on its own initiative on the control socket. */
sealed interface RecorderNotification {
    /**
     * msgId 16384: `param` object with `type` and `info`. Not acknowledged; rval, token and sequence are not
     * validated (as traced), so a missing rval is fine here.
     */
    data class Normal(val type: String?, val info: NormalInfo, val raw: RecorderReply) : RecorderNotification

    /** msgId 16385: `param` array of events. The client has already sent the automatic acknowledgement. */
    data class Event(val list: List<EventInfo>, val raw: RecorderReply) : RecorderNotification

    /**
     * My Forthing addition: a message whose sequence matches no pending request, e.g. a further reply to a
     * burst photo (reply multiplicity is unverified) or an unknown msgId. Kept so nothing is silently lost.
     */
    data class Unmatched(val seq: Int, val reply: RecorderReply) : RecorderNotification

    companion object {
        const val MSG_NORMAL = 16384
        const val MSG_EVENT = 16385
    }
}

/** `info` of a 16384 notification, by `param.type`, exactly the types the active SDK parser recognises. */
sealed interface NormalInfo {
    data object HeartBeatStart : NormalInfo
    data object HeartBeatStop : NormalInfo
    data object DisconnectShutdown : NormalInfo
    data object GSensorError : NormalInfo
    data object SensorError : NormalInfo
    data class SdCapacity(val driver: Int?, val totalSpace: Long?) : NormalInfo
    data class FileNew(
        val driver: Int?,
        val fileType: Int?,
        val fileName: String?,
        val fileThm: String?,
        val fileTime: String?,
        val pathType: Int?,
    ) : NormalInfo
    data class FileDel(val driver: Int?, val fileType: Int?, val fileName: String?, val pathType: Int?) : NormalInfo
    data class UpgradeStatus(val status: String?, val error: Int?) : NormalInfo

    /** [status] is null for values outside 0..8; [rawStatus] always holds the number. */
    data class SdStatus(val driver: Int?, val status: SdCardStatus?, val rawStatus: Int?) : NormalInfo

    /** Raw status: the SDK enum 0 none / 1 normal / 2 event recording is NOT applied by its parser (unverified). */
    data class RecStatus(val chanNo: Int?, val status: Int?) : NormalInfo
    data class UpdateFileList(val updateDir: Int?) : NormalInfo

    /** Any other type (e.g. the generic SDK names newFile, SDInsert, wifiRestart, which this parser does not know). */
    data class Unknown(val info: JsonElement?) : NormalInfo
}

/** SD status of `sdStatus` notifications. Not to be confused with command failure codes (201 = no card). */
enum class SdCardStatus(val code: Int) {
    NO_CARD(0), EXCEPTION(1), NORMAL(2), EVENT_AREA_FULL(3), INSUFFICIENT_SPACE(4),
    SLOW(5), IMAGE_AREA_FULL(6), UNSUPPORTED_CAPACITY(7), UNSUPPORTED_FILESYSTEM(8);

    companion object {
        fun of(code: Int?): SdCardStatus? = entries.firstOrNull { it.code == code }
    }
}

enum class EventType(val code: Int) {
    CAPTURE(1), DEVICE_WAKE_UP(2), PARK_MONITOR(3), CRASH_RECORD(4), CRASH_IMAGE(5), MANUAL_RECORD(6);

    companion object {
        fun of(code: Int?): EventType? = entries.firstOrNull { it.code == code }
    }
}

/** Event entry: `filePath` / `fileThm` / `time` (not the file-list or command-reply spellings). */
data class EventInfo(
    val type: EventType?,
    val rawType: Int?,
    val filePath: String?,
    val fileThm: String?,
    val time: String?,
    val raw: JsonObject,
)

fun parseNormalNotification(reply: RecorderReply): RecorderNotification.Normal {
    val p = reply.param.asObject()
    val type = p.str("type")
    val i = p.obj("info") ?: EMPTY_OBJECT
    val info = when (type) {
        "heart_beat_start" -> NormalInfo.HeartBeatStart
        "heart_beat_stop" -> NormalInfo.HeartBeatStop
        "disconnectShutdown" -> NormalInfo.DisconnectShutdown
        "gsensorErr" -> NormalInfo.GSensorError
        "sensorErr" -> NormalInfo.SensorError
        "sdCap" -> NormalInfo.SdCapacity(i.int("driver"), i.long("totalSpace"))
        "fileNew" -> NormalInfo.FileNew(
            i.int("driver"), i.int("fileType"), i.str("fileName"), i.str("fileThm"), i.str("fileTime"), i.int("pathType"),
        )
        "fileDel" -> NormalInfo.FileDel(i.int("driver"), i.int("fileType"), i.str("fileName"), i.int("pathType"))
        "upgradeStatus" -> NormalInfo.UpgradeStatus(i.str("status"), i.int("error"))
        "sdStatus" -> NormalInfo.SdStatus(i.int("driver"), SdCardStatus.of(i.int("status")), i.int("status"))
        "recStatus" -> NormalInfo.RecStatus(i.int("chanNo"), i.int("status"))
        "updateFileList" -> NormalInfo.UpdateFileList(i.int("updateDir"))
        else -> NormalInfo.Unknown(p["info"])
    }
    return RecorderNotification.Normal(type, info, reply)
}

/** The active SDK branch parses `param` as an array; any other shape yields an empty list (raw kept). */
fun parseEventNotification(reply: RecorderReply): RecorderNotification.Event {
    val list = (reply.param as? JsonArray).objects().map {
        EventInfo(EventType.of(it.int("type")), it.int("type"), it.str("filePath"), it.str("fileThm"), it.str("time"), it)
    }
    return RecorderNotification.Event(list, reply)
}
