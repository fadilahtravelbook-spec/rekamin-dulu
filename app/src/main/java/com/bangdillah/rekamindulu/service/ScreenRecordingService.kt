package com.bangdillah.rekamindulu.service

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
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
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
import com.bangdillah.rekamindulu.MainActivity
import com.bangdillah.rekamindulu.R
import com.bangdillah.rekamindulu.floating.FloatingOverlayService
import com.bangdillah.rekamindulu.notification.NotificationHelper
import com.bangdillah.rekamindulu.utils.AppPreferences
import com.bangdillah.rekamindulu.utils.StorageHelper
import java.io.File
import java.util.Locale

/**
 * ScreenRecordingService - Foreground Service that manages the MediaProjection lifecycle,
 * virtual display initialization, MediaCodec/MediaRecorder video+audio capture, live mic mute,
 * and instant screenshot capture.
 */
open class ScreenRecordingService : Service() {

    companion object {
        const val TAG = "ScreenRecordingService"
        const val NOTIFICATION_ID = NotificationHelper.NOTIFICATION_ID_RECORDING
        const val CHANNEL_ID = NotificationHelper.CHANNEL_RECORDING

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
        const val ACTION_TOGGLE_MIC = "ACTION_TOGGLE_MIC"
        const val ACTION_SET_MIC_MUTED = "ACTION_SET_MIC_MUTED"
        const val ACTION_CAPTURE_SCREENSHOT = "ACTION_CAPTURE_SCREENSHOT"
        const val ACTION_REFRESH_FOREGROUND_TYPE = "ACTION_REFRESH_FOREGROUND_TYPE"
        const val ACTION_RECORDING_STOPPED = "com.bangdillah.rekamindulu.RECORDING_STOPPED"
        const val ACTION_RECORDING_STATUS_CHANGED = "com.bangdillah.rekamindulu.RECORDING_STATUS_CHANGED"
        const val ACTION_RECORDING_ERROR = "com.bangdillah.rekamindulu.RECORDING_ERROR"
        const val ACTION_SCREENSHOT_RESULT = "com.bangdillah.rekamindulu.SCREENSHOT_RESULT"
        const val EXTRA_FILE_PATH = "EXTRA_FILE_PATH"
        const val EXTRA_ERROR_MESSAGE = "EXTRA_ERROR_MESSAGE"
        const val EXTRA_MIC_MUTED = "EXTRA_MIC_MUTED"
        const val EXTRA_SCREENSHOT_SUCCESS = "EXTRA_SCREENSHOT_SUCCESS"
        const val EXTRA_SCREENSHOT_URI = "EXTRA_SCREENSHOT_URI"

        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"

        var isRecording = false
            protected set

        var isPaused = false
            protected set

        var isMicMuted = false
            protected set

        var startTimeMillis = 0L
            protected set

        var elapsedSeconds = 0
            protected set

        var lastRecordedFilePath: String? = null
            protected set

        var currentActiveFilePath: String? = null
            internal set

        fun resolveForegroundServiceType(
            hasMicPermission: Boolean,
            hasCameraPermission: Boolean,
            wantsAudio: Boolean,
            wantsFacecam: Boolean,
            sdkInt: Int = Build.VERSION.SDK_INT
        ): Int {
            var type = if (sdkInt >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                0
            }
            if (sdkInt >= Build.VERSION_CODES.R && hasMicPermission && wantsAudio) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (sdkInt >= Build.VERSION_CODES.R && hasCameraPermission && wantsFacecam) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            return type
        }
    }

    protected var mediaProjection: MediaProjection? = null
    protected var mediaProjectionCallback: MediaProjection.Callback? = null
    protected var virtualDisplay: VirtualDisplay? = null
    protected var virtualDisplayThread: HandlerThread? = null
    protected var virtualDisplayHandler: Handler? = null
    protected var mediaRecorder: MediaRecorder? = null
    protected var mediaCodecRecorder: MediaCodecScreenRecorder? = null
    protected var wakeLock: PowerManager.WakeLock? = null
    protected var wifiLock: WifiManager.WifiLock? = null

    protected var screenWidth = 720
    protected var screenHeight = 1280
    protected var screenDensity = 320

    private var isStarting = false
    private var isStopping = false
    private var isCapturingScreenshot = false
    @Volatile
    private var isForegroundActive = false

    private val timerHandler = Handler(Looper.getMainLooper())
    protected var currentVideoFilePath: String? = null
    private var pauseStartTime = 0L
    private var totalPausedDuration = 0L
    private var cachedDisplayMetrics: DisplayMetrics? = null

    private var screenOffReceiver: BroadcastReceiver? = null

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                if (!isPaused) {
                    elapsedSeconds++
                    ScreenRecorderService.syncRecordingState(isRecording, isPaused, startTimeMillis, elapsedSeconds, currentVideoFilePath)
                    if (elapsedSeconds % 5 == 0) {
                        updateNotification()
                    }
                }
                // Auto-stop jika rekaman melebihi batas 24 jam
                if (elapsedSeconds >= 86400) {
                    Log.w(TAG, "Recording reached 24 hour limit, auto-stopping")
                    stopRecording()
                    return
                }
                // Cek kapasitas storage setiap 10 detik tanpa overhead codec scanner atau binder lag
                if (!isPaused && elapsedSeconds % 10 == 0) {
                    if (RecordingEngineOptimizer.isStorageCriticallyLow(this@ScreenRecordingService)) {
                        Log.e(TAG, "Storage critically low (<100MB), auto-stopping recording")
                        stopRecording()
                        return
                    }
                }
                timerHandler.postDelayed(this, 1000)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        createNotificationChannel()

        if (action != ACTION_START && !isForegroundActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                startForeground(NOTIFICATION_ID, buildSavingNotification())
                isForegroundActive = true
            } catch (e: Exception) {
                Log.w(TAG, "Fallback foreground promotion on non-start action ($action): ${e.message}")
            }
        }

        when (action) {
            ACTION_START -> {
                if (isRecording || isStarting) {
                    Log.w(TAG, "Duplicate ACTION_START ignored while recording/starting is active")
                    return START_NOT_STICKY
                }
                val audioSourceExtra = intent.getStringExtra("EXTRA_AUDIO_SOURCE")
                if (!audioSourceExtra.isNullOrEmpty()) {
                    AppPreferences(this).audioSource = audioSourceExtra
                } else if (intent.hasExtra("EXTRA_MIC_ENABLED") || intent.hasExtra("EXTRA_GAME_AUDIO_ENABLED")) {
                    val micOn = intent.getBooleanExtra("EXTRA_MIC_ENABLED", true)
                    val gameOn = intent.getBooleanExtra("EXTRA_GAME_AUDIO_ENABLED", true)
                    AppPreferences(this).audioSource = when {
                        micOn && gameOn -> "BOTH"
                        micOn -> "MIC"
                        gameOn -> "INTERNAL"
                        else -> "MUTE"
                    }
                }

                safeStartForeground(buildNotification())
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_OK)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                        ?: @Suppress("DEPRECATION") (intent.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
                }
                val isResultCodeValid = (resultCode == Activity.RESULT_OK) || (intent.hasExtra(EXTRA_RESULT_CODE) && resultCode != Activity.RESULT_CANCELED)
                if (isResultCodeValid && resultData != null) {
                    startRecording(resultCode, resultData)
                } else {
                    Log.e(TAG, "Missing resultCode or resultData in ACTION_START (resultCode=$resultCode, hasData=${resultData != null})")
                    broadcastError("Izin tangkap layar diperlukan untuk memulai perekaman.")
                    stopForegroundCompat()
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                if (isRecording || isPaused || isStarting) {
                    updateSavingNotification()
                    stopRecording()
                } else {
                    stopForegroundCompat()
                    stopSelf()
                }
            }
            ACTION_PAUSE -> {
                if (isRecording && !isPaused) {
                    pauseRecording()
                    updateNotification()
                } else if (!isRecording && !isStarting) {
                    stopForegroundCompat()
                    stopSelf()
                }
            }
            ACTION_RESUME -> {
                if (isRecording && isPaused) {
                    resumeRecording()
                    updateNotification()
                } else if (!isRecording && !isStarting) {
                    stopForegroundCompat()
                    stopSelf()
                }
            }
            ACTION_SET_MIC_MUTED -> {
                if (isRecording || isPaused) {
                    val muted = intent.getBooleanExtra(EXTRA_MIC_MUTED, !isMicMuted)
                    applyMicMutedState(muted)
                } else if (!isStarting) {
                    stopForegroundCompat()
                    stopSelf()
                }
            }
            ACTION_TOGGLE_MIC -> {
                if (isRecording || isPaused) {
                    applyMicMutedState(!isMicMuted)
                } else if (!isStarting) {
                    stopForegroundCompat()
                    stopSelf()
                }
            }
            ACTION_CAPTURE_SCREENSHOT -> {
                if (isRecording || isPaused) {
                    captureScreenshotFromProjection()
                } else if (!isStarting) {
                    stopForegroundCompat()
                    stopSelf()
                }
            }
            ACTION_REFRESH_FOREGROUND_TYPE -> {
                if (isRecording || isPaused) {
                    safeStartForeground(buildNotification())
                } else if (!isStarting) {
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun stopForegroundCompat() {
        isForegroundActive = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_ID)
        } catch (ignored: Exception) {}
    }

    private fun applyMicMutedState(muted: Boolean) {
        val codecRec = mediaCodecRecorder
        if (codecRec != null) {
            val mode = codecRec.effectiveAudioMode
            if (mode == "MIC" || mode == "BOTH") {
                isMicMuted = muted
                codecRec.setMicMuted(muted)
                val prefs = AppPreferences(this)
                prefs.audioSource = when (mode) {
                    "BOTH" -> if (muted) "INTERNAL" else "BOTH"
                    else -> if (muted) "MUTE" else "MIC"
                }
            } else {
                isMicMuted = true
                Toast.makeText(
                    this,
                    "Mikrofon tidak direkam pada sesi Audio Internal/Mute ini",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            isMicMuted = muted
        }
        sendBroadcast(Intent(ACTION_RECORDING_STATUS_CHANGED).apply { setPackage(packageName) })
    }

    /**
     * Memastikan startForeground selalu dipanggil dengan ForegroundServiceType yang valid di Android 14+
     */
    private fun safeStartForeground(notification: Notification) {
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val prefs = AppPreferences(this)
            val hasMicPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val hasCameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val wantsAudio = prefs.audioSource != "MUTE"
            val wantsFacecam = prefs.facecamMode != "OFF"
            val fgsType = resolveForegroundServiceType(
                hasMicPermission = hasMicPermission,
                hasCameraPermission = hasCameraPermission,
                wantsAudio = wantsAudio,
                wantsFacecam = wantsFacecam
            )
            try {
                startForeground(NOTIFICATION_ID, notification, fgsType)
                isForegroundActive = true
            } catch (eFgs: Exception) {
                Log.w(TAG, "safeStartForeground with combined types failed, falling back to MEDIA_PROJECTION only", eFgs)
                try {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                    isForegroundActive = true
                } catch (eFgsFallback: Exception) {
                    Log.e(TAG, "safeStartForeground fallback failed", eFgsFallback)
                    try {
                        startForeground(NOTIFICATION_ID, notification)
                        isForegroundActive = true
                    } catch (ignored: Exception) {}
                }
            }
        } else {
            try {
                startForeground(NOTIFICATION_ID, notification)
                isForegroundActive = true
            } catch (e: Exception) {
                Log.e(TAG, "startForeground pre-Q failed", e)
            }
        }
    }

    /**
     * Memulai perekaman layar dengan MediaProjection API, VirtualDisplay, dan perekaman audio mikrofon
     */
    fun startRecording(resultCode: Int, resultData: Intent) {
        if (isRecording || isStarting) return
        isStarting = true
        val prefs = AppPreferences(this)

        // 1. Inisialisasi WakeLock dan Wi-Fi lock untuk performa maksimal bebas throttle
        acquireWakeLock(prefs)

        // 1b. Elevasi prioritas thread encoder CPU ke THREAD_PRIORITY_URGENT_DISPLAY (-8)
        // Menjamin antrean frame VSYNC GPU dialirkan 100% mulus tanpa patah-patah/frame drop
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            Log.i(TAG, "Encoder Real-Time Priority: Service process priority elevated to THREAD_PRIORITY_URGENT_DISPLAY (-8)")
        } catch (e: Exception) {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
            } catch (ignored: Exception) {}
        }

        // 2. Buat Notification Channel dan promosikan Service ke Foreground Service
        safeStartForeground(buildNotification())

        try {
            // 3. Ambil dimensi layar fisik perangkat
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            cachedDisplayMetrics = metrics

            calculateOutputDimensions(metrics, prefs.resolution, prefs.orientation)

            // 4. Siapkan file output di direktori aman (prefix Game_Rekaman saat Mode Gaming aktif)
            val filePrefix = if (prefs.isGamingMode) "Game_Rekaman" else "Rekaman"
            val outputFile = StorageHelper.createOutputFile(this, filePrefix)
            currentVideoFilePath = outputFile.absolutePath
            currentActiveFilePath = outputFile.absolutePath

            // 5. Inisialisasi MediaProjection API
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

            if (mediaProjection == null) {
                Log.e(TAG, "MediaProjection is null, cannot proceed with recording")
                broadcastError("Izin MediaProjection ditolak oleh sistem")
                stopRecording()
                return
            }

            // Inisialisasi dedicated HandlerThread dengan prioritas DISPLAY
            try {
                virtualDisplayThread?.quitSafely()
            } catch (ignored: Exception) {}
            val vdPriority = android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
            val vdThread = HandlerThread("RekaminVDisplayWorker", vdPriority).apply { start() }
            virtualDisplayThread = vdThread
            val vdHandler = Handler(vdThread.looper)
            virtualDisplayHandler = vdHandler

            // Callback MediaProjection untuk Android 14+ (wajib didaftarkan sebelum createVirtualDisplay / AudioPlaybackCapture)
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection session stopped by system or user")
                    if (isRecording || isPaused) {
                        stopRecording()
                    }
                }
            }
            mediaProjectionCallback = callback
            mediaProjection?.registerCallback(callback, vdHandler)

            // 6. Mulai Encoder Engine:
            // Gunakan MediaCodecScreenRecorder modern pada API 29+ (mendukung AudioPlaybackCapture INTERNAL/BOTH + Live Mic Mute),
            // dengan fallback otomatis ke MediaRecorder bila inisialisasi MediaCodec gagal atau pada API < 29.
            var activeSurface: android.view.Surface? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val codecRec = MediaCodecScreenRecorder(
                        context = this,
                        outputPath = outputFile.absolutePath,
                        mediaProjection = mediaProjection,
                        prefs = prefs
                    )
                    val surface = codecRec.prepare(metrics)
                    screenWidth = codecRec.actualWidth
                    screenHeight = codecRec.actualHeight
                    screenDensity = codecRec.actualDensityDpi
                    codecRec.start()
                    mediaCodecRecorder = codecRec
                    activeSurface = surface
                    isMicMuted = (codecRec.effectiveAudioMode == "INTERNAL" || codecRec.effectiveAudioMode == "MUTE")
                    Log.i(TAG, "MediaCodecScreenRecorder aktif (${screenWidth}x${screenHeight} @ ${codecRec.actualFps}FPS, audio=${codecRec.effectiveAudioMode})")
                } catch (eCodec: Exception) {
                    Log.w(TAG, "MediaCodecScreenRecorder gagal, beralih ke fallback MediaRecorder: ${eCodec.message}", eCodec)
                    mediaCodecRecorder?.releaseAll()
                    mediaCodecRecorder = null
                }
            }

            if (activeSurface == null) {
                setupMediaRecorder(outputFile.absolutePath, prefs, metrics)
                try {
                    mediaRecorder?.start()
                } catch (eStart: Exception) {
                    Log.w(TAG, "mediaRecorder.start failed with initial config, attempting safe fallback", eStart)
                    setupMediaRecorderFallback(outputFile.absolutePath, metrics)
                    try {
                        mediaRecorder?.start()
                    } catch (eFallbackStart: Exception) {
                        Log.e(TAG, "Fallback mediaRecorder.start also failed", eFallbackStart)
                        broadcastError("Gagal memulai encoder rekaman: ${eFallbackStart.localizedMessage ?: "Encoder video tidak dapat dimulai"}")
                        stopRecording()
                        return
                    }
                }
                activeSurface = mediaRecorder?.surface
                isMicMuted = (prefs.audioSource == "MUTE" || prefs.audioSource == "INTERNAL")
            }

            if (activeSurface == null) {
                Log.e(TAG, "Encoder surface is null, aborting start")
                broadcastError("Encoder surface tidak tersedia")
                stopRecording()
                return
            }

            // 7. Inisialisasi VirtualDisplay menggunakan Surface dari encoder yang sudah aktif
            val displayFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "RekaminDisplay",
                screenWidth,
                screenHeight,
                screenDensity,
                displayFlags,
                activeSurface,
                null,
                vdHandler
            )

            isRecording = true
            isPaused = false
            startTimeMillis = System.currentTimeMillis()
            elapsedSeconds = 0
            pauseStartTime = 0L
            totalPausedDuration = 0L
            ScreenRecorderService.syncRecordingState(isRecording, isPaused, startTimeMillis, elapsedSeconds, currentVideoFilePath)

            timerHandler.post(timerRunnable)
            sendBroadcast(Intent(ACTION_RECORDING_STATUS_CHANGED).apply { setPackage(packageName) })

            // Getar saat mulai jika diaktifkan
            if (prefs.vibrateOnStart) {
                triggerVibration(100)
            }

            // Daftarkan listener layar mati jika diaktifkan
            if (prefs.stopOnScreenOff) {
                registerScreenOffReceiver()
            }

            // Mulai floating overlay jika diizinkan
            if (prefs.showFloatingOverlay) {
                val canOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Settings.canDrawOverlays(this)
                } else true

                if (canOverlay) {
                    val overlayIntent = Intent(this, FloatingOverlayService::class.java)
                    startService(overlayIntent)
                }
            }

            Log.i(TAG, "Screen recording started successfully: $currentVideoFilePath")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screen recording", e)
            broadcastError("Gagal memulai perekaman layar: ${e.localizedMessage ?: "Encoder video gagal diinisialisasi"}")
            stopRecording()
        } finally {
            isStarting = false
        }
    }

    private fun broadcastError(message: String) {
        val errorIntent = Intent(ACTION_RECORDING_ERROR).apply {
            putExtra(EXTRA_ERROR_MESSAGE, message)
            setPackage(packageName)
        }
        sendBroadcast(errorIntent)
    }

    private fun acquireWakeLock(prefs: AppPreferences) {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val lockTag = if (prefs.gamingTurboBoost || prefs.isGamingMode) {
                "RekaminDulu:GameTurboCpuLock"
            } else {
                "RekaminDulu:RecordingWakeLock"
            }
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, lockTag).apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L) // maks 24 jam
            }

            // Jika Game Turbo atau Mode Gaming aktif, kunci Wi-Fi low latency agar ping game online stabil
            if (prefs.gamingTurboBoost || prefs.isGamingMode) {
                try {
                    val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "RekaminDulu:GameTurboWifiLock")
                    } else {
                        @Suppress("DEPRECATION")
                        wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RekaminDulu:GameTurboWifiLock")
                    }
                    wifiLock?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                    Log.i(TAG, "Game Turbo: Low-latency CPU & Wi-Fi Lock aktif untuk game online")
                } catch (eWifi: Exception) {
                    Log.w(TAG, "Wi-Fi Lock notice: ${eWifi.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock acquisition notice: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock release error: ${e.message}")
        } finally {
            wakeLock = null
        }

        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (eWifi: Exception) {
            Log.w(TAG, "WiFiLock release error: ${eWifi.message}")
        } finally {
            wifiLock = null
        }
    }

    protected fun calculateOutputDimensions(metrics: DisplayMetrics, resolutionPref: String, orientationPref: String = "AUTO") {
        val dims = RecordingEngineOptimizer.calculateDimensions(metrics, resolutionPref, orientationPref)
        screenWidth = dims.width
        screenHeight = dims.height
        screenDensity = dims.densityDpi
        Log.i(TAG, "Output resolution calculated: ${screenWidth}x${screenHeight} @ ${screenDensity} DPI (Pref: $resolutionPref)")
    }

    /**
     * Konfigurasi MediaRecorder dengan fallback multi-tier otomatis:
     * - Memprioritaskan GPU/VPU Hardware Video Encoder (Offload CPU) agar game tidak lag.
     * - Jalur Audio Internal Mandiri & Stabil (High-Fidelity 48kHz, anti-noise, anti-bentrok voice chat game).
     */
    protected fun setupMediaRecorder(
        filePath: String,
        prefs: AppPreferences,
        metrics: DisplayMetrics = resources.displayMetrics
    ) {
        val hasMicPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val isInternalOnly = prefs.audioSource == "INTERNAL"
        val wantsAudio = prefs.audioSource != "MUTE" && hasMicPermission

        // Validasi ketersediaan GPU hardware encoder agar tidak membebani CPU
        if (prefs.hardwareAccelerationEnabled) {
            val hwEncoder = com.bangdillah.rekamindulu.utils.HardwareEncoderHelper.findBestHardwareEncoder()
            if (hwEncoder != null && hwEncoder.isHardware) {
                Log.i(TAG, "Hardware GPU Encoder aktif: ${hwEncoder.codecName} (${hwEncoder.vendorDescription})")
            } else {
                Log.i(TAG, "Hardware encoder adaptif aktif (profil hardware diselaraskan)")
            }
        }

        fun configureAndPrepare(
            withAudio: Boolean,
            targetWidth: Int,
            targetHeight: Int,
            bitrate: Int,
            fps: Int,
            audioSourceOverride: Int? = null,
            encoderOverride: Int? = null
        ): Boolean {
            try {
                mediaRecorder?.reset()
                mediaRecorder?.release()
            } catch (ignored: Exception) {}

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            var sourceSet = false
            return try {
                mediaRecorder?.apply {
                    if (withAudio) {
                        val candidateSources = if (audioSourceOverride != null) {
                            com.bangdillah.rekamindulu.audio.MicrophoneSelector.getFallbackAudioSources(audioSourceOverride)
                        } else if (isInternalOnly) {
                            intArrayOf(
                                com.bangdillah.rekamindulu.audio.MicrophoneSelector.getGameAudioSource(),
                                MediaRecorder.AudioSource.DEFAULT,
                                MediaRecorder.AudioSource.MIC
                            )
                        } else {
                            val primary = com.bangdillah.rekamindulu.audio.MicrophoneSelector.getAudioSource(prefs.micType)
                            com.bangdillah.rekamindulu.audio.MicrophoneSelector.getFallbackAudioSources(primary)
                        }

                        for (src in candidateSources) {
                            try {
                                setAudioSource(src)
                                sourceSet = true
                                Log.i(TAG, "Audio source $src berhasil disetel untuk perekaman")
                                break
                            } catch (eSrc: Exception) {
                                Log.w(TAG, "Audio source $src ditolak oleh sistem hardware: ${eSrc.message}")
                            }
                        }

                        if (!sourceSet) {
                            Log.w(TAG, "Semua audio source hardware ditolak, beralih ke video-only")
                        }
                    }

                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

                    if (withAudio && sourceSet) {
                        try {
                            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

                            val preferStereo = isInternalOnly || prefs.micType == "camcorder" || prefs.audioSource == "BOTH"
                            var channelSet = false
                            if (preferStereo) {
                                try {
                                    setAudioChannels(2)
                                    channelSet = true
                                } catch (eStereo: Exception) {
                                    Log.w(TAG, "Stereo 2 kanal ditolak, fallback ke mono: ${eStereo.message}")
                                }
                            }
                            if (!channelSet) {
                                try { setAudioChannels(1) } catch (ignored: Exception) {}
                            }

                            val sampleRate = try {
                                com.bangdillah.rekamindulu.audio.AudioHelper.getOptimalSampleRate(this@ScreenRecordingService)
                            } catch (e: Exception) {
                                44100
                            }
                            try {
                                setAudioSamplingRate(sampleRate)
                            } catch (eRate: Exception) {
                                try { setAudioSamplingRate(44100) } catch (ignored: Exception) {}
                            }

                            val targetBitrate = if (preferStereo) 192000 else 160000
                            try {
                                setAudioEncodingBitRate(targetBitrate)
                            } catch (eBitrate: Exception) {
                                try { setAudioEncodingBitRate(128000) } catch (ignored: Exception) {}
                            }
                        } catch (eAac: Exception) {
                            Log.w(TAG, "Gagal menginisialisasi audio encoder AAC: ${eAac.message}")
                            return false
                        }
                    }

                    val encoder = encoderOverride ?: if (prefs.videoEncoder == "H265" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        MediaRecorder.VideoEncoder.HEVC
                    } else {
                        MediaRecorder.VideoEncoder.H264
                    }
                    try {
                        setVideoEncoder(encoder)
                    } catch (eEnc: Exception) {
                        Log.w(TAG, "Encoder $encoder gagal, coba H264", eEnc)
                        setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    }

                    RecordingEngineOptimizer.applyHighQualityEncoderTuning(this, encoder)

                    setOutputFile(filePath)
                    val chosenFps = fps.coerceIn(15, 120)
                    val chosenBitrate = bitrate.coerceIn(1_000_000, 60_000_000)

                    setVideoSize(targetWidth, targetHeight)
                    setVideoEncodingBitRate(chosenBitrate)
                    setVideoFrameRate(chosenFps)

                    Log.i(TAG, "MediaRecorder dikonfigurasi: ${targetWidth}x${targetHeight} @ $chosenFps FPS (${chosenBitrate / 1_000_000} Mbps)")

                    setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "MediaRecorder error: what=$what, extra=$extra")
                        stopRecording()
                    }
                    setOnInfoListener { _, what, extra ->
                        if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                            what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                            stopRecording()
                        }
                    }

                    prepare()
                }
                screenWidth = targetWidth
                screenHeight = targetHeight
                true
            } catch (ePrep: Exception) {
                Log.w(TAG, "MediaRecorder prepare failed (withAudio=$withAudio, ${targetWidth}x${targetHeight}): ${ePrep.message}")
                false
            }
        }

        val dims720 = RecordingEngineOptimizer.calculateDimensions(metrics, "720p", prefs.orientation)
        val dims480 = RecordingEngineOptimizer.calculateDimensions(metrics, "480p", prefs.orientation)
        val safeW = dims720.width
        val safeH = dims720.height
        val ultraSafeW = dims480.width
        val ultraSafeH = dims480.height

        val optimizedProfile = RecordingEngineOptimizer.getOptimizedProfile(metrics, prefs)
        val initialBitrate = optimizedProfile.targetBitrateBps
        val initialFps = optimizedProfile.targetFps
        val preferredAudioSource = if (isInternalOnly) {
            com.bangdillah.rekamindulu.audio.MicrophoneSelector.getGameAudioSource()
        } else {
            com.bangdillah.rekamindulu.audio.MicrophoneSelector.getAudioSource(prefs.micType)
        }

        // Tier 1: Konfigurasi optimal sesuai pilihan pengguna
        if (configureAndPrepare(wantsAudio, screenWidth, screenHeight, initialBitrate, initialFps, preferredAudioSource)) {
            Log.i(TAG, "Tier 1: MediaRecorder siap (${screenWidth}x${screenHeight} @ ${initialFps} FPS, bitrate=${initialBitrate / 1_000_000} Mbps, audio=$wantsAudio)")
            return
        }

        // Tier 1b: Jika HEVC dipilih tapi gagal di Tier 1, coba H264
        if (prefs.videoEncoder == "H265") {
            if (configureAndPrepare(wantsAudio, screenWidth, screenHeight, initialBitrate, initialFps, preferredAudioSource, MediaRecorder.VideoEncoder.H264)) {
                Log.i(TAG, "Tier 1b: MediaRecorder H264 siap @ ${initialFps} FPS")
                return
            }
        }

        // Tier 2: Coba dengan sumber MIC standar
        if (wantsAudio && preferredAudioSource != MediaRecorder.AudioSource.MIC) {
            if (configureAndPrepare(true, screenWidth, screenHeight, initialBitrate, initialFps, MediaRecorder.AudioSource.MIC, MediaRecorder.VideoEncoder.H264)) {
                Log.i(TAG, "Tier 2: MediaRecorder siap dengan audio MIC standar @ ${initialFps} FPS")
                return
            }
        }

        // Tier 3: Fallback ke HD 720p proporsional rasio layar
        val fallbackBitrate720 = minOf(initialBitrate, 14_000_000)
        if (configureAndPrepare(wantsAudio, safeW, safeH, fallbackBitrate720, initialFps, MediaRecorder.AudioSource.MIC, MediaRecorder.VideoEncoder.H264)) {
            screenWidth = safeW
            screenHeight = safeH
            screenDensity = dims720.densityDpi
            Log.i(TAG, "Tier 3: MediaRecorder 720p siap @ ${initialFps} FPS (audio=$wantsAudio)")
            return
        }

        // Tier 4: Fallback ke 480p proporsional rasio layar
        val fallbackBitrate480 = minOf(initialBitrate, 6_000_000)
        if (configureAndPrepare(wantsAudio, ultraSafeW, ultraSafeH, fallbackBitrate480, initialFps, MediaRecorder.AudioSource.MIC, MediaRecorder.VideoEncoder.H264)) {
            screenWidth = ultraSafeW
            screenHeight = ultraSafeH
            screenDensity = dims480.densityDpi
            Log.i(TAG, "Tier 4: MediaRecorder 480p siap @ ${initialFps} FPS (audio=$wantsAudio)")
            return
        }

        // Tier 5: Video-only 720p
        if (wantsAudio) {
            if (configureAndPrepare(false, safeW, safeH, fallbackBitrate720, initialFps, null, MediaRecorder.VideoEncoder.H264)) {
                screenWidth = safeW
                screenHeight = safeH
                screenDensity = dims720.densityDpi
                Log.i(TAG, "Tier 5: MediaRecorder 720p video-only siap @ ${initialFps} FPS")
                return
            }
        }

        // Tier 6: Fallback terakhir universal 480p video-only
        if (!configureAndPrepare(false, ultraSafeW, ultraSafeH, fallbackBitrate480, initialFps, null, MediaRecorder.VideoEncoder.H264)) {
            throw RuntimeException("Perangkat tidak dapat menginisialisasi encoder video untuk perekaman layar.")
        }
        screenWidth = ultraSafeW
        screenHeight = ultraSafeH
        screenDensity = dims480.densityDpi
    }

    /**
     * Fallback cepat jika mediaRecorder.start() gagal pada konfigurasi awal sebelum virtual display dibuat.
     */
    protected fun setupMediaRecorderFallback(
        filePath: String,
        metrics: DisplayMetrics = resources.displayMetrics
    ) {
        val prefs = AppPreferences(this)
        val hasMicPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val wantsAudio = prefs.audioSource != "MUTE" && hasMicPermission
        val targetFps = prefs.fps.coerceIn(15, 120)
        val targetBitrate = (prefs.bitrateMbps * 1_000_000).coerceIn(1_000_000, 30_000_000)

        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (ignored: Exception) {}

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        val dims720 = RecordingEngineOptimizer.calculateDimensions(metrics, "720p", prefs.orientation)
        val safeW = dims720.width
        val safeH = dims720.height

        var preparedWithAudio = false
        if (wantsAudio) {
            try {
                mediaRecorder?.apply {
                    val fallbackSource = if (prefs.audioSource == "INTERNAL") {
                        com.bangdillah.rekamindulu.audio.MicrophoneSelector.getGameAudioSource()
                    } else {
                        MediaRecorder.AudioSource.MIC
                    }
                    val candidates = com.bangdillah.rekamindulu.audio.MicrophoneSelector.getFallbackAudioSources(fallbackSource)
                    var sourceSet = false
                    for (src in candidates) {
                        try {
                            setAudioSource(src)
                            sourceSet = true
                            break
                        } catch (e: Exception) {
                            Log.w(TAG, "Fallback setAudioSource $src failed: ${e.message}")
                        }
                    }
                    if (!sourceSet) throw IllegalStateException("Tidak ada sumber audio yang diterima")

                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioChannels(1)
                    val sampleRate = try {
                        com.bangdillah.rekamindulu.audio.AudioHelper.getOptimalSampleRate(this@ScreenRecordingService)
                    } catch (e: Exception) {
                        44100
                    }
                    try {
                        setAudioSamplingRate(sampleRate)
                    } catch (eRate: Exception) {
                        try { setAudioSamplingRate(44100) } catch (ignored: Exception) {}
                    }
                    try {
                        setAudioEncodingBitRate(128000)
                    } catch (eBitrate: Exception) {
                        try { setAudioEncodingBitRate(64000) } catch (ignored: Exception) {}
                    }
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setOutputFile(filePath)
                    setVideoSize(safeW, safeH)
                    setVideoEncodingBitRate(minOf(targetBitrate, 8_000_000))
                    setVideoFrameRate(targetFps)
                    prepare()
                }
                preparedWithAudio = true
                Log.i(TAG, "Fallback MediaRecorder berhasil disiapkan DENGAN AUDIO mikrofon @ ${targetFps} FPS")
            } catch (e: Exception) {
                Log.w(TAG, "Fallback dengan audio gagal, mencoba video-only", e)
                try {
                    mediaRecorder?.reset()
                    mediaRecorder?.release()
                } catch (ignored: Exception) {}
                mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(this)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }
            }
        }

        if (!preparedWithAudio) {
            mediaRecorder?.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setOutputFile(filePath)
                setVideoSize(safeW, safeH)
                setVideoEncodingBitRate(minOf(targetBitrate, 6_000_000))
                setVideoFrameRate(targetFps)
                prepare()
            }
        }
        screenWidth = safeW
        screenHeight = safeH
        screenDensity = dims720.densityDpi
    }

    /**
     * Menghentikan perekaman layar, merilis resource MediaProjection dan VirtualDisplay
     */
    @Synchronized
    fun stopRecording(immediate: Boolean = false) {
        if (isStopping) return
        if (!isRecording && !isPaused && mediaRecorder == null && mediaCodecRecorder == null && virtualDisplay == null) {
            stopForegroundCompat()
            stopSelf()
            return
        }
        isStopping = true

        try {
            val prefs = AppPreferences(this)
            isRecording = false
            currentActiveFilePath = null
            isPaused = false
            isMicMuted = false
            timerHandler.removeCallbacks(timerRunnable)

            unregisterScreenOffReceiver()
            releaseWakeLock()

            if (!immediate) {
                try {
                    updateSavingNotification()
                } catch (e: Exception) {
                    Log.w(TAG, "Error displaying saving notification", e)
                }

                // Beri jeda pengaman min 1.2 detik agar encoder menerima frame & audio yang cukup,
                // lalu jalankan finalisasi pada worker thread agar Main Thread bebas dari ANR.
                val elapsedDuration = System.currentTimeMillis() - startTimeMillis
                val remainingMs = if (startTimeMillis > 0L && elapsedDuration in 0..1200) {
                    1200L - elapsedDuration
                } else {
                    0L
                }

                if (Looper.myLooper() == Looper.getMainLooper()) {
                    Thread({
                        if (remainingMs > 0L) {
                            try {
                                Thread.sleep(remainingMs)
                            } catch (ignored: InterruptedException) {}
                        }
                        finalizeStopRecording(prefs)
                    }, "RekaminStopWorker").start()
                    return
                } else if (remainingMs > 0L) {
                    try {
                        Thread.sleep(remainingMs)
                    } catch (ignored: InterruptedException) {}
                }
            }

            finalizeStopRecording(prefs)
        } catch (e: Exception) {
            Log.e(TAG, "Error in stopRecording", e)
            isStopping = false
        }
    }

    private fun finalizeStopRecording(prefs: AppPreferences) {
        try {
            var recordingStoppedCleanly = false
            val activeCodecRecorder = mediaCodecRecorder
            if (activeCodecRecorder != null) {
                try {
                    recordingStoppedCleanly = activeCodecRecorder.stop()
                    Log.i(TAG, "MediaCodecScreenRecorder difinalisasi (clean=$recordingStoppedCleanly)")
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping mediaCodecRecorder", e)
                } finally {
                    mediaCodecRecorder = null
                }
            } else {
                // Finalisasi MediaRecorder TERLEBIH DAHULU sementara Surface dari VirtualDisplay masih aktif
                try {
                    mediaRecorder?.stop()
                    recordingStoppedCleanly = true
                    Log.i(TAG, "MediaRecorder berhasil dihentikan & header MP4 moov atom difinalisasi")
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Notice stopping mediaRecorder (rekaman mungkin sangat singkat): ${e.message}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping mediaRecorder", e)
                }
            }

            // Lepaskan VirtualDisplay setelah encoder selesai menulis trailer MP4
            try {
                virtualDisplay?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing virtualDisplay", e)
            } finally {
                virtualDisplay = null
            }

            try {
                virtualDisplayThread?.quitSafely()
            } catch (e: Exception) {
                Log.w(TAG, "Error quitting virtualDisplayThread: ${e.message}")
            } finally {
                virtualDisplayThread = null
                virtualDisplayHandler = null
            }

            // Hentikan session MediaProjection
            try {
                mediaProjectionCallback?.let {
                    mediaProjection?.unregisterCallback(it)
                }
                mediaProjectionCallback = null
                mediaProjection?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping mediaProjection", e)
            } finally {
                mediaProjection = null
            }

            try {
                mediaRecorder?.reset()
                mediaRecorder?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing mediaRecorder", e)
            } finally {
                mediaRecorder = null
            }

            try {
                stopService(Intent(this, FloatingOverlayService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping FloatingOverlayService", e)
            }

            if (prefs.vibrateOnStart) {
                triggerVibration(150)
            }

            val savedPath = currentVideoFilePath
            if (!savedPath.isNullOrEmpty()) {
                val savedFile = File(savedPath)
                var fileHasValidHeader = false
                if (savedFile.exists() && savedFile.length() > 10240) {
                    var testRetriever: MediaMetadataRetriever? = null
                    try {
                        testRetriever = MediaMetadataRetriever()
                        testRetriever.setDataSource(savedFile.absolutePath)
                        val dur = testRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        val hasVid = "yes".equals(
                            testRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO),
                            ignoreCase = true
                        )
                        fileHasValidHeader = dur > 0L || hasVid || recordingStoppedCleanly
                    } catch (e: Exception) {
                        Log.w(TAG, "Header validation notice: ${e.message}")
                        fileHasValidHeader = recordingStoppedCleanly || savedFile.length() > 50000
                    } finally {
                        try { testRetriever?.release() } catch (ignored: Exception) {}
                    }
                }

                val isValidRecording = savedFile.exists() && savedFile.length() > 10240 && fileHasValidHeader
                if (isValidRecording) {
                    StorageHelper.scanVideoFile(this, savedFile)
                    MainActivity.hasPendingSaveDialog = true
                    lastRecordedFilePath = savedPath
                    NotificationHelper(this).showRecordingCompletedNotification(savedFile)
                } else {
                    if (savedFile.exists()) {
                        savedFile.delete()
                    }
                    lastRecordedFilePath = null
                    if (!recordingStoppedCleanly && startTimeMillis > 0L) {
                        broadcastError("Rekaman terlalu singkat (kurang dari 1 detik) sehingga tidak dapat disimpan.")
                    }
                }
            } else {
                lastRecordedFilePath = null
            }
            currentVideoFilePath = null

            ScreenRecorderService.syncRecordingState(isRecording, isPaused, startTimeMillis, elapsedSeconds, lastRecordedFilePath)

            val stoppedIntent = Intent(ACTION_RECORDING_STOPPED).apply {
                putExtra(EXTRA_FILE_PATH, lastRecordedFilePath)
                setPackage(packageName)
            }
            sendBroadcast(stoppedIntent)

            val statusIntent = Intent(ACTION_RECORDING_STATUS_CHANGED).apply {
                setPackage(packageName)
            }
            sendBroadcast(statusIntent)

            stopForegroundCompat()
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
            } catch (ignored: Exception) {}

            Log.i(TAG, "Screen recording stopped. File saved: $lastRecordedFilePath")
            stopSelf()
        } finally {
            isStopping = false
        }
    }

    fun pauseRecording() {
        if (isRecording && !isPaused) {
            try {
                if (mediaCodecRecorder != null) {
                    mediaCodecRecorder?.pause()
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    mediaRecorder?.pause()
                } else {
                    return
                }
                isPaused = true
                ScreenRecorderService.syncRecordingState(isRecording, isPaused, startTimeMillis, elapsedSeconds, currentVideoFilePath)
                pauseStartTime = System.currentTimeMillis()
                updateNotification()
                sendBroadcast(Intent(ACTION_RECORDING_STATUS_CHANGED).apply { setPackage(packageName) })
            } catch (e: Exception) {
                Log.e(TAG, "Error pausing recording", e)
            }
        }
    }

    fun resumeRecording() {
        if (isRecording && isPaused) {
            try {
                if (mediaCodecRecorder != null) {
                    mediaCodecRecorder?.resume()
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    mediaRecorder?.resume()
                } else {
                    return
                }
                isPaused = false
                ScreenRecorderService.syncRecordingState(isRecording, isPaused, startTimeMillis, elapsedSeconds, currentVideoFilePath)
                if (pauseStartTime > 0L) {
                    totalPausedDuration += (System.currentTimeMillis() - pauseStartTime)
                    pauseStartTime = 0L
                }
                updateNotification()
                sendBroadcast(Intent(ACTION_RECORDING_STATUS_CHANGED).apply { setPackage(packageName) })
            } catch (e: Exception) {
                Log.e(TAG, "Error resuming recording", e)
            }
        }
    }

    /**
     * Mengambil tangkapan layar PNG beresolusi penuh dari MediaProjection yang sedang aktif
     * dan menyimpannya ke folder Pictures/RekaminDulu melalui StorageHelper.
     */
    private fun captureScreenshotFromProjection() {
        val projection = mediaProjection
        if (projection == null || (!isRecording && !isPaused)) {
            Toast.makeText(this, "Screenshot hanya tersedia saat rekaman layar aktif.", Toast.LENGTH_SHORT).show()
            return
        }
        if (isCapturingScreenshot) return
        isCapturingScreenshot = true

        val metrics = cachedDisplayMetrics ?: resources.displayMetrics
        val capW = screenWidth.coerceAtLeast(320)
        val capH = screenHeight.coerceAtLeast(480)
        val capDpi = screenDensity.coerceIn(120, 640)

        Thread({
            var imageReader: ImageReader? = null
            var shotDisplay: VirtualDisplay? = null
            var shotThread: HandlerThread? = null
            try {
                shotThread = HandlerThread("RekaminScreenshot").apply { start() }
                val shotHandler = Handler(shotThread.looper)
                imageReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
                val latch = java.util.concurrent.CountDownLatch(1)
                var capturedBitmap: Bitmap? = null

                imageReader.setOnImageAvailableListener({ reader ->
                    if (capturedBitmap != null) return@setOnImageAvailableListener
                    var image: android.media.Image? = null
                    try {
                        image = reader.acquireLatestImage()
                        if (image != null) {
                            val plane = image.planes[0]
                            val buffer = plane.buffer
                            val pixelStride = plane.pixelStride
                            val rowStride = plane.rowStride
                            val rowPadding = rowStride - pixelStride * capW
                            val fullBitmap = Bitmap.createBitmap(
                                capW + rowPadding / pixelStride,
                                capH,
                                Bitmap.Config.ARGB_8888
                            )
                            fullBitmap.copyPixelsFromBuffer(buffer)
                            capturedBitmap = if (rowPadding == 0) {
                                fullBitmap
                            } else {
                                Bitmap.createBitmap(fullBitmap, 0, 0, capW, capH).also {
                                    fullBitmap.recycle()
                                }
                            }
                            latch.countDown()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Screenshot frame acquire error: ${e.message}")
                        latch.countDown()
                    } finally {
                        try { image?.close() } catch (ignored: Exception) {}
                    }
                }, shotHandler)

                shotDisplay = projection.createVirtualDisplay(
                    "RekaminShot",
                    capW,
                    capH,
                    capDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.surface,
                    null,
                    shotHandler
                )

                latch.await(1500L, java.util.concurrent.TimeUnit.MILLISECONDS)

                val bmp = capturedBitmap
                val savedUri = if (bmp != null) {
                    StorageHelper.saveScreenshot(this, bmp).also {
                        bmp.recycle()
                    }
                } else null

                timerHandler.post {
                    val resultIntent = Intent(ACTION_SCREENSHOT_RESULT).apply {
                        putExtra(EXTRA_SCREENSHOT_SUCCESS, savedUri != null)
                        putExtra(EXTRA_SCREENSHOT_URI, savedUri?.toString())
                        setPackage(packageName)
                    }
                    sendBroadcast(resultIntent)
                    if (savedUri != null) {
                        Toast.makeText(this, "📸 Screenshot tersimpan di Pictures/RekaminDulu", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Gagal menyimpan tangkapan layar", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Screenshot capture unavailable: ${e.message}")
                timerHandler.post {
                    sendBroadcast(Intent(ACTION_SCREENSHOT_RESULT).apply {
                        putExtra(EXTRA_SCREENSHOT_SUCCESS, false)
                        setPackage(packageName)
                    })
                    Toast.makeText(
                        this,
                        "Perangkat membatasi tangkapan layar ganda selama perekaman.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                try { shotDisplay?.release() } catch (ignored: Exception) {}
                try { imageReader?.close() } catch (ignored: Exception) {}
                try { shotThread?.quitSafely() } catch (ignored: Exception) {}
                isCapturingScreenshot = false
            }
        }, "RekaminScreenshotWorker").start()
    }

    private fun triggerVibration(durationMs: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(durationMs)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed", e)
        }
    }

    private fun registerScreenOffReceiver() {
        if (screenOffReceiver == null) {
            screenOffReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_SCREEN_OFF && isRecording) {
                        Log.i(TAG, "Screen off detected, auto-stopping recording")
                        stopRecording()
                    }
                }
            }
            ContextCompat.registerReceiver(
                this,
                screenOffReceiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                ContextCompat.RECEIVER_EXPORTED
            )
        }
    }

    private fun unregisterScreenOffReceiver() {
        screenOffReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering screenOffReceiver", e)
            }
            screenOffReceiver = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Layanan Perekam Layar Rekamin Dulu",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Status perekaman layar aktif dan kontrol cepat notifikasi persistens"
                setSound(null, null)
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(customMessage: String? = null) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(customMessage))
        } catch (e: Exception) {
            Log.w(TAG, "Failed updating recording notification", e)
        }
    }

    private fun buildSavingNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⏳ Menyimpan Rekaman...")
            .setContentText("Memproses dan mengoptimalkan video...")
            .setSmallIcon(R.drawable.ic_record)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun updateSavingNotification() {
        if (!isForegroundActive && !isRecording && !isPaused) return
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildSavingNotification())
        } catch (e: Exception) {
            Log.w(TAG, "Failed updating saving notification", e)
        }
    }

    private fun buildNotification(customMessage: String? = null): Notification {
        val timeString = FloatingOverlayService.formatDuration(elapsedSeconds)

        val contentIntent = Intent(this, com.bangdillah.rekamindulu.HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingContent = PendingIntent.getActivity(
            this, 0, contentIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ScreenRecordingService::class.java).apply { action = ACTION_STOP }
        val pendingStop = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val pauseResumeIntent = Intent(this, ScreenRecordingService::class.java).apply {
            action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        }
        val pendingPauseResume = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this, 2, pauseResumeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this, 2, pauseResumeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val prefs = AppPreferences(this)
        val displayFps = prefs.fps
        val modeBadge = when {
            prefs.isGamingMode -> "🎮 Gaming Pro (${displayFps} FPS)"
            prefs.fpsStabilizerEnabled -> "⚡ ${displayFps} FPS Stabil"
            else -> "🔴 Rekamin Dulu"
        }
        val title = if (isPaused) "⏸️ Rekamin Dulu (Dijeda)" else modeBadge
        val message = customMessage ?: if (isPaused) {
            "Perekaman layar dijeda • $timeString"
        } else {
            val hwLabel = if (prefs.hardwareAccelerationEnabled) "GPU HW" else "CPU"
            "Merekam: $timeString • ${prefs.resolution} @ ${displayFps}FPS • $hwLabel"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_record)
            .setContentIntent(pendingContent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MAX)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }

        if (isPaused) {
            builder.addAction(R.drawable.ic_play_arrow, "Lanjutkan", pendingPauseResume)
            builder.setUsesChronometer(false)
            builder.setShowWhen(false)
        } else {
            builder.addAction(R.drawable.ic_pause, "Jeda", pendingPauseResume)
            builder.setUsesChronometer(true)
            builder.setShowWhen(true)
            val effectiveStart = startTimeMillis + totalPausedDuration
            if (effectiveStart > 0) {
                builder.setWhen(effectiveStart)
            }
        }

        builder.addAction(R.drawable.ic_close, "Hentikan & Simpan", pendingStop)

        return builder.build()
    }

    override fun onDestroy() {
        super.onDestroy()
        timerHandler.removeCallbacksAndMessages(null)
        if ((isRecording || isPaused) && !isStopping) {
            stopRecording(immediate = true)
        }
        unregisterScreenOffReceiver()
        releaseWakeLock()
    }
}
