package com.wasimaster.wmkeyboard.core.files

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
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

enum class FileSort { NAME, DATE, SIZE, TYPE }

/**
 * What a search looks for: every one of [words] in the name (or, with
 * [inContents], in a text file's words), narrowed by [kinds], a size range and
 * a date. Empty [kinds] takes every kind; zero bounds are no bound.
 */
data class FileQuery(
    val text: String,
    val kinds: Set<FileKind> = emptySet(),
    val minSize: Long = 0,
    val maxSize: Long = 0,
    /** Changed since this moment, in milliseconds; 0 for any time. */
    val after: Long = 0,
    val inContents: Boolean = false,
) {
    val words: List<String> = text.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** Whether the filters alone, apart from the words, would let [entry] through. */
    fun admits(entry: FileEntry): Boolean {
        if (kinds.isNotEmpty() && FileKinds.of(entry) !in kinds) return false
        if (entry.isDirectory && (minSize > 0 || maxSize > 0)) return false
        if (minSize > 0 && entry.size < minSize) return false
        if (maxSize > 0 && entry.size > maxSize) return false
        if (after > 0 && entry.modifiedMs < after) return false
        return true
    }

    fun nameMatches(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return words.all { it in lower }
    }

    /** Whether a search like this one has anything to look for. */
    val isEmpty: Boolean get() = words.isEmpty() && kinds.isEmpty() && minSize == 0L && maxSize == 0L && after == 0L
}

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

    /** The largest text file a search reads for its words; past this it is a log or a dump, not a note. */
    const val CONTENT_MAX = 2L * 1024 * 1024

    /** How often a listing of a big folder says how far it has come. */
    private const val LIST_STEP = 500

    /** How often, at most, a search hands over what it has found so far. */
    private const val BATCH_MS = 200L

    /**
     * What [dir] holds, folders first. Null when it cannot be read at all,
     * which is not the same as empty. [onCount] hears how many it has read,
     * every few hundred, so a folder of ten thousand photos can say so while
     * it reads. [fast] reads each entry's attributes in one call (Android 8
     * and later); the plain way asks three times.
     */
    fun list(
        dir: File,
        sort: FileSort = FileSort.NAME,
        descending: Boolean = false,
        showHidden: Boolean = false,
        mimes: List<String> = emptyList(),
        mimeOf: (String) -> String? = { null },
        foldersFirst: Boolean = true,
        fast: Boolean = false,
        onCount: (Int) -> Unit = {},
    ): List<FileEntry>? {
        val out = ArrayList<FileEntry>()
        if (fast) {
            val read = runCatching {
                Files.newDirectoryStream(dir.toPath()).use { stream ->
                    for (child in stream) {
                        val name = child.fileName.toString()
                        if (!showHidden && name.startsWith(".")) continue
                        val attrs = runCatching { Files.readAttributes(child, BasicFileAttributes::class.java) }
                            .recoverCatching { Files.readAttributes(child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }
                            .getOrNull() ?: continue
                        val folder = attrs.isDirectory
                        val entry = FileEntry(
                            path = child.toString(),
                            name = name,
                            isDirectory = folder,
                            size = if (folder) 0L else attrs.size(),
                            modifiedMs = attrs.lastModifiedTime().toMillis(),
                            mime = if (folder) "" else mimeOf(name).orEmpty(),
                        )
                        if (entry.isDirectory || mimeMatches(entry.mime, mimes)) out += entry
                        if (out.size % LIST_STEP == 0) onCount(out.size)
                    }
                }
            }
            if (read.isFailure) return null
        } else {
            val children = dir.listFiles() ?: return null
            for ((index, child) in children.withIndex()) {
                if (!showHidden && child.name.startsWith(".")) continue
                val entry = entryOf(child, mimeOf)
                if (entry.isDirectory || mimeMatches(entry.mime, mimes)) out += entry
                if (index % LIST_STEP == LIST_STEP - 1) onCount(index + 1)
            }
        }
        return sorted(out, sort, descending, foldersFirst)
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
    ): List<FileEntry> = search(root, FileQuery(query), showHidden, mimes, cap, mimeOf)

    /**
     * Every file and folder under [root] that [query] takes, in walk order, up
     * to [cap]. A file's words are read only when the query asks for that,
     * the name did not already match, the file is text and no bigger than
     * [CONTENT_MAX]. [onBatch] hears everything found so far, a few times a
     * second, so results show while the walk goes on.
     */
    suspend fun search(
        root: File,
        query: FileQuery,
        showHidden: Boolean = false,
        mimes: List<String> = emptyList(),
        cap: Int = SEARCH_CAP,
        mimeOf: (String) -> String? = { null },
        onBatch: (List<FileEntry>) -> Unit = {},
        clock: () -> Long = System::currentTimeMillis,
    ): List<FileEntry> {
        if (query.isEmpty) return emptyList()
        val found = ArrayList<FileEntry>()
        val context = currentCoroutineContext()
        val pending = ArrayDeque<Pair<File, Int>>()
        pending.addLast(root to 0)
        var lastBatch = clock()
        var told = 0
        while (pending.isNotEmpty() && found.size < cap) {
            val (dir, depth) = pending.removeFirst()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                context.ensureActive()
                if (!showHidden && child.name.startsWith(".")) continue
                val folder = child.isDirectory
                if (folder && depth < SEARCH_DEPTH && !FileOps.isSymlink(child)) pending.addLast(child to depth + 1)
                val entry = entryOf(child, mimeOf)
                if (!entry.isDirectory && !mimeMatches(entry.mime, mimes)) continue
                if (!query.admits(entry)) continue
                val hit = query.nameMatches(child.name) ||
                    (query.inContents && !folder && contentMatches(entry, query) { child.inputStream() })
                if (hit) {
                    found += entry
                    if (found.size >= cap) break
                }
                val now = clock()
                if (found.size > told && now - lastBatch >= BATCH_MS) {
                    onBatch(found.toList())
                    told = found.size
                    lastBatch = now
                }
            }
        }
        if (found.size > told) onBatch(found.toList())
        return found
    }

    /** Whether a text [entry]'s words hold all of [query]'s, read through [open]; anything unreadable is no match. */
    fun contentMatches(entry: FileEntry, query: FileQuery, open: () -> java.io.InputStream): Boolean {
        if (query.words.isEmpty()) return false
        if (!FileKinds.isTextual(FileKinds.of(entry)) || entry.size > CONTENT_MAX) return false
        val text = runCatching { open().use { it.readBytes() }.toString(Charsets.UTF_8) }.getOrNull() ?: return false
        val lower = text.lowercase(Locale.ROOT)
        return query.words.all { it in lower }
    }

    /** [entries] folders first (unless [foldersFirst] is off), then by [sort]; names break every tie, so the order never shuffles. */
    fun sorted(entries: List<FileEntry>, sort: FileSort, descending: Boolean = false, foldersFirst: Boolean = true): List<FileEntry> {
        val byName = compareBy<FileEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        val order: Comparator<FileEntry> = when (sort) {
            FileSort.NAME -> byName
            FileSort.DATE -> compareBy<FileEntry> { it.modifiedMs }.then(byName)
            FileSort.SIZE -> compareBy<FileEntry> { it.size }.then(byName)
            FileSort.TYPE -> compareBy<FileEntry> { FileKinds.of(it).ordinal }
                .thenBy { FileKinds.extensionOf(it.name) }
                .then(byName)
        }
        val directed = if (descending) order.reversed() else order
        return if (foldersFirst) {
            entries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.then(directed))
        } else {
            entries.sortedWith(directed)
        }
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
