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
     * Multi-column aware question detection:
     * Step 1: Detect if document has multiple columns based on X positions
     * Step 2: Group text blocks by column
     * Step 3: Process each column separately in reading order
     * Step 4: Find questions within each column
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

        if (allLines.isEmpty()) return emptyList()

        // Detect columns based on X center positions
        val columns = detectColumns(allLines, pageWidth)
        val boundaries = mutableListOf<QuestionBoundary>()

        // Process each column separately
        for (columnLines in columns) {
            // Sort lines within column by Y position only
            val sortedLines = columnLines.sortedBy { it.bounds.top }

            // Find question starts within this column
            val questionStartIndices = sortedLines.indices.filter { sortedLines[it].isQuestionStart }

            if (questionStartIndices.isEmpty()) continue

            // Group lines for each question in this column
            for (i in questionStartIndices.indices) {
                val startIdx = questionStartIndices[i]
                val endIdx = if (i < questionStartIndices.size - 1) {
                    questionStartIndices[i + 1]
                } else {
                    sortedLines.size
                }

                val questionLines = sortedLines.subList(startIdx, endIdx)
                if (questionLines.isEmpty()) continue

                val questionNumber = questionLines[0].questionNumber ?: continue

                // Calculate the bounding rect for all lines in this question
                val minTop = questionLines.minOf { it.bounds.top }
                val maxBottom = questionLines.maxOf { it.bounds.bottom }
                val minLeft = questionLines.minOf { it.bounds.left }
                val maxRight = questionLines.maxOf { it.bounds.right }

                val hasAnswerChoices = questionLines.any { it.hasAnswerChoice }
                val allText = questionLines.joinToString("\n") { it.text }

                val rect = Rect(
                    maxOf(0, minLeft - config.marginLeft),
                    maxOf(0, minTop - config.marginTop),
                    minOf(pageWidth, maxRight + config.marginRight),
                    minOf(pageHeight, maxBottom + config.marginBottom + 20)
                )

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
        }

        // Sort by top position (reading order: top to bottom, left to right)
        return boundaries.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
    }

    /**
     * Detect columns by clustering text lines based on X center position.
     * Returns a list of columns, each containing the lines in that column.
     */
    private fun detectColumns(lines: List<TextLine>, pageWidth: Int): List<List<TextLine>> {
        if (lines.isEmpty()) return emptyList()

        // Calculate X center for each line
        val linesWithCenter = lines.map { line ->
            val centerX = (line.bounds.left + line.bounds.right) / 2
            Pair(line, centerX)
        }

        // Check if we have a two-column layout
        // If most lines are clearly on left half or right half, it's two columns
        val midPoint = pageWidth / 2
        val leftLines = linesWithCenter.filter { it.second < midPoint - pageWidth * 0.1 }
        val rightLines = linesWithCenter.filter { it.second > midPoint + pageWidth * 0.1 }
        val centerLines = linesWithCenter.filter {
            it.second >= midPoint - pageWidth * 0.1 && it.second <= midPoint + pageWidth * 0.1
        }

        // If we have significant lines on both sides and few in center, it's two columns
        val isTwoColumn = leftLines.size >= 3 && rightLines.size >= 3 &&
                          centerLines.size < (lines.size * 0.3)

        return if (isTwoColumn) {
            // Two column layout - separate left and right
            val leftColumnLines = lines.filter {
                (it.bounds.left + it.bounds.right) / 2 < midPoint
            }
            val rightColumnLines = lines.filter {
                (it.bounds.left + it.bounds.right) / 2 >= midPoint
            }
            listOf(leftColumnLines, rightColumnLines).filter { it.isNotEmpty() }
        } else {
            // Single column - all lines together
            listOf(lines)
        }
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
