package com.karabagoo.pdfquestionextractor.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.karabagoo.pdfquestionextractor.R
import com.karabagoo.pdfquestionextractor.databinding.ActivityQuestionPreviewBinding
import com.karabagoo.pdfquestionextractor.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class QuestionPreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQuestionPreviewBinding
    private var imagePath: String? = null
    private var questionNumber: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuestionPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        imagePath = intent.getStringExtra("image_path")
        questionNumber = intent.getIntExtra("question_number", 0)
        val pageNumber = intent.getIntExtra("page_number", 0)

        setupToolbar(questionNumber, pageNumber)
        loadImage()
        setupDownloadButton()
    }

    private fun setupToolbar(questionNumber: Int, pageNumber: Int) {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(false)
            title = getString(R.string.question_number, questionNumber)
            subtitle = getString(R.string.page_number, pageNumber)
        }

        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun loadImage() {
        imagePath?.let { path ->
            lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    BitmapFactory.decodeFile(path)
                }
                bitmap?.let {
                    binding.ivPreview.setImageBitmap(it)
                }
            }
        }
    }

    private fun setupDownloadButton() {
        binding.fabDownload.setOnClickListener {
            imagePath?.let { path ->
                lifecycleScope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        BitmapFactory.decodeFile(path)
                    }

                    bitmap?.let {
                        val uri = ImageUtils.saveBitmapToGallery(
                            this@QuestionPreviewActivity,
                            it,
                            "Soru_$questionNumber"
                        )

                        val message = if (uri != null) {
                            getString(R.string.saved_successfully)
                        } else {
                            getString(R.string.save_failed)
                        }
                        Toast.makeText(this@QuestionPreviewActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up the temporary preview file
        imagePath?.let {
            File(it).delete()
        }
    }
}
