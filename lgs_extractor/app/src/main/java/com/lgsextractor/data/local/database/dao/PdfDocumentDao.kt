package com.lgsextractor.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lgsextractor.data.local.database.entity.PdfDocumentEntity
import kotlinx.coroutines.flow.Flow

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
