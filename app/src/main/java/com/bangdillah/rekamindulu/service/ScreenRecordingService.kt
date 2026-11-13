package com.bangdillah.rekamindulu.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bangdillah.rekamindulu.HomeActivity
import com.bangdillah.rekamindulu.MainActivity
import com.bangdillah.rekamindulu.R
import com.bangdillah.rekamindulu.floating.FloatingOverlayService
import com.bangdillah.rekamindulu.notification.NotificationHelper
import com.bangdillah.rekamindulu.utils.AppPreferences
import com.bangdillah.rekamindulu.utils.StorageHelper
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ScreenRecordingService - Foreground service pengelola MediaProjection, VirtualDisplay,
 * MediaCodecScreenRecorder, notifikasi kontrol rekaman, dan screenshot instan.
 */
open class ScreenRecordingService : Service() {

    companion object {
        const val TAG = "ScreenRecordingService"

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
        const val ACTION_TOGGLE_MIC = "ACTION_TOGGLE_MIC"
        const val ACTION_SET_MIC_MUTED = "ACTION_SET_MIC_MUTED"
        const val ACTION_CAPTURE_SCREENSHOT = "ACTION_CAPTURE_SCREENSHOT"
        const val ACTION_REFRESH_FOREGROUND_TYPE = "ACTION_REFRESH_FOREGROUND_TYPE"

        const val ACTION_RECORDING_STOPPED = "ACTION_RECORDING_STOPPED"
        const val ACTION_RECORDING_STATUS_CHANGED = "ACTION_RECORDING_STATUS_CHANGED"
        const val ACTION_RECORDING_ERROR = "ACTION_RECORDING_ERROR"
        const val ACTION_SCREENSHOT_RESULT = "ACTION_SCREENSHOT_RESULT"

        const val EXTRA_FILE_PATH = "EXTRA_FILE_PATH"
        const val EXTRA_ERROR_MESSAGE = "EXTRA_ERROR_MESSAGE"
        const val EXTRA_MIC_MUTED = "EXTRA_MIC_MUTED"
        const val EXTRA_SCREENSHOT_SUCCESS = "EXTRA_SCREENSHOT_SUCCESS"
        const val EXTRA_SCREENSHOT_URI = "EXTRA_SCREENSHOT_URI"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"

        @Volatile
        var isRecording: Boolean = false
            internal set

        @Volatile
        var isPaused: Boolean = false
            internal set

        @Volatile
        var isMicMuted: Boolean = false
            internal set

        @Volatile
        var startTimeMillis: Long = 0L
            internal set

        @Volatile
        var lastRecordedFilePath: String? = null
            internal set

        @Volatile
        var currentActiveFilePath: String? = null
            internal set

        @Volatile
        private var pausedAtMillis: Long = 0L

        @Volatile
        private var accumulatedPausedMillis: Long = 0L

        val elapsedSeconds: Int
            get() {
                if (!isRecording || startTimeMillis <= 0L) return 0
                val referenceTime = if (isPaused && pausedAtMillis > 0L) {
                    pausedAtMillis
                } else {
                    System.currentTimeMillis()
                }
                val activeMs = (referenceTime - startTimeMillis - accumulatedPausedMillis).coerceAtLeast(0L)
                return (activeMs / 1000L).toInt()
            }

        /**
         * Menghitung kombinasi foregroundServiceType yang aman sesuai versi Android & izin runtime
         * agar terhindar dari SecurityException di Android 14+.
         */
        fun resolveForegroundServiceType(
            hasMicPermission: Boolean,
            hasCameraPermission: Boolean,
            wantsAudio: Boolean,
            wantsFacecam: Boolean,
            sdkInt: Int = Build.VERSION.SDK_INT
        ): Int {
            if (sdkInt < Build.VERSION_CODES.Q) return 0
            var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (sdkInt >= Build.VERSION_CODES.R) {
                if (hasMicPermission && wantsAudio) {
                    fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                if (hasCameraPermission && wantsFacecam) {
                    fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
            }
            return fgsType
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var codecRecorder: MediaCodecScreenRecorder? = null
    private var outputFile: File? = null
    private var notificationHelper: NotificationHelper? = null
    private val isStopping = AtomicBoolean(false)
    private var screenOffReceiverRegistered = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system/user")
            if (isRecording && !isStopping.get()) {
                stopRecordingInternal()
            }
        }
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                val prefs = AppPreferences(this@ScreenRecordingService)
                if (prefs.stopOnScreenOff && isRecording && !isStopping.get()) {
                    Log.i(TAG, "Screen turned off; stopping recording per preference")
                    stopRecordingInternal()
                }
            }
        }
    }

    private val notificationUpdateRunnable = object : Runnable {
        override fun run() {
            if (isRecording && !isStopping.get()) {
                updateForegroundNotification()
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> stopRecordingInternal()
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            ACTION_TOGGLE_MIC -> handleSetMicMuted(!isMicMuted)
            ACTION_SET_MIC_MUTED -> {
                val muted = intent.getBooleanExtra(EXTRA_MIC_MUTED, !isMicMuted)
                handleSetMicMuted(muted)
            }
            ACTION_CAPTURE_SCREENSHOT -> handleCaptureScreenshot()
            ACTION_REFRESH_FOREGROUND_TYPE -> startOrUpdateForegroundService()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        if (isRecording) {
            Log.w(TAG, "Recording already active; ignoring duplicate ACTION_START")
            return
        }

        // Harus masuk ke foreground terlebih dahulu sebelum memanggil getMediaProjection (Android 14+)
        startOrUpdateForegroundService()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                ?: @Suppress("DEPRECATION") (intent.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
        }

        val isResultCodeValid = (resultCode == Activity.RESULT_OK) ||
            (intent.hasExtra(EXTRA_RESULT_CODE) && resultCode != Activity.RESULT_CANCELED)
        if (!isResultCodeValid || resultData == null) {
            notifyErrorAndStop("Izin tangkap layar tidak valid atau dibatalkan.")
            return
        }

        try {
            val prefs = AppPreferences(this)
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
            if (projection == null) {
                notifyErrorAndStop("Gagal memperoleh sesi MediaProjection dari sistem.")
                return
            }
            mediaProjection = projection
            projection.registerCallback(projectionCallback, mainHandler)

            val filePrefix = if (prefs.isGamingMode) "Game_Rekaman" else "Rekaman"
            val targetFile = StorageHelper.createOutputFile(this, filePrefix)
            outputFile = targetFile
            currentActiveFilePath = targetFile.absolutePath

            val recorder = MediaCodecScreenRecorder(
                context = this,
                outputPath = targetFile.absolutePath,
                mediaProjection = projection,
                prefs = prefs
            )
            val metrics = obtainDisplayMetrics()
            val inputSurface = recorder.prepare(metrics)
            codecRecorder = recorder

            virtualDisplay = projection.createVirtualDisplay(
                "RekaminDuluDisplay",
                recorder.actualWidth,
                recorder.actualHeight,
                recorder.actualDensityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null,
                mainHandler
            )

            recorder.start()

            isStopping.set(false)
            isRecording = true
            isPaused = false
            isMicMuted = false
            pausedAtMillis = 0L
            accumulatedPausedMillis = 0L
            startTimeMillis = System.currentTimeMillis()

            registerScreenOffReceiverIfNeeded(prefs)
            updateForegroundNotification()
            mainHandler.removeCallbacks(notificationUpdateRunnable)
            mainHandler.postDelayed(notificationUpdateRunnable, 1000L)

            if (prefs.vibrateOnStart) {
                triggerServiceVibration(45L)
            }

            if (prefs.showFloatingOverlay &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this))
            ) {
                try {
                    startService(Intent(this, FloatingOverlayService::class.java))
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to start FloatingOverlayService: ${e.message}")
                }
            }

            broadcastStatusChanged()
        } catch (e: Exception) {
            Log.e(TAG, "Gagal memulai perekaman layar", e)
            cleanupResources()
            notifyErrorAndStop("Gagal memulai perekaman layar: ${e.localizedMessage ?: "kesalahan sistem"}")
        }
    }

    private fun handlePause() {
        if (!isRecording || isPaused) return
        isPaused = true
        pausedAtMillis = System.currentTimeMillis()
        codecRecorder?.pause()
        updateForegroundNotification()
        broadcastStatusChanged()
    }

    private fun handleResume() {
        if (!isRecording || !isPaused) return
        if (pausedAtMillis > 0L) {
            accumulatedPausedMillis += (System.currentTimeMillis() - pausedAtMillis).coerceAtLeast(0L)
            pausedAtMillis = 0L
        }
        isPaused = false
        codecRecorder?.resume()
        updateForegroundNotification()
        broadcastStatusChanged()
    }

    private fun handleSetMicMuted(muted: Boolean) {
        if (!isRecording) return
        val effectiveMode = codecRecorder?.effectiveAudioMode ?: AppPreferences(this).audioSource
        if (effectiveMode != "MIC" && effectiveMode != "BOTH") {
            mainHandler.post {
                val msg = if (effectiveMode == "INTERNAL") {
                    "Mode Audio Internal aktif (mikrofon tidak digunakan)"
                } else {
                    "Perekaman dalam mode Mute (tanpa audio)"
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
            return
        }
        isMicMuted = muted
        codecRecorder?.setMicMuted(muted)
        updateForegroundNotification()
        broadcastStatusChanged()
    }

    private fun handleCaptureScreenshot() {
        val projection = mediaProjection
        if (!isRecording || projection == null) {
            mainHandler.post {
                Toast.makeText(this, "Perekaman layar belum aktif untuk tangkap layar", Toast.LENGTH_SHORT).show()
            }
            broadcastScreenshotResult(success = false, uriString = null)
            return
        }

        val metrics = obtainDisplayMetrics()
        val width = metrics.widthPixels.coerceAtLeast(320)
        val height = metrics.heightPixels.coerceAtLeast(480)
        val densityDpi = metrics.densityDpi.coerceAtLeast(160)

        try {
            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            val shotVirtualDisplay = projection.createVirtualDisplay(
                "RekaminScreenshot",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                mainHandler
            )

            val captured = AtomicBoolean(false)
            imageReader.setOnImageAvailableListener({ reader ->
                if (captured.getAndSet(true)) return@setOnImageAvailableListener
                var image: android.media.Image? = null
                try {
                    image = reader.acquireLatestImage()
                    if (image != null) {
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        val pixelStride = plane.pixelStride.coerceAtLeast(1)
                        val rowStride = plane.rowStride
                        val rowPadding = (rowStride - pixelStride * width).coerceAtLeast(0)
                        val rawBitmap = Bitmap.createBitmap(
                            width + rowPadding / pixelStride,
                            height,
                            Bitmap.Config.ARGB_8888
                        )
                        rawBitmap.copyPixelsFromBuffer(buffer)
                        val croppedBitmap = if (rawBitmap.width != width || rawBitmap.height != height) {
                            Bitmap.createBitmap(rawBitmap, 0, 0, width, height).also {
                                rawBitmap.recycle()
                            }
                        } else {
                            rawBitmap
                        }
                        val savedUri = StorageHelper.saveScreenshot(this, croppedBitmap)
                        croppedBitmap.recycle()

                        mainHandler.post {
                            if (savedUri != null) {
                                Toast.makeText(this, "📸 Tangkapan layar disimpan ke Galeri", Toast.LENGTH_SHORT).show()
                                broadcastScreenshotResult(success = true, uriString = savedUri.toString())
                            } else {
                                Toast.makeText(this, "Gagal menyimpan tangkapan layar", Toast.LENGTH_SHORT).show()
                                broadcastScreenshotResult(success = false, uriString = null)
                            }
                        }
                    } else {
                        mainHandler.post {
                            Toast.makeText(this, "Gagal mengambil frame tangkapan layar", Toast.LENGTH_SHORT).show()
                        }
                        broadcastScreenshotResult(success = false, uriString = null)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Screenshot capture error", e)
                    mainHandler.post {
                        Toast.makeText(this, "Gagal mengambil tangkapan layar", Toast.LENGTH_SHORT).show()
                    }
                    broadcastScreenshotResult(success = false, uriString = null)
                } finally {
                    try { image?.close() } catch (_: Exception) {}
                    try { shotVirtualDisplay?.release() } catch (_: Exception) {}
                    try { reader.close() } catch (_: Exception) {}
                }
            }, mainHandler)

            mainHandler.postDelayed({
                if (!captured.getAndSet(true)) {
                    try { shotVirtualDisplay?.release() } catch (_: Exception) {}
                    try { imageReader.close() } catch (_: Exception) {}
                    Toast.makeText(this, "Waktu tangkap layar habis, coba lagi", Toast.LENGTH_SHORT).show()
                    broadcastScreenshotResult(success = false, uriString = null)
                }
            }, 2500L)
        } catch (e: Exception) {
            Log.e(TAG, "Failed creating screenshot VirtualDisplay", e)
            mainHandler.post {
                Toast.makeText(this, "Perangkat membatasi tangkap layar sekunder saat merekam", Toast.LENGTH_SHORT).show()
            }
            broadcastScreenshotResult(success = false, uriString = null)
        }
    }

    private fun stopRecordingInternal() {
        if (isStopping.getAndSet(true)) return

        mainHandler.removeCallbacks(notificationUpdateRunnable)
        val recorder = codecRecorder
        val targetFile = outputFile

        Thread({
            try {
                virtualDisplay?.release()
            } catch (ignored: Exception) {}
            virtualDisplay = null

            val muxerWritten = try {
                recorder?.stop() ?: false
            } catch (e: Exception) {
                Log.w(TAG, "Recorder stop error: ${e.message}")
                false
            }
            codecRecorder = null

            try {
                mediaProjection?.unregisterCallback(projectionCallback)
                mediaProjection?.stop()
            } catch (ignored: Exception) {}
            mediaProjection = null

            val isValidFile = targetFile != null && targetFile.exists() && targetFile.length() > 10240L
            if (!muxerWritten && targetFile != null && targetFile.exists() && targetFile.length() <= 10240L) {
                try { targetFile.delete() } catch (_: Exception) {}
            }

            if (isValidFile && targetFile != null) {
                lastRecordedFilePath = targetFile.absolutePath
                MainActivity.hasPendingSaveDialog = true
                StorageHelper.scanVideoFile(this, targetFile)
                notificationHelper?.showRecordingCompletedNotification(targetFile)
            }

            mainHandler.post {
                if (AppPreferences(this).vibrateOnStart) {
                    triggerServiceVibration(60L)
                }
                unregisterScreenOffReceiver()
                currentActiveFilePath = null
                isRecording = false
                isPaused = false
                isMicMuted = false
                startTimeMillis = 0L
                pausedAtMillis = 0L
                accumulatedPausedMillis = 0L
                isStopping.set(false)

                broadcastStatusChanged()
                sendPackageBroadcast(Intent(ACTION_RECORDING_STOPPED).apply {
                    if (isValidFile && targetFile != null) {
                        putExtra(EXTRA_FILE_PATH, targetFile.absolutePath)
                    }
                })

                stopForegroundCompat()
                stopSelf()
            }
        }, "RekaminStopThread").start()
    }

    private fun startOrUpdateForegroundService() {
        val prefs = AppPreferences(this)
        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val hasCamera = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val wantsAudio = prefs.audioSource != "MUTE"
        val wantsFacecam = prefs.facecamMode != "OFF"

        val fgsType = resolveForegroundServiceType(
            hasMicPermission = hasMic,
            hasCameraPermission = hasCamera,
            wantsAudio = wantsAudio,
            wantsFacecam = wantsFacecam
        )

        val notification = buildRecordingNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NotificationHelper.NOTIFICATION_ID_RECORDING, notification, fgsType)
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID_RECORDING, notification)
        }
    }

    private fun updateForegroundNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NotificationHelper.NOTIFICATION_ID_RECORDING, buildRecordingNotification())
        } catch (e: Exception) {
            Log.w(TAG, "Failed updating foreground notification: ${e.message}")
        }
    }

    private fun buildRecordingNotification(): Notification {
        val openAppIntent = Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ScreenRecordingService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val togglePauseAction = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        val pauseResumeIntent = Intent(this, ScreenRecordingService::class.java).apply {
            action = togglePauseAction
        }
        val pendingPauseResume = PendingIntent.getService(
            this,
            102,
            pauseResumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val durationStr = FloatingOverlayService.formatDuration(elapsedSeconds)
        val title = if (isPaused) {
            "Rekaman Dijeda • $durationStr"
        } else {
            "Merekam Layar • $durationStr"
        }
        val activeAudioMode = codecRecorder?.effectiveAudioMode ?: AppPreferences(this).audioSource
        val subtitle = when {
            isMicMuted -> "Mikrofon dibisukan • Ketuk untuk membuka aplikasi"
            activeAudioMode == "INTERNAL" -> "Audio Internal Game aktif • Ketuk untuk kontrol"
            activeAudioMode == "MUTE" -> "Mode Tanpa Suara (Mute) • Ketuk untuk kontrol"
            else -> "Ketuk untuk kontrol atau berhenti merekam"
        }

        val pauseLabel = if (isPaused) "Lanjut" else "Jeda"
        val pauseIcon = if (isPaused) R.drawable.ic_play_arrow else R.drawable.ic_pause

        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_record)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(pendingOpenApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(pauseIcon, pauseLabel, pendingPauseResume)
            .addAction(R.drawable.ic_stop, "Berhenti", pendingStop)
            .build()
    }

    private fun obtainDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay?.getRealMetrics(metrics)
        }
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            val resMetrics = resources.displayMetrics
            metrics.widthPixels = resMetrics.widthPixels
            metrics.heightPixels = resMetrics.heightPixels
            metrics.densityDpi = resMetrics.densityDpi
        }
        return metrics
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerScreenOffReceiverIfNeeded(prefs: AppPreferences) {
        if (prefs.stopOnScreenOff && !screenOffReceiverRegistered) {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenOffReceiver, filter)
            }
            screenOffReceiverRegistered = true
        }
    }

    private fun unregisterScreenOffReceiver() {
        if (screenOffReceiverRegistered) {
            try {
                unregisterReceiver(screenOffReceiver)
            } catch (ignored: Exception) {}
            screenOffReceiverRegistered = false
        }
    }

    private fun broadcastStatusChanged() {
        sendPackageBroadcast(Intent(ACTION_RECORDING_STATUS_CHANGED))
    }

    private fun broadcastScreenshotResult(success: Boolean, uriString: String?) {
        sendPackageBroadcast(Intent(ACTION_SCREENSHOT_RESULT).apply {
            putExtra(EXTRA_SCREENSHOT_SUCCESS, success)
            if (uriString != null) {
                putExtra(EXTRA_SCREENSHOT_URI, uriString)
            }
        })
    }

    private fun notifyErrorAndStop(message: String) {
        sendPackageBroadcast(Intent(ACTION_RECORDING_ERROR).apply {
            putExtra(EXTRA_ERROR_MESSAGE, message)
        })
        stopForegroundCompat()
        stopSelf()
    }

    private fun sendPackageBroadcast(intent: Intent) {
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun cleanupResources() {
        mainHandler.removeCallbacks(notificationUpdateRunnable)
        unregisterScreenOffReceiver()
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { codecRecorder?.releaseAll() } catch (_: Exception) {}
        codecRecorder = null
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null
        currentActiveFilePath = null
        isRecording = false
        isPaused = false
        isMicMuted = false
        startTimeMillis = 0L
        pausedAtMillis = 0L
        accumulatedPausedMillis = 0L
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun triggerServiceVibration(durationMs: Long) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(durationMs)
            }
        } catch (ignored: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isStopping.get()) {
            cleanupResources()
        }
    }
}
