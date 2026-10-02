package me.ri3d.dashcam.enhance

/**
 * Classical baseline, pure Kotlin: mild edge-preserving denoise, then a separable "sharpened bicubic" resample
 * whose 1-D kernel is `(1 + a)·CatmullRom − a·BSpline` – an unsharp mask (Catmull-Rom minus its B-spline blur)
 * folded into the interpolation. The clip shader (`GlScaler`) and the live effect use the same idea on the GPU.
 */
internal object Classical : TileUpscaler {
    const val SHARPEN = 0.5f
    const val DENOISE_THRESHOLD = 12

    override fun upscale(src: IntArray, w: Int, h: Int, scale: Int): IntArray =
        resample(denoise(src, w, h), w, h, scale, SHARPEN)

    /** Kernel weights for taps −1, 0, +1, +2 at fractional position [t]; they always sum to 1. */
    fun weights(t: Float, a: Float, out: FloatArray = FloatArray(4)): FloatArray {
        val t2 = t * t
        val t3 = t2 * t
        val u = 1 - t
        out[0] = (1 + a) * (-0.5f * t + t2 - 0.5f * t3) - a * (u * u * u / 6)
        out[1] = (1 + a) * (1 - 2.5f * t2 + 1.5f * t3) - a * ((3 * t3 - 6 * t2 + 4) / 6)
        out[2] = (1 + a) * (0.5f * t + 2 * t2 - 1.5f * t3) - a * ((-3 * t3 + 3 * t2 + 3 * t + 1) / 6)
        out[3] = (1 + a) * (-0.5f * t2 + 0.5f * t3) - a * (t3 / 6)
        return out
    }

    /** 3×3 sigma filter: the centre (weight 4) is averaged with neighbours whose luma is within [threshold]. */
    fun denoise(src: IntArray, w: Int, h: Int, threshold: Int = DENOISE_THRESHOLD): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val c = src[y * w + x]
            val cy = luma(c)
            var r = 4 * (c shr 16 and 0xff)
            var g = 4 * (c shr 8 and 0xff)
            var b = 4 * (c and 0xff)
            var n = 4
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val p = src[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
                if (kotlin.math.abs(luma(p) - cy) > threshold) continue
                r += p shr 16 and 0xff
                g += p shr 8 and 0xff
                b += p and 0xff
                n++
            }
            out[y * w + x] = argb((r + n / 2) / n, (g + n / 2) / n, (b + n / 2) / n)
        }
        return out
    }

    /** Separable resample by an integer [scale] with the sharpened-cubic kernel (edges clamped). */
    fun resample(src: IntArray, w: Int, h: Int, scale: Int, a: Float): IntArray {
        val ow = w * scale
        val oh = h * scale
        // Integer scale: the sub-pixel phase repeats every `scale` output pixels.
        val offset = IntArray(scale)
        val kernel = Array(scale) { p ->
            val pos = (p + 0.5f) / scale - 0.5f
            val base = kotlin.math.floor(pos).toInt()
            offset[p] = base
            weights(pos - base, a)
        }
        val tmp = FloatArray(ow * h * 3)
        for (y in 0 until h) {
            val row = y * w
            for (ox in 0 until ow) {
                val k = kernel[ox % scale]
                val i0 = ox / scale + offset[ox % scale]
                var r = 0f
                var g = 0f
                var b = 0f
                for (j in 0..3) {
                    val p = src[row + (i0 - 1 + j).coerceIn(0, w - 1)]
                    r += k[j] * (p shr 16 and 0xff)
                    g += k[j] * (p shr 8 and 0xff)
                    b += k[j] * (p and 0xff)
                }
                val t = (y * ow + ox) * 3
                tmp[t] = r
                tmp[t + 1] = g
                tmp[t + 2] = b
            }
        }
        val out = IntArray(ow * oh)
        for (oy in 0 until oh) {
            val k = kernel[oy % scale]
            val i0 = oy / scale + offset[oy % scale]
            val rows = IntArray(4) { (i0 - 1 + it).coerceIn(0, h - 1) * ow * 3 }
            for (ox in 0 until ow) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (j in 0..3) {
                    val t = rows[j] + ox * 3
                    r += k[j] * tmp[t]
                    g += k[j] * tmp[t + 1]
                    b += k[j] * tmp[t + 2]
                }
                out[oy * ow + ox] = argb(clamp(r), clamp(g), clamp(b))
            }
        }
        return out
    }

    private fun luma(p: Int) = (77 * (p shr 16 and 0xff) + 150 * (p shr 8 and 0xff) + 29 * (p and 0xff)) shr 8
    private fun clamp(v: Float) = (v + 0.5f).toInt().coerceIn(0, 255)
}

internal fun argb(r: Int, g: Int, b: Int) = (0xff shl 24) or (r shl 16) or (g shl 8) or b
