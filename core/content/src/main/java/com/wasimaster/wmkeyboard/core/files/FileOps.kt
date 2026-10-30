package com.wasimaster.wmkeyboard.core.files

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException

/** How far an operation has come, in files. */
data class FileOpProgress(val done: Int, val total: Int, val name: String)

/**
 * What an operation came to. [done] and [failed] count what was asked for, not
 * what a folder held; [touched] is every path that appeared or went, for the
 * media scanner.
 */
data class FileOpResult(val done: Int, val failed: Int, val touched: List<String>)

/**
 * The file manager's changes to the disk: copy, move, rename, new folder and
 * delete, over plain [java.io.File] so they run on the JVM in tests. Each is
 * blocking and meant for a background dispatcher; the long ones check for
 * cancellation between buffers.
 *
 * A symbolic link is treated as the link: deleting one leaves what it points
 * at, and a copy or a move skips it, so nothing here can be walked out of the
 * folder it was asked about.
 */
object FileOps {

    private const val BUFFER = 64 * 1024
    private const val NAME_BYTES_MAX = 255

    /** Whether [name] can name one file in one folder: no path in it, no "." or "..", and short enough. */
    fun validName(name: String): Boolean {
        if (name.isBlank() || name == "." || name == "..") return false
        if ('/' in name || '\u0000' in name) return false
        return name.toByteArray(Charsets.UTF_8).size <= NAME_BYTES_MAX
    }

    /** Whether [file] is a symbolic link: its real path differs from where its parent says it is. */
    fun isSymlink(file: File): Boolean = runCatching {
        val parent = file.absoluteFile.parentFile ?: return@runCatching false
        File(parent.canonicalFile, file.name).canonicalFile != File(parent.canonicalFile, file.name).absoluteFile
    }.getOrDefault(false)

    /** Whether [child] is [parent] itself or somewhere under it, by real paths. */
    fun isInside(parent: File, child: File): Boolean = runCatching {
        val root = parent.canonicalPath
        val path = child.canonicalPath
        path == root || path.startsWith(root + File.separator)
    }.getOrDefault(true)

    /** [name] in [dir], or `name (1).ext` and upward when that is taken. */
    fun uniqueTarget(dir: File, name: String): File {
        val first = File(dir, name)
        if (!first.exists()) return first
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val extension = name.substring(dot)
        var n = 1
        while (true) {
            val next = File(dir, "$stem ($n)$extension")
            if (!next.exists()) return next
            n++
        }
    }

    /** Makes [name] in [dir]. The new folder, or null when the name is no good or the disk said no. */
    fun newFolder(dir: File, name: String): File? {
        if (!validName(name)) return null
        val folder = File(dir, name.trim())
        if (folder.exists()) return null
        return folder.takeIf { it.mkdir() }
    }

    /** Renames [file] within its folder. The renamed file, or null when the name is no good or taken. */
    fun rename(file: File, name: String): File? {
        if (!validName(name)) return null
        val target = File(file.parentFile ?: return null, name.trim())
        if (target.exists()) return null
        return target.takeIf { file.renameTo(it) }
    }

    /** Copies each of [sources] into [dir], beside what is there under a new name if the name is taken. */
    suspend fun copy(
        sources: List<File>,
        dir: File,
        onProgress: (FileOpProgress) -> Unit = {},
    ): FileOpResult = transfer(sources, dir, move = false, onProgress)

    /**
     * Moves each of [sources] into [dir]: a rename where the disk can do it
     * (the same volume), otherwise a copy, with the source deleted only once
     * every byte of the copy is there.
     */
    suspend fun move(
        sources: List<File>,
        dir: File,
        onProgress: (FileOpProgress) -> Unit = {},
    ): FileOpResult = transfer(sources, dir, move = true, onProgress)

    private suspend fun transfer(
        sources: List<File>,
        dir: File,
        move: Boolean,
        onProgress: (FileOpProgress) -> Unit,
    ): FileOpResult {
        var done = 0
        var failed = 0
        val touched = ArrayList<String>()
        sources.forEachIndexed { index, source ->
            onProgress(FileOpProgress(index, sources.size, source.name))
            val into = source.isDirectory && isInside(source, dir)
            val alreadyThere = move && source.parentFile?.canonicalPath == dir.canonicalPath
            when {
                // Moving something to where it is: nothing to do, and nothing wrong.
                alreadyThere -> done++
                into || !source.exists() || isSymlink(source) -> failed++
                else -> {
                    val target = uniqueTarget(dir, source.name)
                    if (move && source.renameTo(target)) {
                        touched += source.path
                        touched += target.path
                        done++
                    } else if (copyWhole(source, target, touched)) {
                        if (!move || deleteAll(source, touched)) done++ else failed++
                    } else {
                        failed++
                    }
                }
            }
        }
        onProgress(FileOpProgress(sources.size, sources.size, ""))
        return FileOpResult(done, failed, touched)
    }

    /** [copyInto], taking back whatever got across if it failed or was cancelled: that is not a copy of anything. */
    private suspend fun copyWhole(source: File, target: File, touched: MutableList<String>): Boolean {
        val whole = try {
            copyInto(source, target, touched)
        } catch (e: kotlinx.coroutines.CancellationException) {
            deleteAll(target, ArrayList())
            throw e
        }
        if (!whole) deleteAll(target, ArrayList())
        return whole
    }

    /** Copies [source] to [target], folders and all; true only when everything arrived whole. */
    private suspend fun copyInto(source: File, target: File, touched: MutableList<String>): Boolean {
        currentCoroutineContext().ensureActive()
        if (isSymlink(source)) return true
        if (source.isDirectory) {
            if (!target.mkdir()) return false
            val children = source.listFiles() ?: return false
            var whole = true
            for (child in children) {
                if (!copyInto(child, File(target, child.name), touched)) whole = false
            }
            return whole
        }
        val copied = try {
            copyFile(source, target)
            target.length() == source.length()
        } catch (e: IOException) {
            target.delete()
            false
        } catch (e: kotlinx.coroutines.CancellationException) {
            target.delete()
            throw e
        }
        if (copied) touched += target.path else target.delete()
        return copied
    }

    private suspend fun copyFile(source: File, target: File) {
        val context = currentCoroutineContext()
        source.inputStream().use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    context.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    /** Deletes each of [files], folders and all. There is no way back from this; the panel asks first. */
    suspend fun delete(files: List<File>, onProgress: (FileOpProgress) -> Unit = {}): FileOpResult {
        var done = 0
        var failed = 0
        val touched = ArrayList<String>()
        files.forEachIndexed { index, file ->
            currentCoroutineContext().ensureActive()
            onProgress(FileOpProgress(index, files.size, file.name))
            if (deleteAll(file, touched)) done++ else failed++
        }
        onProgress(FileOpProgress(files.size, files.size, ""))
        return FileOpResult(done, failed, touched)
    }

    /** [file] and whatever it holds; a link goes on its own, and what it points at stays. */
    private fun deleteAll(file: File, touched: MutableList<String>): Boolean {
        var whole = true
        if (file.isDirectory && !isSymlink(file)) {
            file.listFiles()?.forEach { if (!deleteAll(it, touched)) whole = false }
        }
        val gone = file.delete()
        if (gone) touched += file.path
        return gone && whole
    }
}
