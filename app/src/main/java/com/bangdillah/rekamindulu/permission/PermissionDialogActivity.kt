package com.bangdillah.rekamindulu.permission

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bangdillah.rekamindulu.R
import com.bangdillah.rekamindulu.utils.AppPreferences
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class PermissionDialogActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 1001
    }

    private lateinit var appPreferences: AppPreferences
    private var settingsDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appPreferences = AppPreferences(this)
        appPreferences.applyTheme()
        supportActionBar?.hide()
        window.setBackgroundDrawableResource(android.R.color.transparent)
        setContentView(R.layout.permission_dialog)

        applyDialogWindowBounds()

        val btnTambahIzin = findViewById<Button>(R.id.btn_tambah_izin)
        val btnTidakKeluar = findViewById<Button>(R.id.btn_tidak_keluar)

        btnTambahIzin?.setOnClickListener {
            checkAndRequestPermissions()
        }

        btnTidakKeluar?.setOnClickListener {
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        applyDialogWindowBounds()
    }

    private fun applyDialogWindowBounds() {
        val dm = resources.displayMetrics
        val maxWidthPx = (400 * dm.density).toInt()
        val targetWidth = (dm.widthPixels * 0.90f).toInt().coerceAtMost(maxWidthPx)
        val maxHeightPx = (dm.heightPixels * 0.90f).toInt()

        window.setLayout(targetWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.CENTER)
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissions(missingPermissions.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        } else {
            Toast.makeText(this, "✓ Seluruh izin perekaman telah aktif!", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            val audioIndex = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
            val audioGranted = audioIndex != -1 && grantResults.getOrNull(audioIndex) == PackageManager.PERMISSION_GRANTED

            if (audioGranted) {
                Toast.makeText(this, "✓ Izin mikrofon berhasil diaktifkan", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            } else {
                val rationaleRequired = shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
                if (!rationaleRequired) {
                    // Permanently denied (Don't ask again)
                    showSettingsDialog()
                } else {
                    Toast.makeText(this, "Izin perekaman belum diaktifkan", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun showSettingsDialog() {
        if (isFinishing || isDestroyed) return

        dismissSettingsDialog()

        settingsDialog = MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_permission)
            .setTitle("Izin Diperlukan")
            .setMessage("Izin mikrofon dibutuhkan untuk merekam suara game & bicara. Buka pengaturan aplikasi untuk mengaktifkannya?")
            .setPositiveButton("Buka Pengaturan") { dialog, _ ->
                dialog.dismiss()
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
                finish()
            }
            .setNegativeButton("Nanti Saja") { dialog, _ ->
                dialog.dismiss()
                finish()
            }
            .setOnCancelListener { dialog ->
                dialog.dismiss()
                finish()
            }
            .create()

        settingsDialog?.show()
    }

    private fun dismissSettingsDialog() {
        try {
            if (settingsDialog?.isShowing == true) {
                settingsDialog?.dismiss()
            }
        } catch (ignored: Exception) {
        }
        settingsDialog = null
    }

    override fun onDestroy() {
        dismissSettingsDialog()
        super.onDestroy()
    }
}
