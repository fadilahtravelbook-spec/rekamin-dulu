package com.bangdillah.rekamindulu.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log

object AudioHelper {

    private const val TAG = "AudioHelper"
    private const val SAMPLE_RATE = 44100
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    fun isAudioInternalSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    fun createAudioRecord(): AudioRecord? {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid audio buffer size")
            return null
        }
        return try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                minBufferSize
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for AudioRecord", e)
            null
        }
    }
}
