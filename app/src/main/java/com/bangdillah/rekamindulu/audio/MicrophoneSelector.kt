package com.bangdillah.rekamindulu.audio

import android.media.MediaRecorder
import android.os.Build

/**
 * MicrophoneSelector - Helper untuk memilih tipe sumber audio mikrofon yang digunakan.
 */
object MicrophoneSelector {
    fun getAudioSource(type: String): Int {
        return when (type.lowercase()) {
            "camcorder" -> MediaRecorder.AudioSource.CAMCORDER
            "voice_comm", "bluetooth" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            "unprocessed" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    MediaRecorder.AudioSource.UNPROCESSED
                } else {
                    MediaRecorder.AudioSource.MIC
                }
            }
            "usb", "mic" -> MediaRecorder.AudioSource.MIC
            else -> MediaRecorder.AudioSource.MIC
        }
    }
}
