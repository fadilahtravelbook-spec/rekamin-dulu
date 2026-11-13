package com.bangdillah.rekamindulu.backend

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Local in-memory stub for FirebaseVideoRepository when Firebase is not configured.
 */
class FirebaseVideoRepository(@Suppress("UNUSED_PARAMETER") context: Context) {

    private val recordings = MutableStateFlow<List<VideoRecording>>(emptyList())

    suspend fun saveRecording(video: VideoRecording) {
        if (video.id.isBlank()) return
        recordings.value = (recordings.value.filterNot { it.id == video.id } + video)
            .sortedByDescending { it.createdAt }
    }

    suspend fun syncSingleFile(
        @Suppress("UNUSED_PARAMETER") context: Context,
        @Suppress("UNUSED_PARAMETER") userId: String,
        @Suppress("UNUSED_PARAMETER") file: File
    ): Boolean = false

    fun observeRecordings(userId: String): Flow<List<VideoRecording>> {
        return recordings.map { list ->
            if (userId.isBlank()) emptyList() else list.filter { it.userId == userId }
        }
    }

    suspend fun deleteRecording(
        @Suppress("UNUSED_PARAMETER") userId: String,
        recordingId: String,
        @Suppress("UNUSED_PARAMETER") durationSeconds: Int = 0
    ) {
        if (recordingId.isBlank()) return
        recordings.value = recordings.value.filterNot { it.id == recordingId }
    }

    suspend fun updateRecordingInfo(
        @Suppress("UNUSED_PARAMETER") userId: String,
        recordingId: String,
        newTitle: String,
        notes: String = ""
    ) {
        recordings.value = recordings.value.map { item ->
            if (item.id == recordingId) {
                item.copy(
                    title = newTitle,
                    notes = if (notes.isNotBlank()) notes else item.notes
                )
            } else {
                item
            }
        }
    }

    suspend fun syncAllLocalRecordings(
        @Suppress("UNUSED_PARAMETER") context: Context,
        @Suppress("UNUSED_PARAMETER") userId: String,
        onProgress: ((synced: Int, total: Int) -> Unit)? = null
    ) {
        onProgress?.invoke(0, 0)
    }
}
