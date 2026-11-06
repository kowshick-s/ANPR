package com.vehiclegate.anpr.camera

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A video "screen" that is not on screen: a Surface the player renders into, backed by a
 * SurfaceTexture on a private OpenGL context. Frames can be read back as Bitmaps at any size up
 * to [width]×[height]. This lets cameras be decoded and scanned with the app minimised, the
 * screen off, or the dashboard closed – no View or window needed.
 *
 * Everything GL runs on this object's own thread ([handler]); [grab] must be called there.
 */
class OffscreenFrameSource(val width: Int, val height: Int, name: String) {

    private val thread = HandlerThread("frames-$name").apply { start() }
    val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var texId = 0
    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var uSt = 0
    private val stMatrix = FloatArray(16)
    private var pixels: ByteBuffer? = null

    /** Interleaved x, y, u, v for a full-screen triangle strip (per instance: one GL thread each). */
    private val QUAD: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply {
            put(floatArrayOf(
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f
            ))
            position(0)
        }

    private lateinit var surfaceTexture: SurfaceTexture
    /** Give this to the player as its video output. */
    lateinit var surface: Surface
        private set

    /** Frames delivered by the player so far, and when the last one arrived (elapsedRealtime). */
    @Volatile var frames = 0L; private set
    @Volatile var lastFrameAt = 0L; private set
    @Volatile var error: String? = null; private set
    @Volatile private var released = false

    init {
        val latch = CountDownLatch(1)
        handler.post {
            try { setup() } catch (e: Exception) { error = "GL setup failed: ${e.message}" }
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
    }

    private fun setup() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "eglInitialize" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) { "eglChooseConfig" }
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        eglSurface = EGL14.eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent" }

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        program = buildProgram(VERTEX, FRAGMENT)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uSt = GLES20.glGetUniformLocation(program, "uSt")

        surfaceTexture = SurfaceTexture(texId)
        surfaceTexture.setDefaultBufferSize(width, height)
        surfaceTexture.setOnFrameAvailableListener({ st ->
            if (released) return@setOnFrameAvailableListener
            try {
                st.updateTexImage() // always consume, otherwise the decoder stalls
                frames++
                lastFrameAt = SystemClock.elapsedRealtime()
            } catch (_: Exception) { }
        }, handler)
        surface = Surface(surfaceTexture)
    }

    /**
     * Renders the latest frame scaled to [w]×[h] (≤ source size) and returns it as a Bitmap.
     * Reuses [into] when it has the right size. Must be called on [handler]'s thread.
     */
    fun grab(w: Int, h: Int, into: Bitmap? = null): Bitmap? {
        if (released || frames == 0L || error != null) return null
        val ow = w.coerceIn(1, width)
        val oh = h.coerceIn(1, height)
        surfaceTexture.getTransformMatrix(stMatrix)
        GLES20.glViewport(0, 0, ow, oh)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniformMatrix4fv(uSt, 1, false, stMatrix, 0)
        QUAD.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, QUAD)
        GLES20.glEnableVertexAttribArray(aPos)
        QUAD.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, QUAD)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        val need = ow * oh * 4
        val buf = pixels?.takeIf { it.capacity() >= need }
            ?: ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder()).also { pixels = it }
        buf.clear()
        GLES20.glReadPixels(0, 0, ow, oh, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        buf.rewind()
        buf.limit(need)
        val bmp = into?.takeIf { it.width == ow && it.height == oh && it.isMutable && !it.isRecycled }
            ?: Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf)
        return bmp
    }

    fun release() {
        if (released) return
        released = true
        handler.post {
            try { surfaceTexture.setOnFrameAvailableListener(null) } catch (_: Exception) {}
            try { surface.release() } catch (_: Exception) {}
            try { surfaceTexture.release() } catch (_: Exception) {}
            try {
                if (program != 0) GLES20.glDeleteProgram(program)
                if (texId != 0) GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, eglSurface)
                EGL14.eglDestroyContext(display, context)
            } catch (_: Exception) {}
            thread.quitSafely()
        }
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val sh = GLES20.glCreateShader(type)
            GLES20.glShaderSource(sh, src)
            GLES20.glCompileShader(sh)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(sh) }
            return sh
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    companion object {
        // y is flipped so glReadPixels (bottom-up rows) yields an upright bitmap
        private const val VERTEX = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uSt;
            varying vec2 vTex;
            void main() {
                gl_Position = vec4(aPos.x, -aPos.y, 0.0, 1.0);
                vTex = (uSt * aTex).xy;
            }"""
        private const val FRAGMENT = """#extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }"""

    }
}
