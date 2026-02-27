package com.lgsextractor.presentation.questions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lgsextractor.domain.model.Question
import com.lgsextractor.domain.model.SubjectType
import com.lgsextractor.domain.repository.AccuracyMetrics
import com.lgsextractor.domain.repository.QuestionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class QuestionListViewModel @Inject constructor(
    private val questionRepository: QuestionRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val documentId: String = checkNotNull(savedStateHandle["document_id"])

    private val _subjectFilter = MutableStateFlow<List<SubjectType>>(emptyList())
    private val _sortOrder = MutableStateFlow(SortOrder.BY_PAGE)

    enum class SortOrder { BY_PAGE, BY_QUESTION_NUMBER, BY_CONFIDENCE }

    @OptIn(ExperimentalCoroutinesApi::class)
    val questions: StateFlow<List<Question>> = combine(
        questionRepository.getQuestionsForDocument(documentId),
        _subjectFilter,
        _sortOrder
    ) { questions, filter, sort ->
        val filtered = if (filter.isEmpty()) questions
            else questions.filter { it.subject in filter }

        when (sort) {
            SortOrder.BY_PAGE -> filtered.sortedWith(compareBy({ it.pageNumber }, { it.questionNumber }))
            SortOrder.BY_QUESTION_NUMBER -> filtered.sortedBy { it.questionNumber }
            SortOrder.BY_CONFIDENCE -> filtered.sortedByDescending { it.confidence }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _accuracyMetrics = MutableStateFlow<AccuracyMetrics?>(null)
    val accuracyMetrics: StateFlow<AccuracyMetrics?> = _accuracyMetrics.asStateFlow()

    init {
        loadMetrics()
    }

    fun filterBySubject(subjects: List<SubjectType>) {
        _subjectFilter.value = subjects
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
    }

    fun deleteQuestion(id: String) {
        viewModelScope.launch {
            questionRepository.deleteQuestion(id)
        }
    }

    fun markVerified(id: String) {
        viewModelScope.launch {
            questionRepository.markAsVerified(id)
            loadMetrics()
        }
    }

    private fun loadMetrics() {
        viewModelScope.launch {
            _accuracyMetrics.value = questionRepository.getAccuracyMetrics(documentId)
        }
    }
}
