package com.lgsextractor.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import javax.inject.Inject

class FileUtils @Inject constructor(private val context: Context) {

    fun getFileName(uri: Uri): String? {
        return when (uri.scheme) {
            "content" -> {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
                }
            }
            "file" -> uri.lastPathSegment
            else -> null
        }
    }

    fun getFileSize(uri: Uri): Long {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst() && sizeIndex >= 0) cursor.getLong(sizeIndex) else -1L
        } ?: -1L
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    fun getPublicDownloadsDir(): File {
        return android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        ).also { it.mkdirs() }
    }
}
