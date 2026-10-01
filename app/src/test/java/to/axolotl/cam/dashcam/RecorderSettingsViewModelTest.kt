package to.axolotl.cam.dashcam

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import to.axolotl.cam.R
import to.axolotl.cam.core.ui.UiText
import to.axolotl.cam.recorder.RecorderSimulator

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
    fun `Wi-Fi is resubmitted whole with mode and frequency as read and without chanNo`() = runTest {
        val viewModel = loadedViewModel()
        sim.replies[4097] = settingsReply(wifi = """{"mode":1,"ssid":"FORTHING-NEW","passwd":"New12345","frequency":1}""")

        viewModel.changeWifi("FORTHING-NEW", "New12345")
        runCurrent()

        assertThat(lastSettingsRequest()).isEqualTo(
            """{"msgId":8192,"token":123,"param":{"wifi":{"mode":1,"ssid":"FORTHING-NEW","passwd":"New12345","frequency":1}}}""",
        )
        assertThat(viewModel.ui.value.status["wifi"]).isEqualTo(ChangeStatus.Confirmed)
        assertThat(viewModel.ui.value.rejoinWifi).isTrue()
    }

    @Test
    fun `an empty Wi-Fi password keeps the one read back`() = runTest {
        val viewModel = loadedViewModel()

        viewModel.changeWifi("FORTHING-RENAMED", "")
        runCurrent()

        assertThat(lastSettingsRequest()).isEqualTo(
            """{"msgId":8192,"token":123,"param":{"wifi":{"mode":1,"ssid":"FORTHING-RENAMED","passwd":"Old12345","frequency":1}}}""",
        )
        assertThat(viewModel.ui.value.status["wifi"])
            .isEqualTo(ChangeStatus.Mismatch(UiText.Dynamic("FORTHING-RENAMED"), UiText.Dynamic("FORTHING-OLD")))
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
    fun `factory reset reports rval 0 and asks to rejoin the Wi-Fi`() = runTest {
        val viewModel = loadedViewModel()

        viewModel.factoryReset()
        runCurrent()

        assertThat(sim.received.map { it.msgId }).contains(12289)
        assertThat(viewModel.ui.value.reset).isEqualTo(ChangeStatus.Confirmed)
        assertThat(viewModel.ui.value.rejoinWifi).isTrue()
    }
}
