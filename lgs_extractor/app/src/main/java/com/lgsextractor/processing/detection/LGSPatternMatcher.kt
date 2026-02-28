package com.lgsextractor.processing.detection

import com.lgsextractor.domain.model.BoundingBox
import com.lgsextractor.domain.model.DetectionConfig
import com.lgsextractor.domain.model.PublisherFormat
import com.lgsextractor.domain.model.QuestionOption
import com.lgsextractor.processing.ocr.OcrLine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pattern-based detection of LGS/test-book questions.
 *
 * Handles all Turkish exam formats:
 *   - "1. Aşağıdaki..."         (numbered dot)
 *   - "01. Tablodaki..."        (zero-padded)
 *   - "Soru 5"                  (Soru prefix)
 *   - "Soru: 5"                 (Soru colon)
 *   - A) B) C) D)               (options with paren)
 *   - A. B. C. D.               (options with dot)
 *   - A ) B ) C ) D )           (options with space+paren)
 */
@Singleton
class LGSPatternMatcher @Inject constructor() {

    data class QuestionStart(
        val lineIndex: Int,
        val questionNumber: Int,
        val line: OcrLine,
        val patternName: String
    )

    data class OptionMatch(
        val label: String,          // "A", "B", "C", "D"
        val text: String,
        val lineIndex: Int,
        val line: OcrLine
    )

    data class OptionBlock(
        val options: List<OptionMatch>,
        val startLineIndex: Int,
        val endLineIndex: Int
    ) {
        val isComplete: Boolean get() = options.map { it.label }.containsAll(listOf("A", "B", "C", "D"))
    }

    // ---- Question start patterns ----

    /** "1." or "01." at line start */
    private val numberedDotPattern = Regex(
        """^\s*(0?[1-9][0-9]?)\.\s+\S""",
        RegexOption.MULTILINE
    )

    /** "Soru 1" or "Soru: 1" (case-insensitive, Turkish chars) */
    private val soruPrefixPattern = Regex(
        """^\s*[Ss][Oo][Rr][Uu]\s*:?\s*([0-9]+)""",
        setOf(RegexOption.MULTILINE)
    )

    /** "SORU 1" uppercase */
    private val soruUpperPattern = Regex(
        """^\s*SORU\s+([0-9]+)""",
        RegexOption.MULTILINE
    )

    /** "1)" with closing paren (some publishers) */
    private val numberedParenPattern = Regex(
        """^\s*(0?[1-9][0-9]?)\)\s+\S""",
        RegexOption.MULTILINE
    )

    // ---- Option patterns ----

    /** Single option line: "A) text" or "A. text" or "A ) text" */
    private val singleOptionPattern = Regex(
        """^\s*([A-D])\s*[\)\.\s]\s*(.+)""",
        setOf(RegexOption.MULTILINE)
    )

    /** All 4 options on one line: "A) x  B) y  C) z  D) w" */
    private val inlineOptionsPattern = Regex(
        """([A-D])\s*[\)\.]?\s*([^A-D\n]{1,60})(?=[A-D]\s*[\)\.]|\s*$)""",
        setOf(RegexOption.MULTILINE)
    )

    /** Check if a line is an option label */
    private val optionLabelOnly = Regex("""^\s*[A-D]\s*[\)\.\s]?\s*$""")

    // ---- Public API ----

    /**
     * Find all question start lines in the OCR text lines.
     * Returns sorted list of QuestionStart by lineIndex.
     */
    fun findQuestionStarts(
        lines: List<OcrLine>,
        config: DetectionConfig
    ): List<QuestionStart> {
        val starts = mutableListOf<QuestionStart>()

        lines.forEachIndexed { idx, line ->
            val text = line.text.trim()
            if (text.isBlank()) return@forEachIndexed

            // Try numbered dot: "1."
            numberedDotPattern.find(text)?.let { match ->
                val num = match.groupValues[1].toIntOrNull() ?: return@let
                if (num in 1..150) {
                    starts.add(QuestionStart(idx, num, line, "numbered_dot"))
                    return@forEachIndexed
                }
            }

            // Try zero-padded: "01."
            if (text.matches(Regex("""^\s*0\d\.\s+.*"""))) {
                val num = text.trim().substring(0, 3).trimEnd('.').trim().toIntOrNull()
                if (num != null && num in 1..99) {
                    starts.add(QuestionStart(idx, num, line, "zero_padded_dot"))
                    return@forEachIndexed
                }
            }

            // Try "Soru N"
            soruPrefixPattern.find(text)?.let { match ->
                val num = match.groupValues[1].toIntOrNull() ?: return@let
                starts.add(QuestionStart(idx, num, line, "soru_prefix"))
                return@forEachIndexed
            }

            // Try "SORU N"
            soruUpperPattern.find(text)?.let { match ->
                val num = match.groupValues[1].toIntOrNull() ?: return@let
                starts.add(QuestionStart(idx, num, line, "soru_upper"))
                return@forEachIndexed
            }

            // Try "1)"
            numberedParenPattern.find(text)?.let { match ->
                val num = match.groupValues[1].toIntOrNull() ?: return@let
                if (num in 1..150) {
                    starts.add(QuestionStart(idx, num, line, "numbered_paren"))
                    return@forEachIndexed
                }
            }
        }

        // Validate: question numbers should be roughly sequential
        return filterAndValidateStarts(starts)
    }

    /**
     * Find the option block (A, B, C, D) starting from a given line index.
     * Options can be:
     *   - One per line
     *   - All on one line
     *   - Multi-line (option text wraps)
     */
    fun findOptionBlock(
        lines: List<OcrLine>,
        startLineIndex: Int
    ): OptionBlock? {
        val options = mutableListOf<OptionMatch>()
        var i = startLineIndex
        var lastOptionLineIdx = -1
        var currentLabel: String? = null
        var currentText = StringBuilder()

        while (i < lines.size && options.size < 4) {
            val text = lines[i].text.trim()
            if (text.isBlank()) { i++; continue }

            // Check for inline options: "A) x  B) y  C) z  D) w" on single line
            val inlineMatches = inlineOptionsPattern.findAll(text).toList()
            if (inlineMatches.size >= 2) {
                inlineMatches.forEach { match ->
                    val label = match.groupValues[1]
                    val optText = match.groupValues[2].trim()
                    if (label in listOf("A", "B", "C", "D")) {
                        options.add(OptionMatch(label, optText, i, lines[i]))
                    }
                }
                if (options.size >= 4) {
                    lastOptionLineIdx = i
                    break
                }
                i++
                continue
            }

            // Check for single-line option: "A) text..."
            val match = singleOptionPattern.find(text)
            if (match != null) {
                val label = match.groupValues[1]
                val optText = match.groupValues[2].trim()
                if (label in listOf("A", "B", "C", "D")) {
                    // Save previous option if any
                    if (currentLabel != null) {
                        options.add(OptionMatch(
                            label = currentLabel!!,
                            text = currentText.toString().trim(),
                            lineIndex = i - 1,
                            line = lines[i - 1]
                        ))
                    }
                    currentLabel = label
                    currentText = StringBuilder(optText)
                    lastOptionLineIdx = i
                } else {
                    // Continuation of current option
                    if (currentLabel != null) currentText.append(" $text")
                }
            } else if (currentLabel != null) {
                // Continuation line of current option (wrapped text)
                val isNewQuestionStart = numberedDotPattern.containsMatchIn(text) ||
                    soruPrefixPattern.containsMatchIn(text)
                if (isNewQuestionStart) break
                currentText.append(" $text")
                lastOptionLineIdx = i
            } else {
                // Not an option, and no current option being built
                if (options.isNotEmpty()) break  // past option block
            }
            i++
        }

        // Save last option
        if (currentLabel != null && currentLabel in listOf("A", "B", "C", "D")) {
            options.add(OptionMatch(
                label = currentLabel!!,
                text = currentText.toString().trim(),
                lineIndex = lastOptionLineIdx,
                line = if (lastOptionLineIdx >= 0) lines[lastOptionLineIdx] else lines[startLineIndex]
            ))
        }

        if (options.isEmpty()) return null

        return OptionBlock(
            options = options.sortedBy { it.label },
            startLineIndex = options.minOf { it.lineIndex },
            endLineIndex = options.maxOf { it.lineIndex }
        )
    }

    /**
     * Detect publisher format from first page OCR text.
     * Looks for publisher name patterns in header/footer.
     */
    fun detectPublisherFormat(fullPageText: String): PublisherFormat {
        val lower = fullPageText.lowercase()
        return when {
            lower.contains("birey") -> PublisherFormat.BIREY
            lower.contains("fdd") -> PublisherFormat.FDD
            lower.contains("palme") -> PublisherFormat.PALME
            lower.contains("zambak") -> PublisherFormat.ZAMBAK
            lower.contains("ata yayın") || lower.contains("ata yayıncı") -> PublisherFormat.ATA
            lower.contains("lgs") && lower.contains("meb") -> PublisherFormat.LGS_OFFICIAL
            else -> PublisherFormat.GENERIC
        }
    }

    // ---- Private helpers ----

    /**
     * Validate that detected question starts are sequential (removes OCR noise).
     */
    private fun filterAndValidateStarts(starts: List<QuestionStart>): List<QuestionStart> {
        if (starts.size <= 1) return starts

        val validated = mutableListOf<QuestionStart>()
        var lastNum = 0

        // Allow gaps up to 5 in sequence, catch restarts (multi-section books)
        for (s in starts.sortedBy { it.lineIndex }) {
            val delta = s.questionNumber - lastNum
            when {
                lastNum == 0 -> {
                    validated.add(s)
                    lastNum = s.questionNumber
                }
                delta in 1..5 -> {
                    // Sequential or small gap (acceptable)
                    validated.add(s)
                    lastNum = s.questionNumber
                }
                delta < 0 && s.questionNumber in 1..5 -> {
                    // Restart from 1 (new section/chapter)
                    validated.add(s)
                    lastNum = s.questionNumber
                }
                else -> {
                    // Large gap or out-of-order → likely OCR noise, skip
                    android.util.Log.d("PatternMatcher",
                        "Skipping suspicious question start: ${s.questionNumber} (last=$lastNum, line=${s.line.text})")
                }
            }
        }
        return validated
    }
}
