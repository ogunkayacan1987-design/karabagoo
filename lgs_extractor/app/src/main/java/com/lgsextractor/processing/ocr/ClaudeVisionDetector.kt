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
     * Converts Claude's structured JSON response to OcrResult list.
     *
     * Three-pass approach for accurate multi-column bounding boxes:
     * 1) Parse question data from JSON
     * 2) Anchor each question's header to a real ML Kit bounding box (X and Y)
     * 3) Group questions by column (left vs right) using header X position;
     *    calculate Y boundaries within each column independently;
     *    return one OcrResult per column with correct columnIndex and X extents.
     *
     * Falls back to evenly-distributed virtual positions when ML Kit has no matches.
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

            // --- Pass 1: Parse question data ---
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

            // --- Pass 2: Anchor each question header to ML Kit position (X and Y) ---
            data class HeaderPos(val top: Int, val left: Int, val right: Int, val anchored: Boolean)

            val sliceHeight = regionRect.height() / qCount
            val headerPositions: List<HeaderPos> = qDataList.mapIndexed { i, qData ->
                val virtualY = regionRect.top + i * sliceHeight
                val defaultRect = Rect(regionRect.left, virtualY, regionRect.right, virtualY + VIRTUAL_LINE_HEIGHT_PX)

                // Try number prefix first ("4." anchor) then question text
                val numMatch = findMatchingRect("${qData.number}.", mlKitLines, defaultRect)
                val resolved = if (numMatch !== defaultRect) numMatch
                               else findMatchingRect(qData.text, mlKitLines, defaultRect)

                if (resolved !== defaultRect) {
                    HeaderPos(resolved.top, resolved.left, resolved.right, anchored = true)
                } else {
                    HeaderPos(virtualY, regionRect.left, regionRect.right, anchored = false)
                }
            }

            // --- Pass 3: Group by column, build OcrLines, return per-column OcrResults ---
            // Determine if page is multi-column: any anchored header in right half → 2 columns
            val halfX = regionRect.left + regionRect.width() / 2
            val isMultiColumn = headerPositions.any { it.anchored && it.left > halfX }

            // Assign each question to a column
            val questionCols: List<Int> = headerPositions.mapIndexed { i, pos ->
                when {
                    !isMultiColumn -> 0
                    pos.anchored -> if (pos.left > halfX) 1 else 0
                    // Not anchored: first half of questions → col 0, rest → col 1
                    else -> if (i < qCount / 2) 0 else 1
                }
            }

            // Group question indices by column, sort by header Y within each column
            data class QEntry(val origIdx: Int, val qData: QData, val pos: HeaderPos)
            val columnGroups: Map<Int, List<QEntry>> = qDataList.indices
                .map { i -> QEntry(i, qDataList[i], headerPositions[i]) }
                .groupBy { questionCols[it.origIdx] }
                .mapValues { (_, entries) -> entries.sortedBy { it.pos.top } }

            // Build an OcrResult for each column
            val results = mutableListOf<OcrResult>()

            for ((col, entries) in columnGroups.entries.sortedBy { it.key }) {
                val lines = mutableListOf<OcrLine>()
                val fullTextParts = mutableListOf<String>()

                // X extent for this column
                val colLeft  = if (!isMultiColumn || col == 0) regionRect.left else halfX
                val colRight = if (!isMultiColumn || col == columnGroups.size - 1) regionRect.right else halfX

                entries.forEachIndexed { colQIdx, entry ->
                    val qTop = entry.pos.top
                    // Bottom = next question's top - 1; last question extends to column bottom
                    val nextTop = entries.getOrNull(colQIdx + 1)?.pos?.top
                    val qBottom = if (nextTop != null) (nextTop - 1) else regionRect.bottom
                    val safeBottom = maxOf(qBottom, qTop + VIRTUAL_LINE_HEIGHT_PX)

                    // X from anchored ML Kit position (or column default)
                    val qLeft  = if (entry.pos.anchored) entry.pos.left  else colLeft
                    val qRight = if (entry.pos.anchored) entry.pos.right else colRight

                    val linesInQ = 1 + entry.qData.optKeys.size
                    val lineH = ((safeBottom - qTop) / linesInQ).coerceAtLeast(VIRTUAL_LINE_HEIGHT_PX)

                    val header = "${entry.qData.number}. ${entry.qData.text}"
                    fullTextParts.add(header)
                    lines.add(OcrLine(
                        text = header,
                        boundingBox = Rect(qLeft, qTop, qRight, qTop + lineH),
                        confidence = entry.qData.confidence,
                        lineIndex = lines.size
                    ))

                    var optY = qTop + lineH
                    entry.qData.optionsObj?.let { opts ->
                        for (optKey in entry.qData.optKeys) {
                            val optLine = "$optKey) ${opts.optString(optKey, "")}"
                            fullTextParts.add(optLine)
                            lines.add(OcrLine(
                                text = optLine,
                                boundingBox = Rect(qLeft, optY, qRight, optY + lineH),
                                confidence = entry.qData.confidence,
                                lineIndex = lines.size
                            ))
                            optY += lineH
                        }
                    }
                }

                if (lines.isNotEmpty()) {
                    results.add(OcrResult(
                        fullText = fullTextParts.joinToString("\n"),
                        textLines = lines,
                        columnIndex = col,
                        regionRect = Rect(colLeft, regionRect.top, colRight, regionRect.bottom)
                    ))
                }
            }

            results
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
