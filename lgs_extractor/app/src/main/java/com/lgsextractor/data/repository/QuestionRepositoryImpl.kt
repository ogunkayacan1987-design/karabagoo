package com.lgsextractor.data.repository

import com.google.gson.Gson
import com.lgsextractor.data.local.database.dao.CorrectionDao
import com.lgsextractor.data.local.database.dao.QuestionDao
import com.lgsextractor.data.local.database.entity.CorrectionEntity
import com.lgsextractor.data.local.database.entity.QuestionEntity
import com.lgsextractor.domain.model.BoundingBox
import com.lgsextractor.domain.model.Question
import com.lgsextractor.domain.repository.AccuracyMetrics
import com.lgsextractor.domain.repository.QuestionRepository
import com.lgsextractor.domain.repository.UserCorrection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QuestionRepositoryImpl @Inject constructor(
    private val questionDao: QuestionDao,
    private val correctionDao: CorrectionDao,
    private val gson: Gson
) : QuestionRepository {

    override suspend fun saveQuestion(question: Question) = withContext(Dispatchers.IO) {
        questionDao.insertQuestion(QuestionEntity.fromDomain(question))
    }

    override suspend fun saveQuestions(questions: List<Question>) = withContext(Dispatchers.IO) {
        questionDao.insertQuestions(questions.map { QuestionEntity.fromDomain(it) })
    }

    override suspend fun getQuestion(id: String): Question? = withContext(Dispatchers.IO) {
        questionDao.getQuestion(id)?.toDomain()
    }

    override fun getQuestionsForDocument(documentId: String): Flow<List<Question>> {
        return questionDao.getQuestionsForDocument(documentId)
            .map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun updateQuestionBoundary(id: String, box: BoundingBox) =
        withContext(Dispatchers.IO) {
            questionDao.updateBoundingBox(id, gson.toJson(box))
        }

    override suspend fun markAsVerified(id: String) = withContext(Dispatchers.IO) {
        questionDao.markAsVerified(id)
    }

    override suspend fun deleteQuestion(id: String) = withContext(Dispatchers.IO) {
        questionDao.deleteQuestion(id)
    }

    override suspend fun deleteQuestionsForDocument(documentId: String) =
        withContext(Dispatchers.IO) {
            questionDao.deleteQuestionsForDocument(documentId)
        }

    override suspend fun saveUserCorrection(
        questionId: String,
        originalBox: BoundingBox,
        correctedBox: BoundingBox
    ) = withContext(Dispatchers.IO) {
        // Get the question to find documentId
        val question = questionDao.getQuestion(questionId)
        correctionDao.insertCorrection(
            CorrectionEntity(
                questionId = questionId,
                documentId = question?.documentId ?: "",
                originalLeft = originalBox.left,
                originalTop = originalBox.top,
                originalRight = originalBox.right,
                originalBottom = originalBox.bottom,
                correctedLeft = correctedBox.left,
                correctedTop = correctedBox.top,
                correctedRight = correctedBox.right,
                correctedBottom = correctedBox.bottom,
                pageWidth = originalBox.pageWidth,
                pageHeight = originalBox.pageHeight
            )
        )
    }

    override suspend fun getUserCorrections(documentId: String): List<UserCorrection> =
        withContext(Dispatchers.IO) {
            correctionDao.getCorrectionsForDocument(documentId).map { entity ->
                UserCorrection(
                    questionId = entity.questionId,
                    originalBox = BoundingBox(
                        entity.originalLeft, entity.originalTop,
                        entity.originalRight, entity.originalBottom,
                        entity.pageWidth, entity.pageHeight
                    ),
                    correctedBox = BoundingBox(
                        entity.correctedLeft, entity.correctedTop,
                        entity.correctedRight, entity.correctedBottom,
                        entity.pageWidth, entity.pageHeight
                    ),
                    timestamp = entity.timestamp
                )
            }
        }

    override suspend fun getAccuracyMetrics(documentId: String): AccuracyMetrics =
        withContext(Dispatchers.IO) {
            val total = questionDao.getQuestionCount(documentId)
            val verified = questionDao.getVerifiedCount(documentId)
            val corrected = correctionDao.getCorrectionCount(documentId)

            // Compute average IoU from corrections
            val corrections = correctionDao.getCorrectionsForDocument(documentId)
            val avgIoU = if (corrections.isEmpty()) 0f else {
                corrections.map { c ->
                    computeIoU(
                        BoundingBox(c.originalLeft, c.originalTop, c.originalRight, c.originalBottom, c.pageWidth, c.pageHeight),
                        BoundingBox(c.correctedLeft, c.correctedTop, c.correctedRight, c.correctedBottom, c.pageWidth, c.pageHeight)
                    )
                }.average().toFloat()
            }

            val precision = if (total == 0) 0f else verified.toFloat() / total
            val recall = if (total == 0) 0f else (total - corrected).toFloat() / total

            AccuracyMetrics(
                totalQuestions = total,
                verifiedQuestions = verified,
                correctedQuestions = corrected,
                averageIoU = avgIoU,
                precisionScore = precision,
                recallScore = recall
            )
        }

    /**
     * Intersection over Union for two bounding boxes.
     */
    private fun computeIoU(a: BoundingBox, b: BoundingBox): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)

        if (interRight <= interLeft || interBottom <= interTop) return 0f

        val interArea = (interRight - interLeft) * (interBottom - interTop).toFloat()
        val aArea = a.width * a.height.toFloat()
        val bArea = b.width * b.height.toFloat()

        return interArea / (aArea + bArea - interArea)
    }
}
