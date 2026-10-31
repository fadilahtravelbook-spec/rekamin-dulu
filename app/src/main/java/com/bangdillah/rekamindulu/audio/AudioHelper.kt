package com.bangdillah.rekamindulu.audio

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log

object AudioHelper {

    private const val TAG = "AudioHelper"
    const val DEFAULT_SAMPLE_RATE = 44100
    const val HIGH_RES_SAMPLE_RATE = 48000
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    fun isAudioInternalSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    fun isMicrophoneAvailable(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
    }

    fun getOptimalSampleRate(context: Context): Int {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return DEFAULT_SAMPLE_RATE
        val rateStr = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        return rateStr?.toIntOrNull() ?: DEFAULT_SAMPLE_RATE
    }

    /**
     * Membuat AudioRecord yang aman dengan fallback bertingkat (Stereo -> Mono).
     * Mencegah underrun dengan mengalikan ukuran buffer minimal x2.
     */
    fun createAudioRecord(
        audioSource: Int = MediaRecorder.AudioSource.MIC,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        preferStereo: Boolean = false
    ): AudioRecord? {
        val channelConfigs = if (preferStereo) {
            intArrayOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO)
        } else {
            intArrayOf(AudioFormat.CHANNEL_IN_MONO, AudioFormat.CHANNEL_IN_STEREO)
        }

        for (channelConfig in channelConfigs) {
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, AUDIO_FORMAT)
            if (minBufferSize == AudioRecord.ERROR_BAD_VALUE || minBufferSize == AudioRecord.ERROR) {
                continue
            }

            val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)

            try {
                val record = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    AudioRecord.Builder()
                        .setAudioSource(audioSource)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AUDIO_FORMAT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(channelConfig)
                                .build()
                        )
                        .setBufferSizeInBytes(bufferSize)
                        .build()
                } else {
                    @Suppress("DEPRECATION")
                    AudioRecord(
                        audioSource,
                        sampleRate,
                        channelConfig,
                        AUDIO_FORMAT,
                        bufferSize
                    )
                }

                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    Log.i(TAG, "AudioRecord initialized successfully (sampleRate=$sampleRate, channels=$channelConfig)")
                    return record
                } else {
                    Log.w(TAG, "AudioRecord created but in STATE_UNINITIALIZED, releasing...")
                    try { record.release() } catch (ignored: Exception) {}
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission RECORD_AUDIO denied for AudioRecord", e)
                return null
            } catch (e: Exception) {
                Log.w(TAG, "Failed creating AudioRecord for channelConfig $channelConfig: ${e.message}")
            }
        }

        Log.e(TAG, "Unable to create an initialized AudioRecord for source $audioSource")
        return null
    }
}

