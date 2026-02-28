package com.lgsextractor.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.lgsextractor.data.local.database.dao.PdfDocumentDao
import com.lgsextractor.data.local.database.entity.PdfDocumentEntity
import com.lgsextractor.domain.model.PdfDocument
import com.lgsextractor.domain.model.PdfPage
import com.lgsextractor.domain.model.PublisherFormat
import com.lgsextractor.domain.model.SubjectType
import com.lgsextractor.domain.repository.PdfRepository
import com.lgsextractor.util.FileUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PdfRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pdfDocumentDao: PdfDocumentDao,
    private val fileUtils: FileUtils
) : PdfRepository {

    private val pdfCacheDir: File
        get() = File(context.cacheDir, "pdf_renders").also { it.mkdirs() }

    override suspend fun importPdf(uri: Uri): Result<PdfDocument> = withContext(Dispatchers.IO) {
        runCatching {
            // Copy PDF to app's private storage
            val documentId = UUID.randomUUID().toString()
            val destFile = File(context.filesDir, "pdfs/$documentId.pdf")
            destFile.parentFile?.mkdirs()

            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Cannot open PDF URI")

            val fileName = fileUtils.getFileName(uri) ?: "document_$documentId.pdf"
            val pageCount = getPdfPageCount(destFile)
            val isScanned = detectIfScanned(destFile)

            val entity = PdfDocumentEntity(
                id = documentId,
                filePath = destFile.absolutePath,
                fileName = fileName,
                pageCount = pageCount,
                fileSizeBytes = destFile.length(),
                isScanned = isScanned,
                detectedPublisher = PublisherFormat.GENERIC.name,
                detectedSubject = SubjectType.UNKNOWN.name,
                importedAt = System.currentTimeMillis()
            )
            pdfDocumentDao.insertDocument(entity)

            PdfDocument(
                id = documentId,
                filePath = destFile.absolutePath,
                fileName = fileName,
                pageCount = pageCount,
                fileSizeBytes = destFile.length(),
                isScanned = isScanned
            )
        }
    }

    override suspend fun getPdfDocument(id: String): PdfDocument? = withContext(Dispatchers.IO) {
        pdfDocumentDao.getDocument(id)?.let { entity ->
            PdfDocument(
                id = entity.id,
                filePath = entity.filePath,
                fileName = entity.fileName,
                pageCount = entity.pageCount,
                fileSizeBytes = entity.fileSizeBytes,
                isScanned = entity.isScanned,
                detectedPublisher = PublisherFormat.valueOf(entity.detectedPublisher),
                detectedSubject = SubjectType.valueOf(entity.detectedSubject),
                importedAt = entity.importedAt
            )
        }
    }

    override fun getAllDocuments(): Flow<List<PdfDocument>> {
        return pdfDocumentDao.getAllDocuments().map { entities ->
            entities.map { e ->
                PdfDocument(
                    id = e.id, filePath = e.filePath, fileName = e.fileName,
                    pageCount = e.pageCount, fileSizeBytes = e.fileSizeBytes,
                    isScanned = e.isScanned, importedAt = e.importedAt
                )
            }
        }
    }

    override suspend fun deletePdfDocument(id: String): Unit = withContext(Dispatchers.IO) {
        pdfDocumentDao.deleteDocument(id)
        // Clean up rendered page cache
        File(pdfCacheDir, id).deleteRecursively()
    }

    override suspend fun renderPage(
        documentId: String,
        pageNumber: Int,
        dpi: Int
    ): Result<PdfPage> = withContext(Dispatchers.IO) {
        runCatching {
            val document = pdfDocumentDao.getDocument(documentId)
                ?: throw IllegalArgumentException("Document not found: $documentId")

            val cacheFile = File(pdfCacheDir, "$documentId/page_${pageNumber}_${dpi}dpi.png")

            // Use cached render if available
            if (cacheFile.exists()) {
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(cacheFile.absolutePath, opts)
                return@runCatching PdfPage(
                    pageNumber = pageNumber,
                    bitmapPath = cacheFile.absolutePath,
                    width = opts.outWidth,
                    height = opts.outHeight,
                    dpi = dpi
                )
            }

            val pdfFile = File(document.filePath)
            val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)

            try {
                val page = renderer.openPage(pageNumber)
                val scale = dpi / 72f  // PDF default is 72 DPI

                val width = (page.width * scale).toInt()
                val height = (page.height * scale).toInt()

                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                // White background (PDFs may have transparent background)
                bitmap.eraseColor(android.graphics.Color.WHITE)

                val matrix = android.graphics.Matrix().apply { setScale(scale, scale) }
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                // Cache the rendered page
                cacheFile.parentFile?.mkdirs()
                cacheFile.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                bitmap.recycle()

                PdfPage(
                    pageNumber = pageNumber,
                    bitmapPath = cacheFile.absolutePath,
                    width = width,
                    height = height,
                    dpi = dpi
                )
            } finally {
                renderer.close()
                pfd.close()
            }
        }
    }

    override suspend fun renderAllPages(documentId: String, dpi: Int): Flow<PdfPage> = flow {
        val document = pdfDocumentDao.getDocument(documentId) ?: return@flow
        for (pageNum in 0 until document.pageCount) {
            val result = renderPage(documentId, pageNum, dpi)
            result.onSuccess { emit(it) }
        }
    }

    private fun getPdfPageCount(file: File): Int {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return try {
            PdfRenderer(pfd).use { it.pageCount }
        } finally {
            pfd.close()
        }
    }

    /**
     * Heuristic: if average text density per page is very low, it's likely scanned.
     * For now, simply return false (text PDF assumed); full detection requires OCR.
     */
    private fun detectIfScanned(file: File): Boolean = false
}
