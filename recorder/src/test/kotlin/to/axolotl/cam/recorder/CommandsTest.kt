package to.axolotl.cam.recorder

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import to.axolotl.cam.recorder.RecorderCommand.DeleteFiles
import to.axolotl.cam.recorder.RecorderCommand.FactoryReset
import to.axolotl.cam.recorder.RecorderCommand.FormatStorage
import to.axolotl.cam.recorder.RecorderCommand.GetAllSettings
import to.axolotl.cam.recorder.RecorderCommand.GetCapabilities
import to.axolotl.cam.recorder.RecorderCommand.GetDeviceInfo
import to.axolotl.cam.recorder.RecorderCommand.GetSetting
import to.axolotl.cam.recorder.RecorderCommand.GetStorageInfo
import to.axolotl.cam.recorder.RecorderCommand.KeepAlive
import to.axolotl.cam.recorder.RecorderCommand.ListFiles
import to.axolotl.cam.recorder.RecorderCommand.SetSettings
import to.axolotl.cam.recorder.RecorderCommand.StartRecord
import to.axolotl.cam.recorder.RecorderCommand.StopRecord
import to.axolotl.cam.recorder.RecorderCommand.StopSession
import to.axolotl.cam.recorder.RecorderCommand.TakePhoto

/** Logical bodies as the recovered serializers produce them (token 123 is illustrative). */
class CommandsTest {
    private fun body(cmd: RecorderCommand) = cmd.body(123)

    @Test
    fun parameterlessCommands_sendNoParam() {
        assertThat(body(StopSession)).isEqualTo("""{"token":123,"msgId":2}""")
        assertThat(body(KeepAlive)).isEqualTo("""{"token":123,"msgId":3}""")
        assertThat(body(GetAllSettings)).isEqualTo("""{"token":123,"msgId":4097}""")
        assertThat(body(GetDeviceInfo)).isEqualTo("""{"token":123,"msgId":4098}""")
        assertThat(body(FactoryReset)).isEqualTo("""{"token":123,"msgId":12289}""")
        for (g in CapabilityGroup.entries) {
            assertThat(body(GetCapabilities(g))).isEqualTo("""{"token":123,"msgId":${g.msgId}}""")
        }
        assertThat(CapabilityGroup.entries.map { it.msgId }).containsExactly(20480, 20481, 20482, 20483, 20484, 20485).inOrder()
    }

    @Test
    fun paramCommands_matchReportShapes() {
        assertThat(body(GetSetting("soundSwitch"))).isEqualTo("""{"msgId":4096,"token":123,"param":{"chanNo":1,"type":"soundSwitch"}}""")
        assertThat(body(GetStorageInfo())).isEqualTo("""{"msgId":4099,"token":123,"param":{"driver":1}}""")
        assertThat(body(ListFiles(type = RecorderValues.FILES_NORMAL)))
            .isEqualTo("""{"msgId":4100,"token":123,"param":{"driver":1,"type":0,"lastFileName":"","pageNum":50}}""")
        assertThat(body(DeleteFiles(listOf("/example/a.mp4", "/example/b.mp4"))))
            .isEqualTo("""{"msgId":4101,"token":123,"param":{"fileList":["/example/a.mp4","/example/b.mp4"]}}""")
        assertThat(body(FormatStorage())).isEqualTo("""{"msgId":12288,"token":123,"param":{"driver":1}}""")
        assertThat(body(TakePhoto())).isEqualTo("""{"msgId":12292,"token":123,"param":{"chanNo":1,"interval":3,"number":1}}""")
        assertThat(body(TakePhoto(number = 5))).isEqualTo("""{"msgId":12292,"token":123,"param":{"chanNo":1,"interval":3,"number":5}}""")
        assertThat(body(StartRecord(RecorderValues.RECORD_MANUAL))).isEqualTo("""{"msgId":12293,"token":123,"param":{"chanNo":1,"recType":1}}""")
        assertThat(body(StartRecord(RecorderValues.RECORD_TIMELAPSE))).isEqualTo("""{"msgId":12293,"token":123,"param":{"chanNo":1,"recType":2}}""")
        assertThat(body(StopRecord())).isEqualTo("""{"msgId":12294,"token":123,"param":{"chanNo":1,"recType":2}}""")
        assertThrows(IllegalArgumentException::class.java) { DeleteFiles(emptyList()) }
    }

    @Test
    fun settingsPatch_onlyNonNullFields() {
        assertThat(body(SetSettings(SettingsPatch(chanNo = 1, normalVideoTime = 3))))
            .isEqualTo("""{"msgId":8192,"token":123,"param":{"chanNo":1,"normalVideoTime":3}}""")
        assertThat(body(SetSettings(SettingsPatch(chanNo = 1, soundSwitch = RecorderValues.ON))))
            .isEqualTo("""{"msgId":8192,"token":123,"param":{"chanNo":1,"soundSwitch":1}}""")
    }

    @Test
    fun wifiPasswordPatch_omitsChanNo() {
        val patch = SettingsPatch(wifi = WifiParam(mode = 0, ssid = "EXAMPLE", passwd = "Example123", frequency = 0))
        assertThat(body(SetSettings(patch)))
            .isEqualTo("""{"msgId":8192,"token":123,"param":{"wifi":{"mode":0,"ssid":"EXAMPLE","passwd":"Example123","frequency":0}}}""")
    }

    @Test
    fun regression_osdIsSerialisedAsJsonArray() {
        val patch = SettingsPatch(osd = OsdInfo(enableOsd = 1, osdContent = (0..7).toList()), chanNo = 1)
        assertThat(body(SetSettings(patch)))
            .isEqualTo("""{"msgId":8192,"token":123,"param":{"osd":{"enableOSD":1,"osdContent":[0,1,2,3,4,5,6,7]},"chanNo":1}}""")
        assertThat(body(SetSettings(patch))).doesNotContain("[I@")
    }

    @Test
    fun regression_gSensorUiMapping_1Hoch_2Mittel_3Niedrig_sentRaw() {
        assertThat(RecorderValues.GSENSOR_HIGH).isEqualTo(1) // "Hoch"
        assertThat(RecorderValues.GSENSOR_MEDIUM).isEqualTo(2) // "Mittel"
        assertThat(RecorderValues.GSENSOR_LOW).isEqualTo(3) // "Niedrig"
        assertThat(body(SetSettings(SettingsPatch(chanNo = 1, gSensorSensitivity = RecorderValues.GSENSOR_HIGH))))
            .isEqualTo("""{"msgId":8192,"token":123,"param":{"chanNo":1,"gSensorSensitivity":1}}""")
    }

    @Test
    fun regression_wifiModeReadThenResubmittedUnchanged() {
        for (mode in 0..1) {
            val settings = parseSettings(reply("all-settings-wifi-mode$mode.json"))
            val wifi = checkNotNull(settings.global.wifi)
            assertThat(wifi.mode).isEqualTo(mode) // the SDK's read-all parser would have inverted this
            val resubmitted = body(SetSettings(SettingsPatch(wifi = wifi.copy(passwd = "NewPass123"))))
            assertThat(resubmitted).contains(""""wifi":{"mode":$mode,""")
        }
    }

    @Test
    fun eventAck_exactBody() {
        assertThat(eventAckBody(123)).isEqualTo("""{"rval":0,"msgId":16385,"token":123}""")
    }

    @Test
    fun wifiParamToString_hidesPassword() {
        val s = SettingsPatch(wifi = WifiParam(0, "EXAMPLE", "Example123", 0)).toString()
        assertThat(s).doesNotContain("Example123")
        assertThat(s).contains("EXAMPLE")
    }
}
