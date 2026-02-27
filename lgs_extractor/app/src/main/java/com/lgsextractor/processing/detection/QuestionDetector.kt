package com.lgsextractor.processing.detection

import com.lgsextractor.domain.model.BoundingBox
import com.lgsextractor.domain.model.DetectionConfig
import com.lgsextractor.domain.model.PdfPage
import com.lgsextractor.domain.model.Question
import com.lgsextractor.domain.model.QuestionOption
import com.lgsextractor.domain.model.SubjectType
import com.lgsextractor.processing.cv.OpenCVProcessor
import com.lgsextractor.processing.ocr.OcrResult
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the full question detection pipeline for a single page.
 * Converts raw OCR + layout data into domain Question objects.
 */
@Singleton
class QuestionDetector @Inject constructor(
    private val boundaryInferencer: QuestionBoundaryInferencer,
    private val patternMatcher: LGSPatternMatcher
) {
    fun detectQuestions(
        page: PdfPage,
        ocrResult: List<OcrResult>,
        layoutInfo: OpenCVProcessor.LayoutInfo,
        documentId: String,
        config: DetectionConfig
    ): List<Question> {
        val boundaries = boundaryInferencer.inferBoundaries(ocrResult, layoutInfo, config)

        return boundaries.mapNotNull { boundary ->
            if (boundary.confidence < config.minQuestionConfidence) return@mapNotNull null

            val questionText = extractQuestionText(boundary.questionLines)
            val options = buildOptions(boundary.optionBlock, boundary.boundingBox)

            Question(
                id = UUID.randomUUID().toString(),
                pdfPath = documentId,
                pageNumber = page.pageNumber,
                questionNumber = boundary.questionNumber,
                questionText = questionText,
                options = options,
                boundingBox = boundary.boundingBox,
                subject = detectSubject(questionText, config),
                confidence = boundary.confidence,
                isManuallyVerified = false,
                hasImage = boundary.hasImage,
                hasTable = boundary.hasTable,
                isMathFormula = detectMathFormula(questionText),
                columnIndex = boundary.columnIndex,
                publisherFormat = config.publisherFormat
            )
        }
    }

    private fun extractQuestionText(lines: List<com.lgsextractor.processing.ocr.OcrLine>): String {
        return lines.joinToString("\n") { it.text.trim() }.trim()
    }

    private fun buildOptions(
        optionBlock: LGSPatternMatcher.OptionBlock?,
        questionBox: BoundingBox
    ): List<QuestionOption> {
        if (optionBlock == null) return emptyList()
        return optionBlock.options.map { opt ->
            QuestionOption(
                label = opt.label,
                text = opt.text,
                boundingBox = BoundingBox(
                    left = opt.line.boundingBox.left,
                    top = opt.line.boundingBox.top,
                    right = opt.line.boundingBox.right,
                    bottom = opt.line.boundingBox.bottom,
                    pageWidth = questionBox.pageWidth,
                    pageHeight = questionBox.pageHeight
                )
            )
        }
    }

    private fun detectSubject(text: String, config: DetectionConfig): SubjectType {
        // If explicitly configured, use that
        if (config.publisherFormat != com.lgsextractor.domain.model.PublisherFormat.GENERIC) {
            // Could be set by user; for now use keyword detection
        }

        val lower = text.lowercase()
        return when {
            lower.containsAny("π", "sin", "cos", "tan", "\\d+x", "denklem", "çarpım", "bölüm",
                "toplam", "fark", "kare", "köklü", "logaritma", "integral") -> SubjectType.MATHEMATICS
            lower.containsAny("atom", "molekül", "hücre", "elektron", "proton", "nötron",
                "madde", "enerji", "kuvvet", "sürtünme", "ısı", "kimya", "fizik", "biyoloji",
                "fotosentez", "solunum", "gen", "dna") -> SubjectType.SCIENCE
            lower.containsAny("okuma parçası", "paragraf", "metin", "yazar", "şiir",
                "kelime", "cümle", "sözcük", "noktalama", "fiil", "isim", "sıfat",
                "dil bilgisi", "yazım") -> SubjectType.TURKISH
            lower.containsAny("tarih", "coğrafya", "vatandaşlık", "milli", "devlet",
                "cumhuriyet", "atatürk", "ekonomi", "kültür", "medeniyet") -> SubjectType.SOCIAL_STUDIES
            lower.containsAny("reading", "listening", "grammar", "vocabulary",
                "dialogue", "write", "english") -> SubjectType.ENGLISH
            lower.containsAny("din", "islam", "peygamber", "kuran", "ibadet",
                "ahlak", "değer", "vicdan") -> SubjectType.RELIGION
            else -> SubjectType.UNKNOWN
        }
    }

    private fun detectMathFormula(text: String): Boolean {
        val mathIndicators = listOf("=", "+", "-", "×", "÷", "√", "π", "²", "³", "∞",
            "≤", "≥", "≠", "∑", "∫", "log", "sin", "cos", "tan")
        val count = mathIndicators.count { text.contains(it) }
        return count >= 2
    }
}

private fun String.containsAny(vararg terms: String): Boolean =
    terms.any { this.contains(it, ignoreCase = true) }
