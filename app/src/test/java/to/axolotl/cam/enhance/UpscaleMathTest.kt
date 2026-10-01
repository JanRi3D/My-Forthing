package to.axolotl.cam.enhance

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Test

class UpscaleMathTest {

    @Test
    fun `no eta or size estimate before 5 percent`() {
        assertThat(progressOf(0.049f, 10_000, 1_000)).isEqualTo(UpscaleProgress(0.049f, null, null))
    }

    @Test
    fun `eta and size extrapolate the measured rate`() {
        val p = progressOf(0.1f, 1_000, 1_000_000)
        assertThat(p.etaMs).isEqualTo(9_000)
        assertThat(p.outputBytesEstimate).isEqualTo(10_000_000)
        assertThat(progressOf(1.5f, 1_000, 10).fraction).isEqualTo(1f)
        assertThat(progressOf(1f, 4_000, 10).etaMs).isEqualTo(0)
    }

    @Test
    fun `output keeps aspect ratio with the short side at the target and even sides`() {
        assertThat(outputSize(1920, 1080, 1440)).isEqualTo(2560 to 1440)
        assertThat(outputSize(1920, 1080, 2160)).isEqualTo(3840 to 2160)
        assertThat(outputSize(1280, 720, 2160)).isEqualTo(3840 to 2160)
        assertThat(outputSize(640, 480, 1440)).isEqualTo(1920 to 1440)
        assertThat(outputSize(1080, 1920, 1440)).isEqualTo(1440 to 2560)
        val (w, h) = outputSize(1001, 563, 1440)
        assertThat(w % 2 + h % 2).isEqualTo(0)
    }

    @Test
    fun `size estimate follows the configured bitrates`() {
        val bitrate = bitrateFor(2560, 1440, 30, VideoCodec.H264)
        assertThat(bitrate).isEqualTo(13_271_040)
        assertThat(bitrateFor(2560, 1440, 30, VideoCodec.HEVC)).isLessThan(bitrate)
        // 60 s at 13.27 Mbit/s video + 128 kbit/s audio
        assertThat(estimateBytes(bitrate, true, 60_000_000)).isEqualTo((13_271_040L + 128_000) * 60 / 8)
        assertThat(estimateBytes(bitrate, false, 60_000_000)).isEqualTo(13_271_040L * 60 / 8)
    }

    @Test
    fun `cancelled job maps to the typed Cancelled error`() = runTest {
        val job = CompletableDeferred<UpscaleResult>()
        job.cancel()
        assertThat(job.awaitResult()).isEqualTo(UpscaleResult.Failed(UpscaleError.Cancelled))
    }

    @Test
    fun `classical kernel weights sum to one and the resample keeps flat colour`() {
        for (i in 0..10) assertThat(Classical.weights(i / 10f, Classical.SHARPEN).sum()).isWithin(1e-5f).of(1f)
        val flat = IntArray(16 * 9) { argb(90, 120, 200) }
        assertThat(Classical.upscale(flat, 16, 9, 4).distinct()).containsExactly(argb(90, 120, 200))
    }

    @Test
    fun `denoise smooths small noise but keeps a hard edge`() {
        val w = 8
        val edge = IntArray(w * 4) { if (it % w < 4) argb(20, 20, 20) else argb(230, 230, 230) }
        assertThat(Classical.denoise(edge, w, 4)).isEqualTo(edge)
        val noisy = IntArray(9) { argb(100, 100, 100) }.also { it[4] = argb(108, 108, 108) }
        assertThat(Classical.denoise(noisy, 3, 3)[4] shr 16 and 0xff).isEqualTo(103)
    }
}
