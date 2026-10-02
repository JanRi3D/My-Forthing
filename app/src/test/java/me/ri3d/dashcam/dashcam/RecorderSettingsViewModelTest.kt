package me.ri3d.dashcam.dashcam

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import me.ri3d.dashcam.R
import me.ri3d.dashcam.core.ui.UiText
import me.ri3d.dashcam.recorder.ErrorCodes
import me.ri3d.dashcam.recorder.RecorderSimulator

/** [SIM] Settings change → rval → readback, against the real manager in simulator mode. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RecorderSettingsViewModelTest {
    private val sim = RecorderSimulator().apply { replies[4097] = settingsReply() }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun TestScope.loadedViewModel(): RecorderSettingsViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val manager = managerFor(sim).apply { setSimulator(true) }
        manager.connect()
        val viewModel = RecorderSettingsViewModel(manager)
        runCurrent()
        assertThat(viewModel.ui.value.settings?.global?.normalVideoTime).isEqualTo(3)
        return viewModel
    }

    private fun lastSettingsRequest() = sim.received.last { it.msgId == 8192 }.json

    @Test
    fun `a change is confirmed only by the readback`() = runTest {
        val viewModel = loadedViewModel()
        sim.replies[4097] = settingsReply(normalVideoTime = 5) // the recorder applied it

        viewModel.change(RecorderSetting.NORMAL_VIDEO_TIME, 5)
        runCurrent()

        assertThat(lastSettingsRequest()).isEqualTo("""{"msgId":8192,"token":123,"param":{"chanNo":1,"normalVideoTime":5}}""")
        assertThat(sim.received.last().msgId).isEqualTo(4097) // readback after the change
        assertThat(viewModel.ui.value.status["normalVideoTime"]).isEqualTo(ChangeStatus.Confirmed)
        assertThat(viewModel.ui.value.settings?.global?.normalVideoTime).isEqualTo(5)
    }

    @Test
    fun `a readback that differs shows both values`() = runTest {
        val viewModel = loadedViewModel()

        viewModel.change(RecorderSetting.NORMAL_VIDEO_TIME, 5)
        runCurrent()

        assertThat(viewModel.ui.value.status["normalVideoTime"])
            .isEqualTo(ChangeStatus.Mismatch(UiText.Res(R.string.dashcam_opt_5_min), UiText.Res(R.string.dashcam_opt_3_min)))
    }

    @Test
    fun `a rejected change shows the app meaning and is not read back`() = runTest {
        val viewModel = loadedViewModel()
        sim.rvalOverrides[8192] = 208

        viewModel.change(RecorderSetting.SOUND, 0)
        runCurrent()

        val status = viewModel.ui.value.status["soundSwitch"] as ChangeStatus.Failed
        assertThat(status.error.code).isEqualTo(208)
        assertThat(errorMeaning(status.error)).isEqualTo(R.string.dashcam_err_app_sd_space)
        assertThat(sim.received.last().msgId).isEqualTo(8192)
    }

    @Test
    fun `Wi-Fi is resubmitted whole with ssid, mode and frequency as read and without chanNo`() = runTest {
        val viewModel = loadedViewModel()
        sim.replies[4097] = settingsReply(wifi = """{"mode":1,"ssid":"FORTHING-OLD","passwd":"New12345","frequency":1}""")

        viewModel.changeWifi("New12345")
        runCurrent()

        assertThat(lastSettingsRequest()).isEqualTo(
            """{"msgId":8192,"token":123,"param":{"wifi":{"mode":1,"ssid":"FORTHING-OLD","passwd":"New12345","frequency":1}}}""",
        )
        assertThat(viewModel.ui.value.status["wifi"]).isEqualTo(ChangeStatus.Confirmed)
        assertThat(viewModel.ui.value.rejoinWifi).isTrue()
    }

    @Test
    fun `a Wi-Fi readback with another password is a mismatch without showing either password`() = runTest {
        val viewModel = loadedViewModel() // the readback keeps Old12345

        viewModel.changeWifi("New12345")
        runCurrent()

        assertThat(viewModel.ui.value.status["wifi"]).isEqualTo(
            ChangeStatus.Mismatch(UiText.Res(R.string.dashcam_wifi_password_sent), UiText.Res(R.string.dashcam_wifi_password_differs)),
        )
        assertThat(viewModel.ui.value.rejoinWifi).isTrue()
    }

    @Test
    fun `an invalid Wi-Fi password is never sent`() = runTest {
        val viewModel = loadedViewModel()

        viewModel.changeWifi("kurz1")
        viewModel.changeWifi("Passwört123")
        runCurrent()

        assertThat(sim.received.map { it.msgId }).doesNotContain(8192)
    }

    @Test
    fun `an unanswered Wi-Fi change has an unknown outcome and still asks to rejoin`() = runTest {
        val viewModel = loadedViewModel()
        sim.silentMsgIds += 8192

        viewModel.changeWifi("New12345")
        advanceTimeBy(11_000)
        runCurrent()

        val status = viewModel.ui.value.status["wifi"] as ChangeStatus.Unknown
        assertThat(status.error.code).isEqualTo(ErrorCodes.REQUEST_TIMEOUT)
        assertThat(viewModel.ui.value.rejoinWifi).isTrue()
    }

    @Test
    fun `a failed readback after rval 0 is unconfirmed`() = runTest {
        val viewModel = loadedViewModel()
        sim.silentMsgIds += 4097

        viewModel.change(RecorderSetting.NORMAL_VIDEO_TIME, 5)
        advanceTimeBy(11_000)
        runCurrent()

        val status = viewModel.ui.value.status["normalVideoTime"] as ChangeStatus.Unconfirmed
        assertThat(status.error.code).isEqualTo(ErrorCodes.REQUEST_TIMEOUT)
    }

    @Test
    fun `the overlay switch resubmits osdContent exactly as read`() = runTest {
        val viewModel = loadedViewModel()

        viewModel.change(RecorderSetting.OSD, 0)
        runCurrent()

        assertThat(lastSettingsRequest())
            .isEqualTo("""{"msgId":8192,"token":123,"param":{"osd":{"enableOSD":0,"osdContent":[0,1,7]},"chanNo":1}}""")
    }

    @Test
    fun `factory reset is only accepted (rval 0, no readback) and asks to rejoin the Wi-Fi`() = runTest {
        val viewModel = loadedViewModel()

        viewModel.factoryReset()
        runCurrent()

        assertThat(sim.received.map { it.msgId }).contains(12289)
        assertThat(viewModel.ui.value.reset).isEqualTo(ChangeStatus.Accepted)
        assertThat(viewModel.ui.value.rejoinWifi).isTrue()
    }

    @Test
    fun `a refused factory reset is a failure without rejoin prompt`() = runTest {
        val viewModel = loadedViewModel()
        sim.rvalOverrides[12289] = 301

        viewModel.factoryReset()
        runCurrent()

        assertThat((viewModel.ui.value.reset as ChangeStatus.Failed).error.code).isEqualTo(301)
        assertThat(viewModel.ui.value.rejoinWifi).isFalse()
    }
}
