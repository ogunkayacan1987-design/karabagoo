package com.lgsextractor.domain.repository

import com.lgsextractor.domain.model.BoundingBox
import com.lgsextractor.domain.model.Question
import kotlinx.coroutines.flow.Flow

interface QuestionRepository {
    suspend fun saveQuestion(question: Question)
    suspend fun saveQuestions(questions: List<Question>)
    suspend fun getQuestion(id: String): Question?
    fun getQuestionsForDocument(documentId: String): Flow<List<Question>>
    suspend fun updateQuestionBoundary(id: String, box: BoundingBox)
    suspend fun markAsVerified(id: String)
    suspend fun deleteQuestion(id: String)
    suspend fun deleteQuestionsForDocument(documentId: String)
    suspend fun saveUserCorrection(questionId: String, originalBox: BoundingBox, correctedBox: BoundingBox)
    suspend fun getUserCorrections(documentId: String): List<UserCorrection>
    suspend fun getAccuracyMetrics(documentId: String): AccuracyMetrics
}

data class UserCorrection(
    val questionId: String,
    val originalBox: BoundingBox,
    val correctedBox: BoundingBox,
    val timestamp: Long = System.currentTimeMillis()
)

data class AccuracyMetrics(
    val totalQuestions: Int,
    val verifiedQuestions: Int,
    val correctedQuestions: Int,
    val averageIoU: Float,     // Intersection over Union of corrected boxes
    val precisionScore: Float,
    val recallScore: Float
) {
    val f1Score: Float get() = if (precisionScore + recallScore == 0f) 0f
        else 2f * precisionScore * recallScore / (precisionScore + recallScore)
}
