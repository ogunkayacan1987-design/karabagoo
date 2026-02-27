package com.lgsextractor.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Domain model representing a detected question from a PDF page.
 */
@Parcelize
data class Question(
    val id: String,
    val pdfPath: String,
    val pageNumber: Int,
    val questionNumber: Int,
    val questionText: String,
    val options: List<QuestionOption>,
    val boundingBox: BoundingBox,
    val cropImagePath: String? = null,
    val subject: SubjectType = SubjectType.UNKNOWN,
    val confidence: Float = 0f,
    val isManuallyVerified: Boolean = false,
    val hasImage: Boolean = false,
    val hasTable: Boolean = false,
    val isMathFormula: Boolean = false,
    val columnIndex: Int = 0,  // 0 = left, 1 = right (for two-column layouts)
    val publisherFormat: PublisherFormat = PublisherFormat.GENERIC
) : Parcelable

@Parcelize
data class QuestionOption(
    val label: String,        // "A", "B", "C", "D"
    val text: String,
    val boundingBox: BoundingBox,
    val hasImage: Boolean = false
) : Parcelable

@Parcelize
data class BoundingBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val pageWidth: Int,
    val pageHeight: Int
) : Parcelable {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun expand(padding: Int): BoundingBox = BoundingBox(
        left = maxOf(0, left - padding),
        top = maxOf(0, top - padding),
        right = minOf(pageWidth, right + padding),
        bottom = minOf(pageHeight, bottom + padding),
        pageWidth = pageWidth,
        pageHeight = pageHeight
    )

    fun toAndroidRect(): android.graphics.Rect =
        android.graphics.Rect(left, top, right, bottom)

    fun toAndroidRectF(): android.graphics.RectF =
        android.graphics.RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

    /** Normalize to 0..1 range for display scaling */
    fun toNormalized(): NormalizedBox = NormalizedBox(
        left = left.toFloat() / pageWidth,
        top = top.toFloat() / pageHeight,
        right = right.toFloat() / pageWidth,
        bottom = bottom.toFloat() / pageHeight
    )
}

@Parcelize
data class NormalizedBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) : Parcelable {
    fun toPixel(width: Int, height: Int): BoundingBox = BoundingBox(
        left = (left * width).toInt(),
        top = (top * height).toInt(),
        right = (right * width).toInt(),
        bottom = (bottom * height).toInt(),
        pageWidth = width,
        pageHeight = height
    )
}

enum class SubjectType(val displayName: String, val turkishName: String) {
    MATHEMATICS("Mathematics", "Matematik"),
    SCIENCE("Science", "Fen Bilimleri"),
    TURKISH("Turkish", "Türkçe"),
    SOCIAL_STUDIES("Social Studies", "Sosyal Bilgiler"),
    ENGLISH("English", "İngilizce"),
    RELIGION("Religion", "Din Kültürü"),
    UNKNOWN("Unknown", "Bilinmiyor")
}

enum class PublisherFormat(val displayName: String) {
    LGS_OFFICIAL("LGS Resmi"),
    BIREY("Birey Yayınları"),
    FDD("FDD Yayınları"),
    PALME("Palme Yayıncılık"),
    ZAMBAK("Zambak Yayınları"),
    ATA("Ata Yayıncılık"),
    GENERIC("Genel Format")
}
