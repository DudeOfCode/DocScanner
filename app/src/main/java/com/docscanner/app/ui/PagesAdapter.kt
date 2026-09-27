package com.docscanner.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import com.docscanner.app.databinding.ItemPageBinding
import java.io.File

class PagesAdapter(
    private val pages: () -> List<String>,
    private val selected: () -> Set<Int>,
    private val onToggleSelect: (Int, Boolean) -> Unit,
    private val onMove: (Int, Int) -> Unit,
    private val onCrop: (Int) -> Unit,
    private val onFilter: (Int) -> Unit,
    private val onRotate: (Int) -> Unit,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<PagesAdapter.VH>() {

    inner class VH(val b: ItemPageBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount() = pages().size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val list = pages()
        val path = list[position]
        holder.b.pageLabel.text = "Page ${position + 1}"
        val f = File(path)
        Glide.with(holder.b.pageImage).load(f)
            .diskCacheStrategy(DiskCacheStrategy.NONE).skipMemoryCache(true)
            .signature(ObjectKey(f.lastModified()))
            .into(holder.b.pageImage)

        holder.b.pageCheck.setOnCheckedChangeListener(null)
        holder.b.pageCheck.isChecked = selected().contains(position)
        holder.b.pageCheck.setOnCheckedChangeListener { _, checked -> onToggleSelect(position, checked) }

        holder.b.btnUp.visibility = if (position == 0) android.view.View.INVISIBLE else android.view.View.VISIBLE
        holder.b.btnDown.visibility = if (position == list.size - 1) android.view.View.INVISIBLE else android.view.View.VISIBLE
        holder.b.btnUp.setOnClickListener { onMove(position, -1) }
        holder.b.btnDown.setOnClickListener { onMove(position, 1) }
        holder.b.btnCrop.setOnClickListener { onCrop(position) }
        holder.b.btnFilter.setOnClickListener { onFilter(position) }
        holder.b.btnRotate.setOnClickListener { onRotate(position) }
        holder.b.btnDelete.setOnClickListener { onDelete(position) }
    }
}
