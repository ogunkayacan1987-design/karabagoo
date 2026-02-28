package com.lgsextractor.presentation.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.lgsextractor.R
import com.lgsextractor.databinding.ActivityMainBinding
import com.lgsextractor.domain.model.PdfDocument
import com.lgsextractor.presentation.adapter.DocumentAdapter
import com.lgsextractor.presentation.viewer.PdfViewerActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var documentAdapter: DocumentAdapter

    private val pdfPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            // Persist permission for later access
            contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.importPdf(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupFab()
        observeViewModel()

        // Handle PDF opened from file manager
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW && intent.type == "application/pdf") {
            intent.data?.let { viewModel.importPdf(it) }
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)
    }

    private fun setupRecyclerView() {
        documentAdapter = DocumentAdapter(
            onDocumentClick = { doc -> openDocument(doc) },
            onDocumentDelete = { doc -> confirmDelete(doc) }
        )
        binding.recyclerDocuments.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = documentAdapter
        }
    }

    private fun setupFab() {
        binding.fabAddPdf.setOnClickListener {
            pdfPickerLauncher.launch(arrayOf("application/pdf"))
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                when (state) {
                    is MainViewModel.UiState.Idle -> {
                        binding.progressBar.visibility = View.GONE
                    }
                    is MainViewModel.UiState.Loading -> {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.tvEmptyState.visibility = View.GONE
                    }
                    is MainViewModel.UiState.DocumentImported -> {
                        binding.progressBar.visibility = View.GONE
                        openDocument(state.document)
                        viewModel.resetState()
                    }
                    is MainViewModel.UiState.Error -> {
                        binding.progressBar.visibility = View.GONE
                        Snackbar.make(binding.root, state.message, Snackbar.LENGTH_LONG)
                            .setAction("Tamam") { viewModel.resetState() }
                            .show()
                        viewModel.resetState()
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.documents.collectLatest { docs ->
                documentAdapter.submitList(docs)
                binding.tvEmptyState.visibility = if (docs.isEmpty()) View.VISIBLE else View.GONE
                binding.recyclerDocuments.visibility = if (docs.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun openDocument(document: PdfDocument) {
        val intent = Intent(this, PdfViewerActivity::class.java).apply {
            putExtra(PdfViewerActivity.EXTRA_DOCUMENT_ID, document.id)
            putExtra(PdfViewerActivity.EXTRA_DOCUMENT_NAME, document.fileName)
        }
        startActivity(intent)
    }

    private fun confirmDelete(document: PdfDocument) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Dokümanı Sil")
            .setMessage("\"${document.fileName}\" ve tüm tespit edilen sorular silinecek. Emin misiniz?")
            .setNegativeButton("İptal", null)
            .setPositiveButton("Sil") { _, _ ->
                viewModel.deleteDocument(document.id)
                Snackbar.make(binding.root, "Doküman silindi", Snackbar.LENGTH_SHORT).show()
            }
            .show()
    }
}
