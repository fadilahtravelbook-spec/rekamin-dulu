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
import com.bumptech.glide.load.resource.bitmap.VideoDecoder
import com.bumptech.glide.request.RequestOptions
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

    companion object {
        data class MetaItem(val durationText: String, val resolutionText: String, val hasVideo: Boolean, val durMs: Long)
        private val metadataCache = HashMap<String, MetaItem>()
        private val metadataExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)

        fun invalidateCache(filePath: String) {
            metadataCache.remove(filePath)
        }
    }

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
            itemView.tag = file.absolutePath
            titleView.text = file.nameWithoutExtension.replace("_", " ")

            val fileSizeMb = file.length() / (1024.0 * 1024.0)
            val dateFormatted = dateFormat.format(Date(file.lastModified()))

            // Extract duration and resolution from metadata (cached to ensure smooth 60 FPS scrolling)
            val cached = metadataCache[file.absolutePath]

            if (cached != null) {
                durationView.text = String.format(
                    Locale.getDefault(),
                    "%s • %.1f MB • %s\n%s",
                    cached.durationText,
                    fileSizeMb,
                    cached.resolutionText,
                    dateFormatted
                )
                if (file.length() > 10240 && cached.hasVideo) {
                    val targetUs = if (cached.durMs > 1000L) 500000L else 0L
                    val requestOptions = RequestOptions()
                        .frame(targetUs)
                        .set(VideoDecoder.FRAME_OPTION, MediaMetadataRetriever.OPTION_CLOSEST)

                    Glide.with(itemView.context)
                        .asBitmap()
                        .apply(requestOptions)
                        .load(file)
                        .placeholder(R.drawable.ic_video_placeholder)
                        .error(R.drawable.ic_video_placeholder)
                        .into(thumbnailView)
                } else {
                    thumbnailView.setImageResource(R.drawable.ic_video_placeholder)
                }
            } else {
                durationView.text = String.format(
                    Locale.getDefault(),
                    "%.1f MB\n%s",
                    fileSizeMb,
                    dateFormatted
                )
                thumbnailView.setImageResource(R.drawable.ic_video_placeholder)

                metadataExecutor.execute {
                    var retriever: MediaMetadataRetriever? = null
                    val itemMeta = try {
                        retriever = MediaMetadataRetriever()
                        retriever.setDataSource(file.absolutePath)
                        val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        val hasVideo = "yes".equals(
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO),
                            ignoreCase = true
                        )

                        val sec = (durMs / 1000).toInt()
                        val min = sec / 60
                        val durationText = String.format(Locale.getDefault(), "%02d:%02d", min, sec % 60)
                        val resolutionText = if (width != null && height != null) "${width}x${height}" else "1080p"
                        MetaItem(durationText, resolutionText, hasVideo && width != null, durMs)
                    } catch (e: Exception) {
                        MetaItem("00:00", "1080p", false, 0L)
                    } finally {
                        try { retriever?.release() } catch (ignored: Exception) {}
                    }

                    metadataCache[file.absolutePath] = itemMeta

                    itemView.post {
                        if (itemView.tag == file.absolutePath) {
                            durationView.text = String.format(
                                Locale.getDefault(),
                                "%s • %.1f MB • %s\n%s",
                                itemMeta.durationText,
                                fileSizeMb,
                                itemMeta.resolutionText,
                                dateFormatted
                            )
                            if (file.length() > 10240 && itemMeta.hasVideo) {
                                val targetUs = if (itemMeta.durMs > 1000L) 500000L else 0L
                                val requestOptions = RequestOptions()
                                    .frame(targetUs)
                                    .set(VideoDecoder.FRAME_OPTION, MediaMetadataRetriever.OPTION_CLOSEST)

                                Glide.with(itemView.context)
                                    .asBitmap()
                                    .apply(requestOptions)
                                    .load(file)
                                    .placeholder(R.drawable.ic_video_placeholder)
                                    .error(R.drawable.ic_video_placeholder)
                                    .into(thumbnailView)
                            }
                        }
                    }
                }
            }

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
