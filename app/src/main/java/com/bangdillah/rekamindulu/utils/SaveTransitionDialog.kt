package com.bangdillah.rekamindulu.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.FileProvider
import com.bangdillah.rekamindulu.R
import com.bangdillah.rekamindulu.library.LibraryActivity
import com.bangdillah.rekamindulu.player.PlayerActivity
import com.bangdillah.rekamindulu.trim.TrimActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File
import java.util.Locale

object SaveTransitionDialog {

    fun show(context: Context, videoPath: String, onDismissed: (() -> Unit)? = null) {
        val file = File(videoPath)
        if (!file.exists()) return

        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.layout_save_transition_dialog, null)
        dialog.setContentView(view)

        val imgThumbnail = view.findViewById<ImageView>(R.id.img_save_dialog_thumbnail)
        val textTitle = view.findViewById<TextView>(R.id.text_save_dialog_title)
        val textStats = view.findViewById<TextView>(R.id.text_save_dialog_stats)
        val textDurationBadge = view.findViewById<TextView>(R.id.text_save_dialog_overlay_duration)

        textTitle.text = file.name

        // Compute metadata
        var durationMs = 0L
        var width = 0
        var height = 0
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationMs = durStr?.toLongOrNull() ?: 0L
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0

            val frame = retriever.getFrameAtTime((durationMs * 500).coerceAtLeast(0L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (frame != null) {
                imgThumbnail.setImageBitmap(frame)
            }
            retriever.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val totalSec = (durationMs / 1000).toInt()
        val min = totalSec / 60
        val sec = totalSec % 60
        val durFormatted = String.format(Locale.getDefault(), "%02d:%02d", min, sec)
        textDurationBadge.text = durFormatted

        val sizeMb = file.length() / (1024.0 * 1024.0)
        val sizeFormatted = if (sizeMb >= 1024.0) {
            String.format(Locale.getDefault(), "%.2f GB", sizeMb / 1024.0)
        } else {
            String.format(Locale.getDefault(), "%.1f MB", sizeMb)
        }

        val resInfo = if (width > 0 && height > 0) " • ${width}x${height}" else ""
        textStats.text = "Durasi: $durFormatted • Ukuran: $sizeFormatted$resInfo"

        // Play Button
        view.findViewById<Button>(R.id.btn_save_dialog_play)?.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra("VIDEO_PATH", file.absolutePath)
            }
            context.startActivity(intent)
        }

        // Trim Button
        view.findViewById<Button>(R.id.btn_save_dialog_trim)?.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(context, TrimActivity::class.java).apply {
                putExtra("VIDEO_PATH", file.absolutePath)
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
                context.startActivity(Intent.createChooser(shareIntent, "Bagikan Rekaman Layar"))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Library Button
        view.findViewById<Button>(R.id.btn_save_dialog_library)?.setOnClickListener {
            dialog.dismiss()
            if (context !is LibraryActivity) {
                val intent = Intent(context, LibraryActivity::class.java)
                context.startActivity(intent)
            }
        }

        dialog.setOnDismissListener {
            onDismissed?.invoke()
        }

        if (context is Activity && !context.isFinishing && !context.isDestroyed) {
            dialog.show()
        }
    }
}
