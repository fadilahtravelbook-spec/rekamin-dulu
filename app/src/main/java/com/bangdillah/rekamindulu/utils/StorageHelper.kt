package com.bangdillah.rekamindulu.utils

import android.app.Activity
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


/**
 * StorageHelper - Pengelola penyimpanan video publik, direktori rekaman,
 * tangkapan layar, MediaScannerConnection, dan sinkronisasi MediaStore galeri ponsel.
 */
object StorageHelper {

    private const val TAG = "StorageHelper"
    private const val FOLDER_NAME = "RekaminDulu"
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Mendapatkan direktori penyimpanan untuk rekaman video.
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
     * Menjamin tidak terjadi tabrakan nama jika dipanggil dua kali pada detik yang sama.
     */
    fun createOutputFile(context: Context, prefix: String = "Rekaman"): File {
        val dir = getRecordingDirectory(context)
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var candidate = File(dir, "${prefix}_$timeStamp.mp4")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "${prefix}_${timeStamp}_$counter.mp4")
            counter++
        }
        return candidate
    }

    /**
     * Menyimpan tangkapan layar PNG ke Galeri (Pictures/RekaminDulu) melalui MediaStore (API 29+)
     * atau direktori eksternal pada API < 29 dengan pemindaian MediaScanner.
     */
    fun saveScreenshot(context: Context, bitmap: Bitmap): Uri? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val fileName = "Screenshot_Rekamin_$timeStamp.png"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/$FOLDER_NAME"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        throw IllegalStateException("Bitmap compress PNG failed")
                    }
                } ?: throw IllegalStateException("Failed opening OutputStream for screenshot")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } catch (e: Exception) {
                Log.e(TAG, "Failed saving screenshot via MediaStore", e)
                saveScreenshotToFallbackDir(context, bitmap, fileName)
            }
        } else {
            saveScreenshotToFallbackDir(context, bitmap, fileName)
        }
    }

    private fun saveScreenshotToFallbackDir(context: Context, bitmap: Bitmap, fileName: String): Uri? {
        return try {
            @Suppress("DEPRECATION")
            val publicPictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val basePictures = if (publicPictures != null && (publicPictures.exists() || publicPictures.mkdirs())) {
                publicPictures
            } else {
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
            }
            val dir = File(basePictures, FOLDER_NAME)
            if (!dir.exists()) dir.mkdirs()
            val outFile = File(dir, fileName)
            FileOutputStream(outFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(outFile.absolutePath),
                arrayOf("image/png"),
                null
            )
            Uri.fromFile(outFile)
        } catch (e: Exception) {
            Log.e(TAG, "Fallback screenshot save failed", e)
            null
        }
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
            val deletedRows = resolver.delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, where, args)
            if (deletedRows == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val relWhere = "${MediaStore.Video.Media.DISPLAY_NAME} = ? AND ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
                val relArgs = arrayOf(file.name, "%$FOLDER_NAME%")
                resolver.delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, relWhere, relArgs)
            }
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
            var outputFile: File? = null
            var encoder: MediaCodec? = null
            var audioEncoder: MediaCodec? = null
            var inputSurface: android.view.Surface? = null
            var muxer: MediaMuxer? = null
            var muxerStarted = false
            var succeeded = false
            try {
                val createdFile = createOutputFile(context, "Demo_Rekamin")
                outputFile = createdFile
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

                val videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                encoder = videoEncoder
                videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = videoEncoder.createInputSurface()
                inputSurface = surface
                videoEncoder.start()

                // Audio encoder setup (AAC 44.1kHz Mono)
                val sampleRate = 44100
                val audioMime = "audio/mp4a-latm"
                val audioFormat = MediaFormat.createAudioFormat(audioMime, sampleRate, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 64000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }

                var audioTrackIndex = -1
                try {
                    val aEnc = MediaCodec.createEncoderByType(audioMime)
                    aEnc.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    aEnc.start()
                    audioEncoder = aEnc
                } catch (eAudio: Exception) {
                    Log.w(TAG, "Audio encoder not supported for demo: ${eAudio.message}")
                    audioEncoder = null
                }

                val mediaMuxer = MediaMuxer(createdFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxer = mediaMuxer
                var videoTrackIndex = -1
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
                val canvas0 = surface.lockHardwareCanvas()
                canvas0.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintBg)
                canvas0.drawRoundRect(40f, 120f, width - 40f, 300f, 24f, 24f, paintHeader)
                canvas0.drawCircle(80f, 210f, 14f, paintDot)
                canvas0.drawText("Rekamin Dulu • Demo HD", width / 2f + 16f, 195f, paintText)
                canvas0.drawText("Rekam Layar & Audio Suara", width / 2f + 16f, 245f, paintSubtext)
                surface.unlockCanvasAndPost(canvas0)

                // Feed audio samples if audioEncoder is active
                var totalAudioSamplesFed = 0
                val totalSamplesNeeded = sampleRate * durationSec
                var audioEndOfStreamSent = false

                fun feedAudioIfPossible() {
                    val aEnc = audioEncoder ?: return
                    if (audioEndOfStreamSent) return
                    var feedIterations = 0
                    while (totalAudioSamplesFed < totalSamplesNeeded && feedIterations < 64) {
                        feedIterations++
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
                        val outIdx = videoEncoder.dequeueOutputBuffer(bufferInfo, 10000)
                        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            videoTrackIndex = mediaMuxer.addTrack(videoEncoder.outputFormat)
                            videoFormatFound = true
                        } else if (outIdx >= 0) {
                            val buf = videoEncoder.getOutputBuffer(outIdx)
                            if (buf != null && bufferInfo.size > 0) {
                                val clone = java.nio.ByteBuffer.allocate(bufferInfo.size)
                                clone.put(buf)
                                clone.flip()
                                val infoClone = MediaCodec.BufferInfo()
                                infoClone.set(0, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                                pendingVideoBuffers.add(Pair(clone, infoClone))
                            }
                            videoEncoder.releaseOutputBuffer(outIdx, false)
                        }
                    }

                    val aEnc = audioEncoder
                    if (!audioFormatFound && aEnc != null) {
                        val aOutIdx = aEnc.dequeueOutputBuffer(audioBufferInfo, 10000)
                        if (aOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            audioTrackIndex = mediaMuxer.addTrack(aEnc.outputFormat)
                            audioFormatFound = true
                        } else if (aOutIdx >= 0) {
                            val buf = aEnc.getOutputBuffer(aOutIdx)
                            if (buf != null && audioBufferInfo.size > 0) {
                                val clone = java.nio.ByteBuffer.allocate(audioBufferInfo.size)
                                clone.put(buf)
                                clone.flip()
                                val infoClone = MediaCodec.BufferInfo()
                                infoClone.set(0, audioBufferInfo.size, audioBufferInfo.presentationTimeUs, audioBufferInfo.flags)
                                pendingAudioBuffers.add(Pair(clone, infoClone))
                            }
                            aEnc.releaseOutputBuffer(aOutIdx, false)
                        }
                    }
                }

                if (videoFormatFound) {
                    mediaMuxer.start()
                    muxerStarted = true

                    for ((buf, info) in pendingVideoBuffers) {
                        mediaMuxer.writeSampleData(videoTrackIndex, buf, info)
                    }
                    pendingVideoBuffers.clear()

                    if (audioTrackIndex >= 0) {
                        for ((buf, info) in pendingAudioBuffers) {
                            mediaMuxer.writeSampleData(audioTrackIndex, buf, info)
                        }
                        pendingAudioBuffers.clear()
                    }
                }

                // Render remaining frames (1 to numFrames - 1)
                for (i in 1 until numFrames) {
                    feedAudioIfPossible()

                    val canvas = surface.lockHardwareCanvas()
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintBg)
                    canvas.drawRoundRect(40f, 120f, width - 40f, 300f, 24f, 24f, paintHeader)
                    canvas.drawCircle(80f, 210f, 14f, paintDot)
                    canvas.drawText("Rekamin Dulu • Demo HD", width / 2f + 16f, 195f, paintText)
                    canvas.drawText("Rekam Layar & Audio Suara", width / 2f + 16f, 245f, paintSubtext)

                    val animY = 440f + (i * 7f) % 450f
                    canvas.drawCircle(width / 2f, animY, 54f, paintCircle)

                    val sec = i / frameRate
                    canvas.drawText("Sample Rekaman Video 00:0$sec / 00:03", width / 2f, 1020f, paintText)
                    canvas.drawText("1080p 60FPS • Suara Jernih Aktif", width / 2f, 1070f, paintSubtext)

                    surface.unlockCanvasAndPost(canvas)

                    var vDrain = 0
                    while (vDrain < 120) {
                        vDrain++
                        val outputBufferId = videoEncoder.dequeueOutputBuffer(bufferInfo, 5000)
                        if (outputBufferId >= 0) {
                            val outputBuffer = videoEncoder.getOutputBuffer(outputBufferId)
                            if (outputBuffer != null && bufferInfo.size > 0 && muxerStarted) {
                                bufferInfo.presentationTimeUs = (i * 1000000L / frameRate)
                                mediaMuxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                            }
                            videoEncoder.releaseOutputBuffer(outputBufferId, false)
                            break
                        } else {
                            break
                        }
                    }

                    val aEnc = audioEncoder
                    if (aEnc != null && muxerStarted && audioTrackIndex >= 0) {
                        var aDrain = 0
                        while (aDrain < 120) {
                            aDrain++
                            val aOut = aEnc.dequeueOutputBuffer(audioBufferInfo, 5000)
                            if (aOut >= 0) {
                                val aBuf = aEnc.getOutputBuffer(aOut)
                                if (aBuf != null && audioBufferInfo.size > 0) {
                                    mediaMuxer.writeSampleData(audioTrackIndex, aBuf, audioBufferInfo)
                                }
                                aEnc.releaseOutputBuffer(aOut, false)
                            } else {
                                break
                            }
                        }
                    }
                }

                videoEncoder.signalEndOfInputStream()

                // Final bounded video drain
                var finalVideoDrain = 0
                while (finalVideoDrain < 120) {
                    finalVideoDrain++
                    val outputBufferId = videoEncoder.dequeueOutputBuffer(bufferInfo, 10000)
                    if (outputBufferId >= 0) {
                        val outputBuffer = videoEncoder.getOutputBuffer(outputBufferId)
                        if (outputBuffer != null && bufferInfo.size > 0 && muxerStarted) {
                            mediaMuxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                        }
                        videoEncoder.releaseOutputBuffer(outputBufferId, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break
                        }
                    } else if (outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break
                    }
                }

                // Final bounded audio drain
                val aEnc = audioEncoder
                if (aEnc != null && muxerStarted && audioTrackIndex >= 0) {
                    var finalAudioDrain = 0
                    while (finalAudioDrain < 120) {
                        finalAudioDrain++
                        val aOut = aEnc.dequeueOutputBuffer(audioBufferInfo, 10000)
                        if (aOut >= 0) {
                            val aBuf = aEnc.getOutputBuffer(aOut)
                            if (aBuf != null && audioBufferInfo.size > 0) {
                                mediaMuxer.writeSampleData(audioTrackIndex, aBuf, audioBufferInfo)
                            }
                            aEnc.releaseOutputBuffer(aOut, false)
                            if ((audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                break
                            }
                        } else if (aOut == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            break
                        }
                    }
                }

                succeeded = muxerStarted && createdFile.exists() && createdFile.length() > 1024L
            } catch (e: Exception) {
                Log.e(TAG, "generateDemoVideo failed", e)
                succeeded = false
            } finally {
                try { encoder?.stop() } catch (_: Exception) {}
                try { encoder?.release() } catch (_: Exception) {}
                try { audioEncoder?.stop() } catch (_: Exception) {}
                try { audioEncoder?.release() } catch (_: Exception) {}
                try { inputSurface?.release() } catch (_: Exception) {}
                try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
                try { muxer?.release() } catch (_: Exception) {}

                val resultFile = outputFile
                if (succeeded && resultFile != null) {
                    scanVideoFile(context, resultFile)
                    mainHandler.post { onComplete?.invoke(resultFile) }
                } else {
                    if (resultFile != null && resultFile.exists()) {
                        try { resultFile.delete() } catch (_: Exception) {}
                    }
                    mainHandler.post { onComplete?.invoke(null) }
                }
            }
        }.start()
    }
}

