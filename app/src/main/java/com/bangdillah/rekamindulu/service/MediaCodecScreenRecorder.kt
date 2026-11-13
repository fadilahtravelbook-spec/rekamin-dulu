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
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import com.bangdillah.rekamindulu.audio.AudioHelper
import com.bangdillah.rekamindulu.audio.MicrophoneSelector
import com.bangdillah.rekamindulu.utils.AppPreferences
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
    private var micChannelCount: Int = 1
    private var playbackChannelCount: Int = 2
    private var audioChannelCount: Int = 1
    private var audioSampleRate: Int = AudioHelper.DEFAULT_SAMPLE_RATE

    private var videoTrackIndex: Int = -1
    private var audioTrackIndex: Int = -1
    private var expectAudioTrack: Boolean = false
    private var muxerStarted: Boolean = false
    private val muxerLock = ReentrantLock()

    private val pendingVideoSamples = mutableListOf<Pair<ByteBuffer, MediaCodec.BufferInfo>>()
    private val pendingAudioSamples = mutableListOf<Pair<ByteBuffer, MediaCodec.BufferInfo>>()

    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val isMicMuted = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

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
            val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps.coerceIn(1_000_000, 60_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps.coerceIn(15, 120))
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mimeType == MediaFormat.MIMETYPE_VIDEO_AVC) {
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
            val codec = MediaCodec.createEncoderByType(mimeType)
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (eHigh: Exception) {
                // Retry tanpa optional high profile keys jika chipset menolaknya
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
            Log.i(TAG, "Video MediaCodec siap: $mimeType ${width}x${height} @ ${fps}FPS ($bitrateBps bps)")
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

        try {
            videoEncoder?.signalEndOfInputStream()
        } catch (e: Exception) {
            Log.w(TAG, "signalEndOfInputStream notice: ${e.message}")
        }

        try { audioLoopThread?.join(2500) } catch (ignored: InterruptedException) {}
        try { audioDrainThread?.join(2500) } catch (ignored: InterruptedException) {}
        try { videoDrainThread?.join(3500) } catch (ignored: InterruptedException) {}

        val cleanlyWritten = muxerLock.withLock {
            try {
                if (muxerStarted) {
                    mediaMuxer?.stop()
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.w(TAG, "mediaMuxer.stop notice: ${e.message}")
                false
            }
        }

        releaseAll()
        return cleanlyWritten
    }

    private fun currentNormalizedPtsUs(): Long {
        val nowUs = System.nanoTime() / 1000L
        return (nowUs - recordingStartUs - totalPausedUs).coerceAtLeast(0L)
    }

    private fun drainVideoEncoderLoop() {
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
                    onOutputFormatChanged(isVideo = true, format = encoder.outputFormat)
                }
                outIndex >= 0 -> {
                    idleAfterStop = 0
                    val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val encodedData = try { encoder.getOutputBuffer(outIndex) } catch (e: Exception) { null }

                    if (encodedData != null && bufferInfo.size > 0 && !isConfig) {
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
        val encoder = audioEncoder ?: return
        val pb = playbackAudioRecord
        val mic = micAudioRecord

        val pbFramesPerRead = 1024
        val pbChannels = pb?.channelCount ?: 0
        val micChannels = mic?.channelCount ?: 0
        val pbShortBuf = ShortArray(pbFramesPerRead * pbChannels.coerceAtLeast(1))
        val micShortBuf = ShortArray(pbFramesPerRead * micChannels.coerceAtLeast(1))

        var samplesEncoded = 0L

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
                    samplesEncoded += (initShorts / audioChannelCount.coerceAtLeast(1))
                }
            }
        } catch (ignored: Exception) {}

        var codecAborted = false
        while (!stopRequested.get() && !codecAborted) {
            if (isPaused.get()) {
                // Kuras frame AudioRecord yang menumpuk selama pause agar saat resume audio tetap sinkron tanpa burst/delay
                val drainedPb = if (pb != null && pb.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    try { pb.read(pbShortBuf, 0, pbShortBuf.size) } catch (_: Exception) { 0 }
                } else 0
                val drainedMic = if (mic != null && mic.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    try { mic.read(micShortBuf, 0, micShortBuf.size) } catch (_: Exception) { 0 }
                } else 0
                if (drainedPb <= 0 && drainedMic <= 0) {
                    try { Thread.sleep(15) } catch (_: InterruptedException) {}
                }
                continue
            }

            val pbRead = if (pb != null && pb.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                pb.read(pbShortBuf, 0, pbShortBuf.size)
            } else 0

            val micRead = if (mic != null && mic.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val targetSamples = if (pbRead > 0 && pbChannels > 0) {
                    (pbRead / pbChannels) * micChannels.coerceAtLeast(1)
                } else {
                    micShortBuf.size
                }
                mic.read(micShortBuf, 0, targetSamples.coerceIn(1, micShortBuf.size))
            } else 0

            val mixedShorts: ShortArray = when (effectiveAudioMode) {
                "BOTH" -> mixPcm16Frames(
                    playback = pbShortBuf,
                    playbackSamplesRead = pbRead.coerceAtLeast(0),
                    playbackChannels = pbChannels.coerceAtLeast(1),
                    mic = micShortBuf,
                    micSamplesRead = micRead.coerceAtLeast(0),
                    micChannels = micChannels.coerceAtLeast(1),
                    micMuted = isMicMuted.get()
                )
                "INTERNAL" -> {
                    if (pbRead > 0) pbShortBuf.copyOf(pbRead) else ShortArray(0)
                }
                "MIC" -> {
                    if (micRead > 0) {
                        if (isMicMuted.get()) ShortArray(micRead) else micShortBuf.copyOf(micRead)
                    } else ShortArray(0)
                }
                else -> ShortArray(0)
            }

            if (mixedShorts.isEmpty()) {
                try { Thread.sleep(10) } catch (_: InterruptedException) {}
                continue
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

                val framesThisChunk = chunkShorts / audioChannelCount.coerceAtLeast(1)
                val samplePtsUs = (samplesEncoded * 1_000_000L) / audioSampleRate
                val wallPtsUs = currentNormalizedPtsUs()
                val ptsUs = maxOf(samplePtsUs, minOf(wallPtsUs, samplePtsUs + 50_000L))
                samplesEncoded += framesThisChunk

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
                val ptsUs = (samplesEncoded * 1_000_000L) / audioSampleRate
                encoder.queueInputBuffer(
                    inIdx,
                    0,
                    0,
                    ptsUs,
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
                        } catch (e: Exception) {
                            Log.w(TAG, "flush pending video sample error: ${e.message}")
                        }
                    }
                    pendingVideoSamples.clear()

                    if (audioTrackIndex >= 0) {
                        for ((buf, info) in pendingAudioSamples) {
                            try {
                                muxer.writeSampleData(audioTrackIndex, buf, info)
                            } catch (e: Exception) {
                                Log.w(TAG, "flush pending audio sample error: ${e.message}")
                            }
                        }
                    }
                    pendingAudioSamples.clear()
                    Log.i(TAG, "MediaMuxer started (videoTrack=$videoTrackIndex, audioTrack=$audioTrackIndex)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "onOutputFormatChanged muxer error: ${e.message}", e)
            }
        }
    }

    private fun writeOrBufferSample(isVideo: Boolean, data: ByteBuffer, info: MediaCodec.BufferInfo) {
        muxerLock.withLock {
            val muxer = mediaMuxer ?: return
            if (muxerStarted) {
                val track = if (isVideo) videoTrackIndex else audioTrackIndex
                if (track >= 0) {
                    try {
                        muxer.writeSampleData(track, data, info)
                    } catch (e: Exception) {
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
            videoTrackIndex = -1
            audioTrackIndex = -1
            pendingVideoSamples.clear()
            pendingAudioSamples.clear()
        }
    }
}
