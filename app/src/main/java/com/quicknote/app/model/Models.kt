package com.quicknote.app.model

import android.net.Uri

/** A folder is a subdirectory directly under the vault root (or the root itself for "QuickNotes"). */
data class NoteFolder(
    val uri: Uri,
    val name: String,
    val isPinned: Boolean = false
)

/** A single .md note file inside a folder. */
data class Note(
    val uri: Uri,
    val fileName: String,
    val title: String,
    val preview: String,
    val firstImageUri: Uri?,
    val dateModified: Long,
    val sizeBytes: Long,
    val isPinned: Boolean = false
)

/** One block of a note's body: either a text chunk or an embedded image reference. */
sealed class EditorBlock {
    data class Text(var content: String) : EditorBlock()
    data class Image(val fileName: String, var uri: Uri?) : EditorBlock()
}

enum class SortMode { NEWEST, OLDEST, ALPHABETICAL, SIZE, MANUAL }
