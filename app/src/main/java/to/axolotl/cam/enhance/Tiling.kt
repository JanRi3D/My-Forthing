package to.axolotl.cam.enhance

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** ARGB pixel access by rectangle; a Bitmap on Android, an IntArray in JVM tests. */
internal interface Pixels {
    val width: Int
    val height: Int
    fun read(x: Int, y: Int, w: Int, h: Int, out: IntArray)
    fun write(x: Int, y: Int, w: Int, h: Int, src: IntArray)
}

/** Upscales one tile of [w]×[h] ARGB pixels by [scale]; returns (w·scale)×(h·scale) pixels. */
internal fun interface TileUpscaler {
    fun upscale(src: IntArray, w: Int, h: Int, scale: Int): IntArray
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

/** Linear feather weight of the new tile at [i] pixels into an overlap of [overlap] pixels. */
internal fun ramp(i: Int, overlap: Int): Float = if (i >= overlap) 1f else (i + 0.5f) / overlap

/**
 * Blends [tile] over [dst] (both [w]×[h] ARGB): the new tile fades in across the first [left] columns and
 * [top] rows, so its edge pixels (worst context) get almost no weight and the old tile's interior wins there.
 */
internal fun blendInto(dst: IntArray, tile: IntArray, w: Int, h: Int, left: Int, top: Int) {
    for (y in 0 until h) {
        val wy = ramp(y, top)
        val row = y * w
        for (x in 0 until w) {
            val a = wy * ramp(x, left)
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
 * Upscales [src] into [dst] (exactly scale× its size) tile by tile, blending overlaps. Peak extra memory is a few
 * tile buffers; cancellation is checked before every tile.
 */
internal suspend fun upscaleTiled(
    src: Pixels,
    dst: Pixels,
    scale: Int,
    tile: Int,
    overlap: Int,
    engine: TileUpscaler,
    onProgress: (Float) -> Unit,
) {
    require(dst.width == src.width * scale && dst.height == src.height * scale)
    val tiles = planTiles(src.width, src.height, tile, overlap)
    val input = IntArray(tiles[0].w * tiles[0].h)
    val existing = IntArray(input.size * scale * scale)
    tiles.forEachIndexed { index, t ->
        currentCoroutineContext().ensureActive()
        src.read(t.x, t.y, t.w, t.h, input)
        val out = engine.upscale(input, t.w, t.h, scale)
        val ow = t.w * scale
        val oh = t.h * scale
        if (t.blendLeft > 0 || t.blendTop > 0) {
            dst.read(t.x * scale, t.y * scale, ow, oh, existing)
            blendInto(existing, out, ow, oh, t.blendLeft * scale, t.blendTop * scale)
            dst.write(t.x * scale, t.y * scale, ow, oh, existing)
        } else {
            dst.write(t.x * scale, t.y * scale, ow, oh, out)
        }
        onProgress((index + 1f) / tiles.size)
    }
}
