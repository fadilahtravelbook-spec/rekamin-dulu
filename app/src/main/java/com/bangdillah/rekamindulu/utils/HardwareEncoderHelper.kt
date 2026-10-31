package com.bangdillah.rekamindulu.utils

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.util.Locale

/**
 * HardwareEncoderHelper - Pengelola & validator akselerasi hardware GPU/VPU
 * untuk encoding video H.264/H.265 secara offload dari CPU.
 *
 * Menjamin proses perekaman layar memanfaatkan chipset dedicated GPU/VPU
 * (Snapdragon Adreno, MediaTek Mali, Samsung Exynos, Google Tensor, Kirin)
 * sehingga CPU tidak kewalahan saat menjalankan game berat secara bersamaan.
 */
object HardwareEncoderHelper {

    private const val TAG = "HardwareEncoderHelper"

    data class EncoderCapability(
        val codecName: String,
        val mimeType: String,
        val isHardware: Boolean,
        val vendorDescription: String,
        val maxSupportedWidth: Int,
        val maxSupportedHeight: Int,
        val maxSupportedFps: Int,
        val isCbrSupported: Boolean
    )

    /**
     * Vendor hardware prefix yang menandakan GPU/VPU dedicated encoder (Bukan software CPU)
     */
    private val HARDWARE_VENDOR_PREFIXES = listOf(
        "omx.qcom.", "c2.qti.", // Qualcomm Snapdragon (Adreno VPU)
        "omx.mtk.", "c2.mtk.", // MediaTek Dimensity / Helio (Mali VPU)
        "omx.exynos.", "c2.exynos.", // Samsung Exynos
        "c2.google.", // Google Tensor hardware encoder
        "omx.hisi.", "c2.hisi.", // HiSilicon Kirin
        "omx.img.", "c2.img.", // Imagination PowerVR
        "omx.nvidia.", // Nvidia Tegra
        "omx.sprd.", "c2.sprd.", // Unisoc / Spreadtrum
        "omx.intel." // Intel Atom / Moorefield
    )

    /**
     * Memeriksa apakah perangkat memiliki Hardware Video Encoder aktif untuk H.264 / H.265
     */
    fun isHardwareAccelerationSupported(mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC): Boolean {
        return findBestHardwareEncoder(mimeType) != null
    }

    /**
     * Mencari encoder hardware terbaik di perangkat
     */
    fun findBestHardwareEncoder(mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC): EncoderCapability? {
        return try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val codecInfos = codecList.codecInfos

            var candidate: EncoderCapability? = null

            for (info in codecInfos) {
                if (!info.isEncoder) continue
                val types = info.supportedTypes
                for (type in types) {
                    if (type.equals(mimeType, ignoreCase = true)) {
                        val isHw = isCodecHardwareAccelerated(info)
                        val vendor = identifyVendor(info.name)

                        val caps = try {
                            val c = info.getCapabilitiesForType(type)
                            val vCaps = c.videoCapabilities
                            val maxW = vCaps.supportedWidths.upper
                            val maxH = vCaps.supportedHeights.upper
                            val maxF = vCaps.supportedFrameRates.upper
                            val isCbr = try {
                                c.encoderCapabilities.isBitrateModeSupported(
                                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                                )
                            } catch (e: Exception) { false }

                            EncoderCapability(
                                codecName = info.name,
                                mimeType = mimeType,
                                isHardware = isHw,
                                vendorDescription = vendor,
                                maxSupportedWidth = maxW,
                                maxSupportedHeight = maxH,
                                maxSupportedFps = maxF,
                                isCbrSupported = isCbr
                            )
                        } catch (e: Exception) {
                            EncoderCapability(
                                codecName = info.name,
                                mimeType = mimeType,
                                isHardware = isHw,
                                vendorDescription = vendor,
                                maxSupportedWidth = 1920,
                                maxSupportedHeight = 1080,
                                maxSupportedFps = 60,
                                isCbrSupported = true
                            )
                        }

                        // Prioritaskan hardware encoder asli
                        if (isHw) {
                            return caps
                        } else if (candidate == null) {
                            candidate = caps
                        }
                    }
                }
            }
            candidate
        } catch (e: Throwable) {
            Log.w(TAG, "Gagal menginspeksi MediaCodecList: ${e.message}")
            null
        }
    }

    /**
     * Memverifikasi apakah MediaCodecInfo adalah akselerasi hardware
     */
    fun isCodecHardwareAccelerated(info: MediaCodecInfo): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ memiliki flag resmi dari sistem
                if (info.isHardwareAccelerated) return true
                if (info.isSoftwareOnly) return false
            }
        } catch (ignored: Throwable) {}

        val nameLower = info.name.lowercase(Locale.US)
        if (nameLower.contains("google") && !nameLower.contains("c2.google.avc")) return false
        if (nameLower.contains("android") && !nameLower.contains("qti")) return false
        if (nameLower.contains("sw") || nameLower.contains("software")) return false

        for (prefix in HARDWARE_VENDOR_PREFIXES) {
            if (nameLower.startsWith(prefix) || nameLower.contains(prefix)) {
                return true
            }
        }
        return false
    }

    /**
     * Identifikasi vendor hardware SoC untuk display UI diagnostik
     */
    fun identifyVendor(codecName: String): String {
        val lower = codecName.lowercase(Locale.US)
        return when {
            lower.contains("qcom") || lower.contains("qti") -> "Qualcomm Adreno VPU (Hardware)"
            lower.contains("mtk") -> "MediaTek Mali VPU (Hardware)"
            lower.contains("exynos") -> "Samsung Exynos VPU (Hardware)"
            lower.contains("c2.google") -> "Google Tensor TPU/VPU (Hardware)"
            lower.contains("hisi") -> "HiSilicon Kirin VPU (Hardware)"
            lower.contains("img") || lower.contains("powervr") -> "PowerVR GPU (Hardware)"
            lower.contains("nvidia") -> "Nvidia Tegra GPU (Hardware)"
            lower.contains("sprd") -> "Unisoc VPU (Hardware)"
            lower.contains("google") || lower.contains("android") -> "Software CPU Encoder"
            else -> "Hardware Codec ($codecName)"
        }
    }

    /**
     * Status offload CPU untuk ditampilkan pada floating overlay HUD & dialog kesiapan
     */
    fun getHardwareOffloadStatus(isHardwareEnabled: Boolean): String {
        return if (!isHardwareEnabled) {
            "Mode Standar CPU"
        } else {
            val hw = try { findBestHardwareEncoder() } catch (ignored: Throwable) { null }
            if (hw != null && hw.isHardware) {
                "GPU Hardware Offload (Beban CPU Ringan)"
            } else {
                "Akselerasi Adaptif"
            }
        }
    }
}
