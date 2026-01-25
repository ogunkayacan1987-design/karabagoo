package com.karabagoo.pdfquestionextractor.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Utility class for rendering PDF pages to bitmaps
 */
class PdfRendererHelper(private val context: Context) {

    private var pdfRenderer: PdfRenderer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var currentFile: File? = null

    val pageCount: Int
        get() = pdfRenderer?.pageCount ?: 0

    /**
     * Opens a PDF from URI
     */
    suspend fun openPdf(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            close()

            // Copy to cache for reliable access
            val cacheFile = File(context.cacheDir, "temp_pdf_${System.currentTimeMillis()}.pdf")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            }

            currentFile = cacheFile
            fileDescriptor = ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(fileDescriptor!!)

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Renders a specific page to a bitmap
     * @param pageIndex 0-based page index
     * @param scale Scale factor for rendering quality (1.0 = 72 DPI, 2.0 = 144 DPI, etc.)
     */
    suspend fun renderPage(pageIndex: Int, scale: Float = 2.5f): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val renderer = pdfRenderer ?: return@withContext null

            if (pageIndex < 0 || pageIndex >= renderer.pageCount) {
                return@withContext null
            }

            val page = renderer.openPage(pageIndex)

            val width = (page.width * scale).toInt()
            val height = (page.height * scale).toInt()

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            // Fill with white background
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)

            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Renders all pages
     */
    suspend fun renderAllPages(scale: Float = 2.5f, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<Bitmap> =
        withContext(Dispatchers.IO) {
            val bitmaps = mutableListOf<Bitmap>()
            val count = pageCount

            for (i in 0 until count) {
                onProgress(i + 1, count)
                renderPage(i, scale)?.let { bitmaps.add(it) }
            }

            bitmaps
        }

    /**
     * Gets page dimensions without rendering
     */
    fun getPageDimensions(pageIndex: Int): Pair<Int, Int>? {
        try {
            val renderer = pdfRenderer ?: return null

            if (pageIndex < 0 || pageIndex >= renderer.pageCount) {
                return null
            }

            val page = renderer.openPage(pageIndex)
            val dimensions = Pair(page.width, page.height)
            page.close()

            return dimensions
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * Closes the PDF and releases resources
     */
    fun close() {
        try {
            pdfRenderer?.close()
            pdfRenderer = null

            fileDescriptor?.close()
            fileDescriptor = null

            currentFile?.delete()
            currentFile = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
