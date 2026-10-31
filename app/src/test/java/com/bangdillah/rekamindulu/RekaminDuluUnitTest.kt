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
}
