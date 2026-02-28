package com.lgsextractor.data.local.database.dao

import androidx.room.*
import com.lgsextractor.data.local.database.entity.CorrectionEntity
import com.lgsextractor.data.local.database.entity.PdfDocumentEntity
import com.lgsextractor.data.local.database.entity.QuestionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface QuestionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuestion(question: QuestionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuestions(questions: List<QuestionEntity>)

    @Query("SELECT * FROM questions WHERE id = :id")
    suspend fun getQuestion(id: String): QuestionEntity?

    @Query("SELECT * FROM questions WHERE documentId = :documentId ORDER BY pageNumber, questionNumber")
    fun getQuestionsForDocument(documentId: String): Flow<List<QuestionEntity>>

    @Query("SELECT * FROM questions WHERE documentId = :documentId ORDER BY pageNumber, questionNumber")
    suspend fun getQuestionsForDocumentSync(documentId: String): List<QuestionEntity>

    @Query("UPDATE questions SET isManuallyVerified = 1 WHERE id = :id")
    suspend fun markAsVerified(id: String)

    @Query("""
        UPDATE questions
        SET boundingBox = :boundingBoxJson
        WHERE id = :id
    """)
    suspend fun updateBoundingBox(id: String, boundingBoxJson: String)

    @Query("DELETE FROM questions WHERE id = :id")
    suspend fun deleteQuestion(id: String)

    @Query("DELETE FROM questions WHERE documentId = :documentId")
    suspend fun deleteQuestionsForDocument(documentId: String)

    @Query("SELECT COUNT(*) FROM questions WHERE documentId = :documentId")
    suspend fun getQuestionCount(documentId: String): Int

    @Query("SELECT COUNT(*) FROM questions WHERE documentId = :documentId AND isManuallyVerified = 1")
    suspend fun getVerifiedCount(documentId: String): Int
}

@Dao
interface PdfDocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: PdfDocumentEntity)

    @Query("SELECT * FROM pdf_documents WHERE id = :id")
    suspend fun getDocument(id: String): PdfDocumentEntity?

    @Query("SELECT * FROM pdf_documents ORDER BY importedAt DESC")
    fun getAllDocuments(): Flow<List<PdfDocumentEntity>>

    @Query("DELETE FROM pdf_documents WHERE id = :id")
    suspend fun deleteDocument(id: String)
}

@Dao
interface CorrectionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCorrection(correction: CorrectionEntity)

    @Query("SELECT * FROM user_corrections WHERE documentId = :documentId ORDER BY timestamp DESC")
    suspend fun getCorrectionsForDocument(documentId: String): List<CorrectionEntity>

    @Query("SELECT COUNT(*) FROM user_corrections WHERE documentId = :documentId")
    suspend fun getCorrectionCount(documentId: String): Int
}
