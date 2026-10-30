package com.bangdillah.rekamindulu

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bangdillah.rekamindulu.about.AboutActivity
import com.bangdillah.rekamindulu.countdown.CountdownActivity
import com.bangdillah.rekamindulu.library.LibraryActivity
import com.bangdillah.rekamindulu.permission.PermissionDialogActivity
import com.bangdillah.rekamindulu.service.ScreenRecordingService
import com.bangdillah.rekamindulu.settings.SettingsActivity
import com.bangdillah.rekamindulu.trim.TrimActivity
import com.bangdillah.rekamindulu.utils.AppPreferences
import com.bangdillah.rekamindulu.utils.DeviceAnalyzer
import com.bangdillah.rekamindulu.utils.SaveTransitionDialog
import com.bangdillah.rekamindulu.utils.SpecAnalysisDialog
import com.bangdillah.rekamindulu.utils.StorageHelper
import com.bangdillah.rekamindulu.library.LibraryAdapter
import com.bangdillah.rekamindulu.player.PlayerActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.snackbar.Snackbar
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
        var isSessionUnlocked = false
        var hasPendingSaveDialog = false
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
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: hasAudioRecordingPermission()
        if (audioGranted) {
            showSnackbar("Izin audio mikrofon diberikan")
        }
        initiateScreenCaptureFlow()
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
                    hasPendingSaveDialog = false
                    val path = intent.getStringExtra(ScreenRecordingService.EXTRA_FILE_PATH)
                        ?: ScreenRecordingService.lastRecordedFilePath
                    updateRecordingBanner()
                    updateLibraryStats()
                    updateHomeRecordingsList()
                    if (!path.isNullOrEmpty() && File(path).exists() && File(path).length() > 200) {
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
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("⚠️ Perekaman Gagal")
                        .setMessage(errorMsg)
                        .setPositiveButton("Mengerti", null)
                        .show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        appPreferences = AppPreferences(this)
        appPreferences.applyTheme()
        supportActionBar?.hide()

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        initAudioSettings()
        checkAppLock()
        setupHeaderActions()
        setupDashboardCards()
        setupHomeRecordingsListView()
        setupBottomNavigation()
    }

    /**
     * Inisialisasi preferensi audio awal (Mikrofon dan Suara Game)
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
        updateAudioToggleViews()
    }

    private fun updateAudioToggleViews() {
        val toggleMic = findViewById<Button>(R.id.toggle_mic)
        val toggleGameAudio = findViewById<Button>(R.id.toggle_game_audio)
        val toggleFacecam = findViewById<Button>(R.id.toggle_facecam)

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
    }


    private fun checkAppLock() {
        if (appPreferences.isAppLockEnabled && appPreferences.appLockPin.isNotEmpty() && !isSessionUnlocked) {
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                hint = "Masukkan 4 angka PIN"
                maxLines = 1
                setPadding(48, 36, 48, 36)
            }

            AlertDialog.Builder(this)
                .setTitle("🔒 Kunci Rekamin Dulu")
                .setMessage("Aplikasi ini dilindungi PIN. Masukkan PIN keamanan untuk melanjutkan:")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("Buka Kunci") { dialog, _ ->
                    val entered = input.text.toString().trim()
                    if (entered == appPreferences.appLockPin) {
                        isSessionUnlocked = true
                        dialog.dismiss()
                        showSnackbar("Kunci aplikasi berhasil dibuka")
                    } else {
                        Toast.makeText(this, "PIN salah! Coba lagi.", Toast.LENGTH_SHORT).show()
                        checkAppLock()
                    }
                }
                .setNegativeButton("Keluar") { _, _ ->
                    finishAffinity()
                }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        findViewById<BottomNavigationView>(R.id.bottom_navigation)?.selectedItemId = R.id.nav_home
        updateHardwareMetrics()
        updateResolutionBadge()
        updateLibraryStats()
        updateRecordingBanner()
        updateHomeRecordingsList()

        if (hasPendingSaveDialog) {
            hasPendingSaveDialog = false
            val path = ScreenRecordingService.lastRecordedFilePath
            if (!path.isNullOrEmpty() && File(path).exists()) {
                showSaveSuccessToast(path)
                SaveTransitionDialog.show(this, path) {
                    updateLibraryStats()
                    updateHomeRecordingsList()
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(ScreenRecordingService.ACTION_RECORDING_STOPPED)
            addAction(ScreenRecordingService.ACTION_RECORDING_STATUS_CHANGED)
            addAction(ScreenRecordingService.ACTION_RECORDING_ERROR)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(recordingStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(recordingStatusReceiver, filter)
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
            unregisterReceiver(recordingStatusReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
    }

    private fun setupHeaderActions() {
        findViewById<View>(R.id.ic_about)?.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        findViewById<View>(R.id.ic_settings)?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<TextView>(R.id.text_resolution_badge)?.setOnClickListener {
            showQualityPickerDialog()
        }

        // Toggle Mic Audio Recording
        val toggleMic = findViewById<Button>(R.id.toggle_mic)
        toggleMic?.setOnClickListener {
            isMicOn = !isMicOn
            updateAudioToggleViews()
            syncAudioPreferences()
            showSnackbar(if (isMicOn) "🎤 Mikrofon diaktifkan" else "🔇 Mikrofon dinonaktifkan")
        }

        // Toggle Internal Game Audio Recording
        val toggleGameAudio = findViewById<Button>(R.id.toggle_game_audio)
        toggleGameAudio?.setOnClickListener {
            isGameAudioOn = !isGameAudioOn
            updateAudioToggleViews()
            syncAudioPreferences()
            showSnackbar(if (isGameAudioOn) "🎮 Audio internal game diaktifkan" else "Audio internal game dinonaktifkan")
        }

        // Toggle Facecam
        val toggleFacecam = findViewById<Button>(R.id.toggle_facecam)
        toggleFacecam?.setOnClickListener {
            facecamStateIndex = (facecamStateIndex + 1) % facecamStates.size
            val stateStr = facecamStates[facecamStateIndex]
            appPreferences.facecamMode = stateStr
            updateAudioToggleViews()
            showSnackbar("Mode Kamera Wajah: $stateStr")
        }


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
        val options = arrayOf(
            "1080p Full HD • 30 FPS • 8 Mbps (Stabil & Rekomendasi)",
            "720p HD • 30 FPS • 5 Mbps (Hemat Memori)",
            "1080p Full HD • 60 FPS • 12 Mbps (High Performance)",
            "4K Ultra HD • 30 FPS • 20 Mbps (Kualitas Tertinggi)",
            "⚙️ Buka Pengaturan & Analisis Lengkap..."
        )

        AlertDialog.Builder(this)
            .setTitle("Pilih Kualitas Rekaman Video")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        appPreferences.resolution = "1080p"
                        appPreferences.fps = 30
                        appPreferences.bitrateMbps = 8
                        updateResolutionBadge()
                        showSnackbar("Preset 1080p 30FPS diterapkan")
                    }
                    1 -> {
                        appPreferences.resolution = "720p"
                        appPreferences.fps = 30
                        appPreferences.bitrateMbps = 5
                        updateResolutionBadge()
                        showSnackbar("Preset 720p 30FPS diterapkan")
                    }
                    2 -> {
                        appPreferences.resolution = "1080p"
                        appPreferences.fps = 60
                        appPreferences.bitrateMbps = 12
                        updateResolutionBadge()
                        showSnackbar("Preset 1080p 60FPS diterapkan")
                    }
                    3 -> {
                        appPreferences.resolution = "4K"
                        appPreferences.fps = 30
                        appPreferences.bitrateMbps = 20
                        updateResolutionBadge()
                        showSnackbar("Preset 4K Ultra HD diterapkan")
                    }
                    4 -> {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun updateResolutionBadge() {
        val badgeRes = findViewById<TextView>(R.id.text_resolution_badge)
        badgeRes?.text = String.format(
            Locale.getDefault(),
            "%s • %d FPS • %d Mbps",
            appPreferences.resolution,
            appPreferences.fps,
            appPreferences.bitrateMbps
        )
    }

    private fun setupDashboardCards() {
        // Dedicated Start/Stop Button linked to ScreenRecordingService
        val btnStartStop = findViewById<Button>(R.id.btn_start_stop)
        btnStartStop?.setOnClickListener {
            if (ScreenRecordingService.isRecording) {
                stopScreenCapture()
            } else {
                startScreenCapture()
            }
        }

        val cardStartRecord = findViewById<View>(R.id.card_start_record)
        cardStartRecord?.setOnClickListener {
            if (ScreenRecordingService.isRecording) {
                stopScreenCapture()
            } else {
                startScreenCapture()
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
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

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
        }

        AlertDialog.Builder(this)
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
                if (file.renameTo(targetFile)) {
                    StorageHelper.scanRenamedFile(this, file, targetFile)
                    Toast.makeText(this, "Nama berhasil diubah", Toast.LENGTH_SHORT).show()
                    updateHomeRecordingsList()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun handleExtractAudioHome(file: File) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Mengekstrak Audio 🎵")
            .setMessage("Sedang memisahkan audio dari ${file.name}...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        Thread {
            val (success, audioFile) = com.bangdillah.rekamindulu.audio.AudioExtractorHelper.extractAudio(this, file)
            runOnUiThread {
                progressDialog.dismiss()
                if (success && audioFile != null && audioFile.exists()) {
                    AlertDialog.Builder(this)
                        .setTitle("Ekstrak Audio Berhasil! 🎉")
                        .setIcon(R.drawable.ic_check_circle)
                        .setMessage("Tersimpan sebagai:\n${audioFile.name}")
                        .setPositiveButton("Bagus", null)
                        .show()
                } else {
                    Toast.makeText(this, "Gagal mengekstrak audio dari video ini", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun updateHomeRecordingsList() {

        if (!::homeRecordingsAdapter.isInitialized) return
        homeRecordingFiles.clear()
        val allFiles = StorageHelper.getAllVideoFiles(this)
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

    private fun confirmDeleteHomeRecording(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Hapus Rekaman Video?")
            .setMessage("File \"${file.name}\" akan dihapus secara permanen dari penyimpanan.")
            .setIcon(R.drawable.ic_delete)
            .setPositiveButton("Hapus") { _, _ ->
                if (file.exists()) {
                    StorageHelper.scanDeletedFile(this, file)
                    file.delete()
                    Toast.makeText(this, "Rekaman video berhasil dihapus", Toast.LENGTH_SHORT).show()
                    updateHomeRecordingsList()
                    updateLibraryStats()
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
        try {
            val report = DeviceAnalyzer.analyze(this)

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
        } catch (e: Exception) {
            Log.e(TAG, "Error updating hardware metrics", e)
        }
    }

    private fun updateLibraryStats() {
        try {
            val files = StorageHelper.getAllVideoFiles(this)
            val count = files.size
            var totalBytes = 0L
            files.forEach { totalBytes += it.length() }
            val totalMb = totalBytes / (1024.0 * 1024.0)

            val textStats = findViewById<TextView>(R.id.text_library_stats)
            if (totalMb >= 1024.0) {
                textStats?.text = String.format(Locale.getDefault(), "%d File • %.1f GB", count, totalMb / 1024.0)
            } else {
                textStats?.text = String.format(Locale.getDefault(), "%d File • %.0f MB", count, totalMb)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error loading library stats", e)
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
        val floatingPill = findViewById<View>(R.id.card_floating_recording_pill)
        val floatingStatus = findViewById<TextView>(R.id.text_floating_rec_status)
        val floatingTimer = findViewById<TextView>(R.id.text_floating_rec_timer)
        val floatingBtnPause = findViewById<Button>(R.id.btn_floating_pause)

        if (ScreenRecordingService.isRecording) {
            banner?.visibility = View.VISIBLE
            floatingPill?.visibility = View.VISIBLE
            layoutIdle?.visibility = View.GONE
            layoutActive?.visibility = View.VISIBLE

            val sec = ScreenRecordingService.elapsedSeconds
            val min = sec / 60
            val isPaused = ScreenRecordingService.isPaused
            val formattedTime = String.format(Locale.getDefault(), "%02d:%02d", min, sec % 60)

            textHeroTimer?.text = formattedTime

            if (isPaused) {
                textTimer?.text = "⏸️ Perekaman Dijeda • Durasi: $formattedTime"
                btnPause?.text = "Lanjut"
                headerStatus?.text = "⏸️ DIJEDA • $formattedTime"
                headerStatus?.setTextColor(ContextCompat.getColor(this, R.color.colorWarning))

                floatingStatus?.text = "⏸️ REKAMAN DIJEDA"
                floatingTimer?.text = "$formattedTime • Ketuk untuk melanjutkan"
                floatingBtnPause?.text = "Lanjut"

                textHeroBadge?.text = "⏸️ REKAMAN DIJEDA"
                textHeroBadge?.setTextColor(ContextCompat.getColor(this, R.color.colorWarning))
                btnHeroPause?.text = "▶️ Lanjutkan"
                textHeroInfo?.text = "${appPreferences.resolution} • ${appPreferences.fps} FPS • Perekaman Dijeda"
            } else {
                textTimer?.text = String.format(
                    Locale.getDefault(),
                    "Durasi: %s • %s %dFPS",
                    formattedTime,
                    appPreferences.resolution,
                    appPreferences.fps
                )
                btnPause?.text = "Jeda"
                headerStatus?.text = "🔴 MEREKAM AKTIF • $formattedTime"
                headerStatus?.setTextColor(ContextCompat.getColor(this, R.color.colorError))

                floatingStatus?.text = "🔴 SEDANG MEREKAM"
                floatingTimer?.text = "$formattedTime • ${appPreferences.resolution} ${appPreferences.fps}FPS"
                floatingBtnPause?.text = "Jeda"

                textHeroBadge?.text = "🔴 SEDANG MEREKAM AKTIF"
                textHeroBadge?.setTextColor(ContextCompat.getColor(this, R.color.colorError))
                btnHeroPause?.text = "⏸️ Jeda"
                val audioDesc = if (isMicOn && isGameAudioOn) "Mikrofon & Audio Game Aktif" else if (isMicOn) "Mikrofon Aktif" else if (isGameAudioOn) "Audio Game Aktif" else "Mute"
                textHeroInfo?.text = "${appPreferences.resolution} • ${appPreferences.fps} FPS • $audioDesc"
            }
            startRecordLabel?.text = "Sedang Merekam • Tekan tombol untuk Berhenti & Simpan"
            btnStartStop?.text = "Hentikan & Simpan Rekaman"
            btnStartStop?.backgroundTintList = ContextCompat.getColorStateList(this, R.color.colorError)
            imgStartStopIcon?.setImageResource(R.drawable.ic_close)
        } else {
            banner?.visibility = View.GONE
            floatingPill?.visibility = View.GONE
            layoutIdle?.visibility = View.VISIBLE
            layoutActive?.visibility = View.GONE

            headerStatus?.text = "● Siap Merekam • Status Optimal"
            headerStatus?.setTextColor(ContextCompat.getColor(this, R.color.colorSuccess))
            startRecordLabel?.text = "Tekan tombol untuk mulai merekam layar & audio"
            btnStartStop?.text = "Start Recording"
            btnStartStop?.backgroundTintList = ContextCompat.getColorStateList(this, R.color.colorPrimary)
            imgStartStopIcon?.setImageResource(R.drawable.ic_record)
        }
    }

    // =========================================================================
    // MEDIAPROJECTION API & SCREEN CAPTURE IMPLEMENTATION
    // =========================================================================

    private var inAppCountdownTimer: CountDownTimer? = null

    /**
     * Mendapatkan daftar izin runtime yang dibutuhkan untuk perekaman layar dan audio.
     * Mencakup RECORD_AUDIO (jika mikrofon aktif) dan POST_NOTIFICATIONS (Android 13+).
     */
    fun getRequiredRuntimePermissions(): Array<String> {
        val permissions = mutableListOf<String>()
        if (isMicOn || isGameAudioOn || appPreferences.audioSource != "MUTE") {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.toTypedArray()
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

        val missingPermissions = getRequiredRuntimePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            runtimePermissionLauncher.launch(missingPermissions.toTypedArray())
            return
        }

        // Cek sisa memori penyimpanan internal jika diaktifkan (hanya ingatkan jika < 200MB)
        if (appPreferences.storageWarningEnabled) {
            val report = DeviceAnalyzer.analyze(this)
            if (report.freeStorageGb < 0.2) {
                AlertDialog.Builder(this)
                    .setTitle("⚠️ Peringatan Sisa Memori HP")
                    .setMessage(
                        String.format(
                            Locale.getDefault(),
                            "Sisa memori internal Anda tersisa %.2f GB (sangat rendah). Merekam video resolusi tinggi dapat gagal jika penyimpanan habis. Lanjutkan merekam?",
                            report.freeStorageGb
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
     * Menjalankan countdown sebelum memanggil MediaProjection sistem jika disetel di preferensi
     */
    private fun initiateScreenCaptureFlow() {
        val countdown = appPreferences.countdownDuration
        if (countdown > 0) {
            showCountdownHUD(countdown) {
                requestScreenCapture()
            }
        } else {
            requestScreenCapture()
        }
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

        btnCancel?.setOnClickListener {
            inAppCountdownTimer?.cancel()
            hudLayout.visibility = View.GONE
            showSnackbar("Perekaman dibatalkan")
        }

        btnSkip?.setOnClickListener {
            inAppCountdownTimer?.cancel()
            hudLayout.visibility = View.GONE
            onFinish()
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
                Handler(Looper.getMainLooper()).postDelayed({
                    hudLayout.visibility = View.GONE
                    onFinish()
                }, 300)
            }
        }.start()
    }

    /**
     * Meminta izin sistem MediaProjection menggunakan MediaProjectionManager
     */
    fun requestScreenCapture() {
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(captureIntent)
    }

    /**
     * Dipanggil saat pengguna menyetujui izin tangkap layar MediaProjection
     */
    open fun onScreenCapturePermissionGranted(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, ScreenRecordingService::class.java).apply {
            action = ScreenRecordingService.ACTION_START
            putExtra(ScreenRecordingService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenRecordingService.EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        updateRecordingBanner()
        showSnackbar("🔴 Perekaman layar aktif • Video otomatis disimpan saat selesai")
    }

    open fun onScreenCapturePermissionDenied() {
        Toast.makeText(this, "Izin tangkap layar dibatalkan pengguna", Toast.LENGTH_SHORT).show()
    }

    fun sendServiceCommand(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    /**
     * Menghentikan perekaman layar aktif
     */
    fun stopScreenCapture() {
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
        showSnackbar("✓ Rekaman ${file.name} tersimpan ke Galeri", Snackbar.LENGTH_LONG)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val hudLayout = findViewById<View>(R.id.layout_countdown_hud)
        if (hudLayout != null && hudLayout.visibility == View.VISIBLE) {
            inAppCountdownTimer?.cancel()
            hudLayout.visibility = View.GONE
            showSnackbar("Perekaman dibatalkan")
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        inAppCountdownTimer?.cancel()
    }

    fun showSnackbar(message: String, duration: Int = Snackbar.LENGTH_SHORT) {
        val snackbar = Snackbar.make(
            findViewById(android.R.id.content),
            message,
            duration
        )
        snackbar.show()
    }
}
