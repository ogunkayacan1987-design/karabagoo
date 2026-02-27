package com.lgsextractor.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lgsextractor.data.local.database.entity.CorrectionEntity

@Dao
interface CorrectionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCorrection(correction: CorrectionEntity)

    @Query("SELECT * FROM user_corrections WHERE documentId = :documentId ORDER BY timestamp DESC")
    suspend fun getCorrectionsForDocument(documentId: String): List<CorrectionEntity>

    @Query("SELECT COUNT(*) FROM user_corrections WHERE documentId = :documentId")
    suspend fun getCorrectionCount(documentId: String): Int
}
