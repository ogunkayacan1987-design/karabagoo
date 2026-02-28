package com.lgsextractor.processing.ocr

import android.graphics.Rect
import android.util.Log
import com.lgsextractor.domain.model.DetectionConfig
import com.lgsextractor.domain.model.OcrEngineType
import com.lgsextractor.domain.model.PdfPage
import com.lgsextractor.processing.cv.OpenCVProcessor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OCR result for a single column or page region.
 */
data class OcrResult(
    val fullText: String,
    val textLines: List<OcrLine>,
    val columnIndex: Int,
    val regionRect: Rect
)

data class OcrLine(
    val text: String,
    val boundingBox: Rect,  // in page coordinates (not cropped)
    val confidence: Float,
    val lineIndex: Int
)

/**
 * Facade that delegates to ML Kit, Tesseract, or Claude Vision based on config.
 */
@Singleton
class OcrEngine @Inject constructor(
    private val mlKitOcr: MLKitOcrEngine,
    private val tesseractOcr: TesseractOcrEngine,
    private val cvProcessor: OpenCVProcessor,
    private val claudeVisionDetector: ClaudeVisionDetector,
    private val geminiVisionDetector: GeminiVisionDetector
) {
    companion object {
        private const val TAG = "OcrEngine"
    }

    suspend fun recognizeText(
        page: PdfPage,
        layoutInfo: OpenCVProcessor.LayoutInfo,
        config: DetectionConfig,
        claudeApiKey: String? = null,
        geminiApiKey: String? = null
    ): List<OcrResult> {
        val results = mutableListOf<OcrResult>()
        val claudeKey = claudeApiKey?.takeIf { it.isNotBlank() }
        val geminiKey = geminiApiKey?.takeIf { it.isNotBlank() }

        val effectiveTop = layoutInfo.headerHeight
        val effectiveBottom = if (layoutInfo.footerStart > layoutInfo.headerHeight)
            layoutInfo.footerStart else page.height

        // Claude/Gemini Vision: send full page as one image.
        // Column-by-column splitting produces narrow images that degrade vision accuracy.
        // Vision models understand multi-column layouts natively.
        if ((config.useClaudeVision || config.ocrEngine == OcrEngineType.CLAUDE_VISION) && claudeKey != null) {
            val fullRegion = Rect(0, effectiveTop, page.width, effectiveBottom)
            val fullBitmap = cvProcessor.cropRegion(page, fullRegion) ?: return results
            val processedBitmap = if (page.bitmapPath.isNotEmpty()) cvProcessor.preprocessForOcr(fullBitmap) else fullBitmap
            val mlKitLines = mlKitOcr.recognizeText(processedBitmap, 0, fullRegion)?.textLines ?: emptyList()
            if (processedBitmap !== fullBitmap) processedBitmap.recycle()
            val claudeResults = claudeVisionDetector.detectQuestions(
                pageBitmap = fullBitmap,
                apiKey = claudeKey,
                config = config,
                columnIndex = 0,
                regionRect = fullRegion,
                mlKitLines = mlKitLines,
                expectedColumns = layoutInfo.columnBoundaries.size
            )
            fullBitmap.recycle()
            return claudeResults
        }

        if ((config.useGeminiVision || config.ocrEngine == OcrEngineType.GEMINI_VISION) && geminiKey != null) {
            val fullRegion = Rect(0, effectiveTop, page.width, effectiveBottom)
            val fullBitmap = cvProcessor.cropRegion(page, fullRegion) ?: return results
            val processedBitmap = if (page.bitmapPath.isNotEmpty()) cvProcessor.preprocessForOcr(fullBitmap) else fullBitmap
            val mlKitLines = mlKitOcr.recognizeText(processedBitmap, 0, fullRegion)?.textLines ?: emptyList()
            if (processedBitmap !== fullBitmap) processedBitmap.recycle()
            val geminiResults = geminiVisionDetector.detectQuestions(
                pageBitmap = fullBitmap,
                apiKey = geminiKey,
                config = config,
                columnIndex = 0,
                regionRect = fullRegion,
                mlKitLines = mlKitLines,
                expectedColumns = layoutInfo.columnBoundaries.size
            )
            fullBitmap.recycle()
            return geminiResults
        }

        // ML Kit / Tesseract / Hybrid: process each detected column separately
        layoutInfo.columnBoundaries.forEachIndexed { colIdx, column ->
            // Guard: footerStart must be greater than headerHeight to produce a valid region.
            // If layout analysis returned degenerate values (e.g. emptyLayout fallback),
            // fall back to the full page extent for this column.
            val regionRect = Rect(
                column.startX,
                effectiveTop,
                column.endX,
                effectiveBottom
            )

            val columnBitmap = cvProcessor.cropRegion(page, regionRect)
                ?: return@forEachIndexed

            // preprocessForOcr (grayscale + CLAHE) is good for ML Kit/Tesseract
            val processedBitmap = if (page.bitmapPath.isNotEmpty()) {
                cvProcessor.preprocessForOcr(columnBitmap)
            } else columnBitmap

            val result = when (config.ocrEngine) {
                OcrEngineType.ML_KIT_PRIMARY -> {
                    mlKitOcr.recognizeText(processedBitmap, colIdx, regionRect)
                        ?: tesseractOcr.recognizeText(processedBitmap, colIdx, regionRect)
                }
                OcrEngineType.TESSERACT_PRIMARY -> {
                    tesseractOcr.recognizeText(processedBitmap, colIdx, regionRect)
                        ?: mlKitOcr.recognizeText(processedBitmap, colIdx, regionRect)
                }
                OcrEngineType.HYBRID -> {
                    val mlResult = mlKitOcr.recognizeText(processedBitmap, colIdx, regionRect)
                    val tessResult = tesseractOcr.recognizeText(processedBitmap, colIdx, regionRect)
                    mergeResults(mlResult, tessResult, colIdx, regionRect)
                }
                else -> null  // CLAUDE_VISION / GEMINI_VISION handled above
            }

            result?.let { results.add(it) }
            // processedBitmap and columnBitmap are different objects when preprocessing ran.
            // Always recycle processedBitmap; recycle columnBitmap only if it differs.
            if (processedBitmap !== columnBitmap) processedBitmap.recycle()
            columnBitmap.recycle()
        }

        return results
    }

    /**
     * Merge ML Kit and Tesseract results by picking the higher-confidence line.
     */
    private fun mergeResults(
        mlResult: OcrResult?,
        tessResult: OcrResult?,
        colIdx: Int,
        regionRect: Rect
    ): OcrResult? {
        if (mlResult == null) return tessResult
        if (tessResult == null) return mlResult

        // Pick lines with higher confidence
        val mergedLines = mutableListOf<OcrLine>()
        val allLines = (mlResult.textLines + tessResult.textLines)
            .groupBy { it.lineIndex }

        allLines.forEach { (_, lines) ->
            mergedLines.add(lines.maxByOrNull { it.confidence } ?: lines.first())
        }

        return OcrResult(
            fullText = mergedLines.joinToString("\n") { it.text },
            textLines = mergedLines.sortedBy { it.boundingBox.top },
            columnIndex = colIdx,
            regionRect = regionRect
        )
    }
}
