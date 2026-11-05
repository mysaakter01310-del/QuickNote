package com.quicknote.app.ui

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.quicknote.app.util.DraftStore
import com.quicknote.app.util.QuickSaver

/** Invisible helper so the floating box can open the gallery. Images go straight into the draft. */
class ImagePickActivity : AppCompatActivity() {

    private val pick = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) QuickSaver.copyPicked(applicationContext, uris)
        DraftStore.notifyChanged()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pick.launch("image/*")
    }
}
