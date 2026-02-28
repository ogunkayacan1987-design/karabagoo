package com.lgsextractor.data.local.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.lgsextractor.data.local.database.Converters
import com.lgsextractor.domain.model.BoundingBox
import com.lgsextractor.domain.model.PublisherFormat
import com.lgsextractor.domain.model.Question
import com.lgsextractor.domain.model.QuestionOption
import com.lgsextractor.domain.model.SubjectType

@Entity(tableName = "questions")
@TypeConverters(Converters::class)
data class QuestionEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val pageNumber: Int,
    val questionNumber: Int,
    val questionText: String,
    val options: List<QuestionOption>,
    val boundingBox: BoundingBox,
    val cropImagePath: String?,
    val subject: String,
    val confidence: Float,
    val isManuallyVerified: Boolean,
    val hasImage: Boolean,
    val hasTable: Boolean,
    val isMathFormula: Boolean,
    val columnIndex: Int,
    val publisherFormat: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): Question = Question(
        id = id,
        pdfPath = documentId,
        pageNumber = pageNumber,
        questionNumber = questionNumber,
        questionText = questionText,
        options = options,
        boundingBox = boundingBox,
        cropImagePath = cropImagePath,
        subject = SubjectType.valueOf(subject),
        confidence = confidence,
        isManuallyVerified = isManuallyVerified,
        hasImage = hasImage,
        hasTable = hasTable,
        isMathFormula = isMathFormula,
        columnIndex = columnIndex,
        publisherFormat = PublisherFormat.valueOf(publisherFormat)
    )

    companion object {
        fun fromDomain(q: Question): QuestionEntity = QuestionEntity(
            id = q.id,
            documentId = q.pdfPath,
            pageNumber = q.pageNumber,
            questionNumber = q.questionNumber,
            questionText = q.questionText,
            options = q.options,
            boundingBox = q.boundingBox,
            cropImagePath = q.cropImagePath,
            subject = q.subject.name,
            confidence = q.confidence,
            isManuallyVerified = q.isManuallyVerified,
            hasImage = q.hasImage,
            hasTable = q.hasTable,
            isMathFormula = q.isMathFormula,
            columnIndex = q.columnIndex,
            publisherFormat = q.publisherFormat.name
        )
    }
}

@Entity(tableName = "pdf_documents")
data class PdfDocumentEntity(
    @PrimaryKey val id: String,
    val filePath: String,
    val fileName: String,
    val pageCount: Int,
    val fileSizeBytes: Long,
    val isScanned: Boolean,
    val detectedPublisher: String,
    val detectedSubject: String,
    val importedAt: Long
)

@Entity(tableName = "user_corrections")
data class CorrectionEntity(
    @PrimaryKey(autoGenerate = true) val correctionId: Long = 0,
    val questionId: String,
    val documentId: String,
    val originalLeft: Int,
    val originalTop: Int,
    val originalRight: Int,
    val originalBottom: Int,
    val correctedLeft: Int,
    val correctedTop: Int,
    val correctedRight: Int,
    val correctedBottom: Int,
    val pageWidth: Int,
    val pageHeight: Int,
    val timestamp: Long = System.currentTimeMillis()
)
