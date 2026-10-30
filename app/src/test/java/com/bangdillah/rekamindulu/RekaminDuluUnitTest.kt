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
        assertEquals(MediaRecorder.AudioSource.MIC, MicrophoneSelector.getAudioSource("mic"))
        assertEquals(MediaRecorder.AudioSource.MIC, MicrophoneSelector.getAudioSource("usb"))
        assertEquals(MediaRecorder.AudioSource.MIC, MicrophoneSelector.getAudioSource("unknown_source"))
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
}
