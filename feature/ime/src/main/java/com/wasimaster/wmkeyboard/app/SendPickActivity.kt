package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Invisible trampoline for the Send files tool's "Files" tile (#729): the
 * document picker is an activity-result flow, which an input method cannot
 * run. The same shape as [GalleryPickActivity], with its own queue so the two
 * tools never take each other's picks.
 *
 * Nothing is copied here. The picks are parked in [consume]'s queue and the
 * keyboard, in the same process, adds them to its draft once the app behind
 * it has the field back.
 *
 * The picker's read grant belongs to this activity and Android revokes it
 * when the activity goes, which is at once: the upload, minutes later, would
 * meet a `SecurityException` (#808). So each pick's grant is made persistable
 * here, and the keyboard lets it go again once the file has left the draft.
 */
class SendPickActivity : ComponentActivity() {

    /**
     * One pick: a URI this process may read, and what the picker said about it.
     * [held] says the read grant was persisted here, for this pick, and is the
     * keyboard's to release; false when it was already held for something else.
     */
    data class Picked(val uri: Uri, val mime: String, val name: String, val size: Long, val held: Boolean = false)

    companion object {
        private val pending = ArrayDeque<Picked>()

        /** Hands over (and clears) what the last pick produced, oldest first. */
        fun consume(): List<Picked> = synchronized(pending) {
            val out = pending.toList()
            pending.clear()
            out
        }

        fun hasPending(): Boolean = synchronized(pending) { pending.isNotEmpty() }

        /** Queues [picks] for the draft; the keyboard's own file manager comes this way too. */
        internal fun offer(picks: List<Picked>) = synchronized(pending) {
            pending.clear()
            pending.addAll(picks)
        }

        /** What [uri] says about itself, with a name made from the URI where it gives none. */
        internal fun describe(context: Context, uri: Uri): Picked {
            var name = ""
            var size = 0L
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val s = c.getColumnIndex(OpenableColumns.SIZE)
                        if (n >= 0 && !c.isNull(n)) name = c.getString(n).orEmpty()
                        if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                    }
                }
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            return Picked(uri, mime, name.ifBlank { uri.lastPathSegment?.substringAfterLast('/') ?: "file" }, size)
        }

        fun intent(context: Context): Intent =
            Intent(context, SendPickActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private val documents = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        deliver(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A restarted activity (rotation, process restore) is already waiting
        // on the picker it started the first time.
        if (savedInstanceState != null) return
        if (runCatching { documents.launch(arrayOf("*/*")) }.isFailure) finish()
    }

    private fun deliver(uris: List<Uri>) {
        val picks = uris.mapNotNull { uri -> runCatching { describe(this, uri).copy(held = hold(uri)) }.getOrNull() }
        if (picks.isNotEmpty()) offer(picks)
        finish()
    }

    /** Keeps the read grant past this activity; true when it is newly held for this pick. */
    private fun hold(uri: Uri): Boolean {
        if (contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }) return false
        return runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
    }
}
