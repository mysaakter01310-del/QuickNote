package com.quicknote.app.util

import android.content.Context
import android.net.Uri
import com.quicknote.app.model.Note
import com.quicknote.app.model.NoteFolder
import com.quicknote.app.repo.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Saving / image-copy logic shared by the Quick Add popup and the floating overlay. */
object QuickSaver {

    class Result(val noteUri: Uri, val name: String, val attachments: List<Uri>, val folderName: String)

    /** Copies picked images into the app cache right away, so nothing is lost if the screen dies. */
    fun copyPicked(ctx: Context, uris: List<Uri>) {
        val app = ctx.applicationContext
        AppScope.scope.launch {
            val paths = ArrayList<String>()
            val dir = File(app.cacheDir, "qa_images").apply { mkdirs() }
            for (u in uris) {
                try {
                    val mime = app.contentResolver.getType(u) ?: "image/jpeg"
                    val f = File(dir, "qa_${System.nanoTime()}.${VaultRepository.extFor(mime)}")
                    app.contentResolver.openInputStream(u)?.use { input -> f.outputStream().use { out -> input.copyTo(out) } }
                    if (f.length() > 0) paths.add(f.absolutePath)
                } catch (e: Exception) {
                }
            }
            withContext(Dispatchers.Main) {
                DraftStore.ensure(app)
                DraftStore.draft.images.addAll(paths)
                DraftStore.save(app)
                DraftStore.notifyChanged()
            }
        }
    }

    fun cleanup(paths: List<String>) {
        for (p in paths) try { File(p).delete() } catch (e: Exception) { }
    }

    /** Runs on a background thread. Returns null on failure. */
    fun save(ctx: Context, d: Draft): Result? {
        val repo = VaultRepository(ctx)
        val edit = d.editNoteUri != null
        val folder = when {
            edit -> NoteFolder(Uri.parse(d.editFolderUri), d.folderName)
            d.folderUri != null -> NoteFolder(Uri.parse(d.folderUri), d.folderName)
            else -> repo.rootFolder() ?: return null
        }
        val refs = ArrayList<String>()
        val imported = ArrayList<Uri>()
        for (p in d.images) {
            val f = File(p)
            if (!f.exists()) continue
            val a = repo.importAttachmentFull(folder, Uri.fromFile(f), "img_${System.currentTimeMillis()}_${refs.size}") ?: continue
            refs.add(a.first)
            imported.add(a.second)
        }
        if (edit) {
            val sb = StringBuilder(d.body.trimEnd())
            for (r in refs) sb.append("\n\n![[").append(r).append("]]")
            sb.append("\n")
            var uri = Uri.parse(d.editNoteUri)
            if (!repo.writeText(uri, sb.toString())) return null
            val wanted = d.title.trim()
            if (wanted.isNotEmpty()) uri = repo.renameNote(uri, wanted) ?: uri
            return Result(uri, wanted, imported, folder.name)
        }
        val title = d.title.trim().ifBlank { "Note " + VaultRepository.stamp() }
        val sb = StringBuilder()
        for (r in refs) sb.append("![[").append(r).append("]]\n\n")
        sb.append(d.body.trim()).append("\n")
        val uri = repo.createNote(folder, sb.toString(), title) ?: return null
        return Result(uri, title, imported, folder.name)
    }

    /** Loads an existing note into an editable draft: title = file name, body = the whole file. Background thread. */
    fun loadForEdit(repo: VaultRepository, note: Note, folder: NoteFolder): Draft? {
        val text = repo.readText(note.uri) ?: return null
        return Draft(
            title = note.title, body = text, folderUri = folder.uri.toString(), folderName = folder.name,
            editNoteUri = note.uri.toString(), editFolderUri = folder.uri.toString(), editHasHeading = false
        )
    }
}
