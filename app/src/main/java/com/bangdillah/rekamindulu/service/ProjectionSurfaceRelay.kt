package com.bangdillah.rekamindulu.service

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ProjectionSurfaceRelay - Relay tunggal berbasis EGL14 + GLES20 SurfaceTexture yang
 * menerima frame dari satu VirtualDisplay milik MediaProjection lalu meneruskannya ke
 * inputSurface MediaCodec, sekaligus menyediakan tangkapan layar (screenshot) langsung
 * dari frame permukaan aktif tanpa membuat VirtualDisplay kedua (aman untuk Android 14+).
 */
class ProjectionSurfaceRelay(
    private val encoderSurface: Surface,
    width: Int,
    height: Int
) {
    @Volatile
    var width: Int = width
        private set

    @Volatile
    var height: Int = height
        private set
    companion object {
        private const val TAG = "ProjectionSurfaceRelay"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val VERTEX_SHADER = """
            uniform mat4 uMvpMatrix;
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvpMatrix * aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        private val FULL_QUAD_VERTICES = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )

        private val FULL_QUAD_TEX_COORDS = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )

        /**
         * Menghitung matriks skala X/Y (letterbox/pillarbox presisi) agar sumber video dengan rasio
         * aspek berbeda (misal rotasi layar atau split-screen) dirender proporsional tanpa distorsi
         * ke permukaan encoder yang dimensinya sudah terkunci.
         */
        fun computeAspectFitScale(
            sourceWidth: Int,
            sourceHeight: Int,
            targetWidth: Int,
            targetHeight: Int
        ): Pair<Float, Float> {
            if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
                return Pair(1f, 1f)
            }
            val srcRatio = sourceWidth.toFloat() / sourceHeight.toFloat()
            val dstRatio = targetWidth.toFloat() / targetHeight.toFloat()
            if (kotlin.math.abs(srcRatio - dstRatio) < 0.002f) {
                return Pair(1f, 1f)
            }
            return if (srcRatio > dstRatio) {
                Pair(1f, (dstRatio / srcRatio).coerceIn(0.05f, 1f))
            } else {
                Pair((srcRatio / dstRatio).coerceIn(0.05f, 1f), 1f)
            }
        }
    }

    private val thread = HandlerThread("RekaminSurfaceRelay").apply { start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var encoderEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var oesTextureId: Int = 0
    private var fboId: Int = 0
    private var fboTextureId: Int = 0
    private var fboWidth: Int = 0
    private var fboHeight: Int = 0
    private var programId: Int = 0
    private var aPositionLoc: Int = -1
    private var aTexCoordLoc: Int = -1
    private var uMvpMatrixLoc: Int = -1
    private var uTexMatrixLoc: Int = -1

    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null

    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(FULL_QUAD_VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(FULL_QUAD_VERTICES)
            position(0)
        }

    private val texCoordBuffer: FloatBuffer = ByteBuffer.allocateDirect(FULL_QUAD_TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(FULL_QUAD_TEX_COORDS)
            position(0)
        }

    private val texMatrix = FloatArray(16)
    private val renderMvpMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private val screenshotMvpMatrix = FloatArray(16).apply {
        Matrix.setIdentityM(this, 0)
        Matrix.scaleM(this, 0, 1f, -1f, 1f)
    }
    private val flipYMatrix = FloatArray(16).apply {
        Matrix.setIdentityM(this, 0)
        Matrix.scaleM(this, 0, 1f, -1f, 1f)
    }

    @Volatile
    private var sourceWidth: Int = width
    @Volatile
    private var sourceHeight: Int = height

    private val isPaused = AtomicBoolean(false)
    private val isReleased = AtomicBoolean(false)
    @Volatile
    private var hasFrame = false
    private var lastEncoderPtsNs: Long = 0L

    // Real capture FPS & jitter telemetry dari SurfaceTexture VirtualDisplay
    @Volatile
    var currentCaptureFps: Int = 0
        private set
    @Volatile
    var currentCaptureJitterMs: Double = 0.0
        private set
    @Volatile
    var lastCaptureFrameTimeNanos: Long = 0L
        private set

    private var prevTelemetryNs: Long = 0L
    private val telemetryDeltas = LongArray(12)
    private var telemetryIdx = 0
    private var telemetryCount = 0
    private var telemetryEmaFps = 0.0

    fun prepare(): Surface {
        val latch = CountDownLatch(1)
        var initError: Throwable? = null
        var createdSurface: Surface? = null

        handler.post {
            try {
                initEglAndGl()
                createdSurface = inputSurface
            } catch (t: Throwable) {
                initError = t
                cleanupGlOnThread()
            } finally {
                latch.countDown()
            }
        }

        if (!latch.await(2500L, TimeUnit.MILLISECONDS)) {
            release()
            throw IllegalStateException("Timeout saat menyiapkan ProjectionSurfaceRelay")
        }
        initError?.let {
            release()
            throw it
        }
        return createdSurface ?: throw IllegalStateException("Surface relay gagal dibuat")
    }

    fun setPaused(paused: Boolean) {
        isPaused.set(paused)
        if (paused) {
            prevTelemetryNs = 0L
            telemetryCount = 0
            currentCaptureFps = 0
        }
    }

    fun updateSize(newWidth: Int, newHeight: Int) {
        if (isReleased.get() || newWidth <= 0 || newHeight <= 0) return
        handler.post {
            if (isReleased.get()) return@post
            if (width == newWidth && height == newHeight && sourceWidth == newWidth && sourceHeight == newHeight) return@post
            width = newWidth
            height = newHeight
            sourceWidth = newWidth
            sourceHeight = newHeight
            recomputeMvpMatricesOnThread()
            try {
                surfaceTexture?.setDefaultBufferSize(newWidth, newHeight)
            } catch (e: Exception) {
                Log.w(TAG, "setDefaultBufferSize gagal saat resize: ${e.message}")
            }
            deleteOffscreenFboOnThread()
            Log.i(TAG, "ProjectionSurfaceRelay ukuran diperbarui ke ${newWidth}x${newHeight}")
        }
    }

    /**
     * Memperbarui dimensi konten sumber (VirtualDisplay/SurfaceTexture) di tengah rekaman
     * saat ukuran encoder Video MediaCodec sudah terkunci. Frame akan dirender secara aspect-fit
     * dengan letterbox/pillarbox hitam tanpa memutus rekaman.
     */
    fun updateSourceContentSize(newSourceWidth: Int, newSourceHeight: Int) {
        if (isReleased.get() || newSourceWidth <= 0 || newSourceHeight <= 0) return
        handler.post {
            if (isReleased.get()) return@post
            if (sourceWidth == newSourceWidth && sourceHeight == newSourceHeight) return@post
            sourceWidth = newSourceWidth
            sourceHeight = newSourceHeight
            recomputeMvpMatricesOnThread()
            try {
                surfaceTexture?.setDefaultBufferSize(newSourceWidth, newSourceHeight)
            } catch (e: Exception) {
                Log.w(TAG, "setDefaultBufferSize gagal saat updateSourceContentSize: ${e.message}")
            }
            Log.i(
                TAG,
                "ProjectionSurfaceRelay konten sumber disesuaikan ke ${newSourceWidth}x${newSourceHeight} (encoder tetap ${width}x${height})"
            )
        }
    }

    private fun recomputeMvpMatricesOnThread() {
        val (sx, sy) = computeAspectFitScale(sourceWidth, sourceHeight, width, height)
        Matrix.setIdentityM(renderMvpMatrix, 0)
        Matrix.scaleM(renderMvpMatrix, 0, sx, sy, 1f)
        Matrix.multiplyMM(screenshotMvpMatrix, 0, flipYMatrix, 0, renderMvpMatrix, 0)
    }

    fun captureBitmap(callback: (Bitmap?) -> Unit) {
        if (isReleased.get()) {
            callback(null)
            return
        }
        val posted = handler.post {
            if (isReleased.get()) {
                callback(null)
                return@post
            }
            val st = surfaceTexture
            if (st == null) {
                callback(null)
                return@post
            }
            try {
                try {
                    st.updateTexImage()
                    st.getTransformMatrix(texMatrix)
                    hasFrame = true
                } catch (ignored: Exception) {
                    // Tetap gunakan frame OES terakhir jika belum ada frame baru yang menunggu
                }
                if (!hasFrame) {
                    callback(null)
                    return@post
                }

                val snapW = width.coerceAtLeast(1)
                val snapH = height.coerceAtLeast(1)
                ensureOffscreenFbo(snapW, snapH)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
                GLES20.glViewport(0, 0, snapW, snapH)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                drawOesQuad(mvpMatrix = screenshotMvpMatrix)
                GLES20.glFinish()

                val pixelBuffer = ByteBuffer.allocateDirect(snapW * snapH * 4).order(ByteOrder.nativeOrder())
                GLES20.glReadPixels(0, 0, snapW, snapH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuffer)
                pixelBuffer.rewind()

                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

                val bitmap = Bitmap.createBitmap(snapW, snapH, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(pixelBuffer)
                callback(bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "Gagal menangkap frame bitmap dari surface aktif", e)
                try { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0) } catch (_: Exception) {}
                callback(null)
            }
        }
        if (!posted) {
            callback(null)
        }
    }

    fun release() {
        if (isReleased.getAndSet(true)) return
        val latch = CountDownLatch(1)
        val posted = handler.post {
            cleanupGlOnThread()
            latch.countDown()
        }
        if (posted) {
            try {
                latch.await(1500L, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {}
        }
        try {
            thread.quitSafely()
        } catch (_: Exception) {}
    }

    private fun initEglAndGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw IllegalStateException("eglGetDisplay gagal")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw IllegalStateException("eglInitialize gagal")
        }

        val config = chooseEglConfig(eglDisplay)
        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw IllegalStateException("eglCreateContext gagal")
        }

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        encoderEglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, encoderSurface, surfaceAttribs, 0)
        if (encoderEglSurface == EGL14.EGL_NO_SURFACE) {
            throw IllegalStateException("eglCreateWindowSurface gagal")
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, encoderEglSurface, encoderEglSurface, eglContext)) {
            throw IllegalStateException("eglMakeCurrent gagal")
        }

        programId = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
        uMvpMatrixLoc = GLES20.glGetUniformLocation(programId, "uMvpMatrix")
        uTexMatrixLoc = GLES20.glGetUniformLocation(programId, "uTexMatrix")

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        oesTextureId = texIds[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        Matrix.setIdentityM(texMatrix, 0)
        val st = SurfaceTexture(oesTextureId)
        st.setDefaultBufferSize(width, height)
        st.setOnFrameAvailableListener({ availableSt ->
            onFrameAvailable(availableSt)
        }, handler)
        surfaceTexture = st
        inputSurface = Surface(st)
    }

    private fun onFrameAvailable(st: SurfaceTexture) {
        if (isReleased.get()) return
        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            hasFrame = true

            if (isPaused.get()) return

            val nowNs = System.nanoTime()
            updateCaptureTelemetry(nowNs)

            val snapW = width.coerceAtLeast(1)
            val snapH = height.coerceAtLeast(1)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, snapW, snapH)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            drawOesQuad(mvpMatrix = renderMvpMatrix)

            val stTs = st.timestamp
            val rawPts = if (stTs > 0L && kotlin.math.abs(stTs - nowNs) <= 5_000_000_000L) {
                stTs
            } else {
                nowNs
            }
            val monotonicPts = if (rawPts > lastEncoderPtsNs) rawPts else lastEncoderPtsNs + 1_000_000L
            lastEncoderPtsNs = monotonicPts
            EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderEglSurface, monotonicPts)
            EGL14.eglSwapBuffers(eglDisplay, encoderEglSurface)
        } catch (e: Exception) {
            Log.w(TAG, "onFrameAvailable error: ${e.message}")
        }
    }

    private fun updateCaptureTelemetry(nowNs: Long) {
        lastCaptureFrameTimeNanos = nowNs
        val prev = prevTelemetryNs
        prevTelemetryNs = nowNs
        if (prev <= 0L) return
        val delta = nowNs - prev
        if (delta in 4_000_000L..350_000_000L) {
            telemetryDeltas[telemetryIdx] = delta
            telemetryIdx = (telemetryIdx + 1) % telemetryDeltas.size
            if (telemetryCount < telemetryDeltas.size) telemetryCount++
            if (telemetryCount >= 2) {
                var sum = 0L
                for (i in 0 until telemetryCount) sum += telemetryDeltas[i]
                val avg = sum.toDouble() / telemetryCount
                val instantFps = (1_000_000_000.0 / avg.coerceAtLeast(1_000_000.0)).coerceIn(5.0, 144.0)
                telemetryEmaFps = if (telemetryEmaFps <= 0.0) {
                    instantFps
                } else {
                    val alpha = if (instantFps < telemetryEmaFps) 0.45 else 0.30
                    (alpha * instantFps) + ((1.0 - alpha) * telemetryEmaFps)
                }
                var sumJitter = 0.0
                for (i in 0 until telemetryCount) {
                    sumJitter += kotlin.math.abs(telemetryDeltas[i] - avg)
                }
                currentCaptureJitterMs = (sumJitter / telemetryCount) / 1_000_000.0
                currentCaptureFps = Math.round(telemetryEmaFps).toInt().coerceIn(5, 144)
            }
        }
    }

    private fun deleteOffscreenFboOnThread() {
        try {
            if (fboId != 0) {
                GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
                fboId = 0
            }
            if (fboTextureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(fboTextureId), 0)
                fboTextureId = 0
            }
        } catch (_: Exception) {}
        fboWidth = 0
        fboHeight = 0
    }

    private fun ensureOffscreenFbo(targetWidth: Int, targetHeight: Int) {
        if (fboId != 0 && fboTextureId != 0 && fboWidth == targetWidth && fboHeight == targetHeight) return
        deleteOffscreenFboOnThread()

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        fboTextureId = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            targetWidth,
            targetHeight,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null
        )

        val fbos = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        fboId = fbos[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            fboTextureId,
            0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            deleteOffscreenFboOnThread()
            throw IllegalStateException("Offscreen FBO tidak lengkap: status=$status")
        }
        fboWidth = targetWidth
        fboHeight = targetHeight
    }

    private fun drawOesQuad(mvpMatrix: FloatArray) {
        GLES20.glUseProgram(programId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)

        GLES20.glUniformMatrix4fv(uMvpMatrixLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        texCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    private fun chooseEglConfig(display: EGLDisplay): EGLConfig {
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)

        val recordableAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        if (EGL14.eglChooseConfig(display, recordableAttribs, 0, configs, 0, 1, numConfigs, 0) &&
            numConfigs[0] > 0 && configs[0] != null
        ) {
            return configs[0]!!
        }

        val fallbackAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        if (EGL14.eglChooseConfig(display, fallbackAttribs, 0, configs, 0, 1, numConfigs, 0) &&
            numConfigs[0] > 0 && configs[0] != null
        ) {
            return configs[0]!!
        }
        throw IllegalStateException("Tidak menemukan EGLConfig ES2 yang sesuai")
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vShader)
        GLES20.glAttachShader(program, fShader)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        GLES20.glDeleteShader(vShader)
        GLES20.glDeleteShader(fShader)
        if (linked[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw IllegalStateException("Link shader gagal: $log")
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("Kompilasi shader gagal: $log")
        }
        return shader
    }

    private fun cleanupGlOnThread() {
        try {
            inputSurface?.release()
        } catch (_: Exception) {}
        inputSurface = null

        try {
            surfaceTexture?.setOnFrameAvailableListener(null)
            surfaceTexture?.release()
        } catch (_: Exception) {}
        surfaceTexture = null

        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglContext != EGL14.EGL_NO_CONTEXT) {
            try {
                if (fboId != 0) {
                    GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
                    fboId = 0
                }
                if (fboTextureId != 0) {
                    GLES20.glDeleteTextures(1, intArrayOf(fboTextureId), 0)
                    fboTextureId = 0
                }
                if (oesTextureId != 0) {
                    GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
                    oesTextureId = 0
                }
                if (programId != 0) {
                    GLES20.glDeleteProgram(programId)
                    programId = 0
                }
            } catch (_: Exception) {}

            try {
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
            } catch (_: Exception) {}
        }

        if (encoderEglSurface != EGL14.EGL_NO_SURFACE && eglDisplay != EGL14.EGL_NO_DISPLAY) {
            try {
                EGL14.eglDestroySurface(eglDisplay, encoderEglSurface)
            } catch (_: Exception) {}
        }
        encoderEglSurface = EGL14.EGL_NO_SURFACE

        if (eglContext != EGL14.EGL_NO_CONTEXT && eglDisplay != EGL14.EGL_NO_DISPLAY) {
            try {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            } catch (_: Exception) {}
        }
        eglContext = EGL14.EGL_NO_CONTEXT

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            try {
                EGL14.eglTerminate(eglDisplay)
            } catch (_: Exception) {}
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }
}
