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
 * Uses Gemini Vision API to detect and extract questions from a PDF page bitmap.
 * Sends the bitmap as base64 and parses structured JSON response.
 */
@Singleton
class GeminiVisionDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "GeminiVisionDetector"
        private const val API_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun logToFile(message: String) {
        try {
            val logFile = File(context.getExternalFilesDir(null), "gemini_debug_log.txt")
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            logFile.appendText("[$time] $message\n")
            Log.d(TAG, message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write debug log", e)
        }
    }

    /**
     * Sends a page bitmap to Gemini Vision and returns detected questions as OcrResult list.
     */
    suspend fun detectQuestions(
        pageBitmap: Bitmap,
        apiKey: String,
        config: DetectionConfig,
        columnIndex: Int = 0,
        regionRect: Rect = Rect(0, 0, pageBitmap.width, pageBitmap.height),
        mlKitLines: List<OcrLine> = emptyList()
    ): List<OcrResult> {
        val base64Image = bitmapToBase64(pageBitmap)
        val prompt = buildPrompt()
        val requestBody = buildRequestBody(base64Image, prompt)

        logToFile("Starting Gemini API call for column: $columnIndex, model: ${config.geminiModel}, mlKitLines: ${mlKitLines.size}")

        val url = "$API_BASE_URL${config.geminiModel}:generateContent?key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        return withContext(Dispatchers.IO) {
            try {
                val response = httpClient.newCall(request).execute()
                val bodyStr = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    val errMsg = "API ERROR ${response.code}: $bodyStr"
                    logToFile(errMsg)
                    throw Exception("Gemini API Hatası (${response.code}): Lütfen model adını veya API anahtarını kontrol edin. Detay: ${bodyStr.take(100)}")
                }

                logToFile("API SUCCESS: Received response length = ${bodyStr.length} chars")
                logToFile("RAW JSON DUMP START:\n$bodyStr\nRAW JSON DUMP END")

                val results = parseApiResponse(bodyStr, columnIndex, regionRect, mlKitLines)
                logToFile("Parsed ${results.firstOrNull()?.textLines?.size ?: 0} OCR lines from response")
                results
            } catch (e: Exception) {
                logToFile("EXCEPTION during API call or parsing: ${e.message}\n${e.stackTraceToString()}")
                throw e
            }
        }
    }

    private fun buildPrompt(): String = """
        Bu bir Türkçe LGS (Liselere Geçiş Sınavı) sınav sayfasının görüntüsüdür.
        Sayfadaki tüm soruları tespit et ve her sorunun tam metnini çıkar.

        Yanıtını aşağıdaki JSON formatında ver:
        ```json
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
        ```

        Sadece JSON yanıt ver, açıklama ekleme. Eğer sayfa hiç soru içermiyorsa {"questions": []} döndür.
    """.trimIndent()

    private fun buildRequestBody(base64Image: String, prompt: String): String {
        val textPart = JSONObject().apply {
            put("text", prompt)
        }
        
        val inlineDataPart = JSONObject().apply {
            put("inlineData", JSONObject().apply {
                put("mimeType", "image/jpeg")
                put("data", base64Image)
            })
        }
        
        val content = JSONObject().apply {
            put("parts", JSONArray().apply {
                put(textPart)
                put(inlineDataPart)
            })
        }
        
        return JSONObject().apply {
            put("contents", JSONArray().apply { put(content) })
        }.toString()
    }

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
            if (normLine.contains(targetPrefix) || normTarget.contains(normLine.take(15)) || normLine.commonPrefixWith(normTarget).length >= 5) {
                return match.boundingBox
            }
        }
        return defaultRect
    }

    private fun parseApiResponse(bodyStr: String, columnIndex: Int, regionRect: Rect, mlKitLines: List<OcrLine>): List<OcrResult> {
        try {
            val root = JSONObject(bodyStr)
            val candidates = root.optJSONArray("candidates") 
                ?: throw Exception("Gemini API yanıtında 'candidates' dizisi bulunamadı. Yanıt: ${bodyStr.take(100)}")
            
            if (candidates.length() == 0) throw Exception("Gemini API boş 'candidates' döndürdü.")
            
            val content = candidates.getJSONObject(0).optJSONObject("content") 
                ?: throw Exception("Aday yanıtında 'content' objesi yok.")
            
            val parts = content.optJSONArray("parts") 
                ?: throw Exception("Content içinde 'parts' dizisi yok.")
                
            if (parts.length() == 0) throw Exception("Parts dizisi boş.")
            
            val textContent = parts.getJSONObject(0).optString("text", "")

            // Extract JSON from the response text
            val jsonStart = textContent.indexOf('{')
            val jsonEnd = textContent.lastIndexOf('}')
            if (jsonStart < 0 || jsonEnd < 0) {
                if (textContent.isBlank()) {
                    throw Exception("Gemini API boş metin döndürdü (Muhtemelen güvenlik takıntısı).")
                } else {
                    throw Exception("Gemini JSON döndürmedi. Yanıt modeli: ${textContent.take(150)}")
                }
            }

            val jsonStr = textContent.substring(jsonStart, jsonEnd + 1)
            val parsed = JSONObject(jsonStr)
            val questionsArray = parsed.optJSONArray("questions") ?: return emptyList()

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

                val matchedQuestionRect = findMatchingRect(text, mlKitLines, Rect(regionRect.left, currentY, regionRect.right, currentY + lineH - 1))
                lines.add(OcrLine(
                    text = questionHeader,
                    boundingBox = matchedQuestionRect,
                    confidence = confidence,
                    lineIndex = lineIndex++
                ))
                currentY += lineH

                if (optionsObj != null) {
                    for (optKey in optKeys) {
                        val optText = optionsObj.optString(optKey, "")
                        val optLine = "$optKey) $optText"
                        fullTextParts.add(optLine)
                        
                        val matchedOptRect = findMatchingRect(optText, mlKitLines, Rect(regionRect.left, currentY, regionRect.right, currentY + lineH - 1))
                        lines.add(OcrLine(
                            text = optLine,
                            boundingBox = matchedOptRect,
                            confidence = confidence,
                            lineIndex = lineIndex++
                        ))
                        currentY += lineH
                    }
                }
            }

            if (lines.isEmpty()) return emptyList()

            return listOf(OcrResult(
                fullText = fullTextParts.joinToString("\n"),
                textLines = lines,
                columnIndex = columnIndex,
                regionRect = regionRect
            ))
        } catch (e: Exception) {
            logToFile("Parse Error: ${e.message}\n${e.stackTraceToString()}")
            throw e
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        // Scale down if too large to stay within API limits
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
