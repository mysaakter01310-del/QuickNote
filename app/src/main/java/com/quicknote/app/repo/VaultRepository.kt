package com.quicknote.app.repo

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
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
private const val KEY_OLD_VAULT_URI = "vault_uri"
private const val KEY_ROOT_PATH = "root_path"
private const val KEY_EXTRA_FOLDERS = "extra_folders"
private const val KEY_PINNED_NOTES = "pinned_notes_v2"
private const val KEY_MANUAL_ORDER_PREFIX = "manual_order_v2_"
private const val INBOX_FOLDER_NAME = "QuickNotes"
private val IGNORED_DIRS = setOf(".obsidian", ".trash", ".git", ".thumbnails")
private val IMAGE_TAG_REGEX = Regex("""!\[\[([^\]]+)]]""")
private val BAD_NAME_CHARS = Regex("[\\\\/:*?\"<>|#^\\[\\]]")

private class Meta(val preview: String, val img: String?, val modified: Long, val size: Long)

private val noteIndex = ConcurrentHashMap<String, Meta>()
@Volatile private var indexLoaded = false

/**
 * Notes are plain .md files read and written directly through normal file paths
 * (needs Android's "All files access"). That is much faster than the folder-picker system,
 * and lets each tab be any folder anywhere. Call from a background thread.
 *
 * The file name IS the note title. Nothing else is written into the note body.
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

        fun storageRoot(): File = Environment.getExternalStorageDirectory()

        fun hasAllFilesAccess(): Boolean =
            if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true

        fun fileOf(uri: Uri): File = File(uri.path ?: "")
        fun uriOf(file: File): Uri = Uri.fromFile(file)
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------- default folder + extra folders ----------------

    /** Default folder path. Migrates the old folder-picker choice (primary:Documents/...) once. */
    fun rootPath(): String? {
        prefs.getString(KEY_ROOT_PATH, null)?.let { return it }
        val old = prefs.getString(KEY_OLD_VAULT_URI, null) ?: return null
        return try {
            val id = DocumentsContract.getTreeDocumentId(Uri.parse(old))
            if (id.startsWith("primary:")) {
                val p = File(storageRoot(), id.removePrefix("primary:")).absolutePath
                prefs.edit().putString(KEY_ROOT_PATH, p).apply()
                p
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun setRootPath(path: String) {
        prefs.edit().putString(KEY_ROOT_PATH, path).apply()
    }

    fun hasVault(): Boolean {
        val p = rootPath() ?: return false
        return hasAllFilesAccess() && File(p).isDirectory
    }

    fun extraFolderPaths(): List<String> =
        (prefs.getString(KEY_EXTRA_FOLDERS, "") ?: "").split("|").filter { it.isNotBlank() }

    fun addExtraFolder(path: String) {
        val list = extraFolderPaths().toMutableList()
        if (path !in list && path != rootPath()) list.add(path)
        prefs.edit().putString(KEY_EXTRA_FOLDERS, list.joinToString("|")).apply()
    }

    fun removeExtraFolder(path: String) {
        val list = extraFolderPaths().filter { it != path }
        prefs.edit().putString(KEY_EXTRA_FOLDERS, list.joinToString("|")).apply()
    }

    fun isExtraFolder(folder: NoteFolder) = fileOf(folder.uri).absolutePath in extraFolderPaths()

    fun rootFolder(): NoteFolder? = rootPath()?.let { NoteFolder(uriOf(File(it)), INBOX_FOLDER_NAME, true) }

    /** Creates parent/name and returns it (null if it already exists or fails). */
    fun createFolderAt(parentPath: String, name: String): File? {
        val clean = sanitizeFileName(name)
        val dir = File(parentPath, clean)
        if (dir.exists()) return dir
        return if (dir.mkdirs()) dir else null
    }

    fun sanitizeFileName(s: String): String =
        s.replace(BAD_NAME_CHARS, "-").trim().take(80).trim('.', ' ').ifBlank { "Note ${stamp()}" }

    private fun uniqueFile(dir: File, base: String, ext: String): File {
        var f = File(dir, "$base.$ext")
        var i = 1
        while (f.exists()) {
            f = File(dir, "$base ($i).$ext")
            i++
        }
        return f
    }

    // ---------------- index cache ----------------

    private fun ensureIndex() {
        if (indexLoaded) return
        synchronized(noteIndex) {
            if (indexLoaded) return
            try {
                val f = File(context.filesDir, "note_index_v2.json")
                if (f.exists()) {
                    val arr = JSONArray(f.readText())
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        noteIndex[o.getString("k")] = Meta(o.getString("p"), o.optString("i").ifEmpty { null }, o.getLong("m"), o.getLong("s"))
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
                arr.put(JSONObject().put("k", k).put("p", v.preview).put("i", v.img ?: "").put("m", v.modified).put("s", v.size))
            }
            File(context.filesDir, "note_index_v2.json").writeText(arr.toString())
        } catch (e: Exception) {
        }
    }

    private fun readHead(file: File, max: Int): String = try {
        file.inputStream().use { s ->
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
        ""
    }

    private fun readMeta(file: File): Meta {
        val head = readHead(file, 8192)
        var lines = head.lines()
        val first = lines.indexOfFirst { it.isNotBlank() }
        // old notes may start with "# heading": the file name is the title now, so skip that line in the preview
        if (first >= 0 && lines[first].startsWith("# ")) lines = lines.drop(first + 1)
        val preview = lines.joinToString(" ").replace(IMAGE_TAG_REGEX, "").trim().take(140)
        val img = IMAGE_TAG_REGEX.find(head)?.groupValues?.get(1)
        return Meta(preview, img, file.lastModified(), file.length())
    }

    // ---------------- Folders ----------------

    /** Default folder, its subfolders (hidden ones too), and any extra folders you added. */
    fun listFolders(): List<NoteFolder> {
        val rootP = rootPath() ?: return emptyList()
        val root = File(rootP)
        val result = ArrayList<NoteFolder>()
        result.add(NoteFolder(uriOf(root), INBOX_FOLDER_NAME, true))
        val seen = hashSetOf(root.absolutePath)
        val subs = (root.listFiles { f -> f.isDirectory && f.name !in IGNORED_DIRS && f.name != "attachments" } ?: emptyArray())
            .sortedBy { it.name.lowercase() }
        for (d in subs) if (seen.add(d.absolutePath)) result.add(NoteFolder(uriOf(d), d.name))
        for (p in extraFolderPaths()) {
            val d = File(p)
            if (d.isDirectory && seen.add(d.absolutePath)) result.add(NoteFolder(uriOf(d), d.name))
        }
        return result
    }

    // ---------------- Notes ----------------

    private fun attachDir(folderDir: File): File? =
        listOf(".attachments", "attachments").map { File(folderDir, it) }.firstOrNull { it.isDirectory }

    fun listNotes(folder: NoteFolder, sortMode: SortMode, query: String? = null, hydrate: Boolean = true): List<Note> {
        ensureIndex()
        val dir = fileOf(folder.uri)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".md", true) } ?: emptyArray()
        val pinned = pinnedNoteUris()
        val attach = if (files.isNotEmpty()) attachDir(dir) else null
        var dirty = false
        val notes = ArrayList<Note>(files.size)
        for (f in files) {
            val key = uriOf(f).toString()
            var meta = noteIndex[key]
            val mod = f.lastModified()
            val len = f.length()
            if (meta == null || meta.modified != mod || meta.size != len) {
                meta = readMeta(f)
                noteIndex[key] = meta
                dirty = true
            }
            var imgUri: Uri? = null
            val imgName = meta.img
            if (imgName != null && attach != null) {
                val img = File(attach, imgName.substringAfterLast("/"))
                if (img.exists()) imgUri = uriOf(img)
            }
            notes.add(
                Note(
                    uri = uriOf(f), fileName = f.name, title = f.name.removeSuffix(".md").removeSuffix(".MD"),
                    preview = meta.preview, firstImageUri = imgUri, dateModified = mod, sizeBytes = len,
                    isPinned = pinned.contains(key)
                )
            )
        }
        if (dirty) saveIndex()
        var result: List<Note> = notes
        if (!query.isNullOrBlank()) {
            val q = query.lowercase()
            result = result.filter { it.title.lowercase().contains(q) || it.preview.lowercase().contains(q) }
        }
        return arrange(result, folder, sortMode)
    }

    /** Pure ordering (no file access): pinned first, then the chosen sort. Also used for instant UI updates. */
    fun arrange(notes: List<Note>, folder: NoteFolder, sortMode: SortMode): List<Note> {
        val (pinnedNotes, rest) = notes.partition { it.isPinned }
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
        fileOf(uri).readText()
    } catch (e: Exception) {
        null
    }

    fun writeText(uri: Uri, text: String): Boolean = try {
        fileOf(uri).writeText(text)
        true
    } catch (e: Exception) {
        false
    }

    /** Creates "<baseName>.md" (or "Note <date time>.md"). The title is the file name; the body is just the text. */
    fun createNote(folder: NoteFolder, text: String, baseName: String? = null): Uri? = try {
        val dir = fileOf(folder.uri)
        dir.mkdirs()
        val f = uniqueFile(dir, baseName?.takeIf { it.isNotBlank() }?.let { sanitizeFileName(it) } ?: "Note ${stamp()}", "md")
        f.writeText(text)
        uriOf(f)
    } catch (e: Exception) {
        null
    }

    fun newBlankNote(folder: NoteFolder, body: String = ""): Uri? = createNote(folder, body, null)

    /** Renames a note's file (its title). Returns the new Uri, or the old one if nothing changed. */
    fun renameNote(uri: Uri, newTitle: String): Uri? = try {
        val f = fileOf(uri)
        val clean = sanitizeFileName(newTitle)
        if (clean == f.name.removeSuffix(".md")) uri
        else {
            val target = uniqueFile(f.parentFile!!, clean, "md")
            if (f.renameTo(target)) {
                val pinned = pinnedNoteUris().toMutableSet()
                if (pinned.remove(uri.toString())) {
                    pinned.add(uriOf(target).toString())
                    prefs.edit().putStringSet(KEY_PINNED_NOTES, pinned).apply()
                }
                uriOf(target)
            } else null
        }
    } catch (e: Exception) {
        null
    }

    fun duplicateNote(note: Note, folder: NoteFolder): Boolean {
        val text = readText(note.uri) ?: return false
        return createNote(folder, text, note.title + " copy") != null
    }

    fun deleteUri(uri: Uri): Boolean = try {
        fileOf(uri).delete()
    } catch (e: Exception) {
        false
    }

    fun deleteNote(note: Note): Boolean = deleteUri(note.uri)

    fun moveNote(note: Note, fromFolder: NoteFolder, toFolder: NoteFolder): Boolean {
        val src = fileOf(note.uri)
        val toDir = fileOf(toFolder.uri)
        val text = readText(note.uri) ?: return false
        val carried = carryAttachments(text, fromFolder, toFolder)
        val target = uniqueFile(toDir, note.title, "md")
        return try {
            target.writeText(carried)
            target.setLastModified(src.lastModified())
            src.delete()
        } catch (e: Exception) {
            false
        }
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

    /** Old notes may begin with "# heading"; the file name is the title, so that line is dropped when joining. */
    private fun bodyOf(text: String): String {
        val lines = text.lines()
        val first = lines.indexOfFirst { it.isNotBlank() }
        return (if (first >= 0 && lines[first].startsWith("# ")) lines.drop(first + 1).joinToString("\n") else text).trim()
    }

    fun compileNotes(
        folder: NoteFolder, notes: List<Note>, titleInput: String,
        separator: String, includeTitles: Boolean, deleteOriginals: Boolean
    ): Uri? {
        val title = titleInput.trim().ifBlank { "Compiled ${stamp()}" }
        val parts = ArrayList<String>()
        for (n in notes) {
            val t = readText(n.uri) ?: continue
            val body = bodyOf(t)
            parts.add(if (includeTitles) "**${n.title}**\n\n$body".trim() else body)
        }
        if (parts.isEmpty()) return null
        val sep = when (separator) {
            "line" -> "\n\n---\n\n"
            "blank" -> "\n\n"
            else -> "\n"
        }
        val uri = createNote(folder, parts.joinToString(sep) + "\n", title) ?: return null
        if (deleteOriginals && parts.size == notes.size) notes.forEach { deleteUri(it.uri) }
        return uri
    }

    // ---------------- Attachments ----------------

    fun attachmentMap(folder: NoteFolder): Map<String, Uri> {
        val dir = attachDir(fileOf(folder.uri)) ?: return emptyMap()
        return (dir.listFiles { f -> f.isFile } ?: emptyArray()).associate { it.name to uriOf(it) }
    }

    /** Copies an image into the folder's attachments dir. Returns (embed ref, new file Uri). */
    fun importAttachmentFull(folder: NoteFolder, source: Uri, baseName: String): Pair<String, Uri>? {
        return try {
            val folderDir = fileOf(folder.uri)
            val dir = attachDir(folderDir) ?: File(folderDir, "attachments").apply { mkdirs() }
            val ext = (source.lastPathSegment ?: "").substringAfterLast('.', "").lowercase()
            val mime = context.contentResolver.getType(source)
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: "image/jpeg"
            val target = uniqueFile(dir, baseName, extFor(mime))
            context.contentResolver.openInputStream(source)?.use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            } ?: return null
            Pair("${dir.name}/${target.name}", uriOf(target))
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- Pin / Manual order ----------------

    private fun pinnedNoteUris(): Set<String> = prefs.getStringSet(KEY_PINNED_NOTES, emptySet()) ?: emptySet()

    fun setNotePinned(note: Note, pinned: Boolean) = setNotesPinned(listOf(note), pinned)

    fun setNotesPinned(notes: List<Note>, pinned: Boolean) {
        val set = pinnedNoteUris().toMutableSet()
        for (n in notes) if (pinned) set.add(n.uri.toString()) else set.remove(n.uri.toString())
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

    /** Returns the new order (instant, no file access). */
    fun moveInList(notes: List<Note>, note: Note, up: Boolean): List<Note> {
        val list = notes.toMutableList()
        val i = list.indexOfFirst { it.uri == note.uri }
        if (i < 0) return notes
        val j = if (up) i - 1 else i + 1
        if (j < 0 || j >= list.size) return notes
        list.removeAt(i)
        list.add(j, note)
        return list
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
