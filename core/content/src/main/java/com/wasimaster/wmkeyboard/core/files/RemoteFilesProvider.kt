package com.wasimaster.wmkeyboard.core.files

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/**
 * Reads a file on a server or in a cloud ([RemoteFiles]) for whatever asks
 * through a URI: the keyboard's previews, a picture's thumbnail, Send, and
 * the app a Share or Open with hands it to, under a grant for that one file.
 *
 * Opening fetches the whole file into the cache first (kept while the
 * server reports the same size and time), because a video player and a PDF
 * renderer want to seek. Read-only: nothing writes to a server through here,
 * since a writer could not be told whether its upload arrived. Not exported;
 * only a grant reaches it from another app.
 */
class RemoteFilesProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val app = context ?: throw FileNotFoundException("no context")
        val entry = RemoteFiles.entry(app, uri.toString()) ?: throw FileNotFoundException("not there")
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(
                columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> entry.name
                        OpenableColumns.SIZE -> if (entry.isDirectory) null else entry.size
                        else -> null
                    }
                },
            )
        }
    }

    override fun getType(uri: Uri): String? =
        RemoteFiles.nameOf(uri.toString()).let(FilePlaces::mimeOf) ?: "application/octet-stream"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read only")
        val app = context ?: throw FileNotFoundException("no context")
        val file = RemoteFiles.cached(app, uri.toString()) ?: throw FileNotFoundException("could not fetch it")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
