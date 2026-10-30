package com.bangdillah.rekamindulu.permission

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bangdillah.rekamindulu.R
import com.bangdillah.rekamindulu.utils.AppPreferences

class PermissionDialogActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 1001
    }

    private lateinit var appPreferences: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.permission_dialog)

        appPreferences = AppPreferences(this)

        val btnTambahIzin = findViewById<Button>(R.id.btn_tambah_izin)
        val btnTidakKeluar = findViewById<Button>(R.id.btn_tidak_keluar)

        btnTambahIzin?.setOnClickListener {
            checkAndRequestPermissions()
        }

        btnTidakKeluar?.setOnClickListener {
            finish()
        }
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
            if (audioIndex != -1 && grantResults.getOrNull(audioIndex) == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "✓ Izin mikrofon berhasil diaktifkan", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }
}
