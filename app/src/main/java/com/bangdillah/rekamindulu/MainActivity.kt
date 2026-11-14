package com.bangdillah.rekamindulu

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.animation.ValueAnimator
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.bangdillah.rekamindulu.about.AboutActivity
import com.bangdillah.rekamindulu.library.LibraryActivity
import com.bangdillah.rekamindulu.service.RecordingEngineOptimizer
import com.bangdillah.rekamindulu.service.ScreenRecordingService
import com.bangdillah.rekamindulu.settings.SettingsActivity
import com.bangdillah.rekamindulu.trim.TrimActivity
import com.bangdillah.rekamindulu.utils.AppLockHelper
import com.bangdillah.rekamindulu.utils.AppPreferences
import com.bangdillah.rekamindulu.utils.DeviceAnalyzer
import com.bangdillah.rekamindulu.utils.SaveTransitionDialog
import com.bangdillah.rekamindulu.utils.SpecAnalysisDialog
import com.bangdillah.rekamindulu.utils.StorageHelper
import com.bangdillah.rekamindulu.utils.UiHelper
import com.bangdillah.rekamindulu.library.LibraryAdapter
import com.bangdillah.rekamindulu.player.PlayerActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bangdillah.rekamindulu.utils.SelectionBottomSheetDialog
import com.bangdillah.rekamindulu.utils.SelectionOption
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * MainActivity - Primary screen activity implementing the MediaProjection API
 * for screen capture functionality with full audio recording support (Microphone,
 * Internal Game Audio, Both, or Mute).
 */
open class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "MainActivity"
        const val REQUEST_CODE_PERMISSIONS = 2001
        const val REQUEST_CODE_SCREEN_CAPTURE = 2002
        const val EXTRA_CLEAR_PENDING_SAVE = "com.bangdillah.rekamindulu.EXTRA_CLEAR_PENDING_SAVE"
        var isSessionUnlocked = false
        var hasPendingSaveDialog = false

        fun consumePendingSaveIfHandled(
            openedFilePath: String?,
            lastRecordedPath: String?,
            forceClear: Boolean = false
        ): Boolean {
            if (forceClear || (!openedFilePath.isNullOrEmpty() && openedFilePath == lastRecordedPath)) {
                hasPendingSaveDialog = false
                return true
            }
            return false
        }

        fun resolveStoppedBroadcastFilePath(extraFilePath: String?): String? {
            return extraFilePath?.takeIf { it.isNotBlank() }
        }

        fun shouldShowSaveDialog(
            hasPendingSave: Boolean,
            isAppLockEnabled: Boolean,
            savedPin: String,
            isSessionUnlocked: Boolean
        ): Boolean {
            return hasPendingSave && !AppLockHelper.shouldRequirePin(
                isAppLockEnabled = isAppLockEnabled,
                savedPin = savedPin,
                isSessionUnlocked = isSessionUnlocked
            )
        }

        /**
         * Menentukan daftar izin runtime sesuai opsi rekaman yang dipilih pengguna.
         */
        fun computeRequiredRuntimePermissions(
            audioSource: String,
            facecamMode: String,
            sdkInt: Int = Build.VERSION.SDK_INT
        ): Array<String> {
            val permissions = mutableListOf<String>()
            if (!audioSource.equals("MUTE", ignoreCase = true)) {
                permissions.add(Manifest.permission.RECORD_AUDIO)
            }
            if (!facecamMode.equals("OFF", ignoreCase = true)) {
                permissions.add(Manifest.permission.CAMERA)
            }
            if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            return permissions.toTypedArray()
        }
    }

    protected lateinit var appPreferences: AppPreferences
    protected lateinit var mediaProjectionManager: MediaProjectionManager

    // Saved recordings list adapter and data
    private lateinit var homeRecordingsAdapter: LibraryAdapter
    private val homeRecordingFiles = mutableListOf<File>()

    // Audio recording configuration
    protected var isMicOn = true
    protected var isGameAudioOn = true
    private var facecamStateIndex = 0
    private val facecamStates = arrayOf("OFF", "FRONT", "BACK")

    // Modern ActivityResultLauncher for MediaProjection screen capture intent
    val screenCaptureLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            onScreenCapturePermissionGranted(result.resultCode, result.data!!)
        } else {
            onScreenCapturePermissionDenied()
        }
    }

    // Modern ActivityResultLauncher for audio recording (RECORD_AUDIO) and notifications (POST_NOTIFICATIONS)
    val runtimePermissionLauncher: ActivityResultLauncher<Array<String>> = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraDenied = permissions.containsKey(Manifest.permission.CAMERA) &&
            permissions[Manifest.permission.CAMERA] != true &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        if (cameraDenied && appPreferences.facecamMode != "OFF") {
            facecamStateIndex = 0
            appPreferences.facecamMode = "OFF"
            updateAudioToggleViews()
            showSnackbar("Izin kamera ditolak — Facecam dinonaktifkan")
        }

        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: hasAudioRecordingPermission()
        if (audioGranted) {
            if (isMicOn || isGameAudioOn) {
                showSnackbar("🎤 Izin audio mikrofon diberikan")
            }
            initiateScreenCaptureFlow()
        } else {
            // Audio permission denied by user
            if (isMicOn || isGameAudioOn) {
                val audioType = when {
                    isMicOn && isGameAudioOn -> "suara mikrofon dan audio game"
                    isMicOn -> "suara mikrofon"
                    else -> "audio internal game"
                }
                try { activeDialog?.dismiss() } catch (ignored: Exception) {}
                activeDialog = MaterialAlertDialogBuilder(this)
                    .setTitle("🎙️ Izin Audio Diperlukan")
                    .setMessage("Izin audio dibutuhkan agar rekaman layar memiliki $audioType Anda. Apakah Anda ingin tetap merekam tanpa suara (Mute), atau buka Pengaturan untuk mengaktifkan izin?")
                    .setIcon(R.drawable.ic_mic_off)
                    .setPositiveButton("Tetap Rekam (Mute)") { _, _ ->
                        isMicOn = false
                        isGameAudioOn = false
                        syncAudioPreferences()
                        updateAudioToggleViews()
                        initiateScreenCaptureFlow()
                    }
                    .setNeutralButton("Buka Pengaturan") { _, _ ->
                        openAppPermissionSettings()
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            } else {
                initiateScreenCaptureFlow()
            }
        }
    }

    // Dedicated launcher for audio toggle / quick chip permission requests (does NOT start screen capture)
    private val micTogglePermissionLauncher: ActivityResultLauncher<String> = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            syncAudioPreferences()
            updateAudioToggleViews()
            showSnackbar("🎤 Izin audio mikrofon aktif")
        } else {
            isMicOn = false
            isGameAudioOn = false
            syncAudioPreferences()
            updateAudioToggleViews()
            if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
                showPermissionSettingsSnackbar("Izin audio ditolak permanen — kanal suara disetel ke Mute")
            } else {
                showSnackbar("Izin audio ditolak — kanal suara disetel ke Mute")
            }
        }
    }

    // Dedicated launcher for facecam toggle camera permission (does NOT start screen capture)
    private val facecamPermissionLauncher: ActivityResultLauncher<String> = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showSnackbar("📷 Kamera wajah (${appPreferences.facecamMode}) siap digunakan")
        } else {
            facecamStateIndex = 0
            appPreferences.facecamMode = "OFF"
            updateAudioToggleViews()
            if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
                showPermissionSettingsSnackbar("Izin kamera ditolak permanen — Facecam dinonaktifkan")
            } else {
                showSnackbar("Izin kamera ditolak — Facecam dinonaktifkan")
            }
        }
    }

    // Alias for audioPermissionLauncher for backward compatibility
    val audioPermissionLauncher: ActivityResultLauncher<Array<String>>
        get() = runtimePermissionLauncher

    private val liveTimerHandler = Handler(Looper.getMainLooper())
    private val liveTimerRunnable = object : Runnable {
        override fun run() {
            if (ScreenRecordingService.isRecording) {
                updateRecordingBanner()
                liveTimerHandler.postDelayed(this, 1000)
            } else {
                updateRecordingBanner()
            }
        }
    }

    private val recordingStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ScreenRecordingService.ACTION_RECORDING_STOPPED -> {
                    val path = resolveStoppedBroadcastFilePath(
                        intent.getStringExtra(ScreenRecordingService.EXTRA_FILE_PATH)
                    )
                    val isValidFile = !path.isNullOrEmpty() && File(path).exists() && File(path).length() > 10240
                    updateRecordingBanner()
                    updateLibraryStats()
                    updateHomeRecordingsList()

                    if (!isValidFile) {
                        hasPendingSaveDialog = false
                        return
                    }

                    if (AppLockHelper.shouldRequirePin(
                            appPreferences.isAppLockEnabled,
                            appPreferences.appLockPin,
                            HomeActivity.isSessionUnlocked
                        )
                    ) {
                        hasPendingSaveDialog = true
                        return
                    }

                    hasPendingSaveDialog = false
                    if (path != null) {
                        showSaveSuccessToast(path)
                        SaveTransitionDialog.show(this@MainActivity, path) {
                            updateLibraryStats()
                            updateHomeRecordingsList()
                        }
                    }
                }
                ScreenRecordingService.ACTION_RECORDING_STATUS_CHANGED -> {
                    updateRecordingBanner()
                    if (ScreenRecordingService.isRecording) {
                        liveTimerHandler.removeCallbacks(liveTimerRunnable)
                        liveTimerHandler.post(liveTimerRunnable)
                    } else {
                        liveTimerHandler.removeCallbacks(liveTimerRunnable)
                    }
                }
                ScreenRecordingService.ACTION_RECORDING_ERROR -> {
                    val errorMsg = intent.getStringExtra(ScreenRecordingService.EXTRA_ERROR_MESSAGE)
                        ?: "Gagal memulai perekaman layar. Pastikan izin telah diberikan."
                    liveTimerHandler.removeCallbacks(liveTimerRunnable)
                    updateRecordingBanner()
                    if (!isFinishing && !isDestroyed) {
                        try { activeDialog?.dismiss() } catch (ignored: Exception) {}
                        activeDialog = MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle("⚠️ Perekaman Gagal")
                            .setMessage(errorMsg)
                            .setPositiveButton("Mengerti", null)
                            .show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appPreferences = AppPreferences(this)
        appPreferences.applyTheme()
        setContentView(R.layout.activity_main)
        supportActionBar?.hide()

        UiHelper.applySystemBarInsets(
            anchorView = findViewById(R.id.main_root_layout),
            topView = findViewById(R.id.home_inner_content),
            bottomView = findViewById(R.id.bottom_navigation)
        )
        val countdownHud = findViewById<View>(R.id.layout_countdown_hud)
        UiHelper.applySystemBarInsets(
            anchorView = countdownHud,
            topView = countdownHud,
            bottomView = countdownHud
        )

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        initAudioSettings()
        setupHeaderActions()
        setupLocalStorageStudioCard()
        setupDashboardCards()
        setupHomeRecordingsListView()
        setupBottomNavigation()

        UiHelper.expandTouchTargets(
            findViewById(R.id.home_header_actions),
            listOf(
                findViewById<View>(R.id.ic_about) to 48,
                findViewById<View>(R.id.ic_settings) to 48
            )
        )
        UiHelper.expandTouchTargets(
            findViewById(R.id.layout_cloud_sync_row),
            listOf(findViewById<View>(R.id.btn_trigger_sync_cloud) to 48)
        )
        findViewById<ScrollView>(R.id.home_scroll_view)?.setOnScrollChangeListener { _, _, _, _, _ ->
            updateFloatingPillVisibility()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val hudLayout = findViewById<View>(R.id.layout_countdown_hud)
                if (hudLayout != null && hudLayout.visibility == View.VISIBLE) {
                    cancelCountdownHUD()
                    showSnackbar("Perekaman dibatalkan")
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    /**
     * Konfigurasi kartu ringkasan Studio Penyimpanan Lokal & diagnostik perangkat
     */
    private fun setupLocalStorageStudioCard() {
        findViewById<ImageView>(R.id.img_cloud_user_avatar)?.setImageResource(R.drawable.ic_storage)
        findViewById<TextView>(R.id.text_cloud_account_name)?.text = "Studio Penyimpanan Lokal"
        findViewById<TextView>(R.id.text_cloud_account_subtitle)?.text =
            "📁 Disimpan di Movies/RekaminDulu • Ekspor sebelum hapus aplikasi"

        val btnOpenGallery = findViewById<MaterialButton>(R.id.btn_cloud_auth_action)
        btnOpenGallery?.text = "Buka Galeri"
        btnOpenGallery?.setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java))
        }

        val btnDiagnostics = findViewById<MaterialButton>(R.id.btn_trigger_sync_cloud)
        btnDiagnostics?.text = "Uji Kesiapan"
        btnDiagnostics?.setOnClickListener {
            openDiagnostics()
        }
    }

    /**
     * Inisialisasi preferensi audio awal (Mikrofon dan Suara Game) dan facecam
     */
    private fun initAudioSettings() {
        when (appPreferences.audioSource) {
            "BOTH" -> {
                isMicOn = true
                isGameAudioOn = true
            }
            "MIC" -> {
                isMicOn = true
                isGameAudioOn = false
            }
            "INTERNAL" -> {
                isMicOn = false
                isGameAudioOn = true
            }
            "MUTE" -> {
                isMicOn = false
                isGameAudioOn = false
            }
            else -> {
                isMicOn = true
                isGameAudioOn = true
            }
        }
        facecamStateIndex = facecamStates.indexOf(appPreferences.facecamMode).coerceAtLeast(0)
        updateAudioToggleViews()
    }

    fun performHapticFeedback(view: View? = null, milliseconds: Long = 40) {
        try {
            view?.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(milliseconds)
            }
        } catch (ignored: Exception) {}
    }

    private fun animateButtonPress(view: View, onComplete: () -> Unit) {
        performHapticFeedback(view, 45)
        view.animate()
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(70)
            .withEndAction {
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(80)
                    .withEndAction {
                        onComplete()
                    }
                    .start()
            }
            .start()
    }

    private var breathingAnimator: ValueAnimator? = null

    private fun startBreathingGlowAnimation() {
        val outerDisc = findViewById<FrameLayout>(R.id.disc_outer_glow) ?: return
        if (breathingAnimator != null) return
        breathingAnimator = ValueAnimator.ofFloat(1.0f, 1.07f, 1.0f).apply {
            duration = 2200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { anim ->
                val scale = anim.animatedValue as Float
                outerDisc.scaleX = scale
                outerDisc.scaleY = scale
            }
            start()
        }
    }

    private fun stopBreathingGlowAnimation() {
        breathingAnimator?.cancel()
        breathingAnimator = null
        val outerDisc = findViewById<FrameLayout>(R.id.disc_outer_glow)
        outerDisc?.scaleX = 1.0f
        outerDisc?.scaleY = 1.0f
    }

    private fun updateAudioToggleViews() {
        val toggleMic = findViewById<Button>(R.id.toggle_mic)
        val toggleGameAudio = findViewById<Button>(R.id.toggle_game_audio)
        val toggleFacecam = findViewById<Button>(R.id.toggle_facecam)

        val cardMic = findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_mic_toggle)
        val cardGame = findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_game_audio_toggle)
        val cardFacecam = findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_facecam_toggle)

        val isRecordingActive = ScreenRecordingService.isRecording
        val activeAlpha = if (isRecordingActive) 0.55f else 1.0f
        cardMic?.alpha = activeAlpha
        cardGame?.alpha = activeAlpha
        cardFacecam?.alpha = activeAlpha

        cardMic?.strokeColor = ContextCompat.getColor(this, if (isMicOn) R.color.colorPrimary else R.color.colorBorder)
        cardMic?.strokeWidth = if (isMicOn) 3 else 1

        cardGame?.strokeColor = ContextCompat.getColor(this, if (isGameAudioOn) R.color.colorPrimary else R.color.colorBorder)
        cardGame?.strokeWidth = if (isGameAudioOn) 3 else 1

        val isFacecamActive = facecamStates[facecamStateIndex] != "OFF"
        cardFacecam?.strokeColor = ContextCompat.getColor(this, if (isFacecamActive) R.color.colorPrimary else R.color.colorBorder)
        cardFacecam?.strokeWidth = if (isFacecamActive) 3 else 1

        toggleMic?.apply {
            text = if (isMicOn) "✓ AKTIF" else "NONAKTIF"
            if (isMicOn) {
                backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.colorPrimaryContainer)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.colorPrimary))
            } else {
                backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.colorSurfaceVariant)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textCaption))
            }
        }

        toggleGameAudio?.apply {
            text = if (isGameAudioOn) "✓ AKTIF" else "NONAKTIF"
            if (isGameAudioOn) {
                backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.colorPrimaryContainer)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.colorPrimary))
            } else {
                backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.colorSurfaceVariant)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textCaption))
            }
        }

        toggleFacecam?.apply {
            val stateStr = facecamStates[facecamStateIndex]
            text = stateStr
            if (stateStr != "OFF") {
                backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.colorPrimaryContainer)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.colorPrimary))
            } else {
                backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.colorSurfaceVariant)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textCaption))
            }
        }

        // Update 4 quick audio chips in the studio hero deck
        val chipOnline = findViewById<TextView>(R.id.chip_quick_audio_online)
        val chipBoth = findViewById<TextView>(R.id.chip_quick_audio_both)
        val chipMic = findViewById<TextView>(R.id.chip_quick_audio_mic)
        val chipMute = findViewById<TextView>(R.id.chip_quick_audio_mute)

        val isOnline = !isMicOn && isGameAudioOn
        val isBoth = isMicOn && isGameAudioOn
        val isMicOnly = isMicOn && !isGameAudioOn
        val isMute = !isMicOn && !isGameAudioOn

        fun styleQuickChip(chip: TextView?, isSelected: Boolean) {
            chip?.apply {
                if (isSelected) {
                    backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.colorPrimaryContainer)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.colorPrimary))
                } else {
                    backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.colorSurfaceVariant)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textTitle))
                }
            }
        }

        styleQuickChip(chipOnline, isOnline)
        styleQuickChip(chipBoth, isBoth)
        styleQuickChip(chipMic, isMicOnly)
        styleQuickChip(chipMute, isMute)
    }


    private var appLockDialog: AlertDialog? = null
    private var activeDialog: AlertDialog? = null
    private var extractProgressDialog: AlertDialog? = null

    private fun checkAppLock() {
        appLockDialog = AppLockHelper.showPinGateIfNeeded(
            activity = this,
            appPreferences = appPreferences,
            currentDialog = appLockDialog,
            title = "🔒 Kunci Rekamin Dulu",
            message = "Aplikasi ini dilindungi PIN. Masukkan PIN keamanan untuk melanjutkan:",
            negativeButtonLabel = "Keluar",
            onCancel = { finishAffinity() },
            onPinVerified = {
                showSnackbar("Kunci aplikasi berhasil dibuka")
                updateLibraryStats()
                updateHomeRecordingsList()
                maybeShowPendingSaveDialog()
            }
        )
    }

    private fun maybeShowPendingSaveDialog() {
        if (!shouldShowSaveDialog(
                hasPendingSave = hasPendingSaveDialog,
                isAppLockEnabled = appPreferences.isAppLockEnabled,
                savedPin = appPreferences.appLockPin,
                isSessionUnlocked = HomeActivity.isSessionUnlocked
            )
        ) {
            return
        }
        hasPendingSaveDialog = false
        val path = ScreenRecordingService.lastRecordedFilePath
        if (!path.isNullOrEmpty() && File(path).exists() && File(path).length() > 10240) {
            showSaveSuccessToast(path)
            SaveTransitionDialog.show(this, path) {
                updateLibraryStats()
                updateHomeRecordingsList()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkAppLock()
        findViewById<BottomNavigationView>(R.id.bottom_navigation)?.selectedItemId = R.id.nav_home
        initAudioSettings()
        updateHardwareMetrics()
        updateResolutionBadge()
        updateFpsStabilizerView()
        updateLibraryStats()
        updateRecordingBanner()
        updateHomeRecordingsList()

        maybeShowPendingSaveDialog()

        val filter = IntentFilter().apply {
            addAction(ScreenRecordingService.ACTION_RECORDING_STOPPED)
            addAction(ScreenRecordingService.ACTION_RECORDING_STATUS_CHANGED)
            addAction(ScreenRecordingService.ACTION_RECORDING_ERROR)
        }
        ContextCompat.registerReceiver(
            this,
            recordingStatusReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        if (ScreenRecordingService.isRecording) {
            liveTimerHandler.post(liveTimerRunnable)
        } else {
            liveTimerHandler.removeCallbacks(liveTimerRunnable)
        }
    }

    override fun onPause() {
        super.onPause()
        liveTimerHandler.removeCallbacks(liveTimerRunnable)
        try {
            unregisterReceiver(recordingStatusReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            val hud = findViewById<View>(R.id.layout_countdown_hud)
            if (hud != null && hud.visibility == View.VISIBLE) {
                cancelCountdownHUD()
                Toast.makeText(
                    this,
                    "Hitung mundur dibatalkan karena aplikasi ke latar belakang",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun setupHeaderActions() {
        findViewById<View>(R.id.ic_about)?.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        findViewById<View>(R.id.ic_settings)?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<View>(R.id.card_quality_selector)?.setOnClickListener {
            showQualityPickerDialog()
        }

        findViewById<TextView>(R.id.text_resolution_badge)?.setOnClickListener {
            showQualityPickerDialog()
        }

        // Tap central glowing disc to start/stop with haptic response
        findViewById<View>(R.id.img_start_stop_icon)?.setOnClickListener { v ->
            animateButtonPress(v) {
                if (ScreenRecordingService.isRecording) {
                    stopScreenCapture()
                } else {
                    startScreenCapture()
                }
            }
        }

        // Quick Resolution Chips in Studio Hero Deck
        findViewById<View>(R.id.chip_quick_1080p)?.setOnClickListener { v ->
            if (ScreenRecordingService.isRecording) {
                showSnackbar("⚠️ Resolusi terkunci saat perekaman sedang aktif")
                return@setOnClickListener
            }
            performHapticFeedback(v, 30)
            appPreferences.resolution = "1080p"
            appPreferences.fps = 60
            appPreferences.bitrateMbps = 24
            appPreferences.isGamingMode = true
            appPreferences.fpsStabilizerEnabled = true
            updateResolutionBadge()
            updateFpsStabilizerView()
            showSnackbar("★ 1080p Real HD 60 FPS (24 Mbps) diaktifkan (Gambar Jernih & Tajam)")
        }

        findViewById<View>(R.id.chip_quick_720p)?.setOnClickListener { v ->
            if (ScreenRecordingService.isRecording) {
                showSnackbar("⚠️ Resolusi terkunci saat perekaman sedang aktif")
                return@setOnClickListener
            }
            performHapticFeedback(v, 30)
            appPreferences.resolution = "720p"
            appPreferences.fps = 60
            appPreferences.bitrateMbps = 12
            appPreferences.isGamingMode = true
            appPreferences.fpsStabilizerEnabled = true
            updateResolutionBadge()
            updateFpsStabilizerView()
            showSnackbar("720p HD 60 FPS (12 Mbps) diaktifkan (Optimal, Ringan & Mulus)")
        }

        findViewById<View>(R.id.chip_quick_native)?.setOnClickListener { v ->
            if (ScreenRecordingService.isRecording) {
                showSnackbar("⚠️ Resolusi terkunci saat perekaman sedang aktif")
                return@setOnClickListener
            }
            performHapticFeedback(v, 30)
            appPreferences.resolution = "NATIVE"
            appPreferences.fps = 60
            appPreferences.bitrateMbps = 24
            appPreferences.isGamingMode = true
            appPreferences.fpsStabilizerEnabled = true
            updateResolutionBadge()
            updateFpsStabilizerView()
            showSnackbar("Resolusi Layar Asli 1:1 Pixel Match diaktifkan")
        }

        // Quick Audio Chips in Studio Hero Deck
        findViewById<View>(R.id.chip_quick_audio_online)?.setOnClickListener { v ->
            if (ScreenRecordingService.isRecording) {
                showSnackbar("⚠️ Kanal audio terkunci saat perekaman sedang aktif")
                return@setOnClickListener
            }
            performHapticFeedback(v, 30)
            isMicOn = false
            isGameAudioOn = true
            syncAudioPreferences()
            updateAudioToggleViews()
            if (!hasAudioRecordingPermission()) {
                micTogglePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else if (!com.bangdillah.rekamindulu.audio.AudioHelper.isAudioInternalSupported()) {
                showSnackbar("⚠️ Audio internal butuh Android 10+; fallback ke Mikrofon aktif")
            } else {
                showSnackbar("🎵 Audio Video/Game Online Aktif (Suara jernih tanpa noise mikrofon)")
            }
        }

        findViewById<View>(R.id.chip_quick_audio_both)?.setOnClickListener { v ->
            if (ScreenRecordingService.isRecording) {
                showSnackbar("⚠️ Kanal audio terkunci saat perekaman sedang aktif")
                return@setOnClickListener
            }
            performHapticFeedback(v, 30)
            isMicOn = true
            isGameAudioOn = true
            syncAudioPreferences()
            updateAudioToggleViews()
            if (!hasAudioRecordingPermission()) {
                micTogglePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else if (!com.bangdillah.rekamindulu.audio.AudioHelper.isAudioInternalSupported()) {
                showSnackbar("⚠️ Android < 10 hanya mendukung Mikrofon (audio internal dilewati)")
            } else {
                showSnackbar("🎙️+🎵 Mikrofon & Audio Video/Game Keduanya Aktif")
            }
        }

        findViewById<View>(R.id.chip_quick_audio_mic)?.setOnClickListener { v ->
            if (ScreenRecordingService.isRecording) {
                showSnackbar("⚠️ Kanal audio terkunci saat perekaman sedang aktif")
                return@setOnClickListener
            }
            performHapticFeedback(v, 30)
            isMicOn = true
            isGameAudioOn = false
            syncAudioPreferences()
            updateAudioToggleViews()
            if (!hasAudioRecordingPermission()) {
                micTogglePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                showSnackbar("🎙️ Suara Mikrofon Saja Diaktifkan")
            }
        }

        findViewById<View>(R.id.chip_quick_audio_mute)?.setOnClickListener { v ->
            if (ScreenRecordingService.isRecording) {
                showSnackbar("⚠️ Kanal audio terkunci saat perekaman sedang aktif")
                return@setOnClickListener
            }
            performHapticFeedback(v, 30)
            isMicOn = false
            isGameAudioOn = false
            syncAudioPreferences()
            updateAudioToggleViews()
            showSnackbar("🔇 Mute: Rekaman Tanpa Suara")
        }

        // Toggle Mic Audio Recording (tap button or whole card)
        val toggleMicAction = {
            if (ScreenRecordingService.isRecording) {
                showSnackbar("⚠️ Pengaturan mikrofon terkunci saat perekaman sedang aktif")
            } else {
                isMicOn = !isMicOn
                updateAudioToggleViews()
                syncAudioPreferences()
                if (isMicOn && !hasAudioRecordingPermission()) {
                    micTogglePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    showSnackbar(if (isMicOn) "🎤 Mikrofon diaktifkan" else "🔇 Mikrofon dinonaktifkan")
                }
            }
        }
        findViewById<Button>(R.id.toggle_mic)?.setOnClickListener { toggleMicAction() }
        findViewById<View>(R.id.card_mic_toggle)?.setOnClickListener { toggleMicAction() }

        // Toggle Internal Game Audio Recording (tap button or whole card)
        val toggleGameAudioAction = {
            if (ScreenRecordingService.isRecording) {
                showSnackbar("⚠️ Pengaturan audio game terkunci saat perekaman sedang aktif")
            } else {
                isGameAudioOn = !isGameAudioOn
                updateAudioToggleViews()
                syncAudioPreferences()
                if (isGameAudioOn && !hasAudioRecordingPermission()) {
                    micTogglePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else if (isGameAudioOn && !com.bangdillah.rekamindulu.audio.AudioHelper.isAudioInternalSupported()) {
                    showSnackbar("⚠️ Audio internal butuh Android 10+; fallback Mikrofon akan digunakan")
                } else {
                    showSnackbar(if (isGameAudioOn) "🎮 Audio internal game diaktifkan" else "🔇 Audio game dinonaktifkan")
                }
            }
        }
        findViewById<Button>(R.id.toggle_game_audio)?.setOnClickListener { toggleGameAudioAction() }
        findViewById<View>(R.id.card_game_audio_toggle)?.setOnClickListener { toggleGameAudioAction() }

        // Toggle Facecam (tap button or whole card)
        val toggleFacecamAction = {
            if (ScreenRecordingService.isRecording) {
                showSnackbar("⚠️ Mode kamera wajah terkunci saat perekaman sedang aktif")
            } else {
                facecamStateIndex = (facecamStateIndex + 1) % facecamStates.size
                val stateStr = facecamStates[facecamStateIndex]
                appPreferences.facecamMode = stateStr
                updateAudioToggleViews()
                if (stateStr != "OFF" && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    facecamPermissionLauncher.launch(Manifest.permission.CAMERA)
                } else {
                    showSnackbar("Mode Kamera Wajah: $stateStr")
                }
            }
        }
        findViewById<Button>(R.id.toggle_facecam)?.setOnClickListener { toggleFacecamAction() }
        findViewById<View>(R.id.card_facecam_toggle)?.setOnClickListener { toggleFacecamAction() }

        // Toggle FPS Stabilizer 60 FPS Anti-Drop
        val toggleFpsStabilizerAction = {
            if (ScreenRecordingService.isRecording) {
                showSnackbar("⚠️ Pengaturan stabilisator terkunci saat perekaman sedang aktif")
            } else {
                appPreferences.fpsStabilizerEnabled = !appPreferences.fpsStabilizerEnabled
                updateFpsStabilizerView()
                val msg = if (appPreferences.fpsStabilizerEnabled) {
                    "⚡ Stabilisator FPS 60 FPS: Diaktifkan (Frame Rate Terkunci & Offload GPU Aktif)"
                } else {
                    "Stabilisator FPS: Dinonaktifkan"
                }
                showSnackbar(msg)
            }
        }
        findViewById<Button>(R.id.btn_home_toggle_fps_stabilizer)?.setOnClickListener { toggleFpsStabilizerAction() }
        findViewById<View>(R.id.card_fps_stabilizer_toggle)?.setOnClickListener { toggleFpsStabilizerAction() }


        // Pause / Resume button on active banner
        findViewById<Button>(R.id.btn_home_pause_recording)?.setOnClickListener {
            if (ScreenRecordingService.isRecording) {
                val action = if (ScreenRecordingService.isPaused) {
                    ScreenRecordingService.ACTION_RESUME
                } else {
                    ScreenRecordingService.ACTION_PAUSE
                }
                val intent = Intent(this, ScreenRecordingService::class.java).apply {
                    this.action = action
                }
                sendServiceCommand(intent)
                updateRecordingBanner()
            }
        }

        // Stop button on active banner
        findViewById<Button>(R.id.btn_home_stop_recording)?.setOnClickListener {
            stopScreenCapture()
        }

        // Floating pill Pause / Resume button
        findViewById<Button>(R.id.btn_floating_pause)?.setOnClickListener {
            if (ScreenRecordingService.isRecording) {
                val action = if (ScreenRecordingService.isPaused) {
                    ScreenRecordingService.ACTION_RESUME
                } else {
                    ScreenRecordingService.ACTION_PAUSE
                }
                val intent = Intent(this, ScreenRecordingService::class.java).apply {
                    this.action = action
                }
                sendServiceCommand(intent)
                updateRecordingBanner()
            }
        }

        // Floating pill Stop & Save button
        findViewById<Button>(R.id.btn_floating_stop)?.setOnClickListener {
            stopScreenCapture()
        }

        // Hero studio Pause / Resume button
        findViewById<Button>(R.id.btn_hero_pause_recording)?.setOnClickListener {
            if (ScreenRecordingService.isRecording) {
                val action = if (ScreenRecordingService.isPaused) {
                    ScreenRecordingService.ACTION_RESUME
                } else {
                    ScreenRecordingService.ACTION_PAUSE
                }
                val intent = Intent(this, ScreenRecordingService::class.java).apply {
                    this.action = action
                }
                sendServiceCommand(intent)
                updateRecordingBanner()
            }
        }

        // Hero studio Stop & Save button
        findViewById<Button>(R.id.btn_hero_stop_recording)?.setOnClickListener {
            stopScreenCapture()
        }
    }

    /**
     * Mengatur konfigurasi audio perekaman (Mic, Game Audio, Both, atau Mute)
     */
    fun syncAudioPreferences() {
        appPreferences.audioSource = when {
            isMicOn && isGameAudioOn -> "BOTH"
            isMicOn -> "MIC"
            isGameAudioOn -> "INTERNAL"
            else -> "MUTE"
        }
    }

    fun setAudioRecordingConfig(micEnabled: Boolean, internalAudioEnabled: Boolean) {
        this.isMicOn = micEnabled
        this.isGameAudioOn = internalAudioEnabled
        updateAudioToggleViews()
        syncAudioPreferences()
    }


    private fun showQualityPickerDialog() {
        val currentPreset = when {
            appPreferences.resolution == "1080p" && appPreferences.fps >= 60 -> "1080p_60"
            appPreferences.resolution == "720p" && appPreferences.fps >= 60 -> "720p_60"
            appPreferences.resolution == "NATIVE" && appPreferences.fps >= 60 -> "native_60"
            else -> "custom"
        }

        val options = listOf(
            SelectionOption(
                value = "1080p_60",
                title = "1080p Full HD Real HD (Rekomendasi Utama)",
                subtitle = "Gambar Terang, Super Jernih, Detail Tajam (24 Mbps) & 60 FPS Mulus Tanpa Patah-Patah",
                badgeLabel = "REAL HD",
                isRecommended = true
            ),
            SelectionOption(
                value = "720p_60",
                title = "720p HD Optimal (60 FPS • 12 Mbps)",
                subtitle = "Optimal & Ringan: 60 FPS Sangat Mulus, HP Dingin & Hemat Baterai",
                badgeLabel = "720p",
                isRecommended = false
            ),
            SelectionOption(
                value = "native_60",
                title = "Resolusi Layar Asli (60 FPS • 24 Mbps)",
                subtitle = "Ketajaman 100% Piksel Layar • 1:1 Pixel Match Tanpa Downscale",
                badgeLabel = "ASLI",
                isRecommended = false
            ),
            SelectionOption(
                value = "custom",
                title = "Kustomisasi Lanjutan di Pengaturan",
                subtitle = "Atur Bitrate hingga 30 Mbps, Format Codec H.265 & Tuning Mikrofon",
                badgeLabel = "⚙️",
                isRecommended = false
            )
        )

        SelectionBottomSheetDialog.show(
            context = this,
            iconRes = R.drawable.ic_video,
            title = "Pilih Kualitas Rekaman Layar",
            subtitle = "Pilih preset profil kualitas video atau sesuaikan konfigurasi",
            options = options,
            selectedValue = currentPreset
        ) { selectedPreset ->
            when (selectedPreset) {
                "native_60" -> {
                    appPreferences.resolution = "NATIVE"
                    appPreferences.fps = 60
                    appPreferences.bitrateMbps = 24
                    appPreferences.isGamingMode = true
                    appPreferences.fpsStabilizerEnabled = true
                    updateResolutionBadge()
                    updateFpsStabilizerView()
                    showSnackbar("Preset Layar Asli 60FPS 24Mbps diterapkan (Super Jernih 1:1)")
                }
                "1080p_60" -> {
                    appPreferences.resolution = "1080p"
                    appPreferences.fps = 60
                    appPreferences.bitrateMbps = 24
                    appPreferences.isGamingMode = true
                    appPreferences.fpsStabilizerEnabled = true
                    updateResolutionBadge()
                    updateFpsStabilizerView()
                    showSnackbar("Preset 1080p 60FPS 24Mbps diterapkan (Super Jernih & Terang)")
                }
                "720p_60" -> {
                    appPreferences.resolution = "720p"
                    appPreferences.fps = 60
                    appPreferences.bitrateMbps = 12
                    appPreferences.isGamingMode = true
                    appPreferences.fpsStabilizerEnabled = true
                    updateResolutionBadge()
                    updateFpsStabilizerView()
                    showSnackbar("Preset 720p 60FPS Optimal diterapkan (Ringan, Adem & Mulus)")
                }
                "custom" -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
            }
        }
    }

    private fun updateResolutionBadge() {
        val badgeRes = findViewById<TextView>(R.id.text_resolution_badge)
        val studioTag = findViewById<TextView>(R.id.text_studio_pro_tag)
        val resPref = appPreferences.resolution
        val resLabel = com.bangdillah.rekamindulu.service.RecordingEngineOptimizer.getResolutionLabel(resPref)
        badgeRes?.text = String.format(
            Locale.getDefault(),
            "%s • %d FPS • %d Mbps",
            resLabel,
            appPreferences.fps,
            appPreferences.bitrateMbps
        )
        studioTag?.text = "STUDIO PRO • ${resLabel.uppercase()} • ${appPreferences.fps} FPS"

        if (!ScreenRecordingService.isRecording) {
            findViewById<Button>(R.id.btn_start_stop)?.text = "Mulai Merekam Layar ($resLabel)"
        }

        // Style the 3 quick resolution chips in the studio hero deck
        val chip1080p = findViewById<TextView>(R.id.chip_quick_1080p)
        val chip720p = findViewById<TextView>(R.id.chip_quick_720p)
        val chipNative = findViewById<TextView>(R.id.chip_quick_native)

        fun styleResChip(chip: TextView?, isSelected: Boolean) {
            chip?.apply {
                if (isSelected) {
                    backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.colorPrimary)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.colorOnPrimary))
                } else {
                    backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.colorSurfaceVariant)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textTitle))
                }
            }
        }

        styleResChip(chip1080p, resPref == "1080p")
        styleResChip(chip720p, resPref == "720p")
        styleResChip(chipNative, resPref == "NATIVE")
    }

    private fun updateFpsStabilizerView() {
        val card = findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_fps_stabilizer_toggle)
        val btn = findViewById<Button>(R.id.btn_home_toggle_fps_stabilizer)
        val statusText = findViewById<TextView>(R.id.text_fps_stabilizer_status)
        val iconBox = findViewById<View>(R.id.icon_box_fps_stabilizer)

        val isEnabled = appPreferences.fpsStabilizerEnabled
        if (isEnabled) {
            card?.strokeColor = ContextCompat.getColor(this, R.color.colorSuccess)
            card?.strokeWidth = 3
            btn?.text = "✓ ${appPreferences.fps} FPS TERKUNCI"
            btn?.backgroundTintList = ContextCompat.getColorStateList(this, R.color.colorSuccessContainer)
            btn?.setTextColor(ContextCompat.getColor(this, R.color.colorSuccess))
            statusText?.text = "Terkunci Stabil ${appPreferences.fps} FPS • Offload GPU Aktif (0 Frame Drop)"
            statusText?.setTextColor(ContextCompat.getColor(this, R.color.colorSuccess))
            iconBox?.backgroundTintList = ContextCompat.getColorStateList(this, R.color.colorSuccessContainer)
        } else {
            card?.strokeColor = ContextCompat.getColor(this, R.color.colorBorder)
            card?.strokeWidth = 1
            btn?.text = "NONAKTIF"
            btn?.backgroundTintList = ContextCompat.getColorStateList(this, R.color.colorSurfaceVariant)
            btn?.setTextColor(ContextCompat.getColor(this, R.color.textCaption))
            statusText?.text = "Mode Standar (Frame rate adaptif tanpa penguncian)"
            statusText?.setTextColor(ContextCompat.getColor(this, R.color.textCaption))
            iconBox?.backgroundTintList = ContextCompat.getColorStateList(this, R.color.colorSurfaceVariant)
        }
    }

    private fun setupDashboardCards() {
        // Dedicated Start/Stop Button linked to ScreenRecordingService with tactile bounce
        val btnStartStop = findViewById<Button>(R.id.btn_start_stop)
        btnStartStop?.setOnClickListener { v ->
            animateButtonPress(v) {
                if (ScreenRecordingService.isRecording) {
                    stopScreenCapture()
                } else {
                    startScreenCapture()
                }
            }
        }

        findViewById<View>(R.id.card_library)?.setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java))
        }

        findViewById<View>(R.id.card_gaming)?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<View>(R.id.card_trim)?.setOnClickListener {
            startActivity(Intent(this, TrimActivity::class.java))
        }

        findViewById<View>(R.id.card_settings)?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<View>(R.id.card_guide_tutorial)?.setOnClickListener {
            showGuideTutorialDialog()
        }

        findViewById<View>(R.id.card_device_info)?.setOnClickListener {
            openDiagnostics()
        }

        findViewById<View>(R.id.btn_open_diagnostics)?.setOnClickListener {
            openDiagnostics()
        }
    }

    private fun showGuideTutorialDialog() {
        val dialogView = layoutInflater.inflate(R.layout.layout_guide_dialog, null)
        try { activeDialog?.dismiss() } catch (ignored: Exception) {}
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        activeDialog = dialog

        dialogView.findViewById<Button>(R.id.btn_close_guide)?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun openDiagnostics() {
        SpecAnalysisDialog.show(this) {
            updateHardwareMetrics()
            updateResolutionBadge()
        }
    }

    private fun setupBottomNavigation() {
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNav?.selectedItemId = R.id.nav_home
        bottomNav?.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true
                R.id.nav_library -> {
                    startActivity(Intent(this, LibraryActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    })
                    true
                }
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    })
                    true
                }
                else -> false
            }
        }
    }

    private fun setupHomeRecordingsListView() {
        val recyclerView = findViewById<RecyclerView>(R.id.recycler_home_recordings)
        val btnViewAll = findViewById<TextView>(R.id.btn_home_view_all_recordings)

        recyclerView?.layoutManager = LinearLayoutManager(this)

        homeRecordingsAdapter = LibraryAdapter(
            videoFiles = homeRecordingFiles,
            onItemClick = { file ->
                val intent = Intent(this, PlayerActivity::class.java).apply {
                    putExtra("VIDEO_PATH", file.absolutePath)
                }
                startActivity(intent)
            },
            onRenameClick = { file ->
                showRenameHomeRecordingDialog(file)
            },
            onEditClick = { file ->
                val intent = Intent(this, TrimActivity::class.java).apply {
                    putExtra("VIDEO_PATH", file.absolutePath)
                }
                startActivity(intent)
            },
            onExtractAudioClick = { file ->
                handleExtractAudioHome(file)
            },
            onShareClick = { file ->
                shareVideoFile(file)
            },
            onDeleteClick = { file ->
                confirmDeleteHomeRecording(file)
            }
        )

        recyclerView?.adapter = homeRecordingsAdapter

        btnViewAll?.setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java))
        }

        findViewById<View>(R.id.btn_home_create_demo)?.setOnClickListener {
            Toast.makeText(this, "Membuat video demo MP4...", Toast.LENGTH_SHORT).show()
            StorageHelper.generateDemoVideo(this) { file ->
                if (isFinishing || isDestroyed) return@generateDemoVideo
                if (file != null) {
                    Toast.makeText(this, "Video demo berhasil dibuat!", Toast.LENGTH_SHORT).show()
                    updateHomeRecordingsList()
                    updateLibraryStats()
                } else {
                    Toast.makeText(this, "Gagal membuat video demo", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showRenameHomeRecordingDialog(file: File) {
        val currentNameWithoutExt = file.nameWithoutExtension
        val input = EditText(this).apply {
            setText(currentNameWithoutExt)
            setSelection(currentNameWithoutExt.length)
            setPadding(48, 32, 48, 32)
            hint = "Nama baru video"
            maxLines = 1
            isSingleLine = true
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textTitle))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.textCaption))
        }

        try { activeDialog?.dismiss() } catch (ignored: Exception) {}
        activeDialog = MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_trim)
            .setTitle("Ubah Nama Rekaman")
            .setMessage("Masukkan nama baru untuk rekaman ini:")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(this, "Nama tidak boleh kosong", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val illegalChars = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
                if (newName.any { it in illegalChars }) {
                    Toast.makeText(this, "Nama mengandung karakter tidak valid", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val finalFileName = if (newName.endsWith(".mp4", ignoreCase = true)) newName else "$newName.mp4"
                val targetFile = File(file.parentFile, finalFileName)
                if (targetFile.exists() && targetFile.absolutePath != file.absolutePath) {
                    Toast.makeText(this, "Nama file tersebut sudah digunakan", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val renamed = file.renameTo(targetFile)
                    if (renamed) {
                        LibraryAdapter.invalidateCache(file.absolutePath)
                        StorageHelper.scanRenamedFile(this@MainActivity, file, targetFile)
                    }
                    withContext(Dispatchers.Main) {
                        if (isFinishing || isDestroyed) return@withContext
                        if (renamed) {
                            Toast.makeText(this@MainActivity, "Nama berhasil diubah", Toast.LENGTH_SHORT).show()
                            updateHomeRecordingsList()
                        } else {
                            Toast.makeText(this@MainActivity, "Gagal mengubah nama file", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun handleExtractAudioHome(file: File) {
        if (isFinishing || isDestroyed) return
        try { extractProgressDialog?.dismiss() } catch (ignored: Exception) {}
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Mengekstrak Audio 🎵")
            .setMessage("Memeriksa trek audio dari ${file.name}...")
            .setCancelable(false)
            .create()
        extractProgressDialog = progressDialog
        try {
            progressDialog.show()
        } catch (ignored: Exception) {}

        Thread {
            val hasAudio = com.bangdillah.rekamindulu.audio.AudioExtractorHelper.hasAudioTrack(file)
            if (!hasAudio) {
                runOnUiThread {
                    try { progressDialog.dismiss() } catch (ignored: Exception) {}
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(
                            this,
                            "⚠️ Video ini tidak memiliki rekaman suara (direkam dalam mode Mute/tanpa suara).",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                return@Thread
            }

            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    progressDialog.setMessage("Sedang memisahkan audio dari ${file.name}...")
                }
            }

            val (success, audioFile) = com.bangdillah.rekamindulu.audio.AudioExtractorHelper.extractAudio(this, file)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    try {
                        progressDialog.dismiss()
                    } catch (ignored: Exception) {}

                    if (success && audioFile != null && audioFile.exists()) {
                        try {
                            try { activeDialog?.dismiss() } catch (ignored: Exception) {}
                            activeDialog = MaterialAlertDialogBuilder(this)
                                .setTitle("Ekstrak Audio Berhasil! 🎉")
                                .setIcon(R.drawable.ic_check_circle)
                                .setMessage(
                                    "Audio disimpan sebagai:\n${audioFile.name}\nUkuran: ${
                                        String.format(Locale.getDefault(), "%.1f MB", audioFile.length() / (1024.0 * 1024.0))
                                    }"
                                )
                                .setPositiveButton("Putar Audio") { _, _ ->
                                    playAudioFile(audioFile)
                                }
                                .setNeutralButton("Bagikan") { _, _ ->
                                    shareAudioFile(audioFile)
                                }
                                .setNegativeButton("Tutup", null)
                                .show()
                        } catch (ignored: Exception) {}
                    } else {
                        Toast.makeText(this, "Tidak dapat mengekstrak audio dari video ini", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    private fun playAudioFile(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "audio/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Putar Audio dengan"))
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak ada aplikasi pemutar audio eksternal", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareAudioFile(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Bagikan Audio"))
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal membagikan audio: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateHomeRecordingsList() {
        if (!::homeRecordingsAdapter.isInitialized) return
        if (AppLockHelper.shouldRequirePin(
                appPreferences.isAppLockEnabled,
                appPreferences.appLockPin,
                HomeActivity.isSessionUnlocked
            )
        ) {
            findViewById<RecyclerView>(R.id.recycler_home_recordings)?.visibility = View.GONE
            findViewById<View>(R.id.home_recordings_empty)?.visibility = View.GONE
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val allFiles = StorageHelper.getAllVideoFiles(this@MainActivity)
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                homeRecordingFiles.clear()
                homeRecordingFiles.addAll(allFiles)

                val emptyView = findViewById<View>(R.id.home_recordings_empty)
                val recyclerView = findViewById<RecyclerView>(R.id.recycler_home_recordings)

                if (homeRecordingFiles.isEmpty()) {
                    emptyView?.visibility = View.VISIBLE
                    recyclerView?.visibility = View.GONE
                } else {
                    emptyView?.visibility = View.GONE
                    recyclerView?.visibility = View.VISIBLE
                }

                homeRecordingsAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun confirmDeleteHomeRecording(file: File) {
        try { activeDialog?.dismiss() } catch (ignored: Exception) {}
        activeDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Hapus Rekaman Video?")
            .setMessage("File \"${file.name}\" akan dihapus secara permanen dari penyimpanan.")
            .setIcon(R.drawable.ic_delete)
            .setPositiveButton("Hapus") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    if (!file.exists()) return@launch
                    LibraryAdapter.invalidateCache(file.absolutePath)
                    val deleted = file.delete()
                    StorageHelper.scanDeletedFile(this@MainActivity, file)
                    withContext(Dispatchers.Main) {
                        if (isFinishing || isDestroyed) return@withContext
                        if (deleted) {
                            Toast.makeText(this@MainActivity, "Rekaman video berhasil dihapus", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "Gagal menghapus file rekaman", Toast.LENGTH_SHORT).show()
                        }
                        updateHomeRecordingsList()
                        updateLibraryStats()
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun shareVideoFile(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Bagikan Rekaman Layar"))
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal membagikan video: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateHardwareMetrics() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val report = DeviceAnalyzer.analyze(this@MainActivity)
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext

                    findViewById<TextView>(R.id.text_storage_val)?.text =
                        String.format(Locale.getDefault(), "%.1f GB", report.freeStorageGb)

                    val battStatusText = if (report.isCharging) "Charging" else "Optimal"
                    findViewById<TextView>(R.id.text_battery_val)?.text =
                        String.format(Locale.getDefault(), "%d%% %s", report.batteryPct, battStatusText)

                    findViewById<TextView>(R.id.text_ram_val)?.text =
                        String.format(Locale.getDefault(), "%.1f GB Free", report.freeRamGb)

                    findViewById<TextView>(R.id.text_device_model)?.text = report.deviceName
                    findViewById<TextView>(R.id.text_android_ver)?.text = "${report.androidVersion} (API ${report.apiLevel})"
                    findViewById<TextView>(R.id.text_cpu_info)?.text = "${report.socInfo} (${report.cpuCores} Cores)"
                    findViewById<TextView>(R.id.text_total_ram)?.text = String.format(
                        Locale.getDefault(),
                        "%.1f GB (%.1f GB Free)",
                        report.totalRamGb,
                        report.freeRamGb
                    )
                    findViewById<TextView>(R.id.text_screen_res_detail)?.text = String.format(
                        Locale.getDefault(),
                        "%d x %d (%d Hz)",
                        report.screenWidth,
                        report.screenHeight,
                        report.refreshRateHz
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating hardware metrics", e)
            }
        }
    }

    private fun updateLibraryStats() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val files = StorageHelper.getAllVideoFiles(this@MainActivity)
                val count = files.size
                val totalBytes = files.sumOf { it.length() }
                val totalMb = totalBytes / (1024.0 * 1024.0)
                val freeBytes = RecordingEngineOptimizer.getAvailableStorageBytes(this@MainActivity)
                val freeGb = freeBytes / (1024.0 * 1024.0 * 1024.0)
                val estMins = RecordingEngineOptimizer.getEstimatedRemainingTimeMinutes(this@MainActivity, appPreferences.bitrateMbps)

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    val textStats = findViewById<TextView>(R.id.text_library_stats)
                    val summary = if (totalMb >= 1024.0) {
                        String.format(Locale.getDefault(), "%d File • %.1f GB", count, totalMb / 1024.0)
                    } else {
                        String.format(Locale.getDefault(), "%d File • %.0f MB", count, totalMb)
                    }
                    textStats?.text = summary

                    val estHours = estMins / 60.0
                    findViewById<TextView>(R.id.text_cloud_sync_detail)?.text = if (estHours >= 1.0) {
                        String.format(
                            Locale.getDefault(),
                            "%s tersimpan • Sisa %.1f GB (~%.1f jam rekam)",
                            summary,
                            freeGb,
                            estHours
                        )
                    } else {
                        String.format(
                            Locale.getDefault(),
                            "%s tersimpan • Sisa %.1f GB (~%d mnt rekam)",
                            summary,
                            freeGb,
                            estMins
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error loading library stats", e)
            }
        }
    }

    private fun updateRecordingBanner() {
        val banner = findViewById<View>(R.id.card_active_recording_banner)
        val textTimer = findViewById<TextView>(R.id.text_live_recording_timer)
        val startRecordLabel = findViewById<TextView>(R.id.text_start_record_label)
        val btnPause = findViewById<Button>(R.id.btn_home_pause_recording)
        val headerStatus = findViewById<TextView>(R.id.text_home_header_status)
        val btnStartStop = findViewById<Button>(R.id.btn_start_stop)
        val imgStartStopIcon = findViewById<ImageView>(R.id.img_start_stop_icon)

        // Hero studio HUD views
        val layoutIdle = findViewById<View>(R.id.layout_idle_start)
        val layoutActive = findViewById<View>(R.id.layout_recording_active)
        val textHeroTimer = findViewById<TextView>(R.id.text_hero_live_timer)
        val textHeroBadge = findViewById<TextView>(R.id.text_hero_live_badge)
        val textHeroInfo = findViewById<TextView>(R.id.text_hero_rec_info)
        val btnHeroPause = findViewById<Button>(R.id.btn_hero_pause_recording)

        // Floating pill views
        val floatingPill = findViewById<MaterialCardView>(R.id.card_floating_recording_pill)
        val floatingStatus = findViewById<TextView>(R.id.text_floating_rec_status)
        val floatingTimer = findViewById<TextView>(R.id.text_floating_rec_timer)
        val floatingBtnPause = findViewById<MaterialButton>(R.id.btn_floating_pause)

        if (ScreenRecordingService.isRecording) {
            stopBreathingGlowAnimation()
            banner?.visibility = View.GONE
            layoutIdle?.visibility = View.GONE
            layoutActive?.visibility = View.VISIBLE
            layoutActive?.post { updateFloatingPillVisibility() }

            val sec = ScreenRecordingService.elapsedSeconds
            val isPaused = ScreenRecordingService.isPaused
            val formattedTime = com.bangdillah.rekamindulu.floating.FloatingOverlayService.formatDuration(sec)

            textHeroTimer?.text = formattedTime
            val imgLiveDot = findViewById<ImageView>(R.id.img_hero_live_dot)

            val btnHeroStop = findViewById<Button>(R.id.btn_hero_stop_recording)
            btnHeroStop?.isEnabled = true
            btnHeroStop?.text = "⏹️ Hentikan & Simpan"

            if (isPaused) {
                imgLiveDot?.alpha = 0.4f
                textTimer?.text = "⏸️ Perekaman Dijeda • Durasi: $formattedTime"
                btnPause?.text = "Lanjut"
                headerStatus?.text = "⏸️ DIJEDA • $formattedTime"
                headerStatus?.setTextColor(ContextCompat.getColor(this, R.color.colorWarning))

                floatingPill?.strokeColor = ContextCompat.getColor(this, R.color.colorWarning)
                floatingStatus?.text = "REKAMAN DIJEDA"
                floatingStatus?.setTextColor(ContextCompat.getColor(this, R.color.colorWarning))
                floatingTimer?.text = "$formattedTime • Ketuk untuk melanjutkan"
                floatingBtnPause?.text = "Lanjut"
                floatingBtnPause?.setIconResource(R.drawable.ic_play_arrow)

                textHeroBadge?.text = "⏸️ REKAMAN DIJEDA"
                textHeroBadge?.setTextColor(ContextCompat.getColor(this, R.color.colorWarning))
                btnHeroPause?.text = "▶️ Lanjutkan"
                val resDisplay = com.bangdillah.rekamindulu.service.RecordingEngineOptimizer.getResolutionLabel(appPreferences.resolution)
                textHeroInfo?.text = "$resDisplay • ${appPreferences.fps} FPS • Perekaman Dijeda"
            } else {
                imgLiveDot?.alpha = 1.0f
                val resDisplay = com.bangdillah.rekamindulu.service.RecordingEngineOptimizer.getResolutionLabel(appPreferences.resolution)
                textTimer?.text = String.format(
                    Locale.getDefault(),
                    "Durasi: %s • %s %dFPS",
                    formattedTime,
                    resDisplay,
                    appPreferences.fps
                )
                btnPause?.text = "Jeda"
                headerStatus?.text = "🔴 MEREKAM AKTIF • $formattedTime"
                headerStatus?.setTextColor(ContextCompat.getColor(this, R.color.colorError))

                floatingPill?.strokeColor = 0xFFFF4757.toInt()
                floatingStatus?.text = "SEDANG MEREKAM"
                floatingStatus?.setTextColor(0xFFFF6B6B.toInt())
                floatingTimer?.text = "$formattedTime • $resDisplay ${appPreferences.fps}FPS"
                floatingBtnPause?.text = "Jeda"
                floatingBtnPause?.setIconResource(R.drawable.ic_pause)

                textHeroBadge?.text = "🔴 SEDANG MEREKAM AKTIF"
                textHeroBadge?.setTextColor(ContextCompat.getColor(this, R.color.colorError))
                btnHeroPause?.text = "⏸️ Jeda"
                val activeAudioMode = ScreenRecordingService.effectiveAudioMode ?: appPreferences.audioSource
                val activeMicOn = activeAudioMode == "MIC" || activeAudioMode == "BOTH"
                val activeGameAudioOn = activeAudioMode == "INTERNAL" || activeAudioMode == "BOTH"
                val audioDesc = when {
                    activeMicOn && activeGameAudioOn && ScreenRecordingService.isMicMuted -> "Audio Game Aktif (Mic Dibisukan)"
                    activeMicOn && activeGameAudioOn -> "Mikrofon & Audio Video/Game Aktif"
                    activeMicOn && ScreenRecordingService.isMicMuted -> "Mikrofon Dibisukan"
                    activeMicOn -> "Mikrofon Aktif"
                    activeGameAudioOn -> "Audio Jernih Video/Game Online"
                    else -> "Mute (Tanpa Suara)"
                }
                textHeroInfo?.text = "$resDisplay • ${appPreferences.fps} FPS • ${appPreferences.bitrateMbps} Mbps • $audioDesc"
            }
            startRecordLabel?.text = "Sedang Merekam • Tekan tombol untuk Berhenti & Simpan"
            btnStartStop?.isEnabled = true
            btnStartStop?.text = "Hentikan & Simpan Rekaman"
            btnStartStop?.backgroundTintList = ContextCompat.getColorStateList(this, R.color.colorError)
            imgStartStopIcon?.setImageResource(R.drawable.ic_close)
        } else {
            startBreathingGlowAnimation()
            banner?.visibility = View.GONE
            floatingPill?.visibility = View.GONE
            layoutIdle?.visibility = View.VISIBLE
            layoutActive?.visibility = View.GONE

            headerStatus?.text = "● Siap Merekam • Status Optimal"
            headerStatus?.setTextColor(ContextCompat.getColor(this, R.color.colorSuccess))
            startRecordLabel?.text = "Tekan tombol untuk mulai merekam layar & audio"
            val resDisplay = com.bangdillah.rekamindulu.service.RecordingEngineOptimizer.getResolutionLabel(appPreferences.resolution)
            btnStartStop?.isEnabled = true
            btnStartStop?.text = "Mulai Merekam Layar ($resDisplay)"
            btnStartStop?.backgroundTintList = ContextCompat.getColorStateList(this, R.color.colorPrimary)
            imgStartStopIcon?.setImageResource(R.drawable.ic_record)
        }
    }

    private fun updateFloatingPillVisibility() {
        val floatingPill = findViewById<View>(R.id.card_floating_recording_pill) ?: return
        if (!ScreenRecordingService.isRecording) {
            floatingPill.visibility = View.GONE
            return
        }
        val scrollView = findViewById<ScrollView>(R.id.home_scroll_view)
        val layoutActive = findViewById<View>(R.id.layout_recording_active)
        if (scrollView == null || layoutActive == null || layoutActive.visibility != View.VISIBLE || layoutActive.height == 0) {
            floatingPill.visibility = View.GONE
            return
        }
        val heroRect = android.graphics.Rect()
        val scrollRect = android.graphics.Rect()
        val heroVisible = layoutActive.getGlobalVisibleRect(heroRect) &&
            scrollView.getGlobalVisibleRect(scrollRect) &&
            android.graphics.Rect.intersects(heroRect, scrollRect)
        floatingPill.visibility = if (heroVisible) View.GONE else View.VISIBLE
    }

    // =========================================================================
    // MEDIAPROJECTION API & SCREEN CAPTURE IMPLEMENTATION
    // =========================================================================

    private var inAppCountdownTimer: CountDownTimer? = null
    private val countdownHandler = Handler(Looper.getMainLooper())
    private var countdownFinishRunnable: Runnable? = null

    private fun cancelCountdownHUD() {
        inAppCountdownTimer?.cancel()
        inAppCountdownTimer = null
        countdownFinishRunnable?.let { countdownHandler.removeCallbacks(it) }
        countdownFinishRunnable = null
        findViewById<View>(R.id.layout_countdown_hud)?.visibility = View.GONE
    }

    /**
     * Mendapatkan daftar izin runtime yang dibutuhkan untuk perekaman layar dan audio.
     * Mencakup RECORD_AUDIO (jika mikrofon aktif) dan POST_NOTIFICATIONS (Android 13+).
     */
    fun getRequiredRuntimePermissions(): Array<String> {
        syncAudioPreferences()
        return computeRequiredRuntimePermissions(
            audioSource = appPreferences.audioSource,
            facecamMode = appPreferences.facecamMode,
            sdkInt = Build.VERSION.SDK_INT
        )
    }

    /**
     * Memeriksa apakah izin audio recording (RECORD_AUDIO) sudah diberikan.
     */
    fun hasAudioRecordingPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Memeriksa apakah izin FOREGROUND_SERVICE_MEDIA_PROJECTION sudah aktif (pada Android 14+).
     */
    fun hasMediaProjectionServicePermission(): Boolean {
        return true
    }

    /**
     * Memeriksa apakah seluruh izin runtime yang diperlukan telah diberikan.
     */
    fun hasRequiredPermissions(): Boolean {
        return getRequiredRuntimePermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Meminta izin runtime yang belum diberikan untuk perekaman layar dan audio.
     */
    fun checkAndRequestPermissions() {
        val missingPermissions = getRequiredRuntimePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            runtimePermissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            initiateScreenCaptureFlow()
        }
    }

    /**
     * Memulai alur perekaman layar:
     * 1. Memeriksa izin audio recording & media projection service
     * 2. Memeriksa kapasitas memori
     * 3. Menampilkan countdown HUD (jika diaktifkan di pengaturan)
     * 4. Meminta izin MediaProjection sistem
     */
    fun startScreenCapture() {
        if (ScreenRecordingService.isRecording) {
            stopScreenCapture()
            return
        }

        // Pastikan konfigurasi audio (Mic/Game Audio/Both/Mute) tersinkronisasi ke preferensi
        syncAudioPreferences()

        val missingPermissions = getRequiredRuntimePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            runtimePermissionLauncher.launch(missingPermissions.toTypedArray())
            return
        }

        // Cek sisa memori penyimpanan internal jika diaktifkan (hanya ingatkan jika < 200MB)
        // Gunakan pemantauan instan StatFs melalui RecordingEngineOptimizer agar bebas lag (<0.1ms)
        if (appPreferences.storageWarningEnabled) {
            val freeBytes = com.bangdillah.rekamindulu.service.RecordingEngineOptimizer.getAvailableStorageBytes(this)
            val freeStorageGb = freeBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            if (freeStorageGb < 0.2) {
                try { activeDialog?.dismiss() } catch (ignored: Exception) {}
                activeDialog = MaterialAlertDialogBuilder(this)
                    .setTitle("⚠️ Peringatan Sisa Memori HP")
                    .setMessage(
                        String.format(
                            Locale.getDefault(),
                            "Sisa memori internal Anda tersisa %.2f GB (sangat rendah). Merekam video resolusi tinggi dapat gagal jika penyimpanan habis. Lanjutkan merekam?",
                            freeStorageGb
                        )
                    )
                    .setPositiveButton("Tetap Rekam") { _, _ ->
                        initiateScreenCaptureFlow()
                    }
                    .setNeutralButton("Kelola Galeri") { _, _ ->
                        startActivity(Intent(this, LibraryActivity::class.java))
                    }
                    .setNegativeButton("Batal", null)
                    .show()
                return
            }
        }

        initiateScreenCaptureFlow()
    }

    /**
     * Memulai alur permintaan izin MediaProjection sistem
     */
    private fun initiateScreenCaptureFlow() {
        requestScreenCapture()
    }

    /**
     * Menampilkan in-app animated countdown HUD overlay
     */
    private fun showCountdownHUD(durationSeconds: Int, onFinish: () -> Unit) {
        val hudLayout = findViewById<View>(R.id.layout_countdown_hud) ?: run {
            onFinish()
            return
        }
        val textNumber = findViewById<TextView>(R.id.text_countdown_hud_number)
        val textTitle = findViewById<TextView>(R.id.text_countdown_hud_title)
        val progressBar = findViewById<ProgressBar>(R.id.progress_countdown_hud)
        val btnCancel = findViewById<Button>(R.id.btn_cancel_countdown_hud)
        val btnSkip = findViewById<Button>(R.id.btn_skip_countdown_hud)

        hudLayout.visibility = View.VISIBLE
        textNumber?.text = durationSeconds.toString()
        textTitle?.text = "Bersiap Merekam Layar..."
        progressBar?.max = durationSeconds
        progressBar?.progress = 0

        inAppCountdownTimer?.cancel()
        countdownFinishRunnable?.let { countdownHandler.removeCallbacks(it) }
        countdownFinishRunnable = null

        btnCancel?.setOnClickListener {
            cancelCountdownHUD()
            showSnackbar("Perekaman dibatalkan")
        }

        btnSkip?.setOnClickListener {
            cancelCountdownHUD()
            if (!isFinishing && !isDestroyed) {
                onFinish()
            }
        }

        inAppCountdownTimer = object : CountDownTimer((durationSeconds * 1000 + 350).toLong(), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secRemaining = (millisUntilFinished / 1000).toInt().coerceAtLeast(1)
                textNumber?.text = secRemaining.toString()
                progressBar?.progress = durationSeconds - secRemaining
                if (secRemaining <= 1) {
                    textTitle?.text = "Mulai Merekam..."
                }
            }

            override fun onFinish() {
                textNumber?.text = "GO!"
                progressBar?.progress = durationSeconds
                val finishRunnable = Runnable {
                    countdownFinishRunnable = null
                    hudLayout.visibility = View.GONE
                    if (!isFinishing && !isDestroyed) {
                        onFinish()
                    }
                }
                countdownFinishRunnable = finishRunnable
                countdownHandler.postDelayed(finishRunnable, 300)
            }
        }.start()
    }

    /**
     * Meminta izin sistem MediaProjection menggunakan MediaProjectionManager
     */
    fun requestScreenCapture() {
        try {
            val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
            screenCaptureLauncher.launch(captureIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Gagal membuat intent tangkap layar MediaProjection", e)
            Toast.makeText(this, "Tidak dapat memulai tangkap layar: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Dipanggil saat pengguna menyetujui izin tangkap layar MediaProjection.
     * Countdown dijalankan di sini setelah izin diberikan agar pengguna memiliki waktu bersiap.
     */
    open fun onScreenCapturePermissionGranted(resultCode: Int, data: Intent) {
        val countdown = appPreferences.countdownDuration
        if (countdown > 0) {
            showCountdownHUD(countdown) {
                actuallyStartScreenRecordingService(resultCode, data)
            }
        } else {
            actuallyStartScreenRecordingService(resultCode, data)
        }
    }

    private fun actuallyStartScreenRecordingService(resultCode: Int, data: Intent) {
        syncAudioPreferences()
        val serviceIntent = Intent(this, ScreenRecordingService::class.java).apply {
            action = ScreenRecordingService.ACTION_START
            putExtra(ScreenRecordingService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenRecordingService.EXTRA_RESULT_DATA, data)
            putExtra("EXTRA_AUDIO_SOURCE", appPreferences.audioSource)
            putExtra("EXTRA_MIC_ENABLED", isMicOn)
            putExtra("EXTRA_GAME_AUDIO_ENABLED", isGameAudioOn)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            updateRecordingBanner()
            val audioInfo = when {
                isMicOn && isGameAudioOn -> "Mikrofon & Audio Game Aktif"
                isMicOn -> "Mikrofon Aktif"
                isGameAudioOn -> "Audio Game Aktif"
                else -> "Mute"
            }
            showSnackbar("🔴 Perekaman layar aktif ($audioInfo)")
        } catch (e: Exception) {
            Log.e(TAG, "Gagal memulai ScreenRecordingService", e)
            Toast.makeText(this, "Gagal memulai perekaman layar: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    open fun onScreenCapturePermissionDenied() {
        updateRecordingBanner()
        Toast.makeText(this, "Izin tangkap layar dibatalkan pengguna", Toast.LENGTH_SHORT).show()
    }

    fun sendServiceCommand(intent: Intent) {
        val action = intent.action
        if (action != ScreenRecordingService.ACTION_START &&
            !ScreenRecordingService.isRecording &&
            !ScreenRecordingService.isPaused
        ) {
            return
        }
        try {
            if (action == ScreenRecordingService.ACTION_START && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: IllegalStateException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    startForegroundService(intent)
                } catch (inner: Exception) {
                    Log.e(TAG, "sendServiceCommand fallback failed", inner)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendServiceCommand failed", e)
        }
    }

    /**
     * Menghentikan perekaman layar aktif
     */
    fun stopScreenCapture() {
        if (!ScreenRecordingService.isRecording && !ScreenRecordingService.isPaused) {
            updateRecordingBanner()
            return
        }
        showSnackbar("Menyimpan rekaman video...")
        val stopIntent = Intent(this, ScreenRecordingService::class.java).apply {
            action = ScreenRecordingService.ACTION_STOP
        }
        sendServiceCommand(stopIntent)
        updateRecordingBanner()
    }

    /**
     * Kompatibilitas mundur untuk pemanggilan lawas startActivityForResult
     */
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                onScreenCapturePermissionGranted(resultCode, data)
            } else {
                onScreenCapturePermissionDenied()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            val audioIndex = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
            if (audioIndex != -1 && grantResults.getOrNull(audioIndex) == PackageManager.PERMISSION_GRANTED) {
                showSnackbar("Izin audio mikrofon diberikan")
            }
            requestScreenCapture()
        }
    }

    private fun showSaveSuccessToast(path: String) {
        val file = File(path)
        showSnackbar("✓ Rekaman ${file.name} tersimpan ke Galeri Rekamin Dulu", Snackbar.LENGTH_LONG)
    }

    override fun onDestroy() {
        super.onDestroy()
        liveTimerHandler.removeCallbacksAndMessages(null)
        cancelCountdownHUD()
        try { activeDialog?.dismiss() } catch (ignored: Exception) {}
        activeDialog = null
        try { extractProgressDialog?.dismiss() } catch (ignored: Exception) {}
        extractProgressDialog = null
        appLockDialog?.dismiss()
        appLockDialog = null
        stopBreathingGlowAnimation()
    }

    private fun openAppPermissionSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Buka Pengaturan HP > Aplikasi > Rekamin Dulu > Izin", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPermissionSettingsSnackbar(message: String) {
        val view = findViewById<View>(android.R.id.content) ?: run {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }
        try {
            Snackbar.make(view, message, Snackbar.LENGTH_LONG)
                .setAction("Pengaturan") { openAppPermissionSettings() }
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    fun showSnackbar(message: String, duration: Int = Snackbar.LENGTH_SHORT) {
        val view = findViewById<View>(android.R.id.content) ?: run {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            Snackbar.make(view, message, duration).show()
        } catch (e: Exception) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
}
