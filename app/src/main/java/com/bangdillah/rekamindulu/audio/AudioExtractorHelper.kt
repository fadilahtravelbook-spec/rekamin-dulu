package com.bangdillah.rekamindulu.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.util.Log
import com.bangdillah.rekamindulu.utils.StorageHelper
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AudioExtractorHelper - Mengekstrak trek audio dari video rekaman MP4
 * menjadi file audio .m4a (AAC) mandiri secara cepat tanpa re-encoding.
 */
object AudioExtractorHelper {

    private const val TAG = "AudioExtractorHelper"
    private const val BUFFER_SIZE = 512 * 1024 // 512 KB

    fun extractAudio(
        context: Context,
        inputFile: File,
        progressCallback: ((percent: Int) -> Unit)? = null
    ): Pair<Boolean, File?> {
        if (!inputFile.exists()) {
            return Pair(false, null)
        }

        val outputDir = StorageHelper.getRecordingDirectory(context)
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val outputName = "Audio_${inputFile.nameWithoutExtension}_$timeStamp.m4a"
        val outputFile = File(outputDir, outputName)

        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        try {
            extractor = MediaExtractor()
            extractor.setDataSource(inputFile.absolutePath)

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || audioFormat == null) {
                Log.w(TAG, "No audio track found in video")
                return Pair(false, null)
            }

            extractor.selectTrack(audioTrackIndex)

            val durationUs = try {
                audioFormat.getLong(MediaFormat.KEY_DURATION)
            } catch (e: Exception) {
                1000000L
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackIndex = muxer.addTrack(audioFormat)
            muxer.start()

            val buffer = ByteBuffer.allocate(BUFFER_SIZE)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) {
                    break
                }
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags
                bufferInfo.offset = 0

                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)

                if (durationUs > 0) {
                    val progress = ((bufferInfo.presentationTimeUs * 100) / durationUs).toInt().coerceIn(0, 100)
                    progressCallback?.invoke(progress)
                }

                extractor.advance()
            }

            progressCallback?.invoke(100)

            // Register into system media scanner
            MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(outputFile.absolutePath),
                arrayOf("audio/mp4", "audio/m4a"),
                null
            )

            Log.i(TAG, "Audio extracted successfully: ${outputFile.absolutePath}")
            return Pair(true, outputFile)

        } catch (e: Exception) {
            Log.e(TAG, "Audio extraction failed", e)
            if (outputFile.exists()) {
                outputFile.delete()
            }
            return Pair(false, null)
        } finally {
            try {
                muxer?.stop()
                muxer?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping muxer", e)
            }
            try {
                extractor?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing extractor", e)
            }
        }
    }
}
