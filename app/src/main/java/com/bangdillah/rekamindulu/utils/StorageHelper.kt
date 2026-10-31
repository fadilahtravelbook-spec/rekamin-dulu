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
     * Menghasilkan video demo MP4 valid secara langsung menggunakan MediaCodec & MediaMuxer,
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

                val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var trackIndex = -1
                var muxerStarted = false
                val bufferInfo = MediaCodec.BufferInfo()

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

                for (i in 0 until numFrames) {
                    val canvas = inputSurface.lockHardwareCanvas()
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintBg)

                    // Draw header banner
                    canvas.drawRoundRect(40f, 120f, width - 40f, 300f, 24f, 24f, paintHeader)
                    canvas.drawCircle(80f, 210f, 14f, paintDot)
                    canvas.drawText("Rekamin Dulu • Demo HD", width / 2f + 16f, 195f, paintText)
                    canvas.drawText("Rekam Layar & Audio Game", width / 2f + 16f, 245f, paintSubtext)

                    // Draw moving pulse circle
                    val animY = 440f + (i * 7f) % 450f
                    canvas.drawCircle(width / 2f, animY, 54f, paintCircle)

                    val sec = i / frameRate
                    canvas.drawText("Sample Rekaman Video 00:0$sec / 00:03", width / 2f, 1020f, paintText)
                    canvas.drawText("1080p 60FPS • Siap Diputar & Dipotong", width / 2f, 1070f, paintSubtext)

                    inputSurface.unlockCanvasAndPost(canvas)

                    while (true) {
                        val outputBufferId = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                        if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (!muxerStarted) {
                                trackIndex = muxer.addTrack(encoder.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                        } else if (outputBufferId >= 0) {
                            val outputBuffer = encoder.getOutputBuffer(outputBufferId)
                            if (outputBuffer != null && bufferInfo.size > 0 && muxerStarted) {
                                bufferInfo.presentationTimeUs = (i * 1000000L / frameRate)
                                muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(outputBufferId, false)
                            break
                        } else {
                            break
                        }
                    }
                }

                encoder.signalEndOfInputStream()

                while (true) {
                    val outputBufferId = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                    if (outputBufferId >= 0) {
                        val outputBuffer = encoder.getOutputBuffer(outputBufferId)
                        if (outputBuffer != null && bufferInfo.size > 0 && muxerStarted) {
                            muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outputBufferId, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break
                        }
                    } else if (outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break
                    }
                }

                try {
                    encoder.stop()
                    encoder.release()
                } catch (ignored: Exception) {}

                try {
                    inputSurface.release()
                } catch (ignored: Exception) {}

                try {
                    if (muxerStarted) {
                        muxer.stop()
                    }
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

