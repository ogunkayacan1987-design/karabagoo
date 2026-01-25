package com.karabagoo.pdfquestionextractor.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.karabagoo.pdfquestionextractor.data.ExportQuality
import com.karabagoo.pdfquestionextractor.data.ProcessingState
import com.karabagoo.pdfquestionextractor.data.Question
import com.karabagoo.pdfquestionextractor.ml.QuestionDetector
import com.karabagoo.pdfquestionextractor.util.ImageUtils
import com.karabagoo.pdfquestionextractor.util.PdfRendererHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val pdfRenderer = PdfRendererHelper(application)
    private val questionDetector = QuestionDetector()

    private val _processingState = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val processingState: StateFlow<ProcessingState> = _processingState.asStateFlow()

    private val _questions = MutableStateFlow<List<Question>>(emptyList())
    val questions: StateFlow<List<Question>> = _questions.asStateFlow()

    private val _pdfName = MutableStateFlow<String?>(null)
    val pdfName: StateFlow<String?> = _pdfName.asStateFlow()

    private val _pageCount = MutableStateFlow(0)
    val pageCount: StateFlow<Int> = _pageCount.asStateFlow()

    private val _saveProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val saveProgress: StateFlow<Pair<Int, Int>?> = _saveProgress.asStateFlow()

    private val _saveResult = MutableStateFlow<SaveResult?>(null)
    val saveResult: StateFlow<SaveResult?> = _saveResult.asStateFlow()

    private var currentPdfUri: Uri? = null

    /**
     * Opens a PDF file
     */
    fun openPdf(uri: Uri, fileName: String) {
        viewModelScope.launch {
            _processingState.value = ProcessingState.Loading
            _questions.value = emptyList()

            val success = pdfRenderer.openPdf(uri)
            if (success) {
                currentPdfUri = uri
                _pdfName.value = fileName
                _pageCount.value = pdfRenderer.pageCount
                _processingState.value = ProcessingState.Idle
            } else {
                _processingState.value = ProcessingState.Error("PDF açılamadı")
            }
        }
    }

    /**
     * Detects questions in the loaded PDF
     */
    fun detectQuestions() {
        if (currentPdfUri == null) return

        viewModelScope.launch {
            val totalPages = pdfRenderer.pageCount
            if (totalPages == 0) {
                _processingState.value = ProcessingState.Error("PDF sayfası bulunamadı")
                return@launch
            }

            val allQuestions = mutableListOf<Question>()
            var questionIdCounter = 0

            for (pageIndex in 0 until totalPages) {
                _processingState.value = ProcessingState.Processing(pageIndex + 1, totalPages)

                val pageBitmap = pdfRenderer.renderPage(pageIndex)
                if (pageBitmap != null) {
                    val pageQuestions = questionDetector.detectQuestions(
                        pageBitmap = pageBitmap,
                        pageNumber = pageIndex + 1,
                        startQuestionId = questionIdCounter
                    )

                    allQuestions.addAll(pageQuestions)
                    questionIdCounter += pageQuestions.size

                    // Don't recycle the pageBitmap as it might be used by questions
                }
            }

            if (allQuestions.isEmpty()) {
                _processingState.value = ProcessingState.Error("Hiç soru tespit edilemedi")
            } else {
                _questions.value = allQuestions
                _processingState.value = ProcessingState.Success(allQuestions)
            }
        }
    }

    /**
     * Toggles question selection
     */
    fun toggleQuestionSelection(questionId: Int) {
        _questions.value = _questions.value.map { question ->
            if (question.id == questionId) {
                question.copy(isSelected = !question.isSelected)
            } else {
                question
            }
        }
    }

    /**
     * Selects or deselects all questions
     */
    fun setAllQuestionsSelected(selected: Boolean) {
        _questions.value = _questions.value.map { it.copy(isSelected = selected) }
    }

    /**
     * Saves selected questions to gallery
     */
    fun saveSelectedQuestions(quality: ExportQuality = ExportQuality.HIGH) {
        val selectedQuestions = _questions.value.filter { it.isSelected }
        if (selectedQuestions.isEmpty()) {
            _saveResult.value = SaveResult.Error("Kaydedilecek soru seçilmedi")
            return
        }

        saveQuestions(selectedQuestions, quality)
    }

    /**
     * Saves all questions to gallery
     */
    fun saveAllQuestions(quality: ExportQuality = ExportQuality.HIGH) {
        val allQuestions = _questions.value
        if (allQuestions.isEmpty()) {
            _saveResult.value = SaveResult.Error("Kaydedilecek soru bulunamadı")
            return
        }

        saveQuestions(allQuestions, quality)
    }

    private fun saveQuestions(questionsToSave: List<Question>, quality: ExportQuality) {
        viewModelScope.launch {
            _saveProgress.value = Pair(0, questionsToSave.size)

            val results = ImageUtils.saveQuestionsToGallery(
                context = getApplication(),
                questions = questionsToSave,
                baseName = _pdfName.value?.removeSuffix(".pdf") ?: "Soru",
                quality = quality
            ) { current, total ->
                _saveProgress.value = Pair(current, total)
            }

            val successCount = results.count { it != null }
            val failCount = results.size - successCount

            _saveProgress.value = null

            _saveResult.value = if (failCount == 0) {
                SaveResult.Success("$successCount soru başarıyla kaydedildi")
            } else {
                SaveResult.PartialSuccess("$successCount soru kaydedildi, $failCount başarısız")
            }
        }
    }

    /**
     * Saves a single question
     */
    fun saveSingleQuestion(question: Question, quality: ExportQuality = ExportQuality.HIGH) {
        viewModelScope.launch {
            question.bitmap?.let { bitmap ->
                val paddedBitmap = ImageUtils.addPaddingAndBackground(bitmap)
                val fileName = "${_pdfName.value?.removeSuffix(".pdf") ?: "Soru"}_${question.questionNumber}"
                val uri = ImageUtils.saveBitmapToGallery(getApplication(), paddedBitmap, fileName, quality)

                if (paddedBitmap != bitmap) {
                    paddedBitmap.recycle()
                }

                _saveResult.value = if (uri != null) {
                    SaveResult.Success("Soru ${question.questionNumber} kaydedildi")
                } else {
                    SaveResult.Error("Kaydetme başarısız")
                }
            }
        }
    }

    /**
     * Clears save result
     */
    fun clearSaveResult() {
        _saveResult.value = null
    }

    /**
     * Gets selected questions count
     */
    fun getSelectedCount(): Int = _questions.value.count { it.isSelected }

    /**
     * Checks if all questions are selected
     */
    fun areAllSelected(): Boolean = _questions.value.all { it.isSelected }

    override fun onCleared() {
        super.onCleared()
        pdfRenderer.close()
        questionDetector.close()
        ImageUtils.clearShareCache(getApplication())
    }

    sealed class SaveResult {
        data class Success(val message: String) : SaveResult()
        data class PartialSuccess(val message: String) : SaveResult()
        data class Error(val message: String) : SaveResult()
    }
}
