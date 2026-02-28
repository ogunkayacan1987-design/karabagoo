package com.lgsextractor.processing.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.lgsextractor.domain.model.BoundingBox
import com.lgsextractor.domain.model.PdfPage
import com.lgsextractor.domain.model.Question
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Singleton
class CropEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cropOutputDir: File
        get() = File(context.filesDir, "question_crops").also { it.mkdirs() }

    /**
     * Crop the question region from the page bitmap and save to internal storage.
     * Returns the Question with updated cropImagePath.
     */
    suspend fun cropQuestion(question: Question, page: PdfPage): Question =
        withContext(Dispatchers.IO) {
            val outputFile = File(
                cropOutputDir,
                "${question.pdfPath}/page${page.pageNumber}/${question.id}.jpg"
            )
            outputFile.parentFile?.mkdirs()

            val result = exportToFile(question, outputFile, quality = 90)
            result.fold(
                onSuccess = { question.copy(cropImagePath = it.absolutePath) },
                onFailure = {
                    android.util.Log.e("CropEngine", "Crop failed for Q${question.questionNumber}", it)
                    question
                }
            )
        }

    /**
     * Export a question's crop to a specific file.
     */
    suspend fun exportToFile(
        question: Question,
        outputFile: File,
        quality: Int = 90
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val pageBitmapPath = findPageBitmapPath(question)
                ?: throw IllegalStateException("Page bitmap not found for ${question.pdfPath}")

            val pageBitmap = BitmapFactory.decodeFile(pageBitmapPath)
                ?: throw IllegalStateException("Cannot decode page bitmap")

            try {
                val croppedBitmap = cropToBoundingBox(pageBitmap, question.boundingBox)
                val scaledBitmap = scaleIfNeeded(croppedBitmap, maxWidth = 1200)

                outputFile.parentFile?.mkdirs()
                FileOutputStream(outputFile).use { stream ->
                    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                }

                if (scaledBitmap !== croppedBitmap) scaledBitmap.recycle()
                croppedBitmap.recycle()

                outputFile
            } finally {
                pageBitmap.recycle()
            }
        }
    }

    /**
     * Re-crop a question after manual boundary correction.
     */
    suspend fun recropWithNewBoundary(
        question: Question,
        newBoundary: BoundingBox,
        page: PdfPage
    ): Result<Question> = withContext(Dispatchers.IO) {
        runCatching {
            val updatedQuestion = question.copy(boundingBox = newBoundary)
            cropQuestion(updatedQuestion, page)
        }
    }

    /**
     * Generate a thumbnail (small preview) for the question list.
     */
    suspend fun generateThumbnail(question: Question, maxSize: Int = 200): Bitmap? =
        withContext(Dispatchers.IO) {
            val cropPath = question.cropImagePath ?: return@withContext null
            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                BitmapFactory.decodeFile(cropPath, this)
            }
            val sampleSize = calculateSampleSize(opts.outWidth, opts.outHeight, maxSize, maxSize)
            BitmapFactory.decodeFile(cropPath, BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            })
        }

    private fun cropToBoundingBox(bitmap: Bitmap, box: BoundingBox): Bitmap {
        // Scale box from page-space to bitmap-space (might differ if bitmap is scaled)
        val scaleX = bitmap.width.toFloat() / box.pageWidth
        val scaleY = bitmap.height.toFloat() / box.pageHeight

        val left = (box.left * scaleX).roundToInt().coerceIn(0, bitmap.width - 1)
        val top = (box.top * scaleY).roundToInt().coerceIn(0, bitmap.height - 1)
        val right = (box.right * scaleX).roundToInt().coerceIn(left + 1, bitmap.width)
        val bottom = (box.bottom * scaleY).roundToInt().coerceIn(top + 1, bitmap.height)

        val width = right - left
        val height = bottom - top

        if (width <= 0 || height <= 0) {
            throw IllegalArgumentException("Invalid crop region: ${box}")
        }

        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    private fun scaleIfNeeded(bitmap: Bitmap, maxWidth: Int): Bitmap {
        if (bitmap.width <= maxWidth) return bitmap
        val ratio = maxWidth.toFloat() / bitmap.width
        val newHeight = (bitmap.height * ratio).roundToInt()
        return Bitmap.createScaledBitmap(bitmap, maxWidth, newHeight, true)
    }

    private fun calculateSampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        var sampleSize = 1
        if (h > reqH || w > reqW) {
            val halfH = h / 2
            val halfW = w / 2
            while (halfH / sampleSize >= reqH && halfW / sampleSize >= reqW) {
                sampleSize *= 2
            }
        }
        return sampleSize
    }

    /**
     * Find the rendered page bitmap path from internal cache.
     * Looks up by documentId + pageNumber.
     */
    private fun findPageBitmapPath(question: Question): String? {
        val cacheDir = File(context.cacheDir, "pdf_renders/${question.pdfPath}")
        val pageFile = cacheDir.listFiles { f ->
            f.name.startsWith("page_${question.pageNumber}_") && f.name.endsWith("dpi.png")
        }?.firstOrNull()
        return pageFile?.absolutePath
    }
}
