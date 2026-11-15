package com.bangdillah.rekamindulu.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import com.bangdillah.rekamindulu.audio.AudioHelper
import com.bangdillah.rekamindulu.audio.MicrophoneSelector
import com.bangdillah.rekamindulu.utils.AppPreferences
import com.bangdillah.rekamindulu.utils.HardwareEncoderHelper
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * MediaCodecScreenRecorder - Engine perekam layar modern berbasis MediaCodec + MediaMuxer.
 *
 * Mendukung penuh pada Android 10+ (API 29+):
 * - INTERNAL: Audio internal game/media via AudioPlaybackCapture tanpa suara luar/mic.
 * - MIC: Audio mikrofon dengan dukungan live mute/unmute tanpa menghentikan rekaman.
 * - BOTH: Mixing PCM 16-bit real-time (AudioPlaybackCapture + Mikrofon) dengan saturation clamp.
 * - MUTE: Video murni tanpa track audio.
 */
class MediaCodecScreenRecorder(
    private val context: Context,
    private val outputPath: String,
    private val mediaProjection: MediaProjection?,
    private val prefs: AppPreferences
) {
    companion object {
        private const val TAG = "MediaCodecScreenRecorder"
        private const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val TIMEOUT_US = 10_000L

        /**
         * Menjumlahkan 2 sampel PCM 16-bit dengan saturation clamping (-32768..32767)
         * agar tidak terjadi integer wrap-around distortion/clipping.
         */
        fun mixPcm16Sample(playbackSample: Int, micSample: Int): Short {
            val mixed = playbackSample + micSample
            return mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        /**
         * Mencampur buffer PCM 16-bit dari AudioPlaybackCapture dan Mikrofon menjadi satu stream.
         */
        fun mixPcm16Frames(
            playback: ShortArray,
            playbackSamplesRead: Int,
            playbackChannels: Int,
            mic: ShortArray,
            micSamplesRead: Int,
            micChannels: Int,
            micMuted: Boolean
        ): ShortArray {
            val pbFrames = if (playbackChannels > 0) playbackSamplesRead / playbackChannels else 0
            val micFrames = if (micChannels > 0) micSamplesRead / micChannels else 0
            val totalFrames = maxOf(pbFrames, micFrames)
            if (totalFrames <= 0) return ShortArray(0)

            val outChannels = playbackChannels.coerceAtLeast(micChannels).coerceIn(1, 2)
            val output = ShortArray(totalFrames * outChannels)

            for (frame in 0 until totalFrames) {
                val micMono = if (!micMuted && frame < micFrames) {
                    if (micChannels == 2) {
                        val idx = frame * 2
                        (mic[idx].toInt() + mic[idx + 1].toInt()) / 2
                    } else {
                        mic[frame].toInt()
                    }
                } else {
                    0
                }

                if (outChannels == 2) {
                    val pbL = if (frame < pbFrames) {
                        if (playbackChannels == 2) playback[frame * 2].toInt() else playback[frame].toInt()
                    } else 0
                    val pbR = if (frame < pbFrames) {
                        if (playbackChannels == 2) playback[frame * 2 + 1].toInt() else playback[frame].toInt()
                    } else 0

                    output[frame * 2] = mixPcm16Sample(pbL, micMono)
                    output[frame * 2 + 1] = mixPcm16Sample(pbR, micMono)
                } else {
                    val pb = if (frame < pbFrames) playback[frame].toInt() else 0
                    output[frame] = mixPcm16Sample(pb, micMono)
                }
            }
            return output
        }

        /**
         * Menghitung PTS audio berikutnya agar tetap sinkron dengan jam dinding video ([wallPtsUs]).
         * Pada kondisi normal/kontinu, PTS mengikuti jumlah sampel sejak epoch terakhir agar bebas jitter;
         * namun bila terjadi jeda/hening (> 80 ms tertinggal dari wallPtsUs), epoch disinkronkan ulang
         * ke [wallPtsUs] agar tidak terjadi A/V desync kumulatif di sisa rekaman.
         */
        fun computeAlignedAudioPtsUs(
            audioEpochBaseUs: Long,
            samplesSinceEpoch: Long,
            sampleRate: Int,
            wallPtsUs: Long,
            lastQueuedPtsUs: Long
        ): Pair<Long, Boolean> {
            val safeRate = sampleRate.coerceAtLeast(8_000)
            val contiguousPtsUs = audioEpochBaseUs + (samplesSinceEpoch * 1_000_000L) / safeRate
            val driftUs = wallPtsUs - contiguousPtsUs
            val resynced = driftUs > 80_000L
            val rawPtsUs = if (resynced) wallPtsUs else contiguousPtsUs
            val monotonicPtsUs = if (lastQueuedPtsUs >= 0L && rawPtsUs <= lastQueuedPtsUs) {
                lastQueuedPtsUs + 1_000L
            } else {
                rawPtsUs.coerceAtLeast(0L)
            }
            return Pair(monotonicPtsUs, resynced)
        }
    }

    /**
     * Buffer sirkular thread-safe untuk PCM 16-bit agar pembacaan AudioPlaybackCapture
     * tidak pernah memblokir thread penangkap Mikrofon saat audio internal game hening.
     */
    internal class PcmRingBuffer(private val capacityShorts: Int) {
        private val buffer = ShortArray(capacityShorts.coerceAtLeast(1024))
        private var readPos = 0
        private var writePos = 0
        private var size = 0

        @Synchronized
        fun write(src: ShortArray, count: Int) {
            if (count <= 0) return
            val cap = buffer.size
            val startOffset = if (count >= cap) {
                clear()
                count - cap
            } else {
                val overflow = (size + count) - cap
                if (overflow > 0) {
                    readPos = (readPos + overflow) % cap
                    size -= overflow
                }
                0
            }
            val toWrite = count - startOffset
            for (i in 0 until toWrite) {
                buffer[writePos] = src[startOffset + i]
                writePos = (writePos + 1) % cap
            }
            size += toWrite
        }

        @Synchronized
        fun readUpTo(dst: ShortArray, maxCount: Int): Int {
            val toRead = minOf(maxCount.coerceAtLeast(0), minOf(size, dst.size))
            if (toRead <= 0) return 0
            val cap = buffer.size
            for (i in 0 until toRead) {
                dst[i] = buffer[readPos]
                readPos = (readPos + 1) % cap
            }
            size -= toRead
            return toRead
        }

        @Synchronized
        fun available(): Int = size

        @Synchronized
        fun clear() {
            readPos = 0
            writePos = 0
            size = 0
        }
    }

    var actualWidth: Int = 720
        private set
    var actualHeight: Int = 1280
        private set
    var actualDensityDpi: Int = 320
        private set
    var actualFps: Int = 60
        private set
    var actualBitrateBps: Int = 12_000_000
        private set
    var inputSurface: Surface? = null
        private set
    var effectiveAudioMode: String = "MUTE"
        private set

    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null

    private var micAudioRecord: AudioRecord? = null
    private var playbackAudioRecord: AudioRecord? = null
    private var micNoiseSuppressor: NoiseSuppressor? = null
    private var micEchoCanceler: AcousticEchoCanceler? = null
    private var micChannelCount: Int = 1
    private var playbackChannelCount: Int = 2
    private var audioChannelCount: Int = 1
    private var audioSampleRate: Int = AudioHelper.DEFAULT_SAMPLE_RATE

    private var videoTrackIndex: Int = -1
    private var audioTrackIndex: Int = -1
    private var expectAudioTrack: Boolean = false
    private var muxerStarted: Boolean = false
    private var videoSamplesWritten: Int = 0
    private var muxerWriteError: Boolean = false
    private val muxerLock = ReentrantLock()

    private val pendingVideoSamples = mutableListOf<Pair<ByteBuffer, MediaCodec.BufferInfo>>()
    private val pendingAudioSamples = mutableListOf<Pair<ByteBuffer, MediaCodec.BufferInfo>>()

    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val isMicMuted = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val isReconfiguringVideo = AtomicBoolean(false)
    private val reconfigureLock = Any()
    private var activeVideoMime: String = MediaFormat.MIMETYPE_VIDEO_AVC

    @Volatile
    private var recordingStartUs: Long = 0L
    @Volatile
    private var pauseStartUs: Long = 0L
    @Volatile
    private var totalPausedUs: Long = 0L
    @Volatile
    private var lastVideoPtsUs: Long = -1L
    @Volatile
    private var lastAudioPtsUs: Long = -1L

    private var videoDrainThread: Thread? = null
    private var audioLoopThread: Thread? = null
    private var playbackCaptureThread: Thread? = null
    private var audioDrainThread: Thread? = null

    /**
     * Mengonfigurasi encoder video + encoder audio + MediaMuxer beserta fallback multi-tier.
     * Harus dipanggil sebelum [start].
     */
    fun prepare(metrics: DisplayMetrics): Surface {
        val profile = RecordingEngineOptimizer.getOptimizedProfile(metrics, prefs)
        val isPortrait = profile.height >= profile.width
        val dims720 = RecordingEngineOptimizer.calculateDimensions(metrics, "720p", prefs.orientation)
        val dims480 = RecordingEngineOptimizer.calculateDimensions(metrics, "480p", prefs.orientation)

        val candidates = listOf(
            Triple(profile.width, profile.height, profile.targetBitrateBps),
            Triple(dims720.width, dims720.height, minOf(profile.targetBitrateBps, 14_000_000)),
            Triple(dims480.width, dims480.height, minOf(profile.targetBitrateBps, 6_000_000))
        )

        val preferredMime = if (prefs.videoEncoder == "H265" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            MediaFormat.MIMETYPE_VIDEO_HEVC
        } else {
            MediaFormat.MIMETYPE_VIDEO_AVC
        }
        val mimesToTry = if (preferredMime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
            listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)
        } else {
            listOf(MediaFormat.MIMETYPE_VIDEO_AVC)
        }

        actualDensityDpi = profile.densityDpi
        actualFps = profile.targetFps

        var configured = false
        outer@ for (mime in mimesToTry) {
            for ((w, h, bps) in candidates) {
                if (tryConfigureVideoEncoder(mime, w, h, bps, actualFps)) {
                    activeVideoMime = mime
                    actualWidth = w
                    actualHeight = h
                    actualBitrateBps = bps
                    configured = true
                    break@outer
                }
            }
        }

        if (!configured || inputSurface == null) {
            releaseAll()
            throw IllegalStateException("MediaCodec video encoder gagal dikonfigurasi")
        }

        if (isPortrait && actualHeight < actualWidth) {
            Log.d(TAG, "Portrait preference noted (${actualWidth}x${actualHeight})")
        }

        setupAudioPipeline()

        mediaMuxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        return inputSurface!!
    }

    private fun tryConfigureVideoEncoder(
        mimeType: String,
        width: Int,
        height: Int,
        bitrateBps: Int,
        fps: Int
    ): Boolean {
        try {
            inputSurface?.release()
        } catch (ignored: Exception) {}
        inputSurface = null
        try {
            videoEncoder?.release()
        } catch (ignored: Exception) {}
        videoEncoder = null

        return try {
            val useHardwareAccel = prefs.hardwareAccelerationEnabled
            val hwCap = if (useHardwareAccel) {
                HardwareEncoderHelper.findBestHardwareEncoder(mimeType)
            } else {
                null
            }

            val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps.coerceIn(1_000_000, 60_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps.coerceIn(15, 120))
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
                if (prefs.fpsStabilizerEnabled && (hwCap == null || hwCap.isCbrSupported)) {
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    )
                }
                if (useHardwareAccel && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mimeType == MediaFormat.MIMETYPE_VIDEO_AVC) {
                    setInteger(
                        MediaFormat.KEY_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                    )
                    setInteger(
                        MediaFormat.KEY_LEVEL,
                        MediaCodecInfo.CodecProfileLevel.AVCLevel42
                    )
                }
            }

            val codec = if (useHardwareAccel && hwCap != null && hwCap.isHardware) {
                try {
                    MediaCodec.createByCodecName(hwCap.codecName)
                } catch (eName: Exception) {
                    Log.w(TAG, "createByCodecName(${hwCap.codecName}) gagal, fallback createEncoderByType: ${eName.message}")
                    MediaCodec.createEncoderByType(mimeType)
                }
            } else {
                MediaCodec.createEncoderByType(mimeType)
            }

            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (eHigh: Exception) {
                // Retry tanpa optional high profile / CBR keys jika chipset menolaknya
                val basicFormat = MediaFormat.createVideoFormat(mimeType, width, height).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps.coerceIn(1_000_000, 60_000_000))
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps.coerceIn(15, 120))
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                codec.reset()
                codec.configure(basicFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            val surface = codec.createInputSurface()
            codec.start()
            videoEncoder = codec
            inputSurface = surface
            Log.i(
                TAG,
                "Video MediaCodec siap (${codec.name}, hwAccel=$useHardwareAccel, cbr=${prefs.fpsStabilizerEnabled}): $mimeType ${width}x${height} @ ${fps}FPS ($bitrateBps bps)"
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "Konfigurasi video $mimeType ${width}x${height} gagal: ${e.message}")
            try { inputSurface?.release() } catch (ignored: Exception) {}
            try { videoEncoder?.release() } catch (ignored: Exception) {}
            inputSurface = null
            videoEncoder = null
            false
        }
    }

    private fun setupAudioPipeline() {
        val requestedMode = prefs.audioSource
        if (requestedMode == "MUTE") {
            effectiveAudioMode = "MUTE"
            expectAudioTrack = false
            return
        }

        val hasMicPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMicPermission) {
            Log.w(TAG, "RECORD_AUDIO tidak diberikan; perekaman berjalan tanpa track audio (MUTE)")
            effectiveAudioMode = "MUTE"
            expectAudioTrack = false
            return
        }

        audioSampleRate = try {
            AudioHelper.getOptimalSampleRate(context)
        } catch (e: Exception) {
            AudioHelper.DEFAULT_SAMPLE_RATE
        }

        val wantsPlayback = (requestedMode == "INTERNAL" || requestedMode == "BOTH") &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            mediaProjection != null
        val wantsMic = requestedMode == "MIC" || requestedMode == "BOTH" ||
            (requestedMode == "INTERNAL" && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)

        var pbRecord: AudioRecord? = null
        if (wantsPlayback && mediaProjection != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            pbRecord = AudioHelper.createAudioPlaybackCaptureRecord(
                mediaProjection = mediaProjection,
                sampleRate = audioSampleRate,
                preferStereo = true
            ) ?: AudioHelper.createAudioPlaybackCaptureRecord(
                mediaProjection = mediaProjection,
                sampleRate = AudioHelper.DEFAULT_SAMPLE_RATE,
                preferStereo = false
            )?.also {
                audioSampleRate = AudioHelper.DEFAULT_SAMPLE_RATE
            }
        }

        var mRecord: AudioRecord? = null
        if (wantsMic || (requestedMode == "BOTH" && pbRecord == null)) {
            val primarySource = MicrophoneSelector.getAudioSource(prefs.micType)
            val fallbackSources = MicrophoneSelector.getFallbackAudioSources(primarySource)
            for (src in fallbackSources) {
                mRecord = AudioHelper.createAudioRecord(
                    audioSource = src,
                    sampleRate = audioSampleRate,
                    preferStereo = false
                )
                if (mRecord != null) break
            }
        }

        playbackAudioRecord = pbRecord
        micAudioRecord = mRecord
        playbackChannelCount = pbRecord?.channelCount ?: 0
        micChannelCount = mRecord?.channelCount ?: 0

        effectiveAudioMode = when {
            pbRecord != null && mRecord != null -> "BOTH"
            pbRecord != null -> "INTERNAL"
            mRecord != null -> "MIC"
            else -> "MUTE"
        }

        if (mRecord != null && prefs.internalAudioStabilizer) {
            attachMicStabilizerEffects(mRecord, enableAec = (effectiveAudioMode == "BOTH"))
        }

        if (effectiveAudioMode == "MUTE") {
            expectAudioTrack = false
            return
        }

        audioChannelCount = maxOf(playbackChannelCount, micChannelCount).coerceIn(1, 2)
        val audioBitrate = if (audioChannelCount == 2) 192_000 else 128_000

        try {
            val aFormat = MediaFormat.createAudioFormat(AUDIO_MIME, audioSampleRate, audioChannelCount).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768)
            }
            val aCodec = MediaCodec.createEncoderByType(AUDIO_MIME)
            aCodec.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            aCodec.start()
            audioEncoder = aCodec
            expectAudioTrack = true
            Log.i(
                TAG,
                "Audio pipeline siap: mode=$effectiveAudioMode, rate=$audioSampleRate Hz, ch=$audioChannelCount"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Gagal inisialisasi AAC AudioEncoder, fallback ke video-only", e)
            try { pbRecord?.release() } catch (ignored: Exception) {}
            try { mRecord?.release() } catch (ignored: Exception) {}
            playbackAudioRecord = null
            micAudioRecord = null
            audioEncoder = null
            effectiveAudioMode = "MUTE"
            expectAudioTrack = false
        }
    }

    fun start() {
        if (isRunning.getAndSet(true)) return
        stopRequested.set(false)
        isPaused.set(false)
        recordingStartUs = System.nanoTime() / 1000L
        pauseStartUs = 0L
        totalPausedUs = 0L
        lastVideoPtsUs = -1L
        lastAudioPtsUs = -1L

        if (expectAudioTrack) {
            try {
                playbackAudioRecord?.startRecording()
            } catch (e: Exception) {
                Log.w(TAG, "playbackAudioRecord.startRecording gagal: ${e.message}")
            }
            try {
                micAudioRecord?.startRecording()
            } catch (e: Exception) {
                Log.w(TAG, "micAudioRecord.startRecording gagal: ${e.message}")
            }

            audioLoopThread = Thread({ captureAndFeedAudioLoop() }, "RekaminAudioCapture").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            audioDrainThread = Thread({ drainAudioEncoderLoop() }, "RekaminAudioDrain").apply {
                start()
            }
        }

        videoDrainThread = Thread({ drainVideoEncoderLoop() }, "RekaminVideoDrain").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun setMicMuted(muted: Boolean) {
        isMicMuted.set(muted)
        Log.i(TAG, "Live mic mute status: $muted (effectiveMode=$effectiveAudioMode)")
    }

    fun isMicMuted(): Boolean = isMicMuted.get()

    fun pause() {
        if (!isRunning.get() || isPaused.getAndSet(true)) return
        pauseStartUs = System.nanoTime() / 1000L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            try {
                val bundle = Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_SUSPEND, 1)
                }
                videoEncoder?.setParameters(bundle)
            } catch (ignored: Exception) {}
        }
    }

    fun resume() {
        if (!isRunning.get() || !isPaused.getAndSet(false)) return
        if (pauseStartUs > 0L) {
            totalPausedUs += (System.nanoTime() / 1000L - pauseStartUs).coerceAtLeast(0L)
            pauseStartUs = 0L
        }
        try {
            val bundle = Bundle().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    putInt(MediaCodec.PARAMETER_KEY_SUSPEND, 0)
                }
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            videoEncoder?.setParameters(bundle)
        } catch (ignored: Exception) {}
    }

    /**
     * Mengembalikan true hanya bila MediaMuxer belum mulai menulis track video sehingga
     * encoder video dapat dikonfigurasi ulang ke resolusi baru tanpa merusak file MP4.
     */
    fun canReconfigureVideoSize(): Boolean {
        if (!isRunning.get() || stopRequested.get()) return false
        return muxerLock.withLock {
            !muxerStarted && videoSamplesWritten == 0
        }
    }

    /**
     * Mencoba mengonfigurasi ulang video encoder sebelum MediaMuxer dikunci oleh track video.
     * Mengembalikan input Surface baru jika berhasil, atau null jika muxer sudah aktif maupun
     * hardware encoder menolak dimensi baru.
     */
    fun reconfigureVideoSize(
        newWidth: Int,
        newHeight: Int,
        newDensityDpi: Int,
        newBitrateBps: Int
    ): Surface? {
        synchronized(reconfigureLock) {
            if (!canReconfigureVideoSize()) return null
            if (newWidth == actualWidth && newHeight == actualHeight) {
                actualDensityDpi = newDensityDpi
                return inputSurface
            }

            isReconfiguringVideo.set(true)
            try {
                try {
                    videoEncoder?.signalEndOfInputStream()
                } catch (ignored: Exception) {}
                try {
                    videoDrainThread?.join(1000L)
                } catch (ignored: InterruptedException) {}

                if (videoDrainThread?.isAlive == true) {
                    try { videoEncoder?.stop() } catch (ignored: Exception) {}
                    try { videoDrainThread?.join(500L) } catch (ignored: InterruptedException) {}
                }
                videoDrainThread = null

                try { inputSurface?.release() } catch (ignored: Exception) {}
                inputSurface = null
                try { videoEncoder?.stop() } catch (ignored: Exception) {}
                try { videoEncoder?.release() } catch (ignored: Exception) {}
                videoEncoder = null

                val canProceed = muxerLock.withLock {
                    if (muxerStarted || videoSamplesWritten > 0 || stopRequested.get()) {
                        false
                    } else {
                        pendingVideoSamples.clear()
                        videoTrackIndex = -1
                        lastVideoPtsUs = -1L
                        true
                    }
                }
                if (!canProceed) {
                    return null
                }

                val mimesToTry = if (activeVideoMime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                    listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)
                } else {
                    listOf(MediaFormat.MIMETYPE_VIDEO_AVC)
                }

                var configuredMime: String? = null
                for (mime in mimesToTry) {
                    if (tryConfigureVideoEncoder(mime, newWidth, newHeight, newBitrateBps, actualFps)) {
                        configuredMime = mime
                        break
                    }
                }

                if (configuredMime == null || inputSurface == null) {
                    Log.w(TAG, "Encoder gagal mengonfigurasi dimensi baru ${newWidth}x${newHeight}")
                    return null
                }

                activeVideoMime = configuredMime
                actualWidth = newWidth
                actualHeight = newHeight
                actualDensityDpi = newDensityDpi
                actualBitrateBps = newBitrateBps

                videoDrainThread = Thread({ drainVideoEncoderLoop() }, "RekaminVideoDrain").apply {
                    priority = Thread.MAX_PRIORITY
                    start()
                }
                Log.i(TAG, "Video encoder berhasil dikonfigurasi ulang ke ${newWidth}x${newHeight} ($newDensityDpi dpi)")
                return inputSurface
            } finally {
                isReconfiguringVideo.set(false)
            }
        }
    }

    /**
     * Menghentikan perekaman secara sinkron, mengirim EOS ke video & audio encoder,
     * menunggu muxer selesai menulis moov atom, lalu merilis semua resource.
     */
    fun stop(): Boolean {
        if (!isRunning.get() && videoEncoder == null) return false
        if (isPaused.getAndSet(false) && pauseStartUs > 0L) {
            totalPausedUs += (System.nanoTime() / 1000L - pauseStartUs).coerceAtLeast(0L)
            pauseStartUs = 0L
        }
        stopRequested.set(true)
        isRunning.set(false)

        // Hentikan AudioRecord lebih dulu agar read() yang sedang blocking segera terbuka (unblock)
        // dan thread capture bisa segera keluar serta mengirim EOS ke audio encoder.
        try { playbackAudioRecord?.stop() } catch (ignored: Exception) {}
        try { micAudioRecord?.stop() } catch (ignored: Exception) {}

        try {
            videoEncoder?.signalEndOfInputStream()
        } catch (e: Exception) {
            Log.w(TAG, "signalEndOfInputStream notice: ${e.message}")
        }

        try { playbackCaptureThread?.join(1500) } catch (ignored: InterruptedException) {}
        playbackCaptureThread = null
        try { audioLoopThread?.join(2500) } catch (ignored: InterruptedException) {}
        try { audioDrainThread?.join(2500) } catch (ignored: InterruptedException) {}
        try { videoDrainThread?.join(3500) } catch (ignored: InterruptedException) {}

        val cleanlyWritten = muxerLock.withLock {
            val stopSucceeded = if (muxerStarted) {
                try {
                    mediaMuxer?.stop()
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "mediaMuxer.stop notice: ${e.message}")
                    false
                }
            } else {
                false
            }
            muxerStarted && !muxerWriteError && videoSamplesWritten > 0 && stopSucceeded
        }

        releaseAll()
        return cleanlyWritten
    }

    private fun attachMicStabilizerEffects(record: AudioRecord, enableAec: Boolean) {
        val sessionId = record.audioSessionId
        if (sessionId == 0) return
        try {
            if (NoiseSuppressor.isAvailable()) {
                micNoiseSuppressor = NoiseSuppressor.create(sessionId)?.apply {
                    enabled = true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "NoiseSuppressor tidak tersedia: ${e.message}")
        }
        if (enableAec) {
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    micEchoCanceler = AcousticEchoCanceler.create(sessionId)?.apply {
                        enabled = true
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "AcousticEchoCanceler tidak tersedia: ${e.message}")
            }
        }
    }

    private fun currentNormalizedPtsUs(): Long {
        val nowUs = System.nanoTime() / 1000L
        return (nowUs - recordingStartUs - totalPausedUs).coerceAtLeast(0L)
    }

    private fun drainVideoEncoderLoop() {
        if (prefs.gamingTurboBoost) {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            } catch (_: Exception) {}
        }
        val encoder = videoEncoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        var eosSeen = false
        var idleAfterStop = 0

        while (!eosSeen) {
            val outIndex = try {
                encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            } catch (e: Exception) {
                Log.w(TAG, "video dequeueOutputBuffer error: ${e.message}")
                break
            }

            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    idleAfterStop = 0
                    if (!isReconfiguringVideo.get()) {
                        onOutputFormatChanged(isVideo = true, format = encoder.outputFormat)
                    }
                }
                outIndex >= 0 -> {
                    idleAfterStop = 0
                    val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val encodedData = try { encoder.getOutputBuffer(outIndex) } catch (e: Exception) { null }

                    if (encodedData != null && bufferInfo.size > 0 && !isConfig && !isReconfiguringVideo.get()) {
                        if (!isPaused.get() || isEos) {
                            val normalizedPts = if (bufferInfo.presentationTimeUs > recordingStartUs) {
                                (bufferInfo.presentationTimeUs - recordingStartUs - totalPausedUs).coerceAtLeast(0L)
                            } else {
                                currentNormalizedPtsUs()
                            }
                            val monotonicPts = if (lastVideoPtsUs >= 0L && normalizedPts <= lastVideoPtsUs) {
                                lastVideoPtsUs + 1_000L
                            } else {
                                normalizedPts
                            }
                            lastVideoPtsUs = monotonicPts
                            bufferInfo.presentationTimeUs = monotonicPts

                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            writeOrBufferSample(isVideo = true, encodedData, bufferInfo)
                        }
                    }

                    try {
                        encoder.releaseOutputBuffer(outIndex, false)
                    } catch (ignored: Exception) {}

                    if (isEos) {
                        eosSeen = true
                    }
                }
                else -> {
                    if (stopRequested.get()) {
                        idleAfterStop++
                        if (idleAfterStop >= 40) {
                            Log.w(TAG, "Video drain timeout after stop; exiting cleanly")
                            break
                        }
                        try { Thread.sleep(5) } catch (ignored: InterruptedException) { break }
                    }
                }
            }
        }
    }

    private fun captureAndFeedAudioLoop() {
        if (prefs.gamingTurboBoost) {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Exception) {}
        }
        val encoder = audioEncoder ?: return
        val pb = playbackAudioRecord
        val mic = micAudioRecord

        val pbFramesPerRead = 1024
        val pbChannels = pb?.channelCount ?: 0
        val micChannels = mic?.channelCount ?: 0
        val pbShortBuf = ShortArray(pbFramesPerRead * pbChannels.coerceAtLeast(1))
        val micShortBuf = ShortArray(pbFramesPerRead * micChannels.coerceAtLeast(1))

        var audioEpochBaseUs = 0L
        var samplesSinceEpoch = 0L
        var lastQueuedAudioPtsUs = -1L

        // Feed 1 frame silence awal agar AAC encoder segera mengeluarkan INFO_OUTPUT_FORMAT_CHANGED
        // sehingga MediaMuxer.start() tidak pernah menunggu saat sumber audio diam.
        try {
            val initInIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (initInIdx >= 0) {
                val initBuf = encoder.getInputBuffer(initInIdx)
                if (initBuf != null) {
                    initBuf.clear()
                    val initShorts = minOf(pbFramesPerRead * audioChannelCount.coerceAtLeast(1), initBuf.remaining() / 2)
                    for (i in 0 until (initShorts * 2)) {
                        initBuf.put(0)
                    }
                    encoder.queueInputBuffer(initInIdx, 0, initShorts * 2, 0L, 0)
                    samplesSinceEpoch += (initShorts / audioChannelCount.coerceAtLeast(1))
                    lastQueuedAudioPtsUs = 0L
                }
            }
        } catch (ignored: Exception) {}

        var codecAborted = false
        var activeMic = mic
        var activeMicChannels = micChannels
        var micRecoveredOnce = false
        var micDead = (activeMic == null)
        var playbackDead = (pb == null)
        var pbConsecutiveErrors = 0
        var micConsecutiveErrors = 0

        val isDualMode = (effectiveAudioMode == "BOTH" && pb != null && activeMic != null)
        val playbackRingBuffer = if (isDualMode) {
            PcmRingBuffer(audioSampleRate * pbChannels.coerceAtLeast(1))
        } else {
            null
        }

        if (isDualMode && pb != null && playbackRingBuffer != null) {
            playbackCaptureThread = Thread({
                if (prefs.gamingTurboBoost) {
                    try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Exception) {}
                }
                val pbThreadBuf = ShortArray(pbFramesPerRead * pbChannels.coerceAtLeast(1))
                while (!stopRequested.get() && !playbackDead) {
                    if (isPaused.get()) {
                        playbackRingBuffer.clear()
                        try {
                            if (pb.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                                pb.read(pbThreadBuf, 0, pbThreadBuf.size)
                            }
                        } catch (_: Exception) {}
                        try { Thread.sleep(15) } catch (_: InterruptedException) { break }
                        continue
                    }
                    val readCount = if (pb.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        try { pb.read(pbThreadBuf, 0, pbThreadBuf.size) } catch (_: Exception) { AudioRecord.ERROR_INVALID_OPERATION }
                    } else {
                        try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                        0
                    }
                    if (stopRequested.get()) break
                    when {
                        readCount > 0 -> {
                            pbConsecutiveErrors = 0
                            playbackRingBuffer.write(pbThreadBuf, readCount)
                        }
                        readCount == 0 -> {
                            try { Thread.sleep(4) } catch (_: InterruptedException) { break }
                        }
                        else -> {
                            pbConsecutiveErrors++
                            if (readCount == AudioRecord.ERROR_DEAD_OBJECT || pbConsecutiveErrors >= 5) {
                                Log.w(TAG, "AudioPlaybackCapture terhenti (kode=$readCount); lanjutkan dengan mikrofon saja")
                                playbackDead = true
                                break
                            }
                            try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                        }
                    }
                }
            }, "RekaminPlaybackCapture").apply { start() }
        }

        while (!stopRequested.get() && !codecAborted) {
            if (isPaused.get()) {
                playbackRingBuffer?.clear()
                val drainedPb = if (!isDualMode && pb != null && !playbackDead && pb.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    try { pb.read(pbShortBuf, 0, pbShortBuf.size) } catch (_: Exception) { 0 }
                } else 0
                val drainedMic = if (activeMic != null && !micDead && activeMic.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    try { activeMic.read(micShortBuf, 0, micShortBuf.size) } catch (_: Exception) { 0 }
                } else 0
                if (stopRequested.get()) break
                if (drainedPb <= 0 && drainedMic <= 0) {
                    try { Thread.sleep(15) } catch (_: InterruptedException) {}
                }
                continue
            }

            var micRead = 0
            if (activeMic != null && !micDead) {
                val rawMicRead = if (activeMic.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    try {
                        activeMic.read(micShortBuf, 0, (pbFramesPerRead * activeMicChannels.coerceAtLeast(1)).coerceIn(1, micShortBuf.size))
                    } catch (_: Exception) {
                        AudioRecord.ERROR_INVALID_OPERATION
                    }
                } else 0
                if (stopRequested.get()) break

                if (rawMicRead > 0) {
                    micConsecutiveErrors = 0
                    micRead = rawMicRead
                } else if (rawMicRead < 0) {
                    micConsecutiveErrors++
                    if (rawMicRead == AudioRecord.ERROR_DEAD_OBJECT || micConsecutiveErrors >= 5) {
                        Log.w(TAG, "Mic AudioRecord error (kode=$rawMicRead), mencoba pemulihan ke mic bawaan")
                        if (!micRecoveredOnce && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            micRecoveredOnce = true
                            try { activeMic.stop() } catch (_: Exception) {}
                            try { activeMic.release() } catch (_: Exception) {}
                            val fallbackMic = AudioHelper.createAudioRecord(
                                audioSource = MediaRecorder.AudioSource.MIC,
                                sampleRate = audioSampleRate,
                                preferStereo = false
                            )
                            if (fallbackMic != null) {
                                try {
                                    fallbackMic.startRecording()
                                    activeMic = fallbackMic
                                    micAudioRecord = fallbackMic
                                    activeMicChannels = fallbackMic.channelCount.coerceAtLeast(1)
                                    micChannelCount = activeMicChannels
                                    micConsecutiveErrors = 0
                                    Log.i(TAG, "Mic dipulihkan ke mikrofon bawaan perangkat")
                                } catch (e: Exception) {
                                    try { fallbackMic.release() } catch (_: Exception) {}
                                    micDead = true
                                }
                            } else {
                                micDead = true
                            }
                        } else {
                            micDead = true
                        }
                    }
                }
            }

            var pbRead = 0
            if (isDualMode && playbackRingBuffer != null) {
                if (!playbackDead) {
                    val micFramesRead = if (activeMicChannels > 0 && micRead > 0) micRead / activeMicChannels else pbFramesPerRead
                    val targetPbSamples = (micFramesRead * pbChannels.coerceAtLeast(1)).coerceIn(1, pbShortBuf.size)
                    pbRead = playbackRingBuffer.readUpTo(pbShortBuf, targetPbSamples)
                }
            } else if (pb != null && !playbackDead) {
                val rawPbRead = if (pb.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    try { pb.read(pbShortBuf, 0, pbShortBuf.size) } catch (_: Exception) { AudioRecord.ERROR_INVALID_OPERATION }
                } else 0
                if (stopRequested.get()) break
                if (rawPbRead > 0) {
                    pbConsecutiveErrors = 0
                    pbRead = rawPbRead
                } else if (rawPbRead < 0) {
                    pbConsecutiveErrors++
                    if (rawPbRead == AudioRecord.ERROR_DEAD_OBJECT || pbConsecutiveErrors >= 5) {
                        Log.w(TAG, "AudioPlaybackCapture error (kode=$rawPbRead); kanal internal dinonaktifkan")
                        playbackDead = true
                    }
                }
            }

            val mixedShorts: ShortArray = when (effectiveAudioMode) {
                "BOTH" -> {
                    if (micDead && playbackDead) {
                        ShortArray(pbFramesPerRead * audioChannelCount.coerceAtLeast(1))
                    } else if (micDead && pbRead > 0) {
                        mixPcm16Frames(
                            playback = pbShortBuf,
                            playbackSamplesRead = pbRead,
                            playbackChannels = pbChannels.coerceAtLeast(1),
                            mic = micShortBuf,
                            micSamplesRead = 0,
                            micChannels = activeMicChannels.coerceAtLeast(1),
                            micMuted = true
                        )
                    } else if (micRead > 0 || pbRead > 0) {
                        mixPcm16Frames(
                            playback = pbShortBuf,
                            playbackSamplesRead = pbRead.coerceAtLeast(0),
                            playbackChannels = pbChannels.coerceAtLeast(1),
                            mic = micShortBuf,
                            micSamplesRead = micRead.coerceAtLeast(0),
                            micChannels = activeMicChannels.coerceAtLeast(1),
                            micMuted = isMicMuted.get() || micDead
                        )
                    } else {
                        ShortArray(0)
                    }
                }
                "INTERNAL" -> {
                    when {
                        pbRead > 0 -> pbShortBuf.copyOf(pbRead)
                        playbackDead -> ShortArray(pbFramesPerRead * audioChannelCount.coerceAtLeast(1))
                        else -> ShortArray(0)
                    }
                }
                "MIC" -> {
                    when {
                        micRead > 0 -> if (isMicMuted.get()) ShortArray(micRead) else micShortBuf.copyOf(micRead)
                        micDead -> ShortArray(pbFramesPerRead * audioChannelCount.coerceAtLeast(1))
                        else -> ShortArray(0)
                    }
                }
                else -> ShortArray(0)
            }

            if (mixedShorts.isEmpty()) {
                try { Thread.sleep(10) } catch (_: InterruptedException) {}
                continue
            }
            if ((effectiveAudioMode == "INTERNAL" && playbackDead) ||
                (effectiveAudioMode == "MIC" && micDead) ||
                (effectiveAudioMode == "BOTH" && micDead && playbackDead)
            ) {
                // Jaga pace encoding silence ~21ms per chunk agar PTS tetap wajar tanpa membanjiri CPU
                try { Thread.sleep(20) } catch (_: InterruptedException) {}
            }

            var offsetShorts = 0
            var tryAgainCount = 0
            while (offsetShorts < mixedShorts.size && !stopRequested.get()) {
                val inIdx = try {
                    encoder.dequeueInputBuffer(TIMEOUT_US)
                } catch (ise: IllegalStateException) {
                    Log.w(TAG, "Audio encoder stopped unexpectedly: ${ise.message}")
                    codecAborted = true
                    break
                } catch (e: Exception) {
                    -1
                }
                if (inIdx < 0) {
                    tryAgainCount++
                    if (tryAgainCount >= 50) {
                        Log.w(TAG, "Audio input buffer busy, dropping stale chunk to avoid spin lock")
                        break
                    }
                    try { Thread.sleep(2) } catch (_: InterruptedException) {}
                    continue
                }
                tryAgainCount = 0

                val inBuf = encoder.getInputBuffer(inIdx) ?: break
                inBuf.clear()
                val maxShortsForBuf = inBuf.remaining() / 2
                val chunkShorts = minOf(mixedShorts.size - offsetShorts, maxShortsForBuf)

                for (i in 0 until chunkShorts) {
                    val s = mixedShorts[offsetShorts + i]
                    inBuf.put((s.toInt() and 0xFF).toByte())
                    inBuf.put(((s.toInt() shr 8) and 0xFF).toByte())
                }

                val framesThisChunk = (chunkShorts / audioChannelCount.coerceAtLeast(1)).toLong()
                val wallPtsUs = currentNormalizedPtsUs()
                val (ptsUs, resynced) = computeAlignedAudioPtsUs(
                    audioEpochBaseUs = audioEpochBaseUs,
                    samplesSinceEpoch = samplesSinceEpoch,
                    sampleRate = audioSampleRate,
                    wallPtsUs = wallPtsUs,
                    lastQueuedPtsUs = lastQueuedAudioPtsUs
                )
                if (resynced) {
                    audioEpochBaseUs = ptsUs
                    samplesSinceEpoch = framesThisChunk
                } else {
                    samplesSinceEpoch += framesThisChunk
                }
                lastQueuedAudioPtsUs = ptsUs

                try {
                    encoder.queueInputBuffer(inIdx, 0, chunkShorts * 2, ptsUs, 0)
                } catch (e: Exception) {
                    Log.w(TAG, "audio queueInputBuffer error: ${e.message}")
                    codecAborted = true
                    break
                }
                offsetShorts += chunkShorts
            }
        }

        // Kirim EOS ke audio encoder
        try {
            val inIdx = encoder.dequeueInputBuffer(50_000L)
            if (inIdx >= 0) {
                val (eosPtsUs, _) = computeAlignedAudioPtsUs(
                    audioEpochBaseUs = audioEpochBaseUs,
                    samplesSinceEpoch = samplesSinceEpoch,
                    sampleRate = audioSampleRate,
                    wallPtsUs = currentNormalizedPtsUs(),
                    lastQueuedPtsUs = lastQueuedAudioPtsUs
                )
                encoder.queueInputBuffer(
                    inIdx,
                    0,
                    0,
                    eosPtsUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "audio EOS signal error: ${e.message}")
        }
    }

    private fun drainAudioEncoderLoop() {
        val encoder = audioEncoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        var eosSeen = false
        var idleAfterStop = 0

        while (!eosSeen) {
            val outIndex = try {
                encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            } catch (e: Exception) {
                Log.w(TAG, "audio dequeueOutputBuffer error: ${e.message}")
                break
            }

            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    idleAfterStop = 0
                    onOutputFormatChanged(isVideo = false, format = encoder.outputFormat)
                }
                outIndex >= 0 -> {
                    idleAfterStop = 0
                    val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val encodedData = try { encoder.getOutputBuffer(outIndex) } catch (e: Exception) { null }

                    if (encodedData != null && bufferInfo.size > 0 && !isConfig) {
                        val monotonicPts = if (lastAudioPtsUs >= 0L && bufferInfo.presentationTimeUs <= lastAudioPtsUs) {
                            lastAudioPtsUs + 1_000L
                        } else {
                            bufferInfo.presentationTimeUs.coerceAtLeast(0L)
                        }
                        lastAudioPtsUs = monotonicPts
                        bufferInfo.presentationTimeUs = monotonicPts

                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        writeOrBufferSample(isVideo = false, encodedData, bufferInfo)
                    }

                    try {
                        encoder.releaseOutputBuffer(outIndex, false)
                    } catch (ignored: Exception) {}

                    if (isEos) {
                        eosSeen = true
                    }
                }
                else -> {
                    if (stopRequested.get()) {
                        idleAfterStop++
                        if (idleAfterStop >= 40) {
                            Log.w(TAG, "Audio drain timeout after stop; exiting cleanly")
                            break
                        }
                        try { Thread.sleep(5) } catch (ignored: InterruptedException) { break }
                    }
                }
            }
        }
    }

    private fun onOutputFormatChanged(isVideo: Boolean, format: MediaFormat) {
        muxerLock.withLock {
            if (isVideo && isReconfiguringVideo.get()) return
            val muxer = mediaMuxer ?: return
            try {
                if (isVideo) {
                    if (videoTrackIndex < 0) {
                        videoTrackIndex = muxer.addTrack(format)
                    }
                } else {
                    if (audioTrackIndex < 0) {
                        audioTrackIndex = muxer.addTrack(format)
                    }
                }

                val videoReady = videoTrackIndex >= 0
                val audioReady = !expectAudioTrack || audioTrackIndex >= 0
                if (!muxerStarted && videoReady && audioReady) {
                    muxer.start()
                    muxerStarted = true

                    for ((buf, info) in pendingVideoSamples) {
                        try {
                            muxer.writeSampleData(videoTrackIndex, buf, info)
                            videoSamplesWritten++
                        } catch (e: Exception) {
                            muxerWriteError = true
                            Log.w(TAG, "flush pending video sample error: ${e.message}")
                        }
                    }
                    pendingVideoSamples.clear()

                    if (audioTrackIndex >= 0) {
                        for ((buf, info) in pendingAudioSamples) {
                            try {
                                muxer.writeSampleData(audioTrackIndex, buf, info)
                            } catch (e: Exception) {
                                muxerWriteError = true
                                Log.w(TAG, "flush pending audio sample error: ${e.message}")
                            }
                        }
                    }
                    pendingAudioSamples.clear()
                    Log.i(TAG, "MediaMuxer started (videoTrack=$videoTrackIndex, audioTrack=$audioTrackIndex)")
                }
            } catch (e: Exception) {
                muxerWriteError = true
                Log.e(TAG, "onOutputFormatChanged muxer error: ${e.message}", e)
            }
        }
    }

    private fun writeOrBufferSample(isVideo: Boolean, data: ByteBuffer, info: MediaCodec.BufferInfo) {
        muxerLock.withLock {
            if (isVideo && isReconfiguringVideo.get()) return
            val muxer = mediaMuxer ?: return
            if (muxerStarted) {
                val track = if (isVideo) videoTrackIndex else audioTrackIndex
                if (track >= 0) {
                    try {
                        muxer.writeSampleData(track, data, info)
                        if (isVideo) {
                            videoSamplesWritten++
                        }
                    } catch (e: Exception) {
                        muxerWriteError = true
                        Log.w(TAG, "writeSampleData error (isVideo=$isVideo): ${e.message}")
                    }
                }
            } else {
                val cloneBuf = ByteBuffer.allocate(info.size)
                cloneBuf.put(data)
                cloneBuf.flip()
                val cloneInfo = MediaCodec.BufferInfo().apply {
                    set(0, info.size, info.presentationTimeUs, info.flags)
                }
                if (isVideo) {
                    if (pendingVideoSamples.size < 180) pendingVideoSamples.add(Pair(cloneBuf, cloneInfo))
                } else {
                    if (pendingAudioSamples.size < 180) pendingAudioSamples.add(Pair(cloneBuf, cloneInfo))
                }
            }
        }
    }

    fun releaseAll() {
        isRunning.set(false)
        stopRequested.set(true)

        try { micNoiseSuppressor?.release() } catch (ignored: Exception) {}
        micNoiseSuppressor = null
        try { micEchoCanceler?.release() } catch (ignored: Exception) {}
        micEchoCanceler = null

        try { playbackAudioRecord?.stop() } catch (ignored: Exception) {}
        try { playbackAudioRecord?.release() } catch (ignored: Exception) {}
        playbackAudioRecord = null

        try { micAudioRecord?.stop() } catch (ignored: Exception) {}
        try { micAudioRecord?.release() } catch (ignored: Exception) {}
        micAudioRecord = null

        try { audioEncoder?.stop() } catch (ignored: Exception) {}
        try { audioEncoder?.release() } catch (ignored: Exception) {}
        audioEncoder = null

        try { videoEncoder?.stop() } catch (ignored: Exception) {}
        try { videoEncoder?.release() } catch (ignored: Exception) {}
        videoEncoder = null

        try { inputSurface?.release() } catch (ignored: Exception) {}
        inputSurface = null

        muxerLock.withLock {
            try { mediaMuxer?.release() } catch (ignored: Exception) {}
            mediaMuxer = null
            muxerStarted = false
            videoSamplesWritten = 0
            muxerWriteError = false
            videoTrackIndex = -1
            audioTrackIndex = -1
            pendingVideoSamples.clear()
            pendingAudioSamples.clear()
        }
    }
}
