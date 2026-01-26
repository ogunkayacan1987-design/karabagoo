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
 * Document Question Segmentation Engine
 *
 * Detects and separates exam questions from OCR text.
 * Relies ONLY on textual and semantic patterns.
 * Does NOT rely on visual layout, spacing, columns, or image separation.
 *
 * Rules:
 * - A question starts with a number followed by dot or parenthesis (1., 2., 15., 3))
 * - A question includes: stem, explanations, tables, figure captions, all answer choices
 * - A question ends immediately before the next question number appears
 * - Never split based on whitespace, empty lines, or visual gaps
 * - Never merge two different question numbers
 */
class QuestionDetector(
    private val config: DetectionConfig = DetectionConfig()
) {
    private val textRecognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // Pattern for question numbers: 1. or 1) or 15. or 15)
    private val questionNumberPattern = Regex("^\\s*(\\d+)\\s*[.)]")

    // Pattern for answer choices
    private val answerChoicePattern = Regex("^\\s*[A-E]\\s*[.):]", RegexOption.IGNORE_CASE)

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
                    detectedText = boundary.allText,
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

    /**
     * Step 1: Scan all lines and identify every line that begins with a question number
     * Step 2: Assign each subsequent line to the current question until another question number is encountered
     * Step 3: Create boundaries based on the Y positions of the grouped lines
     */
    private fun findQuestionBoundaries(text: Text, pageBitmap: Bitmap): List<QuestionBoundary> {
        val pageHeight = pageBitmap.height
        val pageWidth = pageBitmap.width

        // Collect all text lines with their positions
        val allLines = mutableListOf<TextLine>()

        for (block in text.textBlocks) {
            for (line in block.lines) {
                val lineText = line.text.trim()
                val box = line.boundingBox ?: continue

                allLines.add(TextLine(
                    text = lineText,
                    bounds = box,
                    isQuestionStart = isQuestionStart(lineText),
                    questionNumber = extractQuestionNumber(lineText),
                    hasAnswerChoice = hasAnswerChoice(lineText)
                ))
            }
        }

        // Sort lines by Y position (top to bottom), then by X (left to right)
        allLines.sortWith(compareBy({ it.bounds.top }, { it.bounds.left }))

        // Step 1: Find all question start positions
        val questionStartIndices = allLines.indices.filter { allLines[it].isQuestionStart }

        if (questionStartIndices.isEmpty()) {
            return emptyList()
        }

        val boundaries = mutableListOf<QuestionBoundary>()

        // Step 2: Group lines for each question
        for (i in questionStartIndices.indices) {
            val startIdx = questionStartIndices[i]
            val endIdx = if (i < questionStartIndices.size - 1) {
                questionStartIndices[i + 1]
            } else {
                allLines.size
            }

            // Get all lines belonging to this question
            val questionLines = allLines.subList(startIdx, endIdx)
            if (questionLines.isEmpty()) continue

            val questionNumber = questionLines[0].questionNumber ?: continue

            // Calculate the bounding rect for all lines in this question
            val minTop = questionLines.minOf { it.bounds.top }
            val maxBottom = questionLines.maxOf { it.bounds.bottom }
            val minLeft = questionLines.minOf { it.bounds.left }
            val maxRight = questionLines.maxOf { it.bounds.right }

            // Check if question has answer choices (for confidence scoring)
            val hasAnswerChoices = questionLines.any { it.hasAnswerChoice }

            // Collect all text for this question
            val allText = questionLines.joinToString("\n") { it.text }

            // Create boundary with some padding
            val rect = Rect(
                maxOf(0, minLeft - config.marginLeft),
                maxOf(0, minTop - config.marginTop),
                minOf(pageWidth, maxRight + config.marginRight),
                minOf(pageHeight, maxBottom + config.marginBottom + 20)
            )

            // Validate height
            if (rect.height() >= config.minQuestionHeight) {
                boundaries.add(QuestionBoundary(
                    questionNumber = questionNumber,
                    rect = rect,
                    allText = allText,
                    confidence = if (hasAnswerChoices) 0.95f else 0.7f,
                    hasError = !hasAnswerChoices && !allText.contains("?")
                ))
            }
        }

        return boundaries.sortedBy { it.questionNumber }
    }

    /**
     * Check if line starts with a question number (1., 2., 15., 3))
     */
    private fun isQuestionStart(text: String): Boolean {
        return questionNumberPattern.containsMatchIn(text)
    }

    /**
     * Extract question number from text
     */
    private fun extractQuestionNumber(text: String): Int? {
        val match = questionNumberPattern.find(text)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    /**
     * Check if line contains an answer choice (A), B), C), D), E))
     */
    private fun hasAnswerChoice(text: String): Boolean {
        return answerChoicePattern.containsMatchIn(text)
    }

    fun close() {
        textRecognizer.close()
    }

    private data class TextLine(
        val text: String,
        val bounds: Rect,
        val isQuestionStart: Boolean,
        val questionNumber: Int?,
        val hasAnswerChoice: Boolean
    )

    private data class QuestionBoundary(
        val questionNumber: Int,
        val rect: Rect,
        val allText: String,
        val confidence: Float,
        val hasError: Boolean = false
    )
}
