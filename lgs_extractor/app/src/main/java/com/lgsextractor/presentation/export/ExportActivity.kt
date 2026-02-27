package com.lgsextractor.presentation.export

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.lgsextractor.databinding.ActivityExportBinding
import com.lgsextractor.domain.usecase.ExportQuestionsUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

@AndroidEntryPoint
class ExportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DOCUMENT_ID = "document_id"
    }

    private lateinit var binding: ActivityExportBinding
    private val viewModel: ExportViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupButtons()
        observeViewModel()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Dışa Aktar"
        }
    }

    private fun setupButtons() {
        binding.btnExportToDownloads.setOnClickListener {
            viewModel.exportToDownloads(
                groupByPage = binding.switchGroupByPage.isChecked,
                quality = binding.sliderQuality.value.toInt()
            )
        }

        binding.btnExportAsZip.setOnClickListener {
            viewModel.exportAsZip(
                quality = binding.sliderQuality.value.toInt()
            )
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.exportState.collectLatest { state ->
                when (state) {
                    is ExportViewModel.ExportState.Idle -> {
                        binding.progressExport.visibility = View.GONE
                    }
                    is ExportViewModel.ExportState.InProgress -> {
                        binding.progressExport.visibility = View.VISIBLE
                        binding.progressExport.progress = state.progress
                        binding.tvExportStatus.text = "${state.exported} / ${state.total} soru dışa aktarıldı"
                    }
                    is ExportViewModel.ExportState.Complete -> {
                        binding.progressExport.visibility = View.GONE
                        binding.tvExportStatus.text = "✓ ${state.count} soru kaydedildi: ${state.outputDir}"

                        Snackbar.make(binding.root, "${state.count} soru dışa aktarıldı", Snackbar.LENGTH_LONG)
                            .setAction("Aç") {
                                state.zipFile?.let { shareFile(it) }
                            }.show()
                    }
                    is ExportViewModel.ExportState.Error -> {
                        binding.progressExport.visibility = View.GONE
                        Snackbar.make(binding.root, "Hata: ${state.message}", Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.questionCount.collectLatest { count ->
                binding.tvQuestionCount.text = "Dışa aktarılacak: $count soru"
            }
        }
    }

    private fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Paylaş"
        ))
    }
}
