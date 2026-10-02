package me.ri3d.dashcam.recorder

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

class ParsersTest {
    @Test
    fun missingRval_isMinusOne() {
        val r = reply("reply-missing-rval.json")
        assertThat(r.msgId).isEqualTo(4098)
        assertThat(r.rval).isEqualTo(-1)
        assertThat(RecorderReply.parse("""{"rval":0}""")).isNull() // no msgId: dropped, as the SDK does
        assertThat(RecorderReply.parse("not json")).isNull()
        val padded = RecorderReply.parse("{\"msgId\":3,\"rval\":0}\u0004\u0004\u0004\u0004 ")!!
        assertThat(padded.rval).isEqualTo(0)
        assertThat(padded.rawJson).isEqualTo("{\"msgId\":3,\"rval\":0}")
    }

    @Test
    fun deviceInfo_allFieldsAndUnknownKept() {
        val d = parseDeviceInfo(reply("device-info.json"))
        assertThat(d.productModel).isEqualTo("EXAMPLE-MODEL")
        assertThat(d.productSN).isEqualTo("EXAMPLE-SN-0001")
        assertThat(d.fwVersion).isEqualTo("V0.0.0 build 260101")
        assertThat(d.fwBuildDate).isEqualTo("2026-01-01")
        assertThat(d.hwVersion).isEqualTo("0x0000")
        assertThat(d.mcuFwVersion).isEqualTo("MCU-0.0")
        assertThat(d.paramVersion).isEqualTo("P-0.0")
        assertThat(d.verifyCode).isEqualTo("EXAMPLE")
        assertThat(d.dateTime).isEqualTo("2026-10-01 12:00:00")
        assertThat(d.semifinishProductSN).isEqualTo("EXAMPLE-SEMI-0001")
        assertThat(d.raw["futureField"]).isEqualTo(JsonPrimitive("kept"))
    }

    @Test
    fun storageInfo() {
        val s = parseStorageInfo(reply("storage-info.json"))
        assertThat(s.totalSpace).isEqualTo(30528L)
        assertThat(s.available).isEqualTo(12034L)
        assertThat(s.residualLife).isEqualTo("90")
        assertThat(s.healthStatus).isEqualTo("good")
    }

    @Test
    fun allSettings_withoutChanObjectAndWithChanArray() {
        val s = parseSettings(reply("all-settings-wifi-mode0.json"))
        with(s.global) {
            assertThat(poweroffDelay).isEqualTo(10)
            assertThat(normalVideoTime).isEqualTo(3)
            assertThat(gSensorSensitivity).isEqualTo(2)
            assertThat(eventRecCycle).isEqualTo(1)
            assertThat(wifi).isEqualTo(WifiParam(mode = 0, ssid = "EXAMPLE", passwd = "Example123", frequency = 0))
            assertThat(timeLapseVideo).isEqualTo(TimeLapse(1, 25, 60))
        }
        val ch = s.channels.single()
        assertThat(ch.chanNo).isEqualTo(1)
        assertThat(ch.videoResolution).isEqualTo(RecorderValues.RESOLUTION_1080P)
        assertThat(ch.soundSwitch).isEqualTo(1)
        assertThat(ch.osd).isEqualTo(OsdInfo(1, listOf(0, 1)))
        assertThat(ch.raw["futureChannelField"]).isEqualTo(JsonPrimitive(7))

        // M1: the parsed password stays usable, but no toString() prints it
        assertThat(s.global.wifi?.passwd).isEqualTo("Example123")
        val reply = reply("all-settings-wifi-mode0.json")
        for (text in listOf(s.toString(), s.global.toString(), RecorderResult.Ok(s, reply).toString())) {
            assertThat(text).doesNotContain("Example123")
            assertThat(text).contains("\"passwd\":\"***\"")
        }
    }

    @Test
    fun regression_wifiModeNotInvertedInReadAll() {
        assertThat(parseSettings(reply("all-settings-wifi-mode0.json")).global.wifi?.mode).isEqualTo(0)
        assertThat(parseSettings(reply("all-settings-wifi-mode1.json")).global.wifi?.mode).isEqualTo(1)
    }

    @Test
    fun malformedOsdEchoReadsAsEmpty_rawKept() {
        val ch = parseSettings(reply("all-settings-wifi-mode1.json")).channels.single()
        assertThat(ch.osd).isEqualTo(OsdInfo(1, emptyList()))
        assertThat(ch.raw.toString()).contains("[I@92e7998")
    }

    @Test
    fun fileList_fileNameFileThmFileTime() {
        val f = parseFileList(reply("file-list.json"))
        assertThat(f.totalFileNum).isEqualTo(71)
        assertThat(f.totalFileSize).isEqualTo(123456L)
        assertThat(f.fileList.map { it.fileName }).containsExactly("/example/clip.mp4", "/example/clip2.mp4").inOrder()
        assertThat(f.fileList[0].fileThm).isEqualTo("/example/clip.jpg")
        assertThat(f.fileList[0].fileTime).isEqualTo("2026-10-01 12:00:00")
        // next cursor is the last entry's exact fileName
        assertThat(RecorderCommand.ListFiles(RecorderValues.FILES_NORMAL, lastFileName = f.fileList.last().fileName!!).param.toString())
            .contains("\"lastFileName\":\"/example/clip2.mp4\"")
    }

    @Test
    fun captureReplies_filePathThmPathFileTime() {
        val photo = parseCaptureResult(reply("photo-reply.json"))
        assertThat(photo).isEqualTo(CaptureResult(1, "/example/photo.jpg", "/example/photo-thumb.jpg", "2026-10-01 12:00:00", photo.raw))
        val rec = parseCaptureResult(reply("record-reply.json"))
        assertThat(rec.filePath).isEqualTo("/example/manual.mp4")
        assertThat(rec.thmPath).isEqualTo("/example/manual.jpg")
        assertThat(parseCaptureResult(reply("stop-record-reply.json")).chanNo).isEqualTo(1)
    }

    @Test
    fun regression_networkCapabilityFlagsDoNotOverwriteEachOther() {
        val a = parseNetworkCapabilities(reply("capabilities-network-pwd0-ssid1.json"))
        assertThat(a.wifiPwdSetting).isFalse()
        assertThat(a.wifiSsidSetting).isTrue()
        assertThat(a.wifiFrequencies).containsExactly(0, 1).inOrder()
        val b = parseNetworkCapabilities(reply("capabilities-network-pwd1-ssid0.json"))
        assertThat(b.wifiPwdSetting).isTrue()
        assertThat(b.wifiSsidSetting).isFalse()
        assertThat(b.wifiModes).containsExactly(0, 1).inOrder()
    }

    @Test
    fun regression_imageFrameRateParsedAsFrameRates() {
        val img = parseImageCapabilities(reply("capabilities-image.json")).single()
        assertThat(img.frameRate).containsExactly(0, 1).inOrder() // the SDK left this empty
        assertThat(img.aspectRatio).containsExactly(0) // and filled this from frameRate
        assertThat(img.videoCodec).containsExactly(0, 1).inOrder()
        assertThat(img.osdContent).containsExactly(0, 1, 2, 3, 4, 5, 6, 7).inOrder()
        assertThat(img.supportOsd).isEqualTo(1)
        assertThat(img.subRec).isTrue()
    }

    @Test
    fun capabilities_allBasicStorageIntelligent() {
        val all = parseAllCapabilities(reply("capabilities-all.json"))
        assertThat(listOf(all.basic, all.imageEncode, all.network, all.storage, all.intelligence))
            .containsExactly(true, true, false, true, false).inOrder()

        val basic = parseBasicCapabilities(reply("capabilities-basic.json"))
        assertThat(basic.totalSensor).isEqualTo(1)
        assertThat(basic.rtspServer.single()).isEqualTo(RtspServer(1, "rtsp://192.168.42.1/ch1/sub/av_stream", 0, basic.rtspServer.single().raw))
        assertThat(basic.supportCanComm).isTrue()
        assertThat(basic.supportReboot).isFalse()
        assertThat(basic.factoryRestore).isTrue()
        assertThat(basic.poweroffDelay).containsExactly(0, 10, 30, 60).inOrder()
        assertThat(basic.gSensorSensitivity).containsExactly(0, 1, 2, 3).inOrder()

        val storage = parseStorageCapabilities(reply("capabilities-storage.json"))
        assertThat(storage.normalVideoTime).containsExactly(1, 3, 5).inOrder()
        assertThat(storage.sdDriver.single().supportFormat).isTrue()
        assertThat(storage.timeLapseTotalRecordTime).isEqualTo(1..120)
        assertThat(storage.picCycle).isTrue()

        val intel = parseIntelligentCapabilities(reply("capabilities-intelligent.json"))
        assertThat(intel.faceDetect).isTrue() // presence, as the SDK reads it
        assertThat(intel.trafficLightDetect).isFalse()
        assertThat(intel.frontCarReminding).isTrue()
    }

    @Test
    fun normalNotifications_parseWithoutRval() {
        val sd = parseNormalNotification(reply("notify-sd-status.json"))
        assertThat(sd.raw.rval).isEqualTo(-1)
        assertThat(sd.type).isEqualTo("sdStatus")
        assertThat(sd.info).isEqualTo(NormalInfo.SdStatus(1, SdCardStatus.NORMAL, 2))

        val unknownStatus = parseNormalNotification(reply("notify-sd-status-unknown.json")).info as NormalInfo.SdStatus
        assertThat(unknownStatus.status).isNull()
        assertThat(unknownStatus.rawStatus).isEqualTo(42)

        assertThat(parseNormalNotification(reply("notify-rec-status.json")).info).isEqualTo(NormalInfo.RecStatus(1, 7))
        assertThat(parseNormalNotification(reply("notify-file-new.json")).info)
            .isEqualTo(NormalInfo.FileNew(1, 1, "/example/event.mp4", "/example/event.jpg", "2026-10-01 12:00:00", 0))
        assertThat(parseNormalNotification(reply("notify-heartbeat-start.json")).info).isEqualTo(NormalInfo.HeartBeatStart)
        val unknown = parseNormalNotification(reply("notify-unknown-type.json"))
        assertThat(unknown.type).isEqualTo("wifiRestart")
        assertThat(unknown.info).isInstanceOf(NormalInfo.Unknown::class.java)
    }

    @Test
    fun events_parsedAsArray_unknownTypeKeptRaw() {
        val e = parseEventNotification(reply("event-manual.json"))
        assertThat(e.list).hasSize(2)
        assertThat(e.list[0].type).isEqualTo(EventType.MANUAL_RECORD)
        assertThat(e.list[0].filePath).isEqualTo("/example/manual.mp4")
        assertThat(e.list[0].fileThm).isEqualTo("/example/manual.jpg")
        assertThat(e.list[0].time).isEqualTo("2026-10-01 12:00:00")
        assertThat(e.list[1].type).isNull()
        assertThat(e.list[1].rawType).isEqualTo(99)
        // the alternative object schema is not what the active SDK branch parses
        assertThat(parseEventNotification(reply("event-object-schema.json")).list).isEmpty()
    }

    @Test
    fun errorTables_threeSourcesSeparate_noAeEnum() {
        assertThat(ErrorCodes.appMeaning(201)).isEqualTo(ErrorCodes.AppMeaning.NO_SD_CARD)
        assertThat(ErrorCodes.appMeaning(3)).isEqualTo(ErrorCodes.AppMeaning.BUSY_RETRY) // AE enum: "invalid token"
        assertThat(ErrorCodes.appMeaning(301)).isEqualTo(ErrorCodes.AppMeaning.RESET_FAILED) // AE enum: "request format"
        assertThat(ErrorCodes.appMeaning(0)).isNull()
        assertThat(ErrorCodes.appMeaning(999)).isNull()
        assertThat(ErrorCodes.HAT_NAMES[101]).isEqualTo("INVALID_TOKEN")
        assertThat(ErrorCodes.HAT_NAMES[502]).isEqualTo("G_SENSOR_ERROR")
        assertThat(ErrorCodes.APP_MEANINGS.keys).doesNotContain(ErrorCodes.SESSION_TIMEOUT)
        assertThat(ErrorCodes.LOCAL_DESCRIPTIONS.keys).containsAtLeast(-101, -102, -103, 4096)
        assertThat(parseCaptureResult(reply("error-no-sd-card.json")).filePath).isNull()
    }

    @Test
    fun redactor_masksSecrets() {
        val json = """{"token":123,"msgId":1,"param":{"tokenNum":123,"aescode":"QUJD+/=","passwd":"Example123","key":"00ff"}}"""
        val r = Redactor.redact(json)
        assertThat(r).doesNotContain("123,")
        assertThat(r).doesNotContain("QUJD")
        assertThat(r).doesNotContain("Example123")
        assertThat(r).doesNotContain("00ff")
        assertThat(r).contains("\"msgId\":1")
        assertThat(RecorderReply.parse(json).toString()).doesNotContain("Example123")
    }

    /** Shapes the physical recorder sent on 2026-10-02 (AE-DC2013-LQ2): values from the owner's Diagnose export. */
    @Test
    fun hardwareShapes_2026_10_02() {
        val basic = parseBasicCapabilities(RecorderReply.parse(RecorderSimulator.HW_BASIC_CAPABILITIES)!!)
        assertThat(basic.rtspServer.single().chanNo).isEqualTo(1)
        assertThat(basic.rtspServer.single().url).isEqualTo("rtsp://192.168.42.1:554/ch1/sub")
        assertThat(basic.rtspServer.single().auth).isNull()
        assertThat(basic.downloadPath).isEqualTo("http://192.168.42.1:80")

        val network = parseNetworkCapabilities(RecorderReply.parse("""{"msgId":20483,"rval":0,"param":{"wifi":{"mode":[0]}}}""")!!)
        assertThat(network.wifiModes).containsExactly(0)

        val fileNew = parseNormalNotification(
            RecorderReply.parse(
                """{"msgId":16384,"param":{"type":"fileNew","info":{"fileType":0,"fileName":"/sd/DCIM/ch1_20261002_091128_0782.mp4",""" +
                    """"fileThm":"/sd/DCIM/ch1_20261002_091128_0782.thm","fileTime":"2026-10-02 09:11:28"}}}""",
            )!!,
        )
        assertThat(fileNew.info).isEqualTo(
            NormalInfo.FileNew(null, 0, "/sd/DCIM/ch1_20261002_091128_0782.mp4", "/sd/DCIM/ch1_20261002_091128_0782.thm", "2026-10-02 09:11:28", null),
        )
        val storage = parseStorageInfo(
            RecorderReply.parse("""{"msgId":4099,"rval":0,"param":{"totalSpace":119255,"available":693,"residualLife":"unknow","healthStatus":"unknow"}}""")!!,
        )
        assertThat(storage.totalSpace).isEqualTo(119255)
        assertThat(storage.residualLife).isEqualTo("unknow") // kept raw
    }
}
