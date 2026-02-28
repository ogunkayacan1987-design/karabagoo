package com.lgsextractor.processing.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

        // Process each detected column separately for better accuracy
        layoutInfo.columnBoundaries.forEachIndexed { colIdx, column ->
            // Guard: footerStart must be greater than headerHeight to produce a valid region.
            // If layout analysis returned degenerate values (e.g. emptyLayout fallback),
            // fall back to the full page extent for this column.
            val effectiveTop = layoutInfo.headerHeight
            val effectiveBottom = if (layoutInfo.footerStart > layoutInfo.headerHeight)
                layoutInfo.footerStart else page.height

            val regionRect = Rect(
                column.startX,
                effectiveTop,
                column.endX,
                effectiveBottom
            )

            val columnBitmap = cvProcessor.cropRegion(page, regionRect)
                ?: return@forEachIndexed

            val processedBitmap = if (page.bitmapPath.isNotEmpty()) {
                cvProcessor.preprocessForOcr(columnBitmap)
            } else columnBitmap

            val result = when {
                config.useGeminiVision || config.ocrEngine == OcrEngineType.GEMINI_VISION -> {
                    if (geminiKey != null) {
                        // Obtain baseline bounding boxes from ML Kit first
                        val mlKitResult = mlKitOcr.recognizeText(processedBitmap, colIdx, regionRect)
                        val mlKitLines = mlKitResult?.textLines ?: emptyList()

                        val geminiResults = geminiVisionDetector.detectQuestions(
                            pageBitmap = processedBitmap,
                            apiKey = geminiKey,
                            config = config,
                            columnIndex = colIdx,
                            regionRect = regionRect,
                            mlKitLines = mlKitLines
                        )
                        if (geminiResults.isNotEmpty()) geminiResults.first() else null
                    } else null
                }
                config.useClaudeVision || config.ocrEngine == OcrEngineType.CLAUDE_VISION -> {
                    if (claudeKey != null) {
                        // Obtain baseline bounding boxes from ML Kit first
                        val mlKitResult = mlKitOcr.recognizeText(processedBitmap, colIdx, regionRect)
                        val mlKitLines = mlKitResult?.textLines ?: emptyList()

                        val claudeResults = claudeVisionDetector.detectQuestions(
                            pageBitmap = processedBitmap,
                            apiKey = claudeKey,
                            config = config,
                            columnIndex = colIdx,
                            regionRect = regionRect,
                            mlKitLines = mlKitLines
                        )
                        if (claudeResults.isNotEmpty()) claudeResults.first() else null
                    } else null
                }
                config.ocrEngine == OcrEngineType.ML_KIT_PRIMARY -> {
                    mlKitOcr.recognizeText(processedBitmap, colIdx, regionRect)
                        ?: tesseractOcr.recognizeText(processedBitmap, colIdx, regionRect)
                }
                config.ocrEngine == OcrEngineType.TESSERACT_PRIMARY -> {
                    tesseractOcr.recognizeText(processedBitmap, colIdx, regionRect)
                        ?: mlKitOcr.recognizeText(processedBitmap, colIdx, regionRect)
                }
                config.ocrEngine == OcrEngineType.HYBRID -> {
                    val mlResult = mlKitOcr.recognizeText(processedBitmap, colIdx, regionRect)
                    val tessResult = tesseractOcr.recognizeText(processedBitmap, colIdx, regionRect)
                    mergeResults(mlResult, tessResult, colIdx, regionRect)
                }
                else -> null
            }

            result?.let { results.add(it) }
            processedBitmap.recycle()
            if (processedBitmap !== columnBitmap) columnBitmap.recycle()
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
