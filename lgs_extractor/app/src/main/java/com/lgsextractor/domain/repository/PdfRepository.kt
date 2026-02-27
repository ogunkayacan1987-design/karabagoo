package com.lgsextractor.domain.repository

import android.net.Uri
import com.lgsextractor.domain.model.PdfDocument
import com.lgsextractor.domain.model.PdfPage
import kotlinx.coroutines.flow.Flow

interface PdfRepository {
    suspend fun importPdf(uri: Uri): Result<PdfDocument>
    suspend fun getPdfDocument(id: String): PdfDocument?
    fun getAllDocuments(): Flow<List<PdfDocument>>
    suspend fun deletePdfDocument(id: String)
    suspend fun renderPage(documentId: String, pageNumber: Int, dpi: Int = 300): Result<PdfPage>
    suspend fun renderAllPages(documentId: String, dpi: Int = 300): Flow<PdfPage>
}
