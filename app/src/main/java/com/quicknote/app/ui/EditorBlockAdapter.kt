package com.quicknote.app.ui

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.quicknote.app.databinding.ItemEditorImageBinding
import com.quicknote.app.databinding.ItemEditorTextBinding
import com.quicknote.app.model.EditorBlock

private const val TYPE_TEXT = 0
private const val TYPE_IMAGE = 1

class EditorBlockAdapter(
    private val blocks: MutableList<EditorBlock>,
    private val onFocusChanged: (Int) -> Unit,
    private val onRemoveImage: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    fun currentBlocks(): List<EditorBlock> = blocks

    fun removeAt(index: Int) {
        if (index in blocks.indices) {
            blocks.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    fun insertImageAt(index: Int, fileName: String, uri: Uri?) {
        val safeIndex = index.coerceIn(0, blocks.size)
        blocks.add(safeIndex, EditorBlock.Image(fileName, uri))
        // Ensure there's always a text block after an image so the cursor has somewhere to go
        if (safeIndex + 1 >= blocks.size || blocks[safeIndex + 1] !is EditorBlock.Text) {
            blocks.add(safeIndex + 1, EditorBlock.Text(""))
        }
        notifyItemRangeInserted(safeIndex, 2)
    }

    override fun getItemViewType(position: Int): Int =
        if (blocks[position] is EditorBlock.Text) TYPE_TEXT else TYPE_IMAGE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_TEXT) {
            val b = ItemEditorTextBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            TextVH(b)
        } else {
            val b = ItemEditorImageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            ImageVH(b)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val block = blocks[position]
        if (holder is TextVH && block is EditorBlock.Text) {
            holder.binding.editBlockText.setText(block.content)
            holder.binding.editBlockText.tag = null
            holder.binding.editBlockText.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onFocusChanged(holder.bindingAdapterPosition)
            }
            holder.binding.editBlockText.addTextChangedListener(SimpleWatcher { text ->
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    (blocks[pos] as? EditorBlock.Text)?.content = text
                }
            })
        } else if (holder is ImageVH && block is EditorBlock.Image) {
            com.quicknote.app.util.Thumbs.load(holder.binding.imgBlockImage, block.uri, 1080)
            holder.binding.btnRemoveImage.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onRemoveImage(pos)
            }
        }
    }

    override fun getItemCount() = blocks.size

    class TextVH(val binding: ItemEditorTextBinding) : RecyclerView.ViewHolder(binding.root)
    class ImageVH(val binding: ItemEditorImageBinding) : RecyclerView.ViewHolder(binding.root)
}

/** Small helper so we don't need a full anonymous TextWatcher each time. */
class SimpleWatcher(private val onChanged: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: android.text.Editable?) {
        onChanged(s?.toString() ?: "")
    }
}
