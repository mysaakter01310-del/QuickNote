package com.quicknote.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.quicknote.app.databinding.DialogCompileBinding
import com.quicknote.app.databinding.FragmentNotesListBinding
import com.quicknote.app.model.Note
import com.quicknote.app.model.NoteFolder
import com.quicknote.app.model.SortMode
import com.quicknote.app.repo.VaultRepository
import com.quicknote.app.util.AppScope
import com.quicknote.app.util.NoteEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NotesListFragment : Fragment() {

    private var _binding: FragmentNotesListBinding? = null
    private val binding get() = _binding!!

    private lateinit var repo: VaultRepository
    private lateinit var folder: NoteFolder
    private lateinit var adapter: NoteAdapter
    private lateinit var touchHelper: ItemTouchHelper
    private var sortMode: SortMode = SortMode.NEWEST
    private var currentQuery: String? = null
    private var reloadJob: Job? = null

    companion object {
        private const val ARG_URI = "uri"
        private const val ARG_NAME = "name"
        private const val ARG_PINNED = "pinned"
        private const val ARG_POSITION = "position"

        fun newInstance(folder: NoteFolder, position: Int): NotesListFragment {
            val f = NotesListFragment()
            f.arguments = Bundle().apply {
                putString(ARG_URI, folder.uri.toString())
                putString(ARG_NAME, folder.name)
                putBoolean(ARG_PINNED, folder.isPinned)
                putInt(ARG_POSITION, position)
            }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentNotesListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = VaultRepository(requireContext().applicationContext)
        val args = requireArguments()
        folder = NoteFolder(
            uri = android.net.Uri.parse(args.getString(ARG_URI)),
            name = args.getString(ARG_NAME) ?: "",
            isPinned = args.getBoolean(ARG_PINNED)
        )

        adapter = NoteAdapter(
            onClick = { note -> if (adapter.selecting) toggle(note) else openEditor(note) },
            onLongClick = { note -> if (adapter.selecting) toggle(note) else startSelection(note) },
            onStartDrag = { vh -> touchHelper.startDrag(vh) }
        )
        binding.recyclerNotes.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerNotes.adapter = adapter
        binding.recyclerNotes.itemAnimator = null

        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun isLongPressDragEnabled() = false
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                if (sortMode != SortMode.MANUAL) return false
                adapter.moveItem(vh.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                if (sortMode == SortMode.MANUAL) {
                    val list = adapter.currentNotes()
                    AppScope.scope.launch { repo.saveManualOrder(folder, list) }
                }
            }
        })
        touchHelper.attachToRecyclerView(binding.recyclerNotes)

        binding.swipeRefresh.setOnRefreshListener { reload() }

        val position = arguments?.getInt(ARG_POSITION) ?: 0
        (requireActivity() as? MainActivity)?.registerFragment(position, this)
        reload()
    }

    // ---------------- loading (always off the main thread) ----------------

    fun reload() {
        if (_binding == null) return
        reloadJob?.cancel()
        reloadJob = viewLifecycleOwner.lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { repo.listNotes(folder, sortMode, currentQuery) }
            show(list)
            _binding?.swipeRefresh?.isRefreshing = false
        }
    }

    private fun show(notes: List<Note>) {
        val b = _binding ?: return
        adapter.dragEnabled = sortMode == SortMode.MANUAL
        adapter.submitList(notes)
        // drop selected notes that no longer exist
        val gone = adapter.selection.filter { adapter.noteAt(it) == null }
        if (gone.isNotEmpty()) {
            adapter.selection.removeAll(gone.toSet())
            notifySelection()
        }
        b.emptyView.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
    }

    fun applyFilter(query: String?) {
        currentQuery = query
        reload()
    }

    fun setSortMode(mode: SortMode) {
        sortMode = mode
        reload()
    }

    private fun openEditor(note: Note) {
        val i = Intent(requireContext(), NoteEditorActivity::class.java)
        i.putExtra("noteUri", note.uri.toString())
        i.putExtra("folderUri", folder.uri.toString())
        i.putExtra("folderName", folder.name)
        startActivity(i)
    }

    // ---------------- selection ----------------

    private fun notifySelection() {
        (activity as? MainActivity)?.onSelectionChanged(adapter.selection.size)
    }

    private fun startSelection(note: Note) {
        adapter.selecting = true
        adapter.selection.clear()
        adapter.selection.add(note.uri.toString())
        adapter.refreshAll()
        notifySelection()
    }

    private fun toggle(note: Note) {
        val key = note.uri.toString()
        if (!adapter.selection.remove(key)) adapter.selection.add(key)
        if (adapter.selection.isEmpty()) {
            exitSelection()
        } else {
            adapter.refreshOne(note)
            notifySelection()
        }
    }

    fun exitSelection() {
        adapter.selecting = false
        adapter.selection.clear()
        adapter.refreshAll()
        notifySelection()
    }

    fun isSelecting() = _binding != null && adapter.selecting

    fun selectAll() {
        val all = adapter.currentNotes()
        if (adapter.selection.size == all.size) {
            exitSelection()
            return
        }
        adapter.selection.clear()
        adapter.selection.addAll(all.map { it.uri.toString() })
        adapter.refreshAll()
        notifySelection()
    }

    // ---------------- actions on the selected notes ----------------

    private fun toastApp(msg: String) {
        context?.let { Toast.makeText(it.applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun runBg(done: String?, work: () -> Unit) {
        val app = requireContext().applicationContext
        exitSelection()
        AppScope.scope.launch {
            try { work() } catch (e: Exception) { }
            withContext(Dispatchers.Main) {
                if (done != null) Toast.makeText(app, done, Toast.LENGTH_SHORT).show()
                NoteEvents.notifyChanged()
            }
        }
    }

    fun pinSelected() {
        val notes = adapter.selectedNotes()
        if (notes.isEmpty()) return
        val pin = !notes.all { it.isPinned }
        repo.setNotesPinned(notes, pin) // in-memory write, instant
        val keys = notes.map { it.uri.toString() }.toSet()
        val updated = adapter.currentNotes().map { if (it.uri.toString() in keys) it.copy(isPinned = pin) else it }
        show(repo.arrange(updated, folder, sortMode))
        exitSelection()
        toastApp(if (pin) "Pinned" else "Unpinned")
    }

    fun copySelected() {
        val notes = adapter.selectedNotes()
        val app = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { notes.mapNotNull { repo.readText(it.uri) }.joinToString("\n\n") }
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("note", text))
            toastApp("Copied")
        }
    }

    fun shareSelected() {
        val notes = adapter.selectedNotes()
        viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { notes.mapNotNull { repo.readText(it.uri) }.joinToString("\n\n") }
            val i = Intent(Intent.ACTION_SEND)
            i.type = "text/plain"
            i.putExtra(Intent.EXTRA_TEXT, text)
            startActivity(Intent.createChooser(i, null))
        }
    }

    fun duplicateSelected() {
        val notes = adapter.selectedNotes()
        runBg("Duplicated") { notes.forEach { repo.duplicateNote(it, folder) } }
    }

    fun moveSelected() {
        val notes = adapter.selectedNotes()
        viewLifecycleOwner.lifecycleScope.launch {
            val folders = withContext(Dispatchers.IO) { repo.listFolders() }.filter { it.uri != folder.uri }
            AlertDialog.Builder(requireContext())
                .setTitle("Move to folder")
                .setItems(folders.map { it.name }.toTypedArray()) { _, which ->
                    runBg("Moved") { notes.forEach { repo.moveNote(it, folder, folders[which]) } }
                }.show()
        }
    }

    fun deleteSelected() {
        val notes = adapter.selectedNotes()
        AlertDialog.Builder(requireContext())
            .setTitle(if (notes.size == 1) "Delete note?" else "Delete ${notes.size} notes?")
            .setMessage("This deletes the markdown files from your phone.")
            .setPositiveButton("Delete") { _, _ ->
                val keys = notes.map { it.uri.toString() }.toSet()
                show(adapter.currentNotes().filter { it.uri.toString() !in keys })
                exitSelection()
                AppScope.scope.launch { notes.forEach { repo.deleteNote(it) } }
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Works for exactly one selected note; the buttons stay visible but dim otherwise. Instant, selection stays. */
    fun moveSelectedUpDown(up: Boolean) {
        if (adapter.selection.size != 1) {
            toastApp("Select one note to move it")
            return
        }
        val note = adapter.selectedNotes().firstOrNull() ?: return
        // take the order you see (pinned stay on top), switch to manual sort, move one step
        val current = adapter.currentNotes()
        val unpinned = current.filter { !it.isPinned }
        if (note.isPinned) {
            toastApp("Unpin it first to move it")
            return
        }
        val moved = repo.moveInList(unpinned, note, up)
        repo.saveManualOrder(folder, moved)
        sortMode = SortMode.MANUAL
        (activity as? MainActivity)?.currentSort = SortMode.MANUAL
        show(repo.arrange(current, folder, SortMode.MANUAL))
    }

    fun compileSelected() {
        val notes = adapter.selectedNotes()
        if (notes.size < 2) {
            toastApp("Select at least 2 notes")
            return
        }
        val d = DialogCompileBinding.inflate(layoutInflater)
        val labels = listOf("Separator: black line", "Separator: blank line", "No separator")
        val values = listOf("line", "blank", "none")
        d.spinSeparator.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)
        AlertDialog.Builder(requireContext())
            .setTitle("Compile ${notes.size} notes")
            .setView(d.root)
            .setPositiveButton("Compile") { _, _ ->
                val title = d.editCompileTitle.text.toString()
                val sep = values[d.spinSeparator.selectedItemPosition]
                val withTitles = d.chkTitles.isChecked
                val del = d.chkDelete.isChecked
                runBg("Compiled ${notes.size} notes") { repo.compileNotes(folder, notes, title, sep, withTitles, del) }
            }
            .setNegativeButton("Cancel", null).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        reloadJob?.cancel()
        _binding = null
    }
}
