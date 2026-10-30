package com.bangdillah.rekamindulu.utils

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
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
                if (file.length() < 100) {
                    // Hapus file 0-byte / dummy rusak
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
}
