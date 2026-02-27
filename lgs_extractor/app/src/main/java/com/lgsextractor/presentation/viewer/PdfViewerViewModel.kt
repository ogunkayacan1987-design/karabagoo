package com.lgsextractor.presentation.viewer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lgsextractor.domain.model.BoundingBox
import com.lgsextractor.domain.model.DetectionConfig
import com.lgsextractor.domain.model.PdfDocument
import com.lgsextractor.domain.model.PdfPage
import com.lgsextractor.domain.model.ProcessingPhase
import com.lgsextractor.domain.model.ProcessingProgress
import com.lgsextractor.domain.model.Question
import com.lgsextractor.domain.repository.PdfRepository
import com.lgsextractor.domain.repository.QuestionRepository
import com.lgsextractor.domain.usecase.ExtractQuestionsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PdfViewerViewModel @Inject constructor(
    private val pdfRepository: PdfRepository,
    private val questionRepository: QuestionRepository,
    private val extractUseCase: ExtractQuestionsUseCase,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val documentId: String = checkNotNull(savedStateHandle["document_id"])

    sealed class ProcessingState {
        object Idle : ProcessingState()
        data class InProgress(val progress: ProcessingProgress) : ProcessingState()
        data class Complete(val questionCount: Int) : ProcessingState()
        data class Error(val message: String) : ProcessingState()
    }

    private val _document = MutableStateFlow<PdfDocument?>(null)
    val document: StateFlow<PdfDocument?> = _document.asStateFlow()

    private val _currentPage = MutableStateFlow<PdfPage?>(null)
    val currentPage: StateFlow<PdfPage?> = _currentPage.asStateFlow()

    private val _currentPageNumber = MutableStateFlow(0)
    val currentPageNumber: StateFlow<Int> = _currentPageNumber.asStateFlow()

    private val _processingState = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val processingState: StateFlow<ProcessingState> = _processingState.asStateFlow()

    private val _questionsOnPage = MutableStateFlow<List<Question>>(emptyList())
    val questionsOnPage: StateFlow<List<Question>> = _questionsOnPage.asStateFlow()

    private val _config = MutableStateFlow(DetectionConfig())
    val config: StateFlow<DetectionConfig> = _config.asStateFlow()

    private var extractionJob: Job? = null

    init {
        loadDocument()
    }

    private fun loadDocument() {
        viewModelScope.launch {
            val doc = pdfRepository.getPdfDocument(documentId)
            _document.value = doc
            if (doc != null) {
                loadPage(0)
                // Observe questions as they change
                questionRepository.getQuestionsForDocument(documentId).collectLatest { questions ->
                    val page = _currentPageNumber.value
                    _questionsOnPage.value = questions.filter { it.pageNumber == page }
                }
            }
        }
    }

    fun loadPage(pageNumber: Int) {
        val doc = _document.value ?: return
        viewModelScope.launch {
            _currentPageNumber.value = pageNumber
            val result = pdfRepository.renderPage(documentId, pageNumber, _config.value.renderDpi)
            result.onSuccess { page ->
                _currentPage.value = page
                // Refresh questions for this page
                questionRepository.getQuestionsForDocument(documentId).collectLatest { questions ->
                    _questionsOnPage.value = questions.filter { it.pageNumber == pageNumber }
                }
            }
        }
    }

    fun startExtraction(pageRange: IntRange? = null) {
        val doc = _document.value ?: return
        extractionJob?.cancel()

        extractionJob = viewModelScope.launch {
            extractUseCase.execute(doc, _config.value, pageRange).collect { progress ->
                _processingState.value = ProcessingState.InProgress(progress)
                if (progress.phase == ProcessingPhase.COMPLETE) {
                    _processingState.value = ProcessingState.Complete(progress.questionsFound)
                }
                if (progress.phase == ProcessingPhase.ERROR) {
                    _processingState.value = ProcessingState.Error(progress.message)
                }
            }
        }

        extractionJob?.invokeOnCompletion { throwable ->
            if (throwable != null && throwable !is kotlinx.coroutines.CancellationException) {
                _processingState.value = ProcessingState.Error(throwable.message ?: "İşlem başarısız")
            }
        }
    }

    fun cancelExtraction() {
        extractionJob?.cancel()
        _processingState.value = ProcessingState.Idle
    }

    fun updateQuestionBoundary(questionId: String, newBox: BoundingBox) {
        viewModelScope.launch {
            val original = questionRepository.getQuestion(questionId)?.boundingBox ?: return@launch
            questionRepository.updateQuestionBoundary(questionId, newBox)
            questionRepository.saveUserCorrection(questionId, original, newBox)
        }
    }

    fun deleteQuestion(questionId: String) {
        viewModelScope.launch {
            questionRepository.deleteQuestion(questionId)
        }
    }

    fun updateConfig(config: DetectionConfig) {
        _config.value = config
    }
}
