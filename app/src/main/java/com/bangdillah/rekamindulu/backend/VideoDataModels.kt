package com.bangdillah.rekamindulu.backend

import androidx.annotation.Keep

@Keep
data class UserProfile(
    var uid: String = "",
    var displayName: String = "",
    var email: String = "",
    var photoUrl: String = "",
    var createdAt: String = "",
    var totalRecordings: Int = 0,
    var totalDurationSeconds: Long = 0L,
    var preferredResolution: String = "1080p",
    var preferredFps: Int = 60,
    var gamingModeEnabled: Boolean = true
)

@Keep
data class VideoRecording(
    var id: String = "",
    var userId: String = "",
    var title: String = "",
    var fileName: String = "",
    var durationSeconds: Int = 0,
    var durationFormatted: String = "00:00",
    var fileSizeBytes: Long = 0L,
    var resolution: String = "1080x2400",
    var fps: Int = 60,
    var encoder: String = "H264",
    var hasAudio: Boolean = true,
    var audioSource: String = "MIC",
    var isGamingSession: Boolean = false,
    var createdAt: String = "",
    var localPath: String = "",
    var notes: String = ""
)
