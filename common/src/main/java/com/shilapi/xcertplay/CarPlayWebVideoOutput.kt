package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Decoder output with independent JPEG and local preview targets. Based on WheelPlay (GPL-3.0). */
internal class CarPlayWebVideoOutput(
    private val width: Int,
    private val height: Int,
    private val wanted: () -> Boolean,
    private val publish: (ByteArray) -> Unit,
) : Closeable {
    private val thread = HandlerThread("carplay-web-video").apply { start() }
    private val handler = Handler(thread.looper)
    private val closed = AtomicBoolean(false)
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var buffer = EGL14.EGL_NO_SURFACE
    private var texture: SurfaceTexture? = null
    private var program = 0
    private var textureId = 0
    private var bitmap: Bitmap? = null
    private var preview = EGL14.EGL_NO_SURFACE
    private var previewTarget: Surface? = null
    private var sourceActive = false
    lateinit var surface: Surface
        private set
    private var hasImage = false
    private var hadViewer = false
    private var dirty = false
    private var scheduled = false
    private var lastPublished = 0L
    private val publishFrame = Runnable {
        scheduled = false
        if (!closed.get() && dirty && wanted()) {
            dirty = false
            lastPublished = SystemClock.elapsedRealtime()
            draw()
        }
    }
    private fun requestFrame() {
        dirty = true
        if (!closed.get() && !scheduled && wanted()) {
            scheduled = true
            handler.postDelayed(publishFrame, (lastPublished + 66 - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        }
    }
    fun setActive(active: Boolean) {
        if (closed.get()) return
        handler.post {
            sourceActive = active
            if (!active) {
                hasImage = false
                dirty = false
                scheduled = false
                handler.removeCallbacks(publishFrame)
                clearPreview()
            }
        }
    }
    fun setPreview(surface: Surface?) {
        if (closed.get()) return
        handler.post {
            if (closed.get()) return@post
            if (previewTarget === surface) return@post
            previewTarget = surface
            EGL14.eglMakeCurrent(display, buffer, buffer, context)
            if (preview != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, preview)
            preview = EGL14.EGL_NO_SURFACE
            if (surface?.isValid == true) {
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                EGL14.eglChooseConfig(display, eglAttributes, 0, configs, 0, 1, count, 0)
                preview = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
                if (hasImage) drawPreview() else clearPreview()
            }
        }
    }
    private val eglAttributes = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT or EGL14.EGL_WINDOW_BIT, EGL14.EGL_RED_SIZE, 8,
        EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE)
    private val viewerCheck = object : Runnable {
        override fun run() {
            if (closed.get()) return
            val viewing = wanted()
            // A newly connected browser also gets a static screen with no new decoder output.
            if (viewing && !hadViewer && hasImage) requestFrame()
            // A disconnect/reconnect can happen between viewer checks. Pending output must
            // resume even if this poll never observed the short interval without a viewer.
            if (viewing && dirty) requestFrame()
            hadViewer = viewing
            handler.postDelayed(this, 200)
        }
    }
    private val pixels by lazy { ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder()) }
    private val vertices = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }
    private val matrix = FloatArray(16)

    init {
        val ready = CountDownLatch(1)
        var failure: Throwable? = null
        handler.post {
            try { initialize() } catch (error: Throwable) { failure = error; release() }
            finally { ready.countDown() }
        }
        if (!ready.await(10, TimeUnit.SECONDS)) {
            close(); error("Offscreen video initialization timed out")
        }
        failure?.let { close(); throw IllegalStateException("Offscreen video unavailable", it) }
    }

    private fun initialize() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<EGLConfig>(1)
        val attributes = eglAttributes
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0)
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        buffer = EGL14.eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, buffer, buffer, context))
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0); textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        // glReadPixels starts at the bottom. This flip makes its first row the top of the bitmap.
        val vertex = shader(GLES20.GL_VERTEX_SHADER, """
            attribute vec2 p; uniform mat4 transform; uniform float flip; varying vec2 uv;
            void main() { gl_Position=vec4(p,0.,1.);
              uv=(transform*vec4((p.x+1.)*.5,mix((p.y+1.)*.5,(1.-p.y)*.5,flip),0.,1.)).xy; }
        """.trimIndent())
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER, """
            #extension GL_OES_EGL_image_external : require
            precision mediump float; uniform samplerExternalOES image; varying vec2 uv;
            void main() { gl_FragColor=texture2D(image,uv); }
        """.trimIndent())
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
        val linked = IntArray(1); GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { GLES20.glGetProgramInfoLog(program) }
        texture = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener({ consumeFrame() }, handler)
        }
        surface = Surface(texture)
        handler.post(viewerCheck)
    }

    private fun shader(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, source); GLES20.glCompileShader(id)
        val compiled = IntArray(1); GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] != 0) { GLES20.glGetShaderInfoLog(id) }
        return id
    }

    private fun consumeFrame() {
        if (closed.get()) return
        try {
            // Drain every decoder notification, even when output is throttled or nobody is viewing.
            EGL14.eglMakeCurrent(display, buffer, buffer, context)
            (texture ?: return).updateTexImage()
            if (!sourceActive) return
            hasImage = true
            drawPreview()
            requestFrame()
        } catch (error: Exception) {
            Log.e("xcertplay-web", "Offscreen frame consumption failed", error)
        }
    }

    private fun draw() {
        if (closed.get()) return
        try {
            EGL14.eglMakeCurrent(display, buffer, buffer, context)
            render(width, height, flip = true)
            pixels.clear()
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            pixels.rewind()
            val frame = bitmap ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap = it }
            frame.copyPixelsFromBuffer(pixels)
            val bytes = ByteArrayOutputStream(width * height / 6)
            frame.compress(Bitmap.CompressFormat.JPEG, 75, bytes)
            publish(bytes.toByteArray())
        } catch (error: Exception) {
            Log.e("xcertplay-web", "Offscreen frame failed", error)
        }
    }

    private fun render(targetWidth: Int, targetHeight: Int, flip: Boolean) {
        val input = texture ?: return
        input.getTransformMatrix(matrix)
        GLES20.glViewport(0, 0, targetWidth, targetHeight)
        GLES20.glUseProgram(program)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "flip"), if (flip) 1f else 0f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "transform"), 1, false, matrix, 0)
        val position = GLES20.glGetAttribLocation(program, "p")
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawPreview() {
        if (preview == EGL14.EGL_NO_SURFACE) return
        if (!EGL14.eglMakeCurrent(display, preview, preview, context)) {
            EGL14.eglDestroySurface(display, preview); preview = EGL14.EGL_NO_SURFACE
            previewTarget = null
            return
        }
        val w = IntArray(1); val h = IntArray(1)
        EGL14.eglQuerySurface(display, preview, EGL14.EGL_WIDTH, w, 0)
        EGL14.eglQuerySurface(display, preview, EGL14.EGL_HEIGHT, h, 0)
        render(w[0], h[0], flip = false)
        EGL14.eglSwapBuffers(display, preview)
        EGL14.eglMakeCurrent(display, buffer, buffer, context)
    }

    private fun clearPreview() {
        if (preview == EGL14.EGL_NO_SURFACE) return
        if (EGL14.eglMakeCurrent(display, preview, preview, context)) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            EGL14.eglSwapBuffers(display, preview)
        }
        EGL14.eglMakeCurrent(display, buffer, buffer, context)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.post { release(); thread.quitSafely() }
    }

    private fun release() {
        handler.removeCallbacks(publishFrame)
        handler.removeCallbacks(viewerCheck)
        if (::surface.isInitialized) surface.release()
        texture?.release(); texture = null
        bitmap?.recycle(); bitmap = null
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (program != 0) GLES20.glDeleteProgram(program)
            if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (preview != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, preview)
            EGL14.eglDestroySurface(display, buffer)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display); display = EGL14.EGL_NO_DISPLAY
        }
    }
}
