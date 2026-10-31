package com.bangdillah.rekamindulu.utils

import android.app.Activity
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


/**
 * StorageHelper - Pengelola penyimpanan video publik, direktori rekaman,
 * MediaScannerConnection, dan sinkronisasi MediaStore galeri ponsel.
 */
object StorageHelper {

    private const val TAG = "StorageHelper"
    private const val FOLDER_NAME = "RekaminDulu"

    /**
     * Mendapatkan direktori penyimpanan publik untuk rekaman video.
     * Mengutamakan direktori app-specific yang dijamin SELALU memiliki izin tulis
     * tanpa crash EACCES di Android 10, 11, 12, 13, 14+.
     */
    fun getRecordingDirectory(context: Context): File {
        val extFiles = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val targetDir = if (extFiles != null) {
            File(extFiles, FOLDER_NAME)
        } else {
            File(context.filesDir, FOLDER_NAME)
        }

        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        return targetDir
    }

    /**
     * Membuat file output MP4 baru dengan timestamp unik di direktori yang aman.
     */
    fun createOutputFile(context: Context, prefix: String = "Rekaman"): File {
        val dir = getRecordingDirectory(context)
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File(dir, "${prefix}_$timeStamp.mp4")
    }

    /**
     * Mengambil seluruh file rekaman MP4 yang valid dari folder rekaman.
     * Membersihkan file korup/berukuran 0-byte secara otomatis.
     */
    fun getAllVideoFiles(context: Context): List<File> {
        val result = mutableListOf<File>()

        // 1. Direktori Publik
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            FOLDER_NAME
        )
        collectValidVideosFromDir(publicDir, result)

        // 2. Direktori Fallback Eksternal
        val fallbackDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), FOLDER_NAME)
        if (fallbackDir.exists() && fallbackDir.absolutePath != publicDir.absolutePath) {
            collectValidVideosFromDir(fallbackDir, result)
        }

        // 3. Direktori Internal Files
        val internalDir = File(context.filesDir, FOLDER_NAME)
        if (internalDir.exists() && internalDir.absolutePath != publicDir.absolutePath && internalDir.absolutePath != fallbackDir.absolutePath) {
            collectValidVideosFromDir(internalDir, result)
        }

        // Urutkan berdasarkan waktu modifikasi terbaru secara default
        result.sortByDescending { it.lastModified() }
        return result
    }

    private fun collectValidVideosFromDir(dir: File, outList: MutableList<File>) {
        if (!dir.exists() || !dir.isDirectory) return

        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isFile && file.name.endsWith(".mp4", ignoreCase = true)) {
                if (file.length() < 10240) {
                    // Jangan hapus jika file sedang aktif direkam oleh ScreenRecordingService
                    if (com.bangdillah.rekamindulu.service.ScreenRecordingService.isRecording &&
                        com.bangdillah.rekamindulu.service.ScreenRecordingService.currentActiveFilePath == file.absolutePath
                    ) {
                        continue
                    }
                    // Hapus file dummy / rusak / tidak memiliki frame
                    try {
                        file.delete()
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not remove corrupted file ${file.name}", e)
                    }
                } else {
                    if (outList.none { it.absolutePath == file.absolutePath }) {
                        outList.add(file)
                    }
                }
            }
        }
    }

    /**
     * Mendaftarkan file video ke MediaStore galeri Android melalui MediaScannerConnection
     * dan mengupdate MediaStore ContentResolver agar video seketika muncul di galeri ponsel.
     */
    fun scanVideoFile(
        context: Context,
        file: File,
        onScanned: ((path: String, uri: Uri?) -> Unit)? = null
    ) {
        if (!file.exists() || file.length() < 100) return

        try {
            MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(file.absolutePath),
                arrayOf("video/mp4")
            ) { path, uri ->
                Log.i(TAG, "MediaScanner indexed: $path -> $uri")
                onScanned?.invoke(path, uri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed scanning video file ${file.absolutePath}", e)
        }
    }

    /**
     * Menghapus referensi file dari MediaStore saat video dihapus dari perpustakaan,
     * sehingga tidak meninggalkan file thumbnail hantu di galeri ponsel.
     */
    fun scanDeletedFile(context: Context, file: File) {
        try {
            com.bangdillah.rekamindulu.library.LibraryAdapter.invalidateCache(file.absolutePath)
        } catch (ignored: Exception) {}

        try {
            val resolver: ContentResolver = context.contentResolver
            @Suppress("DEPRECATION")
            val where = "${MediaStore.Video.Media.DATA} = ?"
            val args = arrayOf(file.absolutePath)
            resolver.delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, where, args)
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore deletion failed for ${file.name}", e)
        }

        try {
            MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(file.absolutePath),
                null,
                null
            )
        } catch (e: Exception) {
            Log.w(TAG, "MediaScanner deletion notification failed for ${file.name}", e)
        }
    }

    /**
     * Sinkronisasi perubahan nama file pada MediaStore galeri ponsel.
     */
    fun scanRenamedFile(context: Context, oldFile: File, newFile: File) {
        scanDeletedFile(context, oldFile)
        scanVideoFile(context, newFile)
    }

    /**
     * Menghasilkan video demo MP4 valid dengan trek video HD dan trek audio AAC jernih,
     * berguna agar pengguna/tester dapat langsung menguji Player, Trim, dan Audio Extractor.
     */
    fun generateDemoVideo(context: Context, onComplete: ((File?) -> Unit)? = null) {
        Thread {
            try {
                val outputFile = createOutputFile(context, "Demo_Rekamin")
                val width = 720
                val height = 1280
                val bitRate = 2_000_000
                val frameRate = 30
                val durationSec = 3
                val numFrames = frameRate * durationSec

                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }

                val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val inputSurface = encoder.createInputSurface()
                encoder.start()

                // Audio encoder setup (AAC 44.1kHz Mono)
                val sampleRate = 44100
                val audioMime = "audio/mp4a-latm"
                val audioFormat = MediaFormat.createAudioFormat(audioMime, sampleRate, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 64000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }

                var audioEncoder: MediaCodec? = null
                var audioTrackIndex = -1
                try {
                    audioEncoder = MediaCodec.createEncoderByType(audioMime)
                    audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    audioEncoder.start()
                } catch (eAudio: Exception) {
                    Log.w(TAG, "Audio encoder not supported for demo: ${eAudio.message}")
                    audioEncoder = null
                }

                val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var videoTrackIndex = -1
                var muxerStarted = false
                val bufferInfo = MediaCodec.BufferInfo()
                val audioBufferInfo = MediaCodec.BufferInfo()

                val paintBg = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#161622") }
                val paintHeader = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#6C5CE7") }
                val paintText = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 38f
                    isAntiAlias = true
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                val paintSubtext = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#A29BFE")
                    textSize = 24f
                    isAntiAlias = true
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                val paintDot = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#E53935")
                    isAntiAlias = true
                }
                val paintCircle = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#00B894")
                    isAntiAlias = true
                }

                // Render first video frame to trigger output format
                val canvas0 = inputSurface.lockHardwareCanvas()
                canvas0.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintBg)
                canvas0.drawRoundRect(40f, 120f, width - 40f, 300f, 24f, 24f, paintHeader)
                canvas0.drawCircle(80f, 210f, 14f, paintDot)
                canvas0.drawText("Rekamin Dulu • Demo HD", width / 2f + 16f, 195f, paintText)
                canvas0.drawText("Rekam Layar & Audio Suara", width / 2f + 16f, 245f, paintSubtext)
                inputSurface.unlockCanvasAndPost(canvas0)

                // Feed audio samples if audioEncoder is active
                var totalAudioSamplesFed = 0
                val totalSamplesNeeded = sampleRate * durationSec
                var audioEndOfStreamSent = false

                fun feedAudioIfPossible() {
                    val aEnc = audioEncoder ?: return
                    if (audioEndOfStreamSent) return
                    while (totalAudioSamplesFed < totalSamplesNeeded) {
                        val inIndex = aEnc.dequeueInputBuffer(0)
                        if (inIndex >= 0) {
                            val inBuf = aEnc.getInputBuffer(inIndex) ?: break
                            inBuf.clear()
                            val samplesThisChunk = Math.min(1024, totalSamplesNeeded - totalAudioSamplesFed)
                            for (s in 0 until samplesThisChunk) {
                                val currentSample = totalAudioSamplesFed + s
                                val angle = (currentSample.toDouble() / sampleRate) * 587.33 * 2.0 * Math.PI
                                val decay = Math.max(0.2, 1.0 - (currentSample.toDouble() / totalSamplesNeeded))
                                val sampleVal = (Math.sin(angle) * 12000.0 * decay).toInt().toShort()
                                inBuf.putShort(sampleVal)
                            }
                            val bytesFed = samplesThisChunk * 2
                            val ptsUs = (totalAudioSamplesFed * 1000000L / sampleRate)
                            totalAudioSamplesFed += samplesThisChunk
                            val isEos = totalAudioSamplesFed >= totalSamplesNeeded
                            if (isEos) {
                                audioEndOfStreamSent = true
                            }
                            aEnc.queueInputBuffer(
                                inIndex,
                                0,
                                bytesFed,
                                ptsUs,
                                if (isEos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                            )
                        } else {
                            break
                        }
                    }
                }

                feedAudioIfPossible()

                // Wait for format on both encoders before starting muxer
                val pendingVideoBuffers = mutableListOf<Pair<java.nio.ByteBuffer, MediaCodec.BufferInfo>>()
                val pendingAudioBuffers = mutableListOf<Pair<java.nio.ByteBuffer, MediaCodec.BufferInfo>>()

                var videoFormatFound = false
                var audioFormatFound = (audioEncoder == null)

                var attempts = 0
                while ((!videoFormatFound || !audioFormatFound) && attempts < 100) {
                    attempts++
                    feedAudioIfPossible()

                    if (!videoFormatFound) {
                        val outIdx = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            videoTrackIndex = muxer.addTrack(encoder.outputFormat)
                            videoFormatFound = true
                        } else if (outIdx >= 0) {
                            val buf = encoder.getOutputBuffer(outIdx)
                            if (buf != null && bufferInfo.size > 0) {
                                val clone = java.nio.ByteBuffer.allocate(bufferInfo.size)
                                clone.put(buf)
                                clone.flip()
                                val infoClone = MediaCodec.BufferInfo()
                                infoClone.set(0, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                                pendingVideoBuffers.add(Pair(clone, infoClone))
                            }
                            encoder.releaseOutputBuffer(outIdx, false)
                        }
                    }

                    if (!audioFormatFound && audioEncoder != null) {
                        val aOutIdx = audioEncoder.dequeueOutputBuffer(audioBufferInfo, 10000)
                        if (aOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            audioTrackIndex = muxer.addTrack(audioEncoder.outputFormat)
                            audioFormatFound = true
                        } else if (aOutIdx >= 0) {
                            val buf = audioEncoder.getOutputBuffer(aOutIdx)
                            if (buf != null && audioBufferInfo.size > 0) {
                                val clone = java.nio.ByteBuffer.allocate(audioBufferInfo.size)
                                clone.put(buf)
                                clone.flip()
                                val infoClone = MediaCodec.BufferInfo()
                                infoClone.set(0, audioBufferInfo.size, audioBufferInfo.presentationTimeUs, audioBufferInfo.flags)
                                pendingAudioBuffers.add(Pair(clone, infoClone))
                            }
                            audioEncoder.releaseOutputBuffer(aOutIdx, false)
                        }
                    }
                }

                if (videoFormatFound) {
                    muxer.start()
                    muxerStarted = true

                    // Flush pending video
                    for ((buf, info) in pendingVideoBuffers) {
                        muxer.writeSampleData(videoTrackIndex, buf, info)
                    }
                    pendingVideoBuffers.clear()

                    // Flush pending audio
                    if (audioTrackIndex >= 0) {
                        for ((buf, info) in pendingAudioBuffers) {
                            muxer.writeSampleData(audioTrackIndex, buf, info)
                        }
                        pendingAudioBuffers.clear()
                    }
                }

                // Render remaining frames (1 to numFrames - 1)
                for (i in 1 until numFrames) {
                    feedAudioIfPossible()

                    val canvas = inputSurface.lockHardwareCanvas()
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintBg)

                    // Draw header banner
                    canvas.drawRoundRect(40f, 120f, width - 40f, 300f, 24f, 24f, paintHeader)
                    canvas.drawCircle(80f, 210f, 14f, paintDot)
                    canvas.drawText("Rekamin Dulu • Demo HD", width / 2f + 16f, 195f, paintText)
                    canvas.drawText("Rekam Layar & Audio Suara", width / 2f + 16f, 245f, paintSubtext)

                    // Draw moving pulse circle
                    val animY = 440f + (i * 7f) % 450f
                    canvas.drawCircle(width / 2f, animY, 54f, paintCircle)

                    val sec = i / frameRate
                    canvas.drawText("Sample Rekaman Video 00:0$sec / 00:03", width / 2f, 1020f, paintText)
                    canvas.drawText("1080p 60FPS • Suara Jernih Aktif", width / 2f, 1070f, paintSubtext)

                    inputSurface.unlockCanvasAndPost(canvas)

                    while (true) {
                        val outputBufferId = encoder.dequeueOutputBuffer(bufferInfo, 5000)
                        if (outputBufferId >= 0) {
                            val outputBuffer = encoder.getOutputBuffer(outputBufferId)
                            if (outputBuffer != null && bufferInfo.size > 0 && muxerStarted) {
                                bufferInfo.presentationTimeUs = (i * 1000000L / frameRate)
                                muxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(outputBufferId, false)
                            break
                        } else {
                            break
                        }
                    }

                    // Drain audio
                    if (audioEncoder != null && muxerStarted && audioTrackIndex >= 0) {
                        while (true) {
                            val aOut = audioEncoder.dequeueOutputBuffer(audioBufferInfo, 5000)
                            if (aOut >= 0) {
                                val aBuf = audioEncoder.getOutputBuffer(aOut)
                                if (aBuf != null && audioBufferInfo.size > 0) {
                                    muxer.writeSampleData(audioTrackIndex, aBuf, audioBufferInfo)
                                }
                                audioEncoder.releaseOutputBuffer(aOut, false)
                            } else {
                                break
                            }
                        }
                    }
                }

                encoder.signalEndOfInputStream()

                // Final video drain
                while (true) {
                    val outputBufferId = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                    if (outputBufferId >= 0) {
                        val outputBuffer = encoder.getOutputBuffer(outputBufferId)
                        if (outputBuffer != null && bufferInfo.size > 0 && muxerStarted) {
                            muxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outputBufferId, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break
                        }
                    } else if (outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break
                    }
                }

                // Final audio drain
                if (audioEncoder != null && muxerStarted && audioTrackIndex >= 0) {
                    while (true) {
                        val aOut = audioEncoder.dequeueOutputBuffer(audioBufferInfo, 10000)
                        if (aOut >= 0) {
                            val aBuf = audioEncoder.getOutputBuffer(aOut)
                            if (aBuf != null && audioBufferInfo.size > 0) {
                                muxer.writeSampleData(audioTrackIndex, aBuf, audioBufferInfo)
                            }
                            audioEncoder.releaseOutputBuffer(aOut, false)
                            if ((audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                break
                            }
                        } else {
                            break
                        }
                    }
                }

                try { encoder.stop(); encoder.release() } catch (ignored: Exception) {}
                try { audioEncoder?.stop(); audioEncoder?.release() } catch (ignored: Exception) {}
                try { inputSurface.release() } catch (ignored: Exception) {}
                try {
                    if (muxerStarted) muxer.stop()
                    muxer.release()
                } catch (ignored: Exception) {}

                scanVideoFile(context, outputFile)

                (context as? Activity)?.runOnUiThread {
                    onComplete?.invoke(outputFile)
                } ?: run {
                    onComplete?.invoke(outputFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "generateDemoVideo failed", e)
                (context as? Activity)?.runOnUiThread {
                    onComplete?.invoke(null)
                }
            }
        }.start()
    }
}

