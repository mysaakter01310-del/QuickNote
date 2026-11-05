package com.quicknote.app.ui

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.quicknote.app.databinding.ActivityNoteEditorBinding
import com.quicknote.app.model.EditorBlock
import com.quicknote.app.model.NoteFolder
import com.quicknote.app.repo.VaultRepository
import com.quicknote.app.util.AppScope
import com.quicknote.app.util.NoteEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NoteEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNoteEditorBinding
    private lateinit var repo: VaultRepository
    private lateinit var noteUri: Uri
    private lateinit var folder: NoteFolder
    private var blockAdapter: EditorBlockAdapter? = null
    private var loaded = false
    private var focusedBlockIndex = 0
    private val newImageUriHolder = arrayOfNulls<Uri>(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNoteEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.editorToolbar)
        binding.editorToolbar.setNavigationOnClickListener { saveAndFinish() }

        repo = VaultRepository(this)
        noteUri = Uri.parse(intent.getStringExtra("noteUri"))
        folder = NoteFolder(
            uri = Uri.parse(intent.getStringExtra("folderUri")),
            name = intent.getStringExtra("folderName") ?: ""
        )
        binding.recyclerBlocks.layoutManager = LinearLayoutManager(this)

        // Read the note in the background so the screen opens instantly.
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                val fullText = repo.readText(noteUri) ?: ""
                val lines = fullText.lines()
                val title = lines.firstOrNull()?.removePrefix("# ")?.trim() ?: ""
                val body = lines.drop(1).joinToString("\n").trimStart('\n')
                Pair(title, repo.parseBlocks(body, folder).toMutableList())
            }
            binding.editTitle.setText(data.first)
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
        binding.btnAddImageCamera.setOnClickListener { if (loaded) launchCamera() }
    }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        insertImageAtFocus(uri)
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = newImageUriHolder[0]
        if (success && uri != null) insertImageAtFocus(uri)
    }

    private fun launchCamera() {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "quicknote_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        newImageUriHolder[0] = uri
        if (uri != null) cameraLauncher.launch(uri)
    }

    private fun insertImageAtFocus(sourceUri: Uri) {
        lifecycleScope.launch {
            val a = withContext(Dispatchers.IO) {
                repo.importAttachmentFull(folder, sourceUri, "img_${System.currentTimeMillis()}")
            } ?: return@launch
            blockAdapter?.insertImageAt(focusedBlockIndex + 1, a.first.substringAfterLast("/"), a.second)
        }
    }

    private fun saveAndFinish() {
        val adapter = blockAdapter
        if (loaded && adapter != null) {
            val title = binding.editTitle.text.toString().trim().ifBlank { "Note " + VaultRepository.stamp() }
            val fullText = "# $title\n\n" + repo.serializeBlocks(adapter.currentBlocks())
            val app = applicationContext
            val uri = noteUri
            // Write in the background, close the screen immediately.
            AppScope.scope.launch {
                VaultRepository(app).writeText(uri, fullText)
                NoteEvents.notifyChanged()
            }
        }
        finish()
    }

    override fun onBackPressed() {
        saveAndFinish()
    }
}
