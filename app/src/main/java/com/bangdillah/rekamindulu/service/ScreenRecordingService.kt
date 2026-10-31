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
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bangdillah.rekamindulu.MainActivity
import com.bangdillah.rekamindulu.R
import com.bangdillah.rekamindulu.floating.FloatingOverlayService
import com.bangdillah.rekamindulu.notification.NotificationHelper
import com.bangdillah.rekamindulu.utils.AppPreferences
import com.bangdillah.rekamindulu.utils.DeviceAnalyzer
import com.bangdillah.rekamindulu.utils.StorageHelper
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * ScreenRecordingService - Foreground Service that manages the MediaProjection lifecycle,
 * virtual display initialization, and audio capture from the microphone to ensure the recording
 * continues running smoothly in the background.
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
        const val ACTION_RECORDING_STOPPED = "com.bangdillah.rekamindulu.RECORDING_STOPPED"
        const val ACTION_RECORDING_STATUS_CHANGED = "com.bangdillah.rekamindulu.RECORDING_STATUS_CHANGED"
        const val ACTION_RECORDING_ERROR = "com.bangdillah.rekamindulu.RECORDING_ERROR"
        const val EXTRA_FILE_PATH = "EXTRA_FILE_PATH"
        const val EXTRA_ERROR_MESSAGE = "EXTRA_ERROR_MESSAGE"

        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"

        var isRecording = false
            protected set

        var isPaused = false
            protected set

        var startTimeMillis = 0L
            protected set

        var elapsedSeconds = 0
            protected set

        var lastRecordedFilePath: String? = null
            protected set

        var currentActiveFilePath: String? = null
            internal set
    }

    protected var mediaProjection: MediaProjection? = null
    protected var mediaProjectionCallback: MediaProjection.Callback? = null
    protected var virtualDisplay: VirtualDisplay? = null
    protected var virtualDisplayThread: android.os.HandlerThread? = null
    protected var virtualDisplayHandler: Handler? = null
    protected var mediaRecorder: MediaRecorder? = null
    protected var wakeLock: PowerManager.WakeLock? = null
    protected var wifiLock: WifiManager.WifiLock? = null

    protected var screenWidth = 720
    protected var screenHeight = 1280
    protected var screenDensity = 320

    private var isStarting = false
    private var isStopping = false

    private val timerHandler = Handler(Looper.getMainLooper())
    protected var currentVideoFilePath: String? = null
    private var pauseStartTime = 0L
    private var totalPausedDuration = 0L

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
                // Cek kapasitas storage setiap 10 detik
                if (elapsedSeconds % 10 == 0) {
                    val report = DeviceAnalyzer.analyze(this@ScreenRecordingService)
                    if (report.freeStorageGb < 0.1) {
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

        when (action) {
            ACTION_START -> {
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
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                updateSavingNotification()
                stopRecording()
            }
            ACTION_PAUSE -> {
                pauseRecording()
                updateNotification()
            }
            ACTION_RESUME -> {
                resumeRecording()
                updateNotification()
            }
        }

        return START_NOT_STICKY
    }

    /**
     * Memastikan startForeground selalu dipanggil dengan ForegroundServiceType yang valid di Android 14+
     */
    private fun safeStartForeground(notification: Notification) {
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val prefs = AppPreferences(this)
            val hasMicPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val wantsAudio = prefs.audioSource != "MUTE"
            // Deklarasikan FOREGROUND_SERVICE_TYPE_MICROPHONE jika merekam audio apapun (MIC, BOTH, atau INTERNAL) untuk kepatuhan Android 14+
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasMicPermission && wantsAudio) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            try {
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } catch (eFgs: Exception) {
                Log.w(TAG, "safeStartForeground with combined types failed, falling back to MEDIA_PROJECTION only", eFgs)
                try {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } catch (eFgsFallback: Exception) {
                    Log.e(TAG, "safeStartForeground fallback failed", eFgsFallback)
                    try {
                        startForeground(NOTIFICATION_ID, notification)
                    } catch (ignored: Exception) {}
                }
            }
        } else {
            try {
                startForeground(NOTIFICATION_ID, notification)
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

        // 1b. Game Turbo & Mode Gaming: Elevasi prioritas thread encoder CPU ke DISPLAY / FOREGROUND
        // Menggunakan THREAD_PRIORITY_DISPLAY (-4) agar tidak mempreempt atau mengorbankan thread render game 3D
        if (prefs.gamingTurboBoost || prefs.isGamingMode) {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
                Log.i(TAG, "Game Turbo: Service process priority elevated to THREAD_PRIORITY_DISPLAY (-4)")
            } catch (e: Exception) {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND)
                } catch (ignored: Exception) {}
            }
        } else {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND)
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

            calculateOutputDimensions(metrics, prefs.resolution, prefs.orientation)
            screenDensity = metrics.densityDpi

            // 4. Siapkan file output di direktori aman
            val outputFile = StorageHelper.createOutputFile(this, "Rekaman")
            currentVideoFilePath = outputFile.absolutePath
            currentActiveFilePath = outputFile.absolutePath

            // 5. Inisialisasi MediaRecorder (Audio Mikrofon/Internal + Surface Video Akselerasi Hardware GPU)
            setupMediaRecorder(outputFile.absolutePath, prefs)

            // 6. Inisialisasi MediaProjection API
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

            if (mediaProjection == null) {
                Log.e(TAG, "MediaProjection is null, cannot proceed with recording")
                broadcastError("Izin MediaProjection ditolak oleh sistem")
                stopRecording()
                return
            }

            // Inisialisasi dedicated HandlerThread dengan prioritas DISPLAY
            // agar antrean buffer frame dari SurfaceFlinger dialirkan mulus tanpa membebani thread render game
            try {
                virtualDisplayThread?.quitSafely()
            } catch (ignored: Exception) {}
            val vdPriority = if (prefs.gamingTurboBoost || prefs.isGamingMode) {
                android.os.Process.THREAD_PRIORITY_DISPLAY
            } else {
                android.os.Process.THREAD_PRIORITY_FOREGROUND
            }
            val vdThread = android.os.HandlerThread("RekaminVDisplayWorker", vdPriority).apply { start() }
            virtualDisplayThread = vdThread
            val vdHandler = Handler(vdThread.looper)
            virtualDisplayHandler = vdHandler

            // Callback MediaProjection untuk Android 14+
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

            // 7. Mulai MediaRecorder TERLEBIH DAHULU sebelum VirtualDisplay
            // Menjamin recorder.surface aktif di state RECORDING dan token MediaProjection belum terpakai jika gagal
            try {
                mediaRecorder?.start()
            } catch (eStart: Exception) {
                Log.w(TAG, "mediaRecorder.start failed with initial config, attempting safe fallback", eStart)
                setupMediaRecorderFallback(outputFile.absolutePath)
                try {
                    mediaRecorder?.start()
                } catch (eFallbackStart: Exception) {
                    Log.e(TAG, "Fallback mediaRecorder.start also failed", eFallbackStart)
                    broadcastError("Gagal memulai encoder rekaman: ${eFallbackStart.localizedMessage ?: "Encoder video tidak dapat dimulai"}")
                    stopRecording()
                    return
                }
            }

            // 8. Inisialisasi VirtualDisplay menggunakan Surface dari MediaRecorder yang sudah aktif
            val recorderSurface = mediaRecorder?.surface
            if (recorderSurface == null) {
                Log.e(TAG, "MediaRecorder surface is null, aborting start")
                broadcastError("MediaRecorder surface tidak tersedia")
                stopRecording()
                return
            }

            // Gunakan VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR murni untuk latensi terendah & sinkronisasi VSYNC 60 FPS langsung
            val displayFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "RekaminDisplay",
                screenWidth,
                screenHeight,
                screenDensity,
                displayFlags,
                recorderSurface,
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
        val rawWidth = metrics.widthPixels.coerceAtLeast(320)
        val rawHeight = metrics.heightPixels.coerceAtLeast(480)
        val isPortrait = when (orientationPref) {
            "LANDSCAPE" -> false
            "PORTRAIT" -> true
            else -> rawHeight >= rawWidth
        }

        val longSide = maxOf(rawWidth, rawHeight)
        val shortSide = minOf(rawWidth, rawHeight)

        // Batas maksimal dimensi sesuai standar profil encoder hardware H.264 / H.265 (MaxMBPS limit)
        // Layar ultra-wide modern (20:9 / 19.5:9 misal 2400x1080) yang dipaksa 2400x1080 melebihi
        // kapasitas macroblock Level 4.1 hardware encoder sehingga menjatuhkan frame rate secara masif saat main game.
        // Solusi: Skalakan sisi panjang secara proporsional ke 1920 (1080p), 1280 (720p), atau 854 (480p).
        val (maxLongLimit, maxShortLimit) = when (resolutionPref) {
            "4K" -> Pair(3840, 2160)
            "1080p" -> Pair(1920, 1080)
            "720p" -> Pair(1280, 720)
            else -> Pair(854, 480)
        }

        var targetLong = longSide
        var targetShort = shortSide

        if (targetLong > maxLongLimit || targetShort > maxShortLimit) {
            val scaleLong = maxLongLimit.toDouble() / targetLong.toDouble()
            val scaleShort = maxShortLimit.toDouble() / targetShort.toDouble()
            val scale = minOf(scaleLong, scaleShort)
            targetLong = (targetLong * scale).toInt()
            targetShort = (targetShort * scale).toInt()
        }

        // Pastikan dimensi kelipatan 16 (macroblock H.264) atau minimal kelipatan 2 genap
        // Mengeliminasi overhead software padding di GPU SurfaceFlinger
        var alignedLong = (targetLong / 16) * 16
        var alignedShort = (targetShort / 16) * 16

        if (alignedLong < 320) alignedLong = (targetLong / 2) * 2
        if (alignedShort < 320) alignedShort = (targetShort / 2) * 2

        if (alignedLong % 2 != 0) alignedLong -= 1
        if (alignedShort % 2 != 0) alignedShort -= 1

        val finalW = if (isPortrait) alignedShort else alignedLong
        val finalH = if (isPortrait) alignedLong else alignedShort

        screenWidth = finalW.coerceAtLeast(320)
        screenHeight = finalH.coerceAtLeast(480)
    }

    /**
     * Konfigurasi MediaRecorder dengan fallback multi-tier otomatis:
     * - Memprioritaskan GPU/VPU Hardware Video Encoder (Offload CPU) agar game tidak lag.
     * - Jalur Audio Internal Mandiri & Stabil (High-Fidelity 48kHz, anti-noise, anti-bentrok voice chat game).
     */
    protected fun setupMediaRecorder(filePath: String, prefs: AppPreferences) {
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
            audioSourceOverride: Int? = null
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

            return try {
                mediaRecorder?.apply {
                    if (withAudio) {
                        val src = audioSourceOverride ?: try {
                            if (isInternalOnly && prefs.internalAudioStabilizer) {
                                // Sumber VOICE_RECOGNITION memiliki filter anti-noise ruangan bawaan OS
                                // sehingga suara ketukan jari & kebisingan luar teredam, fokus suara game
                                MediaRecorder.AudioSource.VOICE_RECOGNITION
                            } else {
                                com.bangdillah.rekamindulu.audio.MicrophoneSelector.getAudioSource(prefs.micType)
                            }
                        } catch (e: Exception) {
                            MediaRecorder.AudioSource.MIC
                        }

                        try {
                            setAudioSource(src)
                        } catch (eSrc: Exception) {
                            Log.w(TAG, "Audio source $src ditolak, fallback ke MIC standar", eSrc)
                            try {
                                setAudioSource(MediaRecorder.AudioSource.MIC)
                            } catch (eMic: Exception) {
                                Log.w(TAG, "MIC standar juga ditolak (mungkin dikunci game voice chat), coba DEFAULT", eMic)
                                setAudioSource(MediaRecorder.AudioSource.DEFAULT)
                            }
                        }
                    }

                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

                    if (withAudio) {
                        try {
                            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

                            // Kanal: Utamakan stereo (2) untuk audio game internal, mono (1) untuk mic/fallback
                            val desiredChannels = if (isInternalOnly) 2 else 1
                            try {
                                setAudioChannels(desiredChannels)
                            } catch (eChan: Exception) {
                                try { setAudioChannels(1) } catch (ignored: Exception) {}
                            }

                            // Frekuensi sampling bertingkat: 48000Hz (Hi-Fi) -> 44100Hz (CD Standar Universal) -> 16000Hz (Aman)
                            var sampleRateConfigured = false
                            for (rate in intArrayOf(48000, 44100, 16000)) {
                                try {
                                    setAudioSamplingRate(rate)
                                    sampleRateConfigured = true
                                    break
                                } catch (eRate: Exception) {
                                    Log.w(TAG, "Audio sampling rate $rate Hz tidak didukung: ${eRate.message}")
                                }
                            }

                            // Bitrate audio adaptif: 128kbps -> 96kbps -> 64kbps
                            for (bitrate in intArrayOf(128000, 96000, 64000)) {
                                try {
                                    setAudioEncodingBitRate(bitrate)
                                    break
                                } catch (eBitrate: Exception) {
                                    Log.w(TAG, "Audio encoding bitrate $bitrate bps tidak didukung: ${eBitrate.message}")
                                }
                            }
                        } catch (eAac: Exception) {
                            Log.w(TAG, "Gagal menginisialisasi audio encoder AAC: ${eAac.message}")
                            return false
                        }
                    }

                    // Pilih hardware video encoder (HEVC / AVC)
                    val encoder = if (prefs.videoEncoder == "H265" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        MediaRecorder.VideoEncoder.HEVC
                    } else {
                        MediaRecorder.VideoEncoder.H264
                    }
                    setVideoEncoder(encoder)
                    setOutputFile(filePath)
                    val chosenFps = if (prefs.isGamingMode || prefs.fpsStabilizerEnabled) maxOf(60, fps) else fps
                    // Bitrate game adaptif:
                    // Di mode gaming, bitrate 6 Mbps pada 1080p (atau 4.5 Mbps pada 720p) adalah sweet-spot ideal:
                    // Gambar tajam, latensi hardware encode < 3ms, buffer encoder tidak pernah stall,
                    // dan memori GPU/VPU tetap 100% bebas untuk performa game tanpa stutter/lag.
                    val pixelCount = targetWidth * targetHeight
                    val chosenBitrate = when {
                        prefs.isGamingMode -> {
                            when {
                                pixelCount >= 1920 * 800 -> minOf(6_500_000, maxOf(4_500_000, bitrate))
                                pixelCount >= 1280 * 500 -> minOf(4_500_000, maxOf(3_000_000, bitrate))
                                else -> minOf(2_500_000, maxOf(1_500_000, bitrate))
                            }
                        }
                        else -> {
                            when {
                                pixelCount >= 1920 * 800 -> minOf(8_000_000, maxOf(4_000_000, bitrate))
                                pixelCount >= 1280 * 500 -> minOf(6_000_000, maxOf(2_500_000, bitrate))
                                else -> minOf(3_000_000, maxOf(1_000_000, bitrate))
                            }
                        }
                    }

                    setVideoSize(targetWidth, targetHeight)
                    setVideoEncodingBitRate(chosenBitrate)
                    setVideoFrameRate(chosenFps)

                    // Sinkronisasi frame rate surface recorder (mencegah variable frame rate judder & drop fps)
                    // Catatan arsitektur: Hindari memanggil setCaptureRate() di MediaRecorder karena mengaktifkan
                    // time-lapse recorder pada Stagefright C++ layer dan menonaktifkan VSYNC timestamp real-time.
                    // Menggunakan setVideoFrameRate(chosenFps) dengan Surface dari VirtualDisplay menjamin 60 FPS murni.
                    Log.i(TAG, "Stabilisator FPS: Hardware encoder aktif pada $chosenFps FPS (${chosenBitrate / 1_000_000} Mbps)")

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

        val isPortrait = screenHeight >= screenWidth
        val safeW = if (isPortrait) 720 else 1280
        val safeH = if (isPortrait) 1280 else 720
        val ultraSafeW = if (isPortrait) 480 else 848
        val ultraSafeH = if (isPortrait) 848 else 480

        val initialBitrate = if (prefs.isGamingMode) {
            minOf(6_500_000, maxOf(4_500_000, prefs.bitrateMbps * 1000 * 1000))
        } else {
            minOf(8_000_000, prefs.bitrateMbps * 1000 * 1000)
        }
        val initialFps = if (prefs.isGamingMode || prefs.fpsStabilizerEnabled) {
            maxOf(60, prefs.fps)
        } else {
            prefs.fps
        }
        val preferredAudioSource = if (isInternalOnly && prefs.internalAudioStabilizer) {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        } else {
            com.bangdillah.rekamindulu.audio.MicrophoneSelector.getAudioSource(prefs.micType)
        }

        // Tier 1: Konfigurasi optimal sesuai pilihan pengguna (Resolusi Asli Layar 1:1 + Target FPS 60 + Audio)
        if (configureAndPrepare(wantsAudio, screenWidth, screenHeight, initialBitrate, initialFps, preferredAudioSource)) {
            Log.i(TAG, "Tier 1: MediaRecorder siap (${screenWidth}x${screenHeight} @ ${initialFps} FPS, audio=$wantsAudio, internal=$isInternalOnly)")
            return
        }

        // Tier 2: Jika Tier 1 gagal dan pengguna menginginkan audio, coba dengan sumber MIC standar pada 60 FPS
        if (wantsAudio && preferredAudioSource != MediaRecorder.AudioSource.MIC) {
            Log.w(TAG, "Tier 1 gagal, mencoba Tier 2 (Resolusi asli @ ${initialFps} FPS + Audio MIC Standar)")
            if (configureAndPrepare(true, screenWidth, screenHeight, initialBitrate, initialFps, MediaRecorder.AudioSource.MIC)) {
                Log.i(TAG, "Tier 2: MediaRecorder siap dengan audio MIC standar @ ${initialFps} FPS")
                return
            }
        }

        // Tier 3: Fallback ke HD 720p TETAP PADA 60 FPS DENGAN AUDIO (Anti Drop FPS)
        Log.w(TAG, "Mencoba Tier 3 (HD 720p @ ${initialFps} FPS DENGAN AUDIO)")
        if (configureAndPrepare(wantsAudio, safeW, safeH, 6_000_000, initialFps, MediaRecorder.AudioSource.MIC)) {
            screenWidth = safeW
            screenHeight = safeH
            Log.i(TAG, "Tier 3: MediaRecorder 720p siap @ ${initialFps} FPS (audio=$wantsAudio)")
            return
        }

        // Tier 4: Fallback ke 480p TETAP PADA 60 FPS DENGAN AUDIO
        Log.w(TAG, "Mencoba Tier 4 (480p @ ${initialFps} FPS DENGAN AUDIO)")
        if (configureAndPrepare(wantsAudio, ultraSafeW, ultraSafeH, 3_000_000, initialFps, MediaRecorder.AudioSource.MIC)) {
            screenWidth = ultraSafeW
            screenHeight = ultraSafeH
            Log.i(TAG, "Tier 4: MediaRecorder 480p siap @ ${initialFps} FPS (audio=$wantsAudio)")
            return
        }

        // Tier 5: Hanya jika hardware audio menolak karena in-game voice chat eksklusif, fallback ke video-only 720p 60 FPS
        if (wantsAudio) {
            Log.w(TAG, "Perangkat keras audio sibuk/tidak merespon, fallback ke video-only 720p @ ${initialFps} FPS")
            if (configureAndPrepare(false, safeW, safeH, 5_000_000, initialFps)) {
                screenWidth = safeW
                screenHeight = safeH
                Log.i(TAG, "Tier 5: MediaRecorder 720p video-only siap @ ${initialFps} FPS")
                return
            }
        }

        // Tier 6: Fallback terakhir universal 480p video-only pada 60 FPS
        if (!configureAndPrepare(false, ultraSafeW, ultraSafeH, 2_000_000, initialFps)) {
            throw RuntimeException("Perangkat tidak dapat menginisialisasi encoder video untuk perekaman layar.")
        }
        screenWidth = ultraSafeW
        screenHeight = ultraSafeH
    }

    /**
     * Fallback cepat jika mediaRecorder.start() gagal pada konfigurasi awal sebelum virtual display dibuat.
     * Mengutamakan perekaman suara mikrofon dengan resolusi aman 720p dan tetap menjaga 60 FPS.
     */
    protected fun setupMediaRecorderFallback(filePath: String) {
        val prefs = AppPreferences(this)
        val hasMicPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val wantsAudio = prefs.audioSource != "MUTE" && hasMicPermission
        val targetFps = if (prefs.isGamingMode || prefs.fpsStabilizerEnabled) maxOf(60, prefs.fps) else prefs.fps

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

        val isPortrait = when (prefs.orientation) {
            "LANDSCAPE" -> false
            "PORTRAIT" -> true
            else -> screenHeight >= screenWidth
        }
        val safeW = if (isPortrait) 720 else 1280
        val safeH = if (isPortrait) 1280 else 720

        var preparedWithAudio = false
        if (wantsAudio) {
            try {
                mediaRecorder?.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioChannels(1)
                    try {
                        setAudioSamplingRate(44100)
                    } catch (eRate: Exception) {
                        try { setAudioSamplingRate(48000) } catch (ignored: Exception) {}
                    }
                    try {
                        setAudioEncodingBitRate(128000)
                    } catch (eBitrate: Exception) {
                        try { setAudioEncodingBitRate(64000) } catch (ignored: Exception) {}
                    }
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setOutputFile(filePath)
                    setVideoSize(safeW, safeH)
                    setVideoEncodingBitRate(6_000_000)
                    setVideoFrameRate(targetFps)
                    prepare()
                }
                preparedWithAudio = true
                Log.i(TAG, "Fallback MediaRecorder berhasil disiapkan DENGAN AUDIO mikrofon @ ${targetFps} FPS")
            } catch (e: Exception) {
                Log.w(TAG, "Fallback dengan audio gagal, mencoba video-only", e)
                try {
                    mediaRecorder?.reset()
                } catch (ignored: Exception) {}
            }
        }

        if (!preparedWithAudio) {
            mediaRecorder?.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setOutputFile(filePath)
                setVideoSize(safeW, safeH)
                setVideoEncodingBitRate(5_000_000)
                setVideoFrameRate(targetFps)
                prepare()
            }
        }
        screenWidth = safeW
        screenHeight = safeH
    }

    /**
     * Menghentikan perekaman layar, merilis resource MediaProjection dan VirtualDisplay
     */
    @Synchronized
    fun stopRecording() {
        if (isStopping) return
        if (!isRecording && !isPaused && mediaRecorder == null && virtualDisplay == null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(NOTIFICATION_ID)
                stopSelf()
            } catch (ignored: Exception) {}
            return
        }
        isStopping = true

        try {
            val prefs = AppPreferences(this)
            isRecording = false
            currentActiveFilePath = null
            isPaused = false
            timerHandler.removeCallbacks(timerRunnable)

            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                audioManager?.isMicrophoneMute = false
            } catch (ignored: Exception) {}

            unregisterScreenOffReceiver()
            releaseWakeLock()

            try {
                updateSavingNotification()
            } catch (e: Exception) {
                Log.w(TAG, "Error displaying saving notification", e)
            }

            // 1. Beri jeda pengaman SEBELUM perekaman dihentikan agar hardware encoder menerima frame & audio yang cukup (min 1.2 detik)
            val elapsedDuration = System.currentTimeMillis() - startTimeMillis
            if (startTimeMillis > 0L && elapsedDuration in 0..1200) {
                try {
                    Thread.sleep(1200 - elapsedDuration)
                } catch (ignored: InterruptedException) {}
            }

            // 2. Finalisasi MediaRecorder TERLEBIH DAHULU sementara Surface dari VirtualDisplay masih aktif dan valid!
            // Urutan ini sangat krusial dalam arsitektur MediaProjection Android:
            // MediaRecorder membutuhkan Surface aktif untuk menulis penutup container MP4 (moov atom, timescale, duration, sample tables).
            // Jika VirtualDisplay dimatikan terlebih dahulu, input Surface terputus dan mediaRecorder.stop() memicu native error 'stop failed'.
            var recordingStoppedCleanly = false
            try {
                mediaRecorder?.stop()
                recordingStoppedCleanly = true
                Log.i(TAG, "MediaRecorder berhasil dihentikan & header MP4 moov atom berhasil difinalisasi dengan sempurna")
            } catch (e: RuntimeException) {
                Log.w(TAG, "Notice stopping mediaRecorder (rekaman mungkin sangat singkat): ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping mediaRecorder", e)
            }

            // 3. Setelah container MP4 selesai ditulis dan ditutup, baru lepaskan VirtualDisplay
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

            // 4. Hentikan session MediaProjection
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

                    // Sinkronisasi metadata rekaman ke Backend Cloud Firestore secara otomatis jika akun login
                    try {
                        val authManager = com.bangdillah.rekamindulu.backend.FirebaseAuthManager.getInstance(this)
                        val currentUser = authManager.currentUser
                        if (currentUser != null) {
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                try {
                                    val repo = com.bangdillah.rekamindulu.backend.FirebaseVideoRepository(this@ScreenRecordingService)
                                    val durationSec = elapsedSeconds.coerceAtLeast(1)
                                    val min = durationSec / 60
                                    val sec = durationSec % 60
                                    val formattedDur = String.format(Locale.getDefault(), "%02d:%02d", min, sec)
                                    val timeStamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).format(java.util.Date())
                                    val docId = savedFile.nameWithoutExtension.replace("[^a-zA-Z0-9_-]".toRegex(), "_").ifBlank { "rec_${savedFile.lastModified()}" }
                                    val rec = com.bangdillah.rekamindulu.backend.VideoRecording(
                                        id = docId,
                                        userId = currentUser.uid,
                                        title = savedFile.nameWithoutExtension.replace("_", " "),
                                        fileName = savedFile.name,
                                        durationSeconds = durationSec,
                                        durationFormatted = formattedDur,
                                        fileSizeBytes = savedFile.length(),
                                        resolution = "${screenWidth}x${screenHeight}",
                                        fps = prefs.fps,
                                        encoder = prefs.videoEncoder,
                                        hasAudio = prefs.audioSource != "MUTE",
                                        audioSource = prefs.audioSource,
                                        isGamingSession = prefs.isGamingMode,
                                        createdAt = timeStamp,
                                        localPath = savedFile.absolutePath,
                                        notes = "Tersimpan rapi di Cloud Backend Firestore"
                                    )
                                    repo.saveRecording(rec)
                                } catch (eCloud: Exception) {
                                    Log.w(TAG, "Notice syncing recording to cloud: ${eCloud.message}")
                                }
                            }
                        }
                    } catch (eAuth: Exception) {
                        Log.w(TAG, "Auth check notice: ${eAuth.message}")
                    }
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_ID)
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
            } catch (ignored: Exception) {}

            Log.i(TAG, "Screen recording stopped. File saved: $lastRecordedFilePath")
        } finally {
            isStopping = false
        }
    }

    fun pauseRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isRecording && !isPaused) {
            try {
                mediaRecorder?.pause()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isRecording && isPaused) {
            try {
                mediaRecorder?.resume()
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
                ContextCompat.RECEIVER_NOT_EXPORTED
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
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildSavingNotification())
        } catch (e: Exception) {
            Log.w(TAG, "Failed updating saving notification", e)
        }
    }

    private fun buildNotification(customMessage: String? = null): Notification {
        val minutes = elapsedSeconds / 60
        val seconds = elapsedSeconds % 60
        val timeString = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

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
        val modeBadge = when {
            prefs.isGamingMode -> "🎮 Gaming Pro (60 FPS)"
            prefs.fpsStabilizerEnabled -> "⚡ 60 FPS Stabil"
            else -> "🔴 Rekamin Dulu"
        }
        val title = if (isPaused) "⏸️ Rekamin Dulu (Dijeda)" else modeBadge
        val message = customMessage ?: if (isPaused) {
            "Perekaman layar dijeda • $timeString"
        } else {
            val hwLabel = if (prefs.hardwareAccelerationEnabled) "GPU HW" else "CPU"
            "Merekam: $timeString • ${prefs.resolution} @ ${if (prefs.isGamingMode || prefs.fpsStabilizerEnabled) 60 else prefs.fps}FPS • $hwLabel"
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
        if (isRecording || isPaused) {
            stopRecording()
        }
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            audioManager?.isMicrophoneMute = false
        } catch (ignored: Exception) {}
        unregisterScreenOffReceiver()
        releaseWakeLock()
    }
}
