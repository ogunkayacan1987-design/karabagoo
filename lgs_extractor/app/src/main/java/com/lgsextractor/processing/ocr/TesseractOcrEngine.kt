package com.lgsextractor.processing.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tesseract OCR engine with Turkish language model.
 *
 * Requires: assets/tessdata/tur.traineddata
 * Download from: https://github.com/tesseract-ocr/tessdata/blob/main/tur.traineddata
 *
 * Uses the simplified Tesseract4Android API for maximum version compatibility.
 */
@Singleton
class TesseractOcrEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var tessApi: TessBaseAPI? = null
    private var isInitialized = false

    // Tesseract4Android page seg modes (int constants)
    private val PSM_SINGLE_COLUMN = 4
    private val OEM_LSTM_ONLY = 2

    private fun initialize() {
        if (isInitialized) return
        try {
            ensureTessData()
            val api = TessBaseAPI()
            val dataPath = context.filesDir.absolutePath
            val success = api.init(dataPath, "tur+eng", OEM_LSTM_ONLY)
            if (success) {
                api.pageSegMode = PSM_SINGLE_COLUMN
                tessApi = api
                isInitialized = true
                android.util.Log.i("TesseractOCR", "Initialized with tur+eng model")
            } else {
                api.recycle()
                android.util.Log.w("TesseractOCR", "Tesseract init failed — traineddata missing?")
            }
        } catch (e: Exception) {
            android.util.Log.e("TesseractOCR", "Initialization error", e)
        }
    }

    /**
     * Copy tessdata from assets to app's files directory if not already present.
     * Silently skips if the asset is missing (ML Kit will be used as primary OCR).
     */
    private fun ensureTessData() {
        val tessDataDir = File(context.filesDir, "tessdata").also { it.mkdirs() }
        listOf("tur", "eng").forEach { lang ->
            val dest = File(tessDataDir, "$lang.traineddata")
            if (!dest.exists()) {
                try {
                    context.assets.open("tessdata/$lang.traineddata").use { src ->
                        FileOutputStream(dest).use { src.copyTo(it) }
                    }
                    android.util.Log.i("TesseractOCR", "Copied $lang.traineddata")
                } catch (e: Exception) {
                    android.util.Log.w("TesseractOCR", "$lang.traineddata not found in assets: ${e.message}")
                }
            }
        }
    }

    /**
     * Run Tesseract OCR on the bitmap and return line-level results.
     * Uses full-text extraction + simple line splitting for compatibility.
     */
    suspend fun recognizeText(
        bitmap: Bitmap,
        columnIndex: Int,
        offsetRect: Rect
    ): OcrResult? = withContext(Dispatchers.Default) {
        initialize()
        val api = tessApi ?: return@withContext null

        try {
            api.setImage(bitmap)

            // Get full text — this is the reliable Tesseract4Android API
            val fullText = api.utF8Text ?: return@withContext null
            if (fullText.isBlank()) return@withContext null

            // Split into lines and estimate Y positions proportionally
            val textLines = splitIntoLines(fullText, bitmap.height, offsetRect)

            OcrResult(
                fullText = fullText,
                textLines = textLines,
                columnIndex = columnIndex,
                regionRect = offsetRect
            )
        } catch (e: Exception) {
            android.util.Log.e("TesseractOCR", "Recognition failed", e)
            null
        }
    }

    /**
     * Split full OCR text into line objects with estimated Y coordinates.
     * Since Tesseract's ResultIterator API varies by version, we estimate
     * Y positions by distributing lines uniformly across the bitmap height.
     */
    private fun splitIntoLines(
        fullText: String,
        bitmapHeight: Int,
        offsetRect: Rect
    ): List<OcrLine> {
        val rawLines = fullText.lines().filter { it.isNotBlank() }
        if (rawLines.isEmpty()) return emptyList()

        val lineHeight = bitmapHeight.toFloat() / rawLines.size

        return rawLines.mapIndexed { index, lineText ->
            val estimatedTop = (index * lineHeight + offsetRect.top).toInt()
            val estimatedBottom = ((index + 1) * lineHeight + offsetRect.top).toInt()
            OcrLine(
                text = lineText.trim(),
                boundingBox = Rect(
                    offsetRect.left,
                    estimatedTop,
                    offsetRect.right,
                    estimatedBottom
                ),
                confidence = 0.70f,  // default Tesseract confidence
                lineIndex = index
            )
        }
    }

    fun release() {
        tessApi?.recycle()
        tessApi = null
        isInitialized = false
    }
}
