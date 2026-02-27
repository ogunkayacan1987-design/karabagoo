package com.lgsextractor.presentation.export

import android.os.Environment
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lgsextractor.domain.model.Question
import com.lgsextractor.domain.repository.QuestionRepository
import com.lgsextractor.domain.usecase.ExportQuestionsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ExportViewModel @Inject constructor(
    private val questionRepository: QuestionRepository,
    private val exportUseCase: ExportQuestionsUseCase,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val documentId: String = checkNotNull(savedStateHandle["document_id"])

    sealed class ExportState {
        object Idle : ExportState()
        data class InProgress(val exported: Int, val total: Int) : ExportState() {
            val progress: Int get() = if (total == 0) 0 else exported * 100 / total
        }
        data class Complete(val count: Int, val outputDir: String, val zipFile: File?) : ExportState()
        data class Error(val message: String) : ExportState()
    }

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    val questionCount: StateFlow<Int> = questionRepository
        .getQuestionsForDocument(documentId)
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun exportToDownloads(groupByPage: Boolean, quality: Int) {
        viewModelScope.launch {
            val questions = getQuestions()
            if (questions.isEmpty()) {
                _exportState.value = ExportState.Error("Dışa aktarılacak soru bulunamadı")
                return@launch
            }

            val outputDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "LGS_Sorular"
            )

            val config = ExportQuestionsUseCase.ExportConfig(
                outputDir = outputDir,
                groupByPage = groupByPage,
                quality = quality
            )

            exportUseCase.execute(questions, config).collect { (exported, result) ->
                when (result) {
                    is ExportQuestionsUseCase.ExportResult.Success -> {
                        _exportState.value = ExportState.InProgress(
                            exported = result.exportedFiles.size,
                            total = questions.size
                        )
                        if (exported >= questions.size) {
                            _exportState.value = ExportState.Complete(
                                count = result.exportedFiles.size,
                                outputDir = outputDir.absolutePath,
                                zipFile = null
                            )
                        }
                    }
                    is ExportQuestionsUseCase.ExportResult.Error -> {
                        _exportState.value = ExportState.Error(result.message)
                    }
                }
            }
        }
    }

    fun exportAsZip(quality: Int) {
        viewModelScope.launch {
            val questions = getQuestions()
            if (questions.isEmpty()) {
                _exportState.value = ExportState.Error("Dışa aktarılacak soru bulunamadı")
                return@launch
            }

            val outputDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "LGS_Sorular_Temp"
            )

            val config = ExportQuestionsUseCase.ExportConfig(
                outputDir = outputDir,
                quality = quality,
                createZip = true
            )

            exportUseCase.execute(questions, config).collect { (exported, result) ->
                when (result) {
                    is ExportQuestionsUseCase.ExportResult.Success -> {
                        if (result.zipFile != null) {
                            _exportState.value = ExportState.Complete(
                                count = result.exportedFiles.size,
                                outputDir = result.zipFile.parent ?: "",
                                zipFile = result.zipFile
                            )
                        } else {
                            _exportState.value = ExportState.InProgress(
                                exported = result.exportedFiles.size,
                                total = questions.size
                            )
                        }
                    }
                    is ExportQuestionsUseCase.ExportResult.Error ->
                        _exportState.value = ExportState.Error(result.message)
                }
            }
        }
    }

    private suspend fun getQuestions(): List<Question> {
        var result = emptyList<Question>()
        questionRepository.getQuestionsForDocument(documentId).collect { result = it }
        return result
    }
}
