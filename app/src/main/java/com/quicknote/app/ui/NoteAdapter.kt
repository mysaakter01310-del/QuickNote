package com.quicknote.app.ui

import android.annotation.SuppressLint
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.quicknote.app.R
import com.quicknote.app.databinding.ItemNoteBinding
import com.quicknote.app.model.Note
import com.quicknote.app.util.Thumbs

class NoteAdapter(
    private val onClick: (Note) -> Unit,
    private val onLongClick: (Note) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<NoteAdapter.VH>() {

    private val notes = mutableListOf<Note>()
    val selection = mutableListOf<String>() // note uris, in the order they were tapped
    var selecting = false
    var dragEnabled = false

    fun submitList(newNotes: List<Note>) {
        val old = notes.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newNotes.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].uri == newNotes[n].uri
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == newNotes[n]
        })
        notes.clear()
        notes.addAll(newNotes)
        diff.dispatchUpdatesTo(this)
    }

    fun currentNotes(): List<Note> = notes.toList()

    fun noteAt(uri: String): Note? = notes.firstOrNull { it.uri.toString() == uri }

    fun selectedNotes(): List<Note> = selection.mapNotNull { noteAt(it) }

    fun refreshAll() = notifyDataSetChanged()

    fun refreshOne(note: Note) {
        val i = notes.indexOfFirst { it.uri == note.uri }
        if (i >= 0) notifyItemChanged(i)
    }

    fun moveItem(from: Int, to: Int) {
        if (from < 0 || to < 0 || from >= notes.size || to >= notes.size) return
        val item = notes.removeAt(from)
        notes.add(to, item)
        notifyItemMoved(from, to)
    }

    inner class VH(val binding: ItemNoteBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemNoteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: VH, position: Int) {
        val note = notes[position]
        val b = holder.binding
        b.txtTitle.text = note.title
        b.txtPreview.text = note.preview
        val timeAgo = DateUtils.getRelativeTimeSpanString(note.dateModified)
        val sizeKb = note.sizeBytes / 1024
        b.txtMeta.text = if (sizeKb > 0) "$sizeKb KB • $timeAgo" else "$timeAgo"
        b.imgPin.visibility = if (note.isPinned) View.VISIBLE else View.GONE

        if (note.firstImageUri != null) {
            b.imgThumb.visibility = View.VISIBLE
            Thumbs.load(b.imgThumb, note.firstImageUri, 144)
        } else {
            b.imgThumb.visibility = View.GONE
            Thumbs.load(b.imgThumb, null, 0)
        }

        val order = selection.indexOf(note.uri.toString())
        if (selecting) {
            b.txtBadge.visibility = View.VISIBLE
            b.imgDrag.visibility = View.GONE
            if (order >= 0) {
                b.txtBadge.setBackgroundResource(R.drawable.badge_on)
                b.txtBadge.text = (order + 1).toString()
            } else {
                b.txtBadge.setBackgroundResource(R.drawable.badge_off)
                b.txtBadge.text = ""
            }
        } else {
            b.txtBadge.visibility = View.GONE
            b.imgDrag.visibility = if (dragEnabled) View.VISIBLE else View.GONE
        }

        b.imgDrag.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN && dragEnabled) onStartDrag(holder)
            false
        }
        b.root.setOnClickListener { onClick(note) }
        b.root.setOnLongClickListener { onLongClick(note); true }
    }

    override fun getItemCount() = notes.size
}
