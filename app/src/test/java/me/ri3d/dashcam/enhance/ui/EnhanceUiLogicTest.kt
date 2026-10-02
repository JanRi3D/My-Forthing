package me.ri3d.dashcam.enhance.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import me.ri3d.dashcam.R
import me.ri3d.dashcam.core.ui.UiText
import me.ri3d.dashcam.enhance.Resolution
import me.ri3d.dashcam.enhance.UpscaleError

/** Pure display rules and mappings (no Android runtime). */
class EnhanceUiLogicTest {
    @Test
    fun `size is always shown, time only when measured, otherwise the reason it cannot run`() {
        val measured = UpscaleOption(Resolution.P1440, 2560, 1440, encoder = true, bytes = 98_000_000, etaMs = 240_000)
        assertThat(optionTextRes(measured)).isEqualTo(R.string.upscale_option_size_time)
        assertThat(optionTextRes(measured.copy(etaMs = null))).isEqualTo(R.string.upscale_option_size)
        assertThat(optionTextRes(measured.copy(encoder = false))).isEqualTo(R.string.upscale_no_encoder)
        assertThat(optionTextRes(measured.copy(tooLong = true))).isEqualTo(R.string.upscale_too_long)
        assertThat(measured.copy(tooLong = true).usable).isFalse()
        assertThat(measured.usable).isTrue()
    }

    @Test
    fun `durations are rounded up`() {
        assertThat(duration(3 * 3_600_000L + 29 * 60_000L + 1)).isEqualTo(UiText.Res(R.string.enhance_duration_hours, listOf(3, 30)))
        assertThat(duration(59_500)).isEqualTo(UiText.Res(R.string.enhance_duration_minutes, listOf(1)))
        assertThat(duration(1)).isEqualTo(UiText.Res(R.string.enhance_duration_seconds, listOf(1)))
    }

    @Test
    fun `every pipeline error maps to its own German reason`() {
        val kinds = listOf(
            UpscaleError.Decoder(null), UpscaleError.Encoder(null), UpscaleError.Storage(null), UpscaleError.Memory(null),
            UpscaleError.TargetNotLarger(null), UpscaleError.Cancelled,
        ).map(UpscaleFailureKind::of)
        assertThat(kinds).containsExactly(
            UpscaleFailureKind.DECODER, UpscaleFailureKind.ENCODER, UpscaleFailureKind.STORAGE, UpscaleFailureKind.MEMORY,
            UpscaleFailureKind.TARGET_NOT_LARGER, UpscaleFailureKind.CANCELLED,
        ).inOrder()
        assertThat(UpscaleFailureKind.entries.map { it.text }.toSet()).hasSize(UpscaleFailureKind.entries.size)
    }

    @Test
    fun `live sharpening scales by displayed over stream width, only while active`() {
        assertThat(sharpenScale(active = true, displayedWidth = 2160f, streamWidth = 1280)).isEqualTo(1.6875f)
        assertThat(sharpenScale(active = false, displayedWidth = 2160f, streamWidth = 1280)).isNull()
        assertThat(sharpenScale(active = true, displayedWidth = 0f, streamWidth = 1280)).isNull() // before layout
        assertThat(sharpenScale(active = true, displayedWidth = 2160f, streamWidth = null)).isNull() // no stream
        assertThat(sharpenScale(active = true, displayedWidth = 2160f, streamWidth = 0)).isNull()
    }
}
