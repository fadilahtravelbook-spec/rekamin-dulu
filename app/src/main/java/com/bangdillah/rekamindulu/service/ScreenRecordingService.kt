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
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
        const val ACTION_FACECAM_FGS_UPDATED = "ACTION_FACECAM_FGS_UPDATED"

        const val ACTION_RECORDING_STOPPED = "ACTION_RECORDING_STOPPED"
        const val ACTION_RECORDING_STATUS_CHANGED = "ACTION_RECORDING_STATUS_CHANGED"
        const val ACTION_RECORDING_ERROR = "ACTION_RECORDING_ERROR"
        const val ACTION_SCREENSHOT_RESULT = "ACTION_SCREENSHOT_RESULT"

        const val EXTRA_FILE_PATH = "EXTRA_FILE_PATH"
        const val EXTRA_ERROR_MESSAGE = "EXTRA_ERROR_MESSAGE"
        const val EXTRA_STOPPED_BY_ERROR = "EXTRA_STOPPED_BY_ERROR"
        const val EXTRA_MIC_MUTED = "EXTRA_MIC_MUTED"
        const val EXTRA_CAMERA_FGS_ACTIVE = "EXTRA_CAMERA_FGS_ACTIVE"
        const val EXTRA_REQUEST_FACECAM_OPEN = "EXTRA_REQUEST_FACECAM_OPEN"
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
        var effectiveAudioMode: String? = null
            internal set

        @Volatile
        internal var activeRelayInstance: ProjectionSurfaceRelay? = null

        data class CaptureTelemetry(
            val fps: Int,
            val jitterMs: Double,
            val lastFrameTimeNanos: Long
        )

        fun getLiveCaptureTelemetry(): CaptureTelemetry? {
            val relay = activeRelayInstance ?: return null
            return CaptureTelemetry(
                fps = relay.currentCaptureFps,
                jitterMs = relay.currentCaptureJitterMs,
                lastFrameTimeNanos = relay.lastCaptureFrameTimeNanos
            )
        }

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

        /**
         * Rekaman hanya dianggap valid jika MediaMuxer benar-benar terfinalisasi
         * dan file output ada dengan ukuran memadai (> 10 KB).
         */
        fun isValidFinalizedRecording(
            muxerFinalized: Boolean,
            fileExists: Boolean,
            fileLengthBytes: Long
        ): Boolean = muxerFinalized && fileExists && fileLengthBytes > 10240L

        enum class ResizeAction {
            IGNORE_INVALID_OR_UNCHANGED,
            UPDATE_VIRTUAL_DISPLAY_AND_RELAY,
            RECONFIGURE_ENCODER_AND_VIRTUAL_DISPLAY,
            STOP_UNSUPPORTED_ENCODER_RESIZE
        }

        data class ResizeEvaluation(
            val action: ResizeAction,
            val targetWidth: Int,
            val targetHeight: Int,
            val targetDensityDpi: Int
        )

        /**
         * Menentukan tindakan konsisten saat ukuran jendela/layar yang direkam berubah
         * (misalnya dari MediaProjection.Callback.onCapturedContentResize di Android 14+).
         */
        fun evaluateCapturedResize(
            rawWidth: Int,
            rawHeight: Int,
            baseDensityDpi: Int,
            resolutionPref: String,
            orientationPref: String,
            currentEncoderWidth: Int,
            currentEncoderHeight: Int,
            currentVirtualDisplayWidth: Int,
            currentVirtualDisplayHeight: Int,
            currentVirtualDisplayDensityDpi: Int,
            canReconfigureEncoder: Boolean
        ): ResizeEvaluation {
            if (rawWidth <= 0 || rawHeight <= 0) {
                return ResizeEvaluation(
                    action = ResizeAction.IGNORE_INVALID_OR_UNCHANGED,
                    targetWidth = currentEncoderWidth,
                    targetHeight = currentEncoderHeight,
                    targetDensityDpi = currentVirtualDisplayDensityDpi
                )
            }

            val target = RecordingEngineOptimizer.calculateCapturedDimensions(
                rawWidth = rawWidth,
                rawHeight = rawHeight,
                baseDensityDpi = baseDensityDpi,
                resolutionPref = resolutionPref,
                orientationPref = orientationPref
            )

            val encoderMatches = (target.width == currentEncoderWidth && target.height == currentEncoderHeight)
            val virtualDisplayMatches = (
                target.width == currentVirtualDisplayWidth &&
                    target.height == currentVirtualDisplayHeight &&
                    target.densityDpi == currentVirtualDisplayDensityDpi
                )

            val action = when {
                encoderMatches && virtualDisplayMatches -> ResizeAction.IGNORE_INVALID_OR_UNCHANGED
                encoderMatches -> ResizeAction.UPDATE_VIRTUAL_DISPLAY_AND_RELAY
                canReconfigureEncoder -> ResizeAction.RECONFIGURE_ENCODER_AND_VIRTUAL_DISPLAY
                else -> ResizeAction.STOP_UNSUPPORTED_ENCODER_RESIZE
            }

            return ResizeEvaluation(
                action = action,
                targetWidth = target.width,
                targetHeight = target.height,
                targetDensityDpi = target.densityDpi
            )
        }

        fun buildUnsupportedResizeErrorMessage(
            oldWidth: Int,
            oldHeight: Int,
            newWidth: Int,
            newHeight: Int
        ): String {
            return "Perekaman dihentikan karena ukuran jendela aplikasi atau orientasi layar berubah " +
                "(${oldWidth}×${oldHeight} → ${newWidth}×${newHeight}) dan encoder video tidak mendukung " +
                "perubahan resolusi di tengah rekaman."
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var surfaceRelay: ProjectionSurfaceRelay? = null
    private var codecRecorder: MediaCodecScreenRecorder? = null
    private var outputFile: File? = null
    private var notificationHelper: NotificationHelper? = null
    private val isStopping = AtomicBoolean(false)
    @Volatile
    private var pendingStartIntent: Intent? = null
    private var screenOffReceiverRegistered = false
    private var resizeExecutor: ExecutorService? = null

    @Volatile
    private var baseCaptureDensityDpi: Int = 0
    @Volatile
    private var virtualDisplayWidth: Int = 0
    @Volatile
    private var virtualDisplayHeight: Int = 0
    @Volatile
    private var virtualDisplayDensityDpi: Int = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onCapturedContentResize(width: Int, height: Int) {
            Log.i(TAG, "MediaProjection onCapturedContentResize: ${width}x${height}")
            handleCapturedContentResize(width, height)
        }

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

    private var healthCheckTickCount = 0

    private val notificationUpdateRunnable = object : Runnable {
        override fun run() {
            if (isRecording && !isStopping.get()) {
                updateForegroundNotification()
                healthCheckTickCount++
                if (!isPaused && healthCheckTickCount % 3 == 0) {
                    checkActiveRecordingHealthAsync()
                }
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    private fun checkActiveRecordingHealthAsync() {
        val currentFile = outputFile ?: return
        val executor = resizeExecutor ?: return
        try {
            executor.execute {
                if (!isRecording || isStopping.get()) return@execute
                val freeBytes = RecordingEngineOptimizer.getAvailableStorageBytes(this)
                val fileBytes = try { currentFile.length() } catch (_: Exception) { 0L }
                when (RecordingEngineOptimizer.evaluateActiveRecordingHealth(freeBytes, fileBytes)) {
                    RecordingEngineOptimizer.RecordingHealthStatus.LOW_STORAGE_STOP -> {
                        Log.w(TAG, "Low storage detected during recording (free=$freeBytes); stopping cleanly")
                        stopRecordingInternal(
                            stopErrorMessage = "Penyimpanan hampir penuh (< 80 MB). Perekaman dihentikan aman agar file video tetap utuh.",
                            stopNoticeTitle = "Rekaman Dihentikan (Penyimpanan Hampir Penuh)"
                        )
                    }
                    RecordingEngineOptimizer.RecordingHealthStatus.MAX_FILE_SIZE_STOP -> {
                        Log.w(TAG, "Max safe MP4 size reached (size=$fileBytes); stopping cleanly")
                        stopRecordingInternal(
                            stopErrorMessage = "Ukuran rekaman mendekati batas aman 4 GB. Perekaman dihentikan agar video tidak rusak.",
                            stopNoticeTitle = "Rekaman Disimpan (Batas Ukuran Aman 4 GB)"
                        )
                    }
                    RecordingEngineOptimizer.RecordingHealthStatus.OK -> Unit
                }
            }
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)
        resizeExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            isRecording && !isStopping.get()
        ) {
            val metrics = obtainDisplayMetrics()
            handleCapturedContentResize(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
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
            ACTION_REFRESH_FOREGROUND_TYPE -> {
                if (isRecording && !isStopping.get()) {
                    val notifyFacecam = intent.getBooleanExtra(EXTRA_REQUEST_FACECAM_OPEN, false)
                    startOrUpdateForegroundService(isInitialStart = false, broadcastFacecamResult = notifyFacecam)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        if (isStopping.get()) {
            Log.i(TAG, "Finalizing previous recording; queuing incoming ACTION_START")
            pendingStartIntent = Intent(intent)
            return
        }
        if (isRecording) {
            Log.w(TAG, "Recording already active; ignoring duplicate ACTION_START")
            return
        }

        // Kosongkan path rekaman sesi sebelumnya sebelum memulai sesi baru
        lastRecordedFilePath = null
        currentActiveFilePath = null
        outputFile = null
        MainActivity.hasPendingSaveDialog = false

        // Harus masuk ke foreground terlebih dahulu sebelum memanggil getMediaProjection (Android 14+)
        startOrUpdateForegroundService(isInitialStart = true, broadcastFacecamResult = false)

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
            baseCaptureDensityDpi = metrics.densityDpi
            val inputSurface = recorder.prepare(metrics)
            codecRecorder = recorder

            val relaySurface = try {
                ProjectionSurfaceRelay(
                    encoderSurface = inputSurface,
                    width = recorder.actualWidth,
                    height = recorder.actualHeight
                ).also { relay ->
                    surfaceRelay = relay
                    activeRelayInstance = relay
                }.prepare()
            } catch (e: Exception) {
                Log.w(TAG, "ProjectionSurfaceRelay fallback ke inputSurface langsung: ${e.message}")
                try { surfaceRelay?.release() } catch (_: Exception) {}
                surfaceRelay = null
                activeRelayInstance = null
                inputSurface
            }

            virtualDisplay = projection.createVirtualDisplay(
                "RekaminDuluDisplay",
                recorder.actualWidth,
                recorder.actualHeight,
                recorder.actualDensityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                relaySurface,
                null,
                mainHandler
            )
            virtualDisplayWidth = recorder.actualWidth
            virtualDisplayHeight = recorder.actualHeight
            virtualDisplayDensityDpi = recorder.actualDensityDpi

            recorder.start()

            isStopping.set(false)
            isRecording = true
            isPaused = false
            isMicMuted = false
            effectiveAudioMode = recorder.effectiveAudioMode
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
        surfaceRelay?.setPaused(true)
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
        surfaceRelay?.setPaused(false)
        codecRecorder?.resume()
        updateForegroundNotification()
        broadcastStatusChanged()
    }

    private fun handleSetMicMuted(muted: Boolean) {
        if (!isRecording) return
        val effectiveMode = effectiveAudioMode ?: codecRecorder?.effectiveAudioMode ?: AppPreferences(this).audioSource
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
        if (!isRecording || mediaProjection == null) {
            mainHandler.post {
                Toast.makeText(this, "Perekaman layar belum aktif untuk tangkap layar", Toast.LENGTH_SHORT).show()
            }
            broadcastScreenshotResult(success = false, uriString = null)
            return
        }

        val relay = surfaceRelay
        if (relay == null) {
            mainHandler.post {
                Toast.makeText(this, "Permukaan tangkap layar aktif belum siap", Toast.LENGTH_SHORT).show()
            }
            broadcastScreenshotResult(success = false, uriString = null)
            return
        }

        relay.captureBitmap { bitmap ->
            if (bitmap == null) {
                mainHandler.post {
                    Toast.makeText(this, "Gagal mengambil frame dari permukaan rekaman", Toast.LENGTH_SHORT).show()
                    broadcastScreenshotResult(success = false, uriString = null)
                }
                return@captureBitmap
            }

            Thread({
                var savedUri: android.net.Uri? = null
                try {
                    savedUri = StorageHelper.saveScreenshot(this, bitmap)
                } catch (e: Exception) {
                    Log.e(TAG, "Screenshot save error", e)
                } finally {
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                }

                mainHandler.post {
                    if (savedUri != null) {
                        Toast.makeText(this, "📸 Tangkapan layar disimpan ke Galeri", Toast.LENGTH_SHORT).show()
                        broadcastScreenshotResult(success = true, uriString = savedUri.toString())
                    } else {
                        Toast.makeText(this, "Gagal menyimpan tangkapan layar", Toast.LENGTH_SHORT).show()
                        broadcastScreenshotResult(success = false, uriString = null)
                    }
                }
            }, "RekaminScreenshotSave").start()
        }
    }

    private fun handleCapturedContentResize(
        rawWidth: Int,
        rawHeight: Int,
        densityOverride: Int? = null
    ) {
        if (!isRecording || isStopping.get()) return
        val executor = resizeExecutor ?: Executors.newSingleThreadExecutor().also { resizeExecutor = it }
        try {
            executor.execute {
                if (!isRecording || isStopping.get()) return@execute
                val recorder = codecRecorder ?: return@execute
                val vd = virtualDisplay ?: return@execute
                val prefs = AppPreferences(this)
                val baseDensity = densityOverride
                    ?: baseCaptureDensityDpi.takeIf { it > 0 }
                    ?: obtainDisplayMetrics().densityDpi

                val evaluation = evaluateCapturedResize(
                    rawWidth = rawWidth,
                    rawHeight = rawHeight,
                    baseDensityDpi = baseDensity,
                    resolutionPref = prefs.resolution,
                    orientationPref = prefs.orientation,
                    currentEncoderWidth = recorder.actualWidth,
                    currentEncoderHeight = recorder.actualHeight,
                    currentVirtualDisplayWidth = virtualDisplayWidth,
                    currentVirtualDisplayHeight = virtualDisplayHeight,
                    currentVirtualDisplayDensityDpi = virtualDisplayDensityDpi,
                    canReconfigureEncoder = recorder.canReconfigureVideoSize()
                )

                when (evaluation.action) {
                    ResizeAction.IGNORE_INVALID_OR_UNCHANGED -> return@execute

                    ResizeAction.UPDATE_VIRTUAL_DISPLAY_AND_RELAY -> {
                        try {
                            surfaceRelay?.updateSize(evaluation.targetWidth, evaluation.targetHeight)
                            vd.resize(
                                evaluation.targetWidth,
                                evaluation.targetHeight,
                                evaluation.targetDensityDpi
                            )
                            virtualDisplayWidth = evaluation.targetWidth
                            virtualDisplayHeight = evaluation.targetHeight
                            virtualDisplayDensityDpi = evaluation.targetDensityDpi
                            Log.i(
                                TAG,
                                "VirtualDisplay diperbarui ke ${evaluation.targetWidth}x${evaluation.targetHeight} (${evaluation.targetDensityDpi} dpi)"
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Gagal memperbarui VirtualDisplay saat resize: ${e.message}", e)
                            stopRecordingInternal(
                                stopErrorMessage = buildUnsupportedResizeErrorMessage(
                                    recorder.actualWidth,
                                    recorder.actualHeight,
                                    evaluation.targetWidth,
                                    evaluation.targetHeight
                                )
                            )
                        }
                    }

                    ResizeAction.RECONFIGURE_ENCODER_AND_VIRTUAL_DISPLAY -> {
                        val oldW = recorder.actualWidth
                        val oldH = recorder.actualHeight
                        try {
                            try { vd.setSurface(null) } catch (_: Exception) {}
                            try { surfaceRelay?.release() } catch (_: Exception) {}
                            surfaceRelay = null

                            val newInputSurface = recorder.reconfigureVideoSize(
                                newWidth = evaluation.targetWidth,
                                newHeight = evaluation.targetHeight,
                                newDensityDpi = evaluation.targetDensityDpi,
                                newBitrateBps = recorder.actualBitrateBps
                            )

                            if (newInputSurface == null) {
                                stopRecordingInternal(
                                    stopErrorMessage = buildUnsupportedResizeErrorMessage(
                                        oldW,
                                        oldH,
                                        evaluation.targetWidth,
                                        evaluation.targetHeight
                                    )
                                )
                                return@execute
                            }

                            val newRelaySurface = try {
                                ProjectionSurfaceRelay(
                                    encoderSurface = newInputSurface,
                                    width = recorder.actualWidth,
                                    height = recorder.actualHeight
                                ).also { relay ->
                                    surfaceRelay = relay
                                    activeRelayInstance = relay
                                }.prepare()
                            } catch (e: Exception) {
                                Log.w(TAG, "ProjectionSurfaceRelay fallback setelah reconfigure: ${e.message}")
                                try { surfaceRelay?.release() } catch (_: Exception) {}
                                surfaceRelay = null
                                activeRelayInstance = null
                                newInputSurface
                            }

                            vd.setSurface(newRelaySurface)
                            vd.resize(
                                recorder.actualWidth,
                                recorder.actualHeight,
                                recorder.actualDensityDpi
                            )
                            virtualDisplayWidth = recorder.actualWidth
                            virtualDisplayHeight = recorder.actualHeight
                            virtualDisplayDensityDpi = recorder.actualDensityDpi
                            Log.i(
                                TAG,
                                "VirtualDisplay & encoder diperbarui ke ${recorder.actualWidth}x${recorder.actualHeight}"
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Gagal rekonfigurasi encoder saat resize: ${e.message}", e)
                            stopRecordingInternal(
                                stopErrorMessage = buildUnsupportedResizeErrorMessage(
                                    oldW,
                                    oldH,
                                    evaluation.targetWidth,
                                    evaluation.targetHeight
                                )
                            )
                        }
                    }

                    ResizeAction.STOP_UNSUPPORTED_ENCODER_RESIZE -> {
                        val relay = surfaceRelay
                        if (relay != null) {
                            try {
                                relay.updateSourceContentSize(evaluation.targetWidth, evaluation.targetHeight)
                                vd.resize(
                                    evaluation.targetWidth,
                                    evaluation.targetHeight,
                                    evaluation.targetDensityDpi
                                )
                                virtualDisplayWidth = evaluation.targetWidth
                                virtualDisplayHeight = evaluation.targetHeight
                                virtualDisplayDensityDpi = evaluation.targetDensityDpi
                                Log.i(
                                    TAG,
                                    "Aspect-fit resize aktif di tengah rekaman: sumber ${evaluation.targetWidth}x${evaluation.targetHeight} -> encoder tetap ${recorder.actualWidth}x${recorder.actualHeight}"
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Gagal menerapkan aspect-fit resize: ${e.message}", e)
                                stopRecordingInternal(
                                    stopErrorMessage = buildUnsupportedResizeErrorMessage(
                                        recorder.actualWidth,
                                        recorder.actualHeight,
                                        evaluation.targetWidth,
                                        evaluation.targetHeight
                                    )
                                )
                            }
                        } else {
                            val errorMsg = buildUnsupportedResizeErrorMessage(
                                recorder.actualWidth,
                                recorder.actualHeight,
                                evaluation.targetWidth,
                                evaluation.targetHeight
                            )
                            Log.w(TAG, errorMsg)
                            stopRecordingInternal(stopErrorMessage = errorMsg)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Resize executor tidak menerima tugas: ${e.message}")
        }
    }

    private fun stopRecordingInternal(
        stopErrorMessage: String? = null,
        stopNoticeTitle: String = "Perekaman Dihentikan (Ukuran Berubah)"
    ) {
        if (isStopping.getAndSet(true)) return

        mainHandler.removeCallbacks(notificationUpdateRunnable)
        healthCheckTickCount = 0
        val recorder = codecRecorder
        val targetFile = outputFile
        outputFile = null

        Thread({
            try {
                virtualDisplay?.release()
            } catch (ignored: Exception) {}
            virtualDisplay = null
            virtualDisplayWidth = 0
            virtualDisplayHeight = 0
            virtualDisplayDensityDpi = 0

            activeRelayInstance = null
            try {
                surfaceRelay?.release()
            } catch (ignored: Exception) {}
            surfaceRelay = null

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

            val isValidFile = isValidFinalizedRecording(
                muxerFinalized = muxerWritten,
                fileExists = targetFile?.exists() == true,
                fileLengthBytes = targetFile?.length() ?: 0L
            )
            if (!isValidFile) {
                if (targetFile != null && targetFile.exists()) {
                    try { targetFile.delete() } catch (_: Exception) {}
                }
                lastRecordedFilePath = null
                MainActivity.hasPendingSaveDialog = false
            } else if (targetFile != null) {
                lastRecordedFilePath = targetFile.absolutePath
                StorageHelper.scanVideoFile(this, targetFile)
                if (stopErrorMessage == null) {
                    MainActivity.hasPendingSaveDialog = true
                    notificationHelper?.showRecordingCompletedNotification(targetFile)
                } else {
                    MainActivity.hasPendingSaveDialog = false
                }
            }

            val resolvedErrorMessage = if (stopErrorMessage != null) {
                if (isValidFile && targetFile != null) {
                    "$stopErrorMessage Bagian sebelum perubahan ukuran tetap disimpan (${targetFile.name})."
                } else {
                    stopErrorMessage
                }
            } else if (!isValidFile) {
                "Rekaman gagal difinalisasi dengan benar oleh encoder/muxer. File parsial dihapus."
            } else {
                null
            }

            if (stopErrorMessage != null && resolvedErrorMessage != null) {
                notificationHelper?.showRecordingStoppedWithNoticeNotification(
                    title = stopNoticeTitle,
                    message = resolvedErrorMessage,
                    savedFile = if (isValidFile) targetFile else null
                )
            }

            mainHandler.post {
                if (AppPreferences(this).vibrateOnStart) {
                    triggerServiceVibration(60L)
                }
                unregisterScreenOffReceiver()
                currentActiveFilePath = null
                effectiveAudioMode = null
                baseCaptureDensityDpi = 0
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
                    if (resolvedErrorMessage != null) {
                        putExtra(EXTRA_STOPPED_BY_ERROR, true)
                    }
                })
                if (resolvedErrorMessage != null) {
                    if (stopErrorMessage != null) {
                        Toast.makeText(this, resolvedErrorMessage, Toast.LENGTH_LONG).show()
                    }
                    sendPackageBroadcast(Intent(ACTION_RECORDING_ERROR).apply {
                        putExtra(EXTRA_ERROR_MESSAGE, resolvedErrorMessage)
                    })
                }

                val queuedStart = pendingStartIntent
                pendingStartIntent = null
                if (queuedStart != null) {
                    Log.i(TAG, "Executing queued ACTION_START after previous recording finalized")
                    handleStart(queuedStart)
                } else {
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }, "RekaminStopThread").start()
    }

    private fun startForegroundWithType(notification: Notification, fgsType: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NotificationHelper.NOTIFICATION_ID_RECORDING, notification, fgsType)
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID_RECORDING, notification)
        }
    }

    private fun startOrUpdateForegroundService(
        isInitialStart: Boolean = true,
        broadcastFacecamResult: Boolean = false
    ): Boolean {
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
        var cameraFgsActive = hasCamera && wantsFacecam
        try {
            startForegroundWithType(notification, fgsType)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground gagal dengan tipe $fgsType (initial=$isInitialStart): ${e.message}")
            cameraFgsActive = false
            val fallbackType = resolveForegroundServiceType(
                hasMicPermission = hasMic,
                hasCameraPermission = false,
                wantsAudio = wantsAudio,
                wantsFacecam = false
            )
            try {
                startForegroundWithType(notification, fallbackType)
            } catch (e2: Exception) {
                Log.w(TAG, "Fallback startForeground tanpa kamera gagal: ${e2.message}")
                if (isInitialStart) {
                    val minimalType = resolveForegroundServiceType(
                        hasMicPermission = false,
                        hasCameraPermission = false,
                        wantsAudio = false,
                        wantsFacecam = false
                    )
                    try {
                        startForegroundWithType(notification, minimalType)
                    } catch (e3: Exception) {
                        Log.e(TAG, "Minimal startForeground gagal: ${e3.message}")
                    }
                }
            }
        }

        if (broadcastFacecamResult) {
            sendPackageBroadcast(Intent(ACTION_FACECAM_FGS_UPDATED).apply {
                putExtra(EXTRA_CAMERA_FGS_ACTIVE, cameraFgsActive)
            })
        }
        return cameraFgsActive
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
        val activeAudioMode = effectiveAudioMode ?: codecRecorder?.effectiveAudioMode ?: AppPreferences(this).audioSource
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
            val bounds = wm.maximumWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.configuration.densityDpi.takeIf { it > 0 }
                ?: resources.displayMetrics.densityDpi
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
        lastRecordedFilePath = null
        currentActiveFilePath = null
        MainActivity.hasPendingSaveDialog = false
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
        virtualDisplayWidth = 0
        virtualDisplayHeight = 0
        virtualDisplayDensityDpi = 0
        baseCaptureDensityDpi = 0
        activeRelayInstance = null
        try { surfaceRelay?.release() } catch (_: Exception) {}
        surfaceRelay = null
        try { codecRecorder?.releaseAll() } catch (_: Exception) {}
        codecRecorder = null
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null
        try {
            val leftover = outputFile
            if (leftover != null && leftover.exists()) {
                leftover.delete()
            }
        } catch (_: Exception) {}
        outputFile = null
        lastRecordedFilePath = null
        MainActivity.hasPendingSaveDialog = false
        currentActiveFilePath = null
        effectiveAudioMode = null
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
        pendingStartIntent = null
        try { resizeExecutor?.shutdownNow() } catch (_: Exception) {}
        resizeExecutor = null
    }
}
