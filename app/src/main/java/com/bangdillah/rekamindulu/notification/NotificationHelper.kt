package com.bangdillah.rekamindulu.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.bangdillah.rekamindulu.HomeActivity
import com.bangdillah.rekamindulu.R
import com.bangdillah.rekamindulu.library.LibraryActivity
import com.bangdillah.rekamindulu.player.PlayerActivity
import java.io.File
import java.util.Locale

/**
 * NotificationHelper - Mengelola channel notifikasi, status perekaman aktif,
 * dan notifikasi hasil rekaman tersimpan dengan aksi Putar, Galeri, dan Bagikan.
 */
class NotificationHelper(private val context: Context) {

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val TAG = "NotificationHelper"
        const val CHANNEL_RECORDING = "rekam_notifikasi"
        const val CHANNEL_COMPLETED = "rekam_selesai"
        const val NOTIFICATION_ID_RECORDING = 2001
        const val NOTIFICATION_ID_COMPLETED = 1002
    }

    init {
        createNotificationChannels()
    }

    /**
     * Membuat Notification Channel untuk Android 8.0+
     */
    fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val recordingChannel = NotificationChannel(
                CHANNEL_RECORDING,
                "Layanan Perekam Layar Rekamin Dulu",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Status perekaman layar aktif dan kontrol cepat notifikasi persistens"
                setSound(null, null)
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }

            val completedChannel = NotificationChannel(
                CHANNEL_COMPLETED,
                "Hasil Rekaman Video",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Pemberitahuan video rekaman selesai disimpan"
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }

            notificationManager.createNotificationChannel(recordingChannel)
            notificationManager.createNotificationChannel(completedChannel)
        }
    }

    /**
     * Menampilkan notifikasi bahwa rekaman berhasil disimpan dengan aksi langsung
     * untuk memutar, membuka galeri, atau membagikan video.
     */
    fun showRecordingCompletedNotification(file: File) {
        if (!file.exists()) return

        val sizeMb = file.length() / (1024.0 * 1024.0)
        val sizeStr = if (sizeMb >= 1024.0) {
            String.format(Locale.getDefault(), "%.2f GB", sizeMb / 1024.0)
        } else {
            String.format(Locale.getDefault(), "%.1f MB", sizeMb)
        }

        // 1. Klik konten -> Buka Player
        val playerIntent = Intent(context, PlayerActivity::class.java).apply {
            putExtra("VIDEO_PATH", file.absolutePath)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingPlayer = PendingIntent.getActivity(
            context,
            201,
            playerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 2. Aksi Galeri
        val libraryIntent = Intent(context, LibraryActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingLibrary = PendingIntent.getActivity(
            context,
            202,
            libraryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 3. Aksi Bagikan
        var pendingShare: PendingIntent? = null
        try {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, "Bagikan Rekaman")
            chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            pendingShare = PendingIntent.getActivity(
                context,
                203,
                chooser,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating share pending intent", e)
        }

        try {
            val builder = NotificationCompat.Builder(context, CHANNEL_COMPLETED)
                .setSmallIcon(R.drawable.ic_record)
                .setContentTitle("Rekaman Tersimpan! 🎉")
                .setContentText("${file.name} ($sizeStr) • Ketuk untuk memutar")
                .setContentIntent(pendingPlayer)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .addAction(R.drawable.ic_play_arrow, "Putar", pendingPlayer)
                .addAction(R.drawable.ic_folder, "Galeri", pendingLibrary)

            if (pendingShare != null) {
                builder.addAction(R.drawable.ic_share, "Bagikan", pendingShare)
            }

            notificationManager.notify(NOTIFICATION_ID_COMPLETED, builder.build())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to display completed notification", e)
        }
    }
}