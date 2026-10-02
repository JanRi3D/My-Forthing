package me.ri3d.dashcam.enhance.ui

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import me.ri3d.dashcam.R
import me.ri3d.dashcam.core.data.PreferencesRepository
import me.ri3d.dashcam.core.model.ExportQuality
import me.ri3d.dashcam.enhance.LiveUpscaleDecision
import me.ri3d.dashcam.enhance.LiveUpscaleDecision.Reason
import me.ri3d.dashcam.enhance.LiveUpscaleProbe
import me.ri3d.dashcam.live.LiveFrameSource
import me.ri3d.dashcam.media.eventually
import java.io.File

/** Probe caching (once per process, stored decision first), the Android 13 gate and the toggle preference. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LiveSharpeningTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val storeScope = CoroutineScope(Dispatchers.IO + Job())
    private val store by lazy { PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "enhance.preferences_pb") } }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        storeScope.cancel()
    }

    @Test
    @Config(sdk = [32])
    fun `the probe runs once per process and the next start shows its stored decision first`() = runTest {
        val enhancer = FakeFrameEnhancer()
        val first = LiveSharpening(LiveUpscaleProbe(enhancer), store)
        val probe = first.ensureProbed()!!
        assertThat(first.ensureProbed()).isNull()
        probe.join()
        assertThat(enhancer.capabilityCalls).isEqualTo(1)
        val decision = first.decision.first { it != null }!!
        assertThat(decision.reason).isEqualTo(Reason.API_TOO_OLD)
        assertThat(decision.offered()).isFalse()

        val nextStart = LiveSharpening(LiveUpscaleProbe(enhancer), store)
        assertThat(nextStart.decision.first { it != null }!!.reason).isEqualTo(Reason.API_TOO_OLD)
        assertThat(enhancer.capabilityCalls).isEqualTo(1) // known before (and without) probing again
    }

    @Test
    @Config(sdk = [33])
    fun `a yes from the probe is offered from Android 13`() {
        assertThat(LiveUpscaleDecision(true, Reason.OK, 2f, 5f, null, null).offered()).isTrue()
        assertThat(LiveUpscaleDecision(false, Reason.GPU_TOO_SLOW, 9f, 20f, null, null).offered()).isFalse()
        assertThat((null as LiveUpscaleDecision?).offered()).isFalse()
    }

    @Test
    @Config(sdk = [32])
    fun `a yes is not offered below Android 13`() {
        assertThat(LiveUpscaleDecision(true, Reason.OK, 2f, 5f, null, null).offered()).isFalse()
    }

    @Test
    @Config(sdk = [32])
    fun `the settings say why sharpening is not offered`() {
        assertThat(liveTextRes(null)).isEqualTo(R.string.enhance_settings_live_checking)
        assertThat(liveTextRes(LiveUpscaleDecision(false, Reason.API_TOO_OLD, null, null, null, null))).isEqualTo(R.string.enhance_settings_live_api)
        assertThat(liveTextRes(LiveUpscaleDecision(false, Reason.GPU_TOO_SLOW, 8f, 16.6f, null, null))).isEqualTo(R.string.enhance_settings_live_slow)
        assertThat(liveTextRes(LiveUpscaleDecision(false, Reason.GPU_TOO_SLOW, null, null, null, null))).isEqualTo(R.string.enhance_settings_live_slow_plain)
        assertThat(liveTextRes(LiveUpscaleDecision(false, Reason.PROBE_FAILED, null, null, null, null))).isEqualTo(R.string.enhance_settings_live_failed)
    }

    @Test
    @Config(sdk = [32])
    fun `the toggle writes the liveUpscale preference`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val prefs = PreferencesRepository(store)
        val vm = EnhanceSettingsViewModel(LiveSharpening(LiveUpscaleProbe(FakeFrameEnhancer()), store), prefs, LiveFrameSource())
        vm.setLiveSharpen(true)
        eventually { vm.prefs.value.liveUpscale }
        vm.setExportQuality(ExportQuality.Q2160)
        eventually { vm.prefs.value.exportQuality == ExportQuality.Q2160 }
        assertThat(prefs.preferences.first().liveUpscale).isTrue()
    }
}
