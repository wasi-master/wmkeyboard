package com.wasimaster.wmkeyboard.core.tools.offlinedict

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * An installed offline dictionary (`<id>.wmdict`): the data repository's
 * `dictionary/<lang>/<id>.wmdict.gz` inflated, read in place.
 *
 * The file is one line per headword, sorted by the key's UTF-8 bytes, under
 * a few `#` header lines; `scripts/build_dictionary_packs.py` in the data
 * repository writes it and documents the format. A look-up is a binary
 * search over byte offsets, so it reads a few kilobytes of a 15 MB file and
 * never parses the rest.
 */
class WmDictFile private constructor(
    private val raf: RandomAccessFile,
    /** The header's fields: `id`, `name`, `lang`, `license`, `attribution`, `source`. */
    val header: Map<String, String>,
    private val dataStart: Long,
) : Closeable {

    val id: String get() = header["id"].orEmpty()

    /** One line of the file. */
    sealed interface Line

    data class Entry(val word: String, val ipa: String, val senses: List<Sense>) : Line

    /** An inflection ("mice") pointing at the headwords it is a form of ("mouse"). */
    data class Redirect(val targets: List<String>) : Line

    data class Sense(
        val pos: String,
        val definition: String,
        val example: String,
        val synonyms: List<String>,
        val antonyms: List<String>,
    )

    private val length = raf.length()
    private val buffer = ByteArray(BUFFER_SIZE)
    private var bufferStart = -1L
    private var bufferLength = 0

    /** Every line whose key is [key] (already lower case), in file order. */
    fun find(key: String): List<Line> {
        val target = key.toByteArray(Charsets.UTF_8)
        if (target.isEmpty()) return emptyList()
        // The smallest line start whose key is >= target: lo..hi are byte
        // offsets, and a probe reads the first line starting at or after it.
        var lo = dataStart
        var hi = length
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val start = lineStartAtOrAfter(mid)
            if (start >= length || compareKey(start, target) >= 0) hi = mid else lo = start + 1
        }
        val out = ArrayList<Line>()
        var start = lineStartAtOrAfter(lo)
        while (start < length && compareKey(start, target) == 0) {
            val (text, next) = readLine(start)
            parse(text)?.let(out::add)
            start = next
        }
        return out
    }

    /**
     * The headwords of the lines whose key starts with [prefix] (already lower
     * case), in file order, reading at most [limit] lines. Redirects are
     * skipped: their lemma is listed on its own line.
     */
    fun complete(prefix: String, limit: Int): List<String> {
        val target = prefix.toByteArray(Charsets.UTF_8)
        if (target.isEmpty()) return emptyList()
        var lo = dataStart
        var hi = length
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val start = lineStartAtOrAfter(mid)
            if (start >= length || compareKey(start, target) >= 0) hi = mid else lo = start + 1
        }
        val out = ArrayList<String>()
        var start = lineStartAtOrAfter(lo)
        var read = 0
        while (start < length && read < limit && startsWith(start, target)) {
            val (text, next) = readLine(start)
            (parse(text) as? Entry)?.let { out += it.word }
            start = next
            read++
        }
        return out
    }

    override fun close() = raf.close()

    /** Whether the key of the line at [start] begins with [prefix]. */
    private fun startsWith(start: Long, prefix: ByteArray): Boolean {
        var p = start
        for (b in prefix) {
            val c = if (p < length) byteAt(p) else -1
            if (c == -1 || c == TAB || c == NEWLINE || c != (b.toInt() and 0xff)) return false
            p++
        }
        return true
    }

    private fun byteAt(pos: Long): Int {
        if (pos < bufferStart || pos >= bufferStart + bufferLength) {
            raf.seek(pos)
            bufferLength = raf.read(buffer).coerceAtLeast(0)
            bufferStart = pos
            if (bufferLength == 0) return -1
        }
        return buffer[(pos - bufferStart).toInt()].toInt() and 0xff
    }

    private fun lineStartAtOrAfter(pos: Long): Long {
        if (pos <= dataStart) return dataStart
        var p = pos - 1
        while (p < length) {
            if (byteAt(p) == NEWLINE) return p + 1
            p++
        }
        return length
    }

    /** The key of the line at [start] against [key], bytewise unsigned, as the build sorted them. */
    private fun compareKey(start: Long, key: ByteArray): Int {
        var i = 0
        var p = start
        while (true) {
            val b = if (p < length) byteAt(p) else -1
            val ended = b == -1 || b == TAB || b == NEWLINE
            if (i == key.size) return if (ended) 0 else 1
            if (ended) return -1
            val k = key[i].toInt() and 0xff
            if (b != k) return b - k
            i++
            p++
        }
    }

    private fun readLine(start: Long): Pair<String, Long> {
        val bytes = ByteArrayOutputStream(256)
        var p = start
        while (p < length) {
            val b = byteAt(p)
            p++
            if (b == NEWLINE || b < 0) break
            bytes.write(b)
        }
        return bytes.toString(Charsets.UTF_8.name()) to p
    }

    companion object {
        const val MAGIC = "#wmdict"
        const val FORMAT_VERSION = 1

        private const val BUFFER_SIZE = 8 * 1024
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val NEWLINE = '\n'.code
        private const val TAB = '\t'.code
        private const val SEP = '\u001f'

        /** Opens [file], or null when it is not a dictionary this build reads. The caller closes it. */
        fun open(file: File): WmDictFile? {
            if (!file.isFile) return null
            val raf = RandomAccessFile(file, "r")
            val header = LinkedHashMap<String, String>()
            var pos = 0L
            try {
                var first = true
                while (pos < raf.length() && pos < MAX_HEADER_BYTES) {
                    raf.seek(pos)
                    if (raf.read() != '#'.code) break
                    raf.seek(pos)
                    val line = raf.readUtf8Line() ?: break
                    pos = raf.filePointer
                    val name = line.substringBefore('\t')
                    val value = line.substringAfter('\t', "")
                    if (first) {
                        if (name != MAGIC || value.toIntOrNull() != FORMAT_VERSION) {
                            raf.close()
                            return null
                        }
                        first = false
                    } else {
                        header[name.removePrefix("#")] = value
                    }
                }
                if (first || header["id"].isNullOrEmpty()) {
                    raf.close()
                    return null
                }
            } catch (e: Exception) {
                raf.close()
                throw e
            }
            return WmDictFile(raf, header, pos)
        }

        /** A line of the file as read; null for one this build cannot make sense of. */
        internal fun parse(line: String): Line? {
            val fields = line.split('\t')
            if (fields.size < 2) return null
            val second = fields[1]
            if (fields.size == 2 && second.startsWith("@")) {
                val targets = second.removePrefix("@").split('|').filter { it.isNotEmpty() }
                return if (targets.isEmpty()) null else Redirect(targets)
            }
            if (fields.size < 4) return null
            val senses = fields.drop(3).mapNotNull { raw ->
                val parts = raw.split(SEP)
                if (parts.size < 2 || parts[1].isEmpty()) return@mapNotNull null
                Sense(
                    pos = parts[0],
                    definition = parts[1],
                    example = parts.getOrElse(2) { "" },
                    synonyms = parts.getOrElse(3) { "" }.split('|').filter { it.isNotEmpty() },
                    antonyms = parts.getOrElse(4) { "" }.split('|').filter { it.isNotEmpty() },
                )
            }
            return if (senses.isEmpty()) null else Entry(word = second.ifEmpty { fields[0] }, ipa = fields[2], senses = senses)
        }

        /** [RandomAccessFile.readLine] reads Latin-1; the header is UTF-8. */
        private fun RandomAccessFile.readUtf8Line(): String? {
            val bytes = ByteArrayOutputStream(64)
            while (true) {
                val b = read()
                if (b < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.UTF_8.name())
                if (b == NEWLINE) return bytes.toString(Charsets.UTF_8.name())
                bytes.write(b)
            }
        }
    }
}
