package me.ri3d.cam.dashcam

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.ri3d.cam.R
import me.ri3d.cam.core.ui.UiText
import me.ri3d.cam.recorder.ErrorCodes
import me.ri3d.cam.recorder.ErrorCodes.AppMeaning
import me.ri3d.cam.recorder.NormalInfo
import me.ri3d.cam.recorder.RecorderError
import me.ri3d.cam.recorder.SdCardStatus

// Presentation of raw protocol values. The raw code is always shown next to the meaning; meanings come only
// from the original app's resource table (recorder rval) or the local code list, never from the SDK's AE enum.

/** Meaning of an error: recorder rvals via the app table, local codes via the local list. */
@StringRes
fun errorMeaning(error: RecorderError): Int = when (error.source) {
    RecorderError.Source.RECORDER -> when (error.code) {
        ErrorCodes.MISSING_RVAL -> R.string.dashcam_err_missing_rval
        else -> ErrorCodes.appMeaning(error.code)?.text ?: R.string.dashcam_err_unknown_code
    }
    RecorderError.Source.LOCAL, RecorderError.Source.TIMEOUT -> when (error.code) {
        ErrorCodes.CONNECT_FAILED -> R.string.dashcam_err_connect
        ErrorCodes.SEND_FAILED -> R.string.dashcam_err_send
        ErrorCodes.PARAM_NOT_ENOUGH -> R.string.dashcam_err_param
        ErrorCodes.SESSION_TIMEOUT -> R.string.dashcam_err_session_timeout
        ErrorCodes.HEARTBEAT_LOST -> R.string.dashcam_err_heartbeat
        ErrorCodes.DISCONNECTED -> R.string.dashcam_err_disconnected
        ErrorCodes.SESSION_KEY_INVALID -> when (error.message) {
            RecorderConnectionManagerImpl.KEY_MISSING -> R.string.dashcam_err_key_missing
            RecorderConnectionManagerImpl.KEY_INVALID -> R.string.dashcam_err_key_unreadable
            else -> R.string.dashcam_err_key_rejected
        }
        ErrorCodes.BAD_REPLY -> R.string.dashcam_err_bad_reply
        ErrorCodes.REQUEST_TIMEOUT -> R.string.dashcam_err_timeout
        else -> R.string.dashcam_err_local
    }
}

/** "Meaning (Code 201)". */
@Composable
fun errorText(error: RecorderError): String =
    stringResource(R.string.dashcam_error_with_code, stringResource(errorMeaning(error)), error.code)

/** The build has no usable RSA key: nothing was tried on the network. */
fun isConfigurationError(error: RecorderError): Boolean =
    error.code == ErrorCodes.SESSION_KEY_INVALID &&
        (error.message == RecorderConnectionManagerImpl.KEY_MISSING || error.message == RecorderConnectionManagerImpl.KEY_INVALID)

/**
 * A state-changing command that ended without a recorder answer (timeout, connection lost) may or may not have been
 * applied: the app must not claim "nicht übernommen".
 */
fun outcomeUnknown(error: RecorderError): Boolean =
    error.source == RecorderError.Source.TIMEOUT || error.code == ErrorCodes.DISCONNECTED

/** "Ergebnis unbekannt – neu verbinden und prüfen (…)" for [outcomeUnknown] errors, else "nicht übernommen: …". */
@Composable
fun commandFailedText(error: RecorderError): String = stringResource(
    if (outcomeUnknown(error)) R.string.dashcam_status_unknown else R.string.dashcam_status_failed,
    errorText(error),
)

private val AppMeaning.text: Int
    get() = when (this) {
        AppMeaning.UNKNOWN_ERROR -> R.string.dashcam_err_app_unknown
        AppMeaning.FAILED_RETRY -> R.string.dashcam_err_app_failed_retry
        AppMeaning.BUSY_RETRY -> R.string.dashcam_err_app_busy
        AppMeaning.ABNORMAL_INFO_RETRY -> R.string.dashcam_err_app_abnormal_info
        AppMeaning.FAILED_REOPEN_APP -> R.string.dashcam_err_app_reopen
        AppMeaning.NO_FILE -> R.string.dashcam_err_app_no_file
        AppMeaning.NO_SD_CARD -> R.string.dashcam_err_app_no_sd
        AppMeaning.SD_DAMAGED -> R.string.dashcam_err_app_sd_damaged
        AppMeaning.SD_ABNORMAL -> R.string.dashcam_err_app_sd_abnormal
        AppMeaning.SD_FILESYSTEM_ABNORMAL -> R.string.dashcam_err_app_sd_fs
        AppMeaning.SD_READ_ONLY -> R.string.dashcam_err_app_sd_read_only
        AppMeaning.SD_INSUFFICIENT_SPACE -> R.string.dashcam_err_app_sd_space
        AppMeaning.FORMAT_FAILED -> R.string.dashcam_err_app_format
        AppMeaning.EVENT_STORAGE_FULL -> R.string.dashcam_err_app_event_full
        AppMeaning.IMAGE_STORAGE_FULL -> R.string.dashcam_err_app_image_full
        AppMeaning.SD_INITIALIZING -> R.string.dashcam_err_app_sd_init
        AppMeaning.SD_SLOW -> R.string.dashcam_err_app_sd_slow
        AppMeaning.RESET_FAILED -> R.string.dashcam_err_app_reset
        AppMeaning.FILE_LIST_FAILED -> R.string.dashcam_err_app_file_list
        AppMeaning.CAPTURE_FAILED -> R.string.dashcam_err_app_capture
        AppMeaning.NOT_IN_PREVIEW -> R.string.dashcam_err_app_not_preview
        AppMeaning.EVENT_RECORDING_ACTIVE -> R.string.dashcam_err_app_event_recording
        AppMeaning.TIMELAPSE_RECORDING_ACTIVE -> R.string.dashcam_err_app_timelapse_recording
        AppMeaning.DELETE_FAILED -> R.string.dashcam_err_app_delete
        AppMeaning.MANUAL_RECORDING_FAILED -> R.string.dashcam_err_app_manual_recording
        AppMeaning.TIMELAPSE_RECORDING_FAILED -> R.string.dashcam_err_app_timelapse_failed
    }

/** `sdStatus` notification (0–8 table of the report); unknown values keep the raw number. */
fun sdStatusText(info: NormalInfo.SdStatus?): UiText {
    val raw = info?.rawStatus ?: return UiText.Res(R.string.dashcam_sd_status_none)
    val label = when (info.status) {
        SdCardStatus.NO_CARD -> R.string.dashcam_sd_status_no_card
        SdCardStatus.EXCEPTION -> R.string.dashcam_sd_status_exception
        SdCardStatus.NORMAL -> R.string.dashcam_sd_status_normal
        SdCardStatus.EVENT_AREA_FULL -> R.string.dashcam_sd_status_event_full
        SdCardStatus.INSUFFICIENT_SPACE -> R.string.dashcam_sd_status_space
        SdCardStatus.SLOW -> R.string.dashcam_sd_status_slow
        SdCardStatus.IMAGE_AREA_FULL -> R.string.dashcam_sd_status_image_full
        SdCardStatus.UNSUPPORTED_CAPACITY -> R.string.dashcam_sd_status_capacity
        SdCardStatus.UNSUPPORTED_FILESYSTEM -> R.string.dashcam_sd_status_filesystem
        null -> return UiText.Res(R.string.dashcam_sd_status_unknown, listOf(raw))
    }
    return UiText.Res(label, listOf(raw))
}

/**
 * `recStatus` stays raw in the parser; the SDK enum (0 none, 1 normal, 2 event) is an unverified reading, so the
 * raw number is always shown and other values make no claim.
 */
fun recStatusText(info: NormalInfo.RecStatus): UiText = when (val raw = info.status) {
    0 -> UiText.Res(R.string.dashcam_rec_status_idle, listOf(raw))
    1 -> UiText.Res(R.string.dashcam_rec_status_normal, listOf(raw))
    2 -> UiText.Res(R.string.dashcam_rec_status_event, listOf(raw))
    null -> UiText.Res(R.string.dashcam_rec_status_missing)
    else -> UiText.Res(R.string.dashcam_rec_status_raw, listOf(raw))
}

/** Raw recorder number with the "laut Recorder" qualifier: units of 4099 values are not established. */
fun rawValue(value: Any?): UiText =
    if (value == null) UiText.Res(R.string.dashcam_value_missing) else UiText.Res(R.string.dashcam_value_raw, listOf(value.toString()))
