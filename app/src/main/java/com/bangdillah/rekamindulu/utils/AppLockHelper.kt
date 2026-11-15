package com.bangdillah.rekamindulu.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.text.InputFilter
import android.text.InputType
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bangdillah.rekamindulu.HomeActivity
import com.bangdillah.rekamindulu.MainActivity
import com.bangdillah.rekamindulu.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.security.MessageDigest
import java.security.SecureRandom

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

    /**
     * Membuka chooser/aplikasi sistem eksternal sambil menahan penguncian sesi PIN sementara,
     * dan otomatis membatalkan penahanan tersebut bila startActivity gagal melempar exception.
     */
    fun launchExternalWithLockBypass(context: Context, intent: Intent) {
        suppressNextBackgroundLock()
        try {
            context.startActivity(intent)
        } catch (t: Throwable) {
            clearBackgroundLockSuppression()
            throw t
        }
    }

    fun shouldMaskScreenInRecents(
        isAppLockEnabled: Boolean,
        savedPin: String
    ): Boolean {
        return isAppLockEnabled && savedPin.isNotEmpty()
    }

    fun syncWindowSecurityFlag(activity: Activity, appPreferences: AppPreferences) {
        syncWindowSecurityFlag(activity.window, appPreferences)
    }

    fun syncWindowSecurityFlag(window: Window?, appPreferences: AppPreferences) {
        if (window == null) return
        if (shouldMaskScreenInRecents(appPreferences.isAppLockEnabled, appPreferences.appLockPin)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private const val HASH_PREFIX = "sha256:"
    private val secureRandom = SecureRandom()

    fun isHashedPin(savedPin: String): Boolean {
        return savedPin.startsWith(HASH_PREFIX)
    }

    /**
     * Menghasilkan hash SHA-256 bersalt acak 16-byte dengan format:
     * `sha256:<hex_salt>:<hex_digest>`
     */
    fun hashPin(pin: String): String {
        val cleanPin = pin.trim()
        val salt = ByteArray(16)
        secureRandom.nextBytes(salt)
        val saltHex = salt.joinToString("") { "%02x".format(it) }
        val digestHex = computeSha256Hex(saltHex, cleanPin)
        return "$HASH_PREFIX$saltHex:$digestHex"
    }

    private fun computeSha256Hex(saltHex: String, cleanPin: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(saltHex.toByteArray(Charsets.UTF_8))
        md.update(":".toByteArray(Charsets.UTF_8))
        md.update(cleanPin.toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun migrateLegacyPinIfNeeded(appPreferences: AppPreferences, verifiedPlaintextPin: String) {
        val current = appPreferences.appLockPin
        if (current.isNotEmpty() && !isHashedPin(current) && verifiedPlaintextPin.trim() == current) {
            appPreferences.appLockPin = hashPin(verifiedPlaintextPin)
        }
    }

    fun shouldRequirePin(
        isAppLockEnabled: Boolean,
        savedPin: String,
        isSessionUnlocked: Boolean
    ): Boolean {
        return isAppLockEnabled && savedPin.isNotEmpty() && !isSessionUnlocked
    }

    fun verifyPin(enteredPin: String, savedPin: String): Boolean {
        if (savedPin.isEmpty()) return false
        val cleanEntered = enteredPin.trim()
        if (cleanEntered.isEmpty()) return false
        if (savedPin.startsWith(HASH_PREFIX)) {
            val payload = savedPin.removePrefix(HASH_PREFIX)
            val sep = payload.indexOf(':')
            if (sep <= 0 || sep >= payload.lastIndex) return false
            val saltHex = payload.substring(0, sep)
            val expectedHex = payload.substring(sep + 1)
            val actualHex = computeSha256Hex(saltHex, cleanEntered)
            return MessageDigest.isEqual(
                actualHex.toByteArray(Charsets.UTF_8),
                expectedHex.toByteArray(Charsets.UTF_8)
            )
        }
        return cleanEntered == savedPin
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

        val density = activity.resources.displayMetrics.density
        val padH = (20 * density).toInt()
        val padV = (14 * density).toInt()
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Masukkan PIN (4-6 angka)"
            filters = arrayOf(InputFilter.LengthFilter(6))
            maxLines = 1
            setPadding(padH, padV, padH, padV)
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
                    migrateLegacyPinIfNeeded(appPreferences, entered)
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
        syncWindowSecurityFlag(dialog.window, appPreferences)
        dialog.show()
        return dialog
    }
}
