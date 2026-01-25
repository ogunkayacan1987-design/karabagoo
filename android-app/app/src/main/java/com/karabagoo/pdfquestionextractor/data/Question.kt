package com.karabagoo.pdfquestionextractor.data

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Represents a detected question from a PDF page
 */
data class Question(
    val id: Int,
    val pageNumber: Int,
    val questionNumber: Int,
    val boundingBox: Rect,
    val bitmap: Bitmap?,
    var isSelected: Boolean = false,
    val detectedText: String = "",
    val confidence: Float = 0f
)

/**
 * Represents the result of question detection on a single page
 */
data class PageAnalysisResult(
    val pageNumber: Int,
    val questions: List<Question>,
    val pageBitmap: Bitmap?
)

/**
 * Represents the overall processing state
 */
sealed class ProcessingState {
    object Idle : ProcessingState()
    object Loading : ProcessingState()
    data class Processing(val currentPage: Int, val totalPages: Int) : ProcessingState()
    data class Success(val questions: List<Question>) : ProcessingState()
    data class Error(val message: String) : ProcessingState()
}

/**
 * Configuration for question detection
 */
data class DetectionConfig(
    val questionPatterns: List<Regex> = listOf(
        Regex("^\\s*\\d+[.)]\\s*", RegexOption.MULTILINE),           // 1. or 1)
        Regex("^\\s*[A-Z][.)]\\s*", RegexOption.MULTILINE),          // A. or A)
        Regex("^\\s*Soru\\s*\\d+", RegexOption.IGNORE_CASE),         // Soru 1, Soru 2
        Regex("^\\s*S\\.?\\s*\\d+", RegexOption.IGNORE_CASE),        // S.1, S1, S 1
        Regex("^\\s*\\d+\\s*[-–]\\s*", RegexOption.MULTILINE)        // 1 - or 1 –
    ),
    val minQuestionHeight: Int = 50,
    val maxQuestionHeight: Int = 800,
    val marginTop: Int = 10,
    val marginBottom: Int = 10,
    val marginLeft: Int = 10,
    val marginRight: Int = 10
)

/**
 * Export quality settings
 */
enum class ExportQuality(val value: Int) {
    HIGH(100),
    MEDIUM(85),
    LOW(70)
}
