package com.karabagoo.pdfquestionextractor.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.karabagoo.pdfquestionextractor.R
import com.karabagoo.pdfquestionextractor.data.Question
import com.karabagoo.pdfquestionextractor.databinding.ItemQuestionBinding

class QuestionAdapter(
    private val onQuestionClick: (Question) -> Unit,
    private val onPreviewClick: (Question) -> Unit,
    private val onDownloadClick: (Question) -> Unit,
    private val onShareClick: (Question) -> Unit
) : ListAdapter<Question, QuestionAdapter.QuestionViewHolder>(QuestionDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QuestionViewHolder {
        val binding = ItemQuestionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return QuestionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: QuestionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class QuestionViewHolder(
        private val binding: ItemQuestionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(question: Question) {
            val context = binding.root.context

            // Set question image
            question.bitmap?.let {
                binding.ivQuestionPreview.setImageBitmap(it)
            }

            // Set question number
            binding.tvQuestionNumber.text = context.getString(R.string.question_number, question.questionNumber)

            // Set page number
            binding.tvPageNumber.text = context.getString(R.string.page_number, question.pageNumber)

            // Set card selection state
            binding.cardQuestion.isChecked = question.isSelected

            // Card background based on selection
            binding.cardQuestion.setCardBackgroundColor(
                context.getColor(
                    if (question.isSelected) R.color.card_selected else R.color.card_background
                )
            )

            // Click listeners
            binding.cardQuestion.setOnClickListener {
                onQuestionClick(question)
            }

            binding.btnPreview.setOnClickListener {
                onPreviewClick(question)
            }

            binding.btnDownload.setOnClickListener {
                onDownloadClick(question)
            }

            binding.btnShare.setOnClickListener {
                onShareClick(question)
            }
        }
    }

    class QuestionDiffCallback : DiffUtil.ItemCallback<Question>() {
        override fun areItemsTheSame(oldItem: Question, newItem: Question): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Question, newItem: Question): Boolean {
            return oldItem == newItem
        }
    }
}
