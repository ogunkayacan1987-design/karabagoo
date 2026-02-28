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
     * Each line is given a unique Y coordinate so QuestionBoundaryInferencer
     * can correctly segment questions. When ML Kit lines are provided,
     * real pixel positions are preferred over virtual ones.
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

            val lines = mutableListOf<OcrLine>()
            val fullTextParts = mutableListOf<String>()

            val qCount = questionsArray.length()
            // Distribute questions evenly across the page height for virtual Y positions
            val sliceHeight = if (qCount > 0) regionRect.height() / qCount else regionRect.height()

            for (i in 0 until qCount) {
                val q = questionsArray.getJSONObject(i)
                val number = q.optInt("number", i + 1)
                val text = q.optString("text", "")
                val confidence = q.optDouble("confidence", 0.85).toFloat()

                val optionsObj = q.optJSONObject("options")
                val optKeys = listOf("A", "B", "C", "D")
                    .filter { optionsObj?.optString(it, "")?.isNotBlank() == true }

                val linesForThisQ = 1 + optKeys.size
                val lineH = if (linesForThisQ > 0) sliceHeight / linesForThisQ else VIRTUAL_LINE_HEIGHT_PX
                var currentY = regionRect.top + (i * sliceHeight)

                val questionHeader = "$number. $text"
                fullTextParts.add(questionHeader)

                val defaultHeaderRect = Rect(regionRect.left, currentY, regionRect.right, currentY + lineH - 1)
                val headerRect = findMatchingRect(text, mlKitLines, defaultHeaderRect)
                lines.add(OcrLine(
                    text = questionHeader,
                    boundingBox = headerRect,
                    confidence = confidence,
                    lineIndex = lines.size
                ))
                currentY += lineH

                if (optionsObj != null) {
                    for (optKey in optKeys) {
                        val optText = optionsObj.optString(optKey, "")
                        val optLine = "$optKey) $optText"
                        fullTextParts.add(optLine)

                        val defaultOptRect = Rect(regionRect.left, currentY, regionRect.right, currentY + lineH - 1)
                        val optRect = findMatchingRect(optText, mlKitLines, defaultOptRect)
                        lines.add(OcrLine(
                            text = optLine,
                            boundingBox = optRect,
                            confidence = confidence,
                            lineIndex = lines.size
                        ))
                        currentY += lineH
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
