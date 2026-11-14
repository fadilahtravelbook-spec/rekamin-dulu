package com.bangdillah.rekamindulu.trim

import android.app.Activity
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.bangdillah.rekamindulu.HomeActivity
import com.bangdillah.rekamindulu.MainActivity
import com.bangdillah.rekamindulu.R
import com.bangdillah.rekamindulu.library.LibraryActivity
import com.bangdillah.rekamindulu.player.PlayerActivity
import com.bangdillah.rekamindulu.service.ScreenRecordingService
import com.bangdillah.rekamindulu.utils.AppLockHelper
import com.bangdillah.rekamindulu.utils.AppPreferences
import com.bangdillah.rekamindulu.utils.StorageHelper
import com.bangdillah.rekamindulu.utils.UiHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TrimActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_PICK_VIDEO = 3001
        private const val MIN_CLIP_DURATION_MS = 1000

        fun formatTime(ms: Int): String = UiHelper.formatDurationMs(ms.toLong())

        /**
         * Menjaga posisi slider awal & akhir tetap valid dengan jarak minimal [minClipMs]
         * (atau seluruh durasi video jika video < minClipMs).
         */
        fun clampTrimProgress(
            progress: Int,
            otherBoundMs: Int,
            durationMs: Int,
            isStartThumb: Boolean,
            minClipMs: Int = MIN_CLIP_DURATION_MS
        ): Int {
            val safeDuration = durationMs.coerceAtLeast(0)
            val clampedProgress = progress.coerceIn(0, safeDuration)
            val effectiveGap = minClipMs.coerceAtMost(safeDuration)
            return if (isStartThumb) {
                val maxAllowed = (otherBoundMs.coerceIn(0, safeDuration) - effectiveGap).coerceAtLeast(0)
                clampedProgress.coerceAtMost(maxAllowed)
            } else {
                val minAllowed = (otherBoundMs.coerceIn(0, safeDuration) + effectiveGap).coerceAtMost(safeDuration)
                clampedProgress.coerceAtLeast(minAllowed)
            }
        }
    }

    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        AppLockHelper.clearBackgroundLockSuppression()
        if (uri != null) {
            handlePickedUri(uri)
        }
    }

    private var videoView: VideoView? = null
    private var btnPreviewPlay: ImageView? = null
    private var textSelectedFileName: TextView? = null
    private var textVideoDurationInfo: TextView? = null
    private var seekStartTime: SeekBar? = null
    private var seekEndTime: SeekBar? = null
    private var textStartTimeVal: TextView? = null
    private var textEndTimeVal: TextView? = null
    private var textTrimSummary: TextView? = null
    private var progressBar: ProgressBar? = null
    private var btnApplyTrim: Button? = null
    private var layoutNoVideoPlaceholder: View? = null

    private lateinit var appPreferences: AppPreferences
    private var appLockDialog: AlertDialog? = null
    private var activeDialog: AlertDialog? = null
    private var pendingInitialVideoFile: File? = null

    private var currentVideoFile: File? = null
    private var tempPickedFile: File? = null
    private var surfaceLostInStop = false
    @Volatile
    private var isTrimming = false
    @Volatile
    private var deleteTempAfterTrim = false
    private var videoDurationMs: Int = 0
    private var startTrimMs: Long = 0L
    private var endTrimMs: Long = 0L
    private var currentResolutionLabel: String = ""

    private val handler = Handler(Looper.getMainLooper())

    private val checkTrimPlaybackRunnable = object : Runnable {
        override fun run() {
            videoView?.let { vv ->
                if (vv.isPlaying) {
                    if (vv.currentPosition >= endTrimMs) {
                        vv.pause()
                        vv.seekTo(startTrimMs.toInt())
                        btnPreviewPlay?.setImageResource(R.drawable.ic_play_arrow)
                        btnPreviewPlay?.visibility = View.VISIBLE
                    } else {
                        handler.postDelayed(this, 100)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appPreferences = AppPreferences(this)
        appPreferences.applyTheme()
        setContentView(R.layout.trim_screen)

        supportActionBar?.hide()

        UiHelper.applySystemBarInsets(
            anchorView = findViewById(R.id.trim_root_scroll),
            topView = findViewById(R.id.trim_inner_content),
            bottomView = findViewById(R.id.trim_inner_content)
        )

        initViews()
        setupListeners()

        val videoPath = intent.getStringExtra("VIDEO_PATH")
        if (!videoPath.isNullOrEmpty()) {
            val file = File(videoPath)
            if (file.exists()) {
                pendingInitialVideoFile = file
            } else {
                showEmptyVideoPlaceholder(true)
            }
        } else {
            showEmptyVideoPlaceholder(true)
        }
    }

    private fun checkAppLock() {
        appLockDialog = AppLockHelper.showPinGateIfNeeded(
            activity = this,
            appPreferences = appPreferences,
            currentDialog = appLockDialog,
            title = "🔒 Kunci Pemotong Video",
            message = "Alat potong video dilindungi PIN. Masukkan PIN keamanan untuk melanjutkan:",
            negativeButtonLabel = "Kembali",
            onCancel = { finish() },
            onUnlocked = {
                videoView?.visibility = View.VISIBLE
                val pending = pendingInitialVideoFile
                if (pending != null) {
                    pendingInitialVideoFile = null
                    surfaceLostInStop = false
                    MainActivity.consumePendingSaveIfHandled(
                        openedFilePath = pending.absolutePath,
                        lastRecordedPath = ScreenRecordingService.lastRecordedFilePath
                    )
                    loadVideoFile(pending)
                } else if (currentVideoFile != null) {
                    val fileToRestore = currentVideoFile!!
                    textSelectedFileName?.text = fileToRestore.name
                    if (surfaceLostInStop && fileToRestore.exists()) {
                        surfaceLostInStop = false
                        rebindPreviewSurface(fileToRestore)
                    }
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        if (AppLockHelper.shouldRequirePin(
                appPreferences.isAppLockEnabled,
                appPreferences.appLockPin,
                HomeActivity.isSessionUnlocked
            )
        ) {
            videoView?.visibility = View.INVISIBLE
            textSelectedFileName?.text = "Video Terkunci 🔒"
            if (videoView?.isPlaying == true) {
                videoView?.pause()
                handler.removeCallbacks(checkTrimPlaybackRunnable)
                btnPreviewPlay?.setImageResource(R.drawable.ic_play_arrow)
                btnPreviewPlay?.visibility = View.VISIBLE
            }
        }
        checkAppLock()
    }

    private fun showEmptyVideoPlaceholder(show: Boolean) {
        layoutNoVideoPlaceholder?.visibility = if (show) View.VISIBLE else View.GONE
        btnPreviewPlay?.visibility = if (show) View.GONE else View.VISIBLE
        if (show) {
            btnApplyTrim?.isEnabled = false
        }
    }

    private fun initViews() {
        videoView = findViewById(R.id.trim_video_view)
        videoView?.setSecure(
            AppLockHelper.shouldMaskScreenInRecents(
                appPreferences.isAppLockEnabled,
                appPreferences.appLockPin
            )
        )
        btnPreviewPlay = findViewById(R.id.btn_preview_play)
        textSelectedFileName = findViewById(R.id.text_selected_file_name)
        textVideoDurationInfo = findViewById(R.id.text_video_duration_info)
        seekStartTime = findViewById(R.id.seek_start_time)
        seekEndTime = findViewById(R.id.seek_end_time)
        textStartTimeVal = findViewById(R.id.text_start_time_val)
        textEndTimeVal = findViewById(R.id.text_end_time_val)
        textTrimSummary = findViewById(R.id.text_trim_summary)
        progressBar = findViewById(R.id.trim_progress_bar)
        btnApplyTrim = findViewById(R.id.btn_apply_trim)
        layoutNoVideoPlaceholder = findViewById(R.id.layout_no_video_placeholder)
    }

    private fun setupListeners() {
        findViewById<View>(R.id.btn_back_trim)?.setOnClickListener {
            finish()
        }

        findViewById<Button>(R.id.btn_browse)?.setOnClickListener {
            AppLockHelper.suppressNextBackgroundLock()
            try {
                pickVideoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            } catch (e: Exception) {
                try {
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "video/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent.createChooser(intent, "Pilih Video yang Akan Dipotong"), REQUEST_CODE_PICK_VIDEO)
                } catch (inner: Exception) {
                    AppLockHelper.clearBackgroundLockSuppression()
                    Toast.makeText(this, "Tidak dapat membuka pemilih video", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnPreviewPlay?.setOnClickListener {
            togglePreviewPlayback()
        }

        findViewById<View>(R.id.layout_trim_preview_container)?.setOnClickListener {
            if (currentVideoFile != null && !isTrimming) {
                togglePreviewPlayback()
            }
        }

        videoView?.setOnCompletionListener {
            handler.removeCallbacks(checkTrimPlaybackRunnable)
            btnPreviewPlay?.setImageResource(R.drawable.ic_play_arrow)
            btnPreviewPlay?.visibility = View.VISIBLE
        }

        videoView?.setOnErrorListener { _, _, _ ->
            handler.removeCallbacks(checkTrimPlaybackRunnable)
            btnPreviewPlay?.visibility = View.GONE
            btnApplyTrim?.isEnabled = false
            if (!isFinishing && !isDestroyed) {
                Toast.makeText(this, "Preview video tidak dapat diputar", Toast.LENGTH_SHORT).show()
            }
            true
        }

        seekStartTime?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val endBound = seekEndTime?.progress ?: endTrimMs.toInt()
                    val clamped = clampTrimProgress(progress, endBound, videoDurationMs, isStartThumb = true)
                    if (clamped != progress) {
                        seekBar?.progress = clamped
                    }
                    startTrimMs = clamped.toLong()
                    textStartTimeVal?.text = formatTime(clamped)
                    updateSummary()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let { videoView?.seekTo(it.progress) }
            }
        })

        seekEndTime?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val startBound = seekStartTime?.progress ?: startTrimMs.toInt()
                    val clamped = clampTrimProgress(progress, startBound, videoDurationMs, isStartThumb = false)
                    if (clamped != progress) {
                        seekBar?.progress = clamped
                    }
                    endTrimMs = clamped.toLong()
                    textEndTimeVal?.text = formatTime(clamped)
                    updateSummary()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let { videoView?.seekTo(it.progress) }
            }
        })

        btnApplyTrim?.setOnClickListener {
            if (isTrimming) return@setOnClickListener
            val file = currentVideoFile
            if (file == null || !file.exists()) {
                Toast.makeText(this, "Pilih video terlebih dahulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val clipDurationMs = endTrimMs - startTrimMs
            if (clipDurationMs < MIN_CLIP_DURATION_MS) {
                Toast.makeText(this, "Rentang durasi minimal 1 detik", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            executeTrim(file)
        }
    }

    private fun togglePreviewPlayback() {
        val vv = videoView ?: return
        if (vv.isPlaying) {
            vv.pause()
            handler.removeCallbacks(checkTrimPlaybackRunnable)
            btnPreviewPlay?.setImageResource(R.drawable.ic_play_arrow)
            btnPreviewPlay?.visibility = View.VISIBLE
        } else {
            vv.seekTo(startTrimMs.toInt())
            vv.start()
            btnPreviewPlay?.setImageResource(R.drawable.ic_pause)
            btnPreviewPlay?.visibility = View.GONE
            handler.post(checkTrimPlaybackRunnable)
        }
    }

    private fun loadVideoFile(file: File) {
        currentVideoFile = file
        surfaceLostInStop = false
        showEmptyVideoPlaceholder(false)
        textSelectedFileName?.text = file.name
        textVideoDurationInfo?.text = "Memuat informasi video..."
        btnApplyTrim?.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            var retriever: MediaMetadataRetriever? = null
            var durationMs = 0
            var width = 0
            var height = 0
            var loadError = false

            try {
                retriever = MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath)
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                durationMs = durationStr?.toIntOrNull()?.takeIf { it > 0 } ?: 0
            } catch (e: Exception) {
                loadError = true
            } finally {
                try { retriever?.release() } catch (ignored: Exception) {}
            }

            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                if (loadError) {
                    videoDurationMs = 0
                    btnApplyTrim?.isEnabled = false
                    textVideoDurationInfo?.text = "Gagal membaca detail video"
                    Toast.makeText(this@TrimActivity, "Gagal membaca detail video", Toast.LENGTH_SHORT).show()
                    return@withContext
                }

                currentResolutionLabel = if (width > 0 && height > 0) " • Resolusi ${width}x${height}" else ""

                if (durationMs > 0) {
                    initializeDurationBounds(durationMs)
                } else {
                    videoDurationMs = 0
                    startTrimMs = 0L
                    endTrimMs = 0L
                    seekStartTime?.max = 0
                    seekStartTime?.progress = 0
                    seekEndTime?.max = 0
                    seekEndTime?.progress = 0
                    textStartTimeVal?.text = formatTime(0)
                    textEndTimeVal?.text = formatTime(0)
                    textVideoDurationInfo?.text = "Memeriksa durasi dari pemutar..."
                    btnApplyTrim?.isEnabled = false
                }

                bindVideoPreview(file, expectedMetadataDurationMs = durationMs)
            }
        }
    }

    private fun initializeDurationBounds(durationMs: Int) {
        videoDurationMs = durationMs
        textVideoDurationInfo?.text = String.format(
            Locale.getDefault(),
            "Total Durasi: %s%s",
            formatTime(videoDurationMs),
            currentResolutionLabel
        )
        startTrimMs = 0L
        endTrimMs = videoDurationMs.toLong()
        seekStartTime?.max = videoDurationMs
        seekStartTime?.progress = 0
        seekEndTime?.max = videoDurationMs
        seekEndTime?.progress = videoDurationMs
        textStartTimeVal?.text = formatTime(0)
        textEndTimeVal?.text = formatTime(videoDurationMs)
        btnApplyTrim?.isEnabled = true
        updateSummary()
    }

    private fun bindVideoPreview(file: File, expectedMetadataDurationMs: Int) {
        videoView?.setOnPreparedListener { mp ->
            val actualDur = mp.duration
            if (actualDur > 0) {
                if (videoDurationMs <= 0) {
                    initializeDurationBounds(actualDur)
                } else {
                    videoDurationMs = actualDur
                    seekStartTime?.max = actualDur
                    seekEndTime?.max = actualDur
                    if (endTrimMs > actualDur || endTrimMs == expectedMetadataDurationMs.toLong()) {
                        endTrimMs = actualDur.toLong()
                        seekEndTime?.progress = actualDur
                        textEndTimeVal?.text = formatTime(actualDur)
                        textVideoDurationInfo?.text = String.format(
                            Locale.getDefault(),
                            "Total Durasi: %s%s",
                            formatTime(actualDur),
                            currentResolutionLabel
                        )
                        updateSummary()
                    }
                    btnApplyTrim?.isEnabled = true
                }
                videoView?.seekTo(startTrimMs.toInt().coerceIn(0, actualDur))
            } else if (videoDurationMs <= 0) {
                btnApplyTrim?.isEnabled = false
                textVideoDurationInfo?.text = "Durasi video tidak valid"
                Toast.makeText(this@TrimActivity, "Durasi video tidak valid atau file rusak", Toast.LENGTH_SHORT).show()
            }
        }
        videoView?.setVideoPath(file.absolutePath)
    }

    private fun rebindPreviewSurface(file: File) {
        videoView?.setOnPreparedListener { mp ->
            val actualDur = mp.duration
            if (actualDur > 0 && videoDurationMs <= 0) {
                initializeDurationBounds(actualDur)
            }
            val safeDur = (if (actualDur > 0) actualDur else videoDurationMs).coerceAtLeast(0)
            videoView?.seekTo(startTrimMs.toInt().coerceIn(0, safeDur))
        }
        videoView?.setVideoPath(file.absolutePath)
    }

    private fun updateSummary() {
        val totalSec = ((endTrimMs - startTrimMs) / 1000).coerceAtLeast(0)
        textTrimSummary?.text = String.format(
            Locale.getDefault(),
            "⏱️ Hasil Potongan: Durasi %d detik (Dari %s s/d %s)",
            totalSec,
            formatTime(startTrimMs.toInt()),
            formatTime(endTrimMs.toInt())
        )
    }

    private fun executeTrim(inputFile: File) {
        val outputDir = StorageHelper.getRecordingDirectory(this)
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputFile = TrimHelper.createTrimOutputFile(outputDir, inputFile.nameWithoutExtension, timeStamp)

        progressBar?.visibility = View.VISIBLE
        progressBar?.progress = 0
        btnApplyTrim?.isEnabled = false
        btnApplyTrim?.text = "Memproses Pemotongan Video..."

        isTrimming = true
        deleteTempAfterTrim = false
        Thread {
            var trimResult: Pair<Boolean, String> = Pair(false, "Gagal memotong video")
            try {
                trimResult = TrimHelper.trimVideo(
                    inputFile = inputFile,
                    outputFile = outputFile,
                    startMs = startTrimMs,
                    endMs = endTrimMs,
                    progressCallback = { percent ->
                        handler.post {
                            progressBar?.progress = percent
                        }
                    }
                )
            } finally {
                isTrimming = false
                if (deleteTempAfterTrim && inputFile.absolutePath == tempPickedFile?.absolutePath) {
                    try {
                        if (inputFile.exists()) inputFile.delete()
                    } catch (_: Exception) {}
                    tempPickedFile = null
                }
            }
            val (success, message) = trimResult

            handler.post {
                if (isFinishing || isDestroyed) return@post
                progressBar?.visibility = View.GONE
                btnApplyTrim?.isEnabled = true
                btnApplyTrim?.text = "PANGKAS & SIMPAN SEKARANG"

                if (success) {
                    // Daftarkan ke MediaStore dan Galeri Ponsel
                    StorageHelper.scanVideoFile(this@TrimActivity, outputFile)

                    try {
                        try { activeDialog?.dismiss() } catch (ignored: Exception) {}
                        activeDialog = MaterialAlertDialogBuilder(this@TrimActivity)
                            .setIcon(R.drawable.ic_check_circle)
                            .setTitle("Pemotongan Berhasil! 🎉")
                            .setMessage("Klip video baru telah disimpan di Galeri Rekamin Dulu (Movies/RekaminDulu):\n${outputFile.name}")
                            .setPositiveButton("Putar Video") { _, _ ->
                                val intent = Intent(this@TrimActivity, PlayerActivity::class.java).apply {
                                    putExtra("VIDEO_PATH", outputFile.absolutePath)
                                }
                                startActivity(intent)
                                finish()
                            }
                            .setNeutralButton("Galeri") { _, _ ->
                                val intent = Intent(this@TrimActivity, LibraryActivity::class.java)
                                startActivity(intent)
                                finish()
                            }
                            .setNegativeButton("Tutup", null)
                            .show()
                    } catch (ignored: Exception) {}
                } else {
                    Toast.makeText(this@TrimActivity, message, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PICK_VIDEO) {
            AppLockHelper.clearBackgroundLockSuppression()
            if (resultCode == Activity.RESULT_OK && data != null) {
                val uri = data.data ?: return
                handlePickedUri(uri)
            }
        }
    }

    private fun handlePickedUri(uri: Uri) {
        progressBar?.visibility = View.VISIBLE
        btnApplyTrim?.isEnabled = false
        textSelectedFileName?.text = "Menyiapkan video..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var rawName = "video_picked_${System.currentTimeMillis()}.mp4"
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIndex >= 0) {
                        val candidate = cursor.getString(nameIndex)
                        if (!candidate.isNullOrBlank()) {
                            rawName = candidate
                        }
                    }
                }

                val safeName = File(rawName).name.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
                val tempFile = File(cacheDir, "trim_src_$safeName")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalStateException("Tidak dapat membuka file sumber")

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) {
                        tempFile.delete()
                        return@withContext
                    }
                    progressBar?.visibility = View.GONE
                    if (tempPickedFile != null && tempPickedFile?.absolutePath != tempFile.absolutePath) {
                        tempPickedFile?.delete()
                    }
                    tempPickedFile = tempFile
                    loadVideoFile(tempFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        progressBar?.visibility = View.GONE
                        btnApplyTrim?.isEnabled = currentVideoFile?.exists() == true
                        Toast.makeText(this@TrimActivity, "Gagal memuat video yang dipilih", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun formatTime(ms: Int): String = Companion.formatTime(ms)

    override fun onPause() {
        super.onPause()
        if (videoView?.isPlaying == true) {
            videoView?.pause()
            handler.removeCallbacks(checkTrimPlaybackRunnable)
            btnPreviewPlay?.setImageResource(R.drawable.ic_play_arrow)
            btnPreviewPlay?.visibility = View.VISIBLE
        }
    }

    override fun onStop() {
        super.onStop()
        surfaceLostInStop = true
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try { activeDialog?.dismiss() } catch (ignored: Exception) {}
        activeDialog = null
        try { appLockDialog?.dismiss() } catch (ignored: Exception) {}
        appLockDialog = null
        videoView?.stopPlayback()
        if (isTrimming) {
            deleteTempAfterTrim = true
        } else {
            tempPickedFile?.let { file ->
                if (file.exists()) {
                    file.delete()
                }
            }
            tempPickedFile = null
        }
    }
}
