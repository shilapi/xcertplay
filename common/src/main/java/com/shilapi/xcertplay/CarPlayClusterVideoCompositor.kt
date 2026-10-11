package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.shilapi.xcertplay.airplay.CarPlayOemThemeRuntime
import com.shilapi.xcertplay.media.MediaCodecSupport
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Owns an offscreen phone-video decoder target, OEM overlay and H.264 encoder for the web cluster. */
internal class CarPlayClusterVideoCompositor(
    context: Context,
    runtime: CarPlayOemThemeRuntime,
    private val width: Int,
    private val height: Int,
    private val hasViewer: () -> Boolean,
    private val onConfig: (ByteArray) -> Unit,
    private val onFrame: (ByteArray, Long) -> Unit,
    private val onError: (Throwable) -> Unit,
) : Closeable {
    private val thread = HandlerThread("carplay-cluster-compositor").apply { start() }
    private val handler = Handler(thread.looper)
    private val closed = AtomicBoolean(false)
    private val keyFrame = AtomicBoolean(true)
    private val remoteAvailable = AtomicBoolean(false)
    private val renderer = CarPlayOemThemeRenderer(context.applicationContext)
    private var encoder: MediaCodec? = null
    private var encoderInput: Surface? = null
    private var started = false
    private var texture: SurfaceTexture? = null
    lateinit var inputSurface: Surface; private set
    private var display = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var encoderWindow = EGL14.EGL_NO_SURFACE
    private var previewWindow = EGL14.EGL_NO_SURFACE
    private var previewSurface: Surface? = null
    private var videoTexture = 0
    private var overlayTexture = 0
    private var videoProgram = 0
    private var overlayProgram = 0
    private var pendingTexture = false
    private var haveVideo = false
    private var bitmap: Bitmap? = null
    private val videoMatrix = FloatArray(16)
    private val identity = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    // SurfaceTexture supplies its own transform (including the producer's vertical flip).
    private val quad = buffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val videoUv = buffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
    private val bitmapUv = buffer(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))
    private val info = MediaCodec.BufferInfo()
    private val tick = object : Runnable {
        override fun run() {
            if (closed.get()) return
            try {
                render()
                handler.postDelayed(this, FRAME_INTERVAL_MS)
            } catch (error: Throwable) { fail(error) }
        }
    }

    init {
        val ready = CountDownLatch(1)
        var failure: Throwable? = null
        handler.post {
            try { renderer.bind(runtime); initialize() }
            catch (error: Throwable) { failure = error; release() }
            finally { ready.countDown() }
        }
        try {
            check(ready.await(10, TimeUnit.SECONDS)) { "Cluster compositor initialization timed out" }
            failure?.let { throw IllegalStateException("Could not initialize cluster encoder", it) }
            handler.post(tick)
        } catch (error: Throwable) { close(); throw error }
    }

    fun requestKeyFrame(): Boolean {
        if (closed.get()) return false
        keyFrame.set(true)
        return true
    }

    fun remoteActive(active: Boolean) {
        remoteAvailable.set(active)
        if (!active) handler.post { haveVideo = false; pendingTexture = false }
    }

    fun preview(surface: Surface?) {
        if (closed.get()) return
        handler.post {
            if (closed.get() || previewSurface === surface) return@post
            try {
                makeCurrent(encoderWindow)
                if (previewWindow != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, previewWindow)
                previewWindow = EGL14.EGL_NO_SURFACE
                previewSurface = surface
                if (surface != null && surface.isValid) previewWindow = window(surface)
            } catch (error: Throwable) {
                // A disappearing local Surface must not stop the remote instrument stream.
                previewSurface = null
                android.util.Log.w("xcertplay-usb", "Cluster preview surface unavailable", error)
            }
        }
    }

    private fun initialize() {
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { codec ->
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, width * height * 4)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderInput = codec.createInputSurface()
            codec.start(); started = true
        }
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<EGLConfig>(1)
        val attrs = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0)
        config = checkNotNull(configs[0])
        eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT)
        encoderWindow = window(checkNotNull(encoderInput)); makeCurrent(encoderWindow)
        videoProgram = program("#extension GL_OES_EGL_image_external : require\n", "samplerExternalOES")
        overlayProgram = program("", "sampler2D")
        videoTexture = texture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        overlayTexture = texture(GLES20.GL_TEXTURE_2D)
        texture = SurfaceTexture(videoTexture).also {
            it.setDefaultBufferSize(width, height)
            it.setOnFrameAvailableListener({ pendingTexture = true }, handler)
            inputSurface = Surface(it)
        }
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    private fun render() {
        val codec = checkNotNull(encoder)
        makeCurrent(encoderWindow)
        drain(codec)
        if (pendingTexture) {
            checkNotNull(texture).updateTexImage()
            texture?.getTransformMatrix(videoMatrix)
            pendingTexture = false
            haveVideo = remoteAvailable.get()
        }
        // UI-independent rendering also advances smoothing when the phone sends a static picture.
        if (hasViewer()) {
            val overlay = checkNotNull(bitmap)
            val canvas = Canvas(overlay)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            renderer.draw(canvas, width, height)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexture)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlay, 0)
            draw(width, height, composed = true)
            if (keyFrame.getAndSet(false)) codec.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
            EGLExt.eglPresentationTimeANDROID(display, encoderWindow, System.nanoTime())
            check(EGL14.eglSwapBuffers(display, encoderWindow)) { "Cluster encoder surface lost" }
            drain(codec)
        }
        if (previewWindow != EGL14.EGL_NO_SURFACE) {
            try {
                makeCurrent(previewWindow)
                val w = IntArray(1); val h = IntArray(1)
                check(EGL14.eglQuerySurface(display, previewWindow, EGL14.EGL_WIDTH, w, 0))
                check(EGL14.eglQuerySurface(display, previewWindow, EGL14.EGL_HEIGHT, h, 0))
                // The local View draws its own identical overlay above this phone-video preview.
                draw(w[0], h[0], composed = false)
                check(EGL14.eglSwapBuffers(display, previewWindow))
            } catch (error: Throwable) {
                makeCurrent(encoderWindow)
                EGL14.eglDestroySurface(display, previewWindow)
                previewWindow = EGL14.EGL_NO_SURFACE; previewSurface = null
                android.util.Log.w("xcertplay-usb", "Cluster preview surface lost", error)
            }
        }
    }

    private fun draw(targetWidth: Int, targetHeight: Int, composed: Boolean) {
        GLES20.glViewport(0, 0, targetWidth, targetHeight)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (haveVideo) drawTexture(videoProgram, videoTexture, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoUv, videoMatrix)
        if (composed) {
            // Canvas bitmaps contain premultiplied alpha; transparent remote zones reveal phone video.
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            drawTexture(overlayProgram, overlayTexture, GLES20.GL_TEXTURE_2D, bitmapUv, identity)
            GLES20.glDisable(GLES20.GL_BLEND)
        }
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "Cluster GL rendering failed" }
    }

    private fun drain(codec: MediaCodec) {
        while (true) {
            when (val index = codec.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    onConfig(ClusterAvcConfig.create(bytes(format.getByteBuffer("csd-0")), bytes(format.getByteBuffer("csd-1"))))
                }
                else -> if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val output = checkNotNull(codec.getOutputBuffer(index)).duplicate()
                            output.position(info.offset); output.limit(info.offset + info.size)
                            val accessUnit = MediaCodecSupport.toLengthPrefixed(bytes(output))
                            if (accessUnit.isNotEmpty()) onFrame(accessUnit, info.presentationTimeUs)
                        }
                    } finally { codec.releaseOutputBuffer(index, false) }
                } else return
            }
        }
    }

    private fun drawTexture(program: Int, id: Int, target: Int, uv: java.nio.FloatBuffer, matrix: FloatArray) {
        GLES20.glUseProgram(program)
        val pos = GLES20.glGetAttribLocation(program, "position")
        val tex = GLES20.glGetAttribLocation(program, "texCoord")
        quad.position(0); uv.position(0)
        GLES20.glEnableVertexAttribArray(pos); GLES20.glEnableVertexAttribArray(tex)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "transform"), 1, false, matrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(target, id)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos); GLES20.glDisableVertexAttribArray(tex)
    }

    private fun program(extension: String, sampler: String): Int {
        fun shader(type: Int, source: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, source); GLES20.glCompileShader(id)
            val result = IntArray(1); GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, result, 0)
            if (result[0] == 0) { val message = GLES20.glGetShaderInfoLog(id); GLES20.glDeleteShader(id); error(message) }
            return id
        }
        val vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec4 position; attribute vec4 texCoord; uniform mat4 transform; varying vec2 uv; void main(){ gl_Position=position; uv=(transform*texCoord).xy; }")
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER, "${extension}precision mediump float; uniform $sampler image; varying vec2 uv; void main(){gl_FragColor=texture2D(image,uv);}")
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vertex); GLES20.glAttachShader(id, fragment); GLES20.glLinkProgram(id)
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
        val result = IntArray(1); GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, result, 0)
        check(result[0] != 0) { GLES20.glGetProgramInfoLog(id) }
        return id
    }

    private fun texture(target: Int): Int {
        val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(target, ids[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun window(surface: Surface): EGLSurface = EGL14.eglCreateWindowSurface(display, config, surface,
        intArrayOf(EGL14.EGL_NONE), 0).also { check(it != EGL14.EGL_NO_SURFACE) { "Could not create cluster EGL window" } }
    private fun makeCurrent(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, surface, surface, eglContext)) { "Could not make cluster EGL context current" }
    }
    private fun fail(error: Throwable) { onError(error); close() }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.removeCallbacks(tick)
        handler.post { release(); thread.quitSafely() }
    }
    private fun release() {
        if (::inputSurface.isInitialized) inputSurface.release()
        texture?.release(); texture = null
        bitmap?.recycle(); bitmap = null
        renderer.bind(null)
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (previewWindow != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, previewWindow)
            if (encoderWindow != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, encoderWindow)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
            EGL14.eglReleaseThread(); EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY; encoderWindow = EGL14.EGL_NO_SURFACE; previewWindow = EGL14.EGL_NO_SURFACE
        encoder?.let { if (started) runCatching { it.stop() }; it.release() }; encoder = null; started = false
        encoderInput?.release(); encoderInput = null
    }
    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val FPS = 30
        private const val FRAME_INTERVAL_MS = 1000L / FPS
        private fun buffer(values: FloatArray) = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
        private fun bytes(buffer: ByteBuffer?): ByteArray = buffer?.duplicate()?.let { ByteArray(it.remaining()).also(it::get) } ?: ByteArray(0)
    }
}

/** Converts MediaCodec's Annex B parameter sets into the avcC record WebCodecs expects. */
internal object ClusterAvcConfig {
    fun create(vararg csd: ByteArray): ByteArray {
        val units = csd.flatMap { MediaCodecSupport.annexBNalUnits(it) }
        val sps = units.firstOrNull { it.isNotEmpty() && it[0].toInt() and 31 == 7 }
            ?: error("Cluster encoder did not return SPS")
        val pps = units.firstOrNull { it.isNotEmpty() && it[0].toInt() and 31 == 8 }
            ?: error("Cluster encoder did not return PPS")
        require(sps.size in 4..65535 && pps.size in 1..65535)
        return ByteBuffer.allocate(11 + sps.size + pps.size).apply {
            put(1); put(sps[1]); put(sps[2]); put(sps[3]); put(0xff.toByte()); put(0xe1.toByte())
            putShort(sps.size.toShort()); put(sps); put(1); putShort(pps.size.toShort()); put(pps)
        }.array()
    }
}
