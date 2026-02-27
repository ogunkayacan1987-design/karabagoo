package com.lgsextractor.processing.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import com.lgsextractor.domain.model.DetectionConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
     */
    suspend fun detectQuestions(
        pageBitmap: Bitmap,
        apiKey: String,
        config: DetectionConfig,
        columnIndex: Int = 0,
        regionRect: Rect = Rect(0, 0, pageBitmap.width, pageBitmap.height)
    ): List<OcrResult> {
        val base64Image = bitmapToBase64(pageBitmap)
        val prompt = buildPrompt()
        val requestBody = buildRequestBody(base64Image, prompt, config)

        logToFile("Starting Claude API call for column: $columnIndex, model: ${config.claudeModel}")

        val request = Request.Builder()
            .url(API_URL)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("content-type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val response = httpClient.newCall(request).execute()
                val bodyStr = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    logToFile("API ERROR ${response.code}: $bodyStr")
                    return@withContext emptyList()
                }

                logToFile("API SUCCESS: Received response length = ${bodyStr.length} chars")
                logToFile("RAW JSON DUMP START:\n$bodyStr\nRAW JSON DUMP END")

                val results = parseApiResponse(bodyStr, columnIndex, regionRect)
                logToFile("Parsed ${results.firstOrNull()?.textLines?.size ?: 0} OCR lines from response")
                results
            } catch (e: Exception) {
                logToFile("EXCEPTION during API call or parsing: ${e.message}\n${e.stackTraceToString()}")
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

    private fun parseApiResponse(bodyStr: String, columnIndex: Int, regionRect: Rect): List<OcrResult> {
        return try {
            val root = JSONObject(bodyStr)
            val contentArray = root.getJSONArray("content")
            val textContent = (0 until contentArray.length())
                .mapNotNull { i ->
                    val item = contentArray.getJSONObject(i)
                    if (item.getString("type") == "text") item.getString("text") else null
                }
                .firstOrNull() ?: return emptyList()

            // Extract JSON from the response text
            val jsonStart = textContent.indexOf('{')
            val jsonEnd = textContent.lastIndexOf('}')
            if (jsonStart < 0 || jsonEnd < 0) return emptyList()

            val jsonStr = textContent.substring(jsonStart, jsonEnd + 1)
            val parsed = JSONObject(jsonStr)
            val questionsArray = parsed.getJSONArray("questions")

            val lines = mutableListOf<OcrLine>()
            val fullTextParts = mutableListOf<String>()
            var lineIndex = 0

            val qCount = questionsArray.length()
            val sliceHeight = if (qCount > 0) regionRect.height() / qCount else 0

            for (i in 0 until qCount) {
                val q = questionsArray.getJSONObject(i)
                val number = q.optInt("number", i + 1)
                val text = q.optString("text", "")
                val confidence = q.optDouble("confidence", 0.85).toFloat()

                val optionsObj = q.optJSONObject("options")
                val optKeys = listOf("A", "B", "C", "D").filter { optionsObj?.optString(it, "")?.isNotBlank() == true }
                
                val linesForThisQ = 1 + optKeys.size
                val lineH = if (linesForThisQ > 0) sliceHeight / linesForThisQ else sliceHeight
                var currentY = regionRect.top + (i * sliceHeight)

                val questionHeader = "$number. $text"
                fullTextParts.add(questionHeader)

                lines.add(OcrLine(
                    text = questionHeader,
                    boundingBox = Rect(regionRect.left, currentY, regionRect.right, currentY + lineH - 1),
                    confidence = confidence,
                    lineIndex = lineIndex++
                ))
                currentY += lineH

                if (optionsObj != null) {
                    for (optKey in optKeys) {
                        val optText = optionsObj.optString(optKey, "")
                        val optLine = "$optKey) $optText"
                        fullTextParts.add(optLine)
                        lines.add(OcrLine(
                            text = optLine,
                            boundingBox = Rect(regionRect.left, currentY, regionRect.right, currentY + lineH - 1),
                            confidence = confidence,
                            lineIndex = lineIndex++
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
            Log.e(TAG, "Failed to parse Claude response", e)
            emptyList()
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        // Scale down if too large to stay within API limits (~5MB)
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
