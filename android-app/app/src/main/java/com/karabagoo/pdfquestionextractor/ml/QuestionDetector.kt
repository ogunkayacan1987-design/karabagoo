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

/**
 * Detects questions in PDF page images using ML Kit text recognition
 * Improved algorithm: Detects from question number to the end of option D
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
        Regex("^\\s*[A-E]\\s*[.)]\\s*", RegexOption.IGNORE_CASE),  // A) or A.
        Regex("^\\s*[A-E]\\s*-\\s*", RegexOption.IGNORE_CASE)      // A -
    )

    // Pattern specifically for last option (D or E)
    private val lastOptionPatterns = listOf(
        Regex("^\\s*[DE]\\s*[.)]", RegexOption.IGNORE_CASE),
        Regex("^\\s*[DE]\\s*-", RegexOption.IGNORE_CASE)
    )

    /**
     * Detects questions in a single page bitmap
     */
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

    /**
     * Recognizes text in the bitmap using ML Kit
     */
    private suspend fun recognizeText(bitmap: Bitmap): Text = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)

        textRecognizer.process(image)
            .addOnSuccessListener { text ->
                cont.resume(text)
            }
            .addOnFailureListener { e ->
                cont.resumeWithException(e)
            }
    }

    /**
     * Finds question boundaries from recognized text
     * Improved: Uses option D/E end position as question boundary
     */
    private fun findQuestionBoundaries(text: Text, pageBitmap: Bitmap): List<QuestionBoundary> {
        val boundaries = mutableListOf<QuestionBoundary>()

        // Collect all text elements with positions
        val textElements = mutableListOf<TextElement>()

        for (block in text.textBlocks) {
            for (line in block.lines) {
                val lineText = line.text.trim()
                val boundingBox = line.boundingBox ?: continue

                textElements.add(TextElement(
                    text = lineText,
                    bounds = boundingBox,
                    isQuestionStart = isQuestionStart(lineText),
                    questionNumber = extractQuestionNumber(lineText),
                    isOption = isOption(lineText),
                    isLastOption = isLastOption(lineText)
                ))
            }
        }

        // Sort by vertical position
        textElements.sortBy { it.bounds.top }

        // Find question starts
        val questionStarts = textElements.filter { it.isQuestionStart && it.questionNumber != null }

        // For each question, find its boundary
        for (i in questionStarts.indices) {
            val currentQuestion = questionStarts[i]
            val questionNumber = currentQuestion.questionNumber ?: continue

            val startY = currentQuestion.bounds.top - config.marginTop

            // Find the end of this question
            // Option 1: Find the last option (D or E) before the next question
            // Option 2: If no options found, use the next question start

            val nextQuestionStartY = if (i < questionStarts.size - 1) {
                questionStarts[i + 1].bounds.top
            } else {
                pageBitmap.height
            }

            // Find all elements between this question and the next
            val questionElements = textElements.filter {
                it.bounds.top >= currentQuestion.bounds.top &&
                it.bounds.top < nextQuestionStartY
            }

            // Find the last option (D or E) in this question's range
            val lastOptionElement = questionElements
                .filter { it.isLastOption }
                .maxByOrNull { it.bounds.bottom }

            // If we found a last option, use its bottom as the end
            // Otherwise, find any option and use the last one
            val endY = when {
                lastOptionElement != null -> {
                    lastOptionElement.bounds.bottom + config.marginBottom + 20
                }
                else -> {
                    // Find the last option of any kind
                    val anyLastOption = questionElements
                        .filter { it.isOption }
                        .maxByOrNull { it.bounds.bottom }

                    anyLastOption?.bounds?.bottom?.plus(config.marginBottom + 20)
                        ?: (nextQuestionStartY - config.marginTop)
                }
            }

            // Create the question boundary
            val questionRect = Rect(
                config.marginLeft,
                maxOf(0, startY),
                pageBitmap.width - config.marginRight,
                minOf(pageBitmap.height, endY)
            )

            // Validate the question height
            val height = questionRect.height()
            if (height >= config.minQuestionHeight && height <= config.maxQuestionHeight) {
                boundaries.add(
                    QuestionBoundary(
                        questionNumber = questionNumber,
                        rect = questionRect,
                        text = currentQuestion.text,
                        confidence = if (lastOptionElement != null) 0.95f else 0.8f
                    )
                )
            }
        }

        return boundaries
    }

    /**
     * Checks if the text starts a new question
     */
    private fun isQuestionStart(text: String): Boolean {
        return extractQuestionNumber(text) != null
    }

    /**
     * Extracts question number from text
     */
    private fun extractQuestionNumber(text: String): Int? {
        for (pattern in questionPatterns) {
            val match = pattern.find(text)
            if (match != null && match.groupValues.size > 1) {
                return match.groupValues[1].toIntOrNull()
            }
        }

        // Fallback: simple number at start
        val simpleMatch = Regex("^\\s*(\\d+)\\s*[.)]").find(text)
        if (simpleMatch != null && simpleMatch.groupValues.size > 1) {
            return simpleMatch.groupValues[1].toIntOrNull()
        }

        return null
    }

    /**
     * Checks if text is an option (A, B, C, D, E)
     */
    private fun isOption(text: String): Boolean {
        return optionPatterns.any { it.containsMatchIn(text) }
    }

    /**
     * Checks if text is the last option (D or E)
     */
    private fun isLastOption(text: String): Boolean {
        return lastOptionPatterns.any { it.containsMatchIn(text) }
    }

    /**
     * Releases resources
     */
    fun close() {
        textRecognizer.close()
    }

    /**
     * Data class for text element with metadata
     */
    private data class TextElement(
        val text: String,
        val bounds: Rect,
        val isQuestionStart: Boolean,
        val questionNumber: Int?,
        val isOption: Boolean,
        val isLastOption: Boolean
    )

    /**
     * Data class for question boundary
     */
    private data class QuestionBoundary(
        val questionNumber: Int,
        val rect: Rect,
        val text: String,
        val confidence: Float
    )
}
