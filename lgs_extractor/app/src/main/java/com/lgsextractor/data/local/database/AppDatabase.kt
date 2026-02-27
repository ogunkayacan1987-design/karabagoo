package com.lgsextractor.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.lgsextractor.data.local.database.dao.CorrectionDao
import com.lgsextractor.data.local.database.dao.PdfDocumentDao
import com.lgsextractor.data.local.database.dao.QuestionDao
import com.lgsextractor.data.local.database.entity.CorrectionEntity
import com.lgsextractor.data.local.database.entity.PdfDocumentEntity
import com.lgsextractor.data.local.database.entity.QuestionEntity

@Database(
    entities = [
        QuestionEntity::class,
        PdfDocumentEntity::class,
        CorrectionEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun questionDao(): QuestionDao
    abstract fun pdfDocumentDao(): PdfDocumentDao
    abstract fun correctionDao(): CorrectionDao

    companion object {
        const val DATABASE_NAME = "lgs_extractor.db"
    }
}
