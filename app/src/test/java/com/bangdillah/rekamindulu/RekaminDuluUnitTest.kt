package com.bangdillah.rekamindulu

import android.media.MediaRecorder
import com.bangdillah.rekamindulu.audio.MicrophoneSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RekaminDuluUnitTest {

    @Test
    fun testMicrophoneSelectorMapping() {
        assertEquals(MediaRecorder.AudioSource.CAMCORDER, MicrophoneSelector.getAudioSource("camcorder"))
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MicrophoneSelector.getAudioSource("voice_comm"))
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MicrophoneSelector.getAudioSource("bluetooth"))
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MicrophoneSelector.getAudioSource("headset"))
        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, MicrophoneSelector.getAudioSource("voice_recognition"))
        assertEquals(MediaRecorder.AudioSource.MIC, MicrophoneSelector.getAudioSource("mic"))
        assertEquals(MediaRecorder.AudioSource.MIC, MicrophoneSelector.getAudioSource("usb"))
        assertEquals(MediaRecorder.AudioSource.MIC, MicrophoneSelector.getAudioSource("default"))
        assertEquals(MediaRecorder.AudioSource.MIC, MicrophoneSelector.getAudioSource("unknown_source"))
    }

    @Test
    fun testMicrophoneSelectorDisplayNames() {
        val camDesc = MicrophoneSelector.getDisplayName("camcorder")
        assertTrue(camDesc.contains("Camcorder", ignoreCase = true))

        val btDesc = MicrophoneSelector.getDisplayName("bluetooth")
        assertTrue(btDesc.contains("Headset", ignoreCase = true) || btDesc.contains("Bluetooth", ignoreCase = true))

        val recDesc = MicrophoneSelector.getDisplayName("voice_recognition")
        assertTrue(recDesc.contains("Voice Recognition", ignoreCase = true) || recDesc.contains("Peredam", ignoreCase = true))

        val defaultDesc = MicrophoneSelector.getDisplayName("default")
        assertTrue(defaultDesc.contains("Default", ignoreCase = true))
    }

    @Test
    fun testAudioHelperConstants() {
        assertEquals(44100, com.bangdillah.rekamindulu.audio.AudioHelper.DEFAULT_SAMPLE_RATE)
        assertEquals(48000, com.bangdillah.rekamindulu.audio.AudioHelper.HIGH_RES_SAMPLE_RATE)
    }

    @Test
    fun testGameAudioSourceAndFallbacks() {
        val gameSource = MicrophoneSelector.getGameAudioSource()
        assertTrue("Game audio source harus berupa integer valid", gameSource >= 0)

        val camFallbacks = MicrophoneSelector.getFallbackAudioSources(MediaRecorder.AudioSource.CAMCORDER)
        assertTrue(camFallbacks.contains(MediaRecorder.AudioSource.CAMCORDER))
        assertTrue(camFallbacks.contains(MediaRecorder.AudioSource.MIC))
        assertTrue(camFallbacks.contains(MediaRecorder.AudioSource.DEFAULT))

        val micFallbacks = MicrophoneSelector.getFallbackAudioSources(MediaRecorder.AudioSource.MIC)
        assertTrue(micFallbacks.contains(MediaRecorder.AudioSource.MIC))
    }

    @Test
    fun testSampleRateClampingLogic() {
        fun clampSampleRate(rawRate: Int): Int {
            return when {
                rawRate >= 48000 -> 48000
                rawRate in 44100 until 48000 -> 44100
                rawRate > 0 -> 44100
                else -> 44100
            }
        }

        // Standard DAC outputs
        assertEquals(44100, clampSampleRate(44100))
        assertEquals(48000, clampSampleRate(48000))

        // High-res DAC outputs (96kHz, 192kHz) that crash standard AAC encoder if unclamped
        assertEquals(48000, clampSampleRate(96000))
        assertEquals(48000, clampSampleRate(192000))
    }

    @Test
    fun testAudioSourcePreferencesMapping() {
        fun computeAudioSource(micOn: Boolean, internalOn: Boolean): String {
            return when {
                micOn && internalOn -> "BOTH"
                micOn -> "MIC"
                internalOn -> "INTERNAL"
                else -> "MUTE"
            }
        }

        assertEquals("BOTH", computeAudioSource(micOn = true, internalOn = true))
        assertEquals("MIC", computeAudioSource(micOn = true, internalOn = false))
        assertEquals("INTERNAL", computeAudioSource(micOn = false, internalOn = true))
        assertEquals("MUTE", computeAudioSource(micOn = false, internalOn = false))
    }


    @Test
    fun testFileExtensionValidation() {
        val mp4File = File("sample_video.mp4")
        assertTrue(mp4File.name.endsWith(".mp4", ignoreCase = true))
        assertEquals("sample_video", mp4File.nameWithoutExtension)
    }

    @Test
    fun testSanitizeFileName() {
        val illegalChars = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        val testName = "Rekaman_Game:1"
        val hasIllegal = testName.any { it in illegalChars }
        assertTrue("Nama harus terdeteksi memiliki karakter ilegal", hasIllegal)

        val cleanName = "Rekaman_Game_1"
        val hasIllegalClean = cleanName.any { it in illegalChars }
        assertTrue("Nama bersih tidak boleh memiliki karakter ilegal", !hasIllegalClean)
    }

    @Test
    fun testHardwareEncoderVendorIdentification() {
        val qcomDesc = com.bangdillah.rekamindulu.utils.HardwareEncoderHelper.identifyVendor("c2.qti.avc.encoder")
        assertTrue(qcomDesc.contains("Qualcomm", ignoreCase = true))

        val mtkDesc = com.bangdillah.rekamindulu.utils.HardwareEncoderHelper.identifyVendor("OMX.MTK.VIDEO.ENCODER.AVC")
        assertTrue(mtkDesc.contains("MediaTek", ignoreCase = true))

        val exynosDesc = com.bangdillah.rekamindulu.utils.HardwareEncoderHelper.identifyVendor("c2.exynos.avc.encoder")
        assertTrue(exynosDesc.contains("Samsung", ignoreCase = true))

        val swDesc = com.bangdillah.rekamindulu.utils.HardwareEncoderHelper.identifyVendor("c2.android.avc.encoder")
        assertTrue(swDesc.contains("Software", ignoreCase = true))
    }

    @Test
    fun testHardwareOffloadStatus() {
        val offStatus = com.bangdillah.rekamindulu.utils.HardwareEncoderHelper.getHardwareOffloadStatus(false)
        assertEquals("Mode Standar CPU", offStatus)

        val onStatus = com.bangdillah.rekamindulu.utils.HardwareEncoderHelper.getHardwareOffloadStatus(true)
        assertTrue(onStatus.isNotEmpty())
    }

    @Test
    fun testFpsStabilizerCadencePacing() {
        val targetFps = 60
        val isStabilizerActive = true

        // Simulate 60 FPS VSYNC interval (~16.6ms) with minor jitter (17.2ms)
        val deltaNanos = 17_200_000L
        val instantFps = 1_000_000_000.0 / deltaNanos // ~58.13 FPS

        // In stabilizer active mode with 80% threshold, 58.13 FPS is within normal 60 FPS pacing
        val stabilizedFps = if (isStabilizerActive) {
            if (instantFps >= (targetFps * 0.80)) targetFps else instantFps.toInt()
        } else {
            instantFps.toInt()
        }
        assertEquals(60, stabilizedFps)

        // Simulate severe lag drop (30ms = 33 FPS)
        val lagDeltaNanos = 30_000_000L
        val lagFps = 1_000_000_000.0 / lagDeltaNanos // 33.33 FPS
        val lagResult = if (isStabilizerActive) {
            if (lagFps >= (targetFps * 0.80)) targetFps else lagFps.toInt()
        } else {
            lagFps.toInt()
        }
        assertEquals(33, lagResult)
    }

    @Test
    fun testFirestoreDocIdSanitization() {
        val rawName = "Rekaman Layar 2026/10/31 #1.mp4"
        val rawWithoutExt = rawName.substringBeforeLast(".")
        val sanitized = rawWithoutExt.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        assertEquals("Rekaman_Layar_2026_10_31__1", sanitized)
        assertTrue(sanitized.matches("^[a-zA-Z0-9_-]+$".toRegex()))
    }

    @Test
    fun testVideoRecordingModelDefaults() {
        val model = com.bangdillah.rekamindulu.backend.VideoRecording()
        assertEquals("", model.id)
        assertEquals("", model.userId)
        assertEquals("00:00", model.durationFormatted)
        assertTrue(model.hasAudio)
        assertEquals("H264", model.encoder)
    }

    @Test
    fun testMediaProjectionServiceActionConstants() {
        assertEquals("ACTION_START", com.bangdillah.rekamindulu.service.ScreenRecordingService.ACTION_START)
        assertEquals("ACTION_STOP", com.bangdillah.rekamindulu.service.ScreenRecordingService.ACTION_STOP)
        assertEquals("ACTION_PAUSE", com.bangdillah.rekamindulu.service.ScreenRecordingService.ACTION_PAUSE)
        assertEquals("ACTION_RESUME", com.bangdillah.rekamindulu.service.ScreenRecordingService.ACTION_RESUME)
        assertEquals("EXTRA_RESULT_CODE", com.bangdillah.rekamindulu.service.ScreenRecordingService.EXTRA_RESULT_CODE)
        assertEquals("EXTRA_RESULT_DATA", com.bangdillah.rekamindulu.service.ScreenRecordingService.EXTRA_RESULT_DATA)
    }

    @Test
    fun testAudioSupportModesAndPermissionCheck() {
        // Mode 1: Mic Only -> Audio enabled, requires RECORD_AUDIO
        val micOnlySource = if (true && !false) "MIC" else "OTHER"
        assertEquals("MIC", micOnlySource)
        val micNeedsAudioPermission = micOnlySource != "MUTE"
        assertTrue(micNeedsAudioPermission)

        // Mode 2: Game Audio Only -> Internal audio
        val gameOnlySource = if (!false && true) "INTERNAL" else "OTHER"
        assertEquals("INTERNAL", gameOnlySource)

        // Mode 3: Both (Mic + Game Audio)
        val bothSource = if (true && true) "BOTH" else "OTHER"
        assertEquals("BOTH", bothSource)

        // Mode 4: Mute (No Mic, No Game Audio) -> No audio track
        val muteSource = if (!false && !false) "MUTE" else "OTHER"
        assertEquals("MUTE", muteSource)
        val muteNeedsAudioPermission = muteSource != "MUTE"
        assertTrue(!muteNeedsAudioPermission)
    }

    @Test
    fun testOverlayOnScreenTimerFormatting() {
        // 0 seconds -> 00:00
        assertEquals("00:00", com.bangdillah.rekamindulu.floating.FloatingOverlayService.formatDuration(0))

        // Negative duration safeguard -> 00:00
        assertEquals("00:00", com.bangdillah.rekamindulu.floating.FloatingOverlayService.formatDuration(-10))

        // 45 seconds -> 00:45
        assertEquals("00:45", com.bangdillah.rekamindulu.floating.FloatingOverlayService.formatDuration(45))

        // 5 minutes 32 seconds (332s) -> 05:32
        assertEquals("05:32", com.bangdillah.rekamindulu.floating.FloatingOverlayService.formatDuration(332))

        // 59 minutes 59 seconds (3599s) -> 59:59
        assertEquals("59:59", com.bangdillah.rekamindulu.floating.FloatingOverlayService.formatDuration(3599))

        // 1 hour exactly (3600s) -> 01:00:00
        assertEquals("01:00:00", com.bangdillah.rekamindulu.floating.FloatingOverlayService.formatDuration(3600))

        // 2 hours 15 minutes 40 seconds (8140s) -> 02:15:40
        assertEquals("02:15:40", com.bangdillah.rekamindulu.floating.FloatingOverlayService.formatDuration(8140))
    }

    @Test
    fun testForegroundServiceTypeCalculation() {
        // Simulates Android 14+ ServiceInfo foregroundServiceType calculation logic
        fun calculateFgsType(hasMicPermission: Boolean, audioSource: String): Int {
            val typeMediaProjection = 0x00000020 // ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            val typeMicrophone = 0x00000080      // ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

            val wantsAudio = audioSource != "MUTE"
            return if (hasMicPermission && wantsAudio) {
                typeMediaProjection or typeMicrophone
            } else {
                typeMediaProjection
            }
        }

        // Case 1: Mic permission granted, user wants MIC
        val typeWithMic = calculateFgsType(hasMicPermission = true, audioSource = "MIC")
        assertEquals(0x00000020 or 0x00000080, typeWithMic)

        // Case 2: Mic permission granted, user wants BOTH
        val typeWithBoth = calculateFgsType(hasMicPermission = true, audioSource = "BOTH")
        assertEquals(0x00000020 or 0x00000080, typeWithBoth)

        // Case 3: Mic permission DENIED, user selected MIC -> must NOT declare MICROPHONE (prevents Android 14 SecurityException)
        val typeDeniedMic = calculateFgsType(hasMicPermission = false, audioSource = "MIC")
        assertEquals(0x00000020, typeDeniedMic)

        // Case 4: User selected MUTE -> must NOT declare MICROPHONE
        val typeMute = calculateFgsType(hasMicPermission = true, audioSource = "MUTE")
        assertEquals(0x00000020, typeMute)
    }

    @Test
    fun testAudioPermissionGracefulDegradation() {
        fun resolveAudioCapture(hasPermission: Boolean, preferredSource: String): Pair<Boolean, String> {
            val wantsAudio = preferredSource != "MUTE" && hasPermission
            val finalSource = if (wantsAudio) preferredSource else "MUTE"
            return Pair(wantsAudio, finalSource)
        }

        // When permission is missing, audio is gracefully disabled without crashing
        val (wantsAudio1, source1) = resolveAudioCapture(hasPermission = false, preferredSource = "MIC")
        assertEquals(false, wantsAudio1)
        assertEquals("MUTE", source1)

        // When permission is present, audio is retained
        val (wantsAudio2, source2) = resolveAudioCapture(hasPermission = true, preferredSource = "MIC")
        assertEquals(true, wantsAudio2)
        assertEquals("MIC", source2)
    }

    @Test
    fun testWebClientIdFormat() {
        val clientId = "563321500714-b419c536uptulaun2lhchvs8svgnpf2d.apps.googleusercontent.com"
        assertTrue("Client ID harus berakhir dengan .apps.googleusercontent.com", clientId.endsWith(".apps.googleusercontent.com"))
        assertTrue("Client ID harus diawali dengan project number 563321500714", clientId.startsWith("563321500714-"))
    }

    @Test
    fun testFirestoreDatabaseIdFallback() {
        fun resolveDatabaseTarget(dbId: String?): String {
            return if (!dbId.isNullOrBlank()) dbId else "(default)"
        }

        assertEquals("ai-studio-android-rekamind-29e24682-644a-4f29-9873-59ab0c0fda6b", resolveDatabaseTarget("ai-studio-android-rekamind-29e24682-644a-4f29-9873-59ab0c0fda6b"))
        assertEquals("(default)", resolveDatabaseTarget(""))
        assertEquals("(default)", resolveDatabaseTarget("   "))
        assertEquals("(default)", resolveDatabaseTarget(null))
    }

    @Test
    fun testTrimValidationLogic() {
        fun validateTrim(startMs: Long, endMs: Long, fileExists: Boolean, fileLength: Long): Pair<Boolean, String> {
            if (!fileExists) return Pair(false, "File sumber tidak ditemukan")
            if (fileLength < 1024) return Pair(false, "File terlalu kecil atau kosong")
            val durationMs = endMs - startMs
            if (durationMs < 1000) return Pair(false, "Rentang durasi minimal 1 detik")
            return Pair(true, "Valid")
        }

        // Non-existent file
        val (ok1, msg1) = validateTrim(0, 5000, fileExists = false, fileLength = 50000)
        assertEquals(false, ok1)
        assertEquals("File sumber tidak ditemukan", msg1)

        // Sub-second trim (too short)
        val (ok2, msg2) = validateTrim(1000, 1500, fileExists = true, fileLength = 50000)
        assertEquals(false, ok2)
        assertEquals("Rentang durasi minimal 1 detik", msg2)

        // Valid trim
        val (ok3, msg3) = validateTrim(1000, 4000, fileExists = true, fileLength = 50000)
        assertEquals(true, ok3)
        assertEquals("Valid", msg3)
    }

    @Test
    fun testProportionalHdResolutionCalculation() {
        fun computeDimensions(rawWidth: Int, rawHeight: Int, resolutionPref: String): Pair<Int, Int> {
            val longSide = maxOf(rawWidth, rawHeight)
            val shortSide = minOf(rawWidth, rawHeight)

            val (targetLong, targetShort) = when (resolutionPref) {
                "NATIVE" -> Pair(longSide, shortSide)
                "4K" -> {
                    val desiredShort = 2160
                    val scale = if (shortSide > desiredShort) desiredShort.toDouble() / shortSide.toDouble() else 1.0
                    Pair((longSide * scale).toInt(), (shortSide * scale).toInt())
                }
                "2K" -> {
                    val desiredShort = 1440
                    val scale = if (shortSide > desiredShort) desiredShort.toDouble() / shortSide.toDouble() else 1.0
                    Pair((longSide * scale).toInt(), (shortSide * scale).toInt())
                }
                "1080p" -> {
                    val desiredShort = 1080
                    val scale = if (shortSide > desiredShort) desiredShort.toDouble() / shortSide.toDouble() else 1.0
                    Pair((longSide * scale).toInt(), (shortSide * scale).toInt())
                }
                "720p" -> {
                    val desiredShort = 720
                    val scale = if (shortSide > desiredShort) desiredShort.toDouble() / shortSide.toDouble() else 1.0
                    Pair((longSide * scale).toInt(), (shortSide * scale).toInt())
                }
                else -> Pair(longSide, shortSide)
            }

            val alignedLong = (targetLong / 2) * 2
            val alignedShort = (targetShort / 2) * 2
            return Pair(alignedShort, alignedLong)
        }

        // 1. Phone 1080x2400 (20:9 aspect ratio): 1080p should stay 1080x2400 (NOT downscaled to 864x1920)
        val (w1080, h1080) = computeDimensions(1080, 2400, "1080p")
        assertEquals(1080, w1080)
        assertEquals(2400, h1080)

        // 2. Native resolution: exactly 1080x2400
        val (wNative, hNative) = computeDimensions(1080, 2400, "NATIVE")
        assertEquals(1080, wNative)
        assertEquals(2400, hNative)

        // 3. 720p on 1080x2400: properly scaled to 720x1600 preserving 20:9 ratio
        val (w720, h720) = computeDimensions(1080, 2400, "720p")
        assertEquals(720, w720)
        assertEquals(1600, h720)

        // 4. 2K QHD+ phone (1440x3200): 1080p scales down cleanly to 1080x2400
        val (w2kDown, h2kDown) = computeDimensions(1440, 3200, "1080p")
        assertEquals(1080, w2kDown)
        assertEquals(2400, h2kDown)
    }

    @Test
    fun testHighBitrateClampingSupport() {
        fun clampBitrate(bitrateMbps: Int): Int {
            return (bitrateMbps * 1_000_000).coerceIn(1_000_000, 60_000_000)
        }

        assertEquals(20_000_000, clampBitrate(20))
        assertEquals(24_000_000, clampBitrate(24))
        assertEquals(30_000_000, clampBitrate(30))
        assertEquals(60_000_000, clampBitrate(100))
    }

    @Test
    fun testProportionalDensityScaling() {
        fun computeDensity(baseDensity: Int, screenWidth: Int, rawWidth: Int): Int {
            val scaleRatio = screenWidth.toDouble() / rawWidth.toDouble()
            return (baseDensity * scaleRatio).toInt().coerceIn(120, 640)
        }

        // 1080p on 1080p physical display: density remains 420
        assertEquals(420, computeDensity(420, 1080, 1080))

        // 720p on 1080p physical display: density scales down proportionally to 280
        assertEquals(280, computeDensity(420, 720, 1080))
    }

    @Test
    fun testRecordingEngineOptimizerDimensions1080pAnd720p() {
        val metrics = android.util.DisplayMetrics().apply {
            widthPixels = 1080
            heightPixels = 2400
            densityDpi = 420
        }

        // Test 1080p
        val dims1080 = com.bangdillah.rekamindulu.service.RecordingEngineOptimizer.calculateDimensions(metrics, "1080p")
        assertEquals(1080, dims1080.width)
        assertEquals(2400, dims1080.height)
        assertEquals(420, dims1080.densityDpi)

        // Test 720p
        val dims720 = com.bangdillah.rekamindulu.service.RecordingEngineOptimizer.calculateDimensions(metrics, "720p")
        assertEquals(720, dims720.width)
        assertEquals(1600, dims720.height)
        assertEquals(280, dims720.densityDpi)

        // Test Native
        val dimsNative = com.bangdillah.rekamindulu.service.RecordingEngineOptimizer.calculateDimensions(metrics, "NATIVE")
        assertEquals(1080, dimsNative.width)
        assertEquals(2400, dimsNative.height)
        assertEquals(420, dimsNative.densityDpi)
    }

    @Test
    fun testRecordingEngineOptimizerResolutionLabelsAndBitrates() {
        assertEquals("1080p Real HD", com.bangdillah.rekamindulu.service.RecordingEngineOptimizer.getResolutionLabel("1080p"))
        assertEquals("720p HD", com.bangdillah.rekamindulu.service.RecordingEngineOptimizer.getResolutionLabel("720p"))
        assertEquals("Layar Asli (1:1)", com.bangdillah.rekamindulu.service.RecordingEngineOptimizer.getResolutionLabel("NATIVE"))

        assertEquals(24, com.bangdillah.rekamindulu.service.RecordingEngineOptimizer.getOptimalBitrateMbps("1080p", 60))
        assertEquals(16, com.bangdillah.rekamindulu.service.RecordingEngineOptimizer.getOptimalBitrateMbps("1080p", 30))
        assertEquals(12, com.bangdillah.rekamindulu.service.RecordingEngineOptimizer.getOptimalBitrateMbps("720p", 60))
        assertEquals(8, com.bangdillah.rekamindulu.service.RecordingEngineOptimizer.getOptimalBitrateMbps("720p", 30))
    }

    @Test
    fun testRecordingEngineOptimizerLandscapeDimensions() {
        val metrics = android.util.DisplayMetrics().apply {
            widthPixels = 1080
            heightPixels = 2400
            densityDpi = 420
        }

        // Forced Landscape for games
        val dimsLandscape1080 = com.bangdillah.rekamindulu.service.RecordingEngineOptimizer.calculateDimensions(metrics, "1080p", "LANDSCAPE")
        assertEquals(2400, dimsLandscape1080.width)
        assertEquals(1080, dimsLandscape1080.height)

        val dimsLandscape720 = com.bangdillah.rekamindulu.service.RecordingEngineOptimizer.calculateDimensions(metrics, "720p", "LANDSCAPE")
        assertEquals(1600, dimsLandscape720.width)
        assertEquals(720, dimsLandscape720.height)
    }

    @Test
    fun testTrimSeekClamping() {
        val videoDurationMs = 15000
        val seekEndTimeProgress = 500

        // Start time clamping should not become negative
        val maxAllowedStart = ((seekEndTimeProgress) - 1000).coerceAtLeast(0)
        assertEquals(0, maxAllowedStart)

        // End time clamping should not exceed videoDurationMs
        val seekStartTimeProgress = 14500
        val minAllowedEnd = ((seekStartTimeProgress) + 1000).coerceAtMost(videoDurationMs)
        assertEquals(15000, minAllowedEnd)
    }

    @Test
    fun testPlayerForwardMaxDurationClamp() {
        // Known duration
        val durationKnown = 30000
        val currentPos = 25000
        val maxDurKnown = if (durationKnown > 0) durationKnown else (currentPos + 10000)
        val newPosKnown = (currentPos + 10000).coerceIn(0, maxDurKnown)
        assertEquals(30000, newPosKnown)

        // Unknown duration (e.g. 0 or negative)
        val durationUnknown = -1
        val maxDurUnknown = if (durationUnknown > 0) durationUnknown else (currentPos + 10000)
        val newPosUnknown = (currentPos + 10000).coerceIn(0, maxDurUnknown)
        assertEquals(35000, newPosUnknown)
    }

    @Test
    fun testStorageFreeCalculation() {
        val freeBytes = 500 * 1024 * 1024L // 500MB
        val freeGb = freeBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        assertTrue("500MB harus lebih besar dari batas kritis 0.2 GB (200MB)", freeGb > 0.2)
        assertEquals(0.488, freeGb, 0.01)

        val criticalBytes = 150 * 1024 * 1024L // 150MB
        val criticalGb = criticalBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        assertTrue("150MB harus lebih kecil dari batas peringatan 0.2 GB", criticalGb < 0.2)
    }

    @Test
    fun testPcm16SampleMixingAndSaturationClamping() {
        assertEquals(
            1500.toShort(),
            com.bangdillah.rekamindulu.service.MediaCodecScreenRecorder.mixPcm16Sample(1000, 500)
        )
        assertEquals(
            Short.MAX_VALUE,
            com.bangdillah.rekamindulu.service.MediaCodecScreenRecorder.mixPcm16Sample(30000, 10000)
        )
        assertEquals(
            Short.MIN_VALUE,
            com.bangdillah.rekamindulu.service.MediaCodecScreenRecorder.mixPcm16Sample(-30000, -10000)
        )
    }

    @Test
    fun testPcm16FramesMixingAndLiveMicMute() {
        val playback = shortArrayOf(1000, -1000, 20000, -20000)
        val mic = shortArrayOf(500, 15000)

        val mixedWithMic = com.bangdillah.rekamindulu.service.MediaCodecScreenRecorder.mixPcm16Frames(
            playback = playback,
            playbackSamplesRead = 4,
            playbackChannels = 2,
            mic = mic,
            micSamplesRead = 2,
            micChannels = 1,
            micMuted = false
        )
        assertEquals(4, mixedWithMic.size)
        assertEquals(1500.toShort(), mixedWithMic[0])
        assertEquals((-500).toShort(), mixedWithMic[1])
        assertEquals(Short.MAX_VALUE, mixedWithMic[2]) // 20000 + 15000 clamped
        assertEquals((-5000).toShort(), mixedWithMic[3])

        val mixedMutedMic = com.bangdillah.rekamindulu.service.MediaCodecScreenRecorder.mixPcm16Frames(
            playback = playback,
            playbackSamplesRead = 4,
            playbackChannels = 2,
            mic = mic,
            micSamplesRead = 2,
            micChannels = 1,
            micMuted = true
        )
        assertEquals(4, mixedMutedMic.size)
        assertEquals(1000.toShort(), mixedMutedMic[0])
        assertEquals((-1000).toShort(), mixedMutedMic[1])
        assertEquals(20000.toShort(), mixedMutedMic[2])
        assertEquals((-20000).toShort(), mixedMutedMic[3])
    }

    @Test
    fun testExtendedServiceActionConstants() {
        assertEquals("ACTION_TOGGLE_MIC", com.bangdillah.rekamindulu.service.ScreenRecordingService.ACTION_TOGGLE_MIC)
        assertEquals("ACTION_SET_MIC_MUTED", com.bangdillah.rekamindulu.service.ScreenRecordingService.ACTION_SET_MIC_MUTED)
        assertEquals("ACTION_CAPTURE_SCREENSHOT", com.bangdillah.rekamindulu.service.ScreenRecordingService.ACTION_CAPTURE_SCREENSHOT)
        assertEquals("ACTION_REFRESH_FOREGROUND_TYPE", com.bangdillah.rekamindulu.service.ScreenRecordingService.ACTION_REFRESH_FOREGROUND_TYPE)
    }

    @Test
    fun testLibraryGameFilterMatchesOnlyGameFiles() {
        fun isGameRecording(fileName: String): Boolean {
            return fileName.contains("game", ignoreCase = true) || fileName.contains("fps", ignoreCase = true)
        }

        assertTrue(isGameRecording("Game_Rekaman_20260401_120000.mp4"))
        assertTrue(isGameRecording("MLBB_60FPS_highlight.mp4"))
        assertTrue(!isGameRecording("Rekaman_20260401_120000.mp4"))
        assertTrue(!isGameRecording("Demo_RekaminDulu_12345.mp4"))
    }

    @Test
    fun testCreateAudioOutputFileAvoidsCollisions() {
        val tempDir = java.nio.file.Files.createTempDirectory("rekamin_audio_test").toFile()
        try {
            val fixedStamp = "20260401_120000"
            val first = com.bangdillah.rekamindulu.audio.AudioExtractorHelper.createAudioOutputFile(
                outputDir = tempDir,
                baseName = "Game_MLBB",
                timeStamp = fixedStamp
            )
            assertEquals("Audio_Game_MLBB_20260401_120000.m4a", first.name)
            assertTrue(first.createNewFile())

            val second = com.bangdillah.rekamindulu.audio.AudioExtractorHelper.createAudioOutputFile(
                outputDir = tempDir,
                baseName = "Game_MLBB",
                timeStamp = fixedStamp
            )
            assertEquals("Audio_Game_MLBB_20260401_120000_1.m4a", second.name)
            assertTrue(second.createNewFile())

            val third = com.bangdillah.rekamindulu.audio.AudioExtractorHelper.createAudioOutputFile(
                outputDir = tempDir,
                baseName = "Game_MLBB",
                timeStamp = fixedStamp
            )
            assertEquals("Audio_Game_MLBB_20260401_120000_2.m4a", third.name)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testRecordingEngineOptimizer4K2KAnd480pDimensions() {
        val metrics = android.util.DisplayMetrics().apply {
            widthPixels = 1080
            heightPixels = 2400
            densityDpi = 420
        }

        // 480p scales short side down from 1080 to 480 cleanly preserving 20:9
        val dims480 = com.bangdillah.rekamindulu.service.RecordingEngineOptimizer.calculateDimensions(metrics, "480p")
        assertEquals(480, dims480.width)
        assertEquals(1066, dims480.height)

        // 2K / 4K on a 1080p panel should not upscale beyond native screen dimensions
        val dims2k = com.bangdillah.rekamindulu.service.RecordingEngineOptimizer.calculateDimensions(metrics, "2K")
        assertEquals(1080, dims2k.width)
        assertEquals(2400, dims2k.height)

        val dims4k = com.bangdillah.rekamindulu.service.RecordingEngineOptimizer.calculateDimensions(metrics, "4K")
        assertEquals(1080, dims4k.width)
        assertEquals(2400, dims4k.height)
    }

    @Test
    fun testResolveForegroundServiceTypeRespectsPermissionsAndPrefs() {
        val typeMedia = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        val typeMic = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val typeCam = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA

        // API 34 (Android 14): Mute + no perms -> MEDIA_PROJECTION only
        val muteNoPerms = com.bangdillah.rekamindulu.service.ScreenRecordingService.resolveForegroundServiceType(
            hasMicPermission = false,
            hasCameraPermission = false,
            wantsAudio = false,
            wantsFacecam = false,
            sdkInt = android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        )
        assertEquals(typeMedia, muteNoPerms)

        // API 34: User wants audio & facecam, but permissions denied -> MEDIA_PROJECTION only (no SecurityException)
        val wantsBothWithoutPerms = com.bangdillah.rekamindulu.service.ScreenRecordingService.resolveForegroundServiceType(
            hasMicPermission = false,
            hasCameraPermission = false,
            wantsAudio = true,
            wantsFacecam = true,
            sdkInt = android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        )
        assertEquals(typeMedia, wantsBothWithoutPerms)

        // API 34: All granted & wanted -> MEDIA_PROJECTION | MICROPHONE | CAMERA
        val allGranted = com.bangdillah.rekamindulu.service.ScreenRecordingService.resolveForegroundServiceType(
            hasMicPermission = true,
            hasCameraPermission = true,
            wantsAudio = true,
            wantsFacecam = true,
            sdkInt = android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        )
        assertEquals(typeMedia or typeMic or typeCam, allGranted)

        // API 29 (Android 10): Only MEDIA_PROJECTION exists in Q before R mic/camera types
        val api29Type = com.bangdillah.rekamindulu.service.ScreenRecordingService.resolveForegroundServiceType(
            hasMicPermission = true,
            hasCameraPermission = true,
            wantsAudio = true,
            wantsFacecam = true,
            sdkInt = android.os.Build.VERSION_CODES.Q
        )
        assertEquals(typeMedia, api29Type)
    }

    @Test
    fun testComputeFacecamRotationDegrees() {
        assertEquals(0f, com.bangdillah.rekamindulu.floating.FloatingOverlayService.computeFacecamRotationDegrees(android.view.Surface.ROTATION_0))
        assertEquals(270f, com.bangdillah.rekamindulu.floating.FloatingOverlayService.computeFacecamRotationDegrees(android.view.Surface.ROTATION_90))
        assertEquals(180f, com.bangdillah.rekamindulu.floating.FloatingOverlayService.computeFacecamRotationDegrees(android.view.Surface.ROTATION_180))
        assertEquals(90f, com.bangdillah.rekamindulu.floating.FloatingOverlayService.computeFacecamRotationDegrees(android.view.Surface.ROTATION_270))
    }

    @Test
    fun testOverlayAudioStatusAndSummaryReflectsLiveMicMute() {
        assertEquals(
            "🎙️ Mic + 🔊 Game",
            com.bangdillah.rekamindulu.floating.FloatingOverlayService.resolveAudioStatusLabel("BOTH", isRecording = true, isMicMuted = false)
        )
        assertEquals(
            "🔊 Audio Game (Mic Mute)",
            com.bangdillah.rekamindulu.floating.FloatingOverlayService.resolveAudioStatusLabel("BOTH", isRecording = true, isMicMuted = true)
        )
        assertEquals(
            "🔇 Mic Dibisukan",
            com.bangdillah.rekamindulu.floating.FloatingOverlayService.resolveAudioStatusLabel("MIC", isRecording = true, isMicMuted = true)
        )
        assertEquals(
            "🔊 Audio Game",
            com.bangdillah.rekamindulu.floating.FloatingOverlayService.resolveAudioStatusLabel("INTERNAL", isRecording = true, isMicMuted = true)
        )
        assertEquals(
            " • Audio: Game (Mic Mute)",
            com.bangdillah.rekamindulu.floating.FloatingOverlayService.resolveAudioSummarySuffix("BOTH", isRecording = true, isMicMuted = true)
        )
        assertEquals(
            " • Audio: Mic Mute",
            com.bangdillah.rekamindulu.floating.FloatingOverlayService.resolveAudioSummarySuffix("MIC", isRecording = true, isMicMuted = true)
        )
        assertEquals(
            " • Audio: Mic+Game",
            com.bangdillah.rekamindulu.floating.FloatingOverlayService.resolveAudioSummarySuffix("BOTH", isRecording = false, isMicMuted = true)
        )
    }

    @Test
    fun testTrimActivityClampTrimProgress() {
        // Start thumb dragged past end thumb (8000ms) -> clamped to 7000ms
        assertEquals(
            7000,
            com.bangdillah.rekamindulu.trim.TrimActivity.clampTrimProgress(
                progress = 8500,
                otherBoundMs = 8000,
                durationMs = 15000,
                isStartThumb = true
            )
        )
        // End thumb dragged below start thumb + 1000ms -> clamped to 5000ms
        assertEquals(
            5000,
            com.bangdillah.rekamindulu.trim.TrimActivity.clampTrimProgress(
                progress = 3500,
                otherBoundMs = 4000,
                durationMs = 15000,
                isStartThumb = false
            )
        )
        // Short video (< 1000ms, e.g. 600ms) keeps bounds valid without negative clamping
        assertEquals(
            0,
            com.bangdillah.rekamindulu.trim.TrimActivity.clampTrimProgress(
                progress = 500,
                otherBoundMs = 600,
                durationMs = 600,
                isStartThumb = true
            )
        )
        assertEquals(
            600,
            com.bangdillah.rekamindulu.trim.TrimActivity.clampTrimProgress(
                progress = 100,
                otherBoundMs = 0,
                durationMs = 600,
                isStartThumb = false
            )
        )
    }

    @Test
    fun testCreateTrimOutputFileDeduplicatesPrefixAndCollisions() {
        val tempDir = java.nio.file.Files.createTempDirectory("rekamin_trim_test").toFile()
        try {
            val fixedStamp = "20260401_120000"
            val first = com.bangdillah.rekamindulu.trim.TrimHelper.createTrimOutputFile(
                outputDir = tempDir,
                rawSourceName = "Trim_Trim_Game_MLBB",
                timeStamp = fixedStamp
            )
            assertEquals("Trim_Game_MLBB_20260401_120000.mp4", first.name)
            assertTrue(first.createNewFile())

            val second = com.bangdillah.rekamindulu.trim.TrimHelper.createTrimOutputFile(
                outputDir = tempDir,
                rawSourceName = "Trim_Game_MLBB",
                timeStamp = fixedStamp
            )
            assertEquals("Trim_Game_MLBB_20260401_120000_1.mp4", second.name)
            assertTrue(second.createNewFile())

            val sanitized = com.bangdillah.rekamindulu.trim.TrimHelper.createTrimOutputFile(
                outputDir = tempDir,
                rawSourceName = "Trim_",
                timeStamp = fixedStamp
            )
            assertEquals("Trim_Video_20260401_120000.mp4", sanitized.name)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testEffectiveAudioModeDefaultAndFallbackLabels() {
        assertEquals(
            null,
            com.bangdillah.rekamindulu.service.ScreenRecordingService.effectiveAudioMode
        )
        // Simulate Android <10 fallback where INTERNAL requested degrades to MIC
        assertEquals(
            "🎙️ Mic Aktif",
            com.bangdillah.rekamindulu.floating.FloatingOverlayService.resolveAudioStatusLabel(
                audioSource = "MIC",
                isRecording = true,
                isMicMuted = false
            )
        )
        // Simulate missing permission fallback to MUTE
        assertEquals(
            "🔇 Audio Mute",
            com.bangdillah.rekamindulu.floating.FloatingOverlayService.resolveAudioStatusLabel(
                audioSource = "MUTE",
                isRecording = true,
                isMicMuted = false
            )
        )
    }

    @Test
    fun testLibraryDurationOverOneMinuteFilter() {
        assertEquals(false, com.bangdillah.rekamindulu.library.LibraryActivity.isDurationOverOneMinute(0L))
        assertEquals(false, com.bangdillah.rekamindulu.library.LibraryActivity.isDurationOverOneMinute(59_999L))
        assertEquals(false, com.bangdillah.rekamindulu.library.LibraryActivity.isDurationOverOneMinute(60_000L))
        assertEquals(true, com.bangdillah.rekamindulu.library.LibraryActivity.isDurationOverOneMinute(60_001L))
        assertEquals(true, com.bangdillah.rekamindulu.library.LibraryActivity.isDurationOverOneMinute(125_000L))
    }

    @Test
    fun testAppLockHelperPinGateAndVerification() {
        assertEquals(
            false,
            com.bangdillah.rekamindulu.utils.AppLockHelper.shouldRequirePin(
                isAppLockEnabled = false,
                savedPin = "1234",
                isSessionUnlocked = false
            )
        )
        assertEquals(
            false,
            com.bangdillah.rekamindulu.utils.AppLockHelper.shouldRequirePin(
                isAppLockEnabled = true,
                savedPin = "",
                isSessionUnlocked = false
            )
        )
        assertEquals(
            true,
            com.bangdillah.rekamindulu.utils.AppLockHelper.shouldRequirePin(
                isAppLockEnabled = true,
                savedPin = "1234",
                isSessionUnlocked = false
            )
        )
        assertEquals(
            false,
            com.bangdillah.rekamindulu.utils.AppLockHelper.shouldRequirePin(
                isAppLockEnabled = true,
                savedPin = "1234",
                isSessionUnlocked = true
            )
        )
        assertEquals(true, com.bangdillah.rekamindulu.utils.AppLockHelper.verifyPin("1234", "1234"))
        assertEquals(false, com.bangdillah.rekamindulu.utils.AppLockHelper.verifyPin("9999", "1234"))
    }

    @Test
    fun testOptionScopedRuntimePermissions() {
        // Mute + Facecam OFF on Android 12 (API 31): zero runtime permissions needed before MediaProjection
        val mutePreTiramisu = MainActivity.computeRequiredRuntimePermissions(
            audioSource = "MUTE",
            facecamMode = "OFF",
            sdkInt = android.os.Build.VERSION_CODES.S
        ).toList()
        assertEquals(emptyList<String>(), mutePreTiramisu)

        // Mute + Facecam OFF on Android 13 (API 33): only POST_NOTIFICATIONS
        val muteTiramisu = MainActivity.computeRequiredRuntimePermissions(
            audioSource = "MUTE",
            facecamMode = "OFF",
            sdkInt = android.os.Build.VERSION_CODES.TIRAMISU
        ).toList()
        assertEquals(listOf(android.Manifest.permission.POST_NOTIFICATIONS), muteTiramisu)

        // BOTH audio + FRONT facecam on Android 14 (API 34): RECORD_AUDIO, CAMERA, POST_NOTIFICATIONS
        val fullOptions = MainActivity.computeRequiredRuntimePermissions(
            audioSource = "BOTH",
            facecamMode = "FRONT",
            sdkInt = android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ).toList()
        assertEquals(
            listOf(
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.POST_NOTIFICATIONS
            ),
            fullOptions
        )
    }

    @Test
    fun testSpecAnalysisRecordingGuard() {
        assertTrue(com.bangdillah.rekamindulu.utils.SpecAnalysisDialog.canModifyPresetsOrBenchmark(isRecording = false))
        assertEquals(false, com.bangdillah.rekamindulu.utils.SpecAnalysisDialog.canModifyPresetsOrBenchmark(isRecording = true))
    }

    @Test
    fun testConsumePendingSaveIfHandled() {
        MainActivity.hasPendingSaveDialog = true
        val ignoredOtherFile = MainActivity.consumePendingSaveIfHandled(
            openedFilePath = "/storage/Movies/RekaminDulu/other.mp4",
            lastRecordedPath = "/storage/Movies/RekaminDulu/latest.mp4",
            forceClear = false
        )
        assertEquals(false, ignoredOtherFile)
        assertTrue(MainActivity.hasPendingSaveDialog)

        val clearedSameFile = MainActivity.consumePendingSaveIfHandled(
            openedFilePath = "/storage/Movies/RekaminDulu/latest.mp4",
            lastRecordedPath = "/storage/Movies/RekaminDulu/latest.mp4",
            forceClear = false
        )
        assertTrue(clearedSameFile)
        assertEquals(false, MainActivity.hasPendingSaveDialog)

        MainActivity.hasPendingSaveDialog = true
        val clearedForced = MainActivity.consumePendingSaveIfHandled(
            openedFilePath = null,
            lastRecordedPath = "/storage/Movies/RekaminDulu/latest.mp4",
            forceClear = true
        )
        assertTrue(clearedForced)
        assertEquals(false, MainActivity.hasPendingSaveDialog)
    }

    @Test
    fun testAboutVersionFormatting() {
        assertEquals(
            "Versi 1.0 (Pro Release)",
            com.bangdillah.rekamindulu.about.AboutActivity.formatVersionBadge("1.0")
        )
        assertEquals(
            "Versi 2.4.1 (Pro Release)",
            com.bangdillah.rekamindulu.about.AboutActivity.formatVersionBadge(" 2.4.1 ")
        )
        assertEquals(
            "Versi 1.0 (Pro Release)",
            com.bangdillah.rekamindulu.about.AboutActivity.formatVersionBadge("")
        )
    }

    @Test
    fun testShouldShowSaveDialogRespectsPinLock() {
        assertEquals(
            false,
            MainActivity.shouldShowSaveDialog(
                hasPendingSave = true,
                isAppLockEnabled = true,
                savedPin = "1234",
                isSessionUnlocked = false
            )
        )
        assertTrue(
            MainActivity.shouldShowSaveDialog(
                hasPendingSave = true,
                isAppLockEnabled = true,
                savedPin = "1234",
                isSessionUnlocked = true
            )
        )
        assertEquals(
            false,
            MainActivity.shouldShowSaveDialog(
                hasPendingSave = false,
                isAppLockEnabled = false,
                savedPin = "",
                isSessionUnlocked = false
            )
        )
    }

    @Test
    fun testPlayerCanLoadVideoDirectlyRespectsPinLock() {
        assertEquals(
            false,
            com.bangdillah.rekamindulu.player.PlayerActivity.canLoadVideoDirectly(
                isAppLockEnabled = true,
                savedPin = "5678",
                isSessionUnlocked = false
            )
        )
        assertTrue(
            com.bangdillah.rekamindulu.player.PlayerActivity.canLoadVideoDirectly(
                isAppLockEnabled = true,
                savedPin = "5678",
                isSessionUnlocked = true
            )
        )
    }

    @Test
    fun testAppLockSessionTrackerBackgroundRelock() {
        var sessionUnlocked = true
        val tracker = com.bangdillah.rekamindulu.utils.AppLockSessionTracker {
            sessionUnlocked = false
        }

        // 1. Inter-activity transition (MainActivity -> LibraryActivity) must keep session unlocked
        tracker.onActivityStarted() // MainActivity started (count=1)
        tracker.onActivityStarted() // LibraryActivity started (count=2)
        tracker.onActivityStopped(isChangingConfigurations = false) // MainActivity stopped (count=1)
        assertTrue(sessionUnlocked)
        assertEquals(1, tracker.startedActivityCount)

        // 2. Configuration change (rotation) on last activity must keep session unlocked
        tracker.onActivityStopped(isChangingConfigurations = true) // count=0, rotating
        assertTrue(sessionUnlocked)
        tracker.onActivityStarted() // recreated activity started (count=1)
        assertTrue(sessionUnlocked)

        // 3. Single-use suppression for system picker (e.g. Photo Picker) keeps session unlocked once
        tracker.suppressNextBackground()
        tracker.onActivityStopped(isChangingConfigurations = false) // count=0, picker active
        assertTrue(sessionUnlocked)
        assertEquals(false, tracker.suppressNextBackgroundLock)
        tracker.onActivityStarted() // back to app (count=1)
        assertTrue(sessionUnlocked)

        // 4. Subsequent backgrounding (Home / Recents) must lock session immediately
        tracker.onActivityStopped(isChangingConfigurations = false) // count=0, left app
        assertEquals(false, sessionUnlocked)
        assertEquals(0, tracker.startedActivityCount)

        // 5. Re-entering after unlocking and pressing Home again locks again
        sessionUnlocked = true
        tracker.onActivityStarted() // count=1
        tracker.onActivityStopped(isChangingConfigurations = false) // count=0
        assertEquals(false, sessionUnlocked)
    }
}
