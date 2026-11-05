package com.quicknote.app.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayoutMediator
import com.quicknote.app.R
import com.quicknote.app.databinding.ActivityMainBinding
import com.quicknote.app.model.NoteFolder
import com.quicknote.app.model.SortMode
import com.quicknote.app.repo.VaultRepository
import com.quicknote.app.util.NoteEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: VaultRepository
    private var folders: List<NoteFolder> = emptyList()
    var currentSort: SortMode = SortMode.NEWEST
    private var selecting = false
    private val tileViews = mutableMapOf<String, View>()

    private val pickVaultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri: Uri? = result.data?.data
        if (uri != null) {
            repo.setVaultUri(uri)
            reload()
        }
    }

    private val newImageUriHolder = arrayOfNulls<Uri>(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar.root)
        supportActionBar?.title = "QuickNotes"

        repo = VaultRepository(applicationContext)
        binding.fab.setOnClickListener { showAddMenu() }
        buildSelectionBar()

        binding.editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentFragment()?.applyFilter(s?.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        if (!repo.hasVault()) requestVaultAccess() else reload()
    }

    override fun onStart() {
        super.onStart()
        NoteEvents.listener = { currentFragment()?.reload() }
    }

    override fun onStop() {
        super.onStop()
        NoteEvents.listener = null
    }

    override fun onResume() {
        super.onResume()
        // Silent background refresh: never blocks the screen, so coming back from a note is instant.
        if (repo.hasVault() && folders.isNotEmpty()) currentFragment()?.reload()
    }

    private fun requestVaultAccess() {
        AlertDialog.Builder(this)
            .setTitle("Choose Obsidian vault folder")
            .setMessage("Pick the root folder of your Obsidian vault (or the QuickNotes folder inside it). New notes will be written there as markdown files.")
            .setCancelable(false)
            .setPositiveButton("Choose") { _, _ -> pickVaultLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)) }
            .show()
    }

    private fun reload() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { repo.listFolders() }
            folders = list
            registeredFragments.clear()
            binding.viewPager.adapter = FolderPagerAdapter(this@MainActivity, list)
            TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
                tab.text = list[position].name
            }.attach()
        }
    }

    private val registeredFragments = mutableMapOf<Int, NotesListFragment>()

    fun registerFragment(position: Int, fragment: NotesListFragment) {
        registeredFragments[position] = fragment
    }

    private fun currentFragment(): NotesListFragment? = registeredFragments[binding.viewPager.currentItem]

    fun currentFolder(): NoteFolder? = folders.getOrNull(binding.viewPager.currentItem)

    // ---------------- multi-select bar ----------------

    private fun buildSelectionBar() {
        val acts = listOf(
            Triple("all", R.drawable.ic_select_all, "Select all"),
            Triple("compile", R.drawable.ic_compile, "Compile"),
            Triple("pin", R.drawable.ic_pin, "Pin"),
            Triple("copy", R.drawable.ic_copy, "Copy"),
            Triple("share", R.drawable.ic_share, "Share"),
            Triple("dup", R.drawable.ic_duplicate, "Duplicate"),
            Triple("move", R.drawable.ic_folder, "Move to..."),
            Triple("delete", R.drawable.ic_delete, "Delete"),
            Triple("up", R.drawable.ic_up, "Move up"),
            Triple("down", R.drawable.ic_down, "Move down")
        )
        acts.forEachIndexed { i, (id, icon, label) ->
            val row = if (i < 5) binding.barRow1 else binding.barRow2
            val v = layoutInflater.inflate(R.layout.item_bar_tile, row, false)
            v.findViewById<ImageView>(R.id.tileIcon).setImageResource(icon)
            v.findViewById<TextView>(R.id.tileLabel).text = label
            v.setOnClickListener { onBarAction(id) }
            row.addView(v)
            tileViews[id] = v
        }
    }

    private fun onBarAction(id: String) {
        val f = currentFragment() ?: return
        when (id) {
            "all" -> f.selectAll()
            "compile" -> f.compileSelected()
            "pin" -> f.pinSelected()
            "copy" -> f.copySelected()
            "share" -> f.shareSelected()
            "dup" -> f.duplicateSelected()
            "move" -> f.moveSelected()
            "delete" -> f.deleteSelected()
            "up" -> f.moveSelectedUpDown(true)
            "down" -> f.moveSelectedUpDown(false)
        }
    }

    fun onSelectionChanged(count: Int) {
        selecting = count > 0
        binding.selectionBar.visibility = if (selecting) View.VISIBLE else View.GONE
        binding.fab.visibility = if (selecting) View.GONE else View.VISIBLE
        binding.viewPager.isUserInputEnabled = !selecting
        supportActionBar?.setDisplayHomeAsUpEnabled(selecting)
        if (selecting) supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_back)
        supportActionBar?.title = if (selecting) count.toString() else "QuickNotes"
        val single = if (count == 1) 1f else 0.35f
        tileViews["up"]?.alpha = single
        tileViews["down"]?.alpha = single
        tileViews["compile"]?.alpha = if (count >= 2) 1f else 0.5f
        invalidateOptionsMenu()
    }

    override fun onSupportNavigateUp(): Boolean {
        if (selecting) {
            currentFragment()?.exitSelection()
            return true
        }
        return super.onSupportNavigateUp()
    }

    override fun onBackPressed() {
        if (selecting) currentFragment()?.exitSelection() else super.onBackPressed()
    }

    // ---------------- add menu (new note / camera / gallery) ----------------

    private fun showAddMenu() {
        val folder = currentFolder() ?: return
        AlertDialog.Builder(this)
            .setItems(arrayOf("New Note", "Camera", "Gallery")) { _, which ->
                when (which) {
                    0 -> lifecycleScope.launch {
                        val uri = withContext(Dispatchers.IO) { repo.newBlankNote(folder) }
                        if (uri != null) openEditor(uri, folder)
                    }
                    1 -> launchCamera()
                    2 -> galleryLauncher.launch("image/*")
                }
            }.show()
    }

    private fun newNoteWithImage(folder: NoteFolder, source: Uri) {
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                val ref = repo.importAttachment(folder, source, "img_${System.currentTimeMillis()}.jpg")
                repo.newBlankNote(folder, if (ref != null) "![[$ref]]\n\n" else "")
            }
            if (uri != null) openEditor(uri, folder)
        }
    }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val folder = currentFolder()
        if (uri != null && folder != null) newNoteWithImage(folder, uri)
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = newImageUriHolder[0]
        val folder = currentFolder()
        if (success && uri != null && folder != null) newNoteWithImage(folder, uri)
    }

    private fun launchCamera() {
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "quicknote_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        newImageUriHolder[0] = uri
        if (uri != null) cameraLauncher.launch(uri)
    }

    private fun openEditor(noteUri: Uri, folder: NoteFolder) {
        val i = Intent(this, NoteEditorActivity::class.java)
        i.putExtra("noteUri", noteUri.toString())
        i.putExtra("folderUri", folder.uri.toString())
        i.putExtra("folderName", folder.name)
        startActivity(i)
    }

    // ---------------- toolbar menu ----------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        for (i in 0 until menu.size()) menu.getItem(i).isVisible = !selecting
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_search -> {
                binding.searchBar.visibility = if (binding.searchBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                true
            }
            R.id.action_new_folder -> {
                val input = EditText(this)
                AlertDialog.Builder(this)
                    .setTitle("New folder")
                    .setView(input)
                    .setPositiveButton("Create") { _, _ ->
                        val name = input.text.toString().trim()
                        if (name.isNotEmpty()) {
                            lifecycleScope.launch {
                                withContext(Dispatchers.IO) { repo.createFolder(name) }
                                reload()
                            }
                        }
                    }.setNegativeButton("Cancel", null).show()
                true
            }
            R.id.action_sort -> {
                val modes = arrayOf("Newest", "Oldest", "Alphabetical", "File size", "Manual (drag)")
                AlertDialog.Builder(this)
                    .setTitle("Sort by")
                    .setItems(modes) { _, which ->
                        currentSort = when (which) {
                            0 -> SortMode.NEWEST
                            1 -> SortMode.OLDEST
                            2 -> SortMode.ALPHABETICAL
                            3 -> SortMode.SIZE
                            else -> SortMode.MANUAL
                        }
                        currentFragment()?.setSortMode(currentSort)
                    }.show()
                true
            }
            R.id.action_settings -> {
                requestVaultAccess()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
