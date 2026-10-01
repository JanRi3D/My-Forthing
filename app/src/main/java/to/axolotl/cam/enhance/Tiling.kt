package to.axolotl.cam.enhance

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** ARGB pixel access by rectangle (same contract as Bitmap.getPixels/setPixels); an IntArray in JVM tests. */
internal interface Pixels {
    val width: Int
    val height: Int
    fun read(x: Int, y: Int, w: Int, h: Int, out: IntArray, offset: Int = 0, stride: Int = w)
    fun write(x: Int, y: Int, w: Int, h: Int, src: IntArray, offset: Int = 0, stride: Int = w)
}

/** Reads a [w]×[h] block overlapping the image; pixels past its edges repeat the nearest edge pixel. */
internal fun Pixels.readClamped(x: Int, y: Int, w: Int, h: Int, out: IntArray) {
    val x0 = x.coerceIn(0, width - 1)
    val x1 = (x + w).coerceIn(x0 + 1, width)
    for (r in 0 until h) {
        val row = r * w
        read(x0, (y + r).coerceIn(0, height - 1), x1 - x0, 1, out, row + (x0 - x).coerceAtLeast(0), w)
        for (i in 0 until x0 - x) out[row + i] = out[row + x0 - x]
        for (i in x1 - x until w) out[row + i] = out[row + x1 - x - 1]
    }
}

/** Upscales one tile of [w]×[h] ARGB pixels by [scale]; returns (w·scale)×(h·scale) pixels. */
internal fun interface TileUpscaler {
    fun upscale(src: IntArray, w: Int, h: Int, scale: Int): IntArray
}

/**
 * Tiling per engine, in source pixels: [tile] edge, [overlap] between neighbours and the engine's unreliable edge
 * width [margin] (zero padding inside a CNN, clamped kernel taps).
 */
internal class TileSpec(val tile: Int, val overlap: Int, val margin: Int) {
    init {
        require(tile > overlap && overlap > 2 * margin && margin >= 0)
    }
}

/** A source tile at ([x], [y]) and how many source pixels it shares with its left and upper neighbour. */
internal data class Tile(val x: Int, val y: Int, val w: Int, val h: Int, val blendLeft: Int, val blendTop: Int)

/** Tile origins along one axis: stride `tile - overlap`, the last tile flush with the end. */
internal fun tileStarts(length: Int, tile: Int, overlap: Int): IntArray {
    require(tile > overlap && overlap >= 0)
    if (length <= tile) return intArrayOf(0)
    val stride = tile - overlap
    val count = (length - tile + stride - 1) / stride + 1
    return IntArray(count) { minOf(it * stride, length - tile) }
}

internal fun planTiles(width: Int, height: Int, tile: Int, overlap: Int): List<Tile> {
    val xs = tileStarts(width, tile, overlap)
    val ys = tileStarts(height, tile, overlap)
    val w = minOf(tile, width)
    val h = minOf(tile, height)
    return ys.indices.flatMap { j ->
        xs.indices.map { i ->
            Tile(
                x = xs[i], y = ys[j], w = w, h = h,
                blendLeft = if (i == 0) 0 else xs[i - 1] + w - xs[i],
                blendTop = if (j == 0) 0 else ys[j - 1] + h - ys[j],
            )
        }
    }
}

/**
 * Weight of the new tile at [i] pixels into an [overlap] with its neighbour. The outer [margin] pixels on both
 * sides of the overlap are the two tiles' unreliable edges: the new tile's are ignored (0), the old tile's are
 * replaced (1); in between the weight ramps linearly.
 */
internal fun ramp(i: Int, overlap: Int, margin: Int = 0): Float = when {
    i >= overlap - margin -> 1f
    i < margin -> 0f
    else -> (i - margin + 0.5f) / (overlap - 2 * margin)
}

/** Blends [tile] over [dst] (both [w]×[h] ARGB) across the first [left] columns and [top] rows. */
internal fun blendInto(dst: IntArray, tile: IntArray, w: Int, h: Int, left: Int, top: Int, margin: Int = 0) {
    for (y in 0 until h) {
        val wy = ramp(y, top, margin)
        val row = y * w
        for (x in 0 until w) {
            val a = wy * ramp(x, left, margin)
            val i = row + x
            if (a >= 1f) {
                dst[i] = tile[i]
                continue
            }
            val o = dst[i]
            val n = tile[i]
            fun mix(shift: Int) = ((o shr shift and 0xff) * (1 - a) + (n shr shift and 0xff) * a + 0.5f).toInt()
            dst[i] = argb(mix(16), mix(8), mix(0))
        }
    }
}

/**
 * Upscales [src] into [dst] (exactly scale× its size) tile by tile. At the image border the image is virtually
 * extended by `spec.margin` replicated pixels, at interior edges the margin is dropped and only the reliable part
 * of the overlap is blended. Peak extra memory is a few tile buffers; cancellation is checked before every tile.
 */
internal suspend fun upscaleTiled(
    src: Pixels,
    dst: Pixels,
    scale: Int,
    spec: TileSpec,
    engine: TileUpscaler,
    onProgress: (Float) -> Unit,
) {
    require(dst.width == src.width * scale && dst.height == src.height * scale)
    val margin = spec.margin
    val width = src.width + 2 * margin
    val height = src.height + 2 * margin
    val tiles = planTiles(width, height, spec.tile, spec.overlap)
    val input = IntArray(tiles[0].w * tiles[0].h)
    val existing = IntArray(input.size * scale * scale)
    tiles.forEachIndexed { index, t ->
        currentCoroutineContext().ensureActive()
        src.readClamped(t.x - margin, t.y - margin, t.w, t.h, input)
        val out = engine.upscale(input, t.w, t.h, scale)
        val ow = t.w * scale
        val oh = t.h * scale
        // Output rectangle: the virtual border clipped off, the right/bottom margin left to the next tile.
        val ox = (t.x - margin) * scale
        val oy = (t.y - margin) * scale
        val x0 = maxOf(ox, 0)
        val y0 = maxOf(oy, 0)
        val x1 = minOf(ox + ow - if (t.x + t.w < width) margin * scale else 0, dst.width)
        val y1 = minOf(oy + oh - if (t.y + t.h < height) margin * scale else 0, dst.height)
        val offset = (y0 - oy) * ow + (x0 - ox)
        if (t.blendLeft > 0 || t.blendTop > 0) {
            dst.read(x0, y0, x1 - x0, y1 - y0, existing, offset, ow)
            blendInto(existing, out, ow, oh, t.blendLeft * scale, t.blendTop * scale, margin * scale)
            dst.write(x0, y0, x1 - x0, y1 - y0, existing, offset, ow)
        } else {
            dst.write(x0, y0, x1 - x0, y1 - y0, out, offset, ow)
        }
        onProgress((index + 1f) / tiles.size)
    }
}
