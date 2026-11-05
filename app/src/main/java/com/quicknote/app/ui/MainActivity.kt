package com.quicknote.app.ui

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.StatusBarManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.quicknote.app.R
import com.quicknote.app.databinding.ActivityMainBinding
import com.quicknote.app.model.NoteFolder
import com.quicknote.app.model.SortMode
import com.quicknote.app.repo.VaultRepository
import com.quicknote.app.tile.QuickTileService
import com.quicknote.app.util.NoteEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: VaultRepository
    private var folders: List<NoteFolder> = emptyList()
    var currentSort: SortMode = SortMode.NEWEST
    private var selecting = false
    private var askedSetup = false
    private val tileViews = mutableMapOf<String, View>()
    private val registeredFragments = mutableMapOf<Int, NotesListFragment>()

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val folder = currentFolder()
        if (uri != null && folder != null) newNoteWithImage(folder, uri)
    }

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
        if (repo.hasVault()) {
            if (folders.isEmpty()) reload() else currentFragment()?.reload()
        } else if (!askedSetup) {
            askedSetup = true
            if (!VaultRepository.hasAllFilesAccess()) askAllFilesAccess() else if (repo.rootPath() == null) requestRootFolder()
        }
    }

    // ---------------- first-run setup ----------------

    private fun askAllFilesAccess() {
        AlertDialog.Builder(this)
            .setTitle("Allow file access")
            .setMessage("QuickNote reads and writes your notes as normal files, so it can use any folder you choose (for example in Obsidian). On the next screen, turn on “Allow access to manage all files” for QuickNote, then come back.")
            .setPositiveButton("Open settings") { _, _ ->
                askedSetup = false
                if (Build.VERSION.SDK_INT >= 30) {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestRootFolder() {
        AlertDialog.Builder(this)
            .setTitle("Choose your default notes folder")
            .setMessage("This is where QuickNote saves new notes, as .md files. Pick the folder you use in Obsidian, for example Documents/Obsidian/QuickAdd. Its subfolders show up as tabs, and you can add folders from anywhere else later.")
            .setPositiveButton("Choose folder") { _, _ ->
                val start = repo.rootPath() ?: File(VaultRepository.storageRoot(), "Documents/Obsidian").takeIf { it.isDirectory }?.absolutePath
                FolderPicker.show(this, "Default notes folder", start) { path ->
                    repo.setRootPath(path)
                    reload()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- folders / tabs ----------------

    private fun reload() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { repo.listFolders() }
            folders = list
            registeredFragments.clear()
            // 1-3 folders share the width equally; more than 3 scroll sideways
            binding.tabLayout.tabMode = if (list.size <= 3) TabLayout.MODE_FIXED else TabLayout.MODE_SCROLLABLE
            binding.tabLayout.tabGravity = TabLayout.GRAVITY_FILL
            binding.viewPager.adapter = FolderPagerAdapter(this@MainActivity, list)
            TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
                tab.text = list[position].name
            }.attach()
            list.forEachIndexed { i, f ->
                if (repo.isExtraFolder(f)) {
                    binding.tabLayout.getTabAt(i)?.view?.setOnLongClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Remove “${f.name}” from tabs?")
                            .setMessage("The folder and its notes stay on your phone.")
                            .setPositiveButton("Remove") { _, _ -> repo.removeExtraFolder(File(f.uri.path ?: "").absolutePath); reload() }
                            .setNegativeButton("Cancel", null).show()
                        true
                    }
                }
            }
        }
    }

    fun registerFragment(position: Int, fragment: NotesListFragment) {
        registeredFragments[position] = fragment
    }

    private fun currentFragment(): NotesListFragment? = registeredFragments[binding.viewPager.currentItem]

    fun currentFolder(): NoteFolder? = folders.getOrNull(binding.viewPager.currentItem)

    private fun showNewFolderDialog() {
        val root = repo.rootPath()
        var location = (root?.let { File(it).parent } ?: root) ?: VaultRepository.storageRoot().absolutePath
        val d = resources.displayMetrics.density
        val name = EditText(this).apply { hint = "Folder name" }
        val loc = TextView(this).apply { textSize = 12f; setPadding(0, (10 * d).toInt(), 0, (4 * d).toInt()) }
        fun showLoc() { loc.text = "Created in: $location" }
        showLoc()
        val change = Button(this).apply { text = "Change location" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * d).toInt(), (10 * d).toInt(), (22 * d).toInt(), 0)
            addView(name); addView(loc); addView(change)
        }
        change.setOnClickListener {
            FolderPicker.show(this, "Where should the folder be created?", location) { location = it; showLoc() }
        }
        AlertDialog.Builder(this)
            .setTitle("New folder")
            .setView(box)
            .setPositiveButton("Create") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    val dir = withContext(Dispatchers.IO) { repo.createFolderAt(location, n) }
                    if (dir == null) Toast.makeText(this@MainActivity, "Couldn't create the folder", Toast.LENGTH_SHORT).show()
                    else {
                        repo.addExtraFolder(dir.absolutePath)
                        reload()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addExistingFolder() {
        FolderPicker.show(this, "Add a folder as a tab", repo.rootPath()) { path ->
            repo.addExtraFolder(path)
            reload()
        }
    }

    // ---------------- multi-select bar ----------------

    private fun buildSelectionBar() {
        val acts = listOf(
            Triple("all", R.drawable.ic_select_all, "Select all"),
            Triple("compile", R.drawable.ic_compile, "Compile"),
            Triple("pin", R.drawable.ic_pin, "Pin"),
            Triple("copy", R.drawable.ic_copy, "Copy"),
            Triple("share", R.drawable.ic_share, "Share"),
            Triple("dup", R.drawable.ic_duplicate, "Duplicate"),
            Triple("move", R.drawable.ic_folder, "Move to"),
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

    // ---------------- add menu (new note / gallery) ----------------

    private fun showAddMenu() {
        val folder = currentFolder() ?: return
        AlertDialog.Builder(this)
            .setItems(arrayOf("New Note", "Gallery")) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, NoteEditorActivity::class.java).apply {
                        putExtra("isNew", true)
                        putExtra("folderUri", folder.uri.toString())
                        putExtra("folderName", folder.name)
                    })
                    1 -> galleryLauncher.launch("image/*")
                }
            }.show()
    }

    private fun newNoteWithImage(folder: NoteFolder, source: Uri) {
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                val a = repo.importAttachmentFull(folder, source, "img_${System.currentTimeMillis()}")
                repo.newBlankNote(folder, if (a != null) "![[${a.first}]]\n\n" else "")
            }
            if (uri != null) {
                startActivity(Intent(this@MainActivity, NoteEditorActivity::class.java).apply {
                    putExtra("noteUri", uri.toString())
                    putExtra("folderUri", folder.uri.toString())
                    putExtra("folderName", folder.name)
                })
            }
        }
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
            R.id.action_new_folder -> { showNewFolderDialog(); true }
            R.id.action_add_folder -> { addExistingFolder(); true }
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
            R.id.action_settings -> { requestRootFolder(); true }
            R.id.action_add_tile -> { addTile(); true }
            R.id.action_diagnostics -> { showDiagnostics(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            val sbm = getSystemService(StatusBarManager::class.java)
            sbm.requestAddTileService(
                ComponentName(this, QuickTileService::class.java), "Quick Note",
                Icon.createWithResource(this, R.drawable.ic_note), mainExecutor
            ) { }
        } else {
            AlertDialog.Builder(this)
                .setTitle("Add the Quick Settings tile")
                .setMessage("Swipe down twice to open Quick Settings, tap the pencil / edit icon, and drag the “Quick Note” tile into your active tiles.")
                .setPositiveButton("OK", null).show()
        }
    }

    private fun showDiagnostics() {
        val sb = StringBuilder()
        try {
            val f = File(filesDir, "last_crash.txt")
            if (f.exists()) sb.append("LAST CRASH\n").append(f.readText().take(3000)).append("\n\n")
        } catch (e: Exception) {
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val am = getSystemService(ActivityManager::class.java)
                for (e in am.getHistoricalProcessExitReasons(packageName, 0, 5)) {
                    val why = when (e.reason) {
                        ApplicationExitInfo.REASON_ANR -> "NOT RESPONDING (ANR)"
                        ApplicationExitInfo.REASON_CRASH -> "CRASH"
                        ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE CRASH"
                        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW MEMORY"
                        else -> "reason ${e.reason}"
                    }
                    sb.append(DateFormat.getDateTimeInstance().format(Date(e.timestamp))).append("  ").append(why)
                        .append("\n").append(e.description ?: "").append("\n")
                    if (e.reason == ApplicationExitInfo.REASON_ANR) {
                        e.traceInputStream?.bufferedReader()?.use { sb.append(it.readText().take(7000)) }
                    }
                    sb.append("\n")
                }
            } catch (e: Exception) {
                sb.append("Couldn't read exit info: ").append(e.message)
            }
        }
        if (sb.isEmpty()) sb.append("Nothing recorded yet. If the widget freezes again, open this screen right after and send me the text.")
        val tv = TextView(this).apply { text = sb.toString(); textSize = 11f; setTextIsSelectable(true); setPadding(40, 20, 40, 20) }
        AlertDialog.Builder(this)
            .setTitle("Diagnostics")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Copy") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("diag", sb.toString()))
            }
            .setNegativeButton("Close", null).show()
    }
}
