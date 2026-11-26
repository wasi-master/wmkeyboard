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
 *
 * A folder on a server or in a cloud ([RemoteFiles]) is a `content://` path
 * too, so it travels the same way; every call here sends one to
 * [RemoteFiles] instead of a document provider, and a copy onto one goes
 * through [RemoteFiles.write], which uploads under a temporary name and
 * names the file only once it is whole.
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
    fun entry(context: Context, path: String): FileEntry? = if (RemoteFiles.isRemote(path)) RemoteFiles.entry(context, path) else runCatching {
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
        if (RemoteFiles.isRemote(path)) {
            val found = RemoteFiles.list(context, path) ?: return null
            return FileStore.sorted(
                found.filter { (showHidden || !it.name.startsWith(".")) && (it.isDirectory || FileStore.mimeMatches(it.mime, mimes)) },
                sort,
                descending,
            )
        }
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
    ): List<FileEntry> = search(context, path, FileQuery(query), showHidden, mimes, cap)

    /**
     * [FileStore.search] for a document folder: what [query] takes under
     * [path], handed to [onBatch] after each folder so results show as they
     * come in. A text file's words are read only when the query asks.
     */
    suspend fun search(
        context: Context,
        path: String,
        query: FileQuery,
        showHidden: Boolean = false,
        mimes: List<String> = emptyList(),
        cap: Int = FileStore.SEARCH_CAP,
        onBatch: (List<FileEntry>) -> Unit = {},
    ): List<FileEntry> {
        if (query.isEmpty) return emptyList()
        val found = ArrayList<FileEntry>()
        val pending = ArrayDeque<Pair<String, Int>>()
        pending.addLast(path to 0)
        while (pending.isNotEmpty() && found.size < cap) {
            currentCoroutineContext().ensureActive()
            val (dir, depth) = pending.removeFirst()
            val children = list(context, dir, showHidden = showHidden) ?: continue
            val before = found.size
            for (child in children) {
                if (child.isDirectory && depth < SEARCH_DEPTH) pending.addLast(child.path to depth + 1)
                if (!child.isDirectory && !FileStore.mimeMatches(child.mime, mimes)) continue
                if (!query.admits(child)) continue
                val hit = query.nameMatches(child.name) ||
                    (query.inContents && !child.isDirectory && FileStore.contentMatches(child, query) { input(context, child.path)!! })
                if (hit) {
                    found += child
                    if (found.size >= cap) break
                }
            }
            if (found.size > before) onBatch(found.toList())
        }
        return found
    }

    /** Makes [name] in the folder [dir]. The new folder, or null when the name is no good, taken, or refused. */
    fun newFolder(context: Context, dir: String, name: String): String? {
        if (!FileOps.validName(name)) return null
        val clean = name.trim()
        if (RemoteFiles.isRemote(dir)) return RemoteFiles.makeFolder(context, dir, clean, unique = false)
        if (list(context, dir, showHidden = true)?.any { it.name == clean } != false) return null
        return runCatching {
            DocumentsContract.createDocument(context.contentResolver, Uri.parse(dir), Document.MIME_TYPE_DIR, clean)
        }.getOrNull()?.toString()
    }

    /** Renames [path] where it is. Its new name, which a provider may hand back as a new URI; null when refused. */
    fun rename(context: Context, path: String, name: String): String? {
        if (!FileOps.validName(name)) return null
        if (RemoteFiles.isRemote(path)) return RemoteFiles.rename(context, path, name.trim())
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
     * [clash] says what happens to a name already taken in [dir].
     */
    suspend fun transfer(
        context: Context,
        sources: List<String>,
        dir: String,
        move: Boolean,
        onProgress: (FileOpProgress) -> Unit = {},
        clash: Clash = Clash.KEEP_BOTH,
    ): FileOpResult {
        if (!isDoc(dir) && sources.none(::isDoc)) {
            val files = sources.map(::File)
            return if (move) FileOps.move(files, File(dir), onProgress, clash) else FileOps.copy(files, File(dir), onProgress, clash)
        }
        var done = 0
        var failed = 0
        var skipped = 0
        val touched = ArrayList<String>()
        val moves = ArrayList<Pair<String, String>>()
        val made = ArrayList<String>()
        val nodes = sources.map { nodeOf(context, it) }
        val total = nodes.sumOf { node -> node?.let { sizeOf(context, it) } ?: 0L }
        var index = 0
        var name = ""
        val bytes = ByteCounter(total) { b -> onProgress(FileOpProgress(index, sources.size, name, b, total)) }
        val taken = namesIn(context, dir)
        sources.forEachIndexed { i, source ->
            index = i
            name = nameOf(source)
            onProgress(FileOpProgress(i, sources.size, name, bytes.done, total))
            val node = nodes[i]
            val clashes = node != null && node.name in taken
            when {
                node == null || (node.isDirectory && holds(source, dir)) -> failed++
                clashes && clash == Clash.SKIP -> skipped++
                // Within one server, the server moves it: nothing is downloaded or lost on the way.
                move && !clashes && RemoteFiles.moveWithin(context, source, dir)?.also { moves += source to it } != null -> {
                    bytes.add(node.size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    done++
                }
                else -> {
                    val copied = copyInto(context, node, dir, touched, bytes)
                    val final = when {
                        copied == null -> null
                        clashes && clash == Clash.REPLACE -> settle(context, copied, dir, node.name, touched)
                        else -> copied
                    }
                    when {
                        final == null -> failed++
                        !move -> {
                            made += final
                            done++
                        }
                        remove(context, source, touched) -> {
                            moves += source to final
                            done++
                        }
                        else -> {
                            made += final
                            failed++
                        }
                    }
                }
            }
        }
        onProgress(FileOpProgress(sources.size, sources.size, "", bytes.done, total))
        return FileOpResult(done, failed, touched, moves, made, skipped)
    }

    /** Which of [sources] would meet a name already taken in [dir]. */
    fun clashes(context: Context, sources: List<String>, dir: String): List<String> {
        if (!isDoc(dir) && sources.none(::isDoc)) return FileOps.clashes(sources.map(::File), File(dir)).map { it.path }
        val taken = namesIn(context, dir)
        return sources.filter { source -> source != dir && nameOf(context, source) in taken && parentHolds(dir, source).not() }
    }

    /** A doc source already sitting in [dir] is a duplicate, not a clash. */
    private fun parentHolds(dir: String, source: String): Boolean =
        !isDoc(source) && !isDoc(dir) && File(source).parent == File(dir).path

    private fun namesIn(context: Context, dir: String): Set<String> =
        if (isDoc(dir)) {
            list(context, dir, showHidden = true)?.map { it.name }?.toSet().orEmpty()
        } else {
            File(dir).list()?.toSet().orEmpty()
        }

    /** The name [path] goes by: its provider's display name for a document. */
    fun nameOf(context: Context, path: String): String =
        if (isDoc(path)) entry(context, path)?.name ?: nameOf(path) else File(path).name

    /**
     * [arrived] takes the place of [name] in [dir]: the old one goes only once
     * the new one is whole, then the new one takes the name. Null when the old
     * one would not go.
     */
    private fun settle(context: Context, arrived: String, dir: String, name: String, touched: MutableList<String>): String? {
        val old = childNamed(context, dir, name) ?: return arrived
        if (old == arrived) return arrived
        if (!remove(context, old, touched)) return null
        return if (isDoc(arrived)) {
            rename(context, arrived, name) ?: arrived
        } else {
            val target = File(dir, name)
            if (File(arrived).renameTo(target)) target.path.also { touched += it } else arrived
        }
    }

    private fun childNamed(context: Context, dir: String, name: String): String? =
        if (isDoc(dir)) {
            list(context, dir, showHidden = true)?.firstOrNull { it.name == name }?.path
        } else {
            File(dir, name).takeIf { it.exists() }?.path
        }

    /** Bytes under [node], folders and all: what copying it will write. */
    private fun sizeOf(context: Context, node: Node): Long {
        if (!node.isDirectory) return node.size
        return children(context, node)?.sumOf { sizeOf(context, it) } ?: 0
    }

    // ---- Zip ---------------------------------------------------------------

    /**
     * Packs [sources] (files, folders, documents, in any mix) into one zip
     * called [name] in [dir]. The zip's path, or null when it could not be
     * made whole, in which case nothing of it is left behind.
     */
    suspend fun zip(
        context: Context,
        sources: List<String>,
        dir: String,
        name: String,
        onProgress: (FileOpProgress) -> Unit = {},
    ): String? {
        val nodes = sources.mapNotNull { nodeOf(context, it) }
        if (nodes.isEmpty()) return null
        val zipName = if (name.endsWith(".zip", ignoreCase = true)) name else "$name.zip"
        if (RemoteFiles.isRemote(dir)) return zipOnto(context, nodes, dir, zipName, onProgress)
        val target = makeFile(context, dir, Node("", zipName, false, "application/zip", 0)) ?: return null
        val total = nodes.sumOf { sizeOf(context, it) }
        val whole = try {
            output(context, target)?.use { out ->
                FileZip.write(out, nodes.map { zipSource(context, it) }) { b, n ->
                    onProgress(FileOpProgress(0, 1, n, b, total))
                }
                true
            } ?: false
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
        return target
    }

    /**
     * A zip for a server: packed into a local file first, then uploaded the
     * way every file onto a server is, so a zip that fails halfway leaves
     * nothing there.
     */
    private suspend fun zipOnto(
        context: Context,
        nodes: List<Node>,
        dir: String,
        zipName: String,
        onProgress: (FileOpProgress) -> Unit,
    ): String? {
        val total = nodes.sumOf { sizeOf(context, it) }
        val scope = currentCoroutineContext()
        // Packed here, where it can be stopped, since the writer's body is
        // not a coroutine; the writer then sends this file as it is.
        val packed = File.createTempFile("zip", ".zip", context.cacheDir)
        try {
            packed.outputStream().buffered().use { out ->
                FileZip.write(out, nodes.map { zipSource(context, it) }) { b, n ->
                    onProgress(FileOpProgress(0, 1, n, b, total))
                }
            }
            scope.ensureActive()
            return RemoteFiles.write(
                context,
                dir,
                zipName,
                body = { out -> packed.inputStream().use { it.copyTo(out) } },
                check = { scope.ensureActive() },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        } finally {
            packed.delete()
        }
    }

    /**
     * Unpacks the zip at [archive] into a new folder named after it inside
     * [dir], so nothing already there is touched. The new folder, or null
     * when the zip could not be read whole (and what was unpacked is gone).
     */
    suspend fun unzip(
        context: Context,
        archive: String,
        dir: String,
        onProgress: (FileOpProgress) -> Unit = {},
    ): String? {
        if (RemoteFiles.isRemote(dir)) return unzipOnto(context, archive, dir, onProgress)
        val node = nodeOf(context, archive) ?: return null
        val stem = node.name.substringBeforeLast('.').ifEmpty { node.name }
        val folder = makeFolder(context, dir, stem) ?: return null
        val folders = HashMap<String, String>()
        folders[""] = folder
        val sink = object : FileZip.Sink {
            override fun folder(relative: String): Boolean = folderFor(relative) != null

            override fun file(relative: String): java.io.OutputStream? {
                val parent = folderFor(relative.substringBeforeLast('/', "")) ?: return null
                val leaf = relative.substringAfterLast('/')
                val made = makeFile(context, parent, Node("", leaf, false, FilePlaces.mimeOf(leaf) ?: OCTET, 0)) ?: return null
                return output(context, made)
            }

            private fun folderFor(relative: String): String? {
                if (relative.isEmpty()) return folder
                folders[relative]?.let { return it }
                val parent = folderFor(relative.substringBeforeLast('/', "")) ?: return null
                val made = makeFolder(context, parent, relative.substringAfterLast('/')) ?: return null
                folders[relative] = made
                return made
            }
        }
        val ok = try {
            input(context, archive)?.use { input ->
                FileZip.read(input, sink) { b, n -> onProgress(FileOpProgress(0, 1, n, b, node.size)) }
            } ?: false
        } catch (e: CancellationException) {
            remove(context, folder, ArrayList())
            throw e
        } catch (e: Exception) {
            false
        }
        if (!ok || namesIn(context, folder).isEmpty()) {
            remove(context, folder, ArrayList())
            return null
        }
        return folder
    }

    /**
     * Unpacks onto a server: into a folder in the cache, then copied up as a
     * whole folder, which [copyInto] takes back if any of it fails.
     */
    private suspend fun unzipOnto(
        context: Context,
        archive: String,
        dir: String,
        onProgress: (FileOpProgress) -> Unit,
    ): String? {
        val scratch = File(context.cacheDir, "remote-unzip/${System.nanoTime()}").apply { mkdirs() }
        try {
            val local = unzip(context, archive, scratch.path, onProgress) ?: return null
            val node = nodeOf(context, local) ?: return null
            return copyInto(context, node, dir, ArrayList(), ByteCounter(sizeOf(context, node)) { b -> onProgress(FileOpProgress(0, 1, node.name, b, 0)) })
        } finally {
            scratch.deleteRecursively()
        }
    }

    /** What the zip at [path] holds, for its preview; null when it is not one, or unreadable. */
    fun zipEntries(context: Context, path: String, cap: Int = FileZip.LIST_CAP): List<FileZip.Item>? = runCatching {
        input(context, path)?.use { FileZip.entries(it, cap) }
    }.getOrNull()

    private fun zipSource(context: Context, node: Node): FileZip.Source = object : FileZip.Source {
        override val name = node.name
        override val isDirectory = node.isDirectory
        override fun children(): List<FileZip.Source>? = children(context, node)?.map { zipSource(context, it) }
        override fun open(): InputStream = input(context, node.path) ?: throw java.io.IOException("unreadable")
    }

    // ---- Facts -------------------------------------------------------------

    /** Size, files and folders under [path], counted as it walks; [onProgress] hears each folder's running total. */
    suspend fun tally(context: Context, path: String, onProgress: (FileInfo.Tally) -> Unit = {}): FileInfo.Tally {
        if (!isDoc(path)) return FileInfo.tally(File(path), FilePlaces::mimeOf, onProgress)
        var tally = FileInfo.Tally()
        val pending = ArrayDeque<String>()
        pending.addLast(path)
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val dir = pending.removeFirst()
            for (child in list(context, dir, showHidden = true).orEmpty()) {
                tally = if (child.isDirectory) {
                    pending.addLast(child.path)
                    tally.copy(folders = tally.folders + 1)
                } else {
                    tally.plus(FileKinds.of(child), child.size)
                }
            }
            onProgress(tally)
        }
        return tally
    }

    /** The SHA-256 of the file at [path], in lower-case hex; null when it cannot be read. */
    suspend fun sha256(context: Context, path: String): String? =
        runCatching { input(context, path)?.use { FileInfo.sha256(it) } }.getOrNull()

    /** The first [max] bytes of a text file, as words, for its preview and for typing into a field. */
    fun readText(context: Context, path: String, max: Int): String? = runCatching {
        (if (RemoteFiles.isRemote(path)) RemoteFiles.input(context, path, max.toLong()) else input(context, path))?.use { input ->
            val buffer = ByteArray(max)
            var read = 0
            while (read < max) {
                val n = input.read(buffer, read, max - read)
                if (n < 0) break
                read += n
            }
            String(buffer, 0, read, Charsets.UTF_8)
        }
    }.getOrNull()

    /** The file or document at [path], opened to read. */
    fun input(context: Context, path: String): InputStream? = when {
        RemoteFiles.isRemote(path) -> RemoteFiles.input(context, path)
        isDoc(path) -> context.contentResolver.openInputStream(Uri.parse(path))
        else -> File(path).inputStream()
    }

    /**
     * The file or document at [path], opened to write over from the start.
     * Null for one on a server: a stream gives no way to say the upload
     * failed, so those are written whole through [RemoteFiles.write].
     */
    fun output(context: Context, path: String): OutputStream? = when {
        RemoteFiles.isRemote(path) -> null
        isDoc(path) -> context.contentResolver.openOutputStream(Uri.parse(path), "w")
        else -> File(path).outputStream()
    }

    // ---- Inside ------------------------------------------------------------

    /** One thing to copy: a file or a document, whichever [path] is. */
    private data class Node(val path: String, val name: String, val isDirectory: Boolean, val mime: String, val size: Long)

    private fun nodeOf(context: Context, path: String): Node? {
        if (isDoc(path)) return entry(context, path)?.let { Node(it.path, it.name, it.isDirectory, it.mime, it.size) }
        val file = File(path)
        if (!file.exists() || FileOps.isSymlink(file)) return null
        return Node(file.path, file.name, file.isDirectory, FilePlaces.mimeOf(file.name).orEmpty(), if (file.isDirectory) 0 else file.length())
    }

    private fun children(context: Context, node: Node): List<Node>? =
        if (isDoc(node.path)) {
            list(context, node.path, showHidden = true)?.map { Node(it.path, it.name, it.isDirectory, it.mime, it.size) }
        } else {
            File(node.path).listFiles()?.filterNot(FileOps::isSymlink)?.map {
                Node(it.path, it.name, it.isDirectory, FilePlaces.mimeOf(it.name).orEmpty(), if (it.isDirectory) 0 else it.length())
            }
        }

    /** Whether [dir] is [folder] itself or inside it: a folder cannot be copied into itself. */
    private fun holds(folder: String, dir: String): Boolean {
        if (RemoteFiles.isRemote(folder) || RemoteFiles.isRemote(dir)) {
            val (a, outer) = RemoteFiles.parse(folder) ?: return false
            val (b, inner) = RemoteFiles.parse(dir) ?: return false
            return a == b && inner.size >= outer.size && inner.subList(0, outer.size) == outer
        }
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
    private suspend fun copyInto(context: Context, node: Node, dir: String, touched: MutableList<String>, bytes: ByteCounter? = null): String? {
        currentCoroutineContext().ensureActive()
        if (RemoteFiles.isRemote(dir) && !node.isDirectory) return upload(context, node, dir, bytes)
        val target = (if (node.isDirectory) makeFolder(context, dir, node.name) else makeFile(context, dir, node)) ?: return null
        val whole = try {
            if (node.isDirectory) {
                val kids = children(context, node)
                kids != null && kids.all { copyInto(context, it, target, touched, bytes) != null }
            } else {
                stream(context, node.path, target, bytes)
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

    /**
     * One file onto a server: read whole into a local copy, uploaded under a
     * temporary name, checked, then named. Null when anything failed, with
     * nothing of it left on the server.
     */
    private suspend fun upload(context: Context, node: Node, dir: String, bytes: ByteCounter?): String? {
        val scope = currentCoroutineContext()
        return RemoteFiles.write(
            context,
            dir,
            node.name,
            body = { out ->
                val source = input(context, node.path) ?: throw java.io.IOException("unreadable")
                source.use { it.copyTo(out, BUFFER) }
            },
            onSent = { n -> bytes?.add(n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) },
            check = { scope.ensureActive() },
        )
    }

    private fun makeFolder(context: Context, dir: String, name: String): String? =
        if (RemoteFiles.isRemote(dir)) {
            RemoteFiles.makeFolder(context, dir, name, unique = true)
        } else if (isDoc(dir)) {
            runCatching {
                DocumentsContract.createDocument(context.contentResolver, Uri.parse(dir), Document.MIME_TYPE_DIR, name)
            }.getOrNull()?.toString()
        } else {
            FileOps.uniqueTarget(File(dir), name).takeIf { it.mkdir() }?.path
        }

    /** An empty file for [node] in [dir]. A provider picks its own free name; a folder on the phone gets `name (1)`. */
    private fun makeFile(context: Context, dir: String, node: Node): String? =
        if (RemoteFiles.isRemote(dir)) {
            // Files reach a server whole, through [upload]; an empty one first would be half a file.
            null
        } else if (isDoc(dir)) {
            val mime = node.mime.ifEmpty { FilePlaces.mimeOf(node.name) ?: OCTET }
            runCatching {
                DocumentsContract.createDocument(context.contentResolver, Uri.parse(dir), mime, node.name)
            }.getOrNull()?.toString()
        } else {
            runCatching { FileOps.uniqueTarget(File(dir), node.name).takeIf { it.createNewFile() }?.path }.getOrNull()
        }

    private suspend fun stream(context: Context, from: String, to: String, bytes: ByteCounter?): Boolean {
        val scope = currentCoroutineContext()
        val input: InputStream = input(context, from) ?: return false
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
                    bytes?.add(read)
                }
            }
        }
        return true
    }

    /** [path] gone, folders and all; a link on the phone goes on its own, and what it points at stays. */
    private fun remove(context: Context, path: String, touched: MutableList<String>): Boolean {
        if (RemoteFiles.isRemote(path)) return RemoteFiles.remove(context, path)
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
        if (RemoteFiles.isRemote(path)) RemoteFiles.nameOf(path) else if (isDoc(path)) Uri.parse(path).lastPathSegment?.substringAfterLast('/').orEmpty() else File(path).name

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
