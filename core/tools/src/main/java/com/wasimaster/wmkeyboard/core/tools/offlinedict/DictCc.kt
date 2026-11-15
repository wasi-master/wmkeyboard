package com.wasimaster.wmkeyboard.core.tools.offlinedict

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.PriorityQueue
import java.util.zip.ZipInputStream

/**
 * A dict.cc word list the user downloaded for themselves (#1085), made into
 * an offline dictionary on the phone. dict.cc lets anyone download its
 * vocabulary for personal use (dict.cc/?s=about:wordlist) but not pass it on,
 * so the app cannot publish it as a pack; the user brings the file and this
 * turns it into one, both ways round: a German–English list looks up German
 * words in English and English words in German.
 *
 * The file is UTF-8 text, or a zip holding it: `#` comment lines, the first
 * of which names the pair ("# DE-EN vocabulary database"), then a line per
 * entry: `term TAB translation TAB word class TAB subjects`. A term carries
 * its notes inline: `{n}` gender, `[ugs.]` register, `<Abk.>` abbreviations,
 * `(etw.)` optional words; the look-up key leaves them out, the meaning keeps
 * them.
 *
 * A full list runs to a million lines and more, too many to sort in a
 * phone's memory, so the rows are sorted in runs written beside the pack and
 * merged into it ([convert]).
 */
object DictCc {

    /** Every pack made from a dict.cc file has an id starting with this. */
    const val ID_PREFIX = "dictcc_"

    /** What a converted list came to. */
    data class Converted(val id: String, val name: String, val langId: String, val headwords: Int, val file: File)

    class NotDictCcException : IOException("not a dict.cc word list")

    /**
     * Reads [input] (the text, or a zip holding it) and writes the pack where
     * the offline dictionaries live under [filesDir], as
     * `dictcc_<from>_<to>.wmdict`, replacing an older one of the same pair.
     * [onRows] hears the count of lines read now and then.
     */
    fun convert(input: InputStream, filesDir: File, onRows: (Int) -> Unit = {}): Converted {
        val dir = File(filesDir, OfflineDictionaries.DIR_NAME).apply { mkdirs() }
        var part: File? = null
        val stream = textStream(input)
        val reader = stream.bufferedReader(Charsets.UTF_8)
        val runs = ArrayList<File>()
        var pair: Pair<String, String>? = null
        var rows = 0
        try {
            val chunk = ArrayList<String>(RUN_ROWS)
            var lineNo = 0
            while (true) {
                val line = reader.readLine() ?: break
                lineNo++
                if (line.startsWith("#") || line.isBlank()) {
                    if (pair == null) pair = pair(line)
                    continue
                }
                if (pair == null) {
                    // A dict.cc list says its pair before its first entry.
                    if (lineNo > HEADER_LINES) throw NotDictCcException()
                    continue
                }
                val fields = line.split('\t')
                if (fields.size < 2) continue
                val pos = fields.getOrElse(2) { "" }.trim()
                val subject = fields.getOrElse(3) { "" }.trim()
                row(fields[0], fields[1], pos, subject)?.let(chunk::add)
                row(fields[1], fields[0], pos, subject)?.let(chunk::add)
                rows++
                if (rows % PROGRESS_ROWS == 0) onRows(rows)
                if (chunk.size >= RUN_ROWS) {
                    runs += writeRun(chunk, dir)
                    chunk.clear()
                }
            }
            val (from, to) = pair ?: throw NotDictCcException()
            if (rows == 0) throw NotDictCcException()
            if (chunk.isNotEmpty()) runs += writeRun(chunk, dir)
            val id = "$ID_PREFIX${from}_$to"
            val name = "dict.cc ${from.uppercase()}–${to.uppercase()}"
            val written = File.createTempFile(ID_PREFIX, ".wmdict.part", dir).also { part = it }
            val headwords = written.bufferedWriter(Charsets.UTF_8).use { out ->
                out.write("${WmDictFile.MAGIC}\t${WmDictFile.FORMAT_VERSION}\n")
                out.write("#id\t$id\n#name\t$name\n#lang\t$from\n#license\tdict.cc, for personal use\n")
                out.write("#attribution\tdict.cc word list, downloaded by the user\n#source\tdict.cc $rows\n")
                merge(runs, out)
            }
            val target = OfflineDictionaries.file(filesDir, id)
            target.delete()
            if (!written.renameTo(target)) throw IOException("could not move $target into place")
            return Converted(id, name, from, headwords, target)
        } finally {
            runs.forEach { it.delete() }
            part?.delete()
            reader.close()
        }
    }

    /** The pair a header line names: "# DE-EN vocabulary database …" gives de and en. */
    internal fun pair(line: String): Pair<String, String>? =
        PAIR.find(line)?.let { it.groupValues[1].lowercase() to it.groupValues[2].lowercase() }

    /**
     * [term] as the word a look-up finds: its notes in braces, brackets and
     * angle brackets left out, optional words in parentheses too, and an
     * English verb's "to" dropped. Empty when nothing is left.
     */
    internal fun headword(term: String): String {
        var t = term.replace(NOTES, " ").replace(OPTIONAL, " ").replace(SPACES, " ").trim()
        if (t.startsWith("to ") && t.length > 3) t = t.substring(3)
        return t.trim(' ', ',', ';', '/')
    }

    /** One row as a run line: key NUL headword NUL sense field. Null for a term with nothing to look up. */
    internal fun row(term: String, translation: String, pos: String, subject: String): String? {
        val word = headword(clean(term))
        if (word.isEmpty() || word.length > MAX_WORD) return null
        val meaning = clean(translation)
        if (meaning.isEmpty()) return null
        val key = OfflineDictionaries.normalize(word)
        val sense = listOf(clean(pos), meaning, clean(subject)).joinToString(SEP)
        return "$key\u0000$word\u0000$sense"
    }

    private fun clean(text: String): String = text.replace(CONTROL, " ").replace(SPACES, " ").trim()

    /** The text itself, or the first `.txt` inside a zip. */
    private fun textStream(input: InputStream): InputStream {
        val buffered = input.buffered(BUFFER)
        buffered.mark(4)
        val zipped = buffered.read() == 'P'.code && buffered.read() == 'K'.code
        buffered.reset()
        if (!zipped) return buffered
        val zip = ZipInputStream(buffered)
        while (true) {
            val entry = zip.nextEntry ?: throw NotDictCcException()
            if (!entry.isDirectory && entry.name.endsWith(".txt", ignoreCase = true)) return zip
        }
    }

    private fun writeRun(rows: MutableList<String>, dir: File): File {
        rows.sortWith(CodePointOrder)
        val file = File.createTempFile(ID_PREFIX, ".run", dir)
        file.bufferedWriter(Charsets.UTF_8).use { out ->
            for (r in rows) {
                out.write(r)
                out.write('\n'.code)
            }
        }
        return file
    }

    /**
     * Merges the sorted [runs] into [out], one line per key and headword with
     * its senses joined, the same sense once. Returns the headword count.
     */
    private fun merge(runs: List<File>, out: BufferedWriter): Int {
        class Head(val line: String, val reader: BufferedReader)
        val queue = PriorityQueue<Head>(maxOf(1, runs.size)) { a, b -> CodePointOrder.compare(a.line, b.line) }
        val readers = runs.map { it.bufferedReader(Charsets.UTF_8) }
        try {
            for (r in readers) r.readLine()?.let { queue += Head(it, r) }
            var current: String? = null
            val senses = LinkedHashSet<String>()
            var headwords = 0
            fun flush() {
                val head = current ?: return
                val cut = head.indexOf('\u0000')
                val key = head.substring(0, cut)
                val word = head.substring(cut + 1)
                out.write(key)
                out.write('\t'.code)
                out.write(if (word == key) "" else word)
                out.write("\t")
                for (s in senses) {
                    out.write('\t'.code)
                    out.write(s)
                }
                out.write('\n'.code)
                headwords++
            }
            while (queue.isNotEmpty()) {
                val next = queue.poll()
                next.reader.readLine()?.let { queue += Head(it, next.reader) }
                val second = next.line.indexOf('\u0000', next.line.indexOf('\u0000') + 1)
                if (second < 0) continue
                val head = next.line.substring(0, second)
                if (head != current) {
                    flush()
                    current = head
                    senses.clear()
                }
                if (senses.size < MAX_SENSES) senses += next.line.substring(second + 1)
            }
            flush()
            return headwords
        } finally {
            readers.forEach { it.close() }
        }
    }

    /**
     * Strings by code point, which is the UTF-8 byte order the pack's binary
     * search compares in; NUL, the run lines' separator, sorts first, so a
     * key sorts ahead of every longer key it begins.
     */
    private object CodePointOrder : Comparator<String> {
        override fun compare(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val x = a.codePointAt(i)
                val y = b.codePointAt(j)
                if (x != y) return x.compareTo(y)
                i += Character.charCount(x)
                j += Character.charCount(y)
            }
            return (a.length - i).compareTo(b.length - j)
        }
    }

    private const val SEP = "\u001f"
    private val PAIR = Regex("""^#\s*([A-Za-z]{2,3})\s*-\s*([A-Za-z]{2,3})\b""")
    private val NOTES = Regex("""\{[^}]*\}|\[[^\]]*\]|<[^>]*>""")
    private val OPTIONAL = Regex("""\([^)]*\)""")
    private val SPACES = Regex("""\s+""")
    private val CONTROL = Regex("""[\t\n\r\u0000\u001f]""")

    /** Lines read before giving up on finding the pair: dict.cc's header is a few lines. */
    private const val HEADER_LINES = 50
    private const val RUN_ROWS = 200_000
    private const val PROGRESS_ROWS = 25_000
    private const val MAX_SENSES = 24
    private const val MAX_WORD = 80
    private const val BUFFER = 64 * 1024
}
