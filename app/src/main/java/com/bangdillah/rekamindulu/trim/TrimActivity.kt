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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bangdillah.rekamindulu.R
import com.bangdillah.rekamindulu.library.LibraryActivity
import com.bangdillah.rekamindulu.player.PlayerActivity
import com.bangdillah.rekamindulu.utils.StorageHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TrimActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_PICK_VIDEO = 3001
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

    private var currentVideoFile: File? = null
    private var videoDurationMs: Int = 0
    private var startTrimMs: Long = 0L
    private var endTrimMs: Long = 0L

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
        setContentView(R.layout.trim_screen)

        supportActionBar?.hide()

        initViews()
        setupListeners()

        val videoPath = intent.getStringExtra("VIDEO_PATH")
        if (!videoPath.isNullOrEmpty()) {
            val file = File(videoPath)
            if (file.exists()) {
                loadVideoFile(file)
            }
        }
    }

    private fun initViews() {
        videoView = findViewById(R.id.trim_video_view)
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
    }

    private fun setupListeners() {
        findViewById<View>(R.id.btn_back_trim)?.setOnClickListener {
            finish()
        }

        findViewById<Button>(R.id.btn_browse)?.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "video/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(Intent.createChooser(intent, "Pilih Video yang Akan Dipotong"), REQUEST_CODE_PICK_VIDEO)
        }

        btnPreviewPlay?.setOnClickListener {
            togglePreviewPlayback()
        }

        videoView?.setOnCompletionListener {
            handler.removeCallbacks(checkTrimPlaybackRunnable)
            btnPreviewPlay?.setImageResource(R.drawable.ic_play_arrow)
            btnPreviewPlay?.visibility = View.VISIBLE
        }

        seekStartTime?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    if (progress >= (seekEndTime?.progress ?: videoDurationMs)) {
                        seekStartTime?.progress = (seekEndTime?.progress ?: 1000) - 1000
                        return
                    }
                    startTrimMs = progress.toLong()
                    textStartTimeVal?.text = formatTime(progress)
                    videoView?.seekTo(progress)
                    updateSummary()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekEndTime?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    if (progress <= (seekStartTime?.progress ?: 0)) {
                        seekEndTime?.progress = (seekStartTime?.progress ?: 0) + 1000
                        return
                    }
                    endTrimMs = progress.toLong()
                    textEndTimeVal?.text = formatTime(progress)
                    videoView?.seekTo(progress)
                    updateSummary()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnApplyTrim?.setOnClickListener {
            val file = currentVideoFile
            if (file == null || !file.exists()) {
                Toast.makeText(this, "Pilih video terlebih dahulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val durationCut = (endTrimMs - startTrimMs) / 1000
            if (durationCut < 1) {
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
        textSelectedFileName?.text = file.name

        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: "1080"
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: "1920"
            retriever.release()

            videoDurationMs = durationStr?.toIntOrNull() ?: 10000
            if (videoDurationMs <= 0) videoDurationMs = 10000

            textVideoDurationInfo?.text = String.format(
                Locale.getDefault(),
                "Total Durasi: %s • Resolusi %sx%s",
                formatTime(videoDurationMs),
                width,
                height
            )

            startTrimMs = 0L
            endTrimMs = videoDurationMs.toLong()

            seekStartTime?.max = videoDurationMs
            seekStartTime?.progress = 0

            seekEndTime?.max = videoDurationMs
            seekEndTime?.progress = videoDurationMs

            textStartTimeVal?.text = "00:00"
            textEndTimeVal?.text = formatTime(videoDurationMs)

            videoView?.setVideoPath(file.absolutePath)
            videoView?.seekTo(100)

            updateSummary()

        } catch (e: Exception) {
            Toast.makeText(this, "Gagal membaca detail video", Toast.LENGTH_SHORT).show()
        }
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
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val outputName = "Trim_${inputFile.nameWithoutExtension}_$timeStamp.mp4"
        val outputFile = File(outputDir, outputName)

        progressBar?.visibility = View.VISIBLE
        progressBar?.progress = 0
        btnApplyTrim?.isEnabled = false
        btnApplyTrim?.text = "Memproses Pemotongan Video..."

        Thread {
            val (success, message) = TrimHelper.trimVideo(
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

            handler.post {
                if (isFinishing || isDestroyed) return@post
                progressBar?.visibility = View.GONE
                btnApplyTrim?.isEnabled = true
                btnApplyTrim?.text = "PANGKAS & SIMPAN SEKARANG"

                if (success) {
                    // Daftarkan ke MediaStore dan Galeri Ponsel
                    StorageHelper.scanVideoFile(this@TrimActivity, outputFile)

                    MaterialAlertDialogBuilder(this@TrimActivity)
                        .setIcon(R.drawable.ic_check_circle)
                        .setTitle("Pemotongan Berhasil! 🎉")
                        .setMessage("Klip video baru telah disimpan di galeri ponsel:\n${outputFile.name}")
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
                } else {
                    Toast.makeText(this@TrimActivity, message, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PICK_VIDEO && resultCode == Activity.RESULT_OK && data != null) {
            val uri = data.data ?: return
            handlePickedUri(uri)
        }
    }

    private fun handlePickedUri(uri: Uri) {
        try {
            var fileName = "video_picked.mp4"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex)
                }
            }

            val tempFile = File(cacheDir, fileName)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            loadVideoFile(tempFile)

        } catch (e: Exception) {
            Toast.makeText(this, "Gagal memuat video yang dipilih", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatTime(ms: Int): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.getDefault(), "%02d:%02d", min, sec)
    }

    override fun onPause() {
        super.onPause()
        if (videoView?.isPlaying == true) {
            videoView?.pause()
            handler.removeCallbacks(checkTrimPlaybackRunnable)
            btnPreviewPlay?.setImageResource(R.drawable.ic_play_arrow)
            btnPreviewPlay?.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(checkTrimPlaybackRunnable)
        videoView?.stopPlayback()
    }
}
