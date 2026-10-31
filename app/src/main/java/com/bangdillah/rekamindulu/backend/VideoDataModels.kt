package com.bangdillah.rekamindulu.backend

import androidx.annotation.Keep
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @get:PropertyName("uid") @set:PropertyName("uid")
    var uid: String = "",

    @get:PropertyName("displayName") @set:PropertyName("displayName")
    var displayName: String = "",

    @get:PropertyName("email") @set:PropertyName("email")
    var email: String = "",

    @get:PropertyName("photoUrl") @set:PropertyName("photoUrl")
    var photoUrl: String = "",

    @get:PropertyName("createdAt") @set:PropertyName("createdAt")
    var createdAt: String = "",

    @get:PropertyName("totalRecordings") @set:PropertyName("totalRecordings")
    var totalRecordings: Int = 0,

    @get:PropertyName("totalDurationSeconds") @set:PropertyName("totalDurationSeconds")
    var totalDurationSeconds: Long = 0L,

    @get:PropertyName("preferredResolution") @set:PropertyName("preferredResolution")
    var preferredResolution: String = "1080p",

    @get:PropertyName("preferredFps") @set:PropertyName("preferredFps")
    var preferredFps: Int = 60,

    @get:PropertyName("gamingModeEnabled") @set:PropertyName("gamingModeEnabled")
    var gamingModeEnabled: Boolean = true
)

@Keep
@IgnoreExtraProperties
data class VideoRecording(
    @get:PropertyName("id") @set:PropertyName("id")
    var id: String = "",

    @get:PropertyName("userId") @set:PropertyName("userId")
    var userId: String = "",

    @get:PropertyName("title") @set:PropertyName("title")
    var title: String = "",

    @get:PropertyName("fileName") @set:PropertyName("fileName")
    var fileName: String = "",

    @get:PropertyName("durationSeconds") @set:PropertyName("durationSeconds")
    var durationSeconds: Int = 0,

    @get:PropertyName("durationFormatted") @set:PropertyName("durationFormatted")
    var durationFormatted: String = "00:00",

    @get:PropertyName("fileSizeBytes") @set:PropertyName("fileSizeBytes")
    var fileSizeBytes: Long = 0L,

    @get:PropertyName("resolution") @set:PropertyName("resolution")
    var resolution: String = "1080x2400",

    @get:PropertyName("fps") @set:PropertyName("fps")
    var fps: Int = 60,

    @get:PropertyName("encoder") @set:PropertyName("encoder")
    var encoder: String = "H264",

    @get:PropertyName("hasAudio") @set:PropertyName("hasAudio")
    var hasAudio: Boolean = true,

    @get:PropertyName("audioSource") @set:PropertyName("audioSource")
    var audioSource: String = "MIC",

    @get:PropertyName("isGamingSession") @set:PropertyName("isGamingSession")
    var isGamingSession: Boolean = false,

    @get:PropertyName("createdAt") @set:PropertyName("createdAt")
    var createdAt: String = "",

    @get:PropertyName("localPath") @set:PropertyName("localPath")
    var localPath: String = "",

    @get:PropertyName("notes") @set:PropertyName("notes")
    var notes: String = ""
)
