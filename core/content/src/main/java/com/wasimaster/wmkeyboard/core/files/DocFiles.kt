package com.wasimaster.wmkeyboard.core.files

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Folders the file manager reaches through Android's folder picker (the
 * Storage Access Framework) rather than by path (#1267): a USB stick or an SD
 * card on a phone without All files access, a network share or a cloud drive
 * whose app offers its folders to the picker, and every folder of a Play
 * build, which cannot hold All files access at all.
 *
 * A document is named by its `content://…/tree/…/document/…` string, which
 * the file manager carries wherever it carries a path; [isDoc] tells the two
 * apart, and the calls here take either, so a copy from the phone to a USB
 * stick or from a network share to Downloads is one call.
 *
 * Every call blocks and wants a background thread. A provider that refuses
 * something answers null or a failure count, never an exception to the caller.
 */
object DocFiles {

    private const val BUFFER = 64 * 1024
    private const val SEARCH_DEPTH = 8
    private const val OCTET = "application/octet-stream"

    private val COLUMNS = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED,
    )

    fun isDoc(path: String): Boolean = path.startsWith(ContentResolver.SCHEME_CONTENT + "://")

    /** The URI a pick or a thumbnail reads [path] through: the document itself, or the keyboard's own provider for a file. */
    fun uriOf(context: Context, path: String): Uri = if (isDoc(path)) Uri.parse(path) else FilePlaces.uriOf(context, path)

    /** The top folder of [tree], the URI the folder picker handed over, as a document the manager can list. */
    fun rootOf(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    /** What [path] is, asked of its provider; null when it is gone or the grant is. */
    fun entry(context: Context, path: String): FileEntry? = runCatching {
        val uri = Uri.parse(path)
        context.contentResolver.query(uri, COLUMNS, null, null, null)?.use { c ->
            if (c.moveToFirst()) entryOf(uri, c) else null
        }
    }.getOrNull()

    /** What the folder [path] holds, folders first. Null when it cannot be read at all, which is not the same as empty. */
    fun list(
        context: Context,
        path: String,
        sort: FileSort = FileSort.NAME,
        descending: Boolean = false,
        showHidden: Boolean = false,
        mimes: List<String> = emptyList(),
    ): List<FileEntry>? {
        val dir = Uri.parse(path)
        val children = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(dir, DocumentsContract.getDocumentId(dir))
        }.getOrNull() ?: return null
        val out = ArrayList<FileEntry>()
        val read = runCatching {
            context.contentResolver.query(children, COLUMNS, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val entry = entryOf(DocumentsContract.buildDocumentUriUsingTree(dir, id), c)
                    if (!showHidden && entry.name.startsWith(".")) continue
                    if (entry.isDirectory || FileStore.mimeMatches(entry.mime, mimes)) out += entry
                }
                true
            } ?: false
        }.getOrDefault(false)
        return if (read) FileStore.sorted(out, sort, descending) else null
    }

    /**
     * Every file and folder under [path] whose name holds all of [query]'s
     * words, up to [cap]. Shallower than a search on the phone's own storage:
     * every folder here is a round trip to its provider, which may be a server.
     */
    suspend fun search(
        context: Context,
        path: String,
        query: String,
        showHidden: Boolean = false,
        mimes: List<String> = emptyList(),
        cap: Int = FileStore.SEARCH_CAP,
    ): List<FileEntry> {
        val words = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val found = ArrayList<FileEntry>()
        val pending = ArrayDeque<Pair<String, Int>>()
        pending.addLast(path to 0)
        while (pending.isNotEmpty() && found.size < cap) {
            currentCoroutineContext().ensureActive()
            val (dir, depth) = pending.removeFirst()
            val children = list(context, dir, showHidden = showHidden) ?: continue
            for (child in children) {
                if (child.isDirectory && depth < SEARCH_DEPTH) pending.addLast(child.path to depth + 1)
                val name = child.name.lowercase(Locale.ROOT)
                if (words.all { it in name } && (child.isDirectory || FileStore.mimeMatches(child.mime, mimes))) {
                    found += child
                    if (found.size >= cap) break
                }
            }
        }
        return found
    }

    /** Makes [name] in the folder [dir]. The new folder, or null when the name is no good, taken, or refused. */
    fun newFolder(context: Context, dir: String, name: String): String? {
        if (!FileOps.validName(name)) return null
        val clean = name.trim()
        if (list(context, dir, showHidden = true)?.any { it.name == clean } != false) return null
        return runCatching {
            DocumentsContract.createDocument(context.contentResolver, Uri.parse(dir), Document.MIME_TYPE_DIR, clean)
        }.getOrNull()?.toString()
    }

    /** Renames [path] where it is. Its new name, which a provider may hand back as a new URI; null when refused. */
    fun rename(context: Context, path: String, name: String): String? {
        if (!FileOps.validName(name)) return null
        val uri = Uri.parse(path)
        return runCatching {
            (DocumentsContract.renameDocument(context.contentResolver, uri, name.trim()) ?: uri).toString()
        }.getOrNull()
    }

    /** Deletes each of [paths], files or documents, folders and all. There is no way back; the panel asks first. */
    suspend fun delete(
        context: Context,
        paths: List<String>,
        onProgress: (FileOpProgress) -> Unit = {},
    ): FileOpResult {
        if (paths.none(::isDoc)) return FileOps.delete(paths.map(::File), onProgress)
        var done = 0
        var failed = 0
        val touched = ArrayList<String>()
        paths.forEachIndexed { index, path ->
            currentCoroutineContext().ensureActive()
            onProgress(FileOpProgress(index, paths.size, nameOf(path)))
            if (remove(context, path, touched)) done++ else failed++
        }
        onProgress(FileOpProgress(paths.size, paths.size, ""))
        return FileOpResult(done, failed, touched)
    }

    /**
     * Copies or moves each of [sources] into the folder [dir], any of them a
     * file or a document. Between two places on the phone's own storage this
     * is [FileOps], renames and all. Otherwise each one is read and written
     * through the providers, and a move deletes its source only once the copy
     * arrived whole. A copy that fails or is stopped takes back what it made.
     */
    suspend fun transfer(
        context: Context,
        sources: List<String>,
        dir: String,
        move: Boolean,
        onProgress: (FileOpProgress) -> Unit = {},
    ): FileOpResult {
        if (!isDoc(dir) && sources.none(::isDoc)) {
            val files = sources.map(::File)
            return if (move) FileOps.move(files, File(dir), onProgress) else FileOps.copy(files, File(dir), onProgress)
        }
        var done = 0
        var failed = 0
        val touched = ArrayList<String>()
        sources.forEachIndexed { index, source ->
            onProgress(FileOpProgress(index, sources.size, nameOf(source)))
            val node = nodeOf(context, source)
            when {
                node == null || (node.isDirectory && holds(source, dir)) -> failed++
                copyInto(context, node, dir, touched) == null -> failed++
                !move || remove(context, source, touched) -> done++
                else -> failed++
            }
        }
        onProgress(FileOpProgress(sources.size, sources.size, ""))
        return FileOpResult(done, failed, touched)
    }

    // ---- Inside ------------------------------------------------------------

    /** One thing to copy: a file or a document, whichever [path] is. */
    private data class Node(val path: String, val name: String, val isDirectory: Boolean, val mime: String)

    private fun nodeOf(context: Context, path: String): Node? {
        if (isDoc(path)) return entry(context, path)?.let { Node(it.path, it.name, it.isDirectory, it.mime) }
        val file = File(path)
        if (!file.exists() || FileOps.isSymlink(file)) return null
        return Node(file.path, file.name, file.isDirectory, FilePlaces.mimeOf(file.name).orEmpty())
    }

    private fun children(context: Context, node: Node): List<Node>? =
        if (isDoc(node.path)) {
            list(context, node.path, showHidden = true)?.map { Node(it.path, it.name, it.isDirectory, it.mime) }
        } else {
            File(node.path).listFiles()?.filterNot(FileOps::isSymlink)?.map {
                Node(it.path, it.name, it.isDirectory, FilePlaces.mimeOf(it.name).orEmpty())
            }
        }

    /** Whether [dir] is [folder] itself or inside it: a folder cannot be copied into itself. */
    private fun holds(folder: String, dir: String): Boolean {
        if (!isDoc(folder) && !isDoc(dir)) return FileOps.isInside(File(folder), File(dir))
        if (!isDoc(folder) || !isDoc(dir)) return false
        val a = Uri.parse(folder)
        val b = Uri.parse(dir)
        if (a.authority != b.authority) return false
        val outer = runCatching { DocumentsContract.getDocumentId(a) }.getOrNull() ?: return true
        val inner = runCatching { DocumentsContract.getDocumentId(b) }.getOrNull() ?: return true
        return inner == outer || inner.startsWith("$outer/")
    }

    /** Copies [node] into [dir]; what it made there, or null when nothing whole arrived (and the part that did is gone). */
    private suspend fun copyInto(context: Context, node: Node, dir: String, touched: MutableList<String>): String? {
        currentCoroutineContext().ensureActive()
        val target = (if (node.isDirectory) makeFolder(context, dir, node.name) else makeFile(context, dir, node)) ?: return null
        val whole = try {
            if (node.isDirectory) {
                val kids = children(context, node)
                kids != null && kids.all { copyInto(context, it, target, touched) != null }
            } else {
                stream(context, node.path, target)
            }
        } catch (e: CancellationException) {
            remove(context, target, ArrayList())
            throw e
        } catch (e: Exception) {
            false
        }
        if (!whole) {
            remove(context, target, ArrayList())
            return null
        }
        if (!isDoc(target)) touched += target
        return target
    }

    private fun makeFolder(context: Context, dir: String, name: String): String? =
        if (isDoc(dir)) {
            runCatching {
                DocumentsContract.createDocument(context.contentResolver, Uri.parse(dir), Document.MIME_TYPE_DIR, name)
            }.getOrNull()?.toString()
        } else {
            FileOps.uniqueTarget(File(dir), name).takeIf { it.mkdir() }?.path
        }

    /** An empty file for [node] in [dir]. A provider picks its own free name; a folder on the phone gets `name (1)`. */
    private fun makeFile(context: Context, dir: String, node: Node): String? =
        if (isDoc(dir)) {
            val mime = node.mime.ifEmpty { FilePlaces.mimeOf(node.name) ?: OCTET }
            runCatching {
                DocumentsContract.createDocument(context.contentResolver, Uri.parse(dir), mime, node.name)
            }.getOrNull()?.toString()
        } else {
            runCatching { FileOps.uniqueTarget(File(dir), node.name).takeIf { it.createNewFile() }?.path }.getOrNull()
        }

    private suspend fun stream(context: Context, from: String, to: String): Boolean {
        val scope = currentCoroutineContext()
        val input: InputStream = if (isDoc(from)) {
            context.contentResolver.openInputStream(Uri.parse(from)) ?: return false
        } else {
            File(from).inputStream()
        }
        input.use { source ->
            val output: OutputStream = if (isDoc(to)) {
                context.contentResolver.openOutputStream(Uri.parse(to), "w") ?: return false
            } else {
                File(to).outputStream()
            }
            output.use { sink ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    scope.ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                }
            }
        }
        return true
    }

    /** [path] gone, folders and all; a link on the phone goes on its own, and what it points at stays. */
    private fun remove(context: Context, path: String, touched: MutableList<String>): Boolean {
        if (isDoc(path)) {
            return runCatching { DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(path)) }.getOrDefault(false)
        }
        return removeFile(File(path), touched)
    }

    private fun removeFile(file: File, touched: MutableList<String>): Boolean {
        var whole = true
        if (file.isDirectory && !FileOps.isSymlink(file)) {
            file.listFiles()?.forEach { if (!removeFile(it, touched)) whole = false }
        }
        val gone = file.delete()
        if (gone) touched += file.path
        return gone && whole
    }

    private fun nameOf(path: String): String =
        if (isDoc(path)) Uri.parse(path).lastPathSegment?.substringAfterLast('/').orEmpty() else File(path).name

    private fun entryOf(uri: Uri, c: Cursor): FileEntry {
        val type = c.getString(2).orEmpty()
        val folder = type == Document.MIME_TYPE_DIR
        val name = c.getString(1) ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        return FileEntry(
            path = uri.toString(),
            name = name,
            isDirectory = folder,
            size = if (folder || c.isNull(3)) 0L else c.getLong(3),
            modifiedMs = if (c.isNull(4)) 0L else c.getLong(4),
            // Some providers call everything a stream of bytes; the name knows better.
            mime = when {
                folder -> ""
                type.isEmpty() || type == OCTET -> FilePlaces.mimeOf(name) ?: type
                else -> type
            },
        )
    }
}
