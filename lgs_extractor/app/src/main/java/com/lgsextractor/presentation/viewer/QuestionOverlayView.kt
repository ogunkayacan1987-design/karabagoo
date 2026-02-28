package com.lgsextractor.presentation.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.lgsextractor.domain.model.BoundingBox
import com.lgsextractor.domain.model.Question
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Custom View that overlays detected question bounding boxes on the PDF page.
 *
 * Features:
 * - Draw colored rectangles per question
 * - Highlight selected question
 * - Touch to select a question
 * - Drag handles to resize (manual correction)
 * - Confidence-based coloring (green=high, yellow=medium, red=low)
 */
class QuestionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface OverlayListener {
        fun onQuestionSelected(question: Question)
        fun onQuestionBoundaryChanged(question: Question, newBox: BoundingBox)
    }

    var listener: OverlayListener? = null
    var isEditMode: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    // Page dimensions (in bitmap pixels)
    var pageBitmapWidth: Int = 1
    var pageBitmapHeight: Int = 1

    private var questions: List<Question> = emptyList()
    private var selectedQuestion: Question? = null

    // Drag state for resize handles
    private var activeHandle: HandleType? = null
    private var dragStart: PointF? = null
    private var originalBox: BoundingBox? = null

    enum class HandleType { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    // ---- Paints ----
    private val highConfidencePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#2196F3")  // Blue
    }
    private val medConfidencePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#FF9800")  // Orange
    }
    private val lowConfidencePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#F44336")  // Red
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#4CAF50")  // Green
        pathEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#302196F3")  // Semi-transparent blue
    }
    private val selectedFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#304CAF50")  // Semi-transparent green
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val handleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#4CAF50")
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        isFakeBoldText = true
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC2196F3")
    }

    fun setQuestions(questions: List<Question>) {
        this.questions = questions
        invalidate()
    }

    fun selectQuestion(question: Question?) {
        selectedQuestion = question
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (questions.isEmpty()) return

        val scaleX = width.toFloat() / pageBitmapWidth
        val scaleY = height.toFloat() / pageBitmapHeight

        questions.forEach { question ->
            val rect = boxToViewRect(question.boundingBox, scaleX, scaleY)
            val isSelected = question.id == selectedQuestion?.id

            if (isSelected) {
                canvas.drawRect(rect, selectedFillPaint)
                canvas.drawRect(rect, selectedPaint)
                if (isEditMode) drawResizeHandles(canvas, rect)
            } else {
                canvas.drawRect(rect, fillPaint)
                val strokePaint = when {
                    question.confidence >= 0.75f -> highConfidencePaint
                    question.confidence >= 0.50f -> medConfidencePaint
                    else -> lowConfidencePaint
                }
                canvas.drawRect(rect, strokePaint)
            }

            // Question number label
            drawQuestionLabel(canvas, question.questionNumber.toString(), rect, isSelected)
        }
    }

    private fun drawQuestionLabel(canvas: Canvas, label: String, rect: RectF, selected: Boolean) {
        val textW = labelPaint.measureText(label)
        val padH = 6f
        val padV = 4f
        val bgRect = RectF(rect.left, rect.top, rect.left + textW + padH * 2, rect.top + labelPaint.textSize + padV * 2)

        if (selected) {
            labelBgPaint.color = Color.parseColor("#CC4CAF50")
        } else {
            labelBgPaint.color = Color.parseColor("#CC2196F3")
        }
        canvas.drawRoundRect(bgRect, 4f, 4f, labelBgPaint)
        canvas.drawText(label, rect.left + padH, rect.top + labelPaint.textSize, labelPaint)
    }

    private fun drawResizeHandles(canvas: Canvas, rect: RectF) {
        val handleRadius = 18f
        listOf(
            PointF(rect.left, rect.top),
            PointF(rect.right, rect.top),
            PointF(rect.left, rect.bottom),
            PointF(rect.right, rect.bottom)
        ).forEach { pt ->
            canvas.drawCircle(pt.x, pt.y, handleRadius, handlePaint)
            canvas.drawCircle(pt.x, pt.y, handleRadius, handleBorderPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val scaleX = width.toFloat() / pageBitmapWidth
        val scaleY = height.toFloat() / pageBitmapHeight

        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (isEditMode && selectedQuestion != null) {
                    val handle = findNearestHandle(event.x, event.y, selectedQuestion!!, scaleX, scaleY)
                    if (handle != null) {
                        activeHandle = handle
                        dragStart = PointF(event.x, event.y)
                        originalBox = selectedQuestion!!.boundingBox
                        return true
                    }
                }
                // Try to select a question
                val tapped = questions.firstOrNull { q ->
                    boxToViewRect(q.boundingBox, scaleX, scaleY).contains(event.x, event.y)
                }
                if (tapped != null) {
                    selectedQuestion = tapped
                    listener?.onQuestionSelected(tapped)
                    invalidate()
                    true
                } else {
                    selectedQuestion = null
                    invalidate()
                    false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeHandle != null && selectedQuestion != null) {
                    updateBoundaryFromDrag(event.x, event.y, scaleX, scaleY)
                    true
                } else false
            }
            MotionEvent.ACTION_UP -> {
                if (activeHandle != null && selectedQuestion != null) {
                    listener?.onQuestionBoundaryChanged(
                        selectedQuestion!!,
                        selectedQuestion!!.boundingBox
                    )
                    activeHandle = null
                    dragStart = null
                    originalBox = null
                }
                true
            }
            else -> false
        }
    }

    private fun findNearestHandle(
        x: Float, y: Float,
        question: Question,
        scaleX: Float, scaleY: Float
    ): HandleType? {
        val rect = boxToViewRect(question.boundingBox, scaleX, scaleY)
        val handleRadius = 30f
        val handles = mapOf(
            HandleType.TOP_LEFT to PointF(rect.left, rect.top),
            HandleType.TOP_RIGHT to PointF(rect.right, rect.top),
            HandleType.BOTTOM_LEFT to PointF(rect.left, rect.bottom),
            HandleType.BOTTOM_RIGHT to PointF(rect.right, rect.bottom)
        )
        return handles.entries
            .filter { (_, pt) ->
                val dx = x - pt.x; val dy = y - pt.y
                (dx * dx + dy * dy) <= handleRadius * handleRadius
            }
            .minByOrNull { (_, pt) ->
                val dx = x - pt.x; val dy = y - pt.y; dx * dx + dy * dy
            }?.key
    }

    private fun updateBoundaryFromDrag(x: Float, y: Float, scaleX: Float, scaleY: Float) {
        val q = selectedQuestion ?: return
        val origBox = originalBox ?: return
        val box = q.boundingBox

        // Convert touch to bitmap space
        val bitmapX = (x / scaleX).toInt().coerceIn(0, pageBitmapWidth)
        val bitmapY = (y / scaleY).toInt().coerceIn(0, pageBitmapHeight)

        val newBox = when (activeHandle) {
            HandleType.TOP_LEFT -> box.copy(left = min(bitmapX, box.right - 20), top = min(bitmapY, box.bottom - 20))
            HandleType.TOP_RIGHT -> box.copy(right = max(bitmapX, box.left + 20), top = min(bitmapY, box.bottom - 20))
            HandleType.BOTTOM_LEFT -> box.copy(left = min(bitmapX, box.right - 20), bottom = max(bitmapY, box.top + 20))
            HandleType.BOTTOM_RIGHT -> box.copy(right = max(bitmapX, box.left + 20), bottom = max(bitmapY, box.top + 20))
            null -> return
        }

        // Update the question in-memory for live preview
        val updatedQ = q.copy(boundingBox = newBox)
        questions = questions.map { if (it.id == q.id) updatedQ else it }
        selectedQuestion = updatedQ
        invalidate()
    }

    private fun boxToViewRect(box: BoundingBox, scaleX: Float, scaleY: Float): RectF =
        RectF(
            box.left * scaleX,
            box.top * scaleY,
            box.right * scaleX,
            box.bottom * scaleY
        )
}
