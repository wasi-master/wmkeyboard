package com.wasimaster.wmkeyboard.core.files

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException

/**
 * How far an operation has come: [done] of [total] things asked for, the
 * one under way, and, where the sizes are known, the bytes behind them.
 */
data class FileOpProgress(
    val done: Int,
    val total: Int,
    val name: String,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
)

/**
 * What an operation came to. [done], [failed] and [skipped] count what was
 * asked for, not what a folder held; [touched] is every path that appeared or
 * went, for the media scanner. [moves] pairs each thing moved with where it
 * went and [made] lists what a copy made, so the manager can take it back.
 */
data class FileOpResult(
    val done: Int,
    val failed: Int,
    val touched: List<String>,
    val moves: List<Pair<String, String>> = emptyList(),
    val made: List<String> = emptyList(),
    val skipped: Int = 0,
)

/** What to do when something of the same name is already where a copy or a move is going. */
enum class Clash {
    /** Leave that one where it was. */
    SKIP,

    /** The one already there goes, once the new one has arrived whole. */
    REPLACE,

    /** The new one gets a number after its name. */
    KEEP_BOTH,
}

/** Adds up bytes as a copy writes them, for a progress line in bytes rather than in files. */
class ByteCounter(val total: Long, private val report: (Long) -> Unit = {}) {
    var done: Long = 0
        private set

    fun add(bytes: Int) {
        done += bytes
        report(done)
    }
}

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
        clash: Clash = Clash.KEEP_BOTH,
    ): FileOpResult = transfer(sources, dir, move = false, clash, onProgress)

    /**
     * Moves each of [sources] into [dir]: a rename where the disk can do it
     * (the same volume), otherwise a copy, with the source deleted only once
     * every byte of the copy is there.
     */
    suspend fun move(
        sources: List<File>,
        dir: File,
        onProgress: (FileOpProgress) -> Unit = {},
        clash: Clash = Clash.KEEP_BOTH,
    ): FileOpResult = transfer(sources, dir, move = true, clash, onProgress)

    /**
     * Which of [sources] would meet a name already taken in [dir]: what to ask
     * about before a copy or a move. Moving a thing to where it already is
     * meets nothing.
     */
    fun clashes(sources: List<File>, dir: File): List<File> = sources.filter { source ->
        val there = File(dir, source.name)
        there.exists() && runCatching { there.canonicalPath != source.canonicalPath }.getOrDefault(true)
    }

    /** Bytes under [file], folders and all, links not followed: what a copy of it will write. */
    fun sizeOf(file: File): Long {
        if (isSymlink(file)) return 0
        if (!file.isDirectory) return file.length()
        return file.listFiles()?.sumOf(::sizeOf) ?: 0
    }

    private suspend fun transfer(
        sources: List<File>,
        dir: File,
        move: Boolean,
        clash: Clash,
        onProgress: (FileOpProgress) -> Unit,
    ): FileOpResult {
        var done = 0
        var failed = 0
        var skipped = 0
        val touched = ArrayList<String>()
        val moves = ArrayList<Pair<String, String>>()
        val made = ArrayList<String>()
        var index = 0
        var name = ""
        val total = sources.sumOf(::sizeOf)
        val bytes = ByteCounter(total) { b -> onProgress(FileOpProgress(index, sources.size, name, b, total)) }
        val report = { onProgress(FileOpProgress(index, sources.size, name, bytes.done, bytes.total)) }
        for (source in sources) {
            name = source.name
            report()
            val into = source.isDirectory && isInside(source, dir)
            val alreadyThere = source.parentFile?.canonicalPath == dir.canonicalPath
            val taken = File(dir, source.name).exists()
            when {
                // Moving something to where it is: nothing to do, and nothing wrong.
                move && alreadyThere -> done++
                into || !source.exists() || isSymlink(source) -> failed++
                // A copy beside itself is a duplicate, whatever was asked about clashes.
                taken && !alreadyThere && clash == Clash.SKIP -> skipped++
                else -> {
                    val replace = taken && !alreadyThere && clash == Clash.REPLACE
                    val target = uniqueTarget(dir, source.name)
                    val size = sizeOf(source)
                    if (move && source.renameTo(target)) {
                        touched += source.path
                        touched += target.path
                        val final = if (replace) settle(target, File(dir, source.name), touched) else target
                        if (final != null) {
                            moves += source.path to final.path
                            done++
                        } else {
                            failed++
                        }
                        skipBytes(bytes, size)
                    } else if (copyWhole(source, target, touched, bytes)) {
                        val final = if (replace) settle(target, File(dir, source.name), touched) else target
                        when {
                            final == null -> failed++
                            !move -> {
                                made += final.path
                                done++
                            }
                            deleteAll(source, touched) -> {
                                moves += source.path to final.path
                                done++
                            }
                            else -> {
                                made += final.path
                                failed++
                            }
                        }
                    } else {
                        failed++
                    }
                }
            }
            index++
        }
        name = ""
        report()
        return FileOpResult(done, failed, touched, moves, made, skipped)
    }

    /** A move by rename writes no bytes, but the bar should still move by its share. */
    private fun skipBytes(bytes: ByteCounter, size: Long) {
        var left = size
        while (left > 0) {
            val step = minOf(left, Int.MAX_VALUE.toLong()).toInt()
            bytes.add(step)
            left -= step
        }
    }

    /**
     * [arrived] (whole, under a spare name) takes [wanted]'s place: the old one
     * goes only now, so a failed copy never cost the file it would have
     * replaced. Null when the old one would not go, with [arrived] kept.
     */
    private fun settle(arrived: File, wanted: File, touched: MutableList<String>): File? {
        if (arrived == wanted) return arrived
        if (!deleteAll(wanted, touched)) return null
        return if (arrived.renameTo(wanted)) {
            touched += wanted.path
            wanted
        } else {
            arrived
        }
    }

    /** [copyInto], taking back whatever got across if it failed or was cancelled: that is not a copy of anything. */
    private suspend fun copyWhole(source: File, target: File, touched: MutableList<String>, bytes: ByteCounter? = null): Boolean {
        val whole = try {
            copyInto(source, target, touched, bytes)
        } catch (e: kotlinx.coroutines.CancellationException) {
            deleteAll(target, ArrayList())
            throw e
        }
        if (!whole) deleteAll(target, ArrayList())
        return whole
    }

    /** Copies [source] to [target], folders and all; true only when everything arrived whole. */
    private suspend fun copyInto(source: File, target: File, touched: MutableList<String>, bytes: ByteCounter?): Boolean {
        currentCoroutineContext().ensureActive()
        if (isSymlink(source)) return true
        if (source.isDirectory) {
            if (!target.mkdir()) return false
            val children = source.listFiles() ?: return false
            var whole = true
            for (child in children) {
                if (!copyInto(child, File(target, child.name), touched, bytes)) whole = false
            }
            return whole
        }
        val copied = try {
            copyFile(source, target, bytes)
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

    private suspend fun copyFile(source: File, target: File, bytes: ByteCounter?) {
        val context = currentCoroutineContext()
        source.inputStream().use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    context.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    bytes?.add(read)
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
