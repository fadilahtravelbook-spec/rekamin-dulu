package com.bangdillah.rekamindulu.about

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bangdillah.rekamindulu.R
import com.bangdillah.rekamindulu.utils.SpecAnalysisDialog

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.about_screen)
        supportActionBar?.hide()

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
            showUpdateDialog()
        }

        // 4. Coffee Support Button
        findViewById<Button>(R.id.btn_support_coffee)?.setOnClickListener {
            showSupportDialog()
        }
    }

    private fun showUpdateDialog() {
        AlertDialog.Builder(this)
            .setTitle("Versi Terbaru Terpasang ✅")
            .setIcon(R.drawable.ic_check_circle)
            .setMessage(
                "Rekamin Dulu v1.0.0 (Pro Release) adalah versi paling mutakhir!\n\n" +
                "Changelog v1.0.0:\n" +
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
        AlertDialog.Builder(this)
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
}
