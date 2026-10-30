package com.wasimaster.wmkeyboard.core.files

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.Locale

/** One row of the file manager: a file or a folder on shared storage. */
data class FileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedMs: Long,
    /** The file's type by its extension; empty for a folder or an extension nothing knows. */
    val mime: String,
)

enum class FileSort { NAME, DATE, SIZE }

/**
 * Listing and searching for the keyboard's file manager, over plain
 * [java.io.File] and nothing of Android's, so it runs on the JVM in tests. The
 * Android half (volumes, shortcuts, the recent files) is [FilePlaces].
 *
 * [mimeOf] is how a name becomes a type: `MimeTypeMap` on the device.
 */
object FileStore {

    /** The most a search returns: a walk over a whole phone can match far more than anyone reads. */
    const val SEARCH_CAP = 200

    /** How deep a search walks; storage this deep is an app's cache, not anything being looked for. */
    private const val SEARCH_DEPTH = 24

    /** What [dir] holds, folders first. Null when it cannot be read at all, which is not the same as empty. */
    fun list(
        dir: File,
        sort: FileSort = FileSort.NAME,
        descending: Boolean = false,
        showHidden: Boolean = false,
        mimes: List<String> = emptyList(),
        mimeOf: (String) -> String? = { null },
    ): List<FileEntry>? {
        val children = dir.listFiles() ?: return null
        return sorted(
            children.asSequence()
                .filter { showHidden || !it.name.startsWith(".") }
                .map { entryOf(it, mimeOf) }
                .filter { it.isDirectory || mimeMatches(it.mime, mimes) }
                .toList(),
            sort,
            descending,
        )
    }

    /**
     * Every file and folder under [root] whose name holds all of [query]'s
     * words, in walk order, up to [cap]. Symbolic links are not followed, so a
     * loop cannot keep it walking; a cancelled caller stops it at the next file.
     */
    suspend fun search(
        root: File,
        query: String,
        showHidden: Boolean = false,
        mimes: List<String> = emptyList(),
        cap: Int = SEARCH_CAP,
        mimeOf: (String) -> String? = { null },
    ): List<FileEntry> {
        val words = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val found = ArrayList<FileEntry>()
        val context = currentCoroutineContext()
        val pending = ArrayDeque<Pair<File, Int>>()
        pending.addLast(root to 0)
        while (pending.isNotEmpty() && found.size < cap) {
            val (dir, depth) = pending.removeFirst()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                context.ensureActive()
                if (!showHidden && child.name.startsWith(".")) continue
                val folder = child.isDirectory
                if (folder && depth < SEARCH_DEPTH && !FileOps.isSymlink(child)) pending.addLast(child to depth + 1)
                val name = child.name.lowercase(Locale.ROOT)
                if (words.all { it in name }) {
                    val entry = entryOf(child, mimeOf)
                    if (entry.isDirectory || mimeMatches(entry.mime, mimes)) {
                        found += entry
                        if (found.size >= cap) break
                    }
                }
            }
        }
        return found
    }

    /** [entries] folders first, then by [sort]; names break every tie, so the order never shuffles. */
    fun sorted(entries: List<FileEntry>, sort: FileSort, descending: Boolean = false): List<FileEntry> {
        val byName = compareBy<FileEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        val order: Comparator<FileEntry> = when (sort) {
            FileSort.NAME -> byName
            FileSort.DATE -> compareBy<FileEntry> { it.modifiedMs }.then(byName)
            FileSort.SIZE -> compareBy<FileEntry> { it.size }.then(byName)
        }
        val directed = if (descending) order.reversed() else order
        return entries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.then(directed))
    }

    /** Whether [mime] answers one of [filters], which may end in a wildcard; no filters takes everything. */
    fun mimeMatches(mime: String, filters: List<String>): Boolean {
        if (filters.isEmpty()) return true
        return filters.any { filter ->
            when {
                filter == "*/*" -> true
                filter.endsWith("/*") -> mime.startsWith(filter.dropLast(1), ignoreCase = true)
                else -> mime.equals(filter, ignoreCase = true)
            }
        }
    }

    fun entryOf(file: File, mimeOf: (String) -> String? = { null }): FileEntry {
        val folder = file.isDirectory
        return FileEntry(
            path = file.path,
            name = file.name,
            isDirectory = folder,
            size = if (folder) 0L else file.length(),
            modifiedMs = file.lastModified(),
            mime = if (folder) "" else mimeOf(file.name).orEmpty(),
        )
    }
}
