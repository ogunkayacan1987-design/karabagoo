package com.karabagoo.pdfquestionextractor.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.karabagoo.pdfquestionextractor.data.ExportQuality
import com.karabagoo.pdfquestionextractor.data.Question
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility class for image operations
 */
object ImageUtils {

    /**
     * Crops a bitmap using the specified rectangle
     */
    fun cropBitmap(source: Bitmap, rect: Rect, padding: Int = 20): Bitmap {
        val left = maxOf(0, rect.left - padding)
        val top = maxOf(0, rect.top - padding)
        val right = minOf(source.width, rect.right + padding)
        val bottom = minOf(source.height, rect.bottom + padding)

        val width = right - left
        val height = bottom - top

        if (width <= 0 || height <= 0) {
            return source
        }

        return Bitmap.createBitmap(source, left, top, width, height)
    }

    /**
     * Adds white background and padding to a bitmap
     */
    fun addPaddingAndBackground(source: Bitmap, padding: Int = 30): Bitmap {
        val width = source.width + (padding * 2)
        val height = source.height + (padding * 2)

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, padding.toFloat(), padding.toFloat(), null)

        return result
    }

    /**
     * Saves a bitmap to device storage
     */
    suspend fun saveBitmapToGallery(
        context: Context,
        bitmap: Bitmap,
        fileName: String,
        quality: ExportQuality = ExportQuality.HIGH
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val finalFileName = "${fileName}_$timestamp.jpg"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ - Use MediaStore
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, finalFileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PDFSorular")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                )

                uri?.let {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.value, outputStream)
                    }

                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(it, contentValues, null, null)
                }

                uri
            } else {
                // Older Android - Use external storage directly
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val appDir = File(picturesDir, "PDFSorular")
                if (!appDir.exists()) {
                    appDir.mkdirs()
                }

                val file = File(appDir, finalFileName)
                FileOutputStream(file).use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality.value, outputStream)
                }

                // Notify media scanner
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DATA, file.absolutePath)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                }
                context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Saves multiple questions as JPEG images
     */
    suspend fun saveQuestionsToGallery(
        context: Context,
        questions: List<Question>,
        baseName: String = "Soru",
        quality: ExportQuality = ExportQuality.HIGH,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<Uri?> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Uri?>()

        questions.forEachIndexed { index, question ->
            onProgress(index + 1, questions.size)

            question.bitmap?.let { bitmap ->
                val paddedBitmap = addPaddingAndBackground(bitmap)
                val fileName = "${baseName}_${question.questionNumber}"
                val uri = saveBitmapToGallery(context, paddedBitmap, fileName, quality)
                results.add(uri)

                // Recycle the padded bitmap if it's different from the original
                if (paddedBitmap != bitmap) {
                    paddedBitmap.recycle()
                }
            } ?: results.add(null)
        }

        results
    }

    /**
     * Creates a shareable temporary file
     */
    suspend fun createShareableFile(
        context: Context,
        bitmap: Bitmap,
        fileName: String,
        quality: ExportQuality = ExportQuality.HIGH
    ): File? = withContext(Dispatchers.IO) {
        try {
            val cacheDir = File(context.cacheDir, "shared_images")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }

            val file = File(cacheDir, "$fileName.jpg")
            FileOutputStream(file).use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality.value, outputStream)
            }

            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Clears the share cache directory
     */
    fun clearShareCache(context: Context) {
        try {
            val cacheDir = File(context.cacheDir, "shared_images")
            cacheDir.deleteRecursively()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
