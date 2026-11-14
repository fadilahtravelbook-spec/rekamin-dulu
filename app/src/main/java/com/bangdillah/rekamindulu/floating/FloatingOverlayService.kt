package com.bangdillah.rekamindulu.floating

import android.Manifest
import android.animation.ValueAnimator
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.util.Size
import android.view.Choreographer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import com.bangdillah.rekamindulu.HomeActivity
import com.bangdillah.rekamindulu.R
import com.bangdillah.rekamindulu.service.ScreenRecordingService
import com.bangdillah.rekamindulu.utils.AppPreferences
import com.bangdillah.rekamindulu.utils.UiHelper
import java.util.Locale
import kotlin.math.abs

/**
 * FloatingOverlayService - Pengontrol rekaman layar melayang ultra-clean, responsif & profesional.
 * Didesain khusus agar tidak mengganggu pemain game (Mode Kapsul Siluman + Auto-Dimming di Tepi Layar),
 * dengan Mesin Pengukur FPS Real-Time, Tombol Mute Mic Langsung, Screenshot Asli, dan Facecam Live.
 */
class FloatingOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var params: WindowManager.LayoutParams? = null

    // Facecam Floating Window & Camera2 state
    private var facecamView: View? = null
    private var facecamParams: WindowManager.LayoutParams? = null
    private var facecamTextureView: TextureView? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var cameraSession: CameraCaptureSession? = null
    private var previewSize: Size = Size(480, 640)
    private var cameraSensorOrientation: Int = 90
    private var cameraLensFacing: Int = CameraCharacteristics.LENS_FACING_FRONT

    private var isPaused = false
    private var isMinimized = true // Default minimized agar pemain game langsung bebas bermain tanpa terhalang!
    private var dummyAnimator: ValueAnimator? = null
    private var snapAnimator: ValueAnimator? = null

    private val handler = Handler(Looper.getMainLooper())
    private var appPreferences: AppPreferences? = null

    // Mode Stealth Gamer: Pilihan tingkat transparansi agar visual game tetap terlihat jelas
    private var transparencyIndex = 0
    private val transparencyLevels = floatArrayOf(1.0f, 0.65f, 0.35f)

    // ==========================================
    // MESIN PENGUKUR FPS DINAMIS & STABILISATOR GAME
    // ==========================================
    private var previousFrameTimeNanos = 0L
    private val frameIntervalBuffer = LongArray(16) // buffer sirkular selang waktu frame
    private var bufferIndex = 0
    private var bufferFilled = 0
    private var lastUiUpdateNanos = 0L
    private var currentMeasuredFps = 60
    private var sessionMinFps = 999
    private var sessionMaxFps = 0

    // Exponential Moving Average (EMA) untuk menstabilkan jitter visual
    private var smoothedFps = 60.0

    // Pacing history untuk 6 segmen bar visual
    private val pacingHistory = IntArray(6) { 60 }
    private var pacingHistoryIndex = 0

    // FrameCallback Choreographer dengan frekuensi VSYNC hardware
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (previousFrameTimeNanos > 0L) {
                val delta = frameTimeNanos - previousFrameTimeNanos
                // Filter frame delta masuk akal (4ms - 300ms)
                if (delta in 4_000_000L..300_000_000L) {
                    frameIntervalBuffer[bufferIndex] = delta
                    bufferIndex = (bufferIndex + 1) % frameIntervalBuffer.size
                    if (bufferFilled < frameIntervalBuffer.size) bufferFilled++
                }
            }
            previousFrameTimeNanos = frameTimeNanos

            // Update UI FPS setiap ~500ms agar tampilan dinamis, responsif, dan mencerminkan drop frame game
            if (frameTimeNanos - lastUiUpdateNanos >= 500_000_000L && bufferFilled >= 2) {
                lastUiUpdateNanos = frameTimeNanos
                calculateAndDispatchDynamicFps(frameTimeNanos)
            }

            if (ScreenRecordingService.isRecording) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    private fun calculateAndDispatchDynamicFps(frameTimeNanos: Long) {
        if (isPaused) {
            updateFpsEvaluationUi(
                measuredFps = 0,
                jitterMs = 0.0,
                minFps = if (sessionMinFps > 144) 0 else sessionMinFps,
                maxFps = sessionMaxFps
            )
            return
        }

        val count = bufferFilled.coerceAtLeast(1)
        var sumDelta = 0L
        for (i in 0 until count) {
            sumDelta += frameIntervalBuffer[i]
        }
        val avgDeltaNanos = sumDelta.toDouble() / count
        val instantFps = (1_000_000_000.0 / avgDeltaNanos.coerceAtLeast(1_000_000.0)).coerceIn(10.0, 144.0)

        val prefs = appPreferences ?: AppPreferences(this).also { appPreferences = it }
        val targetFps = prefs.fps
        val hardwareHz = getHardwareRefreshRate()

        // Hitung frame jitter aktual (deviasi antar selang VSYNC dalam ms)
        var sumJitter = 0.0
        for (i in 0 until count) {
            sumJitter += abs(frameIntervalBuffer[i] - avgDeltaNanos)
        }
        val avgJitterMs = (sumJitter / count) / 1_000_000.0

        // Filter EMA adaptif:
        // Jika terjadi drop FPS nyata (instantFps < smoothedFps), respons cepat (bobot 45%)
        // agar gamer langsung melihat penurunan performa game secara real-time.
        val alpha = if (instantFps < smoothedFps) 0.45 else 0.30
        smoothedFps = (alpha * instantFps) + ((1.0 - alpha) * smoothedFps)
        val finalFps = Math.round(smoothedFps).toInt().coerceIn(10, hardwareHz)

        currentMeasuredFps = finalFps

        if (finalFps in 10..144) {
            if (finalFps < sessionMinFps) sessionMinFps = finalFps
            if (finalFps > sessionMaxFps) sessionMaxFps = finalFps
        }

        pacingHistory[pacingHistoryIndex] = finalFps
        pacingHistoryIndex = (pacingHistoryIndex + 1) % pacingHistory.size

        updateFpsEvaluationUi(
            measuredFps = finalFps,
            jitterMs = avgJitterMs,
            minFps = if (sessionMinFps > 144) finalFps else sessionMinFps,
            maxFps = if (sessionMaxFps == 0) finalFps else sessionMaxFps
        )
    }

    // ==========================================
    // AUTO-COLLAPSE & IDLE DIMMING TIMERS
    // ==========================================
    // Otomatis menutup panel besar ke bola mini setelah 4.5 detik tanpa sentuhan
    private val autoCollapseRunnable = Runnable {
        if (!isMinimized && ScreenRecordingService.isRecording) {
            toggleMinimizeMode(true)
        }
    }

    private fun resetAutoCollapseTimer() {
        handler.removeCallbacks(autoCollapseRunnable)
        if (!isMinimized && ScreenRecordingService.isRecording) {
            handler.postDelayed(autoCollapseRunnable, 4500L)
        }
    }

    // Auto-Dimming Bola Mini: Otomatis memudarkan kapsul mini ke 40% setelah 3 detik idle di tepi layar
    private val idleDimRunnable = Runnable {
        if (isMinimized && ScreenRecordingService.isRecording) {
            overlayView?.findViewById<View>(R.id.overlay_bubble_container)
                ?.animate()
                ?.alpha(0.40f)
                ?.setDuration(350)
                ?.start()
        }
    }

    private fun startIdleDimTimer() {
        handler.removeCallbacks(idleDimRunnable)
        if (isMinimized && ScreenRecordingService.isRecording) {
            handler.postDelayed(idleDimRunnable, 3000L)
        }
    }

    private fun wakeBubbleFromDim() {
        handler.removeCallbacks(idleDimRunnable)
        overlayView?.findViewById<View>(R.id.overlay_bubble_container)
            ?.animate()
            ?.alpha(1.0f)
            ?.setDuration(150)
            ?.start()
    }

    private fun cycleTransparency() {
        transparencyIndex = (transparencyIndex + 1) % transparencyLevels.size
        val alpha = transparencyLevels[transparencyIndex]
        overlayView?.findViewById<View>(R.id.overlay_container)?.alpha = alpha
        val btnTrans = overlayView?.findViewById<ImageView>(R.id.btn_overlay_transparency)
        triggerVibration(25)
        when (transparencyIndex) {
            0 -> {
                btnTrans?.imageTintList = ColorStateList.valueOf(0xAAFFFFFF.toInt())
                Toast.makeText(this, "👁️ HUD Normal (100%)", Toast.LENGTH_SHORT).show()
            }
            1 -> {
                btnTrans?.imageTintList = ColorStateList.valueOf(0xFF00E676.toInt())
                Toast.makeText(this, "👻 Semi-Transparan (65%)", Toast.LENGTH_SHORT).show()
            }
            2 -> {
                btnTrans?.imageTintList = ColorStateList.valueOf(0xFFFFAB00.toInt())
                Toast.makeText(this, "🥷 Mode Ghost Gamer (35%)", Toast.LENGTH_SHORT).show()
            }
        }
        resetAutoCollapseTimer()
    }

    companion object {
        const val TAG = "FloatingOverlayService"

        /**
         * Format durasi aktif perekaman layar ke format waktu ramah pengguna:
         * - mm:ss untuk durasi di bawah 1 jam (misal 05:32)
         * - HH:mm:ss untuk durasi 1 jam atau lebih (misal 01:14:08)
         */
        fun formatDuration(totalSeconds: Int): String {
            val s = totalSeconds.coerceAtLeast(0)
            val hours = s / 3600
            val minutes = (s % 3600) / 60
            val seconds = s % 60
            return if (hours > 0) {
                String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%02d:%02d", minutes, seconds)
            }
        }

        /**
         * Menghitung sudut rotasi TextureView Facecam berdasarkan orientasi layar (Surface.ROTATION_*).
         */
        fun computeFacecamRotationDegrees(displayRotation: Int): Float {
            return when (displayRotation) {
                Surface.ROTATION_90 -> 270f
                Surface.ROTATION_270 -> 90f
                Surface.ROTATION_180 -> 180f
                else -> 0f
            }
        }

        /**
         * Menentukan label status audio untuk overlay panel HUD secara akurat
         * dengan memperhitungkan kondisi live mute mikrofon.
         */
        fun resolveAudioStatusLabel(audioSource: String, isRecording: Boolean, isMicMuted: Boolean): String {
            if (isRecording && isMicMuted) {
                return when (audioSource) {
                    "BOTH" -> "🔊 Audio Game (Mic Mute)"
                    "MIC" -> "🔇 Mic Dibisukan"
                    "INTERNAL" -> "🔊 Audio Game"
                    else -> "🔇 Audio Mute"
                }
            }
            return when (audioSource) {
                "BOTH" -> "🎙️ Mic + 🔊 Game"
                "MIC" -> "🎙️ Mic Aktif"
                "INTERNAL" -> "🔊 Audio Game"
                else -> "🔇 Audio Mute"
            }
        }

        /**
         * Ringkasan singkat kanal audio untuk baris detail FPS HUD.
         */
        fun resolveAudioSummarySuffix(audioSource: String, isRecording: Boolean, isMicMuted: Boolean): String {
            if (isRecording && isMicMuted) {
                return when (audioSource) {
                    "BOTH" -> " • Audio: Game (Mic Mute)"
                    "MIC" -> " • Audio: Mic Mute"
                    "INTERNAL" -> " • Audio: Game"
                    else -> " • Audio: Mute"
                }
            }
            return when (audioSource) {
                "INTERNAL" -> " • Audio: Game"
                "MIC" -> " • Audio: Mic"
                "BOTH" -> " • Audio: Mic+Game"
                else -> " • Audio: Mute"
            }
        }
    }

    private data class FpsRating(
        val grade: String,
        val advice: String,
        val colorHex: Int,
        val bgTintHex: Int
    )

    /**
     * Memperbarui tampilan timer melayang (on-screen timer) pada overlay
     * secara real-time pada Mode Bola Mini (Pill) maupun Panel HUD Gamer.
     */
    fun updateOverlayTimer() {
        val sec = ScreenRecordingService.elapsedSeconds
        val timerText = formatDuration(sec)
        val isPausedNow = ScreenRecordingService.isPaused

        val expandedTimerView = overlayView?.findViewById<TextView>(R.id.overlay_timer_text)
        val bubbleTimerView = overlayView?.findViewById<TextView>(R.id.overlay_bubble_timer_text)
        val bubblePulse = overlayView?.findViewById<ImageView>(R.id.overlay_bubble_pulse_dot)
        val headerPulse = overlayView?.findViewById<ImageView>(R.id.overlay_pulse_icon)

        expandedTimerView?.text = timerText
        expandedTimerView?.contentDescription = "Durasi rekaman $timerText"
        bubbleTimerView?.text = timerText
        bubbleTimerView?.contentDescription = "Durasi rekaman $timerText"

        if (isPausedNow) {
            expandedTimerView?.setTextColor(0xFFFF9F43.toInt())
            bubbleTimerView?.setTextColor(0xFFFF9F43.toInt())
            bubblePulse?.alpha = 0.4f
            headerPulse?.alpha = 0.4f
        } else {
            expandedTimerView?.setTextColor(0xFFFF4757.toInt())
            bubbleTimerView?.setTextColor(0xFFFFFFFF.toInt())
            bubblePulse?.alpha = 1.0f
            headerPulse?.alpha = 1.0f
        }
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (ScreenRecordingService.isRecording) {
                updateOverlayTimer()

                // Deteksi jika foreground game freeze/lag berat sehingga Choreographer terhambat > 600ms
                val now = System.nanoTime()
                if (previousFrameTimeNanos > 0L && (now - previousFrameTimeNanos) > 600_000_000L) {
                    val stallDelta = minOf(now - previousFrameTimeNanos, 300_000_000L)
                    frameIntervalBuffer[bufferIndex] = stallDelta
                    bufferIndex = (bufferIndex + 1) % frameIntervalBuffer.size
                    if (bufferFilled < frameIntervalBuffer.size) bufferFilled++
                    calculateAndDispatchDynamicFps(now)
                }

                handler.postDelayed(this, 1000)
            }
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ScreenRecordingService.ACTION_RECORDING_STOPPED -> {
                    stopOverlay()
                }
                ScreenRecordingService.ACTION_RECORDING_STATUS_CHANGED -> {
                    syncOverlayStatus()
                }
                ScreenRecordingService.ACTION_SCREENSHOT_RESULT -> {
                    overlayView?.findViewById<View>(R.id.overlay_container)?.let { container ->
                        container.animate()?.cancel()
                        container.alpha = transparencyLevels[transparencyIndex]
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        appPreferences = AppPreferences(this)
        val filter = IntentFilter().apply {
            addAction(ScreenRecordingService.ACTION_RECORDING_STATUS_CHANGED)
            addAction(ScreenRecordingService.ACTION_RECORDING_STOPPED)
            addAction(ScreenRecordingService.ACTION_SCREENSHOT_RESULT)
        }
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        setupOverlayWindow()
        startFrameHeartbeat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        syncOverlayStatus()
        updateOverlayTimer()
        return START_NOT_STICKY
    }

    private fun startFrameHeartbeat() {
        // Bebaskan CPU dari loop animator berat agar 100% core CPU dialokasikan untuk game & hardware encoder
        try {
            dummyAnimator?.cancel()
            dummyAnimator = null
        } catch (ignored: Exception) {}
    }

    private fun getHardwareRefreshRate(): Int {
        return try {
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION")
                windowManager?.defaultDisplay
            }
            (display?.refreshRate ?: 60f).toInt().coerceAtLeast(30)
        } catch (e: Exception) {
            60
        }
    }

    private fun getScreenDimensions(): Pair<Int, Int> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val metrics = windowManager?.currentWindowMetrics
                val bounds = metrics?.bounds ?: Rect(0, 0, 1080, 1920)
                Pair(bounds.width(), bounds.height())
            } else {
                val dm = resources.displayMetrics
                Pair(dm.widthPixels, dm.heightPixels)
            }
        } catch (e: Exception) {
            Pair(1080, 1920)
        }
    }

    private fun updateFpsEvaluationUi(
        measuredFps: Int,
        jitterMs: Double,
        minFps: Int,
        maxFps: Int
    ) {
        val prefs = appPreferences ?: AppPreferences(this).also { appPreferences = it }
        val targetFps = prefs.fps
        val hardwareHz = getHardwareRefreshRate()

        val textFps = overlayView?.findViewById<TextView>(R.id.overlay_fps_text)
        val bubbleFps = overlayView?.findViewById<TextView>(R.id.overlay_bubble_fps_text)
        val boxFpsEvaluation = overlayView?.findViewById<View>(R.id.box_fps_evaluation)
        val ratingBadge = overlayView?.findViewById<TextView>(R.id.overlay_fps_rating_badge)
        val detailsText = overlayView?.findViewById<TextView>(R.id.overlay_fps_details)
        val adviceText = overlayView?.findViewById<TextView>(R.id.overlay_fps_advice)

        val showFps = prefs.showFpsCounter
        textFps?.visibility = if (showFps) View.VISIBLE else View.GONE
        bubbleFps?.visibility = if (showFps) View.VISIBLE else View.GONE
        boxFpsEvaluation?.visibility = if (showFps) View.VISIBLE else View.GONE

        if (!showFps) {
            return
        }

        if (isPaused) {
            textFps?.text = "0 FPS"
            bubbleFps?.text = "0 FPS"
            ratingBadge?.text = "DIJEDA"
            ratingBadge?.backgroundTintList = ColorStateList.valueOf(0x33FF9F43)
            ratingBadge?.setTextColor(0xFFFF9F43.toInt())

            detailsText?.text = "Perekaman Layar Sedang Dijeda"
            adviceText?.text = "Ketuk tombol Lanjut untuk meneruskan rekaman layar."
            updatePacingBars(0, targetFps)
            return
        }

        val stabilityPct = if (targetFps > 0) {
            if (measuredFps >= targetFps) 100 else ((measuredFps.toFloat() / targetFps.toFloat()) * 100).toInt().coerceIn(0, 100)
        } else 100

        val jitterFormatted = String.format(Locale.US, "%.1f", jitterMs)
        val rating = when {
            measuredFps >= targetFps -> FpsRating(
                grade = "S+ ESPORTS READY (100% STABIL)",
                advice = "Terkunci Lancar: Frame rate konsisten $targetFps FPS, jitter ${jitterFormatted}ms, GPU HW aktif.",
                colorHex = 0xFF00E676.toInt(),
                bgTintHex = 0x2600E676.toInt()
            )
            measuredFps >= targetFps - 3 -> FpsRating(
                grade = "A+ SANGAT MULUS ($measuredFps FPS)",
                advice = "Sinkron: $stabilityPct% target, jitter ${jitterFormatted}ms, rekaman lancar bebas beban.",
                colorHex = 0xFF00E676.toInt(),
                bgTintHex = 0x2600E676.toInt()
            )
            measuredFps >= targetFps - 8 -> FpsRating(
                grade = "A STABIL ($measuredFps FPS)",
                advice = "Performa game baik ($measuredFps FPS, jitter ${jitterFormatted}ms). Video rekaman sinkron.",
                colorHex = 0xFF00B894.toInt(),
                bgTintHex = 0x2600B894.toInt()
            )
            measuredFps >= 35 -> FpsRating(
                grade = "B BEBAN BERAT ($measuredFps FPS)",
                advice = "Beban grafis game meningkat ($measuredFps FPS, jitter ${jitterFormatted}ms). Encoder menyeimbangkan buffer.",
                colorHex = 0xFFFFAB00.toInt(),
                bgTintHex = 0x26FFAB00.toInt()
            )
            else -> FpsRating(
                grade = "C FRAME DROP ($measuredFps FPS)",
                advice = "Terdeteksi lag game ($measuredFps FPS)! Prioritas thread hardware diselaraskan otomatis.",
                colorHex = 0xFFFF5252.toInt(),
                bgTintHex = 0x26FF5252.toInt()
            )
        }

        val fpsString = "$measuredFps FPS"
        textFps?.text = fpsString
        bubbleFps?.text = fpsString

        textFps?.backgroundTintList = ColorStateList.valueOf(rating.bgTintHex)
        textFps?.setTextColor(rating.colorHex)
        bubbleFps?.backgroundTintList = ColorStateList.valueOf(rating.bgTintHex)
        bubbleFps?.setTextColor(rating.colorHex)

        ratingBadge?.text = rating.grade
        ratingBadge?.backgroundTintList = ColorStateList.valueOf(rating.bgTintHex)
        ratingBadge?.setTextColor(rating.colorHex)

        val gpuLabel = if (prefs.hardwareAccelerationEnabled) " • GPU HW" else ""
        val turboLabel = if (prefs.gamingTurboBoost) " • Turbo CPU" else ""
        val activeAudioSource = (if (ScreenRecordingService.isRecording) ScreenRecordingService.effectiveAudioMode else null)
            ?: prefs.audioSource
        val audioModeLabel = resolveAudioSummarySuffix(
            audioSource = activeAudioSource,
            isRecording = ScreenRecordingService.isRecording,
            isMicMuted = ScreenRecordingService.isMicMuted
        )
        detailsText?.text = "Target: ${targetFps} FPS • Stabilitas: ${stabilityPct}% • Layar: ${hardwareHz}Hz${gpuLabel}${turboLabel}${audioModeLabel}"
        adviceText?.text = rating.advice

        updatePacingBars(measuredFps, targetFps)
    }

    private fun updatePacingBars(currentFps: Int, targetFps: Int) {
        val barIds = arrayOf(
            R.id.fps_bar_1,
            R.id.fps_bar_2,
            R.id.fps_bar_3,
            R.id.fps_bar_4,
            R.id.fps_bar_5,
            R.id.fps_bar_6
        )

        for (i in barIds.indices) {
            val barView = overlayView?.findViewById<View>(barIds[i]) ?: continue
            val histVal = pacingHistory[(pacingHistoryIndex + i) % pacingHistory.size]
            val color = when {
                isPaused -> 0x44FF9F43.toInt()
                histVal >= targetFps - 2 -> 0xFF00E676.toInt()
                histVal >= targetFps - 6 -> 0xFF00B894.toInt()
                histVal >= 30 -> 0xFFFFAB00.toInt()
                else -> 0xFFFF5252.toInt()
            }
            barView.backgroundTintList = ColorStateList.valueOf(color)
        }
    }

    private fun syncOverlayStatus() {
        if (!ScreenRecordingService.isRecording) {
            stopOverlay()
            return
        }
        isPaused = ScreenRecordingService.isPaused
        val btnPauseResume = overlayView?.findViewById<ImageView>(R.id.btn_overlay_pause_resume)
        val labelPause = overlayView?.findViewById<TextView>(R.id.label_overlay_pause)
        val textStatus = overlayView?.findViewById<TextView>(R.id.overlay_status_text)
        val headerBadge = overlayView?.findViewById<TextView>(R.id.overlay_header_badge)
        val pulseIcon = overlayView?.findViewById<ImageView>(R.id.overlay_pulse_icon)
        val bubblePulse = overlayView?.findViewById<ImageView>(R.id.overlay_bubble_pulse_dot)

        if (isPaused) {
            btnPauseResume?.setImageResource(R.drawable.ic_play_arrow)
            labelPause?.text = "Lanjut"
            textStatus?.text = "⏸️ Rekaman Dijeda"
            headerBadge?.text = "PAUSED"
            headerBadge?.backgroundTintList = ColorStateList.valueOf(0x33FF9F43)
            headerBadge?.setTextColor(0xFFFF9F43.toInt())
            pulseIcon?.alpha = 0.4f
            bubblePulse?.alpha = 0.4f
        } else {
            btnPauseResume?.setImageResource(R.drawable.ic_pause)
            labelPause?.text = "Jeda"
            textStatus?.text = "● Merekam Layar Aktif"
            headerBadge?.text = "REC"
            headerBadge?.backgroundTintList = ColorStateList.valueOf(0x33FF4757)
            headerBadge?.setTextColor(0xFFFF4757.toInt())
            pulseIcon?.alpha = 1.0f
            bubblePulse?.alpha = 1.0f
        }

        val prefs = appPreferences ?: AppPreferences(this).also { appPreferences = it }
        overlayView?.findViewById<TextView>(R.id.overlay_quality_text)?.text = prefs.resolution

        syncMicButtonUi()
        syncFacecamButtonUi()
        syncFacecamWindowState()
        calculateAndDispatchDynamicFps(System.nanoTime())
        updateOverlayTimer()
    }

    private fun syncMicButtonUi() {
        val prefs = appPreferences ?: AppPreferences(this)
        val activeAudioSource = (if (ScreenRecordingService.isRecording) ScreenRecordingService.effectiveAudioMode else null)
            ?: prefs.audioSource
        val isMicActive = if (ScreenRecordingService.isRecording) {
            !ScreenRecordingService.isMicMuted && (activeAudioSource == "MIC" || activeAudioSource == "BOTH")
        } else {
            activeAudioSource == "MIC" || activeAudioSource == "BOTH"
        }
        val btnMic = overlayView?.findViewById<ImageView>(R.id.btn_overlay_mic)
        val labelMic = overlayView?.findViewById<TextView>(R.id.label_overlay_mic)
        val audioStatusText = overlayView?.findViewById<TextView>(R.id.overlay_audio_status_text)

        audioStatusText?.text = resolveAudioStatusLabel(
            audioSource = activeAudioSource,
            isRecording = ScreenRecordingService.isRecording,
            isMicMuted = ScreenRecordingService.isMicMuted
        )

        if (isMicActive) {
            btnMic?.setImageResource(R.drawable.ic_mic)
            btnMic?.imageTintList = ColorStateList.valueOf(0xFFA29BFE.toInt())
            btnMic?.backgroundTintList = ColorStateList.valueOf(0x2E6C5CE7)
            labelMic?.text = "Mic On"
            labelMic?.setTextColor(0xFFA29BFE.toInt())
        } else {
            btnMic?.setImageResource(R.drawable.ic_mic_off)
            btnMic?.imageTintList = ColorStateList.valueOf(0xFFFF6B6B.toInt())
            btnMic?.backgroundTintList = ColorStateList.valueOf(0x33FF4757)
            labelMic?.text = if (activeAudioSource == "INTERNAL" || activeAudioSource == "MUTE") "No Mic" else "Mic Mute"
            labelMic?.setTextColor(0xFFFF6B6B.toInt())
        }
    }

    private fun syncFacecamButtonUi() {
        val prefs = appPreferences ?: AppPreferences(this)
        val isFacecamActive = prefs.facecamMode != "OFF"
        val btnFacecam = overlayView?.findViewById<ImageView>(R.id.btn_overlay_facecam)
        val labelFacecam = overlayView?.findViewById<TextView>(R.id.label_overlay_facecam)

        if (isFacecamActive) {
            btnFacecam?.imageTintList = ColorStateList.valueOf(0xFFA29BFE.toInt())
            btnFacecam?.backgroundTintList = ColorStateList.valueOf(0x2E6C5CE7)
            labelFacecam?.text = if (prefs.facecamMode == "BACK") "Cam Belakang" else "Facecam On"
            labelFacecam?.setTextColor(0xFFA29BFE.toInt())
        } else {
            btnFacecam?.imageTintList = ColorStateList.valueOf(0xFF9E9EA8.toInt())
            btnFacecam?.backgroundTintList = ColorStateList.valueOf(0x24FFFFFF)
            labelFacecam?.text = "Facecam"
            labelFacecam?.setTextColor(0xFFD0D0DC.toInt())
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun setupOverlayWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val (screenWidth, screenHeight) = getScreenDimensions()

        // Posisikan awal di sisi kanan atas layar (tepi non-intrusif)
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSPARENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - dpToPx(120)).coerceAtLeast(dpToPx(8))
            y = (screenHeight * 0.22f).toInt().coerceAtLeast(dpToPx(48))
        }

        val themedContext = ContextThemeWrapper(this, R.style.Theme_RekaminDulu)
        val inflater = LayoutInflater.from(themedContext)
        overlayView = inflater.inflate(R.layout.overlay_window, null)

        val headerDragBar = overlayView?.findViewById<View>(R.id.overlay_header_drag_bar)
        val bubbleContainer = overlayView?.findViewById<View>(R.id.overlay_bubble_container)
        val expandedCard = overlayView?.findViewById<View>(R.id.overlay_container)

        // Default: Mode Kapsul Mini Aktif (Sangat ramah gamer)
        isMinimized = true
        bubbleContainer?.visibility = View.VISIBLE
        expandedCard?.visibility = View.GONE

        // Minimize action pada header panel besar
        overlayView?.findViewById<ImageView>(R.id.btn_overlay_minimize)?.setOnClickListener {
            toggleMinimizeMode(true)
        }

        // Transparency / Stealth action pada header (cycles 100% -> 65% -> 35%)
        overlayView?.findViewById<ImageView>(R.id.btn_overlay_transparency)?.setOnClickListener {
            cycleTransparency()
        }

        // Drag handling terpisah pada header dan bubble agar sentuhan tombol tidak terintersepsi
        setupDragTouchListener(headerDragBar, isBubble = false)
        setupDragTouchListener(bubbleContainer, isBubble = true)

        // Close Overlay Button
        overlayView?.findViewById<ImageView>(R.id.overlay_close_btn)?.setOnClickListener {
            stopOverlay()
        }

        UiHelper.expandTouchTargets(
            overlayView?.findViewById(R.id.overlay_header_controls_row),
            listOf(
                overlayView?.findViewById<View>(R.id.btn_overlay_transparency) to 44,
                overlayView?.findViewById<View>(R.id.btn_overlay_minimize) to 44,
                overlayView?.findViewById<View>(R.id.overlay_close_btn) to 44
            )
        )

        // Action Stop Button
        val onStopClick = View.OnClickListener {
            triggerVibration(60)
            stopRecordingAndOverlay()
        }
        overlayView?.findViewById<View>(R.id.btn_overlay_stop)?.setOnClickListener(onStopClick)
        overlayView?.findViewById<View>(R.id.action_overlay_stop_wrapper)?.setOnClickListener(onStopClick)

        // Action Pause/Resume Button
        val onPauseResumeClick = View.OnClickListener {
            triggerVibration(35)
            resetAutoCollapseTimer()
            if (!ScreenRecordingService.isPaused) {
                val pauseIntent = Intent(this, ScreenRecordingService::class.java).apply {
                    action = ScreenRecordingService.ACTION_PAUSE
                }
                sendServiceCommand(pauseIntent)
                Toast.makeText(this, "⏸️ Rekaman Dijeda", Toast.LENGTH_SHORT).show()
            } else {
                val resumeIntent = Intent(this, ScreenRecordingService::class.java).apply {
                    action = ScreenRecordingService.ACTION_RESUME
                }
                sendServiceCommand(resumeIntent)
                Toast.makeText(this, "▶️ Melanjutkan Rekaman", Toast.LENGTH_SHORT).show()
            }
        }
        overlayView?.findViewById<View>(R.id.btn_overlay_pause_resume)?.setOnClickListener(onPauseResumeClick)
        overlayView?.findViewById<View>(R.id.action_overlay_pause_wrapper)?.setOnClickListener(onPauseResumeClick)

        // Action Instant Screenshot Button -> Real PNG capture via MediaProjection
        val onScreenshotClick = View.OnClickListener {
            triggerVibration(45)
            resetAutoCollapseTimer()
            playScreenshotFlashFeedback()
            val shotIntent = Intent(this, ScreenRecordingService::class.java).apply {
                action = ScreenRecordingService.ACTION_CAPTURE_SCREENSHOT
            }
            sendServiceCommand(shotIntent)
        }
        overlayView?.findViewById<View>(R.id.btn_overlay_screenshot)?.setOnClickListener(onScreenshotClick)
        overlayView?.findViewById<View>(R.id.action_overlay_screenshot_wrapper)?.setOnClickListener(onScreenshotClick)

        // Action Toggle Mic Button -> Real live PCM mic mute/unmute
        val onMicClick = View.OnClickListener {
            triggerVibration(35)
            resetAutoCollapseTimer()
            if (ScreenRecordingService.isRecording) {
                val toggleIntent = Intent(this, ScreenRecordingService::class.java).apply {
                    action = ScreenRecordingService.ACTION_TOGGLE_MIC
                }
                sendServiceCommand(toggleIntent)
            } else {
                val prefs = appPreferences ?: AppPreferences(this).also { appPreferences = it }
                val next = when (prefs.audioSource) {
                    "BOTH" -> "INTERNAL"
                    "INTERNAL" -> "BOTH"
                    "MIC" -> "MUTE"
                    else -> "MIC"
                }
                prefs.audioSource = next
                syncMicButtonUi()
            }
        }
        overlayView?.findViewById<View>(R.id.btn_overlay_mic)?.setOnClickListener(onMicClick)
        overlayView?.findViewById<View>(R.id.action_overlay_mic_wrapper)?.setOnClickListener(onMicClick)

        // Action Tap FPS Box to toggle FPS Stabilizer
        overlayView?.findViewById<View>(R.id.box_fps_evaluation)?.setOnClickListener {
            triggerVibration(30)
            resetAutoCollapseTimer()
            val prefs = appPreferences ?: AppPreferences(this).also { appPreferences = it }
            prefs.fpsStabilizerEnabled = !prefs.fpsStabilizerEnabled
            val msg = if (prefs.fpsStabilizerEnabled) {
                "⚡ Stabilisator FPS: Diaktifkan"
            } else {
                "Stabilisator FPS: Dinonaktifkan"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            calculateAndDispatchDynamicFps(System.nanoTime())
        }

        // Action Toggle Facecam Button -> Real Camera2 Floating Preview Bubble
        val onFacecamClick = View.OnClickListener {
            triggerVibration(35)
            resetAutoCollapseTimer()
            val prefs = appPreferences ?: AppPreferences(this).also { appPreferences = it }
            if (prefs.facecamMode == "OFF") {
                val hasCam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                if (!hasCam) {
                    Toast.makeText(
                        this,
                        "Berikan izin Kamera di layar utama terlebih dahulu untuk memakai Facecam.",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@OnClickListener
                }
                prefs.facecamMode = "FRONT"
                sendServiceCommand(Intent(this, ScreenRecordingService::class.java).apply {
                    action = ScreenRecordingService.ACTION_REFRESH_FOREGROUND_TYPE
                })
                Toast.makeText(this, "📷 Facecam Kamera Depan Diaktifkan", Toast.LENGTH_SHORT).show()
            } else {
                prefs.facecamMode = "OFF"
                sendServiceCommand(Intent(this, ScreenRecordingService::class.java).apply {
                    action = ScreenRecordingService.ACTION_REFRESH_FOREGROUND_TYPE
                })
                Toast.makeText(this, "📷 Facecam Dinonaktifkan", Toast.LENGTH_SHORT).show()
            }
            syncFacecamButtonUi()
            syncFacecamWindowState()
        }
        overlayView?.findViewById<View>(R.id.btn_overlay_facecam)?.setOnClickListener(onFacecamClick)
        overlayView?.findViewById<View>(R.id.action_overlay_facecam_wrapper)?.setOnClickListener(onFacecamClick)

        // Open App Button
        val onOpenAppClick = View.OnClickListener {
            triggerVibration(35)
            resetAutoCollapseTimer()
            val appIntent = Intent(this, HomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(appIntent)
        }
        overlayView?.findViewById<View>(R.id.btn_overlay_open_app)?.setOnClickListener(onOpenAppClick)
        overlayView?.findViewById<View>(R.id.action_overlay_home_wrapper)?.setOnClickListener(onOpenAppClick)

        syncOverlayStatus()

        try {
            windowManager?.addView(overlayView, params)
            calculateAndDispatchDynamicFps(System.nanoTime())
            handler.removeCallbacks(timerRunnable)
            handler.post(timerRunnable)
            Choreographer.getInstance().postFrameCallback(frameCallback)
            dockBubbleToNearestEdge()
            startIdleDimTimer()
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun playScreenshotFlashFeedback() {
        val container = overlayView?.findViewById<View>(R.id.overlay_container) ?: return
        container.animate()
            .alpha(0.2f)
            .setDuration(70)
            .withEndAction {
                if (!isStopping && container.isAttachedToWindow) {
                    container.animate()
                        .alpha(transparencyLevels[transparencyIndex])
                        .setDuration(120)
                        .start()
                }
            }
            .start()
    }

    private fun toggleMinimizeMode(minimize: Boolean) {
        isMinimized = minimize
        val expandedCard = overlayView?.findViewById<View>(R.id.overlay_container)
        val bubbleContainer = overlayView?.findViewById<View>(R.id.overlay_bubble_container)

        if (minimize) {
            handler.removeCallbacks(autoCollapseRunnable)
            expandedCard?.visibility = View.GONE
            bubbleContainer?.visibility = View.VISIBLE
            wakeBubbleFromDim()
            dockBubbleToNearestEdge()
            startIdleDimTimer()
        } else {
            wakeBubbleFromDim()
            bubbleContainer?.visibility = View.GONE
            expandedCard?.visibility = View.VISIBLE
            expandedCard?.alpha = transparencyLevels[transparencyIndex]
            clampOverlayPosition()
            resetAutoCollapseTimer()
        }
        triggerVibration(30)
    }

    private fun dockBubbleToNearestEdge() {
        val currentParams = params ?: return
        val (screenWidth, _) = getScreenDimensions()
        val viewWidth = overlayView?.width?.coerceAtLeast(dpToPx(56)) ?: dpToPx(110)
        val maxX = (screenWidth - viewWidth).coerceAtLeast(0)
        val edgeMargin = dpToPx(8)
        // Snaps magnetic ke tepi kiri (8dp) atau tepi kanan (maxX - 8dp)
        val targetX = if (currentParams.x + viewWidth / 2 < screenWidth / 2) edgeMargin else (maxX - edgeMargin).coerceAtLeast(0)
        animateSnapX(currentParams.x, targetX)
    }

    private fun animateSnapX(fromX: Int, toX: Int) {
        val currentParams = params ?: return
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(fromX, toX).apply {
            duration = 180
            addUpdateListener { va ->
                currentParams.x = va.animatedValue as Int
                try {
                    windowManager?.updateViewLayout(overlayView, currentParams)
                } catch (ignored: Exception) {}
            }
            start()
        }
    }

    private fun clampOverlayPosition() {
        val currentParams = params ?: return
        val (screenWidth, screenHeight) = getScreenDimensions()
        val viewWidth = overlayView?.width?.coerceAtLeast(dpToPx(56)) ?: dpToPx(280)
        val viewHeight = overlayView?.height?.coerceAtLeast(dpToPx(40)) ?: dpToPx(210)
        val maxX = (screenWidth - viewWidth).coerceAtLeast(0)
        val minY = dpToPx(16)
        val maxY = (screenHeight - viewHeight).coerceAtLeast(minY)

        currentParams.x = currentParams.x.coerceIn(0, maxX)
        currentParams.y = currentParams.y.coerceIn(minY, maxY)
        try {
            windowManager?.updateViewLayout(overlayView, currentParams)
        } catch (ignored: Exception) {}
    }

    private fun clampFacecamPosition() {
        val v = facecamView ?: return
        val p = facecamParams ?: return
        val (screenWidth, screenHeight) = getScreenDimensions()
        val w = v.width.coerceAtLeast(dpToPx(96))
        val h = v.height.coerceAtLeast(dpToPx(128))
        val minY = dpToPx(12)
        p.x = p.x.coerceIn(0, (screenWidth - w).coerceAtLeast(0))
        p.y = p.y.coerceIn(minY, (screenHeight - h).coerceAtLeast(minY))
        try {
            windowManager?.updateViewLayout(v, p)
        } catch (ignored: Exception) {}
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        clampOverlayPosition()
        clampFacecamPosition()
        if (isMinimized) {
            dockBubbleToNearestEdge()
        }
        facecamTextureView?.let { tv ->
            if (tv.isAvailable) {
                configureTransform(tv.width, tv.height)
            }
        }
    }

    private fun triggerVibration(durationMs: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v?.vibrate(durationMs)
                }
            }
        } catch (ignored: Exception) {}
    }

    private fun sendServiceCommand(intent: Intent) {
        if (!ScreenRecordingService.isRecording && !ScreenRecordingService.isPaused) return
        try {
            startService(intent)
        } catch (e: IllegalStateException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    startForegroundService(intent)
                } catch (ignored: Exception) {}
            }
        } catch (ignored: Exception) {}
    }

    private fun stopRecordingAndOverlay() {
        val stopIntent = Intent(this, ScreenRecordingService::class.java).apply {
            action = ScreenRecordingService.ACTION_STOP
        }
        sendServiceCommand(stopIntent)
        stopOverlay()
    }

    private fun setupDragTouchListener(view: View?, isBubble: Boolean) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat().coerceAtLeast(8f)

        view?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDrag = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val currentParams = params ?: return false

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        snapAnimator?.cancel()
                        if (isBubble) {
                            wakeBubbleFromDim()
                        } else {
                            resetAutoCollapseTimer()
                        }
                        initialX = currentParams.x
                        initialY = currentParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDrag = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                            isDrag = true
                            if (isBubble) {
                                wakeBubbleFromDim()
                            } else {
                                resetAutoCollapseTimer()
                            }
                            val (screenWidth, screenHeight) = getScreenDimensions()
                            val viewWidth = overlayView?.width?.coerceAtLeast(dpToPx(56)) ?: dpToPx(if (isBubble) 110 else 280)
                            val viewHeight = overlayView?.height?.coerceAtLeast(dpToPx(36)) ?: dpToPx(if (isBubble) 40 else 210)
                            val maxX = (screenWidth - viewWidth).coerceAtLeast(0)
                            val minY = dpToPx(16)
                            val maxY = (screenHeight - viewHeight).coerceAtLeast(minY)

                            currentParams.x = (initialX + dx).coerceIn(0, maxX)
                            currentParams.y = (initialY + dy).coerceIn(minY, maxY)
                            try {
                                windowManager?.updateViewLayout(overlayView, currentParams)
                            } catch (ignored: Exception) {}
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isDrag) {
                            if (isBubble) {
                                dockBubbleToNearestEdge()
                                startIdleDimTimer()
                            }
                            return true
                        } else {
                            v.performClick()
                            if (isBubble) {
                                toggleMinimizeMode(false)
                                return true
                            } else {
                                return false
                            }
                        }
                    }
                }
                return false
            }
        })
    }

    private var isStopping = false

    fun stopOverlay() {
        if (isStopping) return
        isStopping = true

        handler.removeCallbacksAndMessages(null)
        dummyAnimator?.cancel()
        dummyAnimator = null
        snapAnimator?.cancel()
        snapAnimator = null

        closeFacecamWindowAndCamera()

        try {
            unregisterReceiver(statusReceiver)
        } catch (e: Exception) {
            // Ignore
        }
        try {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        } catch (e: Exception) {
            // Ignore
        }
        overlayView?.let { view ->
            try {
                if (view.isAttachedToWindow) {
                    windowManager?.removeViewImmediate(view)
                }
            } catch (e: Exception) {
                try {
                    if (view.isAttachedToWindow) {
                        windowManager?.removeView(view)
                    }
                } catch (ignored: Exception) {}
            }
        }
        overlayView = null
        stopSelf()
    }

    // ==========================================
    // FACECAM CAMERA2 DRAGGABLE BUBBLE ENGINE
    // ==========================================

    private fun syncFacecamWindowState() {
        val prefs = appPreferences ?: AppPreferences(this).also { appPreferences = it }
        val mode = prefs.facecamMode
        val hasCameraPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (mode == "OFF" || !hasCameraPermission) {
            if (mode != "OFF" && !hasCameraPermission) {
                prefs.facecamMode = "OFF"
                syncFacecamButtonUi()
            }
            closeFacecamWindowAndCamera()
            return
        }

        if (facecamView == null) {
            createFacecamWindow()
        }
        facecamView?.findViewById<TextView>(R.id.facecam_lens_badge)?.text =
            if (mode == "BACK") "BACK" else "FRONT"

        val tv = facecamTextureView
        if (tv != null && tv.isAvailable) {
            openCameraForMode(mode)
        }
    }

    private fun createFacecamWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            return
        }
        val wm = windowManager ?: (getSystemService(WINDOW_SERVICE) as WindowManager).also { windowManager = it }
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val fcParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(16)
            y = dpToPx(64)
        }
        facecamParams = fcParams

        val themedContext = ContextThemeWrapper(this, R.style.Theme_RekaminDulu)
        val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_facecam, null)
        facecamView = view

        val textureView = view.findViewById<TextureView>(R.id.facecam_texture_view)
        facecamTextureView = textureView
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                configureTransform(width, height)
                val mode = (appPreferences ?: AppPreferences(this@FloatingOverlayService)).facecamMode
                if (mode != "OFF") {
                    openCameraForMode(mode)
                }
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                configureTransform(width, height)
            }
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                closeCameraOnly()
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        view.findViewById<ImageView>(R.id.btn_facecam_close)?.setOnClickListener {
            val prefs = appPreferences ?: AppPreferences(this)
            prefs.facecamMode = "OFF"
            sendServiceCommand(Intent(this, ScreenRecordingService::class.java).apply {
                action = ScreenRecordingService.ACTION_REFRESH_FOREGROUND_TYPE
            })
            syncFacecamButtonUi()
            closeFacecamWindowAndCamera()
        }

        view.findViewById<ImageView>(R.id.btn_facecam_flip)?.setOnClickListener {
            val prefs = appPreferences ?: AppPreferences(this)
            val newMode = if (prefs.facecamMode == "BACK") "FRONT" else "BACK"
            prefs.facecamMode = newMode
            view.findViewById<TextView>(R.id.facecam_lens_badge)?.text =
                if (newMode == "BACK") "BACK" else "FRONT"
            syncFacecamButtonUi()
            openCameraForMode(newMode)
        }

        UiHelper.expandTouchTargets(
            view.findViewById(R.id.facecam_inner_frame),
            listOf(
                view.findViewById<View>(R.id.btn_facecam_close) to 44,
                view.findViewById<View>(R.id.btn_facecam_flip) to 44
            )
        )

        setupFacecamDragListener(view)

        try {
            wm.addView(view, fcParams)
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menampilkan jendela Facecam: ${e.message}")
            facecamView = null
            facecamTextureView = null
        }
    }

    private fun setupFacecamDragListener(view: View) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat().coerceAtLeast(8f)
        view.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDrag = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val p = facecamParams ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = p.x
                        initialY = p.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDrag = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                            isDrag = true
                            val (screenWidth, screenHeight) = getScreenDimensions()
                            val w = v.width.coerceAtLeast(dpToPx(96))
                            val h = v.height.coerceAtLeast(dpToPx(128))
                            val minY = dpToPx(12)
                            p.x = (initialX + dx).coerceIn(0, (screenWidth - w).coerceAtLeast(0))
                            p.y = (initialY + dy).coerceIn(minY, (screenHeight - h).coerceAtLeast(minY))
                            try {
                                windowManager?.updateViewLayout(v, p)
                            } catch (ignored: Exception) {}
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDrag) {
                            v.performClick()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun openCameraForMode(mode: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        closeCameraOnly()
        val tv = facecamTextureView ?: return
        if (!tv.isAvailable) return

        if (cameraThread == null) {
            val thread = HandlerThread("RekaminFacecamThread").apply { start() }
            cameraThread = thread
            cameraHandler = Handler(thread.looper)
        }

        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val desiredFacing = if (mode == "BACK") {
                CameraCharacteristics.LENS_FACING_BACK
            } else {
                CameraCharacteristics.LENS_FACING_FRONT
            }
            val selectedId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == desiredFacing
            } ?: cameraManager.cameraIdList.firstOrNull() ?: run {
                resetFacecamOnError("Kamera perangkat tidak tersedia untuk Facecam")
                return
            }

            val chars = cameraManager.getCameraCharacteristics(selectedId)
            cameraSensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            cameraLensFacing = chars.get(CameraCharacteristics.LENS_FACING) ?: desiredFacing
            val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = streamMap?.getOutputSizes(SurfaceTexture::class.java)
            previewSize = sizes
                ?.filter { it.width <= 1280 && it.height <= 960 }
                ?.maxByOrNull { it.width * it.height }
                ?: sizes?.firstOrNull()
                ?: Size(480, 640)

            handler.post { configureTransform(tv.width, tv.height) }

            cameraManager.openCamera(selectedId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (isStopping || (appPreferences?.facecamMode == "OFF")) {
                        try { camera.close() } catch (ignored: Exception) {}
                        return
                    }
                    cameraDevice = camera
                    startFacecamPreviewSession(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    try { camera.close() } catch (ignored: Exception) {}
                    if (cameraDevice === camera) cameraDevice = null
                    resetFacecamOnError("Kamera Facecam terputus atau digunakan aplikasi lain")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.w(TAG, "Camera2 onError=$error")
                    try { camera.close() } catch (ignored: Exception) {}
                    if (cameraDevice === camera) cameraDevice = null
                    resetFacecamOnError("Gagal membuka kamera Facecam (kode $error)")
                }
            }, cameraHandler)
        } catch (se: SecurityException) {
            Log.w(TAG, "Camera2 permission rejected: ${se.message}")
            resetFacecamOnError("Izin kamera diperlukan atau dibatasi oleh sistem untuk Facecam")
        } catch (e: Exception) {
            Log.w(TAG, "Camera2 open failed: ${e.message}")
            resetFacecamOnError("Tidak dapat membuka kamera Facecam")
        }
    }

    private fun resetFacecamOnError(toastMessage: String?) {
        handler.post {
            if (isStopping) return@post
            val prefs = appPreferences ?: AppPreferences(this)
            prefs.facecamMode = "OFF"
            syncFacecamButtonUi()
            closeFacecamWindowAndCamera()
            if (!toastMessage.isNullOrEmpty()) {
                Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startFacecamPreviewSession(camera: CameraDevice) {
        val tv = facecamTextureView ?: return
        val st = tv.surfaceTexture ?: return
        try {
            st.setDefaultBufferSize(previewSize.width, previewSize.height)
            val previewSurface = Surface(st)
            val reqBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                listOf(previewSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null || isStopping) {
                            try { session.close() } catch (ignored: Exception) {}
                            return
                        }
                        cameraSession = session
                        try {
                            session.setRepeatingRequest(reqBuilder.build(), null, cameraHandler)
                        } catch (e: Exception) {
                            Log.w(TAG, "setRepeatingRequest failed: ${e.message}")
                            resetFacecamOnError("Preview Facecam terhenti")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.w(TAG, "Facecam CameraCaptureSession configuration failed")
                        resetFacecamOnError("Konfigurasi kamera Facecam gagal")
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            Log.w(TAG, "startFacecamPreviewSession failed: ${e.message}")
            resetFacecamOnError("Gagal memulai preview Facecam")
        }
    }

    private fun getCurrentDisplayRotation(): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display?.rotation ?: Surface.ROTATION_0
            } else {
                @Suppress("DEPRECATION")
                windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
            }
        } catch (e: Exception) {
            Surface.ROTATION_0
        }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val tv = facecamTextureView ?: return
        if (viewWidth <= 0 || viewHeight <= 0) return
        val rotation = getCurrentDisplayRotation()
        val rotationDegrees = computeFacecamRotationDegrees(rotation)
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val isSensorSwapped = (cameraSensorOrientation == 90 || cameraSensorOrientation == 270)
        val isDisplayLandscape = (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270)
        val bufferWidth = if (isSensorSwapped xor isDisplayLandscape) {
            previewSize.height.toFloat().coerceAtLeast(1f)
        } else {
            previewSize.width.toFloat().coerceAtLeast(1f)
        }
        val bufferHeight = if (isSensorSwapped xor isDisplayLandscape) {
            previewSize.width.toFloat().coerceAtLeast(1f)
        } else {
            previewSize.height.toFloat().coerceAtLeast(1f)
        }

        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        // Center-crop scale to prevent stretched/squished preview on any aspect ratio
        val scaleX = viewWidth.toFloat() / bufferWidth
        val scaleY = viewHeight.toFloat() / bufferHeight
        val cropScale = maxOf(scaleX, scaleY)
        val scaledWidth = bufferWidth * cropScale
        val scaledHeight = bufferHeight * cropScale
        matrix.setScale(scaledWidth / viewWidth.toFloat(), scaledHeight / viewHeight.toFloat(), centerX, centerY)

        if (isDisplayLandscape) {
            matrix.postRotate(rotationDegrees, centerX, centerY)
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, centerX, centerY)
        }
        tv.setTransform(matrix)
    }

    private fun closeCameraOnly() {
        try { cameraSession?.close() } catch (ignored: Exception) {}
        cameraSession = null
        try { cameraDevice?.close() } catch (ignored: Exception) {}
        cameraDevice = null
    }

    private fun closeFacecamWindowAndCamera() {
        closeCameraOnly()
        try { cameraThread?.quitSafely() } catch (ignored: Exception) {}
        cameraThread = null
        cameraHandler = null

        facecamView?.let { view ->
            try {
                if (view.isAttachedToWindow) {
                    windowManager?.removeViewImmediate(view)
                }
            } catch (e: Exception) {
                try {
                    if (view.isAttachedToWindow) {
                        windowManager?.removeView(view)
                    }
                } catch (ignored: Exception) {}
            }
        }
        facecamView = null
        facecamTextureView = null
        facecamParams = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopOverlay()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
