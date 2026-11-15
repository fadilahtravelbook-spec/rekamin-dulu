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
     * Membuat file output pemotongan tanpa penumpukan prefiks "Trim_Trim_..."
     * serta menghindari tabrakan nama jika dipanggil berulang pada detik yang sama.
     */
    fun createTrimOutputFile(outputDir: File, rawSourceName: String, timeStamp: String): File {
        var stripped = rawSourceName.trim()
        while (stripped.startsWith("Trim_", ignoreCase = true)) {
            stripped = stripped.substring(5)
        }
        val cleanBase = stripped
            .replace("[^a-zA-Z0-9._-]".toRegex(), "_")
            .trim('_')
            .ifEmpty { "Video" }
            .take(40)
        val baseName = "Trim_${cleanBase}_$timeStamp"
        var candidate = File(outputDir, "$baseName.mp4")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(outputDir, "${baseName}_$counter.mp4")
            counter++
        }
        return candidate
    }

    fun isTrimOutputValid(
        muxerStopSucceeded: Boolean,
        fileExists: Boolean,
        fileLengthBytes: Long
    ): Boolean {
        return muxerStopSucceeded && fileExists && fileLengthBytes >= 1024L
    }

    /**
     * Memilih mode seek keyframe terbaik: gunakan SEEK_TO_CLOSEST_SYNC apabila lebih dekat ke [startUs]
     * dan tidak melampaui batas aman sebelum [endUs] (minimal tersisa 500ms klip).
     * Jika terlalu dekat ke akhir atau melebihi margin 500ms, fallback ke SEEK_TO_PREVIOUS_SYNC.
     */
    fun chooseOptimalSeekMode(
        startUs: Long,
        endUs: Long,
        prevSyncUs: Long,
        closestSyncUs: Long
    ): Int {
        if (closestSyncUs < 0L) return MediaExtractor.SEEK_TO_PREVIOUS_SYNC
        val leavesMinClip = closestSyncUs <= (endUs - 500_000L)
        val withinForwardTolerance = closestSyncUs <= (startUs + 500_000L)
        val closerThanPrev = prevSyncUs < 0L ||
            kotlin.math.abs(closestSyncUs - startUs) < kotlin.math.abs(startUs - prevSyncUs)
        return if (leavesMinClip && withinForwardTolerance && closerThanPrev) {
            MediaExtractor.SEEK_TO_CLOSEST_SYNC
        } else {
            MediaExtractor.SEEK_TO_PREVIOUS_SYNC
        }
    }

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

            // Bandingkan keyframe terdekat (CLOSEST_SYNC vs PREVIOUS_SYNC) agar hasil potong akurat tanpa memotong durasi klip
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val prevSyncUs = extractor.sampleTime
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val closestSyncUs = extractor.sampleTime
            val chosenSeekMode = chooseOptimalSeekMode(startUs, endUs, prevSyncUs, closestSyncUs)
            extractor.seekTo(startUs, chosenSeekMode)

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

            var referenceBasePts = -1L
            val lastWrittenPtsMap = HashMap<Int, Long>()
            var videoKeyFrameFound = (videoTrackIndex == -1)
            val tracksEnded = HashSet<Int>()

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

                // Stop condition: track beyond endUs
                if (sampleTime > endUs) {
                    tracksEnded.add(trackIndex)
                    if (tracksEnded.size >= indexMap.size || sampleTime > endUs + 500_000L) {
                        break
                    }
                    extractor.advance()
                    continue
                }

                // Video track MUST start with a keyframe so decoders don't fail/show black screen
                if (trackIndex == videoTrackIndex && !videoKeyFrameFound) {
                    if ((flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 || (flags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        videoKeyFrameFound = true
                        referenceBasePts = sampleTime
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

                if (referenceBasePts == -1L) {
                    referenceBasePts = sampleTime
                }

                // Audio frames before referenceBasePts must be dropped to preserve perfect lipsync
                if (sampleTime < referenceBasePts) {
                    extractor.advance()
                    continue
                }

                if (bufferInfo.size > 0) {
                    val rebasedPts = (sampleTime - referenceBasePts).coerceAtLeast(0L)
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

            if (lastWrittenPtsMap.isEmpty()) {
                if (outputFile.exists()) outputFile.delete()
                return Pair(false, "Rentang waktu potong tidak menghasilkan sampel video yang valid")
            }

            var muxerStopSucceeded = false
            if (muxerStarted) {
                muxerStarted = false
                muxer.stop()
                muxerStopSucceeded = true
            }

            if (!isTrimOutputValid(
                    muxerStopSucceeded = muxerStopSucceeded,
                    fileExists = outputFile.exists(),
                    fileLengthBytes = outputFile.length()
                )
            ) {
                if (outputFile.exists()) outputFile.delete()
                return Pair(false, "Hasil pemotongan video tidak valid setelah finalisasi")
            }

            progressCallback?.invoke(100)
            return Pair(true, "Video berhasil dipotong dan disimpan!")

        } catch (e: Exception) {
            Log.e(TAG, "Gagal memotong video", e)
            if (outputFile.exists()) outputFile.delete()
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
