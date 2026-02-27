package com.lgsextractor.data.local.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "corrections")
data class CorrectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val questionId: String,
    val documentId: String,
    val originalLeft: Float,
    val originalTop: Float,
    val originalRight: Float,
    val originalBottom: Float,
    val correctedLeft: Float,
    val correctedTop: Float,
    val correctedRight: Float,
    val correctedBottom: Float,
    val pageWidth: Int,
    val pageHeight: Int,
    val timestamp: Long = System.currentTimeMillis()
)
