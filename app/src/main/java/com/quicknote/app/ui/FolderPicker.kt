package com.quicknote.app.ui

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.quicknote.app.repo.VaultRepository
import java.io.File

/** Simple in-app folder browser. Starts anywhere, can go up to the top of internal storage. */
object FolderPicker {

    fun show(ctx: Context, title: String, startPath: String?, onPicked: (String) -> Unit) {
        val top = VaultRepository.storageRoot()
        var current = File(startPath ?: "").takeIf { it.isDirectory } ?: top
        val d = ctx.resources.displayMetrics.density

        val pathView = TextView(ctx).apply {
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), (8 * d).toInt())
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        }
        val list = ListView(ctx)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(pathView)
            addView(list, LinearLayout.LayoutParams(-1, (300 * d).toInt()))
        }
        var shown: List<File> = emptyList()
        val adapter = ArrayAdapter<String>(ctx, android.R.layout.simple_list_item_1)
        list.adapter = adapter

        fun refresh() {
            pathView.text = current.absolutePath
            shown = (current.listFiles { f -> f.isDirectory } ?: emptyArray()).sortedBy { it.name.lowercase() }
            adapter.clear()
            adapter.add(if (current.absolutePath == top.absolutePath) "(top of internal storage)" else "⬆  ..  (go up)")
            shown.forEach { adapter.add("📁  " + it.name) }
            adapter.notifyDataSetChanged()
        }
        list.setOnItemClickListener { _, _, pos, _ ->
            if (pos == 0) {
                val parent = current.parentFile
                if (current.absolutePath != top.absolutePath && parent != null) current = parent
            } else current = shown[pos - 1]
            refresh()
        }
        refresh()

        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Choose this folder") { _, _ -> onPicked(current.absolutePath) }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("New folder here", null)
            .create().also { dlg ->
                dlg.show()
                dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    val input = EditText(ctx)
                    AlertDialog.Builder(ctx).setTitle("New folder in ${current.name}").setView(input)
                        .setPositiveButton("Create") { _, _ ->
                            val n = input.text.toString().trim()
                            if (n.isNotEmpty()) {
                                val f = File(current, n)
                                if (f.exists() || f.mkdirs()) { current = f; refresh() }
                                else Toast.makeText(ctx, "Couldn't create folder", Toast.LENGTH_SHORT).show()
                            }
                        }.setNegativeButton("Cancel", null).show()
                }
            }
    }
}
