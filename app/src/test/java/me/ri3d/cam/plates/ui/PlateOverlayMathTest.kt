package me.ri3d.cam.plates.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import com.google.common.truth.Truth.assertThat
import me.ri3d.cam.plates.PlateSighting
import me.ri3d.cam.plates.SightingSource
import org.junit.Test

/** Frame pixels → the displayed video rectangle (live `videoRect`, clip player fit). */
class PlateOverlayMathTest {
    @Test
    fun `16 to 9 video in a 16 to 9 box fills it`() {
        assertThat(fitRect(Size(1600f, 900f), IntSize(1920, 1080))).isEqualTo(Rect(0f, 0f, 1600f, 900f))
    }

    @Test
    fun `4 to 3 video in a 16 to 9 box is pillarboxed`() {
        assertThat(fitRect(Size(1600f, 900f), IntSize(640, 480))).isEqualTo(Rect(200f, 0f, 1400f, 900f))
    }

    @Test
    fun `16 to 9 video in a portrait screen is letterboxed`() {
        assertThat(fitRect(Size(1080f, 2400f), IntSize(1920, 1080))).isEqualTo(Rect(0f, 896.25f, 1080f, 1503.75f))
    }

    @Test
    fun `full screen landscape with a wide display is pillarboxed`() {
        val rect = fitRect(Size(2400f, 1080f), IntSize(1280, 720))
        assertThat(rect).isEqualTo(Rect(240f, 0f, 2160f, 1080f))
    }

    @Test
    fun `a box in frame pixels lands on the same spot of the shown video`() {
        // Live: the frame is the stream downscaled to 1280 px, shown pillarboxed in full screen.
        val videoRect = Rect(240f, 0f, 2160f, 1080f) // 1920 × 1080 shown
        val frame = IntSize(1280, 720)
        val box = Rect(640f, 360f, 800f, 400f) // centre of the frame, 160 × 40
        assertThat(mapBox(box, frame, videoRect)).isEqualTo(Rect(1200f, 540f, 1440f, 600f))
        // Corners map to the corners of the shown video, not of the screen.
        assertThat(mapBox(Rect(0f, 0f, 1280f, 720f), frame, videoRect)).isEqualTo(videoRect)
    }

    @Test
    fun `clip boxes in video pixels map through the letterboxed player`() {
        val video = IntSize(1920, 1080)
        val shown = fitRect(Size(1080f, 1080f), video) // square overlay box: bars top and bottom
        assertThat(shown).isEqualTo(Rect(0f, 236.25f, 1080f, 843.75f))
        assertThat(mapBox(Rect(960f, 540f, 1920f, 1080f), video, shown)).isEqualTo(Rect(540f, 540f, 1080f, 843.75f))
    }

    @Test
    fun `empty content gives an empty rect`() {
        assertThat(fitRect(Size(100f, 100f), IntSize(0, 0))).isEqualTo(Rect.Zero)
    }

    @Test
    fun `the clip overlay shows sightings within 500 ms of the position`() {
        val sightings = listOf(0L, 500L, 1_000L, 1_499L, 1_501L, 2_600L).map { s(it) } + s(null)
        assertThat(sightings.near(1_000).map { it.positionMs }).containsExactly(500L, 1_000L, 1_499L)
        assertThat(sightings.near(2_100).map { it.positionMs }).containsExactly(2_600L)
        assertThat(sightings.near(5_000)).isEmpty()
    }

    private fun s(positionMs: Long?) = PlateSighting(
        plateId = 1, display = "B-MK 4821", mediaId = "m", positionMs = positionMs, source = SightingSource.CLIP, seenAt = 0,
        confidence = null, cropPath = null, boxLeft = 0f, boxTop = 0f, boxRight = 1f, boxBottom = 1f,
    )
}
