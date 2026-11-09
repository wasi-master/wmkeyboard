package com.wasimaster.wmkeyboard.core.voice.executorch

import com.wasimaster.wmkeyboard.core.util.Bzip2InputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Unpacks a model that comes as one zip (Vosk's, #1165) or one `.tar.bz2`
 * (k2-fsa's QNN context binaries) into its folder. The
 * archive is hash-checked whole before this runs, like any other file of a
 * model; this only lays its files out.
 *
 * Every entry lands under the model folder or the unpack fails: a name that
 * climbs out with `..` or starts at the root is refused. The single folder
 * Vosk wraps every model in (`vosk-model-small-de-0.15/`) is dropped, so the
 * model's `am/`, `conf/` and `graph/` sit straight in the folder. The files
 * go to a scratch folder first and move into place only once all of them are
 * out, so a model half unpacked is never taken for one.
 */
object EtArchive {

    /** Scratch folder inside the model folder, wiped before each unpack. */
    private const val SCRATCH = ".unpacking"

    /** The file beside [archive] that says it was unpacked whole. */
    fun mark(dir: File, archive: EtFile): File = File(dir, "${archive.name}.unpacked")

    /**
     * Unpacks [zip] (a zip or a `.tar.bz2`) into [dir], writes [archive]'s
     * mark, and deletes [zip].
     * Throws [IOException] for an archive that cannot be laid out safely.
     */
    fun unpack(zip: File, dir: File, archive: EtFile) {
        val scratch = File(dir, SCRATCH)
        scratch.deleteRecursively()
        check(scratch.mkdirs()) { "could not make $scratch" }
        try {
            val names = extract(zip, scratch)
            val strip = commonFolder(names)
            val from = if (strip != null) File(scratch, strip) else scratch
            for (child in from.listFiles().orEmpty()) {
                val target = File(dir, child.name)
                target.deleteRecursively()
                if (!child.renameTo(target)) throw IOException("could not move ${child.name} into place")
            }
            mark(dir, archive).writeText(archive.sha256)
            zip.delete()
        } finally {
            scratch.deleteRecursively()
        }
    }

    /**
     * Extracts [archive] under [into]: a zip, or a bzip2 tar when its name
     * ends in `.tar.bz2`. Returns the entry names, folders with their
     * trailing slash dropped.
     */
    internal fun extract(archive: File, into: File): List<String> {
        val root = into.canonicalFile
        val names = ArrayList<String>()
        fun place(rawName: String, directory: Boolean, body: InputStream) {
            val name = rawName.replace('\\', '/').trimEnd('/')
            if (name.isEmpty()) return
            val target = File(root, name).canonicalFile
            if (name.startsWith("/") || !target.path.startsWith(root.path + File.separator)) {
                throw IOException("archive entry outside the model folder: $rawName")
            }
            names += name
            if (directory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                target.outputStream().use { body.copyTo(it) }
            }
        }
        if (archive.name.endsWith(".tar.bz2")) {
            Bzip2InputStream(archive.inputStream().buffered(1 shl 16)).use { untar(it, ::place) }
        } else {
            ZipInputStream(archive.inputStream().buffered()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    place(entry.name, entry.isDirectory, input)
                }
            }
        }
        return names
    }

    /**
     * Hands each regular file and folder of a ustar (or old-style) tar to
     * [place], with a stream of exactly that entry's bytes. Links and other
     * entry types are skipped, never followed.
     */
    private fun untar(input: InputStream, place: (String, Boolean, InputStream) -> Unit) {
        val header = ByteArray(TAR_BLOCK)
        while (true) {
            if (!readBlock(input, header)) return
            if (header.all { it == 0.toByte() }) return
            val name = String(header, 0, 100, Charsets.UTF_8).substringBefore('\u0000')
            val ustar = String(header, 257, 5, Charsets.US_ASCII) == "ustar"
            val prefix = if (ustar) String(header, 345, 155, Charsets.UTF_8).substringBefore('\u0000') else ""
            val size = String(header, 124, 12, Charsets.US_ASCII).trim('\u0000', ' ').toLongOrNull(8)
                ?: throw IOException("bad tar header")
            val type = header[156].toInt().toChar()
            val full = if (prefix.isNotEmpty()) "$prefix/$name" else name
            val body = Bounded(input, size)
            when (type) {
                '0', '\u0000' -> place(full, false, body)
                '5' -> place(full, true, body)
            }
            body.skipRest()
            val pad = ((TAR_BLOCK - size % TAR_BLOCK) % TAR_BLOCK).toInt()
            if (pad > 0 && !readBlock(input, ByteArray(pad))) throw IOException("tar cut off")
        }
    }

    /** Fills [into] whole; false at a clean end before its first byte. */
    private fun readBlock(input: InputStream, into: ByteArray): Boolean {
        var at = 0
        while (at < into.size) {
            val n = input.read(into, at, into.size - at)
            if (n < 0) {
                if (at == 0) return false
                throw IOException("tar cut off")
            }
            at += n
        }
        return true
    }

    /** The next [left] bytes of [input], and no more; closing it leaves [input] open. */
    private class Bounded(private val input: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val b = input.read()
            if (b < 0) throw IOException("tar cut off")
            left--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = input.read(b, off, minOf(len.toLong(), left).toInt())
            if (n < 0) throw IOException("tar cut off")
            left -= n
            return n
        }

        fun skipRest() {
            val buffer = ByteArray(1 shl 16)
            while (left > 0) read(buffer, 0, buffer.size)
        }

        override fun close() = Unit
    }

    private const val TAR_BLOCK = 512

    /** The one top-level folder every entry of [names] is under, or null when there is none. */
    internal fun commonFolder(names: List<String>): String? {
        if (names.isEmpty()) return null
        val top = names.map { it.substringBefore('/') }.distinct().singleOrNull() ?: return null
        // A lone file at the top is not a folder to strip.
        return top.takeIf { names.any { it.startsWith("$top/") } }
    }
}
