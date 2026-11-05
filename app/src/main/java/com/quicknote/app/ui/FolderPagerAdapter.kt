package com.quicknote.app.ui

import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.quicknote.app.model.NoteFolder

class FolderPagerAdapter(
    activity: FragmentActivity,
    private val folders: List<NoteFolder>
) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = folders.size

    override fun createFragment(position: Int) = NotesListFragment.newInstance(folders[position], position)
}
