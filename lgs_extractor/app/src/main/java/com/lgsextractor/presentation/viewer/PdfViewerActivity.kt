package com.lgsextractor.presentation.viewer

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
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
import com.lgsextractor.domain.model.DetectionConfig
import com.lgsextractor.domain.model.Question
import com.lgsextractor.presentation.export.ExportActivity
import com.lgsextractor.presentation.questions.QuestionListActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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

            val btnTestClaude = Button(this@PdfViewerActivity).apply {
                text = "Claude Bağlantısını Test Et"
            }
            val btnTestPage = Button(this@PdfViewerActivity).apply {
                text = "Mevcut Sayfayı Claude ile Test Et"
            }
            val tvTestResult = TextView(this@PdfViewerActivity).apply {
                setPadding(0, 8, 0, 8)
                textSize = 11f
                setTextIsSelectable(true)
            }

            layout.addView(labelGeminiKey)
            layout.addView(editGeminiKey)
            layout.addView(labelGeminiModel)
            layout.addView(editGeminiModel)
            layout.addView(checkGeminiVision)
            layout.addView(labelClaudeKey)
            layout.addView(editClaudeKey)
            layout.addView(checkClaudeVision)
            layout.addView(btnTestClaude)
            layout.addView(btnTestPage)
            layout.addView(tvTestResult)

            val scrollView = ScrollView(this@PdfViewerActivity).also { it.addView(layout) }

            // Ensure mutually exclusive checking for AI vision engines to prevent double-billing and collisions
            checkGeminiVision.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) checkClaudeVision.isChecked = false
            }
            checkClaudeVision.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) checkGeminiVision.isChecked = false
            }

            btnTestClaude.setOnClickListener {
                val key = editClaudeKey.text.toString().trim()
                if (key.isBlank()) {
                    tvTestResult.text = "❌ API anahtarı boş!"
                    return@setOnClickListener
                }
                tvTestResult.text = "⏳ Test ediliyor..."
                btnTestClaude.isEnabled = false
                lifecycleScope.launch {
                    val result = testClaudeApiKey(key)
                    tvTestResult.text = result
                    btnTestClaude.isEnabled = true
                }
            }

            btnTestPage.setOnClickListener {
                val key = editClaudeKey.text.toString().trim()
                if (key.isBlank()) { tvTestResult.text = "❌ API anahtarı boş!"; return@setOnClickListener }
                val page = viewModel.currentPage.value
                if (page == null) { tvTestResult.text = "❌ Sayfa yüklü değil!"; return@setOnClickListener }
                tvTestResult.text = "⏳ Sayfa Claude'a gönderiliyor (max 4096 token)..."
                btnTestPage.isEnabled = false
                lifecycleScope.launch {
                    val result = testPageWithClaude(key, page.bitmapPath)
                    tvTestResult.text = result
                    btnTestPage.isEnabled = true
                }
            }

            MaterialAlertDialogBuilder(this@PdfViewerActivity)
                .setTitle("AI Ayarları")
                .setView(scrollView)
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

    /**
     * Sends the current page bitmap to Claude and shows raw JSON response.
     * Use this to debug why 0 questions are found.
     */
    private suspend fun testPageWithClaude(apiKey: String, bitmapPath: String): String =
        withContext(Dispatchers.IO) {
            try {
                val bitmap = android.graphics.BitmapFactory.decodeFile(bitmapPath)
                    ?: return@withContext "❌ Bitmap yüklenemedi: $bitmapPath"

                // Scale down for test
                val maxDim = 1600
                val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                    val ratio = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
                    android.graphics.Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * ratio).toInt(),
                        (bitmap.height * ratio).toInt(),
                        true
                    )
                } else bitmap

                val baos = java.io.ByteArrayOutputStream()
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
                val base64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)

                val body = JSONObject().apply {
                    put("model", "claude-opus-4-6")
                    put("max_tokens", 4096)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "image")
                                    put("source", JSONObject().apply {
                                        put("type", "base64")
                                        put("media_type", "image/jpeg")
                                        put("data", base64)
                                    })
                                })
                                put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", """Bu Türkçe LGS sınav sayfasındaki soruları JSON formatında döndür:
{"questions":[{"number":1,"text":"...","options":{"A":"...","B":"...","C":"...","D":"..."}}]}
Sadece JSON yaz.""")
                                })
                            })
                        })
                    })
                }.toString()

                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(90, TimeUnit.SECONDS)
                    .build()

                val req = Request.Builder()
                    .url("https://api.anthropic.com/v1/messages")
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("content-type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(req).execute()
                val responseBody = response.body?.string() ?: "(boş yanıt)"

                if (!response.isSuccessful) {
                    return@withContext "❌ HTTP ${response.code}:\n$responseBody"
                }

                // Extract text from response
                val text = runCatching {
                    JSONObject(responseBody).getJSONArray("content")
                        .getJSONObject(0).getString("text")
                }.getOrDefault(responseBody)

                // Count questions found in response
                val qCount = runCatching {
                    val j = text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)
                    JSONObject(j).getJSONArray("questions").length()
                }.getOrDefault(-1)

                if (qCount >= 0) {
                    "✅ Claude ${qCount} soru buldu!\n\nJSON önizleme (ilk 600 karakter):\n${text.take(600)}"
                } else {
                    "⚠️ JSON parse edilemedi.\n\nClaude yanıtı (ilk 800 karakter):\n${text.take(800)}"
                }
            } catch (e: Exception) {
                "❌ Hata: ${e.javaClass.simpleName}: ${e.message}"
            }
        }

    /** Makes a minimal Claude API call and returns a human-readable result string. */
    private suspend fun testClaudeApiKey(apiKey: String): String = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            val body = JSONObject().apply {
                put("model", "claude-haiku-4-5-20251001")
                put("max_tokens", 32)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "Merhaba, sadece 'OK' yaz.")
                    })
                })
            }.toString()

            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: "(boş yanıt)"

            if (response.isSuccessful) {
                val text = JSONObject(responseBody)
                    .getJSONArray("content")
                    .getJSONObject(0)
                    .getString("text")
                "✅ Bağlantı başarılı!\nYanıt: $text"
            } else {
                val errorMsg = runCatching {
                    JSONObject(responseBody).optString("error", responseBody)
                }.getOrDefault(responseBody)
                "❌ Hata ${response.code}:\n$errorMsg"
            }
        } catch (e: Exception) {
            "❌ Bağlantı hatası:\n${e.javaClass.simpleName}: ${e.message}"
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
