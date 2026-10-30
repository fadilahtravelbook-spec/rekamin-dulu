package com.bangdillah.rekamindulu.trim

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * TrimHelper - Pemotong video MP4 native menggunakan MediaExtractor & MediaMuxer.
 * Cepat, hemat daya, tanpa re-encoding, dan menghasilkan file MP4 valid.
 */
object TrimHelper {

    private const val TAG = "TrimHelper"
    private const val DEFAULT_BUFFER_SIZE = 1024 * 1024 // 1 MB buffer

    /**
     * Memotong video dari startMs sampai endMs dan menyimpannya ke outputFile.
     * @return Pair<Boolean, String> (status sukses, pesan hasil)
     */
    fun trimVideo(
        inputFile: File,
        outputFile: File,
        startMs: Long,
        endMs: Long,
        progressCallback: ((percent: Int) -> Unit)? = null
    ): Pair<Boolean, String> {
        if (!inputFile.exists()) {
            return Pair(false, "File sumber tidak ditemukan")
        }

        val startUs = (startMs * 1000).coerceAtLeast(0L)
        val endUs = (endMs * 1000).coerceAtLeast(startUs + 500_000L)

        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            extractor = MediaExtractor()
            extractor.setDataSource(inputFile.absolutePath)

            val trackCount = extractor.trackCount
            if (trackCount == 0) {
                return Pair(false, "Format video tidak valid (tidak ada trek audio/video)")
            }

            if (outputFile.exists()) {
                outputFile.delete()
            }
            outputFile.parentFile?.mkdirs()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val indexMap = HashMap<Int, Int>()
            var videoTrackIndex = -1

            // Set up tracks
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""

                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                }

                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    val muxerTrack = muxer.addTrack(format)
                    indexMap[i] = muxerTrack
                }
            }

            if (indexMap.isEmpty()) {
                return Pair(false, "Tidak ada trek video/audio yang didukung untuk dipotong")
            }

            // Atur orientasi rotasi dari metadata
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(inputFile.absolutePath)
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                if (!rotation.isNullOrEmpty()) {
                    muxer.setOrientationHint(rotation.toInt())
                }
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "Gagal membaca rotasi video", e)
            }

            muxer.start()
            muxerStarted = true

            // Seek ke keyframe sync terdekat sebelum startUs
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            // Hitung buffer size optimal dari KEY_MAX_INPUT_SIZE setiap track (min 2MB untuk 1080p/4K)
            var maxInputSize = 2 * 1024 * 1024
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    val size = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                    if (size > maxInputSize) {
                        maxInputSize = size
                    }
                }
            }
            val buffer = ByteBuffer.allocateDirect(maxInputSize)
            val bufferInfo = MediaCodec.BufferInfo()
            val totalDurationUs = (endUs - startUs).coerceAtLeast(1L)

            val firstPtsMap = HashMap<Int, Long>()
            val lastWrittenPtsMap = HashMap<Int, Long>()
            var videoKeyFrameFound = (videoTrackIndex == -1)

            while (true) {
                val trackIndex = extractor.sampleTrackIndex
                if (trackIndex < 0) break

                val muxerTrackIndex = indexMap[trackIndex]
                if (muxerTrackIndex == null) {
                    extractor.advance()
                    continue
                }

                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) {
                    break
                }

                val sampleTime = extractor.sampleTime
                val flags = extractor.sampleFlags

                // Stop condition: frame beyond endUs
                if (sampleTime > endUs) {
                    if (trackIndex == videoTrackIndex || videoTrackIndex == -1) {
                        break
                    }
                    extractor.advance()
                    continue
                }

                // Video track MUST start with a keyframe so decoders don't fail/show black screen
                if (trackIndex == videoTrackIndex && !videoKeyFrameFound) {
                    if ((flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                        videoKeyFrameFound = true
                    } else {
                        extractor.advance()
                        continue
                    }
                }

                // Don't start writing audio before the first video keyframe
                if (!videoKeyFrameFound && videoTrackIndex != -1) {
                    extractor.advance()
                    continue
                }

                if (bufferInfo.size > 0) {
                    val firstPts = firstPtsMap.getOrPut(muxerTrackIndex) { sampleTime }
                    val rebasedPts = (sampleTime - firstPts).coerceAtLeast(0L)
                    val lastPts = lastWrittenPtsMap[muxerTrackIndex] ?: -1L
                    val finalPts = if (rebasedPts > lastPts) rebasedPts else lastPts + 1000L
                    val codecFlags = if ((flags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }

                    bufferInfo.presentationTimeUs = finalPts
                    bufferInfo.flags = codecFlags
                    bufferInfo.offset = 0

                    muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                    lastWrittenPtsMap[muxerTrackIndex] = finalPts

                    val currentProgressUs = (sampleTime - startUs).coerceAtLeast(0L)
                    val progressPercent = ((currentProgressUs * 100) / totalDurationUs).toInt().coerceIn(0, 100)
                    progressCallback?.invoke(progressPercent)
                }

                extractor.advance()
            }

            progressCallback?.invoke(100)
            return Pair(true, "Video berhasil dipotong dan disimpan!")

        } catch (e: Exception) {
            Log.e(TAG, "Gagal memotong video", e)
            outputFile.delete()
            return Pair(false, "Gagal memotong video: ${e.localizedMessage ?: "Kesalahan I/O"}")
        } finally {
            if (muxerStarted) {
                try {
                    muxer?.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping muxer", e)
                }
            }
            try {
                muxer?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing muxer", e)
            }
            try {
                extractor?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing extractor", e)
            }
        }
    }
}
