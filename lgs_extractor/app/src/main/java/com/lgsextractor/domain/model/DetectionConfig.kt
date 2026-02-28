package com.lgsextractor.domain.model

/**
 * Configuration for the question detection pipeline.
 * Allows tuning for different publisher formats and quality levels.
 */
data class DetectionConfig(
    // PDF rendering
    val renderDpi: Int = 300,

    // OCR engine preference
    val ocrEngine: OcrEngineType = OcrEngineType.ML_KIT_PRIMARY,

    // Question detection thresholds
    val minQuestionHeight: Int = 80,        // pixels at 300 DPI
    val maxQuestionHeight: Int = 2000,
    val questionPaddingPx: Int = 20,        // extra padding when cropping

    // Multi-column detection
    val enableMultiColumnDetection: Boolean = true,
    val columnSplitThreshold: Float = 0.45f, // whitespace ratio to detect column gap

    // Pattern matching
    val publisherFormat: PublisherFormat = PublisherFormat.GENERIC,
    val questionPatterns: List<QuestionPattern> = QuestionPattern.defaults(),

    // Confidence thresholds
    val minOptionConfidence: Float = 0.7f,
    val minQuestionConfidence: Float = 0.6f,

    // Export settings
    val exportQuality: Int = 90,            // JPEG quality 0-100
    val exportMaxWidthPx: Int = 1200,

    // Learning
    val enableAdaptiveLearning: Boolean = true,

    // Claude Vision AI
    val useClaudeVision: Boolean = false,
    val claudeModel: String = "claude-opus-4-6",
    val claudeMaxTokens: Int = 16000,  // 4096 was too low for pages with long Turkish passages
    
    // Gemini Vision AI
    val useGeminiVision: Boolean = false,
    val geminiModel: String = "gemini-1.5-pro"
)

enum class OcrEngineType {
    ML_KIT_PRIMARY,         // Google ML Kit (default, fast)
    TESSERACT_PRIMARY,      // Tesseract (better for scanned PDFs)
    HYBRID,                 // ML Kit first, Tesseract fallback
    CLAUDE_VISION,          // Claude Vision API (highest accuracy)
    GEMINI_VISION           // Gemini Vision AI API (highest accuracy, fallback)
}

/**
 * Regex patterns for detecting question starts in OCR text.
 * Covers all common Turkish LGS and test book formats.
 */
data class QuestionPattern(
    val name: String,
    val regex: Regex,
    val groupIndex: Int = 1,    // capture group with question number
    val priority: Int = 0       // higher = checked first
) {
    companion object {
        fun defaults(): List<QuestionPattern> = listOf(
            // "1." or "01." at line start
            QuestionPattern(
                name = "numbered_dot",
                regex = Regex("""^\s*(0?[1-9][0-9]?)\.\s+\S""", RegexOption.MULTILINE),
                groupIndex = 1,
                priority = 10
            ),
            // "Soru 1" or "Soru: 1"
            QuestionPattern(
                name = "soru_prefix",
                regex = Regex("""^\s*[Ss][Oo][Rr][Uu]\s*:?\s*(\d+)""", RegexOption.MULTILINE),
                groupIndex = 1,
                priority = 8
            ),
            // "SORU 1" uppercase
            QuestionPattern(
                name = "soru_uppercase",
                regex = Regex("""^\s*SORU\s+(\d+)""", RegexOption.MULTILINE),
                groupIndex = 1,
                priority = 7
            ),
            // "1)" with closing paren
            QuestionPattern(
                name = "numbered_paren",
                regex = Regex("""^\s*(0?[1-9][0-9]?)\)\s+\S""", RegexOption.MULTILINE),
                groupIndex = 1,
                priority = 6
            )
        ).sortedByDescending { it.priority }

        fun optionPatterns(): List<Regex> = listOf(
            // A) B) C) D) style
            Regex("""^[A-D]\s*\)\s*\S""", RegexOption.MULTILINE),
            // A. B. C. D. style
            Regex("""^[A-D]\s*\.\s*\S""", RegexOption.MULTILINE),
            // A ) space before paren
            Regex("""^[A-D]\s+\)\s*\S""", RegexOption.MULTILINE),
        )

        /** Check if text block contains all 4 options A,B,C,D */
        fun hasCompleteOptions(text: String): Boolean {
            return listOf("A", "B", "C", "D").all { label ->
                text.contains(Regex("""$label\s*[\)\.\s]"""))
            }
        }
    }
}
