package com.lgsextractor.processing.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class MLKitOcrEngine @Inject constructor() {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Run ML Kit OCR on the given bitmap.
     * @param offsetRect The position of this bitmap region in the full page coordinate space.
     */
    suspend fun recognizeText(
        bitmap: Bitmap,
        columnIndex: Int,
        offsetRect: Rect
    ): OcrResult? = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val lines = mutableListOf<OcrLine>()
                var lineIndex = 0

                visionText.textBlocks.forEach { block ->
                    block.lines.forEach { line ->
                        // Translate from cropped-region coords to full page coords
                        val box = line.boundingBox?.let { lb ->
                            Rect(
                                lb.left + offsetRect.left,
                                lb.top + offsetRect.top,
                                lb.right + offsetRect.left,
                                lb.bottom + offsetRect.top
                            )
                        } ?: Rect(offsetRect.left, offsetRect.top, offsetRect.right, offsetRect.bottom)

                        lines.add(OcrLine(
                            text = line.text,
                            boundingBox = box,
                            confidence = line.confidence ?: 0.8f,
                            lineIndex = lineIndex++
                        ))
                    }
                }

                val fullText = lines.joinToString("\n") { it.text }
                cont.resume(OcrResult(
                    fullText = fullText,
                    textLines = lines,
                    columnIndex = columnIndex,
                    regionRect = offsetRect
                ))
            }
            .addOnFailureListener { e ->
                android.util.Log.e("MLKitOCR", "Recognition failed", e)
                cont.resume(null)
            }

        cont.invokeOnCancellation { }
    }

    fun close() {
        recognizer.close()
    }
}
