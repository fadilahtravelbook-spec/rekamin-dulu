package com.bangdillah.rekamindulu.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.bangdillah.rekamindulu.R
import com.bangdillah.rekamindulu.library.LibraryActivity
import com.bangdillah.rekamindulu.player.PlayerActivity
import com.bangdillah.rekamindulu.trim.TrimActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File
import java.util.Locale

object SaveTransitionDialog {

    private const val TAG = "SaveTransitionDialog"
    private val mainHandler = Handler(Looper.getMainLooper())

    fun show(context: Context, videoPath: String, onDismissed: (() -> Unit)? = null) {
        val file = File(videoPath)
        if (!file.exists()) return
        if (context is Activity && (context.isFinishing || context.isDestroyed)) return

        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.layout_save_transition_dialog, null)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        UiHelper.applySystemBarInsets(anchorView = view, bottomView = view)

        val imgThumbnail = view.findViewById<ImageView>(R.id.img_save_dialog_thumbnail)
        val textTitle = view.findViewById<TextView>(R.id.text_save_dialog_title)
        val textStats = view.findViewById<TextView>(R.id.text_save_dialog_stats)
        val textDurationBadge = view.findViewById<TextView>(R.id.text_save_dialog_overlay_duration)

        val sizeMb = file.length() / (1024.0 * 1024.0)
        val sizeFormatted = if (sizeMb >= 1024.0) {
            String.format(Locale.getDefault(), "%.2f GB", sizeMb / 1024.0)
        } else {
            String.format(Locale.getDefault(), "%.1f MB", sizeMb)
        }

        textTitle.text = file.name
        imgThumbnail.setImageResource(R.drawable.ic_video_placeholder)
        textDurationBadge.text = "--:--"
        textStats.text = "Durasi: --:-- • Ukuran: $sizeFormatted"

        // Ekstrak metadata & frame thumbnail di background thread agar UI thread tidak macet
        Thread {
            var durationMs = 0L
            var width = 0
            var height = 0
            var thumbnailBitmap: Bitmap? = null
            var retriever: MediaMetadataRetriever? = null
            try {
                retriever = MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath)
                val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                durationMs = durStr?.toLongOrNull() ?: 0L
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val hasVideo = "yes".equals(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO),
                    ignoreCase = true
                )
                if (hasVideo && width > 0 && height > 0 && durationMs > 0 && file.length() > 10240) {
                    val targetUs = if (durationMs > 1000L) 500_000L else 0L
                    thumbnailBitmap = retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error extracting metadata/thumbnail from ${file.name}", e)
            } finally {
                try { retriever?.release() } catch (ignored: Exception) {}
            }

            val durFormatted = UiHelper.formatDurationMs(durationMs)
            val resInfo = if (width > 0 && height > 0) " • ${width}x${height}" else ""

            mainHandler.post {
                val activityAlive = context !is Activity || (!context.isFinishing && !context.isDestroyed)
                if (activityAlive && dialog.isShowing) {
                    if (thumbnailBitmap != null) {
                        imgThumbnail.setImageBitmap(thumbnailBitmap)
                    }
                    textDurationBadge.text = durFormatted
                    textStats.text = "Durasi: $durFormatted • Ukuran: $sizeFormatted$resInfo"
                } else {
                    thumbnailBitmap?.recycle()
                }
            }
        }.start()

        // Play Button
        view.findViewById<Button>(R.id.btn_save_dialog_play)?.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra("VIDEO_PATH", file.absolutePath)
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
        }

        // Trim Button
        view.findViewById<Button>(R.id.btn_save_dialog_trim)?.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(context, TrimActivity::class.java).apply {
                putExtra("VIDEO_PATH", file.absolutePath)
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
        }

        // Share Button
        view.findViewById<Button>(R.id.btn_save_dialog_share)?.setOnClickListener {
            try {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(shareIntent, "Bagikan Rekaman Layar").apply {
                    if (context !is Activity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                context.startActivity(chooser)
            } catch (e: Exception) {
                Log.w(TAG, "Share video failed", e)
                Toast.makeText(
                    context,
                    "Gagal membagikan video: ${e.localizedMessage ?: "File tidak dapat diakses"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // Library Button
        view.findViewById<Button>(R.id.btn_save_dialog_library)?.setOnClickListener {
            dialog.dismiss()
            if (context !is LibraryActivity) {
                val intent = Intent(context, LibraryActivity::class.java).apply {
                    if (context !is Activity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                context.startActivity(intent)
            }
        }

        dialog.setOnDismissListener {
            onDismissed?.invoke()
        }

        try {
            if (context is Activity && !context.isFinishing && !context.isDestroyed) {
                dialog.show()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot show SaveTransitionDialog: ${e.message}")
        }
    }
}
