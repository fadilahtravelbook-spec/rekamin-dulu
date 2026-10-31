package com.bangdillah.rekamindulu.player

import android.content.Intent
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.bangdillah.rekamindulu.R
import com.bangdillah.rekamindulu.audio.AudioExtractorHelper
import com.bangdillah.rekamindulu.trim.TrimActivity
import com.bangdillah.rekamindulu.utils.SelectionBottomSheetDialog
import com.bangdillah.rekamindulu.utils.SelectionOption
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.Locale

class PlayerActivity : AppCompatActivity() {

    private var videoView: VideoView? = null
    private var playPauseBtn: ImageView? = null
    private var seekBar: SeekBar? = null
    private var currentTimeText: TextView? = null
    private var totalTimeText: TextView? = null
    private var titleText: TextView? = null
    private var topBar: View? = null
    private var bottomPanel: View? = null
    private var btnSpeed: Button? = null

    private var currentFile: File? = null
    private var currentMediaPlayer: MediaPlayer? = null
    private var currentSpeedIndex = 2 // default 1.0x
    private val speedLabels = arrayOf("0.5x", "0.75x", "1.0x (Normal)", "1.25x", "1.5x", "2.0x")
    private val speedValues = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    private var restoredPosition = 0
    private var restoredWasPlaying = true

    private var areControlsVisible = true

    private val handler = Handler(Looper.getMainLooper())
    private val updateSeekRunnable = object : Runnable {
        override fun run() {
            videoView?.let { vv ->
                if (vv.isPlaying) {
                    val current = vv.currentPosition
                    seekBar?.progress = current
                    currentTimeText?.text = formatTime(current)
                    handler.postDelayed(this, 300)
                }
            }
        }
    }

    private val hideControlsRunnable = Runnable {
        if (videoView?.isPlaying == true) {
            hideControls()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.player_screen)
        supportActionBar?.hide()

        videoView = findViewById(R.id.video_view)
        playPauseBtn = findViewById(R.id.play_pause_btn)
        seekBar = findViewById(R.id.seek_bar)
        currentTimeText = findViewById(R.id.current_time)
        totalTimeText = findViewById(R.id.total_time)
        titleText = findViewById(R.id.player_video_title)
        topBar = findViewById(R.id.player_top_bar)
        bottomPanel = findViewById(R.id.player_bottom_panel)
        btnSpeed = findViewById(R.id.btn_playback_speed)

        if (savedInstanceState != null) {
            restoredPosition = savedInstanceState.getInt("KEY_PLAYBACK_POSITION", 0)
            restoredWasPlaying = savedInstanceState.getBoolean("KEY_WAS_PLAYING", true)
            currentSpeedIndex = savedInstanceState.getInt("KEY_SPEED_INDEX", 2)
        }

        if (currentSpeedIndex != 2) {
            btnSpeed?.text = "⚡ ${speedLabels[currentSpeedIndex].split(" ")[0]}"
        }

        val videoPath = intent.getStringExtra("VIDEO_PATH")
        if (videoPath.isNullOrEmpty()) {
            Toast.makeText(this, "File video tidak ditemukan", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val file = File(videoPath)
        if (!file.exists()) {
            Toast.makeText(this, "File video tidak ditemukan di penyimpanan", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentFile = file
        titleText?.text = file.nameWithoutExtension.replace("_", " ")

        setupListeners()
        loadAndPlayVideo(file)
    }

    private fun setupListeners() {
        findViewById<View>(R.id.btn_player_back)?.setOnClickListener {
            finish()
        }

        findViewById<View>(R.id.btn_player_share)?.setOnClickListener {
            currentFile?.let { shareVideo(it) }
        }

        findViewById<Button>(R.id.btn_player_trim_shortcut)?.setOnClickListener {
            currentFile?.let {
                val intent = Intent(this, TrimActivity::class.java).apply {
                    putExtra("VIDEO_PATH", it.absolutePath)
                }
                startActivity(intent)
                finish()
            }
        }

        findViewById<Button>(R.id.btn_player_extract_audio)?.setOnClickListener {
            currentFile?.let { extractAudioFromVideo(it) }
        }

        findViewById<Button>(R.id.btn_player_open_external)?.setOnClickListener {
            currentFile?.let { openInExternalApp(it) }
        }

        btnSpeed?.setOnClickListener {
            showSpeedDialog()
        }

        playPauseBtn?.setOnClickListener {
            videoView?.let { vv ->
                if (vv.isPlaying) {
                    vv.pause()
                    playPauseBtn?.setImageResource(R.drawable.ic_play_arrow)
                    handler.removeCallbacks(updateSeekRunnable)
                    handler.removeCallbacks(hideControlsRunnable)
                } else {
                    vv.start()
                    playPauseBtn?.setImageResource(R.drawable.ic_pause)
                    handler.post(updateSeekRunnable)
                    scheduleControlsAutoHide()
                }
            }
        }

        // Tap video to toggle controls visibility
        videoView?.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                toggleControls()
            }
            true
        }


        findViewById<ImageView>(R.id.btn_rewind)?.setOnClickListener {
            videoView?.let { vv ->
                val newPos = (vv.currentPosition - 10000).coerceAtLeast(0)
                vv.seekTo(newPos)
                seekBar?.progress = newPos
                currentTimeText?.text = formatTime(newPos)
                scheduleControlsAutoHide()
            }
        }

        findViewById<ImageView>(R.id.btn_forward)?.setOnClickListener {
            videoView?.let { vv ->
                val newPos = (vv.currentPosition + 10000).coerceAtMost(vv.duration)
                vv.seekTo(newPos)
                seekBar?.progress = newPos
                currentTimeText?.text = formatTime(newPos)
                scheduleControlsAutoHide()
            }
        }

        seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentTimeText?.text = formatTime(progress)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                handler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                sb?.let { videoView?.seekTo(it.progress) }
                scheduleControlsAutoHide()
            }
        })
    }

    private fun toggleControls() {
        if (areControlsVisible) {
            hideControls()
        } else {
            showControls()
        }
    }

    private fun showControls() {
        topBar?.animate()?.alpha(1f)?.setDuration(200)?.withStartAction {
            topBar?.visibility = View.VISIBLE
        }?.start()

        bottomPanel?.animate()?.alpha(1f)?.setDuration(200)?.withStartAction {
            bottomPanel?.visibility = View.VISIBLE
        }?.start()

        areControlsVisible = true
        scheduleControlsAutoHide()
    }

    private fun hideControls() {
        topBar?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction {
            topBar?.visibility = View.GONE
        }?.start()

        bottomPanel?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction {
            bottomPanel?.visibility = View.GONE
        }?.start()

        areControlsVisible = false
    }

    private fun scheduleControlsAutoHide() {
        handler.removeCallbacks(hideControlsRunnable)
        if (videoView?.isPlaying == true) {
            handler.postDelayed(hideControlsRunnable, 4000)
        }
    }

    private fun showSpeedDialog() {
        val options = listOf(
            SelectionOption(
                value = 0,
                title = "0.5x (Lambat)",
                subtitle = "Setengah kecepatan normal untuk analisis detail gerakan",
                badgeLabel = "0.5x"
            ),
            SelectionOption(
                value = 1,
                title = "0.75x",
                subtitle = "Kecepatan sedikit lebih lambat",
                badgeLabel = "0.75x"
            ),
            SelectionOption(
                value = 2,
                title = "1.0x (Normal)",
                subtitle = "Kecepatan asli pemutaran video rekaman",
                badgeLabel = "1.0x",
                isRecommended = true
            ),
            SelectionOption(
                value = 3,
                title = "1.25x",
                subtitle = "Sedikit lebih cepat",
                badgeLabel = "1.25x"
            ),
            SelectionOption(
                value = 4,
                title = "1.5x",
                subtitle = "Satu setengah kali lebih cepat",
                badgeLabel = "1.5x"
            ),
            SelectionOption(
                value = 5,
                title = "2.0x (Cepat)",
                subtitle = "Dua kali kecepatan normal untuk tinjauan kilat",
                badgeLabel = "2.0x"
            )
        )

        SelectionBottomSheetDialog.show(
            context = this,
            iconRes = R.drawable.ic_speed,
            title = "Kecepatan Pemutaran Video",
            subtitle = "Pilih kecepatan putar sesuai kebutuhan Anda",
            options = options,
            selectedValue = currentSpeedIndex
        ) { selectedIndex ->
            currentSpeedIndex = selectedIndex
            val speed = speedValues[selectedIndex]
            applyPlaybackSpeed(speed)
            btnSpeed?.text = "⚡ ${speedLabels[selectedIndex].split(" ")[0]}"
            scheduleControlsAutoHide()
        }
    }

    private fun applyPlaybackSpeed(speed: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                currentMediaPlayer?.let { mp ->
                    val wasPlaying = mp.isPlaying
                    val params = mp.playbackParams ?: PlaybackParams()
                    params.speed = speed
                    mp.playbackParams = params
                    if (!wasPlaying) {
                        mp.pause()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Kecepatan pemutaran tidak didukung pada hardware ini", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Fitur ini membutuhkan Android 6.0+", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadAndPlayVideo(file: File) {
        videoView?.setVideoPath(file.absolutePath)

        videoView?.setOnPreparedListener { mp ->
            currentMediaPlayer = mp
            val duration = mp.duration
            seekBar?.max = duration
            totalTimeText?.text = formatTime(duration)

            if (currentSpeedIndex != 2) {
                val speed = speedValues[currentSpeedIndex]
                applyPlaybackSpeed(speed)
            }

            if (restoredPosition > 0) {
                videoView?.seekTo(restoredPosition)
                seekBar?.progress = restoredPosition
                currentTimeText?.text = formatTime(restoredPosition)
            }

            if (restoredWasPlaying) {
                videoView?.start()
                playPauseBtn?.setImageResource(R.drawable.ic_pause)
                handler.post(updateSeekRunnable)
                scheduleControlsAutoHide()
            } else {
                playPauseBtn?.setImageResource(R.drawable.ic_play_arrow)
                showControls()
            }
        }

        videoView?.setOnCompletionListener {
            playPauseBtn?.setImageResource(R.drawable.ic_play_arrow)
            handler.removeCallbacks(updateSeekRunnable)
            showControls()
            videoView?.seekTo(0)
            seekBar?.progress = 0
            currentTimeText?.text = "00:00"
        }

        videoView?.setOnErrorListener { _, what, extra ->
            if (!isFinishing && !isDestroyed) {
                MaterialAlertDialogBuilder(this)
                    .setIcon(R.drawable.ic_video_error)
                    .setTitle("Tidak Dapat Memutar Video")
                    .setMessage("Format file video tidak didukung atau file rekaman rusak (Error $what, $extra).")
                    .setPositiveButton("Kembali") { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            }
            true
        }
    }

    private fun shareVideo(file: File) {
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
            startActivity(Intent.createChooser(shareIntent, "Bagikan Video Rekaman"))
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal membagikan video: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openInExternalApp(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(viewIntent, "Buka Video dengan"))
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak ada aplikasi pemutar video eksternal", Toast.LENGTH_SHORT).show()
        }
    }

    private fun extractAudioFromVideo(file: File) {
        if (!AudioExtractorHelper.hasAudioTrack(file)) {
            Toast.makeText(this, "⚠️ Video ini tidak memiliki rekaman suara (direkam dalam mode Mute/tanpa suara).", Toast.LENGTH_LONG).show()
            return
        }

        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Mengekstrak Audio 🎵")
            .setMessage("Sedang memisahkan trek audio dari rekaman video ${file.name}...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        Thread {
            val (success, audioFile) = AudioExtractorHelper.extractAudio(this, file)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    try {
                        progressDialog.dismiss()
                    } catch (ignored: Exception) {}

                    if (success && audioFile != null && audioFile.exists()) {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Ekstrak Audio Berhasil! 🎉")
                            .setIcon(R.drawable.ic_check_circle)
                            .setMessage("Audio disimpan sebagai:\n${audioFile.name}\nUkuran: ${String.format(Locale.getDefault(), "%.1f MB", audioFile.length() / (1024.0 * 1024.0))}")
                            .setPositiveButton("Bagikan") { _, _ ->
                                try {
                                    val uri = FileProvider.getUriForFile(
                                        this,
                                        "$packageName.fileprovider",
                                        audioFile
                                    )
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "audio/*"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    startActivity(Intent.createChooser(shareIntent, "Bagikan Audio"))
                                } catch (e: Exception) {
                                    Toast.makeText(this, "Gagal membagikan: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .setNegativeButton("Tutup", null)
                            .show()
                    } else {
                        Toast.makeText(this, "Tidak dapat mengekstrak audio dari video ini", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val pos = videoView?.currentPosition ?: restoredPosition
        val playing = videoView?.isPlaying ?: restoredWasPlaying
        outState.putInt("KEY_PLAYBACK_POSITION", pos)
        outState.putBoolean("KEY_WAS_PLAYING", playing)
        outState.putInt("KEY_SPEED_INDEX", currentSpeedIndex)
    }

    override fun onPause() {
        super.onPause()
        if (videoView?.isPlaying == true) {
            restoredPosition = videoView?.currentPosition ?: 0
            restoredWasPlaying = true
            videoView?.pause()
            playPauseBtn?.setImageResource(R.drawable.ic_play_arrow)
            handler.removeCallbacks(updateSeekRunnable)
            handler.removeCallbacks(hideControlsRunnable)
        } else {
            restoredPosition = videoView?.currentPosition ?: restoredPosition
            restoredWasPlaying = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateSeekRunnable)
        handler.removeCallbacks(hideControlsRunnable)
        videoView?.stopPlayback()
    }

    private fun formatTime(ms: Int): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.getDefault(), "%02d:%02d", min, sec)
    }
}
