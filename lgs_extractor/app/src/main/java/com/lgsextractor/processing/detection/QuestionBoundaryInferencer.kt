package com.lgsextractor.processing.detection

import android.graphics.Rect
import com.lgsextractor.domain.model.BoundingBox
import com.lgsextractor.domain.model.DetectionConfig
import com.lgsextractor.processing.cv.OpenCVProcessor
import com.lgsextractor.processing.ocr.OcrLine
import com.lgsextractor.processing.ocr.OcrResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Infers the pixel boundaries of each question on the page.
 *
 * Algorithm:
 * 1. Find question start Y-coordinates from OCR line positions
 * 2. Find option block Y-coordinates for each question
 * 3. Define question boundary as:
 *    - Top: Y of question number line (minus small padding)
 *    - Bottom: Y of last option line (plus padding) or next question start - 1
 * 4. Cross-reference with OpenCV whitespace gaps for refinement
 * 5. Apply column boundaries to correct X extent
 */
@Singleton
class QuestionBoundaryInferencer @Inject constructor(
    private val patternMatcher: LGSPatternMatcher
) {
    data class QuestionBoundaryResult(
        val questionNumber: Int,
        val boundingBox: BoundingBox,
        val questionLines: List<OcrLine>,
        val optionBlock: LGSPatternMatcher.OptionBlock?,
        val confidence: Float,
        val columnIndex: Int,
        val hasImage: Boolean,
        val hasTable: Boolean
    )

    /**
     * Infer all question boundaries from OCR results for one page.
     */
    fun inferBoundaries(
        ocrResults: List<OcrResult>,
        layoutInfo: OpenCVProcessor.LayoutInfo,
        config: DetectionConfig
    ): List<QuestionBoundaryResult> {
        val allBoundaries = mutableListOf<QuestionBoundaryResult>()

        ocrResults.forEach { ocrResult ->
            val colBoundary = layoutInfo.columnBoundaries.getOrNull(ocrResult.columnIndex)
                ?: layoutInfo.columnBoundaries.firstOrNull()
                ?: return@forEach

            val boundaries = inferBoundariesForColumn(
                ocrResult = ocrResult,
                colBoundary = colBoundary,
                layoutInfo = layoutInfo,
                config = config
            )
            allBoundaries.addAll(boundaries)
        }

        return allBoundaries.sortedWith(
            compareBy({ it.columnIndex }, { it.boundingBox.top })
        )
    }

    private fun inferBoundariesForColumn(
        ocrResult: OcrResult,
        colBoundary: OpenCVProcessor.ColumnBoundary,
        layoutInfo: OpenCVProcessor.LayoutInfo,
        config: DetectionConfig
    ): List<QuestionBoundaryResult> {
        val lines = ocrResult.textLines.sortedBy { it.boundingBox.top }
        if (lines.isEmpty()) return emptyList()

        // Step 1: Find question starts
        val questionStarts = patternMatcher.findQuestionStarts(lines, config)
        if (questionStarts.isEmpty()) return emptyList()

        val results = mutableListOf<QuestionBoundaryResult>()

        questionStarts.forEachIndexed { startIdx, qStart ->
            val nextQStart = questionStarts.getOrNull(startIdx + 1)

            // Lines belonging to this question
            val qLines = if (nextQStart != null) {
                lines.subList(qStart.lineIndex, nextQStart.lineIndex)
            } else {
                lines.subList(qStart.lineIndex, lines.size)
            }

            if (qLines.isEmpty()) return@forEachIndexed

            // Step 2: Find option block within this question's lines
            val optionBlock = patternMatcher.findOptionBlock(lines, qStart.lineIndex)

            // Step 3: Calculate bounding box
            val topY = qStart.line.boundingBox.top
            val bottomY = calculateBottomY(
                qLines = qLines,
                optionBlock = optionBlock,
                nextQStartLine = nextQStart?.line,
                layoutInfo = layoutInfo,
                colIdx = ocrResult.columnIndex,
                config = config
            )

            // Snap to nearest whitespace gap if within threshold
            val refinedTop = snapToGap(topY, layoutInfo.horizontalGaps, colBoundary.columnIndex, above = true)
            val refinedBottom = snapToGap(bottomY, layoutInfo.horizontalGaps, colBoundary.columnIndex, above = false)

            val paddedTop = max(0, refinedTop - config.questionPaddingPx)
            val paddedBottom = min(layoutInfo.pageHeight, refinedBottom + config.questionPaddingPx)

            // Step 4: X extent
            // Prefer ocrResult.regionRect when it is narrower than the full page — this means
            // Claude/Gemini Vision already split the page into column-specific regions.
            // Fall back to colBoundary for ML Kit / Tesseract (per-column processing).
            val useRegionX = ocrResult.regionRect.width() < layoutInfo.pageWidth * 0.85
            val leftX  = max(0, (if (useRegionX) ocrResult.regionRect.left  else colBoundary.startX) - config.questionPaddingPx)
            val rightX = min(layoutInfo.pageWidth, (if (useRegionX) ocrResult.regionRect.right else colBoundary.endX) + config.questionPaddingPx)

            // Step 5: Confidence score
            val confidence = computeConfidence(qLines, optionBlock, qStart.patternName)

            // Step 6: Detect images/tables (gap between text blocks = image placeholder)
            val hasImage = detectImagePlaceholder(qLines, layoutInfo)
            val hasTable = detectTable(qLines)

            if (paddedBottom - paddedTop >= config.minQuestionHeight) {
                results.add(QuestionBoundaryResult(
                    questionNumber = qStart.questionNumber,
                    boundingBox = BoundingBox(
                        left = leftX, top = paddedTop,
                        right = rightX, bottom = paddedBottom,
                        pageWidth = layoutInfo.pageWidth,
                        pageHeight = layoutInfo.pageHeight
                    ),
                    questionLines = qLines,
                    optionBlock = optionBlock,
                    confidence = confidence,
                    columnIndex = ocrResult.columnIndex,
                    hasImage = hasImage,
                    hasTable = hasTable
                ))
            }
        }

        return results
    }

    private fun calculateBottomY(
        qLines: List<OcrLine>,
        optionBlock: LGSPatternMatcher.OptionBlock?,
        nextQStartLine: OcrLine?,
        layoutInfo: OpenCVProcessor.LayoutInfo,
        colIdx: Int,
        config: DetectionConfig
    ): Int {
        // Option block bottom takes priority (most reliable)
        if (optionBlock != null && optionBlock.isComplete) {
            val lastOptionLine = optionBlock.options.maxByOrNull { it.lineIndex }?.line
            if (lastOptionLine != null) {
                return lastOptionLine.boundingBox.bottom
            }
        }

        // Fallback: bottom of the last text line in this question's region
        // But don't cross into next question's start line
        val lastLine = if (nextQStartLine != null) {
            qLines.filter { it.boundingBox.bottom < nextQStartLine.boundingBox.top }
                .maxByOrNull { it.boundingBox.bottom }
        } else {
            qLines.maxByOrNull { it.boundingBox.bottom }
        }

        return lastLine?.boundingBox?.bottom
            ?: qLines.last().boundingBox.bottom
    }

    /**
     * Snap coordinate to nearest whitespace gap if within [threshold] pixels.
     */
    private fun snapToGap(
        y: Int,
        gaps: List<OpenCVProcessor.HorizontalGap>,
        colIdx: Int,
        above: Boolean,
        threshold: Int = 30
    ): Int {
        val colGaps = gaps.filter { it.columnIndex == colIdx }
        if (colGaps.isEmpty()) return y

        val nearestGap = colGaps.minByOrNull { gap ->
            if (above) abs(gap.y - y) else abs(gap.y + gap.height - y)
        } ?: return y

        return if (above) {
            val gapEdge = nearestGap.y
            if (abs(gapEdge - y) <= threshold) gapEdge else y
        } else {
            val gapEdge = nearestGap.y + nearestGap.height
            if (abs(gapEdge - y) <= threshold) gapEdge else y
        }
    }

    private fun computeConfidence(
        qLines: List<OcrLine>,
        optionBlock: LGSPatternMatcher.OptionBlock?,
        patternName: String
    ): Float {
        var score = 0f

        // Pattern reliability
        score += when (patternName) {
            "numbered_dot" -> 0.40f
            "zero_padded_dot" -> 0.38f
            "soru_prefix" -> 0.35f
            "soru_upper" -> 0.33f
            "numbered_paren" -> 0.25f
            else -> 0.20f
        }

        // Option block completeness
        if (optionBlock != null) {
            score += when {
                optionBlock.isComplete -> 0.40f
                optionBlock.options.size >= 3 -> 0.25f
                optionBlock.options.size >= 2 -> 0.15f
                else -> 0.05f
            }
        }

        // OCR line confidence average
        val avgOcrConf = qLines.map { it.confidence }.average().toFloat()
        score += avgOcrConf * 0.20f

        return score.coerceIn(0f, 1f)
    }

    private fun detectImagePlaceholder(
        qLines: List<OcrLine>,
        layoutInfo: OpenCVProcessor.LayoutInfo
    ): Boolean {
        if (qLines.size < 2) return false
        val sorted = qLines.sortedBy { it.boundingBox.top }
        for (i in 0 until sorted.size - 1) {
            val gap = sorted[i + 1].boundingBox.top - sorted[i].boundingBox.bottom
            // Gap > 60px between consecutive text lines suggests an image
            if (gap > 60) return true
        }
        return false
    }

    private fun detectTable(qLines: List<OcrLine>): Boolean {
        // Heuristic: multiple lines with | or tab-separated values
        val tableLineCount = qLines.count { line ->
            line.text.contains("|") ||
            line.text.count { it == '\t' } >= 2 ||
            line.text.split(Regex("\\s{3,}")).size >= 3
        }
        return tableLineCount >= 2
    }
}
