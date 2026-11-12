package com.wasimaster.wmkeyboard.core.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns

/**
 * One folder added to the file manager through Android's folder picker (#1267).
 * [uri] is the tree the picker granted; [root] is its top folder as a document.
 */
data class FileTree(
    val uri: String,
    val root: String,
    val label: String,
    val kind: Kind,
) {
    /** What the folder is on, for its icon: the phone, a removable volume, or somewhere an app reaches (a server, a cloud). */
    enum class Kind { PHONE, REMOVABLE, ELSEWHERE }
}

/**
 * The folders the file manager was given through the folder picker, kept on
 * this phone only: a grant means nothing on another device, so the list is in
 * plain preferences of its own, outside settings, sync and backups.
 *
 * Each stored entry remembers whether the grant was taken for it. Taking a
 * folder off the list lets that grant go only then, so a folder that a backup
 * place also writes to keeps working.
 */
object FileTrees {

    private const val PREFS = "file_manager_trees"
    private const val KEY = "trees"
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    private const val PRIMARY = "primary:"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** (uri, held) in the order they were added. */
    private fun stored(context: Context): List<Pair<String, Boolean>> =
        prefs(context).getString(KEY, null).orEmpty().lines().mapNotNull { line ->
            val held = line.startsWith("1|")
            line.substringAfter('|', "").takeIf { it.isNotEmpty() }?.let { it to held }
        }

    private fun store(context: Context, entries: List<Pair<String, Boolean>>) {
        prefs(context).edit().putString(KEY, entries.joinToString("\n") { (uri, held) -> (if (held) "1|" else "0|") + uri }).apply()
    }

    /** Remembers [tree]; [held] says its grant was taken for the file manager and is the manager's to let go. */
    fun add(context: Context, tree: Uri, held: Boolean) {
        val uri = tree.toString()
        val rest = stored(context).filterNot { it.first == uri }
        store(context, rest + (uri to held))
    }

    /** Takes [uri] off the list, and lets its grant go when it was taken for the list. */
    fun remove(context: Context, uri: String) {
        val all = stored(context)
        val entry = all.firstOrNull { it.first == uri } ?: return
        store(context, all - entry)
        if (entry.second) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
    }

    /**
     * The folders whose grant is still held, named by their provider. One
     * whose grant Android took back (the app behind it uninstalled, the
     * grant cleared) quietly drops off. Asks each provider, so off the main thread.
     */
    fun list(context: Context): List<FileTree> {
        val granted = runCatching {
            context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        }.getOrDefault(emptySet())
        return stored(context).mapNotNull { (uri, _) ->
            if (uri !in granted) return@mapNotNull null
            val tree = Uri.parse(uri)
            val root = runCatching { DocFiles.rootOf(tree) }.getOrNull() ?: return@mapNotNull null
            FileTree(uri, root.toString(), labelOf(context, root), kindOf(tree))
        }
    }

    private fun labelOf(context: Context, root: Uri): String {
        val named = runCatching {
            context.contentResolver.query(root, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        return named?.takeIf { it.isNotBlank() }
            ?: runCatching { DocumentsContract.getDocumentId(root).substringAfterLast(':').substringAfterLast('/') }
                .getOrNull().orEmpty().ifEmpty { root.authority.orEmpty() }
    }

    private fun kindOf(tree: Uri): FileTree.Kind {
        if (tree.authority != EXTERNAL_STORAGE) return FileTree.Kind.ELSEWHERE
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull().orEmpty()
        return if (id.startsWith(PRIMARY)) FileTree.Kind.PHONE else FileTree.Kind.REMOVABLE
    }
}
