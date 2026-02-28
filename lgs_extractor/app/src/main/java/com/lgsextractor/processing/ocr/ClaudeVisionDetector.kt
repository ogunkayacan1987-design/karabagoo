package com.lgsextractor.processing.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import com.lgsextractor.domain.model.DetectionConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uses Claude Vision API to detect and extract questions from a PDF page bitmap.
 * Sends the bitmap as base64 and parses structured JSON response.
 */
@Singleton
class ClaudeVisionDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "ClaudeVisionDetector"
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val VIRTUAL_LINE_HEIGHT_PX = 60
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun logToFile(message: String) {
        try {
            val logFile = File(context.getExternalFilesDir(null), "claude_debug_log.txt")
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            logFile.appendText("[$time] $message\n")
            Log.d(TAG, message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write debug log", e)
        }
    }

    /**
     * Sends a page bitmap to Claude Vision and returns detected questions as OcrResult list.
     * @param pageBitmap   The rendered PDF page bitmap
     * @param apiKey       Claude API key
     * @param config       DetectionConfig with model/token settings
     * @param columnIndex  Column index for the OcrResult
     * @param regionRect   Region rect for the OcrResult
     * @param mlKitLines   ML Kit OCR lines used to anchor Claude results to real pixel positions
     * @return List of OcrResult on success, empty list on failure
     */
    suspend fun detectQuestions(
        pageBitmap: Bitmap,
        apiKey: String,
        config: DetectionConfig,
        columnIndex: Int = 0,
        regionRect: Rect = Rect(0, 0, pageBitmap.width, pageBitmap.height),
        mlKitLines: List<OcrLine> = emptyList()
    ): List<OcrResult> {
        // bitmapToBase64 is CPU-bound; keep it off Main thread
        val base64Image = withContext(Dispatchers.IO) { bitmapToBase64(pageBitmap) }

        val prompt = buildPrompt()
        val requestBody = buildRequestBody(base64Image, prompt, config)

        logToFile("Starting Claude API call: column=$columnIndex model=${config.claudeModel} mlKitLines=${mlKitLines.size}")

        val request = Request.Builder()
            .url(API_URL)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("content-type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        // OkHttp execute() is blocking — MUST run on IO dispatcher to avoid
        // NetworkOnMainThreadException when called from viewModelScope (Main).
        return withContext(Dispatchers.IO) {
            try {
                val response = httpClient.newCall(request).execute()
                val bodyStr = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    logToFile("API ERROR ${response.code}: $bodyStr")
                    return@withContext emptyList()
                }

                logToFile("API SUCCESS: ${bodyStr.length} chars")
                logToFile("RAW JSON:\n$bodyStr")

                val results = parseApiResponse(bodyStr, columnIndex, regionRect, mlKitLines)
                logToFile("Parsed ${results.firstOrNull()?.textLines?.size ?: 0} OCR lines")
                results
            } catch (e: Exception) {
                logToFile("EXCEPTION: ${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString()}")
                emptyList()
            }
        }
    }

    private fun buildPrompt(): String = """
        Bu bir Türkçe LGS (Liselere Geçiş Sınavı) sınav sayfasının görüntüsüdür.
        Sayfadaki tüm soruları tespit et ve her sorunun tam metnini çıkar.

        Yanıtını aşağıdaki JSON formatında ver:
        {
          "questions": [
            {
              "number": 1,
              "text": "Soru metni buraya...",
              "options": {
                "A": "A şıkkı metni",
                "B": "B şıkkı metni",
                "C": "C şıkkı metni",
                "D": "D şıkkı metni"
              },
              "confidence": 0.95
            }
          ]
        }

        Sadece JSON yanıt ver, açıklama ekleme. Eğer sayfa hiç soru içermiyorsa {"questions": []} döndür.
    """.trimIndent()

    private fun buildRequestBody(base64Image: String, prompt: String, config: DetectionConfig): String {
        val imageContent = JSONObject().apply {
            put("type", "image")
            put("source", JSONObject().apply {
                put("type", "base64")
                put("media_type", "image/jpeg")
                put("data", base64Image)
            })
        }

        val textContent = JSONObject().apply {
            put("type", "text")
            put("text", prompt)
        }

        val message = JSONObject().apply {
            put("role", "user")
            put("content", JSONArray().apply {
                put(imageContent)
                put(textContent)
            })
        }

        return JSONObject().apply {
            put("model", config.claudeModel)
            put("max_tokens", config.claudeMaxTokens)
            put("messages", JSONArray().apply { put(message) })
        }.toString()
    }

    /**
     * Tries to find a matching ML Kit line by text similarity so that
     * Claude results can be anchored to real pixel coordinates.
     * Falls back to [defaultRect] if no suitable match is found.
     */
    private fun findMatchingRect(text: String, mlKitLines: List<OcrLine>, defaultRect: Rect): Rect {
        if (text.isBlank() || mlKitLines.isEmpty()) return defaultRect

        val normTarget = text.lowercase(Locale.getDefault()).replace(Regex("[^\\p{L}\\p{Nd}]"), "")
        if (normTarget.length < 3) return defaultRect

        val targetPrefix = normTarget.take(15)

        val match = mlKitLines.maxByOrNull { line ->
            val normLine = line.text.lowercase(Locale.getDefault()).replace(Regex("[^\\p{L}\\p{Nd}]"), "")
            when {
                normLine.contains(targetPrefix) -> 100
                normTarget.contains(normLine.take(15)) && normLine.length >= 10 -> 90
                else -> normLine.commonPrefixWith(normTarget).length
            }
        }

        if (match != null) {
            val normLine = match.text.lowercase(Locale.getDefault()).replace(Regex("[^\\p{L}\\p{Nd}]"), "")
            val score = when {
                normLine.contains(targetPrefix) -> 100
                normTarget.contains(normLine.take(15)) && normLine.length >= 10 -> 90
                else -> normLine.commonPrefixWith(normTarget).length
            }
            if (score >= 5) return match.boundingBox
        }
        return defaultRect
    }

    /**
     * Converts Claude's structured JSON response to OcrResult.
     *
     * Two-pass approach for accurate bounding boxes:
     * 1) Find each question header's real Y using ML Kit text matching
     * 2) Extend each question's bounding box down to the next question's header Y
     *
     * Falls back to evenly-distributed virtual Y when ML Kit has no matches.
     */
    private fun parseApiResponse(
        bodyStr: String,
        columnIndex: Int,
        regionRect: Rect,
        mlKitLines: List<OcrLine> = emptyList()
    ): List<OcrResult> {
        return try {
            val root = JSONObject(bodyStr)
            val contentArray = root.getJSONArray("content")
            val textContent = (0 until contentArray.length())
                .mapNotNull { i ->
                    val item = contentArray.getJSONObject(i)
                    if (item.getString("type") == "text") item.getString("text") else null
                }
                .firstOrNull() ?: return emptyList()

            // Extract JSON block (Claude may prepend/append prose)
            val jsonStart = textContent.indexOf('{')
            val jsonEnd = textContent.lastIndexOf('}')
            if (jsonStart < 0 || jsonEnd < 0) {
                Log.w(TAG, "No JSON object in Claude response: $textContent")
                return emptyList()
            }

            val jsonStr = textContent.substring(jsonStart, jsonEnd + 1)
            val parsed = JSONObject(jsonStr)
            val questionsArray = parsed.getJSONArray("questions")

            Log.d(TAG, "Claude returned ${questionsArray.length()} questions")

            val qCount = questionsArray.length()
            if (qCount == 0) return emptyList()

            // --- Data class for first pass ---
            data class QData(
                val number: Int,
                val text: String,
                val confidence: Float,
                val optKeys: List<String>,
                val optionsObj: org.json.JSONObject?
            )

            val qDataList = (0 until qCount).map { i ->
                val q = questionsArray.getJSONObject(i)
                val optionsObj = q.optJSONObject("options")
                QData(
                    number = q.optInt("number", i + 1),
                    text = q.optString("text", ""),
                    confidence = q.optDouble("confidence", 0.85).toFloat(),
                    optKeys = listOf("A", "B", "C", "D")
                        .filter { optionsObj?.optString(it, "")?.isNotBlank() == true },
                    optionsObj = optionsObj
                )
            }

            // --- Pass 1: Resolve header Y positions ---
            // Try to find each question's real Y from ML Kit; fall back to evenly-spaced virtual Y.
            val sliceHeight = regionRect.height() / qCount
            val headerYList: List<Int> = qDataList.mapIndexed { i, qData ->
                val virtualY = regionRect.top + i * sliceHeight
                val defaultRect = Rect(regionRect.left, virtualY, regionRect.right, virtualY + VIRTUAL_LINE_HEIGHT_PX)
                // Look for the question number pattern in ML Kit ("4." or "4 .")
                val numberMatch = findMatchingRect("${qData.number}.", mlKitLines, defaultRect)
                if (numberMatch !== defaultRect) {
                    numberMatch.top
                } else {
                    findMatchingRect(qData.text, mlKitLines, defaultRect).top
                }
            }

            // --- Pass 2: Build OcrLines with accurate bounding boxes ---
            val lines = mutableListOf<OcrLine>()
            val fullTextParts = mutableListOf<String>()

            for ((i, qData) in qDataList.withIndex()) {
                val qTop = headerYList[i]
                // Question bottom = next question's header Y - 1, or column bottom for last question
                val qBottom = if (i + 1 < qCount) (headerYList[i + 1] - 1) else regionRect.bottom

                val linesInQ = 1 + qData.optKeys.size
                val lineH = if (linesInQ > 0) (qBottom - qTop) / linesInQ else VIRTUAL_LINE_HEIGHT_PX

                val questionHeader = "${qData.number}. ${qData.text}"
                fullTextParts.add(questionHeader)

                lines.add(OcrLine(
                    text = questionHeader,
                    boundingBox = Rect(regionRect.left, qTop, regionRect.right, qTop + lineH),
                    confidence = qData.confidence,
                    lineIndex = lines.size
                ))

                var optY = qTop + lineH
                if (qData.optionsObj != null) {
                    for (optKey in qData.optKeys) {
                        val optText = qData.optionsObj.optString(optKey, "")
                        val optLine = "$optKey) $optText"
                        fullTextParts.add(optLine)
                        lines.add(OcrLine(
                            text = optLine,
                            boundingBox = Rect(regionRect.left, optY, regionRect.right, optY + lineH),
                            confidence = qData.confidence,
                            lineIndex = lines.size
                        ))
                        optY += lineH
                    }
                }
            }

            if (lines.isEmpty()) return emptyList()

            listOf(OcrResult(
                fullText = fullTextParts.joinToString("\n"),
                textLines = lines,
                columnIndex = columnIndex,
                regionRect = regionRect
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Claude response: $bodyStr", e)
            emptyList()
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        val maxDim = 2000
        val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
            val ratio = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt(),
                (bitmap.height * ratio).toInt(),
                true
            )
        } else bitmap

        scaled.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        if (scaled !== bitmap) scaled.recycle()

        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
}
