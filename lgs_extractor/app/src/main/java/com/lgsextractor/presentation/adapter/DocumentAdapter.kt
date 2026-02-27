package com.lgsextractor.presentation.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lgsextractor.databinding.ItemDocumentBinding
import com.lgsextractor.domain.model.PdfDocument
import com.lgsextractor.util.FileUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DocumentAdapter(
    private val onDocumentClick: (PdfDocument) -> Unit,
    private val onDocumentDelete: (PdfDocument) -> Unit
) : ListAdapter<PdfDocument, DocumentAdapter.ViewHolder>(DiffCallback()) {

    inner class ViewHolder(private val binding: ItemDocumentBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(document: PdfDocument) {
            binding.tvFileName.text = document.fileName
            binding.tvPageCount.text = "${document.pageCount} sayfa"
            binding.tvImportDate.text = SimpleDateFormat("dd MMM yyyy", Locale("tr"))
                .format(Date(document.importedAt))
            binding.tvScanType.text = if (document.isScanned) "Taranmış PDF" else "Metin PDF"

            binding.root.setOnClickListener { onDocumentClick(document) }
            binding.btnDelete.setOnClickListener { onDocumentDelete(document) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemDocumentBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    class DiffCallback : DiffUtil.ItemCallback<PdfDocument>() {
        override fun areItemsTheSame(a: PdfDocument, b: PdfDocument) = a.id == b.id
        override fun areContentsTheSame(a: PdfDocument, b: PdfDocument) = a == b
    }
}
