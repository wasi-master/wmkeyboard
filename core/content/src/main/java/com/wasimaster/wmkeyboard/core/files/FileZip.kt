package com.wasimaster.wmkeyboard.core.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Zip and unzip for the file manager, over streams so the same code packs
 * files on the phone and documents behind a provider ([DocFiles] supplies
 * those). The `java.util.zip` reader and writer only: no other archive format.
 *
 * Unpacking never writes outside the folder it was given. An entry whose name
 * climbs out (`../`), starts at the root or names a drive is skipped, which is
 * the zip-slip trap every unzipper has to close.
 */
object FileZip {

    private const val BUFFER = 64 * 1024

    /** The most rows a zip's preview lists. */
    const val LIST_CAP = 2000

    /** One thing to pack: a file to read, or a folder whose [children] go in under its name. */
    interface Source {
        val name: String
        val isDirectory: Boolean

        /** Null when the folder cannot be read, which fails the zip rather than leaving a hole in it. */
        fun children(): List<Source>?
        fun open(): InputStream
    }

    /** Where unpacked entries go, by their path inside the zip, always `/`-separated and already made safe. */
    interface Sink {
        fun folder(relative: String): Boolean
        fun file(relative: String): OutputStream?
    }

    /** One row of a zip's listing. [size] is -1 when the zip does not say. */
    data class Item(val path: String, val isDirectory: Boolean, val size: Long, val modifiedMs: Long)

    /** A file or folder on the phone as a [Source]; a link is left out, as a copy leaves it out. */
    fun fileSource(file: File): Source = object : Source {
        override val name: String = file.name
        override val isDirectory: Boolean = file.isDirectory
        override fun children(): List<Source>? = file.listFiles()?.filterNot(FileOps::isSymlink)?.map(::fileSource)
        override fun open(): InputStream = file.inputStream()
    }

    /**
     * Writes [sources] into a zip on [out], each under its own name, folders
     * and all. [onBytes] hears the bytes read so far and the entry under way.
     * Throws when anything cannot be read: half a zip is no zip.
     */
    suspend fun write(out: OutputStream, sources: List<Source>, onBytes: (Long, String) -> Unit = { _, _ -> }) {
        val context = currentCoroutineContext()
        var bytes = 0L
        val buffer = ByteArray(BUFFER)
        ZipOutputStream(out).use { zip ->
            val used = HashSet<String>()
            suspend fun add(source: Source, prefix: String) {
                context.ensureActive()
                val path = unique(prefix + source.name, used, source.isDirectory)
                if (source.isDirectory) {
                    zip.putNextEntry(ZipEntry("$path/"))
                    zip.closeEntry()
                    val kids = source.children() ?: throw IOException("unreadable folder ${source.name}")
                    for (kid in kids) add(kid, "$path/")
                } else {
                    zip.putNextEntry(ZipEntry(path))
                    source.open().use { input ->
                        while (true) {
                            context.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            zip.write(buffer, 0, read)
                            bytes += read
                            onBytes(bytes, source.name)
                        }
                    }
                    zip.closeEntry()
                }
            }
            for (source in sources) add(source, "")
        }
    }

    /** Two sources of the same name at one level would make a zip most readers refuse: the second gets a number. */
    private fun unique(path: String, used: MutableSet<String>, folder: Boolean): String {
        if (used.add(path)) return path
        val dot = if (folder) -1 else path.lastIndexOf('.').takeIf { it > path.lastIndexOf('/') + 1 } ?: -1
        val stem = if (dot > 0) path.substring(0, dot) else path
        val ext = if (dot > 0) path.substring(dot) else ""
        var n = 1
        while (true) {
            val next = "$stem ($n)$ext"
            if (used.add(next)) return next
            n++
        }
    }

    /**
     * The path inside the zip an entry may be unpacked at, or null when it
     * would land outside the folder or names nothing: no root, no drive, no
     * `..` step, backslashes read as the separators Windows zips mean.
     */
    fun safePath(name: String): String? {
        val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null
        if (name.startsWith("/") || name.startsWith("\\")) return null
        if (parts.first().length == 2 && parts.first()[1] == ':') return null
        if (parts.any { it == ".." || '\u0000' in it }) return null
        if (parts.any { !FileOps.validName(it) }) return null
        return parts.joinToString("/")
    }

    /**
     * Reads the zip on [input] into [sink]. False when any entry could not be
     * written; entries that were not safe are skipped and do not fail it.
     * [onBytes] hears the compressed bytes read so far, which is what a file
     * size is measured in, and the entry under way.
     */
    suspend fun read(input: InputStream, sink: Sink, onBytes: (Long, String) -> Unit = { _, _ -> }): Boolean {
        val context = currentCoroutineContext()
        val counting = CountingInput(input)
        val buffer = ByteArray(BUFFER)
        var whole = true
        ZipInputStream(counting).use { zip ->
            while (true) {
                context.ensureActive()
                val entry = zip.nextEntry ?: break
                val path = safePath(entry.name)
                if (path == null) {
                    zip.closeEntry()
                    continue
                }
                if (entry.isDirectory) {
                    if (!sink.folder(path)) whole = false
                } else {
                    val out = sink.file(path)
                    if (out == null) {
                        whole = false
                    } else {
                        out.use {
                            while (true) {
                                context.ensureActive()
                                val read = zip.read(buffer)
                                if (read < 0) break
                                it.write(buffer, 0, read)
                                onBytes(counting.count, path.substringAfterLast('/'))
                            }
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        return whole
    }

    /** What the zip on [input] holds, in its own order, up to [cap] rows. */
    fun entries(input: InputStream, cap: Int = LIST_CAP): List<Item> {
        val out = ArrayList<Item>()
        ZipInputStream(input).use { zip ->
            while (out.size < cap) {
                val entry = zip.nextEntry ?: break
                val path = entry.name.replace('\\', '/').trimEnd('/')
                if (path.isNotEmpty()) out += Item(path, entry.isDirectory, entry.size, entry.time.coerceAtLeast(0))
                zip.closeEntry()
            }
        }
        return out
    }

    /** Packs [sources] into the zip file [target]; false (and no [target] left) when any part failed. */
    suspend fun zip(sources: List<File>, target: File, onBytes: (Long, String) -> Unit = { _, _ -> }): Boolean {
        val ok = try {
            target.outputStream().use { write(it, sources.filterNot(FileOps::isSymlink).map(::fileSource), onBytes) }
            true
        } catch (e: CancellationException) {
            target.delete()
            throw e
        } catch (e: IOException) {
            false
        }
        if (!ok) target.delete()
        return ok
    }

    /**
     * Unpacks [archive] into a new folder named after it in [dir]; the folder,
     * or null (and nothing left) when the zip could not be read whole.
     */
    suspend fun unzip(archive: File, dir: File, onBytes: (Long, String) -> Unit = { _, _ -> }): File? {
        val folder = FileOps.uniqueTarget(dir, archive.name.substringBeforeLast('.').ifEmpty { archive.name })
        if (!folder.mkdirs()) return null
        val sink = object : Sink {
            override fun folder(relative: String): Boolean = File(folder, relative).let { it.isDirectory || it.mkdirs() }

            override fun file(relative: String): OutputStream? {
                val target = File(folder, relative)
                // The safe path already cannot climb out; this holds even against a link inside the new folder.
                if (!FileOps.isInside(folder, target.parentFile ?: return null)) return null
                target.parentFile?.mkdirs()
                return runCatching { FileOps.uniqueTarget(target.parentFile!!, target.name).outputStream() }.getOrNull()
            }
        }
        val ok = try {
            archive.inputStream().use { read(it, sink, onBytes) }
        } catch (e: CancellationException) {
            folder.deleteRecursively()
            throw e
        } catch (e: IOException) {
            false
        }
        // A file that is no zip at all reads as an empty one: that is a failure too, not an empty folder.
        if (!ok || folder.list().isNullOrEmpty()) {
            folder.deleteRecursively()
            return null
        }
        return folder
    }

    /** Counts the bytes read through it, for a progress line in the zip's own size. */
    private class CountingInput(private val inner: InputStream) : InputStream() {
        var count = 0L
            private set

        override fun read(): Int = inner.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count += it }

        override fun close() = inner.close()
    }
}
