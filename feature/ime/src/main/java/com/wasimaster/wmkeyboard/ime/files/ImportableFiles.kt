package com.wasimaster.wmkeyboard.ime.files

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import com.wasimaster.wmkeyboard.core.files.DocFiles
import com.wasimaster.wmkeyboard.core.files.FileEntry
import com.wasimaster.wmkeyboard.core.files.FileKind
import com.wasimaster.wmkeyboard.core.files.FileKinds

/**
 * The files the app imports, as the Files tool's preview shows them: one of
 * the keyboard's own exports (a theme, a layout, a backup, a sticker pack, a
 * plugin) or another app's that the app converts (a FlorisBoard theme, a
 * HeliBoard backup, a Keyman package, a Gboard dictionary, a font).
 *
 * The app reads them: what a file is and what importing it would do live in
 * `WMFileTypes` and the import dialog's proposals, in the app module, which
 * this one cannot see. So the app hands its reader in at start ([reader]),
 * the keyboard and the app sharing one process, and Import opens the app's
 * own import dialog on the file, with its questions (a language, a
 * passphrase, which parts of a backup) and its report.
 */
object ImportableFiles {

    /**
     * What the import dialog would say about a file: its [title] ("Import
     * theme Dusk?"), its [body], the [notes] on what had to be fixed or will
     * not come across, and its [visual], the dialog's own picture of the
     * contents (a theme's miniature, a layout's grid, a pack's stickers).
     */
    class Found(
        val title: String,
        val body: String,
        val notes: List<String>,
        val visual: (@Composable () -> Unit)?,
    )

    /** Reads a file by its uri and name; null when it is nothing the app imports. Blocking. */
    @Volatile
    var reader: ((Context, android.net.Uri, String) -> Found?)? = null

    /**
     * Whether [entry] is worth reading for an import: not a folder, a
     * picture, a film, a recording, a PDF or an app package (none of those is
     * a format of the app's, and a recording read as a key sound would offer
     * every song as one), and not so big that reading its head is the
     * slow part.
     */
    fun candidate(entry: FileEntry): Boolean {
        if (entry.isDirectory || reader == null) return false
        if (entry.size > MAX_BYTES) return false
        val kind = FileKinds.of(entry)
        // A long log or a data dump would be read whole only to be turned down.
        if (FileKinds.isTextual(kind) && entry.size > MAX_TEXT_BYTES) return false
        // On a server, reading it means fetching it; only small files are worth that unasked.
        if (com.wasimaster.wmkeyboard.core.files.RemoteFiles.isRemote(entry.path) && entry.size > MAX_REMOTE_BYTES) return false
        return kind != FileKind.IMAGE && kind != FileKind.VIDEO && kind != FileKind.AUDIO &&
            kind != FileKind.PDF && kind != FileKind.APK
    }

    /** What [entry] would import as, or null. Blocking: call it off the main thread. */
    fun read(context: Context, entry: FileEntry): Found? {
        val read = reader ?: return null
        if (!candidate(entry)) return null
        return runCatching { read(context.applicationContext, DocFiles.uriOf(context, entry.path), entry.name) }.getOrNull()
    }

    /**
     * The app's import dialog on [entry], over whatever app is open, as
     * opening the file from a file manager would bring it up. False when it
     * would not start.
     */
    fun open(context: Context, entry: FileEntry): Boolean {
        val app = context.applicationContext
        val type = entry.mime.ifEmpty { "application/octet-stream" }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(DocFiles.uriOf(app, entry.path), type)
            .setClassName(app.packageName, IMPORT_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return runCatching { app.startActivity(intent) }.isSuccess
    }

    private const val IMPORT_ACTIVITY = "com.wasimaster.wmkeyboard.app.ImportFileActivity"

    /** A backup with its pictures can run to hundreds of megabytes; past this, only Open with. */
    private const val MAX_BYTES = 512L * 1024 * 1024

    /** Past this, a text or code file is not a theme, a layout or a snippet list. */
    private const val MAX_TEXT_BYTES = 16L * 1024 * 1024

    /** A theme, a layout or a backup of settings; not a pack of stickers on a slow server. */
    private const val MAX_REMOTE_BYTES = 8L * 1024 * 1024
}
