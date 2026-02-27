package com.lgsextractor.data.local.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pdf_documents")
data class PdfDocumentEntity(
    @PrimaryKey val id: String,
    val filePath: String,
    val fileName: String,
    val pageCount: Int,
    val fileSizeBytes: Long,
    val isScanned: Boolean = false,
    val detectedPublisher: String = "GENERIC",
    val detectedSubject: String = "UNKNOWN",
    val importedAt: Long = System.currentTimeMillis()
)
