package com.lgsextractor.processing.cv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.lgsextractor.domain.model.PdfPage
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * OpenCV-based image processing pipeline for layout analysis.
 *
 * Pipeline:
 * 1. Load bitmap from disk
 * 2. Convert to grayscale
 * 3. Adaptive threshold (handles uneven lighting in scanned PDFs)
 * 4. Morphological close (connect text within a line)
 * 5. Find text block contours
 * 6. Detect column structure (1 or 2 columns)
 * 7. Detect horizontal whitespace gaps (potential question boundaries)
 */
@Singleton
class OpenCVProcessor @Inject constructor() {

    data class LayoutInfo(
        val pageWidth: Int,
        val pageHeight: Int,
        val columnCount: Int,
        val columnBoundaries: List<ColumnBoundary>,
        val textBlocks: List<TextBlock>,
        val horizontalGaps: List<HorizontalGap>,
        val hasHeader: Boolean,
        val hasFooter: Boolean,
        val headerHeight: Int = 0,
        val footerStart: Int = 0
    )

    data class ColumnBoundary(
        val startX: Int,
        val endX: Int,
        val columnIndex: Int
    ) {
        val width: Int get() = endX - startX
    }

    data class TextBlock(
        val rect: Rect,
        val columnIndex: Int,
        val estimatedLineCount: Int,
        val hasLargeWhitespace: Boolean = false
    )

    data class HorizontalGap(
        val y: Int,
        val height: Int,
        val columnIndex: Int
    )

    /**
     * Main entry point: analyze the full page layout.
     */
    fun analyzeLayout(page: PdfPage): LayoutInfo {
        val bitmap = BitmapFactory.decodeFile(page.bitmapPath)
            ?: return emptyLayout(page)

        return try {
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)

            val gray = toGrayscale(mat)
            val thresh = adaptiveThreshold(gray)
            val cleaned = morphologicalClose(thresh, lineKernel(page.width))
            val contours = findContours(cleaned)

            val pageW = bitmap.width
            val pageH = bitmap.height

            // Filter to plausible text block contours
            // Imgproc.boundingRect returns org.opencv.core.Rect (x,y,width,height);
            // convert to android.graphics.Rect (left,top,right,bottom) for the rest of the pipeline.
            val textBlocks = contours
                .map { Imgproc.boundingRect(it) }
                .filter { r ->
                    r.width > pageW * 0.05 &&   // min 5% of page width
                    r.height > 8 &&              // min 8px high
                    r.width < pageW * 0.98 &&    // not full page width (page border)
                    r.height < pageH * 0.30      // not suspiciously tall
                }
                .map { r -> android.graphics.Rect(r.x, r.y, r.x + r.width, r.y + r.height) }
                .sortedBy { it.top }

            // Detect columns
            val columnBoundaries = detectColumns(textBlocks, pageW, pageH)
            val columnCount = columnBoundaries.size

            // Assign text blocks to columns
            val assignedBlocks = textBlocks.map { r ->
                val colIdx = columnBoundaries.indexOfFirst { r.left >= it.startX && r.right <= it.endX + 20 }
                    .takeIf { it >= 0 } ?: 0
                TextBlock(
                    rect = r,
                    columnIndex = colIdx,
                    estimatedLineCount = estimateLineCount(r, page.dpi)
                )
            }

            // Find horizontal whitespace gaps per column (potential question separators)
            val gaps = columnBoundaries.flatMapIndexed { colIdx, col ->
                findHorizontalGaps(
                    blocks = assignedBlocks.filter { it.columnIndex == colIdx }.map { it.rect },
                    columnTop = col.startX,
                    pageHeight = pageH,
                    minGapHeight = (page.dpi * 0.05).toInt()  // ~5% of an inch gap
                ).map { it.copy(columnIndex = colIdx) }
            }

            // Detect header/footer
            val headerH = detectHeader(assignedBlocks, pageH)
            val footerStart = detectFooter(assignedBlocks, pageH)

            mat.release(); gray.release(); thresh.release(); cleaned.release()
            bitmap.recycle()

            LayoutInfo(
                pageWidth = pageW,
                pageHeight = pageH,
                columnCount = columnCount,
                columnBoundaries = columnBoundaries,
                textBlocks = assignedBlocks,
                horizontalGaps = gaps,
                hasHeader = headerH > 0,
                hasFooter = footerStart < pageH,
                headerHeight = headerH,
                footerStart = footerStart
            )
        } catch (e: Exception) {
            android.util.Log.e("OpenCVProcessor", "Layout analysis failed", e)
            bitmap.recycle()
            emptyLayout(page)
        }
    }

    /**
     * Get a sub-bitmap crop for a specific region (for passing to OCR).
     */
    fun cropRegion(page: PdfPage, region: Rect): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
            val full = BitmapFactory.decodeFile(page.bitmapPath, opts) ?: return null
            val clampedRect = Rect(
                max(0, region.left),
                max(0, region.top),
                min(full.width, region.right),
                min(full.height, region.bottom)
            )
            Bitmap.createBitmap(full, clampedRect.left, clampedRect.top, clampedRect.width(), clampedRect.height())
                .also { full.recycle() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Apply preprocessing to improve OCR accuracy on scanned images.
     * Returns a new bitmap with better contrast and deskewed if needed.
     */
    /**
     * Apply preprocessing to improve OCR accuracy on scanned images.
     * Uses Gaussian blur for denoising + CLAHE for contrast enhancement.
     * (Note: fastNlMeansDenoising avoided for Maven Central OpenCV compatibility)
     */
    fun preprocessForOcr(bitmap: Bitmap): Bitmap {
        return try {
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)
            val gray = toGrayscale(mat)

            // Gentle Gaussian blur for noise reduction
            val blurred = Mat()
            Imgproc.GaussianBlur(gray, blurred, Size(3.0, 3.0), 0.0)

            // CLAHE for adaptive contrast enhancement
            val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            val enhanced = Mat()
            clahe.apply(blurred, enhanced)

            // Convert back to ARGB bitmap
            val resultMat = Mat()
            Imgproc.cvtColor(enhanced, resultMat, Imgproc.COLOR_GRAY2RGBA)
            val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(resultMat, result)

            mat.release(); gray.release(); blurred.release()
            enhanced.release(); resultMat.release()
            result
        } catch (e: Exception) {
            android.util.Log.w("OpenCVProcessor", "Preprocessing failed, using original", e)
            bitmap
        }
    }

    // ---- Private helpers ----

    private fun toGrayscale(mat: Mat): Mat {
        val gray = Mat()
        if (mat.channels() == 4) {
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
        } else if (mat.channels() == 3) {
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGB2GRAY)
        } else {
            mat.copyTo(gray)
        }
        return gray
    }

    private fun adaptiveThreshold(gray: Mat): Mat {
        val thresh = Mat()
        // Positional args — Java interop does not support named parameters
        Imgproc.adaptiveThreshold(
            gray, thresh, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV,   // INV: text=white, background=black
            15,   // blockSize (must be odd)
            8.0   // C constant
        )
        return thresh
    }

    private fun morphologicalClose(thresh: Mat, kernel: Mat): Mat {
        val closed = Mat()
        Imgproc.morphologyEx(thresh, closed, Imgproc.MORPH_CLOSE, kernel)
        // Also dilate slightly to bridge small gaps
        val dilated = Mat()
        val dilKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(closed, dilated, dilKernel)
        closed.release(); dilKernel.release()
        return dilated
    }

    private fun lineKernel(pageWidth: Int): Mat {
        // Horizontal kernel sized to ~40% of line width to connect text in a line
        val kernelW = (pageWidth * 0.06).toInt().coerceAtLeast(20)
        return Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(kernelW.toDouble(), 3.0))
    }

    private fun findContours(thresh: Mat): List<MatOfPoint> {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            thresh, contours, hierarchy,
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE
        )
        hierarchy.release()
        return contours
    }

    /**
     * Detect whether the page has 1 or 2 columns.
     *
     * Strategy: Look at the horizontal histogram of text presence.
     * A vertical white band in the center ≈ two-column layout.
     */
    private fun detectColumns(
        blocks: List<android.graphics.Rect>,
        pageWidth: Int,
        pageHeight: Int
    ): List<ColumnBoundary> {
        if (blocks.isEmpty()) return listOf(ColumnBoundary(0, pageWidth, 0))

        // Build a column occupancy histogram (divide page into 100 vertical slices)
        val sliceCount = 100
        val sliceWidth = pageWidth.toDouble() / sliceCount
        val occupancy = IntArray(sliceCount)

        for (block in blocks) {
            val startSlice = (block.left / sliceWidth).toInt().coerceIn(0, sliceCount - 1)
            val endSlice = (block.right / sliceWidth).toInt().coerceIn(0, sliceCount - 1)
            for (s in startSlice..endSlice) occupancy[s]++
        }

        // Find the center region (30%-70% of page) with minimum occupancy
        val centerStart = (sliceCount * 0.30).toInt()
        val centerEnd = (sliceCount * 0.70).toInt()
        val centerMin = occupancy.slice(centerStart..centerEnd).minOrNull() ?: 0
        val centerAvg = occupancy.slice(centerStart..centerEnd).average()

        // If center region has significantly lower density → two-column layout
        val isTwoColumn = centerMin == 0 && centerAvg < occupancy.average() * 0.4

        if (!isTwoColumn) {
            // Single column: use left/right margin trimmed bounds
            val margin = (pageWidth * 0.04).toInt()
            return listOf(ColumnBoundary(margin, pageWidth - margin, 0))
        }

        // Find the gap center X
        val gapCenter = (centerStart..centerEnd)
            .minByOrNull { occupancy[it] }
            ?.let { (it * sliceWidth).toInt() } ?: (pageWidth / 2)

        val margin = (pageWidth * 0.03).toInt()
        return listOf(
            ColumnBoundary(margin, gapCenter - margin, 0),
            ColumnBoundary(gapCenter + margin, pageWidth - margin, 1)
        )
    }

    /**
     * Find horizontal white gaps between text blocks in a column.
     * These gaps are candidate boundaries between questions.
     */
    private fun findHorizontalGaps(
        blocks: List<android.graphics.Rect>,
        columnTop: Int,
        pageHeight: Int,
        minGapHeight: Int
    ): List<HorizontalGap> {
        if (blocks.isEmpty()) return emptyList()
        val sorted = blocks.sortedBy { it.top }
        val gaps = mutableListOf<HorizontalGap>()

        var prevBottom = sorted.first().top
        for (block in sorted) {
            val gapHeight = block.top - prevBottom
            if (gapHeight >= minGapHeight) {
                gaps.add(HorizontalGap(y = prevBottom, height = gapHeight, columnIndex = 0))
            }
            prevBottom = max(prevBottom, block.bottom)
        }
        return gaps
    }

    private fun estimateLineCount(rect: android.graphics.Rect, dpi: Int): Int {
        // At 300 DPI, standard 12pt text is ~50px tall. Line height ~60px.
        val lineHeightPx = (dpi * 12 / 72.0).toInt().coerceAtLeast(20)
        return max(1, rect.height() / lineHeightPx)
    }

    private fun detectHeader(blocks: List<TextBlock>, pageH: Int): Int {
        // Header = blocks in top 8% of page
        val threshold = pageH * 0.08
        val headerBlocks = blocks.filter { it.rect.bottom < threshold }
        return if (headerBlocks.isEmpty()) 0 else headerBlocks.maxOf { it.rect.bottom }
    }

    private fun detectFooter(blocks: List<TextBlock>, pageH: Int): Int {
        val threshold = pageH * 0.92
        val footerBlocks = blocks.filter { it.rect.top > threshold }
        return if (footerBlocks.isEmpty()) pageH else footerBlocks.minOf { it.rect.top }
    }

    private fun emptyLayout(page: PdfPage) = LayoutInfo(
        pageWidth = page.width, pageHeight = page.height,
        columnCount = 1,
        columnBoundaries = listOf(ColumnBoundary(0, page.width, 0)),
        textBlocks = emptyList(), horizontalGaps = emptyList(),
        hasHeader = false, hasFooter = false
    )
}
