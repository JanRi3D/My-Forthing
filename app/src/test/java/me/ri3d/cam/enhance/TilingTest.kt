package me.ri3d.cam.enhance

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
        override fun read(x: Int, y: Int, w: Int, h: Int, out: IntArray, offset: Int, stride: Int) {
            for (r in 0 until h) System.arraycopy(px, (y + r) * width + x, out, offset + r * stride, w)
        }

        override fun write(x: Int, y: Int, w: Int, h: Int, src: IntArray, offset: Int, stride: Int) {
            for (r in 0 until h) System.arraycopy(src, offset + r * stride, px, (y + r) * width + x, w)
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
        // With a margin of 2 in an overlap of 8: ignore 2, ramp over 4, then take the new tile.
        assertThat((0 until 8).map { ramp(it, 8, 2) }).containsExactly(0f, 0f, 0.125f, 0.375f, 0.625f, 0.875f, 1f, 1f).inOrder()
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
        upscaleTiled(src, dst, 2, TileSpec(64, 8, 0), nearest) {}
        assertThat(dst.px).isEqualTo(nearest.upscale(src.px, 301, 157, 2))
    }

    @Test
    fun `clamped reads replicate edge pixels`() {
        val img = ArrayPixels(3, 2, intArrayOf(1, 2, 3, 4, 5, 6))
        val out = IntArray(5 * 4)
        img.readClamped(-1, -1, 5, 4, out)
        assertThat(out.toList()).containsExactly(
            1, 1, 2, 3, 3,
            1, 1, 2, 3, 3,
            4, 4, 5, 6, 6,
            4, 4, 5, 6, 6,
        ).inOrder()
    }

    @Test
    fun `margin keeps engine edge artefacts out of the image`() = runTest {
        val nearest = TileUpscaler { src, w, h, s -> IntArray(w * s * h * s) { src[(it / (w * s)) / s * w + (it % (w * s)) / s] } }
        // Simulates a CNN's zero-padding: the outermost output ring of every tile is black.
        val artefacts = TileUpscaler { src, w, h, s ->
            nearest.upscale(src, w, h, s).also { out ->
                for (i in out.indices) if (i % (w * s) == 0 || i % (w * s) == w * s - 1 || i < w * s || i >= out.size - w * s) out[i] = argb(0, 0, 0)
            }
        }
        val src = ArrayPixels(150, 90, IntArray(150 * 90) { argb(200, 200, 200) })
        suspend fun darkest(margin: Int, edgesOnly: Boolean): Int {
            val dst = ArrayPixels(300, 180)
            upscaleTiled(src, dst, 2, TileSpec(64, 16, margin), artefacts) {}
            val edges = (0 until 300).flatMap { listOf(dst.px[it], dst.px[179 * 300 + it]) } +
                (0 until 180).flatMap { listOf(dst.px[it * 300], dst.px[it * 300 + 299]) }
            return (if (edgesOnly) edges else dst.px.toList()).minOf { it and 0xff }
        }
        assertThat(darkest(margin = 0, edgesOnly = true)).isEqualTo(0) // a CNN border without margin
        // With a margin the ring is replicated away at the border and dropped at interior seams.
        assertThat(darkest(margin = 1, edgesOnly = false)).isEqualTo(200)
    }

    @Test
    fun `classical tiles with margin equal the untiled result`() = runTest {
        val src = image(300, 200)
        val whole = Classical.upscale(src.px, 300, 200, 2)
        val tiled = ArrayPixels(600, 400)
        upscaleTiled(src, tiled, 2, TileSpec(96, CLASSICAL_TILING.overlap, CLASSICAL_TILING.margin), Classical) {}
        val worst = whole.indices.maxOf { i ->
            maxOf(abs((whole[i] shr 16 and 0xff) - (tiled.px[i] shr 16 and 0xff)), abs((whole[i] shr 8 and 0xff) - (tiled.px[i] shr 8 and 0xff)))
        }
        assertThat(worst).isAtMost(1) // blending identical values: rounding only
    }

    @Test
    fun `progress reaches 1 after the last tile`() = runTest {
        val progress = mutableListOf<Float>()
        upscaleTiled(image(200, 100), ArrayPixels(400, 200), 2, TileSpec(64, 8, 0), Classical) { progress += it }
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
            runBlocking(job) { upscaleTiled(image(400, 400), ArrayPixels(800, 800), 2, TileSpec(64, 8, 0), engine) {} }
        }
        assertThat(calls).isEqualTo(2)
    }
}
