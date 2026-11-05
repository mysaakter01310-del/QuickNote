package com.quicknote.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.quicknote.app.databinding.ItemPinRowBinding
import com.quicknote.app.model.Note
import com.quicknote.app.model.NoteFolder

class PinnedAdapter(private val onPick: (NoteFolder, Note) -> Unit) : RecyclerView.Adapter<PinnedAdapter.VH>() {
    private var rows: List<Pair<NoteFolder, Note>> = emptyList()
    private var showFolder = false

    fun submit(list: List<Pair<NoteFolder, Note>>, showFolderName: Boolean) {
        rows = list
        showFolder = showFolderName
        notifyDataSetChanged()
    }

    class VH(val b: ItemPinRowBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemPinRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val (folder, note) = rows[position]
        h.b.rowTitle.text = note.title
        val sub = note.preview.ifBlank { " " }
        h.b.rowSub.text = if (showFolder) "${folder.name}: $sub" else sub
        h.b.root.setOnClickListener { onPick(folder, note) }
    }

    override fun getItemCount() = rows.size
}
