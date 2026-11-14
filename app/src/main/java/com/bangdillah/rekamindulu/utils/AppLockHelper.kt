package com.bangdillah.rekamindulu.utils

import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bangdillah.rekamindulu.HomeActivity
import com.bangdillah.rekamindulu.MainActivity
import com.bangdillah.rekamindulu.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Melacak transisi foreground/background aplikasi agar sesi PIN otomatis terkunci kembali
 * saat pengguna meninggalkan aplikasi (Home/Recents/mati layar), namun tidak terpicu oleh
 * perpindahan antar-Activity internal atau perubahan konfigurasi (rotasi layar).
 */
class AppLockSessionTracker(
    private val onLockSession: () -> Unit
) {
    var startedActivityCount: Int = 0
        private set

    var suppressNextBackgroundLock: Boolean = false
        private set

    fun suppressNextBackground() {
        suppressNextBackgroundLock = true
    }

    fun clearBackgroundSuppression() {
        suppressNextBackgroundLock = false
    }

    fun onActivityStarted() {
        startedActivityCount++
        suppressNextBackgroundLock = false
    }

    fun onActivityStopped(isChangingConfigurations: Boolean) {
        startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
        if (startedActivityCount == 0 && !isChangingConfigurations) {
            if (suppressNextBackgroundLock) {
                suppressNextBackgroundLock = false
            } else {
                onLockSession()
            }
        }
    }
}

/**
 * Pengelola gerbang PIN keamanan untuk melindungi layar utama, galeri, pemutar, pemotong video, dan pengaturan.
 */
object AppLockHelper {

    val sessionTracker = AppLockSessionTracker {
        MainActivity.isSessionUnlocked = false
    }

    fun lockSession() {
        MainActivity.isSessionUnlocked = false
    }

    fun suppressNextBackgroundLock() {
        sessionTracker.suppressNextBackground()
    }

    fun clearBackgroundLockSuppression() {
        sessionTracker.clearBackgroundSuppression()
    }

    fun shouldRequirePin(
        isAppLockEnabled: Boolean,
        savedPin: String,
        isSessionUnlocked: Boolean
    ): Boolean {
        return isAppLockEnabled && savedPin.isNotEmpty() && !isSessionUnlocked
    }

    fun verifyPin(enteredPin: String, savedPin: String): Boolean {
        return savedPin.isNotEmpty() && enteredPin.trim() == savedPin
    }

    fun showPinGateIfNeeded(
        activity: AppCompatActivity,
        appPreferences: AppPreferences,
        currentDialog: AlertDialog?,
        title: String,
        message: String,
        negativeButtonLabel: String = "Kembali",
        onCancel: () -> Unit = { activity.finish() },
        onPinVerified: () -> Unit = {},
        onUnlocked: () -> Unit = {}
    ): AlertDialog? {
        if (!shouldRequirePin(
                appPreferences.isAppLockEnabled,
                appPreferences.appLockPin,
                HomeActivity.isSessionUnlocked
            )
        ) {
            onUnlocked()
            return null
        }

        if (currentDialog?.isShowing == true) {
            return currentDialog
        }

        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Masukkan PIN (4-6 angka)"
            filters = arrayOf(InputFilter.LengthFilter(6))
            maxLines = 1
            setPadding(48, 36, 48, 36)
            setTextColor(ContextCompat.getColor(activity, R.color.textTitle))
            setHintTextColor(ContextCompat.getColor(activity, R.color.textCaption))
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Buka Kunci", null)
            .setNegativeButton(negativeButtonLabel) { _, _ ->
                onCancel()
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val entered = input.text?.toString().orEmpty()
                if (verifyPin(entered, appPreferences.appLockPin)) {
                    HomeActivity.isSessionUnlocked = true
                    dialog.dismiss()
                    onPinVerified()
                    onUnlocked()
                } else {
                    input.error = "PIN salah! Coba lagi."
                    input.text?.clear()
                }
            }
        }

        try {
            currentDialog?.dismiss()
        } catch (ignored: Exception) {
        }
        dialog.show()
        return dialog
    }
}
