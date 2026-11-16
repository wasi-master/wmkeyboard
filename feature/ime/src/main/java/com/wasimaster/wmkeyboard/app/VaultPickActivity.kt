package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.wasimaster.wmkeyboard.ime.vault.VaultPrefs

/**
 * Invisible trampoline for the notes vault's "Choose a vault folder" (#1211):
 * Android's folder picker is an activity-result flow, which an input method
 * cannot run. The same shape as [FileTreePickActivity].
 *
 * The picked folder's grant is made persistable here, read and write (a vault
 * the keyboard could not write to would be read-only, which it says), and the
 * folder becomes the vault in [VaultPrefs]. The keyboard hears of it through
 * those preferences and lists the folder.
 */
class VaultPickActivity : ComponentActivity() {

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, VaultPickActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private val tree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) take(uri)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (runCatching { tree.launch(null) }.isFailure) finish()
    }

    private fun take(uri: Uri) {
        val both = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val writable = runCatching { contentResolver.takePersistableUriPermission(uri, both) }.isSuccess
        val readable = writable ||
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
        if (!readable) return
        // The old vault's grant is kept: the file manager may list the same
        // folder, and a grant nobody uses costs nothing but a slot.
        VaultPrefs.setTree(this, uri.toString(), writable)
    }
}
