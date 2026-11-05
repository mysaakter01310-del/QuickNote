package com.quicknote.app.repo

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import com.quicknote.app.model.EditorBlock
import com.quicknote.app.model.Note
import com.quicknote.app.model.NoteFolder
import com.quicknote.app.model.SortMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val PREFS = "quicknote_prefs"
private const val KEY_VAULT_URI = "vault_uri"
private const val KEY_PINNED_NOTES = "pinned_notes"
private const val KEY_PINNED_FOLDER = "pinned_folder"
private const val KEY_MANUAL_ORDER_PREFIX = "manual_order_"
private const val INBOX_FOLDER_NAME = "QuickNotes"
private const val ATTACHMENTS_DIR = "attachments"
private val IMAGE_TAG_REGEX = Regex("""!\[\[([^\]]+)]]""")
private val BAD_NAME_CHARS = Regex("[\\\\/:*?\"<>|#^\\[\\]]")

private class Child(val docId: String, val name: String, val mime: String, val modified: Long, val size: Long) {
    val isDir get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
}

private class Meta(val title: String, val preview: String, val img: String?, val modified: Long, val size: Long)

private val noteIndex = ConcurrentHashMap<String, Meta>()
@Volatile private var indexLoaded = false

/**
 * Reads and writes notes directly as markdown files inside the user's chosen vault folder.
 *
 * Speed notes: the folder listing is ONE query (name, date, size together), only the first
 * 8 KB of a note is read for title/preview, and results are cached in an index so unchanged
 * notes are never re-read. Everything here must be called from a background thread.
 */
class VaultRepository(private val context: Context) {

    companion object {
        fun stamp(): String = SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.US).format(System.currentTimeMillis())
        fun extFor(mime: String): String = when {
            mime.contains("png") -> "png"
            mime.contains("webp") -> "webp"
            mime.contains("gif") -> "gif"
            else -> "jpg"
        }
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val resolver get() = context.contentResolver

    fun hasVault(): Boolean = getVaultUri() != null
    fun getVaultUri(): Uri? = prefs.getString(KEY_VAULT_URI, null)?.let { Uri.parse(it) }

    fun setVaultUri(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        prefs.edit().putString(KEY_VAULT_URI, uri.toString()).apply()
    }

    // ---------------- low-level helpers ----------------

    private fun rootDocUri(): Uri? {
        val tree = getVaultUri() ?: return null
        val id = if (DocumentsContract.isDocumentUri(context, tree)) DocumentsContract.getDocumentId(tree)
        else DocumentsContract.getTreeDocumentId(tree)
        return DocumentsContract.buildDocumentUriUsingTree(tree, id)
    }

    fun rootFolder(): NoteFolder? = rootDocUri()?.let { NoteFolder(it, INBOX_FOLDER_NAME, true) }

    private fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(getVaultUri()!!, docId)

    private fun normalize(created: Uri): Uri = docUri(DocumentsContract.getDocumentId(created))

    private fun children(parentDoc: Uri): List<Child> {
        val tree = getVaultUri() ?: return emptyList()
        val out = ArrayList<Child>()
        try {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parentDoc))
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE
            )
            resolver.query(uri, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    out.add(
                        Child(
                            c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "",
                            if (c.isNull(3)) 0L else c.getLong(3), if (c.isNull(4)) 0L else c.getLong(4)
                        )
                    )
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun isAttachDir(c: Child) = c.isDir && (c.name == ATTACHMENTS_DIR || c.name == ".attachments")

    private fun displayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    fun sanitizeFileName(s: String): String =
        s.replace(BAD_NAME_CHARS, "-").trim().take(80).trim('.', ' ').ifBlank { "Note ${stamp()}" }

    // ---------------- index cache ----------------

    private fun ensureIndex() {
        if (indexLoaded) return
        synchronized(noteIndex) {
            if (indexLoaded) return
            try {
                val f = File(context.filesDir, "note_index.json")
                if (f.exists()) {
                    val arr = JSONArray(f.readText())
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        noteIndex[o.getString("k")] = Meta(
                            o.getString("t"), o.getString("p"), o.optString("i").ifEmpty { null },
                            o.getLong("m"), o.getLong("s")
                        )
                    }
                }
            } catch (e: Exception) {
            }
            indexLoaded = true
        }
    }

    private fun saveIndex() {
        try {
            val arr = JSONArray()
            for ((k, v) in noteIndex) {
                arr.put(JSONObject().put("k", k).put("t", v.title).put("p", v.preview).put("i", v.img ?: "").put("m", v.modified).put("s", v.size))
            }
            File(context.filesDir, "note_index.json").writeText(arr.toString())
        } catch (e: Exception) {
        }
    }

    private fun readHead(uri: Uri, max: Int): String? = try {
        resolver.openInputStream(uri)?.use { s ->
            val buf = ByteArray(max)
            var n = 0
            while (n < max) {
                val r = s.read(buf, n, max - n)
                if (r < 0) break
                n += r
            }
            String(buf, 0, n, Charsets.UTF_8)
        }
    } catch (e: Exception) {
        null
    }

    private fun readMeta(uri: Uri, c: Child): Meta {
        val fallback = c.name.removeSuffix(".md")
        val head = readHead(uri, 8192) ?: return Meta(fallback, "", null, c.modified, c.size)
        val lines = head.lines()
        val title = lines.firstOrNull { it.isNotBlank() }?.removePrefix("# ")?.trim()?.ifBlank { fallback } ?: fallback
        val preview = lines.drop(1).joinToString(" ").replace(IMAGE_TAG_REGEX, "").trim().take(140)
        val img = IMAGE_TAG_REGEX.find(head)?.groupValues?.get(1)
        return Meta(title, preview, img, c.modified, c.size)
    }

    // ---------------- Folders ----------------

    fun listFolders(): List<NoteFolder> {
        val root = rootDocUri() ?: return emptyList()
        val pinnedName = prefs.getString(KEY_PINNED_FOLDER, null)
        val subs = children(root)
            .filter { it.isDir && !it.name.startsWith(".") && it.name != ATTACHMENTS_DIR }
            .sortedBy { it.name.lowercase() }
            .map { NoteFolder(docUri(it.docId), it.name, it.name == pinnedName) }
        return listOf(NoteFolder(root, INBOX_FOLDER_NAME, true)) + subs
    }

    fun createFolder(name: String): Boolean {
        val root = rootDocUri() ?: return false
        return try {
            DocumentsContract.createDocument(resolver, root, DocumentsContract.Document.MIME_TYPE_DIR, name) != null
        } catch (e: Exception) {
            false
        }
    }

    fun deleteFolder(folder: NoteFolder): Boolean = deleteUri(folder.uri)

    fun renameFolder(folder: NoteFolder, newName: String): Boolean = try {
        DocumentsContract.renameDocument(resolver, folder.uri, newName) != null
    } catch (e: Exception) {
        false
    }

    fun setPinnedFolder(name: String?) {
        prefs.edit().putString(KEY_PINNED_FOLDER, name).apply()
    }

    // ---------------- Notes ----------------

    /** hydrate=false: instant, uses cached info (or file names). hydrate=true: reads changed notes only. */
    fun listNotes(folder: NoteFolder, sortMode: SortMode, query: String? = null, hydrate: Boolean = true): List<Note> {
        ensureIndex()
        val kids = children(folder.uri)
        val pinned = pinnedNoteUris()
        val attachDir = kids.firstOrNull { isAttachDir(it) }
        var attachMap: Map<String, Uri>? = null
        var dirty = false
        var notes = ArrayList<Note>()
        for (k in kids) {
            if (k.isDir || !k.name.endsWith(".md")) continue
            val uri = docUri(k.docId)
            val key = uri.toString()
            var meta = noteIndex[key]
            if (hydrate && (meta == null || meta.modified != k.modified || meta.size != k.size)) {
                meta = readMeta(uri, k)
                noteIndex[key] = meta
                dirty = true
            }
            val m: Meta = meta ?: Meta(k.name.removeSuffix(".md"), "", null, k.modified, k.size)
            var imgUri: Uri? = null
            val imgName = m.img
            if (imgName != null && attachDir != null) {
                if (attachMap == null) attachMap = attachmentMapOf(attachDir.docId)
                imgUri = attachMap[imgName.substringAfterLast("/")]
            }
            notes.add(
                Note(
                    uri = uri, fileName = k.name, title = m.title, preview = m.preview, firstImageUri = imgUri,
                    dateModified = k.modified, sizeBytes = k.size, isPinned = pinned.contains(key)
                )
            )
        }
        if (dirty) saveIndex()

        var result: List<Note> = notes
        if (!query.isNullOrBlank()) {
            val q = query.lowercase()
            result = result.filter { it.title.lowercase().contains(q) || it.preview.lowercase().contains(q) }
        }
        val (pinnedNotes, rest) = result.partition { it.isPinned }
        val sortedRest = when (sortMode) {
            SortMode.NEWEST -> rest.sortedByDescending { it.dateModified }
            SortMode.OLDEST -> rest.sortedBy { it.dateModified }
            SortMode.ALPHABETICAL -> rest.sortedBy { it.title.lowercase() }
            SortMode.SIZE -> rest.sortedByDescending { it.sizeBytes }
            SortMode.MANUAL -> applyManualOrder(folder, rest)
        }
        return pinnedNotes.sortedByDescending { it.dateModified } + sortedRest
    }

    fun listAllNotes(): List<Pair<NoteFolder, Note>> {
        val out = ArrayList<Pair<NoteFolder, Note>>()
        for (f in listFolders()) for (n in listNotes(f, SortMode.NEWEST, null, true)) out.add(f to n)
        return out
    }

    fun readText(uri: Uri): String? = try {
        resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    } catch (e: Exception) {
        null
    }

    fun writeText(uri: Uri, text: String): Boolean = try {
        resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
        true
    } catch (e: Exception) {
        false
    }

    /** Creates a note file. baseName null => "Note <date time>". Returns the note's Uri. */
    fun createNote(folder: NoteFolder, initialText: String, baseName: String? = null): Uri? {
        val name = (baseName?.takeIf { it.isNotBlank() } ?: "Note ${stamp()}") + ".md"
        val created = try {
            DocumentsContract.createDocument(resolver, folder.uri, "text/markdown", name)
        } catch (e: Exception) {
            null
        } ?: return null
        val uri = normalize(created)
        writeText(uri, initialText)
        return uri
    }

    /** A new empty note with a unique "Note <date time>" title. */
    fun newBlankNote(folder: NoteFolder, extraBody: String = ""): Uri? {
        val title = "Note ${stamp()}"
        return createNote(folder, "# $title\n\n$extraBody", title)
    }

    fun duplicateNote(note: Note, folder: NoteFolder): Boolean {
        val text = readText(note.uri) ?: return false
        return createNote(folder, text, note.fileName.removeSuffix(".md") + " copy") != null
    }

    fun deleteUri(uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(resolver, uri)
    } catch (e: Exception) {
        false
    }

    fun deleteNote(note: Note): Boolean = deleteUri(note.uri)

    fun moveNote(note: Note, fromFolder: NoteFolder, toFolder: NoteFolder): Boolean {
        val text = readText(note.uri) ?: return false
        val moved = carryAttachments(text, fromFolder, toFolder)
        if (createNote(toFolder, moved, note.fileName.removeSuffix(".md")) == null) return false
        return deleteNote(note)
    }

    private fun carryAttachments(text: String, from: NoteFolder, to: NoteFolder): String {
        if (!IMAGE_TAG_REGEX.containsMatchIn(text)) return text
        val map = attachmentMap(from)
        var out = text
        for (ref in IMAGE_TAG_REGEX.findAll(text).map { it.groupValues[1] }.distinct()) {
            val clean = ref.substringAfterLast("/")
            val src = map[clean] ?: continue
            val a = importAttachmentFull(to, src, clean.substringBeforeLast(".")) ?: continue
            out = out.replace("![[$ref]]", "![[${a.first}]]")
        }
        return out
    }

    /** Joins notes (in the given order) into one new note. Returns the new note's Uri. */
    fun compileNotes(
        folder: NoteFolder, notes: List<Note>, titleInput: String,
        separator: String, includeTitles: Boolean, deleteOriginals: Boolean
    ): Uri? {
        val title = titleInput.trim().ifBlank { "Compiled ${stamp()}" }
        val parts = ArrayList<String>()
        for (n in notes) {
            val t = readText(n.uri) ?: continue
            val lines = t.lines()
            val first = lines.indexOfFirst { it.isNotBlank() }
            val heading = first >= 0 && lines[first].startsWith("# ")
            val body = (if (heading) lines.drop(first + 1).joinToString("\n") else t).trim()
            val noteTitle = if (heading) lines[first].removePrefix("# ").trim() else n.title
            parts.add(if (includeTitles) "**$noteTitle**\n\n$body".trim() else body)
        }
        if (parts.isEmpty()) return null
        val sep = when (separator) {
            "line" -> "\n\n---\n\n"
            "blank" -> "\n\n"
            else -> "\n"
        }
        val uri = createNote(folder, "# $title\n\n" + parts.joinToString(sep) + "\n", sanitizeFileName(title)) ?: return null
        if (deleteOriginals && parts.size == notes.size) notes.forEach { deleteUri(it.uri) }
        return uri
    }

    // ---------------- Attachments ----------------

    private fun attachmentMapOf(dirDocId: String): Map<String, Uri> =
        children(docUri(dirDocId)).filter { !it.isDir }.associate { it.name to docUri(it.docId) }

    fun attachmentMap(folder: NoteFolder): Map<String, Uri> {
        val dir = children(folder.uri).firstOrNull { isAttachDir(it) } ?: return emptyMap()
        return attachmentMapOf(dir.docId)
    }

    /** Copies an image into the folder's attachments dir. Returns (embed ref, new file Uri). */
    fun importAttachmentFull(folder: NoteFolder, source: Uri, baseName: String): Pair<String, Uri>? {
        return try {
            val existing = children(folder.uri).firstOrNull { isAttachDir(it) }
            val dirUri: Uri
            val dirName: String
            if (existing != null) {
                dirUri = docUri(existing.docId)
                dirName = existing.name
            } else {
                val c = DocumentsContract.createDocument(resolver, folder.uri, DocumentsContract.Document.MIME_TYPE_DIR, ATTACHMENTS_DIR) ?: return null
                dirUri = normalize(c)
                dirName = ATTACHMENTS_DIR
            }
            val ext = (source.lastPathSegment ?: "").substringAfterLast('.', "").lowercase()
            val mime = resolver.getType(source)
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: "image/jpeg"
            val created = DocumentsContract.createDocument(resolver, dirUri, mime, baseName + "." + extFor(mime)) ?: return null
            val target = normalize(created)
            resolver.openInputStream(source)?.use { input ->
                resolver.openOutputStream(target)?.use { output -> input.copyTo(output) }
            }
            val name = displayName(target) ?: (baseName + "." + extFor(mime))
            Pair("$dirName/$name", target)
        } catch (e: Exception) {
            null
        }
    }

    fun importAttachment(folder: NoteFolder, sourceUri: Uri, suggestedName: String): String? =
        importAttachmentFull(folder, sourceUri, suggestedName.substringBeforeLast("."))?.first

    // ---------------- Pin / Manual order ----------------

    private fun pinnedNoteUris(): Set<String> = prefs.getStringSet(KEY_PINNED_NOTES, emptySet()) ?: emptySet()

    fun setNotePinned(note: Note, pinned: Boolean) {
        val set = pinnedNoteUris().toMutableSet()
        if (pinned) set.add(note.uri.toString()) else set.remove(note.uri.toString())
        prefs.edit().putStringSet(KEY_PINNED_NOTES, set).apply()
    }

    private fun manualOrderKey(folder: NoteFolder) = KEY_MANUAL_ORDER_PREFIX + folder.uri.toString()

    private fun applyManualOrder(folder: NoteFolder, notes: List<Note>): List<Note> {
        val orderList = prefs.getString(manualOrderKey(folder), null)?.split("|") ?: emptyList()
        val byUri = notes.associateBy { it.uri.toString() }
        val ordered = orderList.mapNotNull { byUri[it] }.toMutableList()
        ordered.addAll(notes.filter { it.uri.toString() !in orderList })
        return ordered
    }

    fun saveManualOrder(folder: NoteFolder, notes: List<Note>) {
        prefs.edit().putString(manualOrderKey(folder), notes.joinToString("|") { it.uri.toString() }).apply()
    }

    fun moveNoteUp(folder: NoteFolder, notes: List<Note>, note: Note) {
        val list = notes.toMutableList()
        val i = list.indexOfFirst { it.uri == note.uri }
        if (i > 0) {
            list.removeAt(i)
            list.add(i - 1, note)
        }
        saveManualOrder(folder, list)
    }

    fun moveNoteDown(folder: NoteFolder, notes: List<Note>, note: Note) {
        val list = notes.toMutableList()
        val i = list.indexOfFirst { it.uri == note.uri }
        if (i in 0 until list.size - 1) {
            list.removeAt(i)
            list.add(i + 1, note)
        }
        saveManualOrder(folder, list)
    }

    // ---------------- Block-based body parsing (for the editor) ----------------

    fun parseBlocks(text: String, folder: NoteFolder): List<EditorBlock> {
        val map = if (IMAGE_TAG_REGEX.containsMatchIn(text)) attachmentMap(folder) else emptyMap()
        val blocks = mutableListOf<EditorBlock>()
        var lastIndex = 0
        for (match in IMAGE_TAG_REGEX.findAll(text)) {
            val before = text.substring(lastIndex, match.range.first)
            if (before.isNotEmpty()) blocks.add(EditorBlock.Text(before))
            val name = match.groupValues[1]
            blocks.add(EditorBlock.Image(name, map[name.substringAfterLast("/")]))
            lastIndex = match.range.last + 1
        }
        val tail = text.substring(lastIndex)
        if (tail.isNotEmpty() || blocks.isEmpty()) blocks.add(EditorBlock.Text(tail))
        return blocks
    }

    fun serializeBlocks(blocks: List<EditorBlock>): String = buildString {
        for (block in blocks) {
            when (block) {
                is EditorBlock.Text -> append(block.content)
                is EditorBlock.Image -> append("![[${block.fileName}]]")
            }
        }
    }
}
