package to.axolotl.cam.recorder

/**
 * Result-code knowledge kept as data, with the three sources of the protocol report strictly separate:
 *  1. [APP_MEANINGS]: the original app's display resources (strongest app-facing evidence).
 *  2. [HAT_NAMES]: the SDK's HAT constant table (more specific internal names; not all are emitted).
 *  3. Local codes: synthesised on the phone, never sent by the recorder.
 * The SDK's generic "AE" error enum contradicts both tables (e.g. it calls 3 "invalid token" and 201 a Wi-Fi
 * error) and is deliberately NOT reproduced or used. Always keep the raw rval and response JSON; these tables
 * only explain them. SD notification status values (see [SdCardStatus]) are a different code space.
 */
object ErrorCodes {
    // ---- 3. Local codes
    /** Reply without rval (SDK: `optInt("rval", -1)`); still source RECORDER. */
    const val MISSING_RVAL = -1
    /** SDK HAT_CONNECT_FAILED: TCP connect failed after the traced retry. */
    const val CONNECT_FAILED = -101
    /** SDK HAT_SEND_REQUEST_FAILED: not connected / not ready / socket write failed. */
    const val SEND_FAILED = -102
    /** SDK HAT_PARAM_NOT_ENOUGH; not produced by this module (commands validate their arguments). */
    const val PARAM_NOT_ENOUGH = -103
    /** SessionApi's synthetic result when the session start is not answered within 5 s. Not command 4096. */
    const val SESSION_TIMEOUT = 4096
    // Axolotl Cam additions (no vendor equivalent):
    /** No successful keepalive for more than 10 heartbeat ticks (~11 s). */
    const val HEARTBEAT_LOST = -201
    /** Connection closed by the recorder, by the socket, or the recorder ended the session (msgId 2). */
    const val DISCONNECTED = -202
    /** RSA key unusable, `aescode` missing/undecryptable, or the session key is not 16+ bytes of hex. */
    const val SESSION_KEY_INVALID = -203
    /** A reply parser rejected the reply. */
    const val BAD_REPLY = -204
    /** No reply within the request timeout (none is traced; the client defaults to 10 s). Source TIMEOUT. */
    const val REQUEST_TIMEOUT = -205

    val LOCAL_DESCRIPTIONS: Map<Int, String> = mapOf(
        MISSING_RVAL to "reply without rval",
        CONNECT_FAILED to "connect failed",
        SEND_FAILED to "send failed / not ready",
        PARAM_NOT_ENOUGH to "insufficient parameters",
        SESSION_TIMEOUT to "session start timed out (5 s)",
        HEARTBEAT_LOST to "heartbeat lost",
        DISCONNECTED to "disconnected",
        SESSION_KEY_INVALID to "session key invalid",
        BAD_REPLY to "reply could not be parsed",
        REQUEST_TIMEOUT to "request timed out",
    )

    // ---- 1. Original app resources (decoded/res/values/arrays.xml via PreviewPresenter.getErrorMsg)
    enum class AppMeaning {
        UNKNOWN_ERROR, FAILED_RETRY, BUSY_RETRY, ABNORMAL_INFO_RETRY, FAILED_REOPEN_APP, NO_FILE,
        NO_SD_CARD, SD_DAMAGED, SD_ABNORMAL, SD_FILESYSTEM_ABNORMAL, SD_READ_ONLY, SD_INSUFFICIENT_SPACE,
        FORMAT_FAILED, EVENT_STORAGE_FULL, IMAGE_STORAGE_FULL, SD_INITIALIZING, SD_SLOW,
        RESET_FAILED, FILE_LIST_FAILED, CAPTURE_FAILED, NOT_IN_PREVIEW, EVENT_RECORDING_ACTIVE,
        TIMELAPSE_RECORDING_ACTIVE, DELETE_FAILED, MANUAL_RECORDING_FAILED, TIMELAPSE_RECORDING_FAILED,
    }

    /**
     * rval → app message meaning. Index 0 of the app's array is its generic fallback text ("connection failed,
     * reopen later"), not a failure code, so 0 is absent here; unknown codes return null and the original app
     * shows that fallback. 204's "format the card" advice is message text, not evidence.
     */
    val APP_MEANINGS: Map<Int, AppMeaning> = buildMap {
        put(1, AppMeaning.UNKNOWN_ERROR)
        put(2, AppMeaning.FAILED_RETRY)
        put(3, AppMeaning.BUSY_RETRY)
        for (c in 4..7) put(c, AppMeaning.ABNORMAL_INFO_RETRY)
        put(101, AppMeaning.FAILED_REOPEN_APP)
        for (c in 102..106) put(c, AppMeaning.FAILED_RETRY)
        put(107, AppMeaning.NO_FILE)
        put(201, AppMeaning.NO_SD_CARD)
        put(202, AppMeaning.SD_DAMAGED)
        put(203, AppMeaning.SD_ABNORMAL)
        put(204, AppMeaning.SD_FILESYSTEM_ABNORMAL)
        put(205, AppMeaning.SD_ABNORMAL)
        put(206, AppMeaning.SD_ABNORMAL)
        put(207, AppMeaning.SD_READ_ONLY)
        put(208, AppMeaning.SD_INSUFFICIENT_SPACE)
        put(209, AppMeaning.FORMAT_FAILED)
        put(210, AppMeaning.SD_ABNORMAL)
        put(211, AppMeaning.EVENT_STORAGE_FULL)
        put(212, AppMeaning.IMAGE_STORAGE_FULL)
        put(213, AppMeaning.SD_INITIALIZING)
        put(214, AppMeaning.SD_SLOW)
        put(301, AppMeaning.RESET_FAILED)
        put(302, AppMeaning.FILE_LIST_FAILED)
        put(303, AppMeaning.CAPTURE_FAILED)
        put(304, AppMeaning.NOT_IN_PREVIEW)
        put(308, AppMeaning.EVENT_RECORDING_ACTIVE)
        put(309, AppMeaning.TIMELAPSE_RECORDING_ACTIVE)
        put(310, AppMeaning.DELETE_FAILED)
        put(311, AppMeaning.MANUAL_RECORDING_FAILED)
        put(312, AppMeaning.TIMELAPSE_RECORDING_FAILED)
        put(501, AppMeaning.ABNORMAL_INFO_RETRY)
        put(502, AppMeaning.ABNORMAL_INFO_RETRY)
    }

    // ---- 2. SDK HAT constant table (DashcamErrorCode)
    val HAT_NAMES: Map<Int, String> = mapOf(
        0 to "OK",
        1 to "UNKNOWN_ERROR",
        2 to "OPERATION_UNSUPPORTED",
        3 to "SYSTEM_BUSY",
        5 to "NO_MORE_MEMORY",
        101 to "INVALID_TOKEN",
        102 to "JSON_PACKAGE_ERROR",
        103 to "SUB_PACKAGE_TIMEOUT",
        104 to "JSON_SYNTAX_ERROR",
        105 to "INVALID_OPTION_VALUE",
        106 to "INVALID_PARAM",
        107 to "INVALID_PATH",
        201 to "SD_CARD_NOT_EXIST",
        202 to "SD_CARD_DAMAGED",
        203 to "SD_CARD_MOUNT_FAILED",
        204 to "SD_CARD_FS_ERROR",
        205 to "SD_CARD_CREATE_FS_FAILED",
        206 to "SD_CARD_CREATE_PARTITION_FAILED",
        207 to "SD_CARD_PROTECTED",
        301 to "RESET_FAILED",
        302 to "FILE_LIST_GET_FAILED",
        501 to "SENSOR_ERROR",
        502 to "G_SENSOR_ERROR",
    )

    fun appMeaning(rval: Int): AppMeaning? = APP_MEANINGS[rval]
}
