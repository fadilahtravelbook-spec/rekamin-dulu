package com.bangdillah.rekamindulu.utils

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.bangdillah.rekamindulu.R
import java.util.Locale

object SpecAnalysisDialog {

    fun show(context: Context, onApplied: (() -> Unit)? = null) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val view = LayoutInflater.from(context).inflate(R.layout.layout_spec_analysis_dialog, null)
        dialog.setContentView(view)

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val report = DeviceAnalyzer.analyze(context)

        // Bind Score & Recommendations
        view.findViewById<TextView>(R.id.text_dialog_score)?.text = "${report.readinessScore} / 100"
        view.findViewById<TextView>(R.id.text_dialog_status_badge)?.text = report.readinessStatus.uppercase(Locale.getDefault())
        view.findViewById<ProgressBar>(R.id.progress_dialog_score)?.progress = report.readinessScore
        view.findViewById<TextView>(R.id.text_dialog_recommendation)?.text = "💡 ${report.recommendationSummary}"

        // Bind Detailed Specs
        view.findViewById<TextView>(R.id.text_diag_device)?.text = "${report.deviceName} (${report.androidVersion})"
        view.findViewById<TextView>(R.id.text_diag_cpu)?.text = "${report.socInfo} (${report.cpuCores} Inti - ${report.cpuArchitecture})"
        view.findViewById<TextView>(R.id.text_diag_ram)?.text = String.format(
            Locale.getDefault(),
            "%.1f GB (%.1f GB Bebas • %d%% Digunakan)",
            report.totalRamGb,
            report.freeRamGb,
            report.usedRamPercent
        )
        view.findViewById<TextView>(R.id.text_diag_storage)?.text = String.format(
            Locale.getDefault(),
            "%.1f GB Bebas (~%.1f Jam Rekaman)",
            report.freeStorageGb,
            report.estRecordingHoursLeft
        )
        view.findViewById<TextView>(R.id.text_diag_screen)?.text = String.format(
            Locale.getDefault(),
            "%d x %d (%d Hz)",
            report.screenWidth,
            report.screenHeight,
            report.refreshRateHz
        )
        view.findViewById<TextView>(R.id.text_diag_codec)?.text = if (report.isH264Hardware) {
            "H.264 AVC (Akselerasi HW ${report.maxSupportedFps} FPS)"
        } else {
            "H.264 AVC (Software Standar)"
        }
        view.findViewById<TextView>(R.id.text_diag_audio)?.text = if (report.isInternalAudioSupported) {
            "Didukung (Internal Game Audio + Mic)"
        } else {
            "Mikrofon Bawaan (Android < 10)"
        }
        view.findViewById<TextView>(R.id.text_diag_battery)?.text = String.format(
            Locale.getDefault(),
            "%d%% • Suhu %.1f°C %s",
            report.batteryPct,
            report.batteryTempCelsius,
            if (report.batteryTempCelsius <= 38.0f) "(Aman)" else "(Hangat)"
        )

        // Button Apply Recommendation
        view.findViewById<Button>(R.id.btn_apply_recommendation)?.setOnClickListener {
            val prefs = AppPreferences(context)
            prefs.resolution = report.recommendedResolution
            prefs.fps = report.recommendedFps
            prefs.bitrateMbps = report.recommendedBitrateMbps

            Toast.makeText(
                context,
                "Preset optimal diterapkan: ${report.recommendedResolution} • ${report.recommendedFps} FPS • ${report.recommendedBitrateMbps} Mbps",
                Toast.LENGTH_LONG
            ).show()

            onApplied?.invoke()
            dialog.dismiss()
        }

        // Close Buttons
        view.findViewById<ImageView>(R.id.btn_close_dialog)?.setOnClickListener {
            dialog.dismiss()
        }
        view.findViewById<Button>(R.id.btn_dismiss_dialog)?.setOnClickListener {
            dialog.dismiss()
        }

        if (context is android.app.Activity && (context.isFinishing || context.isDestroyed)) {
            return
        }
        dialog.show()
    }
}
