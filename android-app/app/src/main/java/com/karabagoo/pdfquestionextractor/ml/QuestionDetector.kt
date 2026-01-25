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
 */
class QuestionDetector(
    private val config: DetectionConfig = DetectionConfig()
) {
    private val textRecognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

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
     */
    private fun findQuestionBoundaries(text: Text, pageBitmap: Bitmap): List<QuestionBoundary> {
        val boundaries = mutableListOf<QuestionBoundary>()
        val questionStarts = mutableListOf<QuestionStart>()

        // Find all question start positions
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val lineText = line.text.trim()
                val boundingBox = line.boundingBox ?: continue

                // Check if this line starts a question
                val questionNumber = extractQuestionNumber(lineText)
                if (questionNumber != null) {
                    questionStarts.add(
                        QuestionStart(
                            questionNumber = questionNumber,
                            topY = boundingBox.top,
                            leftX = boundingBox.left,
                            text = lineText,
                            boundingBox = boundingBox
                        )
                    )
                }
            }
        }

        // Sort by vertical position
        questionStarts.sortBy { it.topY }

        // Create boundaries between consecutive questions
        for (i in questionStarts.indices) {
            val current = questionStarts[i]
            val nextTopY = if (i < questionStarts.size - 1) {
                questionStarts[i + 1].topY - config.marginTop
            } else {
                pageBitmap.height - config.marginBottom
            }

            // Find the full width of this question area
            val questionRect = Rect(
                config.marginLeft,
                maxOf(0, current.topY - config.marginTop),
                pageBitmap.width - config.marginRight,
                minOf(pageBitmap.height, nextTopY)
            )

            // Validate the question height
            val height = questionRect.height()
            if (height >= config.minQuestionHeight && height <= config.maxQuestionHeight) {
                boundaries.add(
                    QuestionBoundary(
                        questionNumber = current.questionNumber,
                        rect = questionRect,
                        text = current.text,
                        confidence = 0.9f
                    )
                )
            }
        }

        return boundaries
    }

    /**
     * Extracts question number from text line
     */
    private fun extractQuestionNumber(text: String): Int? {
        // Try various patterns
        for (pattern in config.questionPatterns) {
            val match = pattern.find(text)
            if (match != null) {
                // Extract the number from the match
                val numberMatch = Regex("\\d+").find(match.value)
                if (numberMatch != null) {
                    return numberMatch.value.toIntOrNull()
                }
            }
        }

        // Also try to detect questions by specific Turkish patterns
        val turkishPatterns = listOf(
            Regex("^\\s*(\\d+)\\s*[.)]"),        // 1. or 1)
            Regex("^\\s*Soru\\s*(\\d+)", RegexOption.IGNORE_CASE),  // Soru 1
            Regex("^\\s*S[.]?\\s*(\\d+)", RegexOption.IGNORE_CASE)  // S.1 or S 1
        )

        for (pattern in turkishPatterns) {
            val match = pattern.find(text)
            if (match != null && match.groupValues.size > 1) {
                return match.groupValues[1].toIntOrNull()
            }
        }

        return null
    }

    /**
     * Releases resources
     */
    fun close() {
        textRecognizer.close()
    }

    /**
     * Data class for question start position
     */
    private data class QuestionStart(
        val questionNumber: Int,
        val topY: Int,
        val leftX: Int,
        val text: String,
        val boundingBox: Rect
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
