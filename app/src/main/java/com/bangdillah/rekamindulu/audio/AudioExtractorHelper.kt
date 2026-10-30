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

            var muxerStarted = false
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackIndex = muxer.addTrack(audioFormat)
            muxer.start()
            muxerStarted = true

            var maxInputSize = BUFFER_SIZE
            if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                val size = audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                if (size > maxInputSize) {
                    maxInputSize = size
                }
            }
            val buffer = ByteBuffer.allocateDirect(maxInputSize)
            val bufferInfo = MediaCodec.BufferInfo()
            var firstSampleTimeUs = -1L
            var lastWrittenPts = -1L

            while (true) {
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) {
                    break
                }
                if (bufferInfo.size > 0) {
                    val rawSampleTime = extractor.sampleTime
                    if (firstSampleTimeUs == -1L) {
                        firstSampleTimeUs = rawSampleTime
                    }
                    val rebasedPts = (rawSampleTime - firstSampleTimeUs).coerceAtLeast(0L)
                    val pts = if (rebasedPts > lastWrittenPts) rebasedPts else lastWrittenPts + 1000L
                    val codecFlags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }
                    bufferInfo.presentationTimeUs = pts
                    bufferInfo.flags = codecFlags
                    bufferInfo.offset = 0

                    muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                    lastWrittenPts = pts

                    if (durationUs > 0) {
                        val progress = ((bufferInfo.presentationTimeUs * 100) / durationUs).toInt().coerceIn(0, 100)
                        progressCallback?.invoke(progress)
                    }
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
                Log.w(TAG, "Error releasing extractor", e)
            }
        }
    }
}
