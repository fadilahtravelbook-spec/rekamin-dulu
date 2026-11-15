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
import androidx.core.view.WindowCompat
import com.bangdillah.rekamindulu.MainActivity
import com.bangdillah.rekamindulu.R
import com.bangdillah.rekamindulu.audio.AudioExtractorHelper
import com.bangdillah.rekamindulu.service.ScreenRecordingService
import com.bangdillah.rekamindulu.trim.TrimActivity
import com.bangdillah.rekamindulu.utils.AppLockHelper
import com.bangdillah.rekamindulu.utils.AppPreferences
import com.bangdillah.rekamindulu.utils.SelectionBottomSheetDialog
import com.bangdillah.rekamindulu.utils.SelectionOption
import com.bangdillah.rekamindulu.utils.UiHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.Locale

class PlayerActivity : AppCompatActivity() {

    companion object {
        fun canLoadVideoDirectly(
            isAppLockEnabled: Boolean,
            savedPin: String,
            isSessionUnlocked: Boolean
        ): Boolean {
            return !AppLockHelper.shouldRequirePin(isAppLockEnabled, savedPin, isSessionUnlocked)
        }

        fun formatTime(ms: Int): String = UiHelper.formatDurationMs(ms.toLong())

        /**
         * Menentukan posisi & status play/pause yang harus disimpan pada onSaveInstanceState.
         * Pada Android 9+, onPause() berjalan sebelum onSaveInstanceState() sehingga VideoView
         * sudah dalam kondisi pause saat state disimpan; helper ini mencegah status wasPlaying
         * dan posisi video tertimpa menjadi false/0 saat rotasi layar.
         */
        fun resolveSavedPlaybackState(
            isPrepared: Boolean,
            isCurrentlyPlaying: Boolean,
            liveCurrentPositionMs: Int,
            restoredPositionMs: Int,
            restoredWasPlaying: Boolean
        ): Pair<Int, Boolean> {
            val resolvedPos = if (isPrepared) {
                liveCurrentPositionMs.coerceAtLeast(0)
            } else {
                restoredPositionMs.coerceAtLeast(0)
            }
            val resolvedPlaying = if (isCurrentlyPlaying) true else restoredWasPlaying
            return Pair(resolvedPos, resolvedPlaying)
        }
    }

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
    private var pendingVideoFile: File? = null
    private var pendingResetPlayback = false
    private var currentMediaPlayer: MediaPlayer? = null
    private var currentSpeedIndex = 2 // default 1.0x
    private val speedLabels = arrayOf("0.5x", "0.75x", "1.0x (Normal)", "1.25x", "1.5x", "2.0x")
    private val speedValues = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    private var restoredPosition = 0
    private var restoredWasPlaying = true

    private var areControlsVisible = true
    private var extractProgressDialog: AlertDialog? = null
    private var activeDialog: AlertDialog? = null
    private lateinit var appPreferences: AppPreferences
    private var appLockDialog: AlertDialog? = null
    private var isVideoLoaded = false
    private var surfaceLostInStop = false
    private var isPrepared = false
    private var hasPendingSpeedChange = false

    private val handler = Handler(Looper.getMainLooper())
    private val updateSeekRunnable = object : Runnable {
        override fun run() {
            videoView?.let { vv ->
                if (vv.isPlaying) {
                    val current = vv.currentPosition
                    restoredPosition = current
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
        appPreferences = AppPreferences(this)
        appPreferences.applyTheme()
        setContentView(R.layout.player_screen)
        supportActionBar?.hide()
        @Suppress("DEPRECATION")
        window.statusBarColor = 0xD90E0F1A.toInt()
        @Suppress("DEPRECATION")
        window.navigationBarColor = 0xFF0A0A12.toInt()
        WindowCompat.getInsetsController(window, window.decorView)?.apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        videoView = findViewById(R.id.video_view)
        videoView?.setSecure(
            AppLockHelper.shouldMaskScreenInRecents(
                appPreferences.isAppLockEnabled,
                appPreferences.appLockPin
            )
        )
        playPauseBtn = findViewById(R.id.play_pause_btn)
        seekBar = findViewById(R.id.seek_bar)
        currentTimeText = findViewById(R.id.current_time)
        totalTimeText = findViewById(R.id.total_time)
        titleText = findViewById(R.id.player_video_title)
        topBar = findViewById(R.id.player_top_bar)
        bottomPanel = findViewById(R.id.player_bottom_panel)
        btnSpeed = findViewById(R.id.btn_playback_speed)

        val bottomInsetWrapper = findViewById<View>(R.id.player_bottom_inset_wrapper)
        UiHelper.applySystemBarInsets(
            anchorView = findViewById(R.id.player_root_layout),
            topView = topBar,
            bottomView = bottomInsetWrapper,
            horizontalView = topBar,
            extraHorizontalViews = listOfNotNull(bottomInsetWrapper)
        )

        if (savedInstanceState != null) {
            restoredPosition = savedInstanceState.getInt("KEY_PLAYBACK_POSITION", 0)
            restoredWasPlaying = savedInstanceState.getBoolean("KEY_WAS_PLAYING", true)
            currentSpeedIndex = savedInstanceState.getInt("KEY_SPEED_INDEX", 2).coerceIn(speedValues.indices)
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

        pendingVideoFile = file
        pendingResetPlayback = false
        if (!canLoadVideoDirectly(
                isAppLockEnabled = appPreferences.isAppLockEnabled,
                savedPin = appPreferences.appLockPin,
                isSessionUnlocked = com.bangdillah.rekamindulu.HomeActivity.isSessionUnlocked
            )
        ) {
            videoView?.visibility = View.INVISIBLE
            titleText?.text = "Video Terkunci 🔒"
        }

        setupListeners()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newPath = intent.getStringExtra("VIDEO_PATH") ?: return
        val newFile = File(newPath)
        if (!newFile.exists()) {
            Toast.makeText(this, "File video tidak ditemukan di penyimpanan", Toast.LENGTH_SHORT).show()
            return
        }

        pendingVideoFile = newFile
        pendingResetPlayback = true
        if (!canLoadVideoDirectly(
                isAppLockEnabled = appPreferences.isAppLockEnabled,
                savedPin = appPreferences.appLockPin,
                isSessionUnlocked = com.bangdillah.rekamindulu.HomeActivity.isSessionUnlocked
            )
        ) {
            pausePlaybackIfRunning()
            videoView?.visibility = View.INVISIBLE
            titleText?.text = "Video Terkunci 🔒"
        }
        checkAppLock()
    }

    private fun checkAppLock() {
        appLockDialog = AppLockHelper.showPinGateIfNeeded(
            activity = this,
            appPreferences = appPreferences,
            currentDialog = appLockDialog,
            title = "🔒 Kunci Pemutar Video",
            message = "Pemutar video dilindungi PIN. Masukkan PIN keamanan untuk memutar video:",
            negativeButtonLabel = "Kembali",
            onCancel = { finish() },
            onUnlocked = { openVideoAfterUnlock() }
        )
    }

    private fun openVideoAfterUnlock() {
        videoView?.visibility = View.VISIBLE
        val targetFile = pendingVideoFile ?: currentFile ?: return
        val resetPlayback = pendingResetPlayback
        val isSameLoadedFile = currentFile?.absolutePath == targetFile.absolutePath &&
            isVideoLoaded &&
            !resetPlayback &&
            !surfaceLostInStop

        pendingVideoFile = null
        pendingResetPlayback = false

        MainActivity.consumePendingSaveIfHandled(
            openedFilePath = targetFile.absolutePath,
            lastRecordedPath = ScreenRecordingService.lastRecordedFilePath,
            forceClear = intent.getBooleanExtra(MainActivity.EXTRA_CLEAR_PENDING_SAVE, false)
        )

        if (isSameLoadedFile) {
            titleText?.text = targetFile.nameWithoutExtension.replace("_", " ")
            return
        }

        surfaceLostInStop = false
        isPrepared = false
        handler.removeCallbacks(updateSeekRunnable)
        handler.removeCallbacks(hideControlsRunnable)
        videoView?.stopPlayback()
        currentMediaPlayer = null
        if (resetPlayback) {
            restoredPosition = 0
            restoredWasPlaying = true
        }
        currentFile = targetFile
        titleText?.text = targetFile.nameWithoutExtension.replace("_", " ")
        isVideoLoaded = true
        loadAndPlayVideo(targetFile)
    }

    private fun pausePlaybackIfRunning() {
        if (videoView?.isPlaying == true) {
            videoView?.pause()
            playPauseBtn?.setImageResource(R.drawable.ic_play_arrow)
        }
        handler.removeCallbacks(updateSeekRunnable)
        handler.removeCallbacks(hideControlsRunnable)
    }

    override fun onResume() {
        super.onResume()
        if (!canLoadVideoDirectly(
                isAppLockEnabled = appPreferences.isAppLockEnabled,
                savedPin = appPreferences.appLockPin,
                isSessionUnlocked = com.bangdillah.rekamindulu.HomeActivity.isSessionUnlocked
            )
        ) {
            pausePlaybackIfRunning()
            videoView?.visibility = View.INVISIBLE
            titleText?.text = "Video Terkunci 🔒"
        }
        checkAppLock()
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
                    if (isPrepared) {
                        restoredPosition = vv.currentPosition.coerceAtLeast(0)
                    }
                    restoredWasPlaying = false
                    vv.pause()
                    playPauseBtn?.setImageResource(R.drawable.ic_play_arrow)
                    handler.removeCallbacks(updateSeekRunnable)
                    handler.removeCallbacks(hideControlsRunnable)
                } else {
                    restoredWasPlaying = true
                    vv.start()
                    if (hasPendingSpeedChange) {
                        applyPlaybackSpeed(speedValues[currentSpeedIndex])
                    }
                    playPauseBtn?.setImageResource(R.drawable.ic_pause)
                    handler.post(updateSeekRunnable)
                    scheduleControlsAutoHide()
                }
            }
        }

        // Tap video to toggle controls visibility
        videoView?.setOnTouchListener { v, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                v.performClick()
                toggleControls()
            }
            true
        }


        findViewById<ImageView>(R.id.btn_rewind)?.setOnClickListener {
            videoView?.let { vv ->
                val newPos = (vv.currentPosition - 10000).coerceAtLeast(0)
                restoredPosition = newPos
                vv.seekTo(newPos)
                seekBar?.progress = newPos
                currentTimeText?.text = formatTime(newPos)
                scheduleControlsAutoHide()
            }
        }

        findViewById<ImageView>(R.id.btn_forward)?.setOnClickListener {
            videoView?.let { vv ->
                val maxDur = if (vv.duration > 0) vv.duration else (vv.currentPosition + 10000)
                val newPos = (vv.currentPosition + 10000).coerceIn(0, maxDur)
                restoredPosition = newPos
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
                sb?.let {
                    restoredPosition = it.progress.coerceAtLeast(0)
                    videoView?.seekTo(it.progress)
                }
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
        topBar?.animate()?.cancel()
        bottomPanel?.animate()?.cancel()

        topBar?.visibility = View.VISIBLE
        topBar?.animate()?.alpha(1f)?.setDuration(200)?.withEndAction(null)?.start()

        bottomPanel?.visibility = View.VISIBLE
        bottomPanel?.animate()?.alpha(1f)?.setDuration(200)?.withEndAction(null)?.start()

        areControlsVisible = true
        scheduleControlsAutoHide()
    }

    private fun hideControls() {
        topBar?.animate()?.cancel()
        bottomPanel?.animate()?.cancel()

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
                val mp = currentMediaPlayer
                val isCurrentlyPlaying = isPrepared && (videoView?.isPlaying == true || mp?.isPlaying == true)
                if (mp != null && isCurrentlyPlaying) {
                    val params = mp.playbackParams ?: PlaybackParams()
                    params.speed = speed
                    mp.playbackParams = params
                    hasPendingSpeedChange = false
                } else {
                    // Jangan panggil mp.playbackParams saat pause karena MediaPlayer Android otomatis
                    // memulai pemutaran dan menyebabkan letupan audio sesaat; terapkan saat Play ditekan.
                    hasPendingSpeedChange = true
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Kecepatan pemutaran tidak didukung pada hardware ini", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Fitur ini membutuhkan Android 6.0+", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadAndPlayVideo(file: File) {
        isPrepared = false
        videoView?.setVideoPath(file.absolutePath)

        videoView?.setOnPreparedListener { mp ->
            currentMediaPlayer = mp
            isPrepared = true
            val duration = mp.duration.coerceAtLeast(0)
            seekBar?.max = duration
            totalTimeText?.text = formatTime(duration)

            if (restoredPosition > 0) {
                val targetPos = if (duration > 0) restoredPosition.coerceAtMost(duration) else restoredPosition
                videoView?.seekTo(targetPos)
                seekBar?.progress = targetPos
                currentTimeText?.text = formatTime(targetPos)
            }

            hasPendingSpeedChange = (currentSpeedIndex != 2)
            if (restoredWasPlaying) {
                videoView?.start()
                if (hasPendingSpeedChange) {
                    applyPlaybackSpeed(speedValues[currentSpeedIndex])
                }
                playPauseBtn?.setImageResource(R.drawable.ic_pause)
                handler.post(updateSeekRunnable)
                scheduleControlsAutoHide()
            } else {
                playPauseBtn?.setImageResource(R.drawable.ic_play_arrow)
                showControls()
            }
        }

        videoView?.setOnCompletionListener {
            restoredWasPlaying = false
            restoredPosition = 0
            playPauseBtn?.setImageResource(R.drawable.ic_play_arrow)
            handler.removeCallbacks(updateSeekRunnable)
            showControls()
            videoView?.seekTo(0)
            seekBar?.progress = 0
            currentTimeText?.text = formatTime(0)
        }

        videoView?.setOnErrorListener { _, what, extra ->
            isPrepared = false
            if (!isFinishing && !isDestroyed) {
                try { activeDialog?.dismiss() } catch (ignored: Exception) {}
                activeDialog = MaterialAlertDialogBuilder(this)
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
            AppLockHelper.launchExternalWithLockBypass(
                this,
                Intent.createChooser(shareIntent, "Bagikan Video Rekaman")
            )
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
            AppLockHelper.launchExternalWithLockBypass(
                this,
                Intent.createChooser(viewIntent, "Buka Video dengan")
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak ada aplikasi pemutar video eksternal", Toast.LENGTH_SHORT).show()
        }
    }

    private fun extractAudioFromVideo(file: File) {
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
                            try { activeDialog?.dismiss() } catch (ignored: Exception) {}
                            activeDialog = MaterialAlertDialogBuilder(this)
                                .setTitle("Ekstrak Audio Berhasil! 🎉")
                                .setIcon(R.drawable.ic_check_circle)
                                .setMessage("Audio disimpan sebagai:\n${audioFile.name}\nUkuran: ${String.format(Locale.getDefault(), "%.1f MB", audioFile.length() / (1024.0 * 1024.0))}")
                                .setPositiveButton("Putar Audio") { _, _ ->
                                    try {
                                        val uri = FileProvider.getUriForFile(
                                            this,
                                            "$packageName.fileprovider",
                                            audioFile
                                        )
                                        val playIntent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(uri, "audio/*")
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        AppLockHelper.launchExternalWithLockBypass(
                                            this,
                                            Intent.createChooser(playIntent, "Putar Audio dengan")
                                        )
                                    } catch (e: Exception) {
                                        Toast.makeText(this, "Tidak ada pemutar audio eksternal", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .setNeutralButton("Bagikan") { _, _ ->
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
                                        AppLockHelper.launchExternalWithLockBypass(
                                            this,
                                            Intent.createChooser(shareIntent, "Bagikan Audio")
                                        )
                                    } catch (e: Exception) {
                                        Toast.makeText(this, "Gagal membagikan: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val (pos, playing) = resolveSavedPlaybackState(
            isPrepared = isPrepared,
            isCurrentlyPlaying = videoView?.isPlaying == true,
            liveCurrentPositionMs = videoView?.currentPosition ?: 0,
            restoredPositionMs = restoredPosition,
            restoredWasPlaying = restoredWasPlaying
        )
        outState.putInt("KEY_PLAYBACK_POSITION", pos)
        outState.putBoolean("KEY_WAS_PLAYING", playing)
        outState.putInt("KEY_SPEED_INDEX", currentSpeedIndex)
    }

    override fun onPause() {
        super.onPause()
        val isPlayingNow = videoView?.isPlaying == true
        if (isPrepared) {
            restoredPosition = (videoView?.currentPosition ?: restoredPosition).coerceAtLeast(0)
        }
        if (isPlayingNow) {
            restoredWasPlaying = true
            videoView?.pause()
            playPauseBtn?.setImageResource(R.drawable.ic_play_arrow)
            handler.removeCallbacks(updateSeekRunnable)
            handler.removeCallbacks(hideControlsRunnable)
        } else {
            restoredWasPlaying = false
        }
    }

    override fun onStop() {
        super.onStop()
        surfaceLostInStop = true
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateSeekRunnable)
        handler.removeCallbacks(hideControlsRunnable)
        try { activeDialog?.dismiss() } catch (ignored: Exception) {}
        activeDialog = null
        try { extractProgressDialog?.dismiss() } catch (ignored: Exception) {}
        extractProgressDialog = null
        try { appLockDialog?.dismiss() } catch (ignored: Exception) {}
        appLockDialog = null
        videoView?.stopPlayback()
    }

    private fun formatTime(ms: Int): String = Companion.formatTime(ms)
}
