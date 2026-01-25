package com.karabagoo.pdfquestionextractor.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.karabagoo.pdfquestionextractor.R
import com.karabagoo.pdfquestionextractor.data.ExportQuality
import com.karabagoo.pdfquestionextractor.data.ProcessingState
import com.karabagoo.pdfquestionextractor.data.Question
import com.karabagoo.pdfquestionextractor.databinding.ActivityMainBinding
import com.karabagoo.pdfquestionextractor.util.ImageUtils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var questionAdapter: QuestionAdapter

    private val pdfPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { handlePdfSelected(it) }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            pendingSaveAction?.invoke()
            pendingSaveAction = null
        } else {
            Toast.makeText(this, R.string.permission_required, Toast.LENGTH_SHORT).show()
        }
    }

    private var pendingSaveAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupViews()
        setupRecyclerView()
        observeViewModel()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
    }

    private fun setupViews() {
        // PDF Selection
        binding.btnSelectPdf.setOnClickListener {
            openPdfPicker()
        }

        binding.cardPdfSelection.setOnClickListener {
            openPdfPicker()
        }

        // Detection
        binding.btnDetectQuestions.setOnClickListener {
            viewModel.detectQuestions()
        }

        // Save buttons
        binding.btnSaveAll.setOnClickListener {
            checkPermissionAndSave { viewModel.saveAllQuestions() }
        }

        binding.btnSaveSelected.setOnClickListener {
            checkPermissionAndSave { viewModel.saveSelectedQuestions() }
        }

        // Select all toggle
        binding.btnSelectAll.setOnClickListener {
            val allSelected = viewModel.areAllSelected()
            viewModel.setAllQuestionsSelected(!allSelected)
            updateSelectAllButton(!allSelected)
        }
    }

    private fun setupRecyclerView() {
        questionAdapter = QuestionAdapter(
            onQuestionClick = { question ->
                viewModel.toggleQuestionSelection(question.id)
            },
            onPreviewClick = { question ->
                showQuestionPreview(question)
            },
            onDownloadClick = { question ->
                checkPermissionAndSave { viewModel.saveSingleQuestion(question) }
            },
            onShareClick = { question ->
                shareQuestion(question)
            }
        )

        binding.recyclerQuestions.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = questionAdapter
            setHasFixedSize(false)
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.processingState.collectLatest { state ->
                updateUIForState(state)
            }
        }

        lifecycleScope.launch {
            viewModel.questions.collectLatest { questions ->
                questionAdapter.submitList(questions.toList())
                updateQuestionsUI(questions)
            }
        }

        lifecycleScope.launch {
            viewModel.pdfName.collectLatest { name ->
                binding.tvPdfName.text = name ?: getString(R.string.no_pdf_selected)
            }
        }

        lifecycleScope.launch {
            viewModel.pageCount.collectLatest { count ->
                if (count > 0) {
                    binding.tvPageCount.text = getString(R.string.total_pages, count)
                    binding.tvPageCount.visibility = View.VISIBLE
                } else {
                    binding.tvPageCount.visibility = View.GONE
                }
            }
        }

        lifecycleScope.launch {
            viewModel.saveProgress.collectLatest { progress ->
                progress?.let { (current, total) ->
                    binding.cardProgress.visibility = View.VISIBLE
                    binding.tvProgressText.text = "Kaydediliyor... $current/$total"
                } ?: run {
                    binding.cardProgress.visibility = View.GONE
                }
            }
        }

        lifecycleScope.launch {
            viewModel.saveResult.collectLatest { result ->
                result?.let {
                    val message = when (it) {
                        is MainViewModel.SaveResult.Success -> it.message
                        is MainViewModel.SaveResult.PartialSuccess -> it.message
                        is MainViewModel.SaveResult.Error -> it.message
                    }
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                    viewModel.clearSaveResult()
                }
            }
        }
    }

    private fun updateUIForState(state: ProcessingState) {
        when (state) {
            is ProcessingState.Idle -> {
                binding.cardProgress.visibility = View.GONE
                binding.cardActions.visibility = if (viewModel.pageCount.value > 0) View.VISIBLE else View.GONE
            }

            is ProcessingState.Loading -> {
                binding.cardProgress.visibility = View.VISIBLE
                binding.tvProgressText.text = getString(R.string.processing)
                binding.cardActions.visibility = View.GONE
            }

            is ProcessingState.Processing -> {
                binding.cardProgress.visibility = View.VISIBLE
                binding.tvProgressText.text = getString(R.string.analyzing_page, state.currentPage)
                binding.cardActions.visibility = View.GONE
            }

            is ProcessingState.Success -> {
                binding.cardProgress.visibility = View.GONE
                binding.cardActions.visibility = View.VISIBLE
                binding.layoutSaveButtons.visibility = View.VISIBLE
            }

            is ProcessingState.Error -> {
                binding.cardProgress.visibility = View.GONE
                binding.cardActions.visibility = View.VISIBLE
                Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateQuestionsUI(questions: List<Question>) {
        if (questions.isNotEmpty()) {
            binding.layoutQuestionsHeader.visibility = View.VISIBLE
            binding.recyclerQuestions.visibility = View.VISIBLE
            binding.tvQuestionsFound.text = getString(R.string.questions_found, questions.size)
            updateSelectAllButton(viewModel.areAllSelected())
        } else {
            binding.layoutQuestionsHeader.visibility = View.GONE
            binding.recyclerQuestions.visibility = View.GONE
        }
    }

    private fun updateSelectAllButton(allSelected: Boolean) {
        binding.btnSelectAll.text = if (allSelected) {
            getString(R.string.deselect_all)
        } else {
            getString(R.string.select_all)
        }
    }

    private fun openPdfPicker() {
        pdfPickerLauncher.launch(arrayOf("application/pdf"))
    }

    private fun handlePdfSelected(uri: Uri) {
        val fileName = getFileName(uri) ?: "document.pdf"
        viewModel.openPdf(uri, fileName)
    }

    private fun getFileName(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            if (nameIndex >= 0) cursor.getString(nameIndex) else null
        }
    }

    private fun checkPermissionAndSave(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ doesn't need permission for MediaStore
            action()
        } else {
            val permissions = arrayOf(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )

            val allGranted = permissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }

            if (allGranted) {
                action()
            } else {
                pendingSaveAction = action
                permissionLauncher.launch(permissions)
            }
        }
    }

    private fun showQuestionPreview(question: Question) {
        question.bitmap?.let { bitmap ->
            val intent = Intent(this, QuestionPreviewActivity::class.java).apply {
                putExtra("question_id", question.id)
                putExtra("question_number", question.questionNumber)
                putExtra("page_number", question.pageNumber)
            }

            // Save bitmap to cache for preview
            lifecycleScope.launch {
                val file = ImageUtils.createShareableFile(
                    this@MainActivity,
                    bitmap,
                    "preview_${question.id}"
                )
                file?.let {
                    intent.putExtra("image_path", it.absolutePath)
                    startActivity(intent)
                }
            }
        }
    }

    private fun shareQuestion(question: Question) {
        question.bitmap?.let { bitmap ->
            lifecycleScope.launch {
                val file = ImageUtils.createShareableFile(
                    this@MainActivity,
                    bitmap,
                    "soru_${question.questionNumber}"
                )

                file?.let {
                    val uri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.fileprovider",
                        it
                    )

                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    startActivity(Intent.createChooser(shareIntent, "Soruyu Paylaş"))
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_help -> {
                showHelpDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.how_to_use)
            .setMessage(R.string.instructions)
            .setPositiveButton(R.string.ok, null)
            .show()
    }
}
