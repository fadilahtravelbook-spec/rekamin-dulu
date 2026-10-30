package com.bangdillah.rekamindulu.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("rekamin_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_RESOLUTION = "KEY_RESOLUTION"
        private const val KEY_FPS = "KEY_FPS"
        private const val KEY_BITRATE = "KEY_BITRATE"
        private const val KEY_AUDIO_SOURCE = "KEY_AUDIO_SOURCE"
        private const val KEY_MIC_TYPE = "KEY_MIC_TYPE"
        private const val KEY_FACECAM = "KEY_FACECAM"
        private const val KEY_GAMING_MODE = "KEY_GAMING_MODE"
        private const val KEY_COUNTDOWN = "KEY_COUNTDOWN"
        private const val KEY_VIBRATE = "KEY_VIBRATE"
        private const val KEY_STOP_SCREEN_OFF = "KEY_STOP_SCREEN_OFF"
        private const val KEY_FLOATING_OVERLAY = "KEY_FLOATING_OVERLAY"
        private const val KEY_VIDEO_ENCODER = "KEY_VIDEO_ENCODER"
        private const val KEY_ORIENTATION = "KEY_ORIENTATION"
        private const val KEY_ONBOARDING_DONE = "KEY_ONBOARDING_DONE"
        private const val KEY_APP_LOCK_ENABLED = "KEY_APP_LOCK_ENABLED"
        private const val KEY_APP_LOCK_PIN = "KEY_APP_LOCK_PIN"
        private const val KEY_THEME_MODE = "KEY_THEME_MODE"
        private const val KEY_SHOW_FPS = "KEY_SHOW_FPS"
        private const val KEY_STORAGE_WARNING = "KEY_STORAGE_WARNING"
    }

    var resolution: String
        get() = prefs.getString(KEY_RESOLUTION, "1080p") ?: "1080p"
        set(value) = prefs.edit().putString(KEY_RESOLUTION, value).apply()

    var fps: Int
        get() = prefs.getInt(KEY_FPS, 30)
        set(value) = prefs.edit().putInt(KEY_FPS, value).apply()

    var bitrateMbps: Int
        get() = prefs.getInt(KEY_BITRATE, 8)
        set(value) = prefs.edit().putInt(KEY_BITRATE, value).apply()

    var audioSource: String
        get() = prefs.getString(KEY_AUDIO_SOURCE, "BOTH") ?: "BOTH"
        set(value) = prefs.edit().putString(KEY_AUDIO_SOURCE, value).apply()

    var micType: String
        get() = prefs.getString(KEY_MIC_TYPE, "default") ?: "default"
        set(value) = prefs.edit().putString(KEY_MIC_TYPE, value).apply()

    var facecamMode: String
        get() = prefs.getString(KEY_FACECAM, "OFF") ?: "OFF"
        set(value) = prefs.edit().putString(KEY_FACECAM, value).apply()

    var isGamingMode: Boolean
        get() = prefs.getBoolean(KEY_GAMING_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_GAMING_MODE, value).apply()

    var countdownDuration: Int
        get() = prefs.getInt(KEY_COUNTDOWN, 3)
        set(value) = prefs.edit().putInt(KEY_COUNTDOWN, value).apply()

    var vibrateOnStart: Boolean
        get() = prefs.getBoolean(KEY_VIBRATE, true)
        set(value) = prefs.edit().putBoolean(KEY_VIBRATE, value).apply()

    var stopOnScreenOff: Boolean
        get() = prefs.getBoolean(KEY_STOP_SCREEN_OFF, true)
        set(value) = prefs.edit().putBoolean(KEY_STOP_SCREEN_OFF, value).apply()

    var showFloatingOverlay: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_OVERLAY, true)
        set(value) = prefs.edit().putBoolean(KEY_FLOATING_OVERLAY, value).apply()

    var videoEncoder: String
        get() = prefs.getString(KEY_VIDEO_ENCODER, "H264") ?: "H264"
        set(value) = prefs.edit().putString(KEY_VIDEO_ENCODER, value).apply()

    var orientation: String
        get() = prefs.getString(KEY_ORIENTATION, "AUTO") ?: "AUTO"
        set(value) = prefs.edit().putString(KEY_ORIENTATION, value).apply()

    var isOnboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_DONE, value).apply()

    var isAppLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_APP_LOCK_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_APP_LOCK_ENABLED, value).apply()

    var appLockPin: String
        get() = prefs.getString(KEY_APP_LOCK_PIN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_APP_LOCK_PIN, value).apply()

    var themeMode: String
        get() = prefs.getString(KEY_THEME_MODE, "SYSTEM") ?: "SYSTEM"
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value).apply()

    var showFpsCounter: Boolean
        get() = prefs.getBoolean(KEY_SHOW_FPS, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_FPS, value).apply()

    var storageWarningEnabled: Boolean
        get() = prefs.getBoolean(KEY_STORAGE_WARNING, true)
        set(value) = prefs.edit().putBoolean(KEY_STORAGE_WARNING, value).apply()

    fun applyTheme() {
        when (themeMode) {
            "LIGHT" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "DARK" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    fun resetToDefaults() {
        prefs.edit()
            .putString(KEY_RESOLUTION, "1080p")
            .putInt(KEY_FPS, 30)
            .putInt(KEY_BITRATE, 8)
            .putString(KEY_AUDIO_SOURCE, "BOTH")
            .putString(KEY_MIC_TYPE, "default")
            .putString(KEY_FACECAM, "OFF")
            .putBoolean(KEY_GAMING_MODE, true)
            .putInt(KEY_COUNTDOWN, 3)
            .putBoolean(KEY_VIBRATE, true)
            .putBoolean(KEY_STOP_SCREEN_OFF, true)
            .putBoolean(KEY_FLOATING_OVERLAY, true)
            .putString(KEY_VIDEO_ENCODER, "H264")
            .putString(KEY_ORIENTATION, "AUTO")
            .putBoolean(KEY_SHOW_FPS, true)
            .putBoolean(KEY_STORAGE_WARNING, true)
            .putString(KEY_THEME_MODE, "SYSTEM")
            .apply()
        applyTheme()
    }
}
