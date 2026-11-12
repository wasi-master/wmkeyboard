package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.wasimaster.wmkeyboard.core.files.FileTrees

/**
 * Invisible trampoline for the file manager's "Add a folder" (#1267): Android's
 * folder picker is an activity-result flow, which an input method cannot run.
 * The same shape as [SendPickActivity].
 *
 * The picked folder's grant is made persistable here, read and write where
 * the provider gives both, and the folder goes on [FileTrees]' list. The
 * keyboard, back on screen, reads the list again.
 */
class FileTreePickActivity : ComponentActivity() {

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, FileTreePickActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private val tree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) take(uri)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A restarted activity (rotation, process restore) is already waiting
        // on the picker it started the first time.
        if (savedInstanceState != null) return
        if (runCatching { tree.launch(null) }.isFailure) finish()
    }

    private fun take(uri: Uri) {
        val both = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val had = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        if (had) {
            FileTrees.add(this, uri, held = false)
            return
        }
        // A provider that shares a folder read-only gives no write grant to keep.
        val taken = runCatching { contentResolver.takePersistableUriPermission(uri, both) }.isSuccess ||
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
        if (taken) FileTrees.add(this, uri, held = true)
    }
}
