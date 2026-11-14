package com.bangdillah.rekamindulu.utils

import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bangdillah.rekamindulu.HomeActivity
import com.bangdillah.rekamindulu.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Pengelola gerbang PIN keamanan untuk melindungi layar utama, galeri, pemutar, pemotong video, dan pengaturan.
 */
object AppLockHelper {

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
