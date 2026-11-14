package com.bangdillah.rekamindulu

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bangdillah.rekamindulu.onboarding.OnboardingActivity
import com.bangdillah.rekamindulu.utils.AppPreferences
import com.bangdillah.rekamindulu.utils.UiHelper

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

        val rootLayout = findViewById<View>(R.id.splash_root_layout)
        UiHelper.applySystemBarInsets(
            anchorView = rootLayout,
            topView = rootLayout,
            bottomView = rootLayout,
            horizontalView = rootLayout
        )

        val versionName = try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            info.versionName?.takeIf { it.isNotBlank() } ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }
        findViewById<TextView>(R.id.text_splash_version)?.text = "Versi $versionName • By Bangdillah210"

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
