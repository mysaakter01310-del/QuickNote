package com.quicknote.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.quicknote.app.repo.VaultRepository

/**
 * Currently a thin pass-through: MainActivity's "Settings" menu item re-launches the
 * vault folder picker directly. This activity is kept as a clean place to grow
 * (theme choice, default folder, etc.) without touching MainActivity again.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var repo: VaultRepository

    private val pickVaultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data?.data?.let { repo.setVaultUri(it) }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = VaultRepository(this)
        pickVaultLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
    }
}
