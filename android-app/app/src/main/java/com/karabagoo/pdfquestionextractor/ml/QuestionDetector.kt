package com.karabagoo.pdfquestionextractor.ml

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.karabagoo.pdfquestionextractor.data.DetectionConfig
import com.karabagoo.pdfquestionextractor.data.Question
import com.karabagoo.pdfquestionextractor.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/**
 * Detects questions in PDF page images using ML Kit text recognition
 * Supports both single-column and multi-column layouts
 */
class QuestionDetector(
    private val config: DetectionConfig = DetectionConfig()
) {
    private val textRecognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // Patterns for question numbers
    private val questionPatterns = listOf(
        Regex("^\\s*(\\d+)\\s*[.)]\\s*"),           // 1. or 1)
        Regex("^\\s*Soru\\s*(\\d+)", RegexOption.IGNORE_CASE),  // Soru 1
        Regex("^\\s*S[.]?\\s*(\\d+)", RegexOption.IGNORE_CASE)  // S.1 or S 1
    )

    // Patterns for options (A, B, C, D, E)
    private val optionPatterns = listOf(
        Regex("^\\s*[A-E]\\s*[.)]", RegexOption.IGNORE_CASE),
        Regex("^\\s*[A-E]\\s*-", RegexOption.IGNORE_CASE),
        Regex("[A-E]\\)", RegexOption.IGNORE_CASE)
    )

    // Pattern for last option (D or E)
    private val lastOptionPatterns = listOf(
        Regex("^\\s*[DE]\\s*[.)]", RegexOption.IGNORE_CASE),
        Regex("^\\s*[DE]\\s*-", RegexOption.IGNORE_CASE),
        Regex("[DE]\\)", RegexOption.IGNORE_CASE)
    )

    suspend fun detectQuestions(
        pageBitmap: Bitmap,
        pageNumber: Int,
        startQuestionId: Int = 0
    ): List<Question> = withContext(Dispatchers.Default) {
        try {
            val textResult = recognizeText(pageBitmap)
            val questionBoundaries = findQuestionBoundaries(textResult, pageBitmap)

            questionBoundaries.mapIndexed { index, boundary ->
                val croppedBitmap = ImageUtils.cropBitmap(pageBitmap, boundary.rect)

                Question(
                    id = startQuestionId + index,
                    pageNumber = pageNumber,
                    questionNumber = boundary.questionNumber,
                    boundingBox = boundary.rect,
                    bitmap = croppedBitmap,
                    isSelected = true,
                    detectedText = boundary.text,
                    confidence = boundary.confidence
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private suspend fun recognizeText(bitmap: Bitmap): Text = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        textRecognizer.process(image)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    private fun findQuestionBoundaries(text: Text, pageBitmap: Bitmap): List<QuestionBoundary> {
        val pageWidth = pageBitmap.width
        val pageHeight = pageBitmap.height
        val boundaries = mutableListOf<QuestionBoundary>()

        // Collect all text elements
        val textElements = mutableListOf<TextElement>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val lineText = line.text.trim()
                val box = line.boundingBox ?: continue
                textElements.add(TextElement(
                    text = lineText,
                    bounds = box,
                    centerX = box.centerX(),
                    questionNumber = extractQuestionNumber(lineText),
                    isOption = isOption(lineText),
                    isLastOption = isLastOption(lineText)
                ))
            }
        }

        // Find question starts
        val questionStarts = textElements.filter { it.questionNumber != null }
            .sortedWith(compareBy({ it.bounds.top }, { it.centerX }))

        if (questionStarts.isEmpty()) return emptyList()

        // Detect if multi-column layout
        val isMultiColumn = detectMultiColumn(questionStarts, pageWidth)
        val columnDivider = pageWidth / 2

        // Process each question
        for (i in questionStarts.indices) {
            val current = questionStarts[i]
            val questionNumber = current.questionNumber ?: continue
            val isLeftColumn = current.centerX < columnDivider

            // Determine the column bounds for this question
            val columnLeft: Int
            val columnRight: Int

            if (isMultiColumn) {
                if (isLeftColumn) {
                    columnLeft = 0
                    columnRight = columnDivider
                } else {
                    columnLeft = columnDivider
                    columnRight = pageWidth
                }
            } else {
                columnLeft = 0
                columnRight = pageWidth
            }

            // Find elements in the same column
            val columnElements = textElements.filter {
                it.centerX >= columnLeft && it.centerX < columnRight
            }

            // Find next question in the same column
            val nextInColumn = questionStarts
                .filter { it.questionNumber != questionNumber }
                .filter { if (isMultiColumn) (it.centerX < columnDivider) == isLeftColumn else true }
                .filter { it.bounds.top > current.bounds.top }
                .minByOrNull { it.bounds.top }

            val maxY = nextInColumn?.bounds?.top?.minus(10) ?: pageHeight

            // Find elements belonging to this question (same column, between this and next)
            val questionElements = columnElements.filter {
                it.bounds.top >= current.bounds.top - 5 &&
                it.bounds.top < maxY
            }

            // Find the last option (D or E)
            val lastOption = questionElements
                .filter { it.isLastOption }
                .maxByOrNull { it.bounds.bottom }

            // Find any option as fallback
            val anyOption = questionElements
                .filter { it.isOption }
                .maxByOrNull { it.bounds.bottom }

            // Determine end Y position
            val endY = when {
                lastOption != null -> lastOption.bounds.bottom + 30
                anyOption != null -> anyOption.bounds.bottom + 30
                else -> {
                    // Find the lowest element in this question's range
                    questionElements.maxByOrNull { it.bounds.bottom }?.bounds?.bottom?.plus(20)
                        ?: (maxY - 10)
                }
            }

            // Create boundary with column-aware width
            val rect = Rect(
                columnLeft + config.marginLeft,
                maxOf(0, current.bounds.top - config.marginTop),
                columnRight - config.marginRight,
                minOf(pageHeight, endY)
            )

            // Validate
            if (rect.height() >= config.minQuestionHeight && rect.height() <= config.maxQuestionHeight) {
                boundaries.add(QuestionBoundary(
                    questionNumber = questionNumber,
                    rect = rect,
                    text = current.text,
                    confidence = if (lastOption != null) 0.95f else 0.75f
                ))
            }
        }

        return boundaries.sortedBy { it.questionNumber }
    }

    /**
     * Detect if page has multi-column layout
     */
    private fun detectMultiColumn(questions: List<TextElement>, pageWidth: Int): Boolean {
        if (questions.size < 2) return false

        val midPoint = pageWidth / 2
        val leftQuestions = questions.filter { it.centerX < midPoint }
        val rightQuestions = questions.filter { it.centerX >= midPoint }

        // If we have questions on both sides, it's likely multi-column
        if (leftQuestions.isNotEmpty() && rightQuestions.isNotEmpty()) {
            // Check if any questions are at similar Y positions (within 100px)
            for (left in leftQuestions) {
                for (right in rightQuestions) {
                    if (abs(left.bounds.top - right.bounds.top) < 100) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun extractQuestionNumber(text: String): Int? {
        for (pattern in questionPatterns) {
            val match = pattern.find(text)
            if (match != null && match.groupValues.size > 1) {
                return match.groupValues[1].toIntOrNull()
            }
        }
        val simple = Regex("^\\s*(\\d+)\\s*[.)]").find(text)
        if (simple != null && simple.groupValues.size > 1) {
            return simple.groupValues[1].toIntOrNull()
        }
        return null
    }

    private fun isOption(text: String): Boolean {
        return optionPatterns.any { it.containsMatchIn(text) }
    }

    private fun isLastOption(text: String): Boolean {
        return lastOptionPatterns.any { it.containsMatchIn(text) }
    }

    fun close() {
        textRecognizer.close()
    }

    private data class TextElement(
        val text: String,
        val bounds: Rect,
        val centerX: Int,
        val questionNumber: Int?,
        val isOption: Boolean,
        val isLastOption: Boolean
    )

    private data class QuestionBoundary(
        val questionNumber: Int,
        val rect: Rect,
        val text: String,
        val confidence: Float
    )
}
