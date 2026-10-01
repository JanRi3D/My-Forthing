package to.axolotl.cam.enhance

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.abs

class TilingTest {

    private class ArrayPixels(override val width: Int, override val height: Int, val px: IntArray = IntArray(width * height)) : Pixels {
        override fun read(x: Int, y: Int, w: Int, h: Int, out: IntArray) {
            for (r in 0 until h) System.arraycopy(px, (y + r) * width + x, out, r * w, w)
        }

        override fun write(x: Int, y: Int, w: Int, h: Int, src: IntArray) {
            for (r in 0 until h) System.arraycopy(src, r * w, px, (y + r) * width + x, w)
        }
    }

    private fun image(w: Int, h: Int) = ArrayPixels(w, h, IntArray(w * h) { argb(it % w * 255 / w, it / w * 255 / h, (it * 37) and 0xff) })

    @Test
    fun `tile starts cover the length with the last tile flush`() {
        val starts = tileStarts(1920, 128, 16).toList()
        assertThat(starts.first()).isEqualTo(0)
        assertThat(starts.last()).isEqualTo(1920 - 128)
        assertThat(starts.zipWithNext { a, b -> b - a }.max()).isAtMost(112)
        assertThat(tileStarts(100, 128, 16).toList()).containsExactly(0)
        assertThat(tileStarts(128, 128, 16).toList()).containsExactly(0)
        assertThat(tileStarts(240, 128, 16).toList()).containsExactly(0, 112)
    }

    @Test
    fun `plan records the real overlap with left and upper neighbours`() {
        val tiles = planTiles(300, 130, 128, 16)
        assertThat(tiles.map { it.x }.distinct()).containsExactly(0, 112, 172).inOrder()
        assertThat(tiles.map { it.y }.distinct()).containsExactly(0, 2).inOrder()
        val last = tiles.last()
        assertThat(last.blendLeft).isEqualTo(112 + 128 - 172) // 68: the flush tile overlaps more than nominal
        assertThat(last.blendTop).isEqualTo(126)
        assertThat(tiles.first().blendLeft).isEqualTo(0)
        // Small image: one tile of the image size.
        assertThat(planTiles(50, 40, 128, 16)).containsExactly(Tile(0, 0, 50, 40, 0, 0))
    }

    @Test
    fun `ramp fades in across the overlap and is 1 beyond it`() {
        assertThat(ramp(0, 4)).isEqualTo(0.125f)
        assertThat(ramp(3, 4)).isEqualTo(0.875f)
        assertThat(ramp(4, 4)).isEqualTo(1f)
        assertThat(ramp(0, 0)).isEqualTo(1f)
    }

    @Test
    fun `blend keeps old pixels at the new tile edge and new pixels past the overlap`() {
        val old = IntArray(8) { argb(0, 0, 0) }
        val new = IntArray(8) { argb(200, 200, 200) }
        blendInto(old, new, 8, 1, left = 4, top = 0)
        val red = old.map { it shr 16 and 0xff }
        assertThat(red).containsExactly(25, 75, 125, 175, 200, 200, 200, 200).inOrder()
    }

    @Test
    fun `tiling with a position independent engine equals the untiled result`() = runTest {
        val nearest = TileUpscaler { src, w, h, s -> IntArray(w * s * h * s) { src[(it / (w * s)) / s * w + (it % (w * s)) / s] } }
        val src = image(301, 157)
        val dst = ArrayPixels(602, 314)
        upscaleTiled(src, dst, 2, 64, 8, nearest) {}
        assertThat(dst.px).isEqualTo(nearest.upscale(src.px, 301, 157, 2))
    }

    @Test
    fun `classical tiles blend without visible seams`() = runTest {
        val src = image(300, 200)
        val whole = Classical.upscale(src.px, 300, 200, 2)
        val tiled = ArrayPixels(600, 400)
        upscaleTiled(src, tiled, 2, 96, CLASSICAL_OVERLAP, Classical) {}
        val worst = whole.indices.maxOf { i ->
            maxOf(abs((whole[i] shr 16 and 0xff) - (tiled.px[i] shr 16 and 0xff)), abs((whole[i] shr 8 and 0xff) - (tiled.px[i] shr 8 and 0xff)))
        }
        assertThat(worst).isAtMost(3) // differences only from edge-clamped context, faded out by the ramp
    }

    @Test
    fun `progress reaches 1 after the last tile`() = runTest {
        val progress = mutableListOf<Float>()
        upscaleTiled(image(200, 100), ArrayPixels(400, 200), 2, 64, 8, Classical) { progress += it }
        assertThat(progress.size).isEqualTo(planTiles(200, 100, 64, 8).size)
        assertThat(progress.last()).isEqualTo(1f)
        assertThat(progress).isInOrder()
    }

    @Test
    fun `cancellation stops before the next tile`() {
        val job = Job()
        var calls = 0
        val engine = TileUpscaler { src, w, h, s ->
            if (++calls == 2) job.cancel()
            Classical.upscale(src, w, h, s)
        }
        assertThrows(CancellationException::class.java) {
            runBlocking(job) { upscaleTiled(image(400, 400), ArrayPixels(800, 800), 2, 64, 8, engine) {} }
        }
        assertThat(calls).isEqualTo(2)
    }
}
