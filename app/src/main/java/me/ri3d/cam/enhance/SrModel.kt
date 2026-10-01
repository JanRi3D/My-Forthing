package me.ri3d.cam.enhance

import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A fixed-size super-resolution model (NHWC RGB in, NHWC RGB out) on LiteRT's CPU/XNNPACK path. Tiles smaller
 * than the model input are edge-padded; scales below the model's native factor are box-downsampled per tile.
 * Not thread-safe: callers serialise access.
 */
internal class SrModel(model: ByteArray, threads: Int, private val valueRange: Float = 1f) : TileUpscaler, Closeable {
    private val interpreter = Interpreter(
        ByteBuffer.allocateDirect(model.size).order(ByteOrder.nativeOrder()).put(model).apply { rewind() },
        Interpreter.Options().setNumThreads(threads),
    )
    private val inTensor = interpreter.getInputTensor(0)
    private val outTensor = interpreter.getOutputTensor(0)
    val inputSize = inTensor.shape()[1]
    val nativeScale = outTensor.shape()[1] / inputSize
    private val outSize = inputSize * nativeScale
    private val input = ByteBuffer.allocateDirect(inTensor.numBytes()).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(outTensor.numBytes()).order(ByteOrder.nativeOrder())

    init {
        require(inTensor.shape()[2] == inputSize && outTensor.shape()[2] == outSize && inTensor.shape()[3] == 3) {
            "unsupported model shape"
        }
    }

    override fun upscale(src: IntArray, w: Int, h: Int, scale: Int): IntArray {
        require(w <= inputSize && h <= inputSize && nativeScale % scale == 0)
        input.rewind()
        val q = inTensor.quantizationParams()
        for (y in 0 until inputSize) for (x in 0 until inputSize) {
            val p = src[minOf(y, h - 1) * w + minOf(x, w - 1)]
            putChannel(p shr 16 and 0xff, q.scale, q.zeroPoint)
            putChannel(p shr 8 and 0xff, q.scale, q.zeroPoint)
            putChannel(p and 0xff, q.scale, q.zeroPoint)
        }
        output.rewind()
        interpreter.run(input, output)
        return readOutput(w * nativeScale, h * nativeScale, nativeScale / scale)
    }

    private fun putChannel(v: Int, scale: Float, zeroPoint: Int) {
        val value = v / 255f * valueRange
        when (inTensor.dataType()) {
            DataType.FLOAT32 -> input.putFloat(value)
            DataType.INT8 -> input.put(Math.round(value / scale + zeroPoint).coerceIn(-128, 127).toByte())
            else -> input.put(Math.round(value / scale + zeroPoint).coerceIn(0, 255).toByte())
        }
    }

    /** Crops the model output to [cw]×[ch] and box-averages [f]×[f] blocks. */
    private fun readOutput(cw: Int, ch: Int, f: Int): IntArray {
        val q = outTensor.quantizationParams()
        val type = outTensor.dataType()
        fun channel(index: Int): Float {
            val v = when (type) {
                DataType.FLOAT32 -> output.getFloat(index * 4)
                DataType.INT8 -> (output.get(index) - q.zeroPoint) * q.scale
                else -> ((output.get(index).toInt() and 0xff) - q.zeroPoint) * q.scale
            }
            return v / valueRange * 255f
        }
        val ow = cw / f
        val oh = ch / f
        val out = IntArray(ow * oh)
        for (oy in 0 until oh) for (ox in 0 until ow) {
            var r = 0f
            var g = 0f
            var b = 0f
            for (dy in 0 until f) for (dx in 0 until f) {
                val i = ((oy * f + dy) * outSize + ox * f + dx) * 3
                r += channel(i)
                g += channel(i + 1)
                b += channel(i + 2)
            }
            val n = (f * f).toFloat()
            out[oy * ow + ox] = argb(px(r / n), px(g / n), px(b / n))
        }
        return out
    }

    private fun px(v: Float) = (v + 0.5f).toInt().coerceIn(0, 255)

    override fun close() = interpreter.close()
}
