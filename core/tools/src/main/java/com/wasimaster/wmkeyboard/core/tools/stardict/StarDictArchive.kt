package com.wasimaster.wmkeyboard.core.tools.stardict

import com.wasimaster.wmkeyboard.core.util.Bzip2InputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Gets a StarDict book's files out of whatever it was shipped in: a `.zip`, a `.tar`,
 * or a `.tar.gz` or `.tar.bz2`. Only the book's own files come out, flat, whatever
 * folder they were in, so a name in an archive can't reach outside [unpack]'s folder.
 * No Android classes, so it runs in the JVM tests.
 */
object StarDictArchive {

    /** Past this, unpacked, the archive is not a dictionary but a mistake (or a bomb). */
    const val MAX_UNPACKED_BYTES = 4L * 1024 * 1024 * 1024

    private const val TAR_BLOCK = 512
    private const val TAR_MAGIC_AT = 257
    private const val TAR_SIZE_AT = 124
    private const val TAR_SIZE_LEN = 12
    private const val TAR_TYPE_AT = 156
    private const val TAR_NAME_LEN = 100
    private const val TAR_PREFIX_AT = 345
    private const val TAR_PREFIX_LEN = 155
    private const val LONG_NAME_TYPE = 'L'
    private const val PAX_TYPE = 'x'
    private const val PAX_GLOBAL_TYPE = 'g'
    private const val MAX_LONG_NAME = 64 * 1024

    private val BOOK_SUFFIXES = listOf(".ifo", ".idx", ".idx.gz", ".idx.dz", ".dict", ".dict.dz", ".syn", ".syn.gz", ".syn.dz")

    /** Whether [name] is one of a book's files, by its last path segment. */
    fun isBookFile(name: String): Boolean {
        val leaf = name.substringAfterLast('/').lowercase(Locale.ROOT)
        return !leaf.startsWith(".") && BOOK_SUFFIXES.any(leaf::endsWith)
    }

    /** Whether these names hold a whole book: an `.ifo`, an index and a body. */
    fun holdsBook(names: Collection<String>): Boolean {
        val leaves = names.map { it.substringAfterLast('/').lowercase(Locale.ROOT) }
        return leaves.any { it.endsWith(".ifo") } &&
            leaves.any { it.endsWith(".idx") || it.endsWith(".idx.gz") || it.endsWith(".idx.dz") } &&
            leaves.any { it.endsWith(".dict") || it.endsWith(".dict.dz") }
    }

    /** Whether [name] says it is a tar, as the books that aren't zips are shipped. */
    fun isTarName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".tar") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz") ||
            lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") || lower.endsWith(".tbz")
    }

    /** Unpacks [archive]'s book files into [into]; returns their names. Reads the format from its first bytes. */
    @Throws(IOException::class)
    fun unpack(archive: File, into: File): List<String> {
        into.mkdirs()
        val head = ByteArray(3)
        val got = archive.inputStream().use { it.read(head) }
        return archive.inputStream().buffered().use { raw ->
            when {
                got >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> unpackZip(raw, into)
                got >= 2 && head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte() -> unpackTar(GZIPInputStream(raw), into)
                got == 3 && head[0] == 'B'.code.toByte() && head[1] == 'Z'.code.toByte() && head[2] == 'h'.code.toByte() ->
                    unpackTar(Bzip2InputStream(raw), into)
                else -> unpackTar(raw, into)
            }
        }
    }

    private fun unpackZip(input: InputStream, into: File): List<String> {
        val names = ArrayList<String>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || entry.name.startsWith("__MACOSX/") || !isBookFile(entry.name)) continue
                val leaf = entry.name.substringAfterLast('/')
                total += copy(zip, File(into, leaf), MAX_UNPACKED_BYTES - total, Long.MAX_VALUE)
                names += leaf
            }
        }
        return names
    }

    private fun unpackTar(input: InputStream, into: File): List<String> {
        val names = ArrayList<String>()
        val block = ByteArray(TAR_BLOCK)
        var total = 0L
        var longName: String? = null
        var checked = false
        while (true) {
            if (!readFull(input, block)) break
            if (block.all { it == 0.toByte() }) break
            if (!checked) {
                // Plain ustar, or the old format with an empty magic: anything else is not a tar.
                val magic = String(block, TAR_MAGIC_AT, 5, Charsets.ISO_8859_1)
                if (magic != "ustar" && block.sliceArray(TAR_MAGIC_AT until TAR_MAGIC_AT + 5).any { it != 0.toByte() }) {
                    throw IOException("not a tar")
                }
                checked = true
            }
            val size = octal(block, TAR_SIZE_AT, TAR_SIZE_LEN)
            if (size < 0) throw IOException("bad tar header")
            val type = block[TAR_TYPE_AT].toInt().toChar()
            val padding = (TAR_BLOCK - size % TAR_BLOCK) % TAR_BLOCK
            when {
                type == LONG_NAME_TYPE -> {
                    if (size > MAX_LONG_NAME) throw IOException("bad tar long name")
                    val bytes = ByteArrayOutputStream()
                    copyStream(input, bytes, size)
                    longName = bytes.toString("UTF-8").trimEnd('\u0000')
                    skip(input, padding)
                    continue
                }
                type == PAX_TYPE || type == PAX_GLOBAL_TYPE -> {
                    skip(input, size + padding)
                    continue
                }
            }
            val name = longName ?: headerName(block)
            longName = null
            val regular = type == '0' || type == '\u0000'
            if (regular && isBookFile(name)) {
                val leaf = name.substringAfterLast('/')
                total += copy(input, File(into, leaf), MAX_UNPACKED_BYTES - total, size)
                skip(input, padding)
                names += leaf
            } else {
                skip(input, size + padding)
            }
        }
        return names
    }

    private fun headerName(block: ByteArray): String {
        val name = cString(block, 0, TAR_NAME_LEN)
        val prefix = cString(block, TAR_PREFIX_AT, TAR_PREFIX_LEN)
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun cString(block: ByteArray, at: Int, length: Int): String {
        var end = at
        while (end < at + length && block[end] != 0.toByte()) end++
        return String(block, at, end - at, Charsets.UTF_8)
    }

    private fun octal(block: ByteArray, at: Int, length: Int): Long {
        var value = 0L
        var seen = false
        for (i in at until at + length) {
            val c = block[i].toInt().toChar()
            if (c == ' ' || c == '\u0000') {
                if (seen) break
                continue
            }
            if (c !in '0'..'7') return -1
            value = value * 8 + (c - '0')
            seen = true
        }
        return value
    }

    private fun readFull(input: InputStream, into: ByteArray): Boolean {
        var at = 0
        while (at < into.size) {
            val n = input.read(into, at, into.size - at)
            if (n < 0) {
                if (at == 0) return false
                throw IOException("archive cut off")
            }
            at += n
        }
        return true
    }

    private fun skip(input: InputStream, bytes: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var left = bytes
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (n < 0) throw IOException("archive cut off")
            left -= n
        }
    }

    private fun copyStream(input: InputStream, out: java.io.OutputStream, bytes: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var left = bytes
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (n < 0) throw IOException("archive cut off")
            out.write(buffer, 0, n)
            left -= n
        }
    }

    /** Copies up to [length] bytes, or to the stream's end when that is [Long.MAX_VALUE]; fails past [allowance]. */
    private fun copy(input: InputStream, target: File, allowance: Long, length: Long): Long {
        var done = 0L
        FileOutputStream(target).use { out ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (done < length) {
                val n = input.read(buffer, 0, minOf(length - done, buffer.size.toLong()).toInt())
                if (n < 0) {
                    if (length == Long.MAX_VALUE) break
                    throw IOException("archive cut off")
                }
                done += n
                if (done > allowance) throw IOException("archive too big")
                out.write(buffer, 0, n)
            }
        }
        return done
    }
}
