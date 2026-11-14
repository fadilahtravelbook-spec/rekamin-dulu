package com.bangdillah.rekamindulu.about

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bangdillah.rekamindulu.R
import com.bangdillah.rekamindulu.utils.AppPreferences
import com.bangdillah.rekamindulu.utils.SpecAnalysisDialog
import com.bangdillah.rekamindulu.utils.UiHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AboutActivity : AppCompatActivity() {

    companion object {
        fun formatVersionBadge(versionName: String?): String {
            val clean = versionName?.trim()?.takeIf { it.isNotEmpty() } ?: "1.0"
            return "Versi $clean (Pro Release)"
        }
    }

    private var activeDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPreferences(this).applyTheme()
        setContentView(R.layout.about_screen)
        supportActionBar?.hide()

        UiHelper.applySystemBarInsets(
            anchorView = findViewById(R.id.about_root_scroll),
            topView = findViewById(R.id.about_inner_content),
            bottomView = findViewById(R.id.about_inner_content)
        )

        val appVersion = resolveAppVersionName()
        findViewById<TextView>(R.id.text_about_version)?.text = formatVersionBadge(appVersion)

        findViewById<android.view.View>(R.id.btn_about_back)?.setOnClickListener {
            finish()
        }

        // 1. Share App Button
        findViewById<Button>(R.id.btn_share_app)?.setOnClickListener {
            val sharingIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(
                    Intent.EXTRA_SUBJECT,
                    "Aplikasi Rekamin Dulu by Bangdillah210"
                )
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Yuk gunakan Rekamin Dulu! Aplikasi perekam layar Android tanpa watermark, audio internal game super jernih, 1080p 60FPS & kontrol melayang profesional. Dikembangkan oleh Bangdillah210."
                )
            }
            startActivity(Intent.createChooser(sharingIntent, "Bagikan Rekamin Dulu"))
        }

        // 2. Hardware Diagnostics
        findViewById<Button>(R.id.btn_about_diagnostics)?.setOnClickListener {
            SpecAnalysisDialog.show(this)
        }

        // 3. Check Update Button
        findViewById<Button>(R.id.btn_check_update)?.setOnClickListener {
            showUpdateDialog(appVersion)
        }

        // 4. Coffee Support Button
        findViewById<Button>(R.id.btn_support_coffee)?.setOnClickListener {
            showSupportDialog()
        }
    }

    private fun resolveAppVersionName(): String {
        return try {
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
    }

    private fun showUpdateDialog(appVersion: String) {
        activeDialog?.dismiss()
        activeDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Versi Terbaru Terpasang ✅")
            .setIcon(R.drawable.ic_check_circle)
            .setMessage(
                "Rekamin Dulu v$appVersion (Pro Release) adalah versi paling mutakhir!\n\n" +
                "Changelog v$appVersion:\n" +
                "• Perekaman layar 1080p / 4K hingga 60-120 FPS\n" +
                "• Dual Audio (Mikrofon & Audio Internal Game)\n" +
                "• Pemotong Video Cepat (Quick Trim)\n" +
                "• Fitur Ekstrak Audio ke M4A/AAC\n" +
                "• Floating Overlay dengan Live FPS Counter\n" +
                "• Fitur App Lock Keamanan PIN\n" +
                "• Pemindai Hardware & Rekomendasi Pintar"
            )
            .setPositiveButton("Bagus", null)
            .setNeutralButton("Bagikan Update") { _, _ ->
                findViewById<Button>(R.id.btn_share_app)?.performClick()
            }
            .show()
    }

    private fun showSupportDialog() {
        activeDialog?.dismiss()
        activeDialog = MaterialAlertDialogBuilder(this)
            .setTitle("☕ Traktir Kopi Bangdillah210")
            .setMessage(
                "Dukungan Anda sangat berarti untuk membantu pengembangan fitur-fitur baru di Rekamin Dulu!\n\n" +
                "Pilihan Dukungan:\n" +
                "1. Bagikan aplikasi ke teman-teman & komunitas game\n" +
                "2. Berikan kritik dan saran pengembangan fitur\n" +
                "3. Donasi sukarela melalui Saweria / Trakteer / E-Wallet"
            )
            .setPositiveButton("Buka Trakteer") { _, _ ->
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://trakteer.id/bangdillah210"))
                    startActivity(browserIntent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Terima kasih atas niat baik Anda!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Salin Pesan Dukungan") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Dukungan Bangdillah210", "Dukung Rekamin Dulu by Bangdillah210!")
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Pesan dukungan disalin ke clipboard! ❤️", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    override fun onDestroy() {
        try { activeDialog?.dismiss() } catch (_: Exception) {}
        activeDialog = null
        super.onDestroy()
    }
}
