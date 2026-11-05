package com.quicknote.app.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.quicknote.app.databinding.ActivityNoteEditorBinding
import com.quicknote.app.model.NoteFolder
import com.quicknote.app.repo.VaultRepository
import com.quicknote.app.util.AppScope
import com.quicknote.app.util.NoteEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The note title is the file name. A new note's file is only created when you save.
 * Leaving without changes touches nothing, so reading a note never moves it in the list.
 */
class NoteEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNoteEditorBinding
    private lateinit var repo: VaultRepository
    private var noteUri: Uri? = null
    private lateinit var folder: NoteFolder
    private var blockAdapter: EditorBlockAdapter? = null
    private var loaded = false
    private var focusedBlockIndex = 0
    private var initialTitle = ""
    private var initialText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNoteEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.editorToolbar.root)
        binding.editorToolbar.root.setNavigationOnClickListener { onBackPressed() }
        binding.editorToolbar.btnSaveTick.setOnClickListener { save(true) }

        repo = VaultRepository(this)
        noteUri = intent.getStringExtra("noteUri")?.let { Uri.parse(it) }
        folder = NoteFolder(
            uri = Uri.parse(intent.getStringExtra("folderUri")),
            name = intent.getStringExtra("folderName") ?: ""
        )
        binding.recyclerBlocks.layoutManager = LinearLayoutManager(this)

        lifecycleScope.launch {
            val uri = noteUri
            val data = withContext(Dispatchers.IO) {
                val text = if (uri != null) repo.readText(uri) ?: "" else ""
                Pair(text, repo.parseBlocks(text, folder).toMutableList())
            }
            val title = uri?.path?.let { java.io.File(it).name.removeSuffix(".md") } ?: ""
            binding.editTitle.setText(title)
            initialTitle = title
            initialText = data.first
            val adapter = EditorBlockAdapter(
                blocks = data.second,
                onFocusChanged = { index -> focusedBlockIndex = index },
                onRemoveImage = { index -> blockAdapter?.removeAt(index) }
            )
            blockAdapter = adapter
            binding.recyclerBlocks.adapter = adapter
            loaded = true
        }

        binding.btnAddImageGallery.setOnClickListener { if (loaded) galleryLauncher.launch("image/*") }
    }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        insertImageAtFocus(uri)
    }

    private fun insertImageAtFocus(sourceUri: Uri) {
        lifecycleScope.launch {
            val a = withContext(Dispatchers.IO) {
                repo.importAttachmentFull(folder, sourceUri, "img_${System.currentTimeMillis()}")
            } ?: return@launch
            blockAdapter?.insertImageAt(focusedBlockIndex + 1, a.first.substringAfterLast("/"), a.second)
        }
    }

    private fun currentText(): String = blockAdapter?.let { repo.serializeBlocks(it.currentBlocks()) } ?: initialText

    private fun isDirty(): Boolean {
        if (!loaded) return false
        return binding.editTitle.text.toString().trim() != initialTitle.trim() || currentText() != initialText
    }

    private fun isEmptyNew(): Boolean =
        noteUri == null && binding.editTitle.text.toString().isBlank() && currentText().isBlank()

    /** Saves in the background and closes. Nothing is written if nothing changed. */
    private fun save(close: Boolean) {
        if (!loaded) { finish(); return }
        val newTitle = binding.editTitle.text.toString().trim()
        val text = currentText()
        val existing = noteUri
        if (existing == null && isEmptyNew()) { finish(); return }
        if (existing != null && !isDirty()) { finish(); return }
        val app = applicationContext
        val f = folder
        val textChanged = text != initialText
        AppScope.scope.launch {
            val r = VaultRepository(app)
            if (existing == null) {
                r.createNote(f, text, newTitle.ifBlank { null })
            } else {
                var uri: Uri = existing
                if (textChanged) r.writeText(uri, text)
                if (newTitle.isNotEmpty() && newTitle != initialTitle.trim()) uri = r.renameNote(uri, newTitle) ?: uri
            }
            NoteEvents.notifyChanged()
        }
        if (close) finish()
    }

    override fun onBackPressed() {
        if (!isDirty() && !(noteUri == null && !isEmptyNew())) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Save changes?")
            .setPositiveButton("Save") { _, _ -> save(true) }
            .setNegativeButton("Discard") { _, _ -> finish() }
            .setNeutralButton("Keep editing", null)
            .show()
    }
}
