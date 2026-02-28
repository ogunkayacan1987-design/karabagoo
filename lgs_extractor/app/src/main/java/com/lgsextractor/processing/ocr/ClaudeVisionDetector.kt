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
        // If the first ML Kit line inside a Claude bbox starts more than this fraction of
        // the region height below the bbox top, assume Claude included inter-question
        // whitespace and snap the top down to the first real text line.
        // (LGS questions always begin with "N." so the first line is never below a chart.)
        private const val BLANK_TOP_FRACTION = 0.06f   // ~6% of page height
        private const val SNAP_TOP_PADDING_PX = 12     // small upward margin after snapping
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
     * @param pageBitmap      The rendered PDF page bitmap
     * @param apiKey          Claude API key
     * @param config          DetectionConfig with model/token settings
     * @param columnIndex     Column index for the OcrResult
     * @param regionRect      Region rect for the OcrResult
     * @param mlKitLines      ML Kit OCR lines used to anchor Claude results to real pixel positions
     * @param expectedColumns Number of columns OpenCV detected on this page (1 or 2).
     *                        When ≥2, forces multi-column question grouping even if ML Kit
     *                        cannot anchor right-column headers.
     * @return List of OcrResult on success, empty list on failure
     */
    suspend fun detectQuestions(
        pageBitmap: Bitmap,
        apiKey: String,
        config: DetectionConfig,
        columnIndex: Int = 0,
        regionRect: Rect = Rect(0, 0, pageBitmap.width, pageBitmap.height),
        mlKitLines: List<OcrLine> = emptyList(),
        expectedColumns: Int = 1
    ): List<OcrResult> {
        // bitmapToBase64 is CPU-bound; keep it off Main thread
        val base64Image = withContext(Dispatchers.IO) { bitmapToBase64(pageBitmap) }

        val prompt = buildPrompt()
        val requestBody = buildRequestBody(base64Image, prompt, config)

        logToFile("Starting Claude API call: column=$columnIndex model=${config.claudeModel} mlKitLines=${mlKitLines.size} expectedColumns=$expectedColumns")

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

                val results = parseApiResponse(bodyStr, columnIndex, regionRect, mlKitLines, expectedColumns)
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
        Sayfadaki tüm soruları tespit et; her sorunun tam metnini ve görüntü üzerindeki
        tam konumunu (bbox) çıkar.

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
              "bbox": {
                "x": 0.02,
                "y": 0.05,
                "width": 0.46,
                "height": 0.35
              },
              "confidence": 0.95
            }
          ]
        }

        bbox değerleri 0.0-1.0 arasında oransal koordinatlardır (görüntünün sol üst köşesi 0,0):
        - x: sorunun sol kenarı (yatay)
        - y: sorunun üst kenarı (dikey)
        - width: sorunun genişliği
        - height: sorunun yüksekliği

        BBOX KURALLARI:
        - bbox.y → sorunun soru numarasının veya ilk içerik öğesinin (metin, resim, grafik) TAM başladığı satır.
          Önceki soruyla arasındaki BOŞLUK veya BEYAZ ALAN kesinlikle dahil edilmemeli.
        - bbox.height → son şıkkın (D) veya son görsel içeriğin bittiği yere kadar olmalı.
        - Grafik, tablo ve resimler sorunun içeriğine dahilse bbox içinde olmalı.

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
     * Converts Claude's structured JSON response to a list of OcrResults.
     *
     * Pass 1 — Parse question data (text, options, bbox) and sort by number.
     *
     * Pass A — Determine isMultiColumn (expectedColumns ≥ 2 OR ML Kit lines in both halves).
     *
     * Pass B — Pre-assign each question to a column by index (first ceil(N/2) → col 0,
     *   remainder → col 1). Compute per-column virtual Y as fallback positions.
     *
     * Pass C — Resolve pixel coordinates for each question header:
     *   1. Primary: use Claude's bbox field when present — most accurate, covers images/charts.
     *   2. Fallback: match question number/text against ML Kit OCR lines.
     *   3. Last resort: virtual Y from Pass B.
     *   Also carries bbox bottom so Pass D can set the correct question height.
     *
     * Pass D — Assign final column (based on anchored X position), build OcrLines and
     *   OcrResults per column. Uses bbox bottom (when available) as the authoritative
     *   question bottom, so the downstream inferencer receives accurate boundaries.
     */
    private fun parseApiResponse(
        bodyStr: String,
        columnIndex: Int,
        regionRect: Rect,
        mlKitLines: List<OcrLine> = emptyList(),
        expectedColumns: Int = 1
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

            val parsed = JSONObject(textContent.substring(jsonStart, jsonEnd + 1))
            val questionsArray = parsed.getJSONArray("questions")
            val qCount = questionsArray.length()
            Log.d(TAG, "Claude returned $qCount questions")
            if (qCount == 0) return emptyList()

            // --- Pass 1: Parse question data ---
            data class QData(
                val number: Int,
                val text: String,
                val confidence: Float,
                val optKeys: List<String>,
                val optionsObj: org.json.JSONObject?,
                // Claude-provided normalised bbox [0,1] — null when not present
                val bboxX: Double? = null,
                val bboxY: Double? = null,
                val bboxW: Double? = null,
                val bboxH: Double? = null
            )
            // Sort by question number so that column pre-assignment is order-independent
            // (Claude may return questions in reading order: alternating left/right column by row)
            val qDataList = (0 until qCount).map { i ->
                val q = questionsArray.getJSONObject(i)
                val opts = q.optJSONObject("options")
                val bb   = q.optJSONObject("bbox")
                QData(
                    number = q.optInt("number", i + 1),
                    text = q.optString("text", ""),
                    confidence = q.optDouble("confidence", 0.85).toFloat(),
                    optKeys = listOf("A","B","C","D").filter { opts?.optString(it,"")?.isNotBlank() == true },
                    optionsObj = opts,
                    bboxX = bb?.optDouble("x"),
                    bboxY = bb?.optDouble("y"),
                    bboxW = bb?.optDouble("width"),
                    bboxH = bb?.optDouble("height")
                )
            }.sortedBy { it.number }

            // --- Pass A: Detect multi-column ---
            val halfX = regionRect.left + regionRect.width() / 2
            // Count ML Kit lines in each half of the page
            val mlLeft  = mlKitLines.count { (it.boundingBox.left + it.boundingBox.right) / 2 < halfX }
            val mlRight = mlKitLines.count { (it.boundingBox.left + it.boundingBox.right) / 2 >= halfX }
            var isMultiColumn = expectedColumns >= 2 || (mlLeft >= 5 && mlRight >= 5)

            // --- Pass B: Pre-assign columns by order; compute per-column virtual Y ---
            // Lower-numbered questions → col 0 (left), higher-numbered → col 1 (right).
            // questionsPerCol uses ceiling division so col 0 gets the extra question on odd
            // totals (matches standard LGS layout); preColAssign uses the same threshold
            // so it is consistent with sliceHeight.
            val questionsPerCol = if (isMultiColumn && qCount >= 2) maxOf(1, (qCount + 1) / 2) else qCount
            val preColAssign: List<Int> = (0 until qCount).map { i ->
                if (isMultiColumn && i >= questionsPerCol) 1 else 0
            }
            val sliceHeight = regionRect.height() / questionsPerCol
            val colQIdx = IntArray(2) { 0 }   // running question index within each column

            // --- Pass C: Anchor to real pixel positions ---
            // Primary: use Claude's bbox when available (most accurate).
            // Fallback: ML Kit line anchoring or virtual Y.
            data class HeaderPos(
                val top: Int, val left: Int, val right: Int,
                val bottom: Int?,   // non-null when Claude provided a full bbox
                val anchored: Boolean
            )
            val headerPositions: List<HeaderPos> = qDataList.mapIndexed { i, qData ->
                val preCIdx = preColAssign[i]
                val virtualY = regionRect.top + colQIdx[preCIdx] * sliceHeight
                colQIdx[preCIdx]++

                // ── Primary: Claude bbox ──────────────────────────────────────────
                val bx = qData.bboxX; val by = qData.bboxY
                val bw = qData.bboxW; val bh = qData.bboxH
                if (bx != null && by != null && bw != null && bh != null
                    && bw > 0.0 && bh > 0.0) {
                    val bLeft   = (regionRect.left + bx          * regionRect.width()).toInt()
                    var bTop    = (regionRect.top  + by          * regionRect.height()).toInt()
                    val bRight  = (regionRect.left + (bx + bw)   * regionRect.width()).toInt()
                    val bBottom = (regionRect.top  + (by + bh)   * regionRect.height()).toInt()

                    // Safety net: if Claude included inter-question whitespace at the top,
                    // the first ML Kit line inside the bbox will be much lower than bTop.
                    // Snap bTop down to the first text line so the crop has no blank header.
                    // (Safe for LGS: questions always start with "N." before any image.)
                    val blankThreshold = (regionRect.height() * BLANK_TOP_FRACTION).toInt()
                    val firstLineInBox = mlKitLines
                        .filter { it.boundingBox.top in bTop..bBottom &&
                                  (it.boundingBox.left + it.boundingBox.right) / 2 in bLeft..bRight }
                        .minByOrNull { it.boundingBox.top }
                    if (firstLineInBox != null &&
                        firstLineInBox.boundingBox.top - bTop > blankThreshold) {
                        bTop = maxOf(bTop, firstLineInBox.boundingBox.top - SNAP_TOP_PADDING_PX)
                        logToFile("Snapped Q${qData.number} top from ${(by * regionRect.height()).toInt()} to $bTop (blank header removed)")
                    }

                    return@mapIndexed HeaderPos(bTop, bLeft, bRight, bBottom, anchored = true)
                }

                // ── Fallback: ML Kit line anchor ──────────────────────────────────
                val defaultRect = Rect(regionRect.left, virtualY, regionRect.right,
                                       virtualY + VIRTUAL_LINE_HEIGHT_PX)
                val numStr = "${qData.number}"
                val numLine = mlKitLines.firstOrNull { line ->
                    val t = line.text.trim()
                    Regex("""^0?${Regex.escape(numStr)}\s*[.):]""").containsMatchIn(t)
                }
                val anchoredRect = numLine?.boundingBox
                    ?: findMatchingRect(qData.text, mlKitLines, defaultRect)
                        .takeIf { it !== defaultRect }

                if (anchoredRect != null)
                    HeaderPos(anchoredRect.top, anchoredRect.left, anchoredRect.right, null, anchored = true)
                else
                    HeaderPos(virtualY, regionRect.left, regionRect.right, null, anchored = false)
            }

            // Promote to multi-column if any anchored header is in the right half
            if (!isMultiColumn && headerPositions.any { it.anchored && it.left > halfX })
                isMultiColumn = true

            // --- Pass D: Final column assignment, OcrLine construction ---
            val questionCols: List<Int> = headerPositions.mapIndexed { i, pos ->
                when {
                    pos.anchored && pos.left > halfX -> 1
                    pos.anchored                     -> 0
                    isMultiColumn                    -> preColAssign[i]
                    else                             -> 0
                }
            }

            data class QEntry(val qData: QData, val pos: HeaderPos)
            val colGroups: Map<Int, List<QEntry>> = qDataList.indices
                .groupBy { i -> questionCols[i] }
                .mapValues { (_, idxList) ->
                    idxList.map { i -> QEntry(qDataList[i], headerPositions[i]) }
                           .sortedBy { it.pos.top }
                }

            val results = mutableListOf<OcrResult>()
            for ((col, entries) in colGroups.entries.sortedBy { it.key }) {
                val lines = mutableListOf<OcrLine>()
                val textParts = mutableListOf<String>()

                val colLeft  = if (!isMultiColumn || col == 0) regionRect.left else halfX
                val colRight = if (!isMultiColumn || col == colGroups.size - 1) regionRect.right else halfX

                entries.forEachIndexed { colQIdx2, entry ->
                    val qTop    = entry.pos.top
                    val nextTop = entries.getOrNull(colQIdx2 + 1)?.pos?.top
                    // Prefer Claude's exact bbox bottom; fall back to next-question top or region bottom
                    val qBottom = entry.pos.bottom
                        ?: if (nextTop != null) nextTop - 1 else regionRect.bottom
                    val safeBottom = maxOf(qBottom, qTop + VIRTUAL_LINE_HEIGHT_PX)

                    val qLeft  = if (entry.pos.anchored) entry.pos.left  else colLeft
                    val qRight = if (entry.pos.anchored) entry.pos.right else colRight

                    val linesInQ = 1 + entry.qData.optKeys.size
                    val lineH = ((safeBottom - qTop) / linesInQ).coerceAtLeast(VIRTUAL_LINE_HEIGHT_PX)

                    val header = "${entry.qData.number}. ${entry.qData.text}"
                    textParts.add(header)
                    lines.add(OcrLine(header, Rect(qLeft, qTop, qRight, qTop + lineH),
                                      entry.qData.confidence, lines.size))

                    var optY = qTop + lineH
                    entry.qData.optionsObj?.let { opts ->
                        for (optKey in entry.qData.optKeys) {
                            val optLine = "$optKey) ${opts.optString(optKey, "")}"
                            textParts.add(optLine)
                            lines.add(OcrLine(optLine, Rect(qLeft, optY, qRight, optY + lineH),
                                              entry.qData.confidence, lines.size))
                            optY += lineH
                        }
                    }
                }

                if (lines.isNotEmpty()) {
                    results.add(OcrResult(
                        fullText = textParts.joinToString("\n"),
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
        val maxDim = 1600  // Reduced from 2000: smaller upload, faster API round-trip; 1600px is still readable
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
