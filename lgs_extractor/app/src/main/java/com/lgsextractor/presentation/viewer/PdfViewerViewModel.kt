package com.lgsextractor.presentation.viewer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lgsextractor.domain.model.BoundingBox
import com.lgsextractor.domain.model.DetectionConfig
import com.lgsextractor.domain.model.PdfDocument
import com.lgsextractor.domain.model.PdfPage
import com.lgsextractor.domain.model.ProcessingPhase
import com.lgsextractor.domain.model.Question
import com.lgsextractor.domain.repository.PdfRepository
import com.lgsextractor.domain.repository.QuestionRepository
import com.lgsextractor.domain.usecase.ExtractQuestionsUseCase
import com.lgsextractor.util.ApiKeyManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PdfViewerViewModel @Inject constructor(
    private val pdfRepository: PdfRepository,
    private val questionRepository: QuestionRepository,
    private val extractUseCase: ExtractQuestionsUseCase,
    val apiKeyManager: ApiKeyManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val documentId: String = checkNotNull(savedStateHandle["document_id"])

    sealed class ProcessingState {
        object Idle : ProcessingState()
        data class InProgress(val progress: com.lgsextractor.domain.model.ProcessingProgress) : ProcessingState()
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

    private val _config = MutableStateFlow(DetectionConfig())
    val config: StateFlow<DetectionConfig> = _config.asStateFlow()

    /** Questions filtered to the currently visible page — derived from DB + currentPageNumber */
    val questionsOnPage: StateFlow<List<Question>> =
        combine(
            questionRepository.getQuestionsForDocument(documentId),
            _currentPageNumber
        ) { questions, pageNum ->
            questions.filter { it.pageNumber == pageNum }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
            }
        }
    }

    fun loadPage(pageNumber: Int) {
        val doc = _document.value ?: return
        viewModelScope.launch {
            _currentPageNumber.value = pageNumber
            pdfRepository.renderPage(documentId, pageNumber, _config.value.renderDpi)
                .onSuccess { page -> _currentPage.value = page }
                .onFailure { android.util.Log.e("PdfViewerVM", "Render failed", it) }
        }
    }

    fun startExtraction(pageRange: IntRange? = null) {
        val doc = _document.value ?: return
        extractionJob?.cancel()

        extractionJob = viewModelScope.launch {
            extractUseCase.execute(doc, _config.value, pageRange).collect { progress ->
                _processingState.value = ProcessingState.InProgress(progress)
                when (progress.phase) {
                    ProcessingPhase.COMPLETE ->
                        _processingState.value = ProcessingState.Complete(progress.questionsFound)
                    ProcessingPhase.ERROR ->
                        _processingState.value = ProcessingState.Error(progress.message)
                    else -> Unit
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
        viewModelScope.launch { questionRepository.deleteQuestion(questionId) }
    }

    fun updateConfig(config: DetectionConfig) { _config.value = config }

    fun setClaudeVisionEnabled(enabled: Boolean) {
        _config.value = _config.value.copy(useClaudeVision = enabled)
    }

    fun setGeminiVisionEnabled(enabled: Boolean) {
        _config.value = _config.value.copy(useGeminiVision = enabled)
    }

    fun setGeminiModel(model: String) {
        _config.value = _config.value.copy(geminiModel = model)
    }

    fun saveClaudeApiKey(key: String) {
        viewModelScope.launch { apiKeyManager.saveClaudeApiKey(key) }
    }

    fun saveGeminiApiKey(key: String) {
        viewModelScope.launch { apiKeyManager.saveGeminiApiKey(key) }
    }
    
    fun saveGeminiModel(model: String) {
        viewModelScope.launch { apiKeyManager.saveGeminiModel(model) }
    }
}
