package com.lgsextractor.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class PdfDocument(
    val id: String,
    val filePath: String,
    val fileName: String,
    val pageCount: Int,
    val fileSizeBytes: Long,
    val isScanned: Boolean = false,          // true = image-based PDF (needs OCR)
    val detectedPublisher: PublisherFormat = PublisherFormat.GENERIC,
    val detectedSubject: SubjectType = SubjectType.UNKNOWN,
    val importedAt: Long = System.currentTimeMillis()
) : Parcelable

@Parcelize
data class PdfPage(
    val pageNumber: Int,      // 0-indexed
    val bitmapPath: String,   // path to rendered bitmap cache
    val width: Int,
    val height: Int,
    val dpi: Int = 300,
    val columnCount: Int = 1, // 1 or 2 columns detected
    val columnBoundaries: List<Int> = emptyList() // x-coordinates of column splits
) : Parcelable

data class ProcessingProgress(
    val currentPage: Int,
    val totalPages: Int,
    val phase: ProcessingPhase,
    val questionsFound: Int = 0,
    val message: String = ""
) {
    val percentage: Int get() = if (totalPages == 0) 0 else (currentPage * 100) / totalPages
}

enum class ProcessingPhase {
    RENDERING_PDF,
    ANALYZING_LAYOUT,
    RUNNING_OCR,
    DETECTING_QUESTIONS,
    CROPPING,
    SAVING,
    COMPLETE,
    ERROR
}

sealed class ProcessingResult {
    data class Success(val questions: List<Question>) : ProcessingResult()
    data class Error(val message: String, val cause: Throwable? = null) : ProcessingResult()
    object Cancelled : ProcessingResult()
}
