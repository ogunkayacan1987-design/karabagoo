package com.lgsextractor.domain.usecase

import com.lgsextractor.domain.model.DetectionConfig
import com.lgsextractor.domain.model.PdfDocument
import com.lgsextractor.domain.model.ProcessingProgress
import com.lgsextractor.domain.model.ProcessingResult
import com.lgsextractor.domain.model.Question
import com.lgsextractor.domain.repository.PdfRepository
import com.lgsextractor.domain.repository.QuestionRepository
import com.lgsextractor.processing.pdf.PdfPageRenderer
import com.lgsextractor.processing.cv.OpenCVProcessor
import com.lgsextractor.processing.ocr.OcrEngine
import com.lgsextractor.processing.detection.QuestionDetector
import com.lgsextractor.processing.export.CropEngine
import com.lgsextractor.util.ApiKeyManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

class ExtractQuestionsUseCase @Inject constructor(
    private val pdfRepository: PdfRepository,
    private val questionRepository: QuestionRepository,
    private val pageRenderer: PdfPageRenderer,
    private val cvProcessor: OpenCVProcessor,
    private val ocrEngine: OcrEngine,
    private val questionDetector: QuestionDetector,
    private val cropEngine: CropEngine,
    private val apiKeyManager: ApiKeyManager
) {
    fun execute(
        document: PdfDocument,
        config: DetectionConfig = DetectionConfig(),
        pageRange: IntRange? = null
    ): Flow<ProcessingProgress> = flow {

        val pages = pageRange ?: (0 until document.pageCount)
        val allQuestions = mutableListOf<Question>()

        // Retrieve Claude API key once for the entire extraction session
        val claudeApiKey = if (config.useClaudeVision || config.ocrEngine == com.lgsextractor.domain.model.OcrEngineType.CLAUDE_VISION) {
            apiKeyManager.getClaudeApiKey()
        } else null

        emit(ProcessingProgress(0, pages.count(), com.lgsextractor.domain.model.ProcessingPhase.RENDERING_PDF))

        for ((index, pageNum) in pages.withIndex()) {
            // 1. Render page to bitmap
            emit(ProcessingProgress(
                currentPage = index,
                totalPages = pages.count(),
                phase = com.lgsextractor.domain.model.ProcessingPhase.RENDERING_PDF,
                questionsFound = allQuestions.size,
                message = "Sayfa ${pageNum + 1} render ediliyor..."
            ))

            val pageResult = pdfRepository.renderPage(document.id, pageNum, config.renderDpi)
            val page = pageResult.getOrElse {
                android.util.Log.e("ExtractUseCase", "Page $pageNum render failed", it)
                continue
            }

            // 2. OpenCV layout analysis
            emit(ProcessingProgress(
                currentPage = index,
                totalPages = pages.count(),
                phase = com.lgsextractor.domain.model.ProcessingPhase.ANALYZING_LAYOUT,
                questionsFound = allQuestions.size,
                message = "Sayfa ${pageNum + 1} layout analizi..."
            ))

            val layoutInfo = cvProcessor.analyzeLayout(page)

            // 3. OCR per column
            emit(ProcessingProgress(
                currentPage = index,
                totalPages = pages.count(),
                phase = com.lgsextractor.domain.model.ProcessingPhase.RUNNING_OCR,
                questionsFound = allQuestions.size,
                message = "Sayfa ${pageNum + 1} OCR işleniyor..."
            ))

            val ocrResult = ocrEngine.recognizeText(page, layoutInfo, config, claudeApiKey)

            // 4. Detect questions
            emit(ProcessingProgress(
                currentPage = index,
                totalPages = pages.count(),
                phase = com.lgsextractor.domain.model.ProcessingPhase.DETECTING_QUESTIONS,
                questionsFound = allQuestions.size,
                message = "Sorular tespit ediliyor..."
            ))

            val pageQuestions = questionDetector.detectQuestions(
                page = page,
                ocrResult = ocrResult,
                layoutInfo = layoutInfo,
                documentId = document.id,
                config = config
            )

            // 5. Crop & export each question
            emit(ProcessingProgress(
                currentPage = index,
                totalPages = pages.count(),
                phase = com.lgsextractor.domain.model.ProcessingPhase.CROPPING,
                questionsFound = allQuestions.size + pageQuestions.size,
                message = "${pageQuestions.size} soru kırpılıyor..."
            ))

            val croppedQuestions = pageQuestions.map { question ->
                cropEngine.cropQuestion(question, page)
            }

            // 6. Save to DB
            questionRepository.saveQuestions(croppedQuestions)
            allQuestions.addAll(croppedQuestions)
        }

        emit(ProcessingProgress(
            currentPage = pages.count(),
            totalPages = pages.count(),
            phase = com.lgsextractor.domain.model.ProcessingPhase.COMPLETE,
            questionsFound = allQuestions.size,
            message = "Tamamlandı: ${allQuestions.size} soru bulundu."
        ))
    }
}
