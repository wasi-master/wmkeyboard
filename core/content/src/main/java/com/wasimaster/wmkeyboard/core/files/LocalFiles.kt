package com.wasimaster.wmkeyboard.core.files

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Paths the provider below may open, apart from anything Android so the check runs on the JVM. */
object LocalFilePaths {

    /**
     * The real file at [path] when it sits inside one of [roots], else null.
     * Canonical on both sides, so neither a `..` nor a symbolic link walks out
     * of shared storage and into, say, this app's own private files.
     */
    fun resolve(path: String, roots: List<File>): File? {
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        val inside = roots.any { root ->
            val real = runCatching { root.canonicalFile }.getOrNull() ?: return@any false
            file == real || file.path.startsWith(real.path + File.separator)
        }
        return file.takeIf { inside && it.isFile }
    }
}

/**
 * Hands a file the file manager picked to the code that asked, as the kind of
 * URI that code already takes from Android's picker.
 *
 * Everything downstream reads a pick through `ContentResolver`, and a `file://`
 * URI answers nothing when asked for its name, size or type. This answers those
 * three and opens the file to read, and nothing else.
 *
 * Not exported, and it grants nothing: the keyboard's own process is the only
 * caller there can be, and what reaches another app is always a prepared copy
 * or the bytes, never one of these. Declared in the main manifest and removed
 * from Play's with the permission it depends on.
 */
class LocalFilesProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun fileOf(uri: Uri): File? {
        val app = context ?: return null
        val path = uri.pathSegments.drop(1).joinToString(separator = "/", prefix = "/")
        return LocalFilePaths.resolve(path, FilePlaces.volumes(app).map { File(it.path) })
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = fileOf(uri) ?: throw FileNotFoundException("not on shared storage")
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(
                columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> file.name
                        OpenableColumns.SIZE -> file.length()
                        else -> null
                    }
                },
            )
        }
    }

    override fun getType(uri: Uri): String? =
        uri.lastPathSegment?.let(FilePlaces::mimeOf) ?: "application/octet-stream"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read only")
        val file = fileOf(uri) ?: throw FileNotFoundException("not on shared storage")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val AUTHORITY_SUFFIX = ".localfiles"

        /** The URI [file] is read through. One segment per folder, so `lastPathSegment` is its name. */
        fun uriFor(context: Context, file: File): Uri {
            val builder = Uri.Builder()
                .scheme("content")
                .authority(context.packageName + AUTHORITY_SUFFIX)
                .appendPath("f")
            file.absolutePath.split('/').filter { it.isNotEmpty() }.forEach { builder.appendPath(it) }
            return builder.build()
        }
    }
}
