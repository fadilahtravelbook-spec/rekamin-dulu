package com.bangdillah.rekamindulu.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bangdillah.rekamindulu.HomeActivity
import com.bangdillah.rekamindulu.R
import com.bangdillah.rekamindulu.floating.FloatingOverlayService
import com.bangdillah.rekamindulu.library.LibraryActivity
import com.bangdillah.rekamindulu.service.ScreenRecordingService
import com.bangdillah.rekamindulu.utils.AppPreferences
import com.bangdillah.rekamindulu.utils.SaveTransitionDialog
import com.bangdillah.rekamindulu.utils.SelectionBottomSheetDialog
import com.bangdillah.rekamindulu.utils.SelectionOption
import com.bangdillah.rekamindulu.utils.SpecAnalysisDialog
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var appPreferences: AppPreferences

    private var textResolutionVal: TextView? = null
    private var textFpsVal: TextView? = null
    private var textBitrateVal: TextView? = null
    private var textCodecVal: TextView? = null
    private var textAudioVal: TextView? = null
    private var textMicTypeVal: TextView? = null
    private var textCountdownVal: TextView? = null
    private var textThemeVal: TextView? = null
    private var textPinVal: TextView? = null
    private var dividerPin: View? = null
    private var rowSettingPin: View? = null

    private var toggleGaming: MaterialSwitch? = null
    private var toggleFpsStabilizer: MaterialSwitch? = null
    private var toggleTurboBoost: MaterialSwitch? = null
    private var toggleHardwareAcceleration: MaterialSwitch? = null
    private var toggleInternalAudioBoost: MaterialSwitch? = null
    private var toggleFloating: MaterialSwitch? = null
    private var toggleVibrate: MaterialSwitch? = null
    private var toggleStopScreenOff: MaterialSwitch? = null
    private var toggleShowFps: MaterialSwitch? = null
    private var toggleStorageWarning: MaterialSwitch? = null
    private var toggleAppLock: MaterialSwitch? = null

    private val liveTimerHandler = Handler(Looper.getMainLooper())
    private val liveTimerRunnable = object : Runnable {
        override fun run() {
            if (ScreenRecordingService.isRecording) {
                updateRecordingIndicator()
                liveTimerHandler.postDelayed(this, 1000)
            } else {
                updateRecordingIndicator()
            }
        }
    }

    private val recordingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ScreenRecordingService.ACTION_RECORDING_STOPPED -> {
                    val path = intent.getStringExtra(ScreenRecordingService.EXTRA_FILE_PATH)
                        ?: ScreenRecordingService.lastRecordedFilePath
                    updateUIValues()
                    updateRecordingIndicator()
                    if (!path.isNullOrEmpty()) {
                        SaveTransitionDialog.show(this@SettingsActivity, path)
                    }
                }
                ScreenRecordingService.ACTION_RECORDING_STATUS_CHANGED -> {
                    updateUIValues()
                    updateRecordingIndicator()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_screen)
        supportActionBar?.hide()

        appPreferences = AppPreferences(this)

        checkAppLock()
        initViews()
        setupListeners()
        setupBottomNavigation()
    }

    private fun checkAppLock() {
        if (appPreferences.isAppLockEnabled && appPreferences.appLockPin.isNotEmpty() && !HomeActivity.isSessionUnlocked) {
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                hint = "Masukkan 4 angka PIN"
                maxLines = 1
                setPadding(48, 36, 48, 36)
            }

            AlertDialog.Builder(this)
                .setTitle("🔒 Kunci Pengaturan")
                .setMessage("Pengaturan dilindungi PIN. Masukkan PIN keamanan untuk mengakses:")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("Buka Kunci") { dialog, _ ->
                    val entered = input.text.toString().trim()
                    if (entered == appPreferences.appLockPin) {
                        HomeActivity.isSessionUnlocked = true
                        dialog.dismiss()
                    } else {
                        Toast.makeText(this, "PIN salah!", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
                .setNegativeButton("Kembali") { _, _ ->
                    finish()
                }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateUIValues()
        updateRecordingIndicator()

        val filter = IntentFilter().apply {
            addAction(ScreenRecordingService.ACTION_RECORDING_STOPPED)
            addAction(ScreenRecordingService.ACTION_RECORDING_STATUS_CHANGED)
        }
        ContextCompat.registerReceiver(this, recordingReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

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
            unregisterReceiver(recordingReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
    }

    private fun initViews() {
        textResolutionVal = findViewById(R.id.text_setting_resolution_val)
        textFpsVal = findViewById(R.id.text_setting_fps_val)
        textBitrateVal = findViewById(R.id.text_setting_bitrate_val)
        textCodecVal = findViewById(R.id.text_setting_codec_val)
        textAudioVal = findViewById(R.id.text_setting_audio_val)
        textMicTypeVal = findViewById(R.id.text_setting_mic_type_val)
        textCountdownVal = findViewById(R.id.text_setting_countdown_val)
        textThemeVal = findViewById(R.id.text_setting_theme_val)
        textPinVal = findViewById(R.id.text_setting_pin_val)
        dividerPin = findViewById(R.id.divider_pin)
        rowSettingPin = findViewById(R.id.row_setting_pin)

        toggleGaming = findViewById(R.id.toggle_gaming_mode)
        toggleFpsStabilizer = findViewById(R.id.toggle_fps_stabilizer)
        toggleTurboBoost = findViewById(R.id.toggle_turbo_boost)
        toggleHardwareAcceleration = findViewById(R.id.toggle_hardware_acceleration)
        toggleInternalAudioBoost = findViewById(R.id.toggle_internal_audio_boost)
        toggleFloating = findViewById(R.id.toggle_floating_overlay)
        toggleVibrate = findViewById(R.id.toggle_vibrate)
        toggleStopScreenOff = findViewById(R.id.toggle_stop_screen_off)
        toggleShowFps = findViewById(R.id.toggle_show_fps)
        toggleStorageWarning = findViewById(R.id.toggle_storage_warning)
        toggleAppLock = findViewById(R.id.toggle_app_lock)
    }

    private fun setupListeners() {
        // Gaming mode toggle with automatic FPS & Bitrate sync + sub-feature coordination
        toggleGaming?.setOnCheckedChangeListener { _, isChecked ->
            appPreferences.isGamingMode = isChecked
            findViewById<View>(R.id.gaming_mode_tooltip)?.visibility =
                if (isChecked) View.VISIBLE else View.GONE

            if (isChecked) {
                // Boost parameters for gaming
                appPreferences.fps = 60
                appPreferences.bitrateMbps = 12
                appPreferences.fpsStabilizerEnabled = true
                appPreferences.gamingTurboBoost = true
                appPreferences.hardwareAccelerationEnabled = true
                toggleFpsStabilizer?.isChecked = true
                toggleTurboBoost?.isChecked = true
                toggleHardwareAcceleration?.isChecked = true
                updateUIValues(skipGamingSwitch = true)
                Toast.makeText(this, "⚡ Mode Gaming Pro Aktif: 60 FPS & 12 Mbps (Stabilisator + Turbo CPU + GPU HW)", Toast.LENGTH_SHORT).show()
            } else {
                appPreferences.fps = 30
                appPreferences.bitrateMbps = 8
                appPreferences.fpsStabilizerEnabled = false
                updateUIValues(skipGamingSwitch = true)
                Toast.makeText(this, "Mode Standar: 30 FPS & 8 Mbps", Toast.LENGTH_SHORT).show()
            }
            com.bangdillah.rekamindulu.backend.FirebaseAuthManager.getInstance(this).updateUserPreferences(
                fps = appPreferences.fps,
                gamingMode = isChecked
            )
        }

        // FPS Stabilizer toggle
        toggleFpsStabilizer?.setOnCheckedChangeListener { _, isChecked ->
            appPreferences.fpsStabilizerEnabled = isChecked
            if (isChecked && appPreferences.fps < 60) {
                appPreferences.fps = 60
                updateUIValues(skipGamingSwitch = true)
            }
            val msg = if (isChecked) {
                "⚡ Stabilisator FPS Aktif: Frame rate terkunci stabil 60 FPS (Anti-Drop)"
            } else {
                "Stabilisator FPS Dinonaktifkan"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Game Turbo Booster toggle
        toggleTurboBoost?.setOnCheckedChangeListener { _, isChecked ->
            appPreferences.gamingTurboBoost = isChecked
            val msg = if (isChecked) {
                "🚀 Game Turbo Aktif: Prioritas thread URGENT_DISPLAY & CPU Anti-Throttle"
            } else {
                "Game Turbo Dinonaktifkan"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Hardware GPU Acceleration toggle
        toggleHardwareAcceleration?.setOnCheckedChangeListener { _, isChecked ->
            appPreferences.hardwareAccelerationEnabled = isChecked
            val msg = if (isChecked) {
                "⚡ Akselerasi Hardware GPU Aktif: AVCProfileHigh 4.2 via VPU Hardware (CPU Bebas Lag)"
            } else {
                "Akselerasi Hardware Dinonaktifkan (Mode Baseline Kompatibel)"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Stabilisator Audio Internal toggle
        toggleInternalAudioBoost?.setOnCheckedChangeListener { _, isChecked ->
            appPreferences.internalAudioStabilizer = isChecked
            val msg = if (isChecked) {
                "🎧 Audio Internal Stabil: Jalur audio game diproteksi dari noise & bentrok"
            } else {
                "Stabilisator Audio Internal Dinonaktifkan"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Floating overlay toggle
        toggleFloating?.setOnCheckedChangeListener { _, isChecked ->
            appPreferences.showFloatingOverlay = isChecked
            if (isChecked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
                MaterialAlertDialogBuilder(this)
                    .setIcon(R.drawable.ic_floating)
                    .setTitle("Izin Jendela Mengambang Diperlukan")
                    .setMessage("Untuk menampilkan tombol kontrol dan indikator FPS saat merekam di atas game/aplikasi lain, aktifkan izin \"Tampilkan di atas aplikasi lain\".")
                    .setPositiveButton("Buka Pengaturan") { _, _ ->
                        try {
                            val intent = Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:$packageName")
                            )
                            startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(this, "Buka Pengaturan HP > Aplikasi > Rekamin Dulu > Izin Menimpa", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton("Nanti", null)
                    .show()
            }
        }

        // Vibrate toggle
        toggleVibrate?.setOnCheckedChangeListener { _, isChecked ->
            appPreferences.vibrateOnStart = isChecked
        }

        // Stop on screen off toggle
        toggleStopScreenOff?.setOnCheckedChangeListener { _, isChecked ->
            appPreferences.stopOnScreenOff = isChecked
        }

        // Show FPS counter toggle
        toggleShowFps?.setOnCheckedChangeListener { _, isChecked ->
            appPreferences.showFpsCounter = isChecked
        }

        // Low storage warning toggle
        toggleStorageWarning?.setOnCheckedChangeListener { _, isChecked ->
            appPreferences.storageWarningEnabled = isChecked
        }

        // App Lock toggle
        toggleAppLock?.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (appPreferences.appLockPin.isEmpty()) {
                    showSetPinDialog { success ->
                        if (!success) {
                            toggleAppLock?.isChecked = false
                        }
                    }
                } else {
                    appPreferences.isAppLockEnabled = true
                    updateUIValues(skipAppLockSwitch = true)
                }
            } else {
                appPreferences.isAppLockEnabled = false
                updateUIValues(skipAppLockSwitch = true)
            }
        }

        // Change PIN row
        rowSettingPin?.setOnClickListener {
            showSetPinDialog()
        }

        // Theme picker
        findViewById<View>(R.id.row_setting_theme)?.setOnClickListener {
            showThemeDialog()
        }

        // Resolution picker
        findViewById<View>(R.id.row_setting_resolution)?.setOnClickListener {
            if (ensureNotRecording()) showResolutionDialog()
        }

        // FPS picker
        findViewById<View>(R.id.row_setting_fps)?.setOnClickListener {
            if (ensureNotRecording()) showFpsDialog()
        }

        // Bitrate picker
        findViewById<View>(R.id.row_setting_bitrate)?.setOnClickListener {
            if (ensureNotRecording()) showBitrateDialog()
        }

        // Codec picker
        findViewById<View>(R.id.row_setting_codec)?.setOnClickListener {
            if (ensureNotRecording()) showCodecDialog()
        }

        // Audio Source picker
        findViewById<View>(R.id.row_setting_audio_source)?.setOnClickListener {
            if (ensureNotRecording()) showAudioSourceDialog()
        }

        // Mic Type picker
        findViewById<View>(R.id.row_setting_mic_type)?.setOnClickListener {
            if (ensureNotRecording()) showMicTypeDialog()
        }

        // Countdown picker
        findViewById<View>(R.id.row_setting_countdown)?.setOnClickListener {
            if (ensureNotRecording()) showCountdownDialog()
        }

        // Spec Analyzer shortcut
        findViewById<View>(R.id.row_spec_analyzer)?.setOnClickListener {
            SpecAnalysisDialog.show(this) {
                updateUIValues()
            }
        }

        // Reset Settings
        findViewById<View>(R.id.row_reset_settings)?.setOnClickListener {
            if (ensureNotRecording()) showResetConfirmationDialog()
        }

        // Active Banner Stop Button
        findViewById<Button>(R.id.btn_settings_stop_rec)?.setOnClickListener {
            val stopIntent = Intent(this, ScreenRecordingService::class.java).apply {
                action = ScreenRecordingService.ACTION_STOP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(stopIntent)
            } else {
                startService(stopIntent)
            }
            Toast.makeText(this, "Menghentikan rekaman...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensureNotRecording(): Boolean {
        if (ScreenRecordingService.isRecording) {
            Toast.makeText(this, "Pengaturan terkunci saat perekaman sedang berlangsung", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun updateRecordingIndicator() {
        val banner = findViewById<View>(R.id.card_settings_recording_indicator)
        val textTimer = findViewById<TextView>(R.id.text_settings_rec_timer)

        if (ScreenRecordingService.isRecording) {
            banner?.visibility = View.VISIBLE
            val sec = ScreenRecordingService.elapsedSeconds
            val min = sec / 60
            val isPaused = ScreenRecordingService.isPaused
            if (isPaused) {
                textTimer?.text = String.format(Locale.getDefault(), "⏸️ Perekaman Layar Dijeda • %02d:%02d", min, sec % 60)
            } else {
                textTimer?.text = String.format(Locale.getDefault(), "🔴 Perekaman Layar Aktif • %02d:%02d", min, sec % 60)
            }
        } else {
            banner?.visibility = View.GONE
        }
    }

    private fun updateUIValues(skipGamingSwitch: Boolean = false, skipAppLockSwitch: Boolean = false) {
        textResolutionVal?.text = when (appPreferences.resolution) {
            "4K" -> "4K UHD"
            "1080p" -> "1080p FHD"
            "720p" -> "720p HD"
            else -> "480p SD"
        }

        textFpsVal?.text = "${appPreferences.fps} FPS"
        textBitrateVal?.text = "${appPreferences.bitrateMbps} Mbps"

        textCodecVal?.text = if (appPreferences.videoEncoder == "H265") {
            "H.265 (HEVC)"
        } else {
            "H.264 (AVC)"
        }

        textAudioVal?.text = when (appPreferences.audioSource) {
            "BOTH" -> "Mic + Game"
            "MIC" -> "Hanya Mic"
            "INTERNAL" -> "Hanya Game"
            else -> "Mute (Hening)"
        }

        textMicTypeVal?.text = when (appPreferences.micType) {
            "camcorder" -> "Camcorder"
            "voice_comm" -> "Headset Voice"
            "unprocessed" -> "Suara Murni"
            else -> "Default HP"
        }

        textCountdownVal?.text = if (appPreferences.countdownDuration == 0) {
            "Langsung"
        } else {
            "${appPreferences.countdownDuration} Detik"
        }

        textThemeVal?.text = when (appPreferences.themeMode) {
            "LIGHT" -> "Terang"
            "DARK" -> "Gelap"
            else -> "Ikuti Sistem"
        }

        val hasAppLock = appPreferences.isAppLockEnabled
        if (!skipAppLockSwitch) {
            toggleAppLock?.isChecked = hasAppLock
        }
        dividerPin?.visibility = if (hasAppLock) View.VISIBLE else View.GONE
        rowSettingPin?.visibility = if (hasAppLock) View.VISIBLE else View.GONE
        textPinVal?.text = if (appPreferences.appLockPin.isNotEmpty()) "••••" else "Belum Dibuat"

        if (!skipGamingSwitch) {
            toggleGaming?.isChecked = appPreferences.isGamingMode
        }
        findViewById<View>(R.id.gaming_mode_tooltip)?.visibility =
            if (appPreferences.isGamingMode) View.VISIBLE else View.GONE

        toggleFloating?.isChecked = appPreferences.showFloatingOverlay
        toggleFpsStabilizer?.isChecked = appPreferences.fpsStabilizerEnabled
        toggleTurboBoost?.isChecked = appPreferences.gamingTurboBoost
        toggleHardwareAcceleration?.isChecked = appPreferences.hardwareAccelerationEnabled
        toggleInternalAudioBoost?.isChecked = appPreferences.internalAudioStabilizer
        toggleVibrate?.isChecked = appPreferences.vibrateOnStart
        toggleStopScreenOff?.isChecked = appPreferences.stopOnScreenOff
        toggleShowFps?.isChecked = appPreferences.showFpsCounter
        toggleStorageWarning?.isChecked = appPreferences.storageWarningEnabled

        // Disable modifications if active recording is underway
        val isRecording = ScreenRecordingService.isRecording
        toggleGaming?.isEnabled = !isRecording
        toggleFpsStabilizer?.isEnabled = !isRecording
        toggleTurboBoost?.isEnabled = !isRecording
        toggleHardwareAcceleration?.isEnabled = !isRecording
        toggleInternalAudioBoost?.isEnabled = !isRecording
        toggleFloating?.isEnabled = !isRecording
        toggleVibrate?.isEnabled = !isRecording
        toggleStopScreenOff?.isEnabled = !isRecording
        toggleShowFps?.isEnabled = !isRecording
        toggleStorageWarning?.isEnabled = !isRecording
    }

    private fun showResolutionDialog() {
        val options = listOf(
            SelectionOption(
                value = "1080p",
                title = "1080p Full HD",
                subtitle = "1920×1080 • Standar YouTube, TikTok & Gaming",
                badgeLabel = "1080p",
                isRecommended = true
            ),
            SelectionOption(
                value = "4K",
                title = "4K Ultra HD",
                subtitle = "3840×2160 (2160p) • Kualitas Tertinggi & Super Tajam",
                badgeLabel = "4K",
                isRecommended = false
            ),
            SelectionOption(
                value = "720p",
                title = "720p HD",
                subtitle = "1280×720 • Ringan & Hemat Kapasitas Memori",
                badgeLabel = "720p",
                isRecommended = false
            ),
            SelectionOption(
                value = "480p",
                title = "480p SD",
                subtitle = "854×480 • Hemat Kuota, Baterai & Ukuran Ringan",
                badgeLabel = "480p",
                isRecommended = false
            )
        )

        SelectionBottomSheetDialog.show(
            context = this,
            iconRes = R.drawable.ic_video,
            title = "Pilih Resolusi Video",
            subtitle = "Pilih resolusi layar sesuai kapasitas memori & kebutuhan konten",
            options = options,
            selectedValue = appPreferences.resolution
        ) { selectedResolution ->
            appPreferences.resolution = selectedResolution
            updateUIValues()
            com.bangdillah.rekamindulu.backend.FirebaseAuthManager.getInstance(this).updateUserPreferences(
                resolution = selectedResolution
            )
            Toast.makeText(this, "Resolusi diubah ke $selectedResolution", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showFpsDialog() {
        val options = listOf(
            SelectionOption(
                value = 60,
                title = "60 FPS (Sangat Mulus)",
                subtitle = "Standar Gameplay Mulus & Rekaman Profesional",
                badgeLabel = "60",
                isRecommended = true
            ),
            SelectionOption(
                value = 120,
                title = "120 FPS (E-Sports)",
                subtitle = "Mode Kompetitif Refresh Rate Maksimal",
                badgeLabel = "120",
                isRecommended = false
            ),
            SelectionOption(
                value = 90,
                title = "90 FPS (High Refresh)",
                subtitle = "Optimal untuk Layar Flagship 90Hz+",
                badgeLabel = "90",
                isRecommended = false
            ),
            SelectionOption(
                value = 30,
                title = "30 FPS (Standar Normal)",
                subtitle = "Hemat Baterai & Cocok untuk Tutorial Aplikasi",
                badgeLabel = "30",
                isRecommended = false
            )
        )

        SelectionBottomSheetDialog.show(
            context = this,
            iconRes = R.drawable.ic_speed,
            title = "Pilih Frame Rate (FPS)",
            subtitle = "Tentukan kehalusan gerakan animasi pada video rekaman",
            options = options,
            selectedValue = appPreferences.fps
        ) { selectedFps ->
            appPreferences.fps = selectedFps
            appPreferences.isGamingMode = selectedFps >= 60
            if (selectedFps < 60) {
                appPreferences.fpsStabilizerEnabled = false
            }
            updateUIValues()
            com.bangdillah.rekamindulu.backend.FirebaseAuthManager.getInstance(this).updateUserPreferences(
                fps = selectedFps,
                gamingMode = appPreferences.isGamingMode
            )
            Toast.makeText(this, "Frame rate disetel ke $selectedFps FPS", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showBitrateDialog() {
        val options = listOf(
            SelectionOption(
                value = 12,
                title = "12 Mbps (High Definition)",
                subtitle = "Video Jernih & Detail Tajam Tanpa Pecah",
                badgeLabel = "12M",
                isRecommended = true
            ),
            SelectionOption(
                value = 16,
                title = "16 Mbps (Maksimal)",
                subtitle = "Kompresi Minimal untuk Kualitas Grafis Tertinggi",
                badgeLabel = "16M",
                isRecommended = false
            ),
            SelectionOption(
                value = 8,
                title = "8 Mbps (Seimbang)",
                subtitle = "Standar Efisien untuk Perekaman Durasi Panjang",
                badgeLabel = "8M",
                isRecommended = false
            ),
            SelectionOption(
                value = 4,
                title = "4 Mbps (Hemat Ruang)",
                subtitle = "Ukuran File Sangat Kecil & Cepat Dibagikan",
                badgeLabel = "4M",
                isRecommended = false
            )
        )

        SelectionBottomSheetDialog.show(
            context = this,
            iconRes = R.drawable.ic_tune,
            title = "Pilih Bitrate Video",
            subtitle = "Kerapatan data per detik menentukan ketajaman & ukuran file",
            options = options,
            selectedValue = appPreferences.bitrateMbps
        ) { selectedBitrate ->
            appPreferences.bitrateMbps = selectedBitrate
            updateUIValues()
            Toast.makeText(this, "Bitrate disetel ke $selectedBitrate Mbps", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCodecDialog() {
        val options = listOf(
            SelectionOption(
                value = "H264",
                title = "H.264 / AVC",
                subtitle = "Kompatibel Luas (Semua Pemutar, WhatsApp, & Media Sosial)",
                badgeLabel = "H264",
                isRecommended = true
            ),
            SelectionOption(
                value = "H265",
                title = "H.265 / HEVC",
                subtitle = "Kompresi Modern (Hemat Ukuran ~40% dengan Kualitas Sama)",
                badgeLabel = "H265",
                isRecommended = false
            )
        )

        SelectionBottomSheetDialog.show(
            context = this,
            iconRes = R.drawable.ic_codec,
            title = "Format Encoder Video",
            subtitle = "Pilih standar kompresi video perangkat keras perangkat Anda",
            options = options,
            selectedValue = appPreferences.videoEncoder
        ) { selectedCodec ->
            appPreferences.videoEncoder = selectedCodec
            updateUIValues()
            Toast.makeText(this, "Encoder disetel ke $selectedCodec", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAudioSourceDialog() {
        val options = listOf(
            SelectionOption(
                value = "BOTH",
                title = "Mikrofon + Audio Game",
                subtitle = "Rekam Lengkap: Suara Vokal & Suara Game Sekaligus",
                badgeLabel = "MIC+",
                isRecommended = true
            ),
            SelectionOption(
                value = "MIC",
                title = "Hanya Suara Mikrofon",
                subtitle = "Fokus Komentar, Vokal & Suara Lingkungan Luar",
                badgeLabel = "MIC",
                isRecommended = false
            ),
            SelectionOption(
                value = "INTERNAL",
                title = "Hanya Audio Internal Game (Stabil)",
                subtitle = "Jalur Bersih: Audio Game Murni Tanpa Noise Luar & Anti-Bentrok Voice Chat",
                badgeLabel = "GAME",
                isRecommended = false
            ),
            SelectionOption(
                value = "MUTE",
                title = "Tanpa Audio (Mute)",
                subtitle = "Rekaman Layar Hening Tanpa Trek Suara",
                badgeLabel = "OFF",
                isRecommended = false
            )
        )

        SelectionBottomSheetDialog.show(
            context = this,
            iconRes = R.drawable.ic_mic,
            title = "Sumber Suara Rekaman",
            subtitle = "Pilih kanal audio yang ingin dimasukkan ke dalam video rekaman",
            options = options,
            selectedValue = appPreferences.audioSource
        ) { selectedSource ->
            appPreferences.audioSource = selectedSource
            updateUIValues()
            Toast.makeText(this, "Sumber audio disetel ke $selectedSource", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMicTypeDialog() {
        val options = listOf(
            SelectionOption(
                value = "default",
                title = "Default (Bawaan Ponsel)",
                subtitle = "Mikrofon Utama Perangkat Ponsel",
                badgeLabel = "MIC",
                isRecommended = true
            ),
            SelectionOption(
                value = "voice_comm",
                title = "Headset / Bluetooth (TWS)",
                subtitle = "Mode Suara Percakapan & Earphone Nirkabel",
                badgeLabel = "BT",
                isRecommended = false
            ),
            SelectionOption(
                value = "camcorder",
                title = "Kamera / Camcorder",
                subtitle = "Audio Eksternal Jernih (Stereo/Wide)",
                badgeLabel = "CAM",
                isRecommended = false
            ),
            SelectionOption(
                value = "unprocessed",
                title = "Suara Murni (Unprocessed)",
                subtitle = "Audio Mentah Alami Tanpa Filter Peredam Bising OS",
                badgeLabel = "RAW",
                isRecommended = false
            )
        )

        SelectionBottomSheetDialog.show(
            context = this,
            iconRes = R.drawable.ic_mic,
            title = "Pilih Mode & Tipe Mikrofon",
            subtitle = "Penyetelan input mikrofon untuk kejernihan vokal maksimal",
            options = options,
            selectedValue = appPreferences.micType
        ) { selectedType ->
            appPreferences.micType = selectedType
            updateUIValues()
            Toast.makeText(this, "Mode mikrofon diubah", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showThemeDialog() {
        val options = listOf(
            SelectionOption(
                value = "SYSTEM",
                title = "Ikuti Tema Sistem Ponsel",
                subtitle = "Otomatis menyesuaikan mode terang / gelap dari OS",
                badgeLabel = "AUTO",
                isRecommended = true
            ),
            SelectionOption(
                value = "LIGHT",
                title = "Tema Terang (Light Mode)",
                subtitle = "Tampilan bersih & cerah",
                badgeLabel = "LIGHT",
                isRecommended = false
            ),
            SelectionOption(
                value = "DARK",
                title = "Tema Gelap (Dark Mode)",
                subtitle = "Nyaman di mata saat malam & hemat konsumsi baterai",
                badgeLabel = "DARK",
                isRecommended = false
            )
        )

        SelectionBottomSheetDialog.show(
            context = this,
            iconRes = R.drawable.ic_palette,
            title = "Pilih Tema Tampilan",
            subtitle = "Pilih nuansa warna antarmuka aplikasi Rekamin Dulu",
            options = options,
            selectedValue = appPreferences.themeMode
        ) { selectedTheme ->
            appPreferences.themeMode = selectedTheme
            appPreferences.applyTheme()
            updateUIValues()
        }
    }

    private fun showSetPinDialog(callback: ((Boolean) -> Unit)? = null) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Masukkan 4 angka PIN"
            maxLines = 1
            setPadding(56, 40, 56, 40)
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.textTitle))
            setHintTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.textCaption))
        }

        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_lock)
            .setTitle("Atur PIN Keamanan")
            .setMessage("PIN ini akan diminta saat membuka aplikasi untuk mengamankan rekaman video Anda:")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                val pin = input.text.toString().trim()
                if (pin.length < 4) {
                    Toast.makeText(this, "PIN minimal 4 angka", Toast.LENGTH_SHORT).show()
                    callback?.invoke(false)
                } else {
                    appPreferences.appLockPin = pin
                    appPreferences.isAppLockEnabled = true
                    updateUIValues()
                    Toast.makeText(this, "PIN keamanan berhasil disimpan!", Toast.LENGTH_SHORT).show()
                    callback?.invoke(true)
                }
            }
            .setNegativeButton("Batal") { _, _ ->
                callback?.invoke(false)
            }
            .show()
    }

    private fun showCountdownDialog() {
        val options = listOf(
            SelectionOption(
                value = 3,
                title = "3 Detik",
                subtitle = "Pilihan Standar & Cukup Waktu Bersiap",
                badgeLabel = "3s",
                isRecommended = true
            ),
            SelectionOption(
                value = 5,
                title = "5 Detik",
                subtitle = "Waktu Persiapan Sedang Sebelum Layar Direkam",
                badgeLabel = "5s",
                isRecommended = false
            ),
            SelectionOption(
                value = 10,
                title = "10 Detik",
                subtitle = "Persiapan Ekstra Panjang",
                badgeLabel = "10s",
                isRecommended = false
            ),
            SelectionOption(
                value = 0,
                title = "Tanpa Countdown (0 Detik)",
                subtitle = "Perekaman Layar Langsung Dimulai Seketika",
                badgeLabel = "0s",
                isRecommended = false
            )
        )

        SelectionBottomSheetDialog.show(
            context = this,
            iconRes = R.drawable.ic_timer,
            title = "Waktu Hitung Mundur",
            subtitle = "Jeda persiapan sebelum proses perekaman layar dimulai",
            options = options,
            selectedValue = appPreferences.countdownDuration
        ) { selectedCountdown ->
            appPreferences.countdownDuration = selectedCountdown
            updateUIValues()
        }
    }

    private fun showResetConfirmationDialog() {
        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_restore)
            .setTitle("Reset Pengaturan?")
            .setMessage("Semua konfigurasi video, audio, dan kontrol akan dikembalikan ke setelan bawaan Rekamin Dulu.")
            .setPositiveButton("Reset") { _, _ ->
                appPreferences.resetToDefaults()
                updateUIValues()
                Toast.makeText(this, "Pengaturan telah direset ke default", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun setupBottomNavigation() {
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation_settings)
        bottomNav?.selectedItemId = R.id.nav_settings
        bottomNav?.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    val intent = Intent(this, HomeActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(intent)
                    finish()
                    true
                }
                R.id.nav_library -> {
                    val intent = Intent(this, LibraryActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(intent)
                    finish()
                    true
                }
                R.id.nav_settings -> true
                else -> false
            }
        }
    }
}