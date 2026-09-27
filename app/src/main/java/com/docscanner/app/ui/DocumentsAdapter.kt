package com.docscanner.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.docscanner.app.data.ScanDocument
import com.docscanner.app.databinding.ItemDocumentBinding
import java.io.File

class DocumentsAdapter(
    private val onClick: (ScanDocument) -> Unit
) : RecyclerView.Adapter<DocumentsAdapter.VH>() {

    private val items = mutableListOf<ScanDocument>()

    fun submit(list: List<ScanDocument>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    inner class VH(val b: ItemDocumentBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemDocumentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val doc = items[position]
        holder.b.title.text = doc.title
        holder.b.date.text = doc.date
        holder.b.pageBadge.text = "${doc.pages.size} Pages"
        doc.pages.firstOrNull()?.let {
            Glide.with(holder.b.thumb).load(File(it)).into(holder.b.thumb)
        }
        holder.b.root.setOnClickListener { onClick(doc) }
    }
}
