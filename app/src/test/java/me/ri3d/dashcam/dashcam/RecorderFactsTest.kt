package me.ri3d.dashcam.dashcam

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import me.ri3d.dashcam.media.eventually
import me.ri3d.dashcam.media.memoryDb
import me.ri3d.dashcam.recorder.CapabilityGroup
import me.ri3d.dashcam.recorder.RecorderCommand
import me.ri3d.dashcam.recorder.RecorderSimulator
import me.ri3d.dashcam.recorder.parseSettings

/** [SIM] Cached recorder facts: stored per serial number with their read time, shown until a live read. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RecorderFactsTest {
    private val db = memoryDb(RuntimeEnvironment.getApplication())
    private val sim = RecorderSimulator().apply {
        replies[4098] = deviceReply("SN-1")
        replies[4099] = storageReply(693)
        replies[4097] = settingsReply(normalVideoTime = 5)
    }

    // The in-memory database is left open: a fact write the manager started may still be on its way when a test ends.
    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun deviceReply(sn: String) =
        """{"msgId":4098,"rval":0,"param":{"productModel":"AE-DC2013-LQ2","productSN":"$sn","fwVersion":"SX5G-3776510A_A"}}"""

    private fun storageReply(available: Int) =
        """{"msgId":4099,"rval":0,"param":{"totalSpace":119255,"available":$available,"residualLife":"unknow","healthStatus":"unknow"}}"""

    @Test
    fun `read replies are kept per recorder with their read time, the Wi-Fi password never`() = runTest {
        val manager = managerFor(sim, facts = db.recorderFactDao()).apply { setSimulator(true) }
        val before = System.currentTimeMillis()
        manager.connect() // 4098 + 4099 follow by themselves
        manager.request(RecorderCommand.GetAllSettings, ::parseSettings)
        manager.capabilities(CapabilityGroup.NETWORK)

        // Rows arrive one by one (a reply before the 4098 is written right after it): wait for all four.
        val facts = manager.cachedFacts.first {
            it?.deviceInfo != null && it.settings != null && it.storage != null && CapabilityGroup.NETWORK in it.capabilities
        }!!
        assertThat(facts.productSN).isEqualTo("SN-1")
        assertThat(facts.deviceInfo!!.value.productModel).isEqualTo("AE-DC2013-LQ2")
        assertThat(facts.storage!!.value.available).isEqualTo(693)
        assertThat(facts.settings!!.value.global.normalVideoTime).isEqualTo(5)
        assertThat(facts.settings!!.readAt).isAtLeast(before)
        assertThat(facts.settings!!.value.global.wifi?.passwd).isEqualTo("***")
        assertThat(db.recorderFactDao().observeLatestRecorder().first().joinToString { it.json }).doesNotContain("Old12345")
    }

    @Test
    fun `a newer read replaces the older one and another recorder becomes the latest`() = runTest {
        val manager = managerFor(sim, facts = db.recorderFactDao()).apply { setSimulator(true) }
        manager.connect()
        val first = manager.cachedFacts.first { it?.storage != null }!!.storage!!
        manager.disconnect()

        sim.replies[4099] = storageReply(285)
        manager.connect()
        val second = manager.cachedFacts.first { it?.storage?.value?.available == 285L }!!.storage!!
        assertThat(second.readAt).isAtLeast(first.readAt)
        manager.disconnect()

        sim.replies[4098] = deviceReply("SN-2")
        manager.connect()
        val other = manager.cachedFacts.first { it?.productSN == "SN-2" && it.storage != null }!!
        assertThat(other.settings).isNull() // never read on this recorder
    }

    @Test
    fun `settings show the last readback until this session's one and are editable only then`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        managerFor(sim, facts = db.recorderFactDao()).apply { setSimulator(true) }.run {
            connect()
            request(RecorderCommand.GetAllSettings, ::parseSettings)
            cachedFacts.first { it?.settings != null }
            disconnect()
        }

        val manager = managerFor(sim, facts = db.recorderFactDao()).apply { setSimulator(true) } // next app start, offline
        val viewModel = RecorderSettingsViewModel(manager)
        backgroundScope.launch { viewModel.cached.collect {} }
        eventually { viewModel.cached.value?.settings != null }
        assertThat(viewModel.cached.value!!.settings!!.value.global.normalVideoTime).isEqualTo(5)
        assertThat(viewModel.ui.value.settings).isNull() // not editable: no readback in this session
        viewModel.change(RecorderSetting.NORMAL_VIDEO_TIME, 3)
        runCurrent()
        assertThat(sim.received.count { it.msgId == 8192 }).isEqualTo(0)

        sim.replies[4097] = settingsReply(normalVideoTime = 1) // changed on the recorder meanwhile
        manager.connect()
        eventually { viewModel.ui.value.settings != null }
        assertThat(viewModel.ui.value.settings!!.global.normalVideoTime).isEqualTo(1) // the live readback wins
    }

    @Test
    fun `the SD card screen has the last 4099 without a session`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        managerFor(sim, facts = db.recorderFactDao()).apply { setSimulator(true) }.run {
            connect()
            cachedFacts.first { it?.storage != null }
            disconnect()
        }
        val viewModel = SdCardViewModel(managerFor(sim, facts = db.recorderFactDao()))
        backgroundScope.launch { viewModel.cached.collect {} }
        eventually { viewModel.cached.value?.storage != null }
        assertThat(viewModel.storage.value).isNull()
        assertThat(viewModel.cached.value!!.storage!!.value.available).isEqualTo(693)
    }
}
