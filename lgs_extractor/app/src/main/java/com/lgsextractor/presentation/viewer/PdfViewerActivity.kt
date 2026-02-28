package com.lgsextractor.presentation.viewer

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
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
import kotlinx.coroutines.flow.firstOrNull
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
                        Snackbar.make(binding.root, "Hata: ${state.message}", Snackbar.LENGTH_INDEFINITE)
                            .setAction("Tamam") {}
                            .show()
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
        lifecycleScope.launch {
            val existingClaudeKey = viewModel.apiKeyManager.getClaudeApiKey() ?: ""
            val claudeEnabled = viewModel.config.value.useClaudeVision
            
            val existingGeminiKey = viewModel.apiKeyManager.getGeminiApiKey() ?: ""
            val geminiEnabled = viewModel.config.value.useGeminiVision
            val existingGeminiModel = viewModel.apiKeyManager.getGeminiModel() ?: viewModel.config.value.geminiModel

            val layout = LinearLayout(this@PdfViewerActivity).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (16 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }

            val labelGeminiKey = TextView(this@PdfViewerActivity).apply {
                text = "Gemini API Key"
                setPadding(0, 16, 0, 8)
            }
            val editGeminiKey = EditText(this@PdfViewerActivity).apply {
                hint = "AIza..."
                setText(existingGeminiKey)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            val labelGeminiModel = TextView(this@PdfViewerActivity).apply {
                text = "Gemini YZ Modeli (gemini-1.5-pro)"
                setPadding(0, 16, 0, 8)
            }
            val editGeminiModel = EditText(this@PdfViewerActivity).apply {
                hint = "gemini-1.5-pro"
                setText(existingGeminiModel)
                inputType = android.text.InputType.TYPE_CLASS_TEXT
            }
            val checkGeminiVision = CheckBox(this@PdfViewerActivity).apply {
                text = "Gemini Vision ile soru tespit et (Önerilen)"
                isChecked = geminiEnabled
            }

            val labelClaudeKey = TextView(this@PdfViewerActivity).apply {
                text = "Claude API Key"
                setPadding(0, 32, 0, 8)
            }
            val editClaudeKey = EditText(this@PdfViewerActivity).apply {
                hint = "sk-ant-..."
                setText(existingClaudeKey)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            val checkClaudeVision = CheckBox(this@PdfViewerActivity).apply {
                text = "Claude Vision ile soru tespit et"
                isChecked = claudeEnabled
            }

            layout.addView(labelGeminiKey)
            layout.addView(editGeminiKey)
            layout.addView(labelGeminiModel)
            layout.addView(editGeminiModel)
            layout.addView(checkGeminiVision)
            layout.addView(labelClaudeKey)
            layout.addView(editClaudeKey)
            layout.addView(checkClaudeVision)

            // Ensure mutually exclusive checking for AI vision engines to prevent double-billing and collisions
            checkGeminiVision.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) checkClaudeVision.isChecked = false
            }
            checkClaudeVision.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) checkGeminiVision.isChecked = false
            }

            MaterialAlertDialogBuilder(this@PdfViewerActivity)
                .setTitle("AI Ayarları")
                .setView(layout)
                .setNegativeButton("İptal", null)
                .setPositiveButton("Kaydet") { _, _ ->
                    val newClaudeKey = editClaudeKey.text.toString().trim()
                    if (newClaudeKey.isNotBlank()) {
                        if (!newClaudeKey.startsWith("sk-ant-")) {
                            Snackbar.make(
                                binding.root,
                                "Uyarı: Claude API anahtarı 'sk-ant-' ile başlamalıdır",
                                Snackbar.LENGTH_LONG
                            ).show()
                        }
                        viewModel.saveClaudeApiKey(newClaudeKey)
                    } else if (checkClaudeVision.isChecked) {
                        Snackbar.make(
                            binding.root,
                            "Claude Vision için API anahtarı gereklidir",
                            Snackbar.LENGTH_LONG
                        ).show()
                        return@setPositiveButton
                    }
                    viewModel.setClaudeVisionEnabled(checkClaudeVision.isChecked)

                    val newGeminiKey = editGeminiKey.text.toString().trim()
                    if (newGeminiKey.isNotBlank()) viewModel.saveGeminiApiKey(newGeminiKey)

                    val newGeminiModel = editGeminiModel.text.toString().trim()
                    if (newGeminiModel.isNotBlank()) {
                        viewModel.saveGeminiModel(newGeminiModel)
                        viewModel.setGeminiModel(newGeminiModel)
                    }

                    viewModel.setGeminiVisionEnabled(checkGeminiVision.isChecked)

                    Snackbar.make(binding.root, "Ayarlar kaydedildi", Snackbar.LENGTH_SHORT).show()
                }
                .show()
        }
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
