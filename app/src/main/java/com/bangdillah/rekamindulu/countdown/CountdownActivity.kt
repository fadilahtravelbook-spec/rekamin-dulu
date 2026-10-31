package com.bangdillah.rekamindulu.countdown

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bangdillah.rekamindulu.R
import com.bangdillah.rekamindulu.service.ScreenRecordingService
import com.bangdillah.rekamindulu.utils.AppPreferences

class CountdownActivity : AppCompatActivity() {

    private var countdownTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPreferences(this).applyTheme()
        setContentView(R.layout.countdown_screen)
        supportActionBar?.hide()

        val numberText = findViewById<TextView>(R.id.countdown_number)
        val progressBar = findViewById<ProgressBar>(R.id.countdown_progress)
        val startText = findViewById<TextView>(R.id.countdown_text)
        val btnCancel = findViewById<Button>(R.id.btn_cancel_countdown)

        val prefs = AppPreferences(this)
        val totalSeconds = prefs.countdownDuration.coerceAtLeast(1)

        val resultCode = intent.getIntExtra(ScreenRecordingService.EXTRA_RESULT_CODE, Activity.RESULT_OK)
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(ScreenRecordingService.EXTRA_RESULT_DATA, Intent::class.java)
                ?: @Suppress("DEPRECATION") (intent.getParcelableExtra(ScreenRecordingService.EXTRA_RESULT_DATA) as? Intent)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(ScreenRecordingService.EXTRA_RESULT_DATA) as? Intent
        }

        progressBar?.max = totalSeconds
        progressBar?.progress = 0
        numberText?.text = totalSeconds.toString()

        btnCancel?.setOnClickListener {
            countdownTimer?.cancel()
            finish()
        }

        countdownTimer = object : CountDownTimer((totalSeconds * 1000 + 300).toLong(), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).toInt().coerceAtLeast(1)
                numberText?.text = seconds.toString()
                val progress = totalSeconds - seconds
                progressBar?.progress = progress

                if (seconds <= 1) {
                    startText?.text = "Mulai Merekam..."
                }
            }

            override fun onFinish() {
                numberText?.text = "GO!"
                progressBar?.progress = totalSeconds

                val isResultCodeValid = (resultCode == Activity.RESULT_OK) || (intent.hasExtra(ScreenRecordingService.EXTRA_RESULT_CODE) && resultCode != Activity.RESULT_CANCELED)
                if (isResultCodeValid && resultData != null) {
                    val serviceIntent = Intent(this@CountdownActivity, ScreenRecordingService::class.java).apply {
                        action = ScreenRecordingService.ACTION_START
                        putExtra(ScreenRecordingService.EXTRA_RESULT_CODE, resultCode)
                        putExtra(ScreenRecordingService.EXTRA_RESULT_DATA, resultData)
                        val audioSrc = intent.getStringExtra("EXTRA_AUDIO_SOURCE") ?: prefs.audioSource
                        putExtra("EXTRA_AUDIO_SOURCE", audioSrc)
                        if (intent.hasExtra("EXTRA_MIC_ENABLED")) {
                            putExtra("EXTRA_MIC_ENABLED", intent.getBooleanExtra("EXTRA_MIC_ENABLED", true))
                        }
                        if (intent.hasExtra("EXTRA_GAME_AUDIO_ENABLED")) {
                            putExtra("EXTRA_GAME_AUDIO_ENABLED", intent.getBooleanExtra("EXTRA_GAME_AUDIO_ENABLED", true))
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                }
                finish()
            }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        countdownTimer?.cancel()
        super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        countdownTimer?.cancel()
    }
}
