package com.lgsextractor.presentation.viewer

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.SeekBar
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.lgsextractor.R
import com.lgsextractor.databinding.ActivityPdfViewerBinding
import com.lgsextractor.domain.model.BoundingBox
import com.lgsextractor.domain.model.Question
import com.lgsextractor.presentation.export.ExportActivity
import com.lgsextractor.presentation.questions.QuestionListActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class PdfViewerActivity : AppCompatActivity(), QuestionOverlayView.OverlayListener {

    companion object {
        const val EXTRA_DOCUMENT_ID = "document_id"
        const val EXTRA_DOCUMENT_NAME = "document_name"
    }

    private lateinit var binding: ActivityPdfViewerBinding
    private val viewModel: PdfViewerViewModel by viewModels()
    private var currentPageCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupPageNavigation()
        setupOverlay()
        setupControls()
        observeViewModel()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_pdf_viewer, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { onBackPressedDispatcher.onBackPressed(); true }
            R.id.action_extract_all -> { confirmExtractAll(); true }
            R.id.action_extract_page -> { extractCurrentPage(); true }
            R.id.action_view_questions -> { openQuestionList(); true }
            R.id.action_export -> { openExport(); true }
            R.id.action_settings -> { openSettings(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = intent.getStringExtra(EXTRA_DOCUMENT_NAME) ?: "PDF Görüntüleyici"
        }
    }

    private fun setupPageNavigation() {
        binding.btnPrevPage.setOnClickListener {
            val current = viewModel.currentPageNumber.value
            if (current > 0) viewModel.loadPage(current - 1)
        }
        binding.btnNextPage.setOnClickListener {
            val current = viewModel.currentPageNumber.value
            if (current < currentPageCount - 1) viewModel.loadPage(current + 1)
        }
        binding.seekbarPage.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) viewModel.loadPage(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun setupOverlay() {
        binding.overlayView.listener = this
        binding.switchEditMode.setOnCheckedChangeListener { _, isChecked ->
            binding.overlayView.isEditMode = isChecked
        }
    }

    private fun setupControls() {
        binding.btnCancelExtraction.setOnClickListener {
            viewModel.cancelExtraction()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.document.collectLatest { doc ->
                doc ?: return@collectLatest
                currentPageCount = doc.pageCount
                binding.seekbarPage.max = maxOf(0, doc.pageCount - 1)
            }
        }

        lifecycleScope.launch {
            viewModel.currentPage.collectLatest { page ->
                page ?: return@collectLatest
                val bitmap = BitmapFactory.decodeFile(page.bitmapPath)
                binding.imagePageView.setImageBitmap(bitmap)
                binding.overlayView.pageBitmapWidth = page.width
                binding.overlayView.pageBitmapHeight = page.height
                updatePageLabel()
            }
        }

        lifecycleScope.launch {
            viewModel.currentPageNumber.collectLatest { pageNum ->
                binding.seekbarPage.progress = pageNum
                updatePageLabel()
            }
        }

        lifecycleScope.launch {
            viewModel.questionsOnPage.collectLatest { questions ->
                binding.overlayView.setQuestions(questions)
                binding.tvQuestionsOnPage.text = "${questions.size} soru bu sayfada"
            }
        }

        lifecycleScope.launch {
            viewModel.processingState.collectLatest { state ->
                when (state) {
                    is PdfViewerViewModel.ProcessingState.Idle -> {
                        binding.processingPanel.visibility = View.GONE
                    }
                    is PdfViewerViewModel.ProcessingState.InProgress -> {
                        binding.processingPanel.visibility = View.VISIBLE
                        binding.progressBarProcessing.progress = state.progress.percentage
                        binding.tvProcessingMessage.text = state.progress.message
                        binding.tvQuestionsFound.text = "${state.progress.questionsFound} soru bulundu"
                    }
                    is PdfViewerViewModel.ProcessingState.Complete -> {
                        binding.processingPanel.visibility = View.GONE
                        Snackbar.make(
                            binding.root,
                            "${state.questionCount} soru başarıyla tespit edildi!",
                            Snackbar.LENGTH_LONG
                        ).setAction("Görüntüle") { openQuestionList() }.show()
                    }
                    is PdfViewerViewModel.ProcessingState.Error -> {
                        binding.processingPanel.visibility = View.GONE
                        Snackbar.make(binding.root, "Hata: ${state.message}", Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun updatePageLabel() {
        val page = viewModel.currentPageNumber.value + 1
        val total = currentPageCount
        binding.tvPageIndicator.text = "$page / $total"
        binding.btnPrevPage.isEnabled = page > 1
        binding.btnNextPage.isEnabled = page < total
    }

    private fun confirmExtractAll() {
        val doc = viewModel.document.value ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("Tüm Sayfaları İşle")
            .setMessage("${doc.pageCount} sayfa OCR ve soru tespiti yapılacak. Bu işlem birkaç dakika alabilir.")
            .setNegativeButton("İptal", null)
            .setPositiveButton("Başlat") { _, _ ->
                viewModel.startExtraction()
            }
            .show()
    }

    private fun extractCurrentPage() {
        val pageNum = viewModel.currentPageNumber.value
        viewModel.startExtraction(pageNum..pageNum)
    }

    private fun openQuestionList() {
        val doc = viewModel.document.value ?: return
        startActivity(Intent(this, QuestionListActivity::class.java).apply {
            putExtra(QuestionListActivity.EXTRA_DOCUMENT_ID, doc.id)
        })
    }

    private fun openExport() {
        val doc = viewModel.document.value ?: return
        startActivity(Intent(this, ExportActivity::class.java).apply {
            putExtra(ExportActivity.EXTRA_DOCUMENT_ID, doc.id)
        })
    }

    private fun openSettings() {
        // TODO: Open settings/config dialog
        Snackbar.make(binding.root, "Ayarlar yakında...", Snackbar.LENGTH_SHORT).show()
    }

    // ---- QuestionOverlayView.OverlayListener ----

    override fun onQuestionSelected(question: Question) {
        binding.cardSelectedQuestion.visibility = View.VISIBLE
        binding.tvSelectedQuestionInfo.text = "Soru ${question.questionNumber} • Güven: ${(question.confidence * 100).toInt()}%"
    }

    override fun onQuestionBoundaryChanged(question: Question, newBox: BoundingBox) {
        viewModel.updateQuestionBoundary(question.id, newBox)
        Snackbar.make(binding.root, "Soru sınırı güncellendi", Snackbar.LENGTH_SHORT).show()
    }
}
