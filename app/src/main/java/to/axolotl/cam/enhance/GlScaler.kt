package to.axolotl.cam.enhance

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import androidx.core.graphics.createBitmap
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GLES 2 renderer onto a MediaCodec encoder input [Surface] (the CTS decode-edit-encode pattern). Draws the
 * decoder's OES frame upscaled with the same sharpened-cubic kernel as [Classical] (no denoise on video), or a
 * CPU-enhanced bitmap for the ML path. Must be created, used and closed on one thread.
 */
internal class GlScaler(surface: Surface) : Closeable {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: android.opengl.EGLContext
    private val eglSurface: android.opengl.EGLSurface
    private val quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).apply { rewind() }
    private val cubic: Int
    private val copy: Int
    private val plain: Int
    val oesTexture: Int
    private var bitmapTexture = 0
    private var fbo = 0
    private var fboTexture = 0

    init {
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGLExt.EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "eglChooseConfig" }
        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent ${EGL14.eglGetError()}" }
        cubic = program(OES_HEADER + CUBIC)
        copy = program(OES_HEADER + COPY)
        plain = program(PLAIN)
        oesTexture = texture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
    }

    val maxTextureSize: Int = IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, it, 0) }[0]

    /** Classical path: OES frame ([srcW]×[srcH]) → sharpened cubic → [outW]×[outH] window. */
    fun drawCubic(st: FloatArray, srcW: Int, srcH: Int, outW: Int, outH: Int, sharpen: Float = Classical.SHARPEN) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, outW, outH)
        GLES20.glUseProgram(cubic)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(cubic, "uSt"), 1, false, st, 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(cubic, "uSrc"), srcW.toFloat(), srcH.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(cubic, "uA"), sharpen)
        draw(cubic, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
    }

    /** ML path, step 1: the OES frame as an upright ARGB bitmap at source size. */
    fun readFrame(st: FloatArray, w: Int, h: Int): Bitmap {
        if (fbo == 0) {
            fboTexture = texture(GLES20.GL_TEXTURE_2D)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            fbo = IntArray(1).also { GLES20.glGenFramebuffers(1, it, 0) }[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTexture, 0)
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { "fbo" }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(copy)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(copy, "uSt"), 1, false, st, 0)
        draw(copy, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        val pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return createBitmap(w, h).apply { copyPixelsFromBuffer(pixels.rewind()) }
    }

    /** ML path, step 2: an enhanced bitmap, bilinear-fitted to the [outW]×[outH] window. */
    fun drawBitmap(bitmap: Bitmap, outW: Int, outH: Int) {
        check(bitmap.width <= maxTextureSize && bitmap.height <= maxTextureSize) { "texture too large" }
        if (bitmapTexture == 0) bitmapTexture = texture(GLES20.GL_TEXTURE_2D)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bitmapTexture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, outW, outH)
        GLES20.glUseProgram(plain)
        draw(plain, GLES20.GL_TEXTURE_2D, bitmapTexture)
    }

    /** Hands the frame to the encoder with the source timestamp. */
    fun swap(ptsNanos: Long) {
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, ptsNanos)
        check(EGL14.eglSwapBuffers(display, eglSurface)) { "eglSwapBuffers ${EGL14.eglGetError()}" }
    }

    private fun draw(program: Int, target: Int, texture: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
        val pos = GLES20.glGetAttribLocation(program, "aPos")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 8, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "GL error $error" }
    }

    private fun texture(target: Int): Int {
        val id = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(target, id)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return id
    }

    private fun program(fragment: String): Int {
        fun shader(type: Int, source: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, source)
            GLES20.glCompileShader(it)
            val ok = IntArray(1).also { s -> GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, s, 0) }[0]
            check(ok != 0) { "shader: ${GLES20.glGetShaderInfoLog(it)}" }
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, VERTEX))
            GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
            GLES20.glLinkProgram(it)
            val ok = IntArray(1).also { s -> GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, s, 0) }[0]
            check(ok != 0) { "link: ${GLES20.glGetProgramInfoLog(it)}" }
        }
    }

    override fun close() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }

    private companion object {
        const val VERTEX = """
attribute vec2 aPos;
varying vec2 vUv;
void main() { vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }
"""
        const val OES_HEADER = """#extension GL_OES_EGL_image_external : require
precision highp float;
uniform samplerExternalOES uTex;
uniform mat4 uSt;
varying vec2 vUv;
"""
        // Same kernel as Classical.weights: (1 + a)·Catmull-Rom − a·B-spline, 4×4 taps at source texel centres.
        const val CUBIC = """
uniform vec2 uSrc;
uniform float uA;
vec4 kernel(float t) {
    float t2 = t * t; float t3 = t2 * t; float u = 1.0 - t;
    vec4 cr = vec4(-0.5 * t + t2 - 0.5 * t3, 1.0 - 2.5 * t2 + 1.5 * t3, 0.5 * t + 2.0 * t2 - 1.5 * t3, -0.5 * t2 + 0.5 * t3);
    vec4 bs = vec4(u * u * u, 3.0 * t3 - 6.0 * t2 + 4.0, -3.0 * t3 + 3.0 * t2 + 3.0 * t + 1.0, t3) / 6.0;
    return (1.0 + uA) * cr - uA * bs;
}
vec3 tap(vec2 px) {
    vec2 c = clamp(px, vec2(0.5), uSrc - 0.5) / uSrc;
    return texture2D(uTex, (uSt * vec4(c, 0.0, 1.0)).xy).rgb;
}
vec3 row(vec2 b, float dy, vec4 k) {
    return k.x * tap(b + vec2(-0.5, dy)) + k.y * tap(b + vec2(0.5, dy)) + k.z * tap(b + vec2(1.5, dy)) + k.w * tap(b + vec2(2.5, dy));
}
void main() {
    vec2 p = vUv * uSrc - 0.5;
    vec2 b = floor(p);
    vec2 f = p - b;
    vec4 kx = kernel(f.x);
    vec4 ky = kernel(f.y);
    vec3 c = ky.x * row(b, -0.5, kx) + ky.y * row(b, 0.5, kx) + ky.z * row(b, 1.5, kx) + ky.w * row(b, 2.5, kx);
    gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""
        // Flipped vertically so glReadPixels returns rows top-down.
        const val COPY = """
void main() { gl_FragColor = texture2D(uTex, (uSt * vec4(vUv.x, 1.0 - vUv.y, 0.0, 1.0)).xy); }
"""
        const val PLAIN = """precision mediump float;
uniform sampler2D uTex;
varying vec2 vUv;
void main() { gl_FragColor = texture2D(uTex, vec2(vUv.x, 1.0 - vUv.y)); }
"""
    }
}
