package me.ri3d.dashcam.enhance

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import me.ri3d.dashcam.core.model.ExportQuality
import java.io.File

class EnhanceFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(name: String, modified: Long) = File(tmp.root, name).apply { writeText("x"); setLastModified(modified) }

    @Test
    fun `sweep removes only an earlier process's leftovers`() {
        val start = 1_000_000_000_000L
        val old = start - 60_000
        file("a.mp4.tmp", old)
        file("b.mp4", old) // output without sidecar
        file("c.mp4", old)
        file("c.mp4.enhance.json", old) // complete pair: kept
        file("d.jpg.enhance.json", old) // sidecar without output
        file("e.jpg.enhance.json.tmp", old)
        file("f.mp4.tmp", start + 1_000) // a running job of this process
        file("g.jpg", start + 1_000)

        assertThat(sweepLeftovers(tmp.root, start))
            .containsExactly("a.mp4.tmp", "b.mp4", "d.jpg.enhance.json", "e.jpg.enhance.json.tmp")
        assertThat(tmp.root.list()!!.toList()).containsExactly("c.mp4", "c.mp4.enhance.json", "f.mp4.tmp", "g.jpg")
    }

    @Test
    fun `output limit follows device memory`() {
        val gib = 1L shl 30
        assertThat(DefaultFrameEnhancer.maxOutputPixels(8 * gib, lowRam = false)).isEqualTo(7680L * 4320)
        assertThat(DefaultFrameEnhancer.maxOutputPixels(2 * gib, lowRam = false)).isEqualTo(7680L * 4320)
        assertThat(DefaultFrameEnhancer.maxOutputPixels(gib + gib / 2, lowRam = false)).isEqualTo((gib + gib / 2) / 64)
        assertThat(DefaultFrameEnhancer.maxOutputPixels(8 * gib, lowRam = true)).isEqualTo(3840L * 2160)
    }

    @Test
    fun `every export quality maps to an upscale target`() {
        assertThat(ExportQuality.entries.map { it.resolution.height }).containsExactly(1080, 1440, 2160).inOrder()
    }
}
