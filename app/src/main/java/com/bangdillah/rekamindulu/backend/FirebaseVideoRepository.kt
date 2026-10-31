package com.bangdillah.rekamindulu.backend

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import com.bangdillah.rekamindulu.R
import com.bangdillah.rekamindulu.utils.AppPreferences
import com.bangdillah.rekamindulu.utils.StorageHelper
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * FirebaseVideoRepository - Mengelola katalog data rekaman video di Google Cloud Firestore,
 * sinkronisasi otomatis file rekaman lokal ke cloud, dan agregasi statistik sesi pengguna.
 */
class FirebaseVideoRepository(private val db: FirebaseFirestore) {

    companion object {
        private const val TAG = "FirebaseVideoRepository"
    }

    /**
     * Konstruktor sekunder yang aman untuk Context sesuai arsitektur firebase-firestore-android,
     * mengambil database ID kustom dari firebase_applet_config.xml.
     */
    constructor(context: Context) : this(
        try {
            val dbId = context.applicationContext.getString(R.string.firestore_database_id)
            if (dbId.isNotBlank()) FirebaseFirestore.getInstance(dbId) else FirebaseFirestore.getInstance()
        } catch (e: Exception) {
            FirebaseFirestore.getInstance()
        }
    )

    /**
     * Menyimpan atau memperbarui metadata rekaman video ke Firestore pada path:
     * /users/{userId}/recordings/{recordingId}
     */
    suspend fun saveRecording(video: VideoRecording) {
        if (video.userId.isBlank() || video.id.isBlank()) {
            Log.w(TAG, "saveRecording dibatalkan: userId atau videoId kosong")
            return
        }

        try {
            val recordingRef = db.collection("users")
                .document(video.userId)
                .collection("recordings")
                .document(video.id)

            recordingRef.set(video, SetOptions.merge()).await()

            // Update statistik pengguna secara atomik di dokumen profil dengan merge agar aman jika dokumen belum ada
            val userRef = db.collection("users").document(video.userId)
            userRef.set(
                mapOf(
                    "totalRecordings" to FieldValue.increment(1),
                    "totalDurationSeconds" to FieldValue.increment(video.durationSeconds.toLong())
                ),
                SetOptions.merge()
            ).await()

            Log.i(TAG, "Video berhasil dicatat ke backend cloud: ${video.title} (${video.id})")
        } catch (e: Exception) {
            Log.e(TAG, "Gagal mencatat video ke Firestore: ${e.message}", e)
        }
    }

    /**
     * Sinkronisasi file rekaman tunggal yang baru selesai direkam atau diedit.
     */
    suspend fun syncSingleFile(context: Context, userId: String, file: File): Boolean {
        if (userId.isBlank() || !file.exists() || file.length() < 10240) return false
        val docId = file.nameWithoutExtension.replace("[^a-zA-Z0-9_-]".toRegex(), "_").ifBlank { "rec_${file.lastModified()}" }
        val prefs = AppPreferences(context)

        var durationMs = 0L
        var width = 0
        var height = 0
        var retriever: MediaMetadataRetriever? = null
        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        } catch (ignored: Exception) {
        } finally {
            try { retriever?.release() } catch (ignored: Exception) {}
        }

        val durSec = (durationMs / 1000).toInt()
        val min = durSec / 60
        val sec = durSec % 60
        val formattedDur = String.format(Locale.getDefault(), "%02d:%02d", min, sec)
        val timeStamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).format(Date(file.lastModified()))
        val resString = if (width > 0 && height > 0) "${width}x${height}" else prefs.resolution

        val recording = VideoRecording(
            id = docId,
            userId = userId,
            title = file.nameWithoutExtension.replace("_", " "),
            fileName = file.name,
            durationSeconds = durSec,
            durationFormatted = formattedDur,
            fileSizeBytes = file.length(),
            resolution = resString,
            fps = prefs.fps,
            encoder = prefs.videoEncoder,
            hasAudio = prefs.audioSource != "MUTE",
            audioSource = prefs.audioSource,
            isGamingSession = prefs.isGamingMode,
            createdAt = timeStamp,
            localPath = file.absolutePath,
            notes = "Rekaman tersimpan rapi & disinkronisasi ke Cloud Backend"
        )

        return try {
            val docRef = db.collection("users")
                .document(userId)
                .collection("recordings")
                .document(docId)
            docRef.set(recording, SetOptions.merge()).await()

            val userRef = db.collection("users").document(userId)
            userRef.set(
                mapOf(
                    "totalRecordings" to FieldValue.increment(1),
                    "totalDurationSeconds" to FieldValue.increment(durSec.toLong())
                ),
                SetOptions.merge()
            ).await()
            Log.i(TAG, "File tunggal berhasil disinkronkan ke cloud: ${file.name}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Gagal sinkron file tunggal ${file.name}: ${e.message}")
            false
        }
    }

    /**
     * Memantau perubahan katalog rekaman video pengguna secara real-time via Firestore snapshot listener.
     */
    fun observeRecordings(userId: String): Flow<List<VideoRecording>> = callbackFlow {
        if (userId.isBlank()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val registration = db.collection("users")
            .document(userId)
            .collection("recordings")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Error saat mendengarkan rekaman Firestore", error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val recordings = snapshot.documents.mapNotNull { doc ->
                        doc.toObject(VideoRecording::class.java)?.apply {
                            if (id.isBlank()) id = doc.id
                        }
                    }
                    trySend(recordings)
                }
            }

        awaitClose { registration.remove() }
    }

    /**
     * Menghapus rekaman dari Firestore.
     */
    suspend fun deleteRecording(userId: String, recordingId: String, durationSeconds: Int = 0) {
        if (userId.isBlank() || recordingId.isBlank()) return
        try {
            db.collection("users")
                .document(userId)
                .collection("recordings")
                .document(recordingId)
                .delete()
                .await()
            Log.i(TAG, "Rekaman $recordingId berhasil dihapus dari cloud")

            val userRef = db.collection("users").document(userId)
            val decrementMap = mutableMapOf<String, Any>(
                "totalRecordings" to FieldValue.increment(-1)
            )
            if (durationSeconds > 0) {
                decrementMap["totalDurationSeconds"] = FieldValue.increment(-durationSeconds.toLong())
            }
            userRef.set(decrementMap, SetOptions.merge()).await()
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menghapus rekaman $recordingId dari cloud", e)
        }
    }

    /**
     * Memperbarui judul dan catatan rekaman.
     */
    suspend fun updateRecordingInfo(userId: String, recordingId: String, newTitle: String, notes: String = "") {
        if (userId.isBlank() || recordingId.isBlank()) return
        try {
            val updates = mutableMapOf<String, Any>("title" to newTitle)
            if (notes.isNotBlank()) {
                updates["notes"] = notes
            }
            db.collection("users")
                .document(userId)
                .collection("recordings")
                .document(recordingId)
                .set(updates, SetOptions.merge())
                .await()
        } catch (e: Exception) {
            Log.e(TAG, "Gagal update info rekaman: ${e.message}", e)
        }
    }

    /**
     * Sinkronisasi seluruh file rekaman lokal di perangkat ke katalog Firestore cloud.
     * Sangat berguna saat pengguna baru login agar seluruh rekaman offline sebelumnya langsung
     * terindeks rapi di backend cloud.
     */
    suspend fun syncAllLocalRecordings(context: Context, userId: String, onProgress: ((synced: Int, total: Int) -> Unit)? = null) {
        if (userId.isBlank()) return

        val localFiles = StorageHelper.getAllVideoFiles(context)
        val prefs = AppPreferences(context)
        var count = 0

        for (file in localFiles) {
            if (!file.exists() || file.length() < 10240) continue

            val docId = file.nameWithoutExtension.replace("[^a-zA-Z0-9_-]".toRegex(), "_").ifBlank { "rec_${file.lastModified()}" }
            val docRef = db.collection("users")
                .document(userId)
                .collection("recordings")
                .document(docId)

            val existing = try {
                docRef.get().await()
            } catch (ignored: Exception) {
                null
            }

            if (existing == null || !existing.exists()) {
                var durationMs = 0L
                var width = 0
                var height = 0
                var retriever: MediaMetadataRetriever? = null
                try {
                    retriever = MediaMetadataRetriever()
                    retriever.setDataSource(file.absolutePath)
                    durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                } catch (ignored: Exception) {
                } finally {
                    try { retriever?.release() } catch (ignored: Exception) {}
                }

                val durSec = (durationMs / 1000).toInt()
                val min = durSec / 60
                val sec = durSec % 60
                val formattedDur = String.format(Locale.getDefault(), "%02d:%02d", min, sec)
                val timeStamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).format(Date(file.lastModified()))
                val resString = if (width > 0 && height > 0) "${width}x${height}" else prefs.resolution

                val recording = VideoRecording(
                    id = docId,
                    userId = userId,
                    title = file.nameWithoutExtension.replace("_", " "),
                    fileName = file.name,
                    durationSeconds = durSec,
                    durationFormatted = formattedDur,
                    fileSizeBytes = file.length(),
                    resolution = resString,
                    fps = prefs.fps,
                    encoder = prefs.videoEncoder,
                    hasAudio = prefs.audioSource != "MUTE",
                    audioSource = prefs.audioSource,
                    isGamingSession = prefs.isGamingMode,
                    createdAt = timeStamp,
                    localPath = file.absolutePath,
                    notes = "Rekaman tersimpan rapi & disinkronisasi ke Cloud Backend"
                )

                try {
                    docRef.set(recording, SetOptions.merge()).await()
                    count++
                } catch (e: Exception) {
                    Log.w(TAG, "Gagal sinkron file ${file.name}: ${e.message}")
                }
            }
            onProgress?.invoke(count, localFiles.size)
        }
        Log.i(TAG, "Sinkronisasi rekaman lokal selesai: $count file baru dicatat ke cloud")
    }
}
