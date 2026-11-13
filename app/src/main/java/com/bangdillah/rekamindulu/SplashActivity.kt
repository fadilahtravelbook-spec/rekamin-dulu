package com.bangdillah.rekamindulu

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.bangdillah.rekamindulu.onboarding.OnboardingActivity
import com.bangdillah.rekamindulu.utils.AppPreferences

/**
 * SplashActivity - Halaman splash screen branding Rekamin Dulu.
 */
class SplashActivity : AppCompatActivity() {

    private val splashHandler = Handler(Looper.getMainLooper())
    private var navigateRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appPreferences = AppPreferences(this)
        appPreferences.applyTheme()

        setContentView(R.layout.splash_screen)
        supportActionBar?.hide()

        val runnable = Runnable {
            if (isFinishing || isDestroyed) return@Runnable
            if (!appPreferences.isOnboardingDone) {
                startActivity(Intent(this, OnboardingActivity::class.java))
            } else {
                startActivity(Intent(this, HomeActivity::class.java))
            }
            finish()
        }
        navigateRunnable = runnable
        splashHandler.postDelayed(runnable, 1200)
    }

    override fun onDestroy() {
        navigateRunnable?.let { splashHandler.removeCallbacks(it) }
        super.onDestroy()
    }
}
