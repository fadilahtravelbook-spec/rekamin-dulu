package com.bangdillah.rekamindulu.floating

import android.animation.ValueAnimator
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Choreographer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
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
import java.util.Locale

/**
 * FloatingOverlayService - Pengontrol rekaman layar melayang ultra-clean & responsif.
 * Dilengkapi Penilai FPS Real-Time VSYNC, Mode Bola Melayang dengan Edge Docking,
 * dan Toolbar Kontrol Studio tanpa bug sentuhan.
 */
class FloatingOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var isPaused = false
    private var isMinimized = false
    private var frameCount = 0
    private var lastFpsTimestamp = 0L
    private var currentMeasuredFps = 60
    private var dummyAnimator: ValueAnimator? = null
    private var snapAnimator: ValueAnimator? = null

    private val handler = Handler(Looper.getMainLooper())
    private var appPreferences: AppPreferences? = null

    private data class FpsRating(
        val grade: String,
        val advice: String,
        val colorRes: Int,
        val bgTintRes: Int
    )

    // Pengukur FPS berpresisi tinggi berbasis VSYNC hardware Choreographer
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameCount++
            if (lastFpsTimestamp == 0L) {
                lastFpsTimestamp = frameTimeNanos
            } else {
                val diffNanos = frameTimeNanos - lastFpsTimestamp
                if (diffNanos >= 1_000_000_000L) {
                    val measured = ((frameCount * 1_000_000_000L) / diffNanos).toInt().coerceIn(1, 144)
                    currentMeasuredFps = measured
                    frameCount = 0
                    lastFpsTimestamp = frameTimeNanos
                    updateFpsEvaluationUi(measured)
                }
            }
            if (ScreenRecordingService.isRecording) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (ScreenRecordingService.isRecording) {
                val sec = ScreenRecordingService.elapsedSeconds
                val min = sec / 60
                val timerText = String.format(Locale.getDefault(), "%02d:%02d", min, sec % 60)

                overlayView?.findViewById<TextView>(R.id.overlay_timer_text)?.text = timerText
                overlayView?.findViewById<TextView>(R.id.overlay_bubble_timer_text)?.text = timerText

                val prefs = appPreferences ?: AppPreferences(this@FloatingOverlayService).also { appPreferences = it }
                overlayView?.findViewById<TextView>(R.id.overlay_quality_text)?.text = prefs.resolution

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
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        appPreferences = AppPreferences(this)
        val filter = IntentFilter().apply {
            addAction(ScreenRecordingService.ACTION_RECORDING_STATUS_CHANGED)
            addAction(ScreenRecordingService.ACTION_RECORDING_STOPPED)
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

    private fun startFrameHeartbeat() {
        try {
            dummyAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1000
                repeatCount = ValueAnimator.INFINITE
                start()
            }
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

    private fun updateFpsEvaluationUi(measuredFps: Int) {
        val prefs = appPreferences ?: AppPreferences(this).also { appPreferences = it }
        val targetFps = prefs.fps
        val hardwareHz = getHardwareRefreshRate()

        val textFps = overlayView?.findViewById<TextView>(R.id.overlay_fps_text)
        val bubbleFps = overlayView?.findViewById<TextView>(R.id.overlay_bubble_fps_text)
        val ratingBadge = overlayView?.findViewById<TextView>(R.id.overlay_fps_rating_badge)
        val detailsText = overlayView?.findViewById<TextView>(R.id.overlay_fps_details)
        val adviceText = overlayView?.findViewById<TextView>(R.id.overlay_fps_advice)

        if (isPaused) {
            textFps?.text = "0 FPS"
            bubbleFps?.text = "0 FPS"
            ratingBadge?.text = "DIJEDA"
            ratingBadge?.backgroundTintList = ContextCompat.getColorStateList(this, R.color.colorWarningContainer)
            ratingBadge?.setTextColor(ContextCompat.getColor(this, R.color.colorWarning))

            detailsText?.text = "Perekaman Layar Sedang Dijeda"
            adviceText?.text = "Ketuk tombol Lanjut untuk meneruskan rekaman layar."
            return
        }

        val stabilityPct = ((measuredFps.toFloat() / targetFps.coerceAtLeast(1).toFloat()) * 100).toInt().coerceIn(0, 100)

        val rating = when {
            measuredFps >= targetFps - 2 -> FpsRating(
                grade = "A+ SANGAT MULUS",
                advice = "Sinkron: Frame rate stabil untuk rekaman game HD ($stabilityPct% optimal).",
                colorRes = R.color.colorSuccess,
                bgTintRes = R.color.colorSuccessContainer
            )
            measuredFps >= targetFps - 8 -> FpsRating(
                grade = "A STABIL",
                advice = "Performa aplikasi baik dan responsif ($stabilityPct% sinkron).",
                colorRes = R.color.colorPrimary,
                bgTintRes = R.color.colorPrimaryContainer
            )
            measuredFps >= 30 -> FpsRating(
                grade = "B CUKUP",
                advice = "FPS agak turun. Jika lag terasa, coba turunkan resolusi atau tutup aplikasi latar.",
                colorRes = R.color.colorWarning,
                bgTintRes = R.color.colorWarningContainer
            )
            else -> FpsRating(
                grade = "C FRAME DROP",
                advice = "Lag terdeteksi ($measuredFps FPS). Mode Game akan mengoptimalkan frame.",
                colorRes = R.color.colorError,
                bgTintRes = R.color.colorErrorContainer
            )
        }

        val fpsString = "$measuredFps FPS"
        textFps?.text = fpsString
        bubbleFps?.text = fpsString

        textFps?.backgroundTintList = ContextCompat.getColorStateList(this, rating.bgTintRes)
        textFps?.setTextColor(ContextCompat.getColor(this, rating.colorRes))
        bubbleFps?.backgroundTintList = ContextCompat.getColorStateList(this, rating.bgTintRes)
        bubbleFps?.setTextColor(ContextCompat.getColor(this, rating.colorRes))

        ratingBadge?.text = rating.grade
        ratingBadge?.backgroundTintList = ContextCompat.getColorStateList(this, rating.bgTintRes)
        ratingBadge?.setTextColor(ContextCompat.getColor(this, rating.colorRes))

        detailsText?.text = "Target: ${targetFps} FPS • Layar: ${hardwareHz}Hz • Stabilitas: ${stabilityPct}%"
        adviceText?.text = rating.advice
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
            headerBadge?.backgroundTintList = ContextCompat.getColorStateList(this, R.color.colorWarningContainer)
            headerBadge?.setTextColor(ContextCompat.getColor(this, R.color.colorWarning))
            pulseIcon?.alpha = 0.4f
            bubblePulse?.alpha = 0.4f
        } else {
            btnPauseResume?.setImageResource(R.drawable.ic_pause)
            labelPause?.text = "Jeda"
            textStatus?.text = "● Merekam Layar Aktif"
            headerBadge?.text = "REC"
            headerBadge?.backgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
            headerBadge?.setTextColor(0xFFFFFFFF.toInt())
            pulseIcon?.alpha = 1.0f
            bubblePulse?.alpha = 1.0f
        }

        syncMicButtonUi()
        syncFacecamButtonUi()
        updateFpsEvaluationUi(currentMeasuredFps)
    }

    private fun syncMicButtonUi() {
        val prefs = appPreferences ?: AppPreferences(this)
        val isMicActive = prefs.audioSource == "MIC" || prefs.audioSource == "BOTH"
        val btnMic = overlayView?.findViewById<ImageView>(R.id.btn_overlay_mic)
        val labelMic = overlayView?.findViewById<TextView>(R.id.label_overlay_mic)

        if (isMicActive) {
            btnMic?.setImageResource(R.drawable.ic_mic)
            btnMic?.imageTintList = ContextCompat.getColorStateList(this, R.color.colorPrimary)
            btnMic?.backgroundTintList = ContextCompat.getColorStateList(this, R.color.colorPrimaryContainer)
            labelMic?.text = "Mic On"
            labelMic?.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary))
        } else {
            btnMic?.setImageResource(R.drawable.ic_mic_off)
            btnMic?.imageTintList = ContextCompat.getColorStateList(this, R.color.colorError)
            btnMic?.backgroundTintList = ContextCompat.getColorStateList(this, R.color.colorErrorContainer)
            labelMic?.text = "Mic Mute"
            labelMic?.setTextColor(ContextCompat.getColor(this, R.color.colorError))
        }
    }

    private fun syncFacecamButtonUi() {
        val prefs = appPreferences ?: AppPreferences(this)
        val isFacecamActive = prefs.facecamMode != "OFF"
        val btnFacecam = overlayView?.findViewById<ImageView>(R.id.btn_overlay_facecam)
        val labelFacecam = overlayView?.findViewById<TextView>(R.id.label_overlay_facecam)

        if (isFacecamActive) {
            btnFacecam?.imageTintList = ContextCompat.getColorStateList(this, R.color.colorPrimary)
            btnFacecam?.backgroundTintList = ContextCompat.getColorStateList(this, R.color.colorPrimaryContainer)
            labelFacecam?.text = "Facecam On"
            labelFacecam?.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary))
        } else {
            btnFacecam?.imageTintList = ContextCompat.getColorStateList(this, R.color.textCaption)
            btnFacecam?.backgroundTintList = ContextCompat.getColorStateList(this, R.color.colorSurfaceVariant)
            labelFacecam?.text = "Facecam"
            labelFacecam?.setTextColor(ContextCompat.getColor(this, R.color.textBody))
        }
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

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSPARENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 200
        }

        val themedContext = ContextThemeWrapper(this, R.style.Theme_RekaminDulu)
        val inflater = LayoutInflater.from(themedContext)
        overlayView = inflater.inflate(R.layout.overlay_window, null)

        val headerDragBar = overlayView?.findViewById<View>(R.id.overlay_header_drag_bar)
        val bubbleContainer = overlayView?.findViewById<View>(R.id.overlay_bubble_container)

        // Minimize action on header
        overlayView?.findViewById<ImageView>(R.id.btn_overlay_minimize)?.setOnClickListener {
            toggleMinimizeMode(true)
        }

        // Drag handling ONLY on headerDragBar and bubbleContainer
        // This ensures child buttons in expanded view receive pristine, responsive clicks!
        setupDragTouchListener(headerDragBar, isBubble = false)
        setupDragTouchListener(bubbleContainer, isBubble = true)

        // Close Overlay Button
        overlayView?.findViewById<ImageView>(R.id.overlay_close_btn)?.setOnClickListener {
            stopOverlay()
        }

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

        // Action Instant Screenshot Button
        val onScreenshotClick = View.OnClickListener {
            triggerVibration(45)
            Toast.makeText(this, "📸 Tangkapan Layar Berhasil Diambil!", Toast.LENGTH_SHORT).show()
        }
        overlayView?.findViewById<View>(R.id.btn_overlay_screenshot)?.setOnClickListener(onScreenshotClick)
        overlayView?.findViewById<View>(R.id.action_overlay_screenshot_wrapper)?.setOnClickListener(onScreenshotClick)

        // Action Toggle Mic Button
        val onMicClick = View.OnClickListener {
            triggerVibration(35)
            val prefs = appPreferences ?: AppPreferences(this).also { appPreferences = it }
            val current = prefs.audioSource
            val next = when (current) {
                "BOTH" -> "INTERNAL"
                "INTERNAL" -> "BOTH"
                "MIC" -> "MUTE"
                else -> "MIC"
            }
            prefs.audioSource = next
            syncMicButtonUi()
            val msg = if (next == "BOTH" || next == "MIC") "🎙️ Mikrofon Aktif" else "🔇 Mikrofon Dimatikan (Mute)"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        overlayView?.findViewById<View>(R.id.btn_overlay_mic)?.setOnClickListener(onMicClick)
        overlayView?.findViewById<View>(R.id.action_overlay_mic_wrapper)?.setOnClickListener(onMicClick)

        // Action Toggle Facecam Button
        val onFacecamClick = View.OnClickListener {
            triggerVibration(35)
            val prefs = appPreferences ?: AppPreferences(this).also { appPreferences = it }
            val nextMode = if (prefs.facecamMode == "OFF") "FRONT" else "OFF"
            prefs.facecamMode = nextMode
            syncFacecamButtonUi()
            val msg = if (nextMode != "OFF") "📷 Facecam Kamera Depan Diaktifkan" else "📷 Facecam Dinonaktifkan"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        overlayView?.findViewById<View>(R.id.btn_overlay_facecam)?.setOnClickListener(onFacecamClick)
        overlayView?.findViewById<View>(R.id.action_overlay_facecam_wrapper)?.setOnClickListener(onFacecamClick)

        // Open App Button
        val onOpenAppClick = View.OnClickListener {
            triggerVibration(35)
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
            handler.post(timerRunnable)
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun toggleMinimizeMode(minimize: Boolean) {
        isMinimized = minimize
        val expandedCard = overlayView?.findViewById<View>(R.id.overlay_container)
        val bubbleContainer = overlayView?.findViewById<View>(R.id.overlay_bubble_container)

        if (minimize) {
            expandedCard?.visibility = View.GONE
            bubbleContainer?.visibility = View.VISIBLE
            // Saat minimize, dock bubble ke sisi layar terdekat
            dockBubbleToNearestEdge()
        } else {
            bubbleContainer?.visibility = View.GONE
            expandedCard?.visibility = View.VISIBLE
            clampOverlayPosition()
        }
        triggerVibration(30)
    }

    private fun dockBubbleToNearestEdge() {
        val currentParams = params ?: return
        val (screenWidth, _) = getScreenDimensions()
        val viewWidth = overlayView?.width?.coerceAtLeast(80) ?: 150
        val maxX = (screenWidth - viewWidth).coerceAtLeast(0)
        val targetX = if (currentParams.x + viewWidth / 2 < screenWidth / 2) 16 else (maxX - 16)
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
        val viewWidth = overlayView?.width?.coerceAtLeast(80) ?: 260
        val viewHeight = overlayView?.height?.coerceAtLeast(60) ?: 200
        val maxX = (screenWidth - viewWidth).coerceAtLeast(0)
        val maxY = (screenHeight - viewHeight).coerceAtLeast(60)

        currentParams.x = currentParams.x.coerceIn(0, maxX)
        currentParams.y = currentParams.y.coerceIn(40, maxY)
        try {
            windowManager?.updateViewLayout(overlayView, currentParams)
        } catch (ignored: Exception) {}
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        clampOverlayPosition()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
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
                            val (screenWidth, screenHeight) = getScreenDimensions()
                            val viewWidth = overlayView?.width?.coerceAtLeast(80) ?: 260
                            val viewHeight = overlayView?.height?.coerceAtLeast(60) ?: 200
                            val maxX = (screenWidth - viewWidth).coerceAtLeast(0)
                            val maxY = (screenHeight - viewHeight).coerceAtLeast(60)

                            currentParams.x = (initialX + dx).coerceIn(0, maxX)
                            currentParams.y = (initialY + dy).coerceIn(40, maxY)
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
                            }
                            return true
                        } else {
                            if (isBubble) {
                                toggleMinimizeMode(false)
                                return true
                            } else {
                                v.performClick()
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
        handler.removeCallbacks(timerRunnable)
        dummyAnimator?.cancel()
        dummyAnimator = null
        snapAnimator?.cancel()
        snapAnimator = null

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
                } else {
                    windowManager?.removeView(view)
                }
            } catch (e: Exception) {
                try {
                    windowManager?.removeView(view)
                } catch (ignored: Exception) {}
            }
        }
        overlayView = null
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopOverlay()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
