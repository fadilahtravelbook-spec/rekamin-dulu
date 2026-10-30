package com.bangdillah.rekamindulu.audio

import android.media.MediaRecorder

/**
 * MicrophoneSelector - Helper untuk memilih tipe mikrofon yang digunakan.
 */
object MicrophoneSelector {
    fun getAudioSource(type: String): Int {
        return when (type) {
            "bluetooth" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            "usb" -> MediaRecorder.AudioSource.MIC
            else -> MediaRecorder.AudioSource.DEFAULT
        }
    }
}
