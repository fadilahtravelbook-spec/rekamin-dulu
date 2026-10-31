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

    /**
     * Memeriksa apakah perangkat mendukung hardware raw unprocessed audio source (Android 7.0+).
     */
    fun isUnprocessedAudioSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val prop = audioManager.getProperty("android.media.property.SUPPORT_AUDIO_SOURCE_UNPROCESSED")
        return prop?.toBoolean() ?: true
    }

    /**
     * Mengambil sample rate optimal yang dijamin valid untuk AAC encoder di MediaRecorder.
     * Mencegah crash jika hardware mengembalikan DAC rate 96kHz / 192kHz yang ditolak oleh AAC encoder standar.
     */
    fun getOptimalSampleRate(context: Context): Int {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return DEFAULT_SAMPLE_RATE
        val rateStr = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        val rawRate = rateStr?.toIntOrNull() ?: DEFAULT_SAMPLE_RATE
        return when {
            rawRate >= 48000 -> HIGH_RES_SAMPLE_RATE
            rawRate in 44100 until 48000 -> DEFAULT_SAMPLE_RATE
            rawRate > 0 -> 44100
            else -> DEFAULT_SAMPLE_RATE
        }
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

    /**
     * Membuat AudioRecord untuk AudioPlaybackCapture (Internal Game Audio) pada Android 10+ (API 29).
     * Merekam suara internal murni dari game/aplikasi tanpa tercampur suara ruangan atau peredam bising mic.
     */
    fun createAudioPlaybackCaptureRecord(
        mediaProjection: android.media.projection.MediaProjection,
        sampleRate: Int = HIGH_RES_SAMPLE_RATE,
        preferStereo: Boolean = true
    ): AudioRecord? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "AudioPlaybackCapture hanya didukung di Android 10+ (API 29)")
            return null
        }

        return try {
            val playbackConfig = android.media.AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
                .build()

            val channelConfig = if (preferStereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, AUDIO_FORMAT)
            val bufferSize = if (minBufferSize > 0) (minBufferSize * 2).coerceAtLeast(4096) else 8192

            val record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(playbackConfig)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (record.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "AudioPlaybackCapture AudioRecord berhasil diinisialisasi ($sampleRate Hz, stereo=$preferStereo)")
                record
            } else {
                Log.w(TAG, "AudioPlaybackCapture AudioRecord uninitialized, releasing")
                try { record.release() } catch (ignored: Exception) {}
                null
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Izin RECORD_AUDIO atau MediaProjection ditolak untuk AudioPlaybackCapture", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Gagal membuat AudioPlaybackCapture AudioRecord: ${e.message}", e)
            null
        }
    }
}

