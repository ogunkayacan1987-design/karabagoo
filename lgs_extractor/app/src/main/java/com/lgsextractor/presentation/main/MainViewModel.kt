package com.lgsextractor.presentation.main

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lgsextractor.domain.model.PdfDocument
import com.lgsextractor.domain.repository.PdfRepository
import com.lgsextractor.domain.repository.QuestionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val pdfRepository: PdfRepository,
    private val questionRepository: QuestionRepository
) : ViewModel() {

    sealed class UiState {
        object Idle : UiState()
        object Loading : UiState()
        data class DocumentImported(val document: PdfDocument) : UiState()
        data class Error(val message: String) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val documents = pdfRepository.getAllDocuments()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun importPdf(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            pdfRepository.importPdf(uri).fold(
                onSuccess = { doc ->
                    _uiState.value = UiState.DocumentImported(doc)
                },
                onFailure = { e ->
                    _uiState.value = UiState.Error(e.message ?: "PDF yüklenemedi")
                }
            )
        }
    }

    fun deleteDocument(documentId: String) {
        viewModelScope.launch {
            questionRepository.deleteQuestionsForDocument(documentId)
            pdfRepository.deletePdfDocument(documentId)
        }
    }

    fun resetState() {
        _uiState.value = UiState.Idle
    }
}
