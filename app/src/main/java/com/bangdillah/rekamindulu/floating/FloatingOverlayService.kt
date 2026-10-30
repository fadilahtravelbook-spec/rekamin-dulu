package com.bangdillah.rekamindulu.floating

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import com.bangdillah.rekamindulu.HomeActivity
import com.bangdillah.rekamindulu.R
import com.bangdillah.rekamindulu.service.ScreenRecordingService
import android.view.Choreographer
import com.bangdillah.rekamindulu.utils.AppPreferences
import java.util.Locale

class FloatingOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var isPaused = false
    private var frameCount = 0
    private var lastFpsTimestamp = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameCount++
            if (lastFpsTimestamp == 0L) {
                lastFpsTimestamp = frameTimeNanos
            } else {
                val diffNanos = frameTimeNanos - lastFpsTimestamp
                if (diffNanos >= 1_000_000_000L) {
                    val measuredFps = ((frameCount * 1_000_000_000L) / diffNanos).toInt()
                    overlayView?.findViewById<TextView>(R.id.overlay_fps_text)?.text = "$measuredFps FPS"
                    frameCount = 0
                    lastFpsTimestamp = frameTimeNanos
                }
            }
            if (ScreenRecordingService.isRecording) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (ScreenRecordingService.isRecording) {
                val sec = ScreenRecordingService.elapsedSeconds
                val min = sec / 60
                val timerText = String.format(Locale.getDefault(), "%02d:%02d", min, sec % 60)
                overlayView?.findViewById<TextView>(R.id.overlay_timer_text)?.text = timerText
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
        val filter = IntentFilter().apply {
            addAction(ScreenRecordingService.ACTION_RECORDING_STATUS_CHANGED)
            addAction(ScreenRecordingService.ACTION_RECORDING_STOPPED)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            statusReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        setupOverlayWindow()
    }

    private fun syncOverlayStatus() {
        if (!ScreenRecordingService.isRecording) {
            stopOverlay()
            return
        }
        isPaused = ScreenRecordingService.isPaused
        val btnPauseResume = overlayView?.findViewById<ImageView>(R.id.btn_overlay_pause_resume)
        val textStatus = overlayView?.findViewById<TextView>(R.id.overlay_status_text)
        if (isPaused) {
            btnPauseResume?.setImageResource(R.drawable.ic_play_arrow)
            textStatus?.text = "⏸️ Dijeda"
        } else {
            btnPauseResume?.setImageResource(R.drawable.ic_pause)
            textStatus?.text = "● Merekam Layar"
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
            x = 80
            y = 250
        }

        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_window, null)

        val container = overlayView?.findViewById<View>(R.id.overlay_container)
        container?.visibility = View.VISIBLE

        setupDragTouchListener(container ?: overlayView)

        // Close/Stop Button
        overlayView?.findViewById<ImageView>(R.id.overlay_close_btn)?.setOnClickListener {
            stopRecordingAndOverlay()
        }

        // Action Stop Button
        val btnStop = overlayView?.findViewById<ImageView>(R.id.btn_overlay_stop)
        btnStop?.setImageResource(R.drawable.ic_close)
        btnStop?.setOnClickListener {
            stopRecordingAndOverlay()
        }

        // Action Pause/Resume Button
        val btnPauseResume = overlayView?.findViewById<ImageView>(R.id.btn_overlay_pause_resume)
        btnPauseResume?.setOnClickListener {
            if (!ScreenRecordingService.isPaused) {
                // Pause
                val pauseIntent = Intent(this, ScreenRecordingService::class.java).apply {
                    action = ScreenRecordingService.ACTION_PAUSE
                }
                sendServiceCommand(pauseIntent)
            } else {
                // Resume
                val resumeIntent = Intent(this, ScreenRecordingService::class.java).apply {
                    action = ScreenRecordingService.ACTION_RESUME
                }
                sendServiceCommand(resumeIntent)
            }
        }

        syncOverlayStatus()

        // Open App Button
        overlayView?.findViewById<ImageView>(R.id.btn_overlay_open_app)?.setOnClickListener {
            val appIntent = Intent(this, HomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(appIntent)
        }

        try {
            windowManager?.addView(overlayView, params)
            handler.post(timerRunnable)
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
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

    private fun setupDragTouchListener(view: View?) {
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
                        if (Math.abs(dx) > 6 || Math.abs(dy) > 6) {
                            isDrag = true
                            currentParams.x = initialX + dx
                            currentParams.y = initialY + dy
                            try {
                                windowManager?.updateViewLayout(overlayView, currentParams)
                            } catch (ignored: Exception) {}
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDrag) {
                            v.performClick()
                        }
                        return isDrag
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
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                // Ignore
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
