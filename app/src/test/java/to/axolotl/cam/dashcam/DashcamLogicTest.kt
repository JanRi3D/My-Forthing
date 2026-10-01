package to.axolotl.cam.dashcam

import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Test
import to.axolotl.cam.BuildConfig
import to.axolotl.cam.R
import to.axolotl.cam.core.ui.UiText
import to.axolotl.cam.recorder.ErrorCodes
import to.axolotl.cam.recorder.NormalInfo
import to.axolotl.cam.recorder.RecorderError
import to.axolotl.cam.recorder.RecorderError.Source.LOCAL
import to.axolotl.cam.recorder.RecorderError.Source.RECORDER
import to.axolotl.cam.recorder.RecorderError.Source.TIMEOUT
import to.axolotl.cam.recorder.RecorderReply
import to.axolotl.cam.recorder.SdCardStatus
import to.axolotl.cam.recorder.SessionCrypto
import to.axolotl.cam.recorder.WifiParam
import to.axolotl.cam.recorder.parseSettings
import java.security.interfaces.RSAPrivateKey

/** Pure mappings and settings helpers (no Android). */
class DashcamLogicTest {
    private fun settings(json: String = settingsReply()) = parseSettings(checkNotNull(RecorderReply.parse(json)))

    @Test
    fun `the configured dashcam key loads`() {
        assumeTrue("dashcam.rsaKey not configured in local.properties", BuildConfig.DASHCAM_RSA_KEY.isNotBlank())
        val key = SessionCrypto.loadPrivateKey(BuildConfig.DASHCAM_RSA_KEY.toByteArray()) as RSAPrivateKey
        assertThat(key.modulus.bitLength()).isEqualTo(1024) // the report's embedded key size; the key itself is never printed
    }

    @Test
    fun `recorder codes use the app table, local codes the local list, unknown codes stay raw`() {
        fun meaning(code: Int, source: RecorderError.Source) = errorMeaning(RecorderError(code, source, null, null))
        assertThat(meaning(201, RECORDER)).isEqualTo(R.string.dashcam_err_app_no_sd)
        assertThat(meaning(3, RECORDER)).isEqualTo(R.string.dashcam_err_app_busy) // not the AE enum's "invalid token"
        assertThat(meaning(105, RECORDER)).isEqualTo(R.string.dashcam_err_app_failed_retry)
        assertThat(meaning(999, RECORDER)).isEqualTo(R.string.dashcam_err_unknown_code)
        assertThat(meaning(ErrorCodes.MISSING_RVAL, RECORDER)).isEqualTo(R.string.dashcam_err_missing_rval)
        assertThat(meaning(ErrorCodes.CONNECT_FAILED, LOCAL)).isEqualTo(R.string.dashcam_err_connect)
        assertThat(meaning(ErrorCodes.REQUEST_TIMEOUT, TIMEOUT)).isEqualTo(R.string.dashcam_err_timeout)
        assertThat(meaning(ErrorCodes.SESSION_TIMEOUT, TIMEOUT)).isEqualTo(R.string.dashcam_err_session_timeout)
        assertThat(meaning(ErrorCodes.SESSION_KEY_INVALID, LOCAL)).isEqualTo(R.string.dashcam_err_key_rejected)
        assertThat(meaning(4096, RECORDER)).isEqualTo(R.string.dashcam_err_unknown_code) // a recorder 4096 is not the local monitor
    }

    @Test
    fun `sd status maps 0 to 8 and keeps unknown values raw`() {
        assertThat(sdStatusText(NormalInfo.SdStatus(1, SdCardStatus.NORMAL, 2))).isEqualTo(UiText.Res(R.string.dashcam_sd_status_normal, listOf(2)))
        assertThat(sdStatusText(NormalInfo.SdStatus(1, SdCardStatus.UNSUPPORTED_FILESYSTEM, 8)))
            .isEqualTo(UiText.Res(R.string.dashcam_sd_status_filesystem, listOf(8)))
        assertThat(sdStatusText(NormalInfo.SdStatus(1, null, 42))).isEqualTo(UiText.Res(R.string.dashcam_sd_status_unknown, listOf(42)))
        assertThat(sdStatusText(null)).isEqualTo(UiText.Res(R.string.dashcam_sd_status_none))
        assertThat(recStatusText(NormalInfo.RecStatus(1, 7))).isEqualTo(UiText.Res(R.string.dashcam_rec_status_raw, listOf(7)))
    }

    @Test
    fun `settings are read from the global and the front channel object`() {
        val s = settings()
        assertThat(s.value(RecorderSetting.NORMAL_VIDEO_TIME)).isEqualTo(3)
        assertThat(s.value(RecorderSetting.VIDEO_RESOLUTION)).isEqualTo(0)
        assertThat(s.value(RecorderSetting.OSD)).isEqualTo(1)
        assertThat(s.osdContent()).containsExactly(0, 1, 7).inOrder()
        assertThat(label(RecorderSetting.G_SENSOR, 1)).isEqualTo(UiText.Res(R.string.dashcam_opt_high))
        assertThat(label(RecorderSetting.G_SENSOR, 0)).isEqualTo(UiText.Res(R.string.dashcam_value_unknown, listOf(0)))
    }

    @Test
    fun `a malformed osdContent blocks the overlay change instead of sending something else`() {
        val s = settings(settingsReply(osd = """{"enableOSD":1,"osdContent":"[I@92e7998"}"""))
        assertThat(s.osdContent()).isNull()
        assertThat(s.patch(RecorderSetting.OSD, 0)).isNull()
    }

    @Test
    fun `wifi resubmission keeps mode and frequency, an empty password keeps the old one`() {
        val read = WifiParam(mode = 1, ssid = "FORTHING-OLD", passwd = "Old12345", frequency = 1)
        assertThat(wifiToSend(read, "FORTHING-NEW", "New12345")).isEqualTo(WifiParam(1, "FORTHING-NEW", "New12345", 1))
        assertThat(wifiToSend(read, "FORTHING-NEW", "")).isEqualTo(WifiParam(1, "FORTHING-NEW", "Old12345", 1))
        assertThat(wifiToSend(WifiParam(null, "A", "Old12345", null), "B", "")).isEqualTo(WifiParam(null, "B", "Old12345", null))
    }

    @Test
    fun `wifi password rule needs 8 characters with a letter and a digit`() {
        assertThat(isValidWifiPassword("abc12345")).isTrue()
        assertThat(isValidWifiPassword("abc1234")).isFalse()
        assertThat(isValidWifiPassword("12345678")).isFalse()
        assertThat(isValidWifiPassword("abcdefgh")).isFalse()
    }

    @Test
    fun `fields the screen does not show are listed raw with secrets masked`() {
        val extras = extraValues(settings(settingsReply(wifi = """{"mode":0,"ssid":"X","passwd":"Secret123","frequency":0}"""))).toMap()
        assertThat(extras).containsEntry("withoutChan.recordSwitch", "1")
        assertThat(extras).containsEntry("withChan[0].frameRate", "0")
        assertThat(extras).containsEntry("withChan[0].futureField", "7")
        assertThat(extras.keys).containsNoneOf("withoutChan.normalVideoTime", "withoutChan.wifi", "withChan[0].osd")
        assertThat(extras.values.joinToString()).doesNotContain("Secret123")
    }

    @Test
    fun `the SSID hint accepts FORTHING in any case, like the original`() {
        assertThat(looksLikeRecorderSsid("FORTHING-ABC123")).isTrue()
        assertThat(looksLikeRecorderSsid("forthing")).isTrue()
        assertThat(looksLikeRecorderSsid("HomeWifi")).isFalse()
    }
}
