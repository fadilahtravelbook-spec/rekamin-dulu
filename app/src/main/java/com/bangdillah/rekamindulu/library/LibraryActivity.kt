package com.bangdillah.rekamindulu.library

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bangdillah.rekamindulu.HomeActivity
import com.bangdillah.rekamindulu.MainActivity
import com.bangdillah.rekamindulu.R
import com.bangdillah.rekamindulu.player.PlayerActivity
import com.bangdillah.rekamindulu.service.ScreenRecordingService
import com.bangdillah.rekamindulu.settings.SettingsActivity
import com.bangdillah.rekamindulu.trim.TrimActivity
import com.bangdillah.rekamindulu.audio.AudioExtractorHelper
import com.bangdillah.rekamindulu.utils.AppLockHelper
import com.bangdillah.rekamindulu.utils.AppPreferences
import com.bangdillah.rekamindulu.utils.SaveTransitionDialog
import com.bangdillah.rekamindulu.utils.SelectionBottomSheetDialog
import com.bangdillah.rekamindulu.utils.SelectionOption
import com.bangdillah.rekamindulu.utils.StorageHelper
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.lifecycleScope
import com.bangdillah.rekamindulu.floating.FloatingOverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class LibraryActivity : AppCompatActivity() {

    companion object {
        const val ONE_MINUTE_MS = 60_000L

        fun isDurationOverOneMinute(durationMs: Long): Boolean {
            return durationMs > ONE_MINUTE_MS
        }
    }

    private lateinit var appPreferences: AppPreferences
    private lateinit var adapter: LibraryAdapter
    private val allVideoFiles = mutableListOf<File>()
    private val displayedVideoFiles = mutableListOf<File>()
    private val videoDurationsMs = ConcurrentHashMap<String, Long>()

    private var currentSortOrder = 0 // 0: Terbaru, 1: Terlama, 2: Nama A-Z, 3: Nama Z-A, 4: Terbesar, 5: Terkecil
    private var currentFilterMode = 0 // 0: Semua, 1: Game, 2: Hari Ini, 3: > 1 Menit

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

    private var extractProgressDialog: AlertDialog? = null
    private var appLockDialog: AlertDialog? = null

    private val recordingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ScreenRecordingService.ACTION_RECORDING_STOPPED -> {
                    MainActivity.hasPendingSaveDialog = false
                    val path = intent.getStringExtra(ScreenRecordingService.EXTRA_FILE_PATH)
                        ?: ScreenRecordingService.lastRecordedFilePath
                    updateRecordingIndicator()
                    loadVideoFiles()
                    if (!path.isNullOrEmpty() && File(path).exists() && File(path).length() > 10240) {
                        SaveTransitionDialog.show(this@LibraryActivity, path) {
                            loadVideoFiles()
                        }
                    }
                }
                ScreenRecordingService.ACTION_RECORDING_STATUS_CHANGED -> {
                    updateRecordingIndicator()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appPreferences = AppPreferences(this)
        appPreferences.applyTheme()
        setContentView(R.layout.library_screen)
        supportActionBar?.hide()
        checkAppLock()

        val recyclerView = findViewById<RecyclerView>(R.id.library_recycler_view)
        recyclerView?.layoutManager = LinearLayoutManager(this)

        adapter = LibraryAdapter(
            videoFiles = displayedVideoFiles,
            onItemClick = { file ->
                val intent = Intent(this, PlayerActivity::class.java).apply {
                    putExtra("VIDEO_PATH", file.absolutePath)
                }
                startActivity(intent)
            },
            onRenameClick = { file ->
                showRenameDialog(file)
            },
            onEditClick = { file ->
                val intent = Intent(this, TrimActivity::class.java).apply {
                    putExtra("VIDEO_PATH", file.absolutePath)
                }
                startActivity(intent)
            },
            onExtractAudioClick = { file ->
                handleExtractAudio(file)
            },
            onShareClick = { file ->
                shareVideoFile(file)
            },
            onDeleteClick = { file ->
                confirmDelete(file)
            }
        )

        recyclerView?.adapter = adapter

        setupHeaderButtons()
        setupSearchBar()
        setupFilterChips()
        setupBottomNavigation()
    }

    private fun checkAppLock() {
        appLockDialog = AppLockHelper.showPinGateIfNeeded(
            activity = this,
            appPreferences = appPreferences,
            currentDialog = appLockDialog,
            title = "🔒 Kunci Galeri Rekaman",
            message = "Galeri rekaman dilindungi PIN. Masukkan PIN keamanan untuk mengakses:",
            negativeButtonLabel = "Kembali",
            onCancel = { finish() },
            onUnlocked = { loadVideoFiles() }
        )
    }

    override fun onResume() {
        super.onResume()
        MainActivity.consumePendingSaveIfHandled(
            openedFilePath = null,
            lastRecordedPath = ScreenRecordingService.lastRecordedFilePath,
            forceClear = true
        )
        findViewById<BottomNavigationView>(R.id.bottom_navigation_library)?.selectedItemId = R.id.nav_library
        checkAppLock()
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

    override fun onDestroy() {
        super.onDestroy()
        liveTimerHandler.removeCallbacksAndMessages(null)
        try { extractProgressDialog?.dismiss() } catch (ignored: Exception) {}
        extractProgressDialog = null
        try { appLockDialog?.dismiss() } catch (ignored: Exception) {}
        appLockDialog = null
    }

    private fun setupHeaderButtons() {
        findViewById<View>(R.id.btn_tambah_rekam)?.setOnClickListener {
            val intent = Intent(this, HomeActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }

        findViewById<View>(R.id.btn_empty_start)?.setOnClickListener {
            val intent = Intent(this, HomeActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }

        findViewById<View>(R.id.btn_empty_demo)?.setOnClickListener {
            Toast.makeText(this, "Membuat video demo MP4...", Toast.LENGTH_SHORT).show()
            StorageHelper.generateDemoVideo(this) { file ->
                if (isFinishing || isDestroyed) return@generateDemoVideo
                if (file != null) {
                    Toast.makeText(this, "Video demo berhasil dibuat!", Toast.LENGTH_SHORT).show()
                    loadVideoFiles()
                } else {
                    Toast.makeText(this, "Gagal membuat video demo", Toast.LENGTH_SHORT).show()
                }
            }
        }

        findViewById<Button>(R.id.btn_library_stop_rec)?.setOnClickListener {
            if (!ScreenRecordingService.isRecording && !ScreenRecordingService.isPaused) {
                updateRecordingIndicator()
                return@setOnClickListener
            }
            val stopIntent = Intent(this, ScreenRecordingService::class.java).apply {
                action = ScreenRecordingService.ACTION_STOP
            }
            try {
                startService(stopIntent)
            } catch (e: IllegalStateException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        startForegroundService(stopIntent)
                    } catch (ignored: Exception) {}
                }
            }
            Toast.makeText(this, "Menghentikan rekaman...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateRecordingIndicator() {
        val banner = findViewById<View>(R.id.card_library_recording_indicator)
        val textTimer = findViewById<TextView>(R.id.text_library_rec_timer)

        if (ScreenRecordingService.isRecording) {
            banner?.visibility = View.VISIBLE
            val sec = ScreenRecordingService.elapsedSeconds
            val isPaused = ScreenRecordingService.isPaused
            val durationStr = FloatingOverlayService.formatDuration(sec)
            if (isPaused) {
                textTimer?.text = "⏸️ Perekaman Layar Dijeda • $durationStr"
            } else {
                textTimer?.text = "🔴 Perekaman Layar Aktif • $durationStr"
            }
        } else {
            banner?.visibility = View.GONE
        }
    }

    private fun setupSearchBar() {
        val etSearch = findViewById<EditText>(R.id.et_search_library)
        val btnClear = findViewById<ImageView>(R.id.btn_clear_search)

        btnClear?.setOnClickListener {
            etSearch?.setText("")
        }

        etSearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                val query = s?.toString() ?: ""
                btnClear?.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                filterVideos(query)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        findViewById<View>(R.id.btn_sort_library)?.setOnClickListener {
            showSortDialog()
        }
    }

    private fun setupFilterChips() {
        val chipAll = findViewById<TextView>(R.id.chip_filter_all)
        val chipGame = findViewById<TextView>(R.id.chip_filter_game)
        val chipRecent = findViewById<TextView>(R.id.chip_filter_recent)
        val chipLong = findViewById<TextView>(R.id.chip_filter_long)

        fun updateChipsUI() {
            chipAll?.isSelected = (currentFilterMode == 0)
            chipGame?.isSelected = (currentFilterMode == 1)
            chipRecent?.isSelected = (currentFilterMode == 2)
            chipLong?.isSelected = (currentFilterMode == 3)
        }

        updateChipsUI()

        chipAll?.setOnClickListener {
            currentFilterMode = 0
            updateChipsUI()
            val query = findViewById<EditText>(R.id.et_search_library)?.text?.toString() ?: ""
            filterVideos(query)
        }

        chipGame?.setOnClickListener {
            currentFilterMode = 1
            updateChipsUI()
            val query = findViewById<EditText>(R.id.et_search_library)?.text?.toString() ?: ""
            filterVideos(query)
        }

        chipRecent?.setOnClickListener {
            currentFilterMode = 2
            updateChipsUI()
            val query = findViewById<EditText>(R.id.et_search_library)?.text?.toString() ?: ""
            filterVideos(query)
        }

        chipLong?.setOnClickListener {
            currentFilterMode = 3
            updateChipsUI()
            val query = findViewById<EditText>(R.id.et_search_library)?.text?.toString() ?: ""
            val missingDurations = allVideoFiles.filter {
                !videoDurationsMs.containsKey(it.absolutePath) && LibraryAdapter.getCachedDurationMs(it) == null
            }
            if (missingDurations.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (file in missingDurations) {
                        videoDurationsMs[file.absolutePath] = LibraryAdapter.getOrExtractDurationMs(file)
                    }
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed && currentFilterMode == 3) {
                            val latestQuery = findViewById<EditText>(R.id.et_search_library)?.text?.toString() ?: ""
                            filterVideos(latestQuery)
                        }
                    }
                }
            }
            filterVideos(query)
        }
    }

    private fun showSortDialog() {
        val sortOptions = listOf(
            SelectionOption(
                value = 0,
                title = "Terbaru (Waktu Merekam)",
                subtitle = "Video rekaman yang paling anyar di urutan teratas",
                badgeLabel = "NEW",
                isRecommended = true
            ),
            SelectionOption(
                value = 1,
                title = "Terlama (Waktu Merekam)",
                subtitle = "Video rekaman awal di urutan teratas",
                badgeLabel = "OLD",
                isRecommended = false
            ),
            SelectionOption(
                value = 2,
                title = "Nama File (A - Z)",
                subtitle = "Urutkan abjad judul rekaman dari huruf awal",
                badgeLabel = "A-Z",
                isRecommended = false
            ),
            SelectionOption(
                value = 3,
                title = "Nama File (Z - A)",
                subtitle = "Urutkan abjad judul rekaman dari huruf akhir",
                badgeLabel = "Z-A",
                isRecommended = false
            ),
            SelectionOption(
                value = 4,
                title = "Ukuran Terbesar",
                subtitle = "File video dengan kapasitas penyimpanan terbesar",
                badgeLabel = "MAX",
                isRecommended = false
            ),
            SelectionOption(
                value = 5,
                title = "Ukuran Terkecil",
                subtitle = "File video dengan kapasitas penyimpanan terkecil",
                badgeLabel = "MIN",
                isRecommended = false
            )
        )

        SelectionBottomSheetDialog.show(
            context = this,
            iconRes = R.drawable.ic_sort,
            title = "Urutkan Galeri Rekaman",
            subtitle = "Pilih kriteria penyusunan daftar rekaman video",
            options = sortOptions,
            selectedValue = currentSortOrder
        ) { selectedOrder ->
            currentSortOrder = selectedOrder
            applySorting()
            val currentQuery = findViewById<EditText>(R.id.et_search_library)?.text?.toString() ?: ""
            filterVideos(currentQuery)
            Toast.makeText(this, "Galeri diurutkan", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applySorting() {
        when (currentSortOrder) {
            0 -> allVideoFiles.sortByDescending { it.lastModified() }
            1 -> allVideoFiles.sortBy { it.lastModified() }
            2 -> allVideoFiles.sortBy { it.name.lowercase(Locale.getDefault()) }
            3 -> allVideoFiles.sortByDescending { it.name.lowercase(Locale.getDefault()) }
            4 -> allVideoFiles.sortByDescending { it.length() }
            5 -> allVideoFiles.sortBy { it.length() }
        }
    }

    private fun filterVideos(query: String) {
        val trimmed = query.trim().lowercase(Locale.getDefault())
        displayedVideoFiles.clear()

        val now = System.currentTimeMillis()
        val oneDayMs = 24 * 60 * 60 * 1000L

        for (file in allVideoFiles) {
            val matchesQuery = trimmed.isEmpty() || file.name.lowercase(Locale.getDefault()).contains(trimmed)
            if (!matchesQuery) continue

            val matchesFilter = when (currentFilterMode) {
                1 -> file.name.contains("game", ignoreCase = true) || file.name.contains("fps", ignoreCase = true)
                2 -> (now - file.lastModified()) <= oneDayMs
                3 -> {
                    val durMs = videoDurationsMs[file.absolutePath] ?: LibraryAdapter.getCachedDurationMs(file) ?: 0L
                    isDurationOverOneMinute(durMs)
                }
                else -> true
            }

            if (matchesFilter) {
                displayedVideoFiles.add(file)
            }
        }

        val emptyState = findViewById<View>(R.id.empty_state)
        val recyclerView = findViewById<RecyclerView>(R.id.library_recycler_view)
        val textEmptyTitle = findViewById<TextView>(R.id.text_empty_title)
        val textEmptySubtitle = findViewById<TextView>(R.id.text_empty_subtitle)
        val btnEmptyStart = findViewById<View>(R.id.btn_empty_start)
        val btnEmptyDemo = findViewById<View>(R.id.btn_empty_demo)

        if (displayedVideoFiles.isEmpty()) {
            emptyState?.visibility = View.VISIBLE
            recyclerView?.visibility = View.GONE

            if (allVideoFiles.isNotEmpty() && trimmed.isNotEmpty()) {
                textEmptyTitle?.text = "Rekaman Tidak Ditemukan"
                textEmptySubtitle?.text = "Tidak ada rekaman video dengan nama \"$trimmed\". Silakan periksa kembali ejaan pencarian Anda."
                btnEmptyStart?.visibility = View.GONE
                btnEmptyDemo?.visibility = View.GONE
            } else if (allVideoFiles.isNotEmpty() && currentFilterMode != 0) {
                val filterName = when (currentFilterMode) {
                    1 -> "Rekaman Game"
                    2 -> "Hari Ini"
                    3 -> "> 1 Menit"
                    else -> "Filter Aktif"
                }
                textEmptyTitle?.text = "Belum Ada Video di Kategori \"$filterName\""
                textEmptySubtitle?.text = "Coba pilih tab filter \"Semua\" untuk melihat ${allVideoFiles.size} video rekaman yang sudah tersimpan."
                btnEmptyStart?.visibility = View.GONE
                btnEmptyDemo?.visibility = View.GONE
            } else {
                textEmptyTitle?.text = "Belum Ada Hasil Rekaman"
                textEmptySubtitle?.text = "Mulai rekam layar gameplay atau aplikasi Anda sekarang! Hasil video MP4 berkualitas tinggi akan otomatis tersimpan di sini."
                btnEmptyStart?.visibility = View.VISIBLE
                btnEmptyDemo?.visibility = View.VISIBLE
            }
        } else {
            emptyState?.visibility = View.GONE
            recyclerView?.visibility = View.VISIBLE
        }

        updateStatsSummary()
        adapter.notifyDataSetChanged()
    }

    private fun updateStatsSummary() {
        val textSummary = findViewById<TextView>(R.id.text_library_stats_summary)
        val totalCount = allVideoFiles.size
        val totalBytes = allVideoFiles.sumOf { it.length() }
        val totalMb = totalBytes / (1024.0 * 1024.0)

        textSummary?.text = if (totalCount == 0) {
            "Belum ada rekaman tersimpan"
        } else {
            String.format(Locale.getDefault(), "%d Video Tersimpan • %.1f MB Digunakan", totalCount, totalMb)
        }
    }

    private fun setupBottomNavigation() {
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation_library)
        bottomNav?.selectedItemId = R.id.nav_library
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
                R.id.nav_library -> true
                R.id.nav_settings -> {
                    val intent = Intent(this, SettingsActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(intent)
                    finish()
                    true
                }
                else -> false
            }
        }
    }

    private fun loadVideoFiles() {
        if (AppLockHelper.shouldRequirePin(
                appPreferences.isAppLockEnabled,
                appPreferences.appLockPin,
                HomeActivity.isSessionUnlocked
            )
        ) {
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val files = StorageHelper.getAllVideoFiles(this@LibraryActivity)
            val resolvedDurations = HashMap<String, Long>(files.size)
            for (file in files) {
                resolvedDurations[file.absolutePath] = LibraryAdapter.getOrExtractDurationMs(file)
            }
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                videoDurationsMs.clear()
                videoDurationsMs.putAll(resolvedDurations)
                allVideoFiles.clear()
                if (files.isNotEmpty()) {
                    allVideoFiles.addAll(files)
                    applySorting()
                }

                val currentQuery = findViewById<EditText>(R.id.et_search_library)?.text?.toString() ?: ""
                filterVideos(currentQuery)
            }
        }
    }

    private fun showRenameDialog(file: File) {
        val currentNameWithoutExt = file.nameWithoutExtension
        val input = EditText(this).apply {
            setText(currentNameWithoutExt)
            setSelection(currentNameWithoutExt.length)
            setPadding(48, 32, 48, 32)
            hint = "Masukkan nama baru rekaman"
            maxLines = 1
            isSingleLine = true
            setTextColor(ContextCompat.getColor(this@LibraryActivity, R.color.textTitle))
            setHintTextColor(ContextCompat.getColor(this@LibraryActivity, R.color.textCaption))
        }

        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_trim)
            .setTitle("Ubah Nama Rekaman")
            .setMessage("Masukkan nama baru untuk video ini:")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(this, "Nama rekaman tidak boleh kosong", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // Check for illegal characters
                val illegalChars = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
                if (newName.any { it in illegalChars }) {
                    Toast.makeText(this, "Nama mengandung karakter terlarang (/ \\ : * ? \" < > |)", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }

                val finalFileName = if (newName.endsWith(".mp4", ignoreCase = true)) newName else "$newName.mp4"
                val parentDir = file.parentFile
                val targetFile = File(parentDir, finalFileName)

                if (targetFile.exists() && targetFile.absolutePath != file.absolutePath) {
                    Toast.makeText(this, "File dengan nama tersebut sudah ada", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val success = file.renameTo(targetFile)
                if (success) {
                    LibraryAdapter.invalidateCache(file.absolutePath)
                    StorageHelper.scanRenamedFile(this, file, targetFile)
                    Toast.makeText(this, "Nama rekaman berhasil diubah", Toast.LENGTH_SHORT).show()
                    loadVideoFiles()
                } else {
                    Toast.makeText(this, "Gagal mengubah nama file", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun confirmDelete(file: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Hapus Rekaman Video?")
            .setMessage("File \"${file.name}\" akan dihapus secara permanen dari Galeri Rekamin Dulu.")
            .setIcon(R.drawable.ic_delete)
            .setPositiveButton("Hapus") { _, _ ->
                if (file.exists()) {
                    LibraryAdapter.invalidateCache(file.absolutePath)
                    val deleted = file.delete()
                    StorageHelper.scanDeletedFile(this, file)
                    if (deleted) {
                        Toast.makeText(this, "Rekaman video berhasil dihapus", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Gagal menghapus file rekaman", Toast.LENGTH_SHORT).show()
                    }
                    loadVideoFiles()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun handleExtractAudio(file: File) {
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
            val hasAudio = AudioExtractorHelper.hasAudioTrack(file)
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
                    progressDialog.setMessage("Sedang memisahkan trek audio dari rekaman video ${file.name}...")
                }
            }

            val (success, audioFile) = AudioExtractorHelper.extractAudio(this, file)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    try {
                        progressDialog.dismiss()
                    } catch (ignored: Exception) {}

                    if (success && audioFile != null && audioFile.exists()) {
                        try {
                            MaterialAlertDialogBuilder(this)
                                .setTitle("Ekstrak Audio Berhasil! 🎉")
                                .setIcon(R.drawable.ic_check_circle)
                                .setMessage("Audio disimpan sebagai:\n${audioFile.name}\nUkuran: ${String.format(Locale.getDefault(), "%.1f MB", audioFile.length() / (1024.0 * 1024.0))}")
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
                        Toast.makeText(this, "Tidak dapat mengekstrak audio atau rekaman ini tidak memiliki audio.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    private fun playAudioFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
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
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Bagikan Audio M4A"))
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal membagikan audio: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareVideoFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
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
}
