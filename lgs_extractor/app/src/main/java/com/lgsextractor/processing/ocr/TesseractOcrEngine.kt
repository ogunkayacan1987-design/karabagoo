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
 * Used as a fallback when ML Kit confidence is low,
 * or as primary engine for heavily scanned PDFs.
 */
@Singleton
class TesseractOcrEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var tessApi: TessBaseAPI? = null
    private var isInitialized = false
    private val tessDataDir: File
        get() = File(context.filesDir, "tessdata")

    private fun initialize() {
        if (isInitialized) return
        try {
            ensureTessData()
            val api = TessBaseAPI()
            val dataPath = context.filesDir.absolutePath
            val success = api.init(dataPath, "tur+eng", TessBaseAPI.OEM_LSTM_ONLY)
            if (success) {
                // Optimize for single-column text
                api.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_COLUMN
                // Set Turkish-specific character whitelist
                api.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST,
                    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz" +
                    "ÇĞİÖŞÜçğışöşü0123456789.,;:!?-_()[]{}+*/=<>@#\$%&\" '\n\t")
                tessApi = api
                isInitialized = true
                android.util.Log.i("TesseractOCR", "Initialized with Turkish model")
            } else {
                api.recycle()
                android.util.Log.e("TesseractOCR", "Tesseract init failed - traineddata missing?")
            }
        } catch (e: Exception) {
            android.util.Log.e("TesseractOCR", "Tesseract initialization error", e)
        }
    }

    /**
     * Copy tessdata from assets to files directory if not already present.
     */
    private fun ensureTessData() {
        tessDataDir.mkdirs()
        val languages = listOf("tur", "eng")
        languages.forEach { lang ->
            val file = File(tessDataDir, "$lang.traineddata")
            if (!file.exists()) {
                try {
                    context.assets.open("tessdata/$lang.traineddata").use { input ->
                        FileOutputStream(file).use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("TesseractOCR", "Could not copy $lang.traineddata: ${e.message}")
                }
            }
        }
    }

    suspend fun recognizeText(
        bitmap: Bitmap,
        columnIndex: Int,
        offsetRect: Rect
    ): OcrResult? = withContext(Dispatchers.Default) {
        initialize()
        val api = tessApi ?: return@withContext null

        try {
            api.setImage(bitmap)
            val text = api.utF8Text ?: return@withContext null

            // Get word boxes for line-level results
            val wordIterator = api.resultIterator
            val lines = mutableListOf<OcrLine>()
            var lineIndex = 0

            if (wordIterator != null) {
                wordIterator.begin()
                do {
                    val lineText = wordIterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                        ?: continue
                    val box = wordIterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                    val conf = wordIterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE) / 100f

                    if (lineText.isNotBlank()) {
                        lines.add(OcrLine(
                            text = lineText.trim(),
                            boundingBox = Rect(
                                box.left + offsetRect.left,
                                box.top + offsetRect.top,
                                box.right + offsetRect.left,
                                box.bottom + offsetRect.top
                            ),
                            confidence = conf,
                            lineIndex = lineIndex++
                        ))
                    }
                } while (wordIterator.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
                wordIterator.delete()
            }

            OcrResult(
                fullText = text,
                textLines = lines.sortedBy { it.boundingBox.top },
                columnIndex = columnIndex,
                regionRect = offsetRect
            )
        } catch (e: Exception) {
            android.util.Log.e("TesseractOCR", "Recognition failed", e)
            null
        }
    }

    fun release() {
        tessApi?.recycle()
        tessApi = null
        isInitialized = false
    }
}
