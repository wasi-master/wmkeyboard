package com.wasimaster.wmkeyboard.core.tools.stardict

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

/**
 * A bzip2 decoder, since nothing on Android has one and StarDict books are mostly
 * shipped as `.tar.bz2`. Written from the format's description. Handles several
 * concatenated streams; checks no CRC, as the tar and dictionary formats above it fail
 * on damage anyway.
 */
class Bzip2InputStream(private val input: InputStream) : InputStream() {

    private var bitBuffer = 0L
    private var bitCount = 0
    private var blockSize = 0
    private var started = false
    private var ended = false

    private var tt = IntArray(0)
    private var origLength = 0
    private var pos = 0
    private var produced = 0
    private var runLeft = 0
    private var runByte = 0
    private var sameCount = 0
    private var lastByte = -1

    private fun bit(): Int = bits(1)

    private fun bits(n: Int): Int {
        while (bitCount < n) {
            val b = input.read()
            if (b < 0) throw IOException("bzip2 stream cut off")
            bitBuffer = (bitBuffer shl 8) or b.toLong()
            bitCount += 8
        }
        bitCount -= n
        return ((bitBuffer ushr bitCount) and ((1L shl n) - 1)).toInt()
    }

    private fun bits48(): Long = (bits(24).toLong() shl 24) or bits(24).toLong()

    private fun streamHeader(): Boolean {
        val first = input.read()
        if (first < 0) return false
        if (first != 'B'.code || input.read() != 'Z'.code || input.read() != 'h'.code) throw IOException("not bzip2")
        val level = input.read() - '0'.code
        if (level !in 1..9) throw IOException("not bzip2")
        blockSize = level * BLOCK_UNIT
        if (tt.size < blockSize) tt = IntArray(blockSize)
        bitCount = 0
        bitBuffer = 0
        return true
    }

    /** Reads the next block. False once the last stream has ended. */
    private fun nextBlock(): Boolean {
        while (true) {
            if (!started) {
                if (!streamHeader()) return false
                started = true
            }
            val magic = bits48()
            if (magic == BLOCK_MAGIC) {
                bits(32) // Block CRC.
                readBlock()
                return true
            }
            if (magic != END_MAGIC) throw IOException("bad bzip2 block")
            bits(32) // Stream CRC.
            bitCount -= bitCount % 8 // To the byte boundary.
            started = false
        }
    }

    private fun readBlock() {
        if (bit() != 0) throw IOException("randomised bzip2")
        val origPtr = bits(24)
        // Which bytes occur, as a two-level bitmap.
        val symbols = IntArray(256)
        var used = 0
        val groups = bits(16)
        for (i in 0 until 16) {
            if (groups and (0x8000 shr i) == 0) continue
            val row = bits(16)
            for (j in 0 until 16) if (row and (0x8000 shr j) != 0) symbols[used++] = i * 16 + j
        }
        if (used == 0) throw IOException("bad bzip2 block")
        val alphabet = used + 2
        val tables = bits(3)
        if (tables !in 2..6) throw IOException("bad bzip2 block")
        val selectorCount = bits(15)
        if (selectorCount < 1) throw IOException("bad bzip2 block")
        // Selectors, move-to-front coded.
        val order = IntArray(tables) { it }
        val selectors = ByteArray(selectorCount)
        for (i in 0 until selectorCount) {
            var j = 0
            while (bit() == 1) {
                j++
                if (j >= tables) throw IOException("bad bzip2 block")
            }
            val chosen = order[j]
            System.arraycopy(order, 0, order, 1, j)
            order[0] = chosen
            selectors[i] = chosen.toByte()
        }
        val limits = Array(tables) { IntArray(MAX_CODE_LEN + 2) }
        val bases = Array(tables) { IntArray(MAX_CODE_LEN + 2) }
        val perms = Array(tables) { IntArray(alphabet) }
        val minLens = IntArray(tables)
        for (t in 0 until tables) {
            val lengths = IntArray(alphabet)
            var length = bits(5)
            for (i in 0 until alphabet) {
                while (true) {
                    if (length !in 1..MAX_CODE_LEN) throw IOException("bad bzip2 block")
                    if (bit() == 0) break
                    length += if (bit() == 0) 1 else -1
                }
                lengths[i] = length
            }
            minLens[t] = buildTable(lengths, alphabet, limits[t], bases[t], perms[t])
        }
        // The Huffman coded, run-length coded, move-to-front coded data.
        val mtf = IntArray(256) { it }
        val counts = IntArray(256)
        val end = used + 1
        var n = 0
        var selector = 0
        var left = 0
        var limit = limits[0]
        var base = bases[0]
        var perm = perms[0]
        var minLen = minLens[0]
        var runLength = 0
        var runWeight = 1
        while (true) {
            if (left == 0) {
                if (selector >= selectorCount) throw IOException("bad bzip2 block")
                val t = selectors[selector++].toInt()
                limit = limits[t]
                base = bases[t]
                perm = perms[t]
                minLen = minLens[t]
                left = GROUP_SIZE
            }
            left--
            var length = minLen
            var code = bits(length)
            while (true) {
                if (length > MAX_CODE_LEN) throw IOException("bad bzip2 block")
                if (code <= limit[length]) break
                length++
                code = (code shl 1) or bit()
            }
            val index = code - base[length]
            if (index !in 0 until alphabet) throw IOException("bad bzip2 block")
            val symbol = perm[index]
            if (symbol == 0 || symbol == 1) {
                runLength += runWeight shl symbol
                runWeight = runWeight shl 1
                continue
            }
            if (runLength > 0) {
                val byte = symbols[mtf[0]]
                if (n + runLength > blockSize) throw IOException("bad bzip2 block")
                counts[byte] += runLength
                java.util.Arrays.fill(tt, n, n + runLength, byte)
                n += runLength
                runLength = 0
                runWeight = 1
            }
            if (symbol == end) break
            if (n >= blockSize) throw IOException("bad bzip2 block")
            val at = symbol - 1
            val moved = mtf[at]
            System.arraycopy(mtf, 0, mtf, 1, at)
            mtf[0] = moved
            val byte = symbols[moved]
            counts[byte]++
            tt[n++] = byte
        }
        if (origPtr >= n) throw IOException("bad bzip2 block")
        // Undo the Burrows-Wheeler transform: each entry's high bits link to the next.
        var sum = 0
        for (i in 0 until 256) {
            val c = counts[i]
            counts[i] = sum
            sum += c
        }
        for (i in 0 until n) {
            val byte = tt[i] and 0xFF
            tt[counts[byte]++] = tt[counts[byte] - 1] or (i shl 8)
        }
        pos = tt[origPtr] ushr 8
        origLength = n
        produced = 0
        sameCount = 0
        lastByte = -1
        runLeft = 0
    }

    private fun buildTable(lengths: IntArray, alphabet: Int, limit: IntArray, base: IntArray, perm: IntArray): Int {
        var minLen = MAX_CODE_LEN
        var maxLen = 1
        for (i in 0 until alphabet) {
            if (lengths[i] < minLen) minLen = lengths[i]
            if (lengths[i] > maxLen) maxLen = lengths[i]
        }
        var p = 0
        for (length in minLen..maxLen) for (i in 0 until alphabet) if (lengths[i] == length) perm[p++] = i
        val countOf = IntArray(MAX_CODE_LEN + 2)
        for (i in 0 until alphabet) countOf[lengths[i]]++
        var code = 0
        var seen = 0
        for (length in minLen..maxLen) {
            base[length] = code - seen
            code += countOf[length]
            limit[length] = code - 1
            seen += countOf[length]
            code = code shl 1
        }
        for (length in 0 until minLen) limit[length] = -1
        for (length in maxLen + 1 until limit.size) limit[length] = Int.MAX_VALUE
        return minLen
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        var written = 0
        while (written < len) {
            if (runLeft > 0) {
                b[off + written++] = runByte.toByte()
                runLeft--
                continue
            }
            if (produced == origLength) {
                if (ended) break
                if (!nextBlock()) {
                    ended = true
                    break
                }
                continue
            }
            val entry = tt[pos]
            val byte = entry and 0xFF
            pos = entry ushr 8
            produced++
            // The first run-length layer: four equal bytes, then a count of more.
            if (sameCount == RUN_TRIGGER) {
                runByte = lastByte
                runLeft = byte
                sameCount = 0
                continue
            }
            sameCount = if (byte == lastByte) sameCount + 1 else 1
            lastByte = byte
            b[off + written++] = byte.toByte()
        }
        return if (written == 0) -1 else written
    }

    override fun close() = input.close()

    private companion object {
        const val BLOCK_UNIT = 100_000
        const val BLOCK_MAGIC = 0x314159265359L
        const val END_MAGIC = 0x177245385090L
        const val MAX_CODE_LEN = 20
        const val GROUP_SIZE = 50
        const val RUN_TRIGGER = 4
    }
}
