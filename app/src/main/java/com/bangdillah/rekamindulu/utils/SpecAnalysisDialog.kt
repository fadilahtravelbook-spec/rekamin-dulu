package com.bangdillah.rekamindulu.utils

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.bangdillah.rekamindulu.R
import java.util.Locale

/**
 * SpecAnalysisDialog - Dialog diagnostik kesiapan hardware dan benchmark encoder MediaCodec
 * dengan tampilan modern, rincian teknis mendalam, dan tombol 1-klik terapkan preset performa.
 */
object SpecAnalysisDialog {

    fun show(context: Context, onApplied: (() -> Unit)? = null) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val view = LayoutInflater.from(context).inflate(R.layout.layout_spec_analysis_dialog, null)
        dialog.setContentView(view)

        val dm = context.resources.displayMetrics
        val dialogWidth = (dm.widthPixels * 0.94f).toInt().coerceAtMost((480 * dm.density).toInt())

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }

        val report = DeviceAnalyzer.analyze(context)

        // 1. Bind Score & Status Badge
        view.findViewById<TextView>(R.id.text_dialog_score)?.text = "${report.readinessScore} / 100"
        val badgeView = view.findViewById<TextView>(R.id.text_dialog_status_badge)
        badgeView?.text = report.readinessStatus.uppercase(Locale.getDefault())

        val badgeColor = when {
            report.readinessScore >= 85 -> ContextCompat.getColor(context, R.color.colorSuccess)
            report.readinessScore >= 70 -> ContextCompat.getColor(context, R.color.colorPrimary)
            report.readinessScore >= 50 -> ContextCompat.getColor(context, R.color.colorWarning)
            else -> ContextCompat.getColor(context, R.color.colorError)
        }
        badgeView?.backgroundTintList = ColorStateList.valueOf(badgeColor)

        view.findViewById<ProgressBar>(R.id.progress_dialog_score)?.apply {
            progress = report.readinessScore
            progressTintList = ColorStateList.valueOf(badgeColor)
        }
        view.findViewById<TextView>(R.id.text_dialog_recommendation)?.text = "💡 ${report.recommendationSummary}"

        // 2. Hardware Encoder Benchmark Suite
        val btnRunBenchmark = view.findViewById<Button>(R.id.btn_run_benchmark)
        val progressBenchmark = view.findViewById<ProgressBar>(R.id.progress_benchmark)
        val layoutBenchmarkResult = view.findViewById<View>(R.id.layout_benchmark_result)
        val textBenchmarkResult = view.findViewById<TextView>(R.id.text_benchmark_result)

        btnRunBenchmark?.setOnClickListener {
            btnRunBenchmark.isEnabled = false
            btnRunBenchmark.text = "⚡ Menguji Encoder Hardware MediaCodec..."
            progressBenchmark?.visibility = View.VISIBLE
            layoutBenchmarkResult?.visibility = View.GONE

            DeviceAnalyzer.runEncoderBenchmark(context) { result ->
                (context as? Activity)?.runOnUiThread {
                    btnRunBenchmark.isEnabled = true
                    btnRunBenchmark.text = "⚡ JALANKAN ULANG BENCHMARK"
                    progressBenchmark?.visibility = View.GONE
                    layoutBenchmarkResult?.visibility = View.VISIBLE
                    textBenchmarkResult?.text = result.message
                } ?: run {
                    btnRunBenchmark.isEnabled = true
                    btnRunBenchmark.text = "⚡ JALANKAN ULANG BENCHMARK"
                    progressBenchmark?.visibility = View.GONE
                    layoutBenchmarkResult?.visibility = View.VISIBLE
                    textBenchmarkResult?.text = result.message
                }
            }
        }

        // 3. Preset Buttons (1-Tap Apply)
        view.findViewById<Button>(R.id.btn_preset_gaming)?.setOnClickListener {
            applyPreset(context, "1080p", 60, 12, "Gaming Pro (1080p 60 FPS 12 Mbps)", onApplied, dialog)
        }

        view.findViewById<Button>(R.id.btn_preset_balanced)?.setOnClickListener {
            applyPreset(context, "720p", 60, 8, "Mode Seimbang (720p 60 FPS 8 Mbps)", onApplied, dialog)
        }

        view.findViewById<Button>(R.id.btn_preset_battery)?.setOnClickListener {
            applyPreset(context, "720p", 30, 4, "Hemat Baterai (720p 30 FPS 4 Mbps)", onApplied, dialog)
        }

        // Legacy / Fallback Recommendation Button
        view.findViewById<Button>(R.id.btn_apply_recommendation)?.setOnClickListener {
            applyPreset(
                context,
                report.recommendedResolution,
                report.recommendedFps,
                report.recommendedBitrateMbps,
                "Optimal (${report.recommendedResolution} ${report.recommendedFps} FPS ${report.recommendedBitrateMbps} Mbps)",
                onApplied,
                dialog
            )
        }

        // 4. Bind Detailed Hardware Specs
        view.findViewById<TextView>(R.id.text_diag_device)?.text = "${report.deviceName} (${report.androidVersion})"
        view.findViewById<TextView>(R.id.text_diag_cpu)?.text = "${report.socInfo} (${report.cpuCores} Inti - ${report.cpuArchitecture})"
        view.findViewById<TextView>(R.id.text_diag_ram)?.text = String.format(
            Locale.getDefault(),
            "%.1f GB (%.1f GB Bebas • %d%% Terpakai)",
            report.totalRamGb,
            report.freeRamGb,
            report.usedRamPercent
        )
        view.findViewById<TextView>(R.id.text_diag_storage)?.text = String.format(
            Locale.getDefault(),
            "%.1f GB Bebas (~%.1f Jam Rekam)",
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
        val hwInfo = HardwareEncoderHelper.findBestHardwareEncoder()
        view.findViewById<TextView>(R.id.text_diag_codec)?.text = if (report.isH264Hardware) {
            "${hwInfo?.vendorDescription ?: "Hardware GPU VPU"} (${report.maxSupportedFps} FPS • CPU Offload)"
        } else {
            "H.264 AVC (Software Standar)"
        }
        view.findViewById<TextView>(R.id.text_diag_audio)?.text = if (report.isInternalAudioSupported) {
            "Internal Game 48kHz (Stabil & Anti-Bentrok Voice Chat)"
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

        // 5. Dismiss / Close Buttons
        view.findViewById<ImageView>(R.id.btn_close_dialog)?.setOnClickListener {
            dialog.dismiss()
        }
        view.findViewById<Button>(R.id.btn_dismiss_dialog)?.setOnClickListener {
            dialog.dismiss()
        }

        if (context is Activity && (context.isFinishing || context.isDestroyed)) {
            return
        }
        dialog.show()
    }

    private fun applyPreset(
        context: Context,
        resolution: String,
        fps: Int,
        bitrateMbps: Int,
        presetName: String,
        onApplied: (() -> Unit)?,
        dialog: Dialog
    ) {
        val prefs = AppPreferences(context)
        prefs.resolution = resolution
        prefs.fps = fps
        prefs.bitrateMbps = bitrateMbps

        Toast.makeText(
            context,
            "✅ Preset diterapkan: $presetName",
            Toast.LENGTH_SHORT
        ).show()

        onApplied?.invoke()
        dialog.dismiss()
    }
}
