package com.bangdillah.rekamindulu.library

import android.media.MediaMetadataRetriever
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bangdillah.rekamindulu.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LibraryAdapter(
    private val videoFiles: List<File>,
    private val onItemClick: (file: File) -> Unit,
    private val onRenameClick: ((file: File) -> Unit)? = null,
    private val onEditClick: ((file: File) -> Unit)? = null,
    private val onExtractAudioClick: ((file: File) -> Unit)? = null,
    private val onShareClick: ((file: File) -> Unit)? = null,
    private val onDeleteClick: (file: File) -> Unit
) : RecyclerView.Adapter<LibraryAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.library_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = videoFiles[position]
        holder.bind(file)
    }

    override fun getItemCount(): Int = videoFiles.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {

        private val thumbnailView: ImageView = view.findViewById(R.id.thumbnail)
        private val titleView: TextView = view.findViewById(R.id.title)
        private val durationView: TextView = view.findViewById(R.id.duration)
        private val btnPlay: Button? = view.findViewById(R.id.btn_item_play)
        private val btnRename: Button? = view.findViewById(R.id.btn_item_rename)
        private val btnEdit: Button? = view.findViewById(R.id.btn_item_edit)
        private val btnExtractAudio: Button? = view.findViewById(R.id.btn_item_extract_audio)
        private val btnShare: Button? = view.findViewById(R.id.btn_item_share)
        private val btnDelete: Button = view.findViewById(R.id.btn_item_delete)

        fun bind(file: File) {
            titleView.text = file.nameWithoutExtension.replace("_", " ")

            val fileSizeMb = file.length() / (1024.0 * 1024.0)
            val dateFormatted = dateFormat.format(Date(file.lastModified()))

            // Extract duration and resolution from metadata
            var durationText = "00:00"
            var resolutionText = "1080p"
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath)
                val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: "1080"
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: "1920"
                retriever.release()

                val sec = (durMs / 1000).toInt()
                val min = sec / 60
                durationText = String.format(Locale.getDefault(), "%02d:%02d", min, sec % 60)
                resolutionText = "${width}x${height}"
            } catch (e: Exception) {
                // Keep defaults if reading metadata fails
            }

            durationView.text = String.format(
                Locale.getDefault(),
                "%s • %.1f MB • %s\n%s",
                durationText,
                fileSizeMb,
                resolutionText,
                dateFormatted
            )

            Glide.with(itemView.context)
                .asBitmap()
                .load(file)
                .placeholder(R.drawable.ic_video_placeholder)
                .error(R.drawable.ic_video_placeholder)
                .into(thumbnailView)

            itemView.setOnClickListener {
                onItemClick(file)
            }

            btnPlay?.setOnClickListener {
                onItemClick(file)
            }

            if (onRenameClick != null) {
                btnRename?.visibility = View.VISIBLE
                btnRename?.setOnClickListener { onRenameClick.invoke(file) }
            } else {
                btnRename?.visibility = View.GONE
            }

            if (onEditClick != null) {
                btnEdit?.visibility = View.VISIBLE
                btnEdit?.setOnClickListener { onEditClick.invoke(file) }
            } else {
                btnEdit?.visibility = View.GONE
            }

            if (onExtractAudioClick != null) {
                btnExtractAudio?.visibility = View.VISIBLE
                btnExtractAudio?.setOnClickListener { onExtractAudioClick.invoke(file) }
            } else {
                btnExtractAudio?.visibility = View.GONE
            }

            if (onShareClick != null) {
                btnShare?.visibility = View.VISIBLE
                btnShare?.setOnClickListener { onShareClick.invoke(file) }
            } else {
                btnShare?.visibility = View.GONE
            }

            btnDelete.setOnClickListener {
                onDeleteClick(file)
            }
        }
    }
}
