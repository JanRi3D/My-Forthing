package me.ri3d.dashcam.dashcam

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import me.ri3d.dashcam.R
import me.ri3d.dashcam.recorder.RecorderResult
import me.ri3d.dashcam.recorder.RecorderSimulator

/** [SIM] 4099 load and 12288 format against the real manager in simulator mode. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SdCardViewModelTest {
    private val sim = RecorderSimulator().apply {
        replies[4099] = """{"msgId":4099,"rval":0,"param":{"totalSpace":30528,"available":12034,"residualLife":"90","healthStatus":"good"}}"""
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `storage is read when ready and formatting re-reads it`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val manager = managerFor(sim).apply { setSimulator(true) }
        manager.connect()
        val viewModel = SdCardViewModel(manager)
        runCurrent()
        assertThat((viewModel.storage.value as RecorderResult.Ok).value.available).isEqualTo(12034)

        viewModel.formatCard()
        runCurrent()

        assertThat(viewModel.format.value).isInstanceOf(RecorderResult.Ok::class.java)
        assertThat(sim.received.map { it.msgId }.takeLast(2)).containsExactly(12288, 4099).inOrder()
        assertThat(sim.received.first { it.msgId == 12288 }.json).isEqualTo("""{"msgId":12288,"token":123,"param":{"driver":1}}""")
    }

    @Test
    fun `a failed format keeps the app meaning`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        sim.rvalOverrides[12288] = 209
        val manager = managerFor(sim).apply { setSimulator(true) }
        manager.connect()
        val viewModel = SdCardViewModel(manager)
        runCurrent()

        viewModel.formatCard()
        runCurrent()

        val failed = viewModel.format.value as RecorderResult.Failed
        assertThat(errorMeaning(failed.error)).isEqualTo(R.string.dashcam_err_app_format)
        assertThat(failed.error.code).isEqualTo(209)
    }
}
