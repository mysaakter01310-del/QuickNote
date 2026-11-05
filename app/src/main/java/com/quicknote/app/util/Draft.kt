package com.quicknote.app.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/** The unsaved Quick Add note. Survives screen kills, minimising to the bubble, and gallery trips. */
data class Draft(
    var title: String = "",
    var body: String = "",
    var folderUri: String? = null,
    var folderName: String = "QuickNotes",
    var images: MutableList<String> = mutableListOf(),
    var editNoteUri: String? = null,
    var editFolderUri: String? = null,
    var editHasHeading: Boolean = true
) {
    fun isBlank() = title.isBlank() && body.isBlank() && images.isEmpty() && editNoteUri == null
    fun copyDeep() = copy(images = images.toMutableList())
}

object DraftStore {
    private const val PREFS = "quicknote_draft"
    private val main = Handler(Looper.getMainLooper())
    @Volatile var draft = Draft()
    private var loaded = false
    private var owner: Any? = null
    private var callback: (() -> Unit)? = null

    @Synchronized
    fun ensure(ctx: Context) {
        if (loaded) return
        loaded = true
        val s = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("d", null) ?: return
        try {
            val o = JSONObject(s)
            val imgs = mutableListOf<String>()
            val arr = o.optJSONArray("images") ?: JSONArray()
            for (i in 0 until arr.length()) imgs.add(arr.getString(i))
            draft = Draft(
                title = o.optString("title"),
                body = o.optString("body"),
                folderUri = o.optString("folderUri").ifEmpty { null },
                folderName = o.optString("folderName").ifEmpty { "QuickNotes" },
                images = imgs,
                editNoteUri = o.optString("editNoteUri").ifEmpty { null },
                editFolderUri = o.optString("editFolderUri").ifEmpty { null },
                editHasHeading = o.optBoolean("editHasHeading", true)
            )
        } catch (e: Exception) {
        }
    }

    fun save(ctx: Context) {
        val d = draft
        val o = JSONObject()
        o.put("title", d.title)
        o.put("body", d.body)
        o.put("folderUri", d.folderUri ?: "")
        o.put("folderName", d.folderName)
        o.put("images", JSONArray(d.images.toList()))
        o.put("editNoteUri", d.editNoteUri ?: "")
        o.put("editFolderUri", d.editFolderUri ?: "")
        o.put("editHasHeading", d.editHasHeading)
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("d", o.toString()).apply()
    }

    fun replace(ctx: Context, d: Draft) {
        loaded = true
        draft = d
        save(ctx)
    }

    fun clear(ctx: Context) = replace(ctx, Draft())

    fun setListener(o: Any, cb: () -> Unit) {
        owner = o
        callback = cb
    }

    fun clearListener(o: Any) {
        if (owner === o) {
            owner = null
            callback = null
        }
    }

    fun notifyChanged() {
        main.post { callback?.invoke() }
    }
}
