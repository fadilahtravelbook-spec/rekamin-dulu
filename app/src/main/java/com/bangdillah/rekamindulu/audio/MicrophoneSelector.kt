package com.bangdillah.rekamindulu.audio

import android.media.MediaRecorder
import android.os.Build
import java.util.Locale

/**
 * MicrophoneSelector - Helper untuk memilih tipe sumber audio mikrofon yang digunakan.
 * Mendukung mic default ponsel, headset/bluetooth (AEC), camcorder, dan mode suara murni.
 */
object MicrophoneSelector {

    fun getAudioSource(type: String): Int {
        return when (type.lowercase(Locale.ROOT).trim()) {
            "camcorder" -> MediaRecorder.AudioSource.CAMCORDER
            "voice_comm", "bluetooth", "headset" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            "voice_recognition" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            "voice_performance" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaRecorder.AudioSource.VOICE_PERFORMANCE
                } else {
                    MediaRecorder.AudioSource.MIC
                }
            }
            "unprocessed" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    MediaRecorder.AudioSource.UNPROCESSED
                } else {
                    MediaRecorder.AudioSource.MIC
                }
            }
            "usb", "mic", "default" -> MediaRecorder.AudioSource.MIC
            else -> MediaRecorder.AudioSource.MIC
        }
    }

    fun getDisplayName(type: String): String {
        return when (type.lowercase(Locale.ROOT).trim()) {
            "camcorder" -> "Camcorder (Wide Audio)"
            "voice_comm", "bluetooth", "headset" -> "Headset / Bluetooth (TWS)"
            "voice_recognition" -> "Peredam Bising (Voice Recognition)"
            "unprocessed" -> "Suara Murni (Unprocessed Raw)"
            else -> "Default (Bawaan Ponsel)"
        }
    }

    fun isUnprocessedSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
    }
}

