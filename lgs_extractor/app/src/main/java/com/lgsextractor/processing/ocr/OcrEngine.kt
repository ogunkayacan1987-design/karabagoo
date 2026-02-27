package com.lgsextractor.processing.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
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
 * Facade that delegates to ML Kit or Tesseract based on config.
 */
@Singleton
class OcrEngine @Inject constructor(
    private val mlKitOcr: MLKitOcrEngine,
    private val tesseractOcr: TesseractOcrEngine,
    private val cvProcessor: OpenCVProcessor
) {
    suspend fun recognizeText(
        page: PdfPage,
        layoutInfo: OpenCVProcessor.LayoutInfo,
        config: DetectionConfig
    ): List<OcrResult> {
        val results = mutableListOf<OcrResult>()

        // Process each detected column separately for better accuracy
        layoutInfo.columnBoundaries.forEachIndexed { colIdx, column ->
            val regionRect = Rect(
                column.startX,
                layoutInfo.headerHeight,
                column.endX,
                layoutInfo.footerStart
            )

            val columnBitmap = cvProcessor.cropRegion(page, regionRect)
                ?: return@forEachIndexed

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
