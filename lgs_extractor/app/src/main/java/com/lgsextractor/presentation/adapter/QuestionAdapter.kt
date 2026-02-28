package com.lgsextractor.presentation.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.lgsextractor.R
import com.lgsextractor.databinding.ItemQuestionBinding
import com.lgsextractor.domain.model.Question
import java.io.File

class QuestionAdapter(
    private val onQuestionClick: (Question) -> Unit,
    private val onQuestionDelete: (Question) -> Unit,
    private val onVerify: (Question) -> Unit
) : ListAdapter<Question, QuestionAdapter.ViewHolder>(DiffCallback()) {

    inner class ViewHolder(private val binding: ItemQuestionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(question: Question) {
            binding.tvQuestionNumber.text = "Soru ${question.questionNumber}"
            binding.tvPageNumber.text = "Sayfa ${question.pageNumber + 1}"
            binding.tvSubject.text = question.subject.turkishName
            binding.tvConfidence.text = "%.0f%%".format(question.confidence * 100)

            // Confidence color
            val confColor = when {
                question.confidence >= 0.75f -> R.color.confidence_high
                question.confidence >= 0.50f -> R.color.confidence_medium
                else -> R.color.confidence_low
            }
            binding.tvConfidence.setTextColor(ContextCompat.getColor(binding.root.context, confColor))

            // Verification badge
            binding.ivVerified.visibility = if (question.isManuallyVerified)
                android.view.View.VISIBLE else android.view.View.GONE

            // Load crop image
            question.cropImagePath?.let { path ->
                Glide.with(binding.ivQuestionCrop)
                    .load(File(path))
                    .placeholder(R.drawable.ic_image_placeholder)
                    .error(R.drawable.ic_image_placeholder)
                    .into(binding.ivQuestionCrop)
            } ?: run {
                binding.ivQuestionCrop.setImageResource(R.drawable.ic_image_placeholder)
            }

            // Indicators
            binding.ivHasImage.visibility = if (question.hasImage) android.view.View.VISIBLE else android.view.View.GONE
            binding.ivHasFormula.visibility = if (question.isMathFormula) android.view.View.VISIBLE else android.view.View.GONE

            binding.root.setOnClickListener { onQuestionClick(question) }
            binding.btnDelete.setOnClickListener { onQuestionDelete(question) }
            binding.btnVerify.setOnClickListener { onVerify(question) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemQuestionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    class DiffCallback : DiffUtil.ItemCallback<Question>() {
        override fun areItemsTheSame(a: Question, b: Question) = a.id == b.id
        override fun areContentsTheSame(a: Question, b: Question) = a == b
    }
}
