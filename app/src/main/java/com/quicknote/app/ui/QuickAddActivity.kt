package com.quicknote.app.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.quicknote.app.databinding.ActivityQuickAddBinding
import com.quicknote.app.databinding.PopupPinnedBinding
import com.quicknote.app.model.Note
import com.quicknote.app.model.NoteFolder
import com.quicknote.app.overlay.FloatService
import com.quicknote.app.repo.VaultRepository
import com.quicknote.app.util.AppScope
import com.quicknote.app.util.DraftStore
import com.quicknote.app.util.NoteEvents
import com.quicknote.app.util.QuickSaver
import com.quicknote.app.util.ThumbStrip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quick Add popup. Header: minimise-to-bubble (left), folder chip (centre), pinned/search (right).
 * Everything typed is kept in a draft, so nothing is lost if the screen closes.
 */
class QuickAddActivity : AppCompatActivity() {

    private lateinit var b: ActivityQuickAddBinding
    private lateinit var repo: VaultRepository
    private var folders: List<NoteFolder> = emptyList()
    private var busy = false
    private var binding = false
    private var pinnedWindow: PopupWindow? = null
    private var allNotes: List<Pair<NoteFolder, Note>>? = null

    private val pick = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        // Copy straight away so the image is never lost, even if this screen is recreated.
        if (uris.isNotEmpty()) QuickSaver.copyPicked(applicationContext, uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityQuickAddBinding.inflate(layoutInflater)
        setContentView(b.root)
        repo = VaultRepository(applicationContext)

        if (!repo.hasVault()) {
            Toast.makeText(this, "Open QuickNote once and choose your vault folder first.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        DraftStore.ensure(this)
        if (FloatService.running) FloatService.hide(this)
        bindDraft()

        b.editQuickTitle.addTextChangedListener(SimpleWatcher { if (!binding) { DraftStore.draft.title = it; DraftStore.save(this) } })
        b.editQuickBody.addTextChangedListener(SimpleWatcher { if (!binding) { DraftStore.draft.body = it; DraftStore.save(this) } })

        b.btnAddImage.setOnClickListener { pick.launch("image/*") }
        b.btnCancel.setOnClickListener {
            QuickSaver.cleanup(DraftStore.draft.images)
            DraftStore.clear(this)
            finish()
        }
        b.btnSave.setOnClickListener { doSave(false) }
        b.btnMinimize.setOnClickListener { startFloating() }
        b.btnFolder.setOnClickListener { showFolderMenu() }
        b.btnPin.setOnClickListener { showPinnedPopup() }
        b.txtOpenFull.setOnClickListener { doSave(true) }

        lifecycleScope.launch { folders = withContext(Dispatchers.IO) { repo.listFolders() } }
    }

    override fun onStart() {
        super.onStart()
        DraftStore.setListener(this) { refreshThumbs() }
        refreshThumbs()
    }

    override fun onStop() {
        super.onStop()
        DraftStore.clearListener(this)
        DraftStore.save(this)
    }

    override fun onDestroy() {
        pinnedWindow?.dismiss()
        super.onDestroy()
    }

    private fun bindDraft() {
        val d = DraftStore.draft
        binding = true
        if (b.editQuickTitle.text.toString() != d.title) b.editQuickTitle.setText(d.title)
        if (b.editQuickBody.text.toString() != d.body) b.editQuickBody.setText(d.body)
        binding = false
        val editing = d.editNoteUri != null
        b.layoutEditing.visibility = if (editing) android.view.View.VISIBLE else android.view.View.GONE
        b.btnSave.text = getString(if (editing) com.quicknote.app.R.string.update else com.quicknote.app.R.string.save)
        b.txtFolder.text = d.folderName
        b.btnFolder.isEnabled = !editing
        b.btnFolder.alpha = if (editing) 0.7f else 1f
        refreshThumbs()
    }

    private fun refreshThumbs() {
        val d = DraftStore.draft
        b.scrollThumbs.visibility = if (d.images.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        ThumbStrip.render(b.thumbRow, d.images, 52) { i ->
            val path = d.images.removeAt(i)
            QuickSaver.cleanup(listOf(path))
            DraftStore.save(this)
            refreshThumbs()
        }
    }

    // ---------------- folder chip ----------------

    private fun showFolderMenu() {
        if (folders.isEmpty()) return
        val pm = PopupMenu(this, b.btnFolder, Gravity.CENTER_HORIZONTAL)
        folders.forEachIndexed { i, f -> pm.menu.add(Menu.NONE, i, i, f.name) }
        pm.setOnMenuItemClickListener { item ->
            val f = folders[item.itemId]
            DraftStore.draft.folderUri = f.uri.toString()
            DraftStore.draft.folderName = f.name
            DraftStore.save(this)
            b.txtFolder.text = f.name
            true
        }
        pm.show()
    }

    // ---------------- pinned / search card (option B) ----------------

    private fun showPinnedPopup() {
        pinnedWindow?.let { if (it.isShowing) { it.dismiss(); return } }
        val pb = PopupPinnedBinding.inflate(LayoutInflater.from(this))
        val adapter = PinnedAdapter { folder, note ->
            pinnedWindow?.dismiss()
            loadForEdit(note, folder)
        }
        pb.popList.layoutManager = LinearLayoutManager(this)
        pb.popList.adapter = adapter

        val d = resources.displayMetrics.density
        val w = (b.card.width * 0.8f).toInt()
        val pw = PopupWindow(pb.root, w, (240 * d).toInt(), true)
        pw.elevation = 14f * d
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.isOutsideTouchable = true
        pw.inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
        pb.popClose.setOnClickListener { pw.dismiss() }

        fun render() {
            val all = allNotes
            if (all == null) {
                pb.popLabel.text = "Loading..."
                return
            }
            val q = pb.popSearch.text.toString().trim().lowercase()
            if (q.isEmpty()) {
                pb.popLabel.text = "Pinned"
                adapter.submit(all.filter { it.second.isPinned }, false)
            } else {
                pb.popLabel.text = "Results, all folders"
                adapter.submit(all.filter { it.second.title.lowercase().contains(q) }, true)
            }
        }
        pb.popSearch.addTextChangedListener(SimpleWatcher { render() })
        pinnedWindow = pw
        pw.showAsDropDown(b.btnPin, b.btnPin.width - w, (4 * d).toInt())
        render()
        if (allNotes == null) {
            lifecycleScope.launch {
                allNotes = withContext(Dispatchers.IO) { repo.listAllNotes() }
                render()
            }
        }
    }

    private fun loadForEdit(note: Note, folder: NoteFolder) {
        lifecycleScope.launch {
            val d = withContext(Dispatchers.IO) { QuickSaver.loadForEdit(repo, note, folder) }
            if (d == null) {
                Toast.makeText(this@QuickAddActivity, "Couldn't open that note", Toast.LENGTH_SHORT).show()
                return@launch
            }
            // keep any image the user already added to the current draft
            d.images = DraftStore.draft.images
            DraftStore.replace(this@QuickAddActivity, d)
            bindDraft()
        }
    }

    // ---------------- save ----------------

    private fun doSave(thenOpenEditor: Boolean) {
        if (busy) return
        val d = DraftStore.draft.copyDeep()
        if (d.isBlank()) {
            finish()
            return
        }
        busy = true
        b.btnSave.isEnabled = false
        val app = applicationContext
        DraftStore.clear(app)
        AppScope.scope.launch {
            val r = try { QuickSaver.save(app, d) } catch (e: Exception) { null }
            withContext(Dispatchers.Main) {
                if (r != null) {
                    QuickSaver.cleanup(d.images)
                    NoteEvents.notifyChanged()
                    Toast.makeText(app, if (d.editNoteUri != null) "Updated" else "Saved to ${r.folderName}", Toast.LENGTH_SHORT).show()
                    if (thenOpenEditor && !isFinishing) {
                        val i = Intent(this@QuickAddActivity, NoteEditorActivity::class.java)
                        i.putExtra("noteUri", r.noteUri.toString())
                        i.putExtra("folderUri", d.editFolderUri)
                        i.putExtra("folderName", d.folderName)
                        startActivity(i)
                        finish()
                    }
                } else {
                    DraftStore.replace(app, d)
                    Toast.makeText(app, "Couldn't save. Your draft is kept.", Toast.LENGTH_LONG).show()
                    busy = false
                    b.btnSave.isEnabled = true
                }
            }
        }
        if (!thenOpenEditor) finish()
    }

    // ---------------- floating bubble ----------------

    private fun startFloating() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow “Display over other apps” for QuickNote, then tap minimise again.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        DraftStore.save(this)
        FloatService.show(this)
        finish()
    }
}
