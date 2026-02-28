package com.lgsextractor.presentation.questions

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.chip.Chip
import com.lgsextractor.R
import com.lgsextractor.databinding.ActivityQuestionListBinding
import com.lgsextractor.domain.model.Question
import com.lgsextractor.domain.model.SubjectType
import com.lgsextractor.presentation.adapter.QuestionAdapter
import com.lgsextractor.presentation.export.ExportActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class QuestionListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DOCUMENT_ID = "document_id"
    }

    private lateinit var binding: ActivityQuestionListBinding
    private val viewModel: QuestionListViewModel by viewModels()
    private lateinit var questionAdapter: QuestionAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuestionListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupFilters()
        observeViewModel()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_question_list, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { onBackPressedDispatcher.onBackPressed(); true }
            R.id.action_export_all -> {
                val docId = intent.getStringExtra(EXTRA_DOCUMENT_ID) ?: return true
                startActivity(Intent(this, ExportActivity::class.java).apply {
                    putExtra(ExportActivity.EXTRA_DOCUMENT_ID, docId)
                })
                true
            }
            R.id.action_toggle_grid -> {
                toggleGridMode()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Tespit Edilen Sorular"
        }
    }

    private fun setupRecyclerView() {
        questionAdapter = QuestionAdapter(
            onQuestionClick = { question -> /* preview */ },
            onQuestionDelete = { question -> viewModel.deleteQuestion(question.id) },
            onVerify = { question -> viewModel.markVerified(question.id) }
        )
        binding.recyclerQuestions.apply {
            layoutManager = GridLayoutManager(this@QuestionListActivity, 2)
            adapter = questionAdapter
        }
    }

    private fun setupFilters() {
        // Subject filter chips
        SubjectType.values().forEach { subject ->
            val chip = Chip(this).apply {
                text = subject.turkishName
                isCheckable = true
                setOnCheckedChangeListener { _, _ -> viewModel.filterBySubject(getSelectedSubjects()) }
            }
            binding.chipGroupSubjects.addView(chip)
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.questions.collectLatest { questions ->
                questionAdapter.submitList(questions)
                binding.tvQuestionCount.text = "${questions.size} soru"
                binding.tvEmptyState.visibility = if (questions.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        lifecycleScope.launch {
            viewModel.accuracyMetrics.collectLatest { metrics ->
                metrics ?: return@collectLatest
                binding.tvAccuracy.text = "Doğruluk: F1=%.2f IoU=%.2f".format(metrics.f1Score, metrics.averageIoU)
            }
        }
    }

    private fun getSelectedSubjects(): List<SubjectType> {
        val selected = mutableListOf<SubjectType>()
        for (i in 0 until binding.chipGroupSubjects.childCount) {
            val chip = binding.chipGroupSubjects.getChildAt(i) as? Chip ?: continue
            if (chip.isChecked) {
                SubjectType.values().firstOrNull { it.turkishName == chip.text }?.let {
                    selected.add(it)
                }
            }
        }
        return selected
    }

    private var isGridMode = true
    private fun toggleGridMode() {
        isGridMode = !isGridMode
        val cols = if (isGridMode) 2 else 1
        (binding.recyclerQuestions.layoutManager as GridLayoutManager).spanCount = cols
    }
}
