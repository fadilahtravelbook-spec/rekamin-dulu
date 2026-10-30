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
import androidx.appcompat.widget.SwitchCompat
import com.bangdillah.rekamindulu.HomeActivity
import com.bangdillah.rekamindulu.R
import com.bangdillah.rekamindulu.library.LibraryActivity
import com.bangdillah.rekamindulu.service.ScreenRecordingService
import com.bangdillah.rekamindulu.utils.AppPreferences
import com.bangdillah.rekamindulu.utils.SaveTransitionDialog
import com.bangdillah.rekamindulu.utils.SpecAnalysisDialog
import com.google.android.material.bottomnavigation.BottomNavigationView
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

    private var toggleGaming: SwitchCompat? = null
    private var toggleFloating: SwitchCompat? = null
    private var toggleVibrate: SwitchCompat? = null
    private var toggleStopScreenOff: SwitchCompat? = null
    private var toggleShowFps: SwitchCompat? = null
    private var toggleStorageWarning: SwitchCompat? = null
    private var toggleAppLock: SwitchCompat? = null

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

        initViews()
        setupListeners()
        setupBottomNavigation()
    }

    override fun onResume() {
        super.onResume()
        updateUIValues()
        updateRecordingIndicator()

        val filter = IntentFilter().apply {
            addAction(ScreenRecordingService.ACTION_RECORDING_STOPPED)
            addAction(ScreenRecordingService.ACTION_RECORDING_STATUS_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(recordingReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(recordingReceiver, filter)
        }

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
        toggleFloating = findViewById(R.id.toggle_floating_overlay)
        toggleVibrate = findViewById(R.id.toggle_vibrate)
        toggleStopScreenOff = findViewById(R.id.toggle_stop_screen_off)
        toggleShowFps = findViewById(R.id.toggle_show_fps)
        toggleStorageWarning = findViewById(R.id.toggle_storage_warning)
        toggleAppLock = findViewById(R.id.toggle_app_lock)
    }

    private fun setupListeners() {
        // Gaming mode toggle
        toggleGaming?.setOnCheckedChangeListener { _, isChecked ->
            appPreferences.isGamingMode = isChecked
            findViewById<View>(R.id.gaming_mode_tooltip)?.visibility =
                if (isChecked) View.VISIBLE else View.GONE
        }

        // Floating overlay toggle
        toggleFloating?.setOnCheckedChangeListener { _, isChecked ->
            appPreferences.showFloatingOverlay = isChecked
            if (isChecked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
                AlertDialog.Builder(this)
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
                    updateUIValues()
                }
            } else {
                appPreferences.isAppLockEnabled = false
                updateUIValues()
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

    private fun updateUIValues() {
        textResolutionVal?.text = when (appPreferences.resolution) {
            "4K" -> "4K (Ultra HD 2160p)"
            "1080p" -> "1080p (Full HD)"
            "720p" -> "720p (HD Standar)"
            else -> "480p (SD Ringan)"
        }

        textFpsVal?.text = "${appPreferences.fps} FPS"
        textBitrateVal?.text = "${appPreferences.bitrateMbps} Mbps"

        textCodecVal?.text = if (appPreferences.videoEncoder == "H265") {
            "H.265 (HEVC - Hemat Ukuran 40%)"
        } else {
            "H.264 (AVC - Kompatibel Tinggi)"
        }

        textAudioVal?.text = when (appPreferences.audioSource) {
            "BOTH" -> "Mikrofon & Audio Game"
            "MIC" -> "Hanya Mikrofon"
            "INTERNAL" -> "Hanya Audio Game"
            else -> "Tanpa Suara (Mute)"
        }

        textMicTypeVal?.text = when (appPreferences.micType) {
            "camcorder" -> "Kamera / Camcorder"
            "voice_comm" -> "Headset / Bluetooth (Voice)"
            "unprocessed" -> "Suara Murni (Unprocessed)"
            else -> "Default (Bawaan HP)"
        }

        textCountdownVal?.text = if (appPreferences.countdownDuration == 0) {
            "Tanpa Hitung Mundur (Langsung)"
        } else {
            "${appPreferences.countdownDuration} Detik"
        }

        textThemeVal?.text = when (appPreferences.themeMode) {
            "LIGHT" -> "Terang (Light Mode)"
            "DARK" -> "Gelap (Dark Mode)"
            else -> "Ikuti Tema Sistem"
        }

        val hasAppLock = appPreferences.isAppLockEnabled
        toggleAppLock?.isChecked = hasAppLock
        dividerPin?.visibility = if (hasAppLock) View.VISIBLE else View.GONE
        rowSettingPin?.visibility = if (hasAppLock) View.VISIBLE else View.GONE
        textPinVal?.text = if (appPreferences.appLockPin.isNotEmpty()) "•••• (Tersetel)" else "Belum Dibuat"

        toggleGaming?.isChecked = appPreferences.isGamingMode
        toggleFloating?.isChecked = appPreferences.showFloatingOverlay
        toggleVibrate?.isChecked = appPreferences.vibrateOnStart
        toggleStopScreenOff?.isChecked = appPreferences.stopOnScreenOff
        toggleShowFps?.isChecked = appPreferences.showFpsCounter
        toggleStorageWarning?.isChecked = appPreferences.storageWarningEnabled

        // Disable modifications if active recording is underway
        val isRecording = ScreenRecordingService.isRecording
        toggleGaming?.isEnabled = !isRecording
        toggleFloating?.isEnabled = !isRecording
        toggleVibrate?.isEnabled = !isRecording
        toggleStopScreenOff?.isEnabled = !isRecording
        toggleShowFps?.isEnabled = !isRecording
        toggleStorageWarning?.isEnabled = !isRecording
    }

    private fun showResolutionDialog() {
        val options = arrayOf("1080p (Full HD - Disarankan)", "720p (HD Hemat Memori)", "4K (Ultra HD)", "480p (SD Hemat Daya)")
        val values = arrayOf("1080p", "720p", "4K", "480p")
        val currentIndex = values.indexOf(appPreferences.resolution).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Pilih Resolusi Video")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                appPreferences.resolution = values[which]
                updateUIValues()
                dialog.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showFpsDialog() {
        val options = arrayOf("60 FPS (Sangat Mulus - Standar)", "30 FPS (Standar Normal)", "90 FPS (Layar Refresh Tinggi)", "120 FPS (Mode E-Sports)")
        val values = arrayOf(60, 30, 90, 120)
        val currentIndex = values.indexOf(appPreferences.fps).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Pilih Frame Rate (FPS)")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                appPreferences.fps = values[which]
                updateUIValues()
                dialog.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showBitrateDialog() {
        val options = arrayOf("12 Mbps (Tinggi & Jernih)", "16 Mbps (Maksimal High-Def)", "8 Mbps (Seimbang)", "4 Mbps (Hemat Ruang)")
        val values = arrayOf(12, 16, 8, 4)
        val currentIndex = values.indexOf(appPreferences.bitrateMbps).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Pilih Bitrate Video")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                appPreferences.bitrateMbps = values[which]
                updateUIValues()
                dialog.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showCodecDialog() {
        val options = arrayOf("H.264 / AVC (Kompatibel Semua Pemutar Video)", "H.265 / HEVC (Ukuran File 40% Lebih Kecil)")
        val values = arrayOf("H264", "H265")
        val currentIndex = values.indexOf(appPreferences.videoEncoder).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Format Encoder Video")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                appPreferences.videoEncoder = values[which]
                updateUIValues()
                dialog.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showAudioSourceDialog() {
        val options = arrayOf("Mikrofon + Audio Internal Game", "Hanya Suara Mikrofon", "Hanya Audio Internal Game", "Tanpa Audio (Mute)")
        val values = arrayOf("BOTH", "MIC", "INTERNAL", "MUTE")
        val currentIndex = values.indexOf(appPreferences.audioSource).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Sumber Suara Rekaman")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                appPreferences.audioSource = values[which]
                updateUIValues()
                dialog.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showMicTypeDialog() {
        val options = arrayOf(
            "Default (Mikrofon Bawaan Ponsel)",
            "Headset / Bluetooth (Mode Percakapan)",
            "Kamera / Camcorder (Audio Eksternal)",
            "Suara Murni (Unprocessed Audio)"
        )
        val values = arrayOf("default", "voice_comm", "camcorder", "unprocessed")
        val currentIndex = values.indexOf(appPreferences.micType).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Pilih Mode & Tipe Mikrofon")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                appPreferences.micType = values[which]
                updateUIValues()
                dialog.dismiss()
                Toast.makeText(this, "Mode mikrofon diubah", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showThemeDialog() {
        val options = arrayOf("Ikuti Sistem (Otomatis)", "Tema Terang (Light)", "Tema Gelap (Dark)")
        val values = arrayOf("SYSTEM", "LIGHT", "DARK")
        val currentIndex = values.indexOf(appPreferences.themeMode).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Pilih Tema Tampilan")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                appPreferences.themeMode = values[which]
                appPreferences.applyTheme()
                updateUIValues()
                dialog.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showSetPinDialog(callback: ((Boolean) -> Unit)? = null) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Masukkan 4 angka PIN"
            maxLines = 1
            setPadding(48, 36, 48, 36)
        }

        AlertDialog.Builder(this)
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
        val options = arrayOf("3 Detik (Rekomendasi)", "5 Detik", "10 Detik", "Tanpa Countdown (Mulai Seketika)")
        val values = arrayOf(3, 5, 10, 0)
        val currentIndex = values.indexOf(appPreferences.countdownDuration).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Waktu Hitung Mundur")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                appPreferences.countdownDuration = values[which]
                updateUIValues()
                dialog.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showResetConfirmationDialog() {
        AlertDialog.Builder(this)
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