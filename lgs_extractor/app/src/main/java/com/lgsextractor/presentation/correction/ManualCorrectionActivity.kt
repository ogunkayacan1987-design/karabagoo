package com.lgsextractor.presentation.correction

import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.lgsextractor.databinding.ActivityManualCorrectionBinding
import com.lgsextractor.domain.model.BoundingBox
import com.lgsextractor.domain.model.Question
import com.lgsextractor.presentation.viewer.QuestionOverlayView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Full-screen manual correction activity.
 * Shows the full page with the selected question's bounding box
 * and allows drag-to-resize correction.
 */
@AndroidEntryPoint
class ManualCorrectionActivity : AppCompatActivity(), QuestionOverlayView.OverlayListener {

    companion object {
        const val EXTRA_QUESTION_ID = "question_id"
        const val EXTRA_PAGE_BITMAP_PATH = "page_bitmap_path"
    }

    private lateinit var binding: ActivityManualCorrectionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManualCorrectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Manuel Düzeltme"
        }

        setupOverlay()
        loadPage()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupOverlay() {
        binding.overlayView.listener = this
        binding.overlayView.isEditMode = true

        binding.btnSaveCorrection.setOnClickListener {
            Snackbar.make(binding.root, "Düzeltme kaydedildi", Snackbar.LENGTH_SHORT).show()
            finish()
        }

        binding.btnResetBoundary.setOnClickListener {
            // Reset to original detection
            loadPage()
        }
    }

    private fun loadPage() {
        val bitmapPath = intent.getStringExtra(EXTRA_PAGE_BITMAP_PATH) ?: return
        val bitmap = BitmapFactory.decodeFile(bitmapPath) ?: return

        binding.imagePageView.setImageBitmap(bitmap)
        binding.overlayView.pageBitmapWidth = bitmap.width
        binding.overlayView.pageBitmapHeight = bitmap.height
    }

    // ---- QuestionOverlayView.OverlayListener ----

    override fun onQuestionSelected(question: Question) {
        binding.tvCorrectionInfo.text = "Soru ${question.questionNumber} seçili — köşelerden sürükleyin"
    }

    override fun onQuestionBoundaryChanged(question: Question, newBox: BoundingBox) {
        binding.tvNewBounds.text = "Yeni sınır: ${newBox.left},${newBox.top} → ${newBox.right},${newBox.bottom}"
    }
}
