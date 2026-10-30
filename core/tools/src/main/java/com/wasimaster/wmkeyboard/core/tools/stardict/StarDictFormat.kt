package com.wasimaster.wmkeyboard.core.tools.stardict

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/**
 * The StarDict dictionary format, read from its published file format notes: a text
 * `.ifo` naming the book, a sorted `.idx` of words with where each one's record sits,
 * a `.dict` holding the records, and optionally a `.syn` of other words for the same
 * entries. No Android classes, so it runs in the JVM tests.
 */
object StarDictFormat {

    /** Past this a single definition is cut: a look-up card is no place for a whole article's worth. */
    private const val MAX_DEFINITION_CHARS = 16_000

    private const val IFO_MAGIC = "StarDict's dict ifo file"

    /** What an `.ifo` says. Null fields are the book's to leave out. */
    data class Ifo(
        val bookName: String,
        val version: String,
        val wordCount: Int,
        val offsetBits: Int,
        /** One type letter per part when every record has the same shape; null when each record says its own. */
        val sameTypeSequence: String?,
        val author: String,
        val website: String,
        val description: String,
    )

    /** Null for anything that is not an `.ifo`, or one with no book name. */
    fun parseIfo(text: String): Ifo? {
        val lines = text.removePrefix("﻿").lineSequence().map { it.trimEnd('\r') }.toList()
        if (lines.firstOrNull()?.trim() != IFO_MAGIC) return null
        val fields = lines.drop(1).mapNotNull { line ->
            val at = line.indexOf('=')
            if (at <= 0) null else line.substring(0, at).trim() to line.substring(at + 1).trim()
        }.toMap()
        val name = fields["bookname"]?.takeIf { it.isNotBlank() } ?: return null
        return Ifo(
            bookName = name,
            version = fields["version"].orEmpty(),
            wordCount = fields["wordcount"]?.toIntOrNull() ?: 0,
            offsetBits = if (fields["idxoffsetbits"] == "64") 64 else 32,
            sameTypeSequence = fields["sametypesequence"]?.takeIf { it.isNotEmpty() },
            author = fields["author"].orEmpty(),
            website = fields["website"].orEmpty(),
            description = fields["description"].orEmpty(),
        )
    }

    /** One `.idx` row. */
    class IndexRow(val word: String, val offset: Long, val size: Int)

    /** Calls [row] for each entry of an `.idx` stream, already unpacked if it was gzipped. */
    inline fun forEachIndexRow(input: InputStream, offsetBits: Int, row: (IndexRow) -> Unit) {
        val data = DataInputStream(input)
        while (true) {
            val word = readWord(data) ?: return
            val offset = if (offsetBits == 64) data.readLong() else data.readInt().toLong() and 0xFFFFFFFFL
            row(IndexRow(word, offset, data.readInt()))
        }
    }

    /** Calls [row] with each word and the index of the entry it stands for, from a `.syn` stream. */
    inline fun forEachSynonym(input: InputStream, row: (word: String, entry: Int) -> Unit) {
        val data = DataInputStream(input)
        while (true) {
            val word = readWord(data) ?: return
            row(word, data.readInt())
        }
    }

    /** A NUL-terminated UTF-8 string; null at a clean end of the stream. */
    @PublishedApi
    internal fun readWord(data: DataInputStream): String? {
        val out = java.io.ByteArrayOutputStream(32)
        while (true) {
            val b = data.read()
            if (b < 0) {
                if (out.size() == 0) return null
                throw EOFException("word cut off")
            }
            if (b == 0) return out.toString("UTF-8")
            out.write(b)
        }
    }

    /**
     * The readable text of a record, its parts on their own lines; blank when it holds
     * only pictures or sound. [sequence] is the book's `sametypesequence`: with one,
     * types are not stored and the last part runs to the end of the record.
     */
    fun definition(record: ByteArray, sequence: String?): String {
        val parts = ArrayList<String>()
        if (sequence != null) {
            var at = 0
            sequence.forEachIndexed { i, type ->
                val last = i == sequence.lastIndex
                if (at > record.size) return@forEachIndexed
                val end = partEnd(record, at, type, last)
                val start = if (type.isUpperCase() && !last) at + SIZE_PREFIX else at
                if (start <= end) textOf(type, record, start, end)?.let(parts::add)
                at = next(end, type, last)
            }
        } else {
            var at = 0
            while (at < record.size) {
                val type = record[at].toInt().toChar()
                at++
                val end = partEnd(record, at, type, last = false)
                val start = if (type.isUpperCase()) at + SIZE_PREFIX else at
                if (start <= end) textOf(type, record, start, end)?.let(parts::add)
                at = next(end, type, last = false)
            }
        }
        return parts.joinToString("\n").take(MAX_DEFINITION_CHARS)
    }

    private const val SIZE_PREFIX = 4

    /** Where the part starting at [at] ends: a NUL for a lowercase type, a size in front of an uppercase one. */
    private fun partEnd(record: ByteArray, at: Int, type: Char, last: Boolean): Int = when {
        last -> record.size
        type.isUpperCase() -> {
            if (at + SIZE_PREFIX > record.size) {
                record.size
            } else {
                val size = (record[at].toInt() and 0xFF shl 24) or (record[at + 1].toInt() and 0xFF shl 16) or
                    (record[at + 2].toInt() and 0xFF shl 8) or (record[at + 3].toInt() and 0xFF)
                val end = at.toLong() + SIZE_PREFIX + (size.toLong() and 0xFFFFFFFFL)
                minOf(end, record.size.toLong()).toInt()
            }
        }
        else -> {
            var end = at
            while (end < record.size && record[end] != 0.toByte()) end++
            end
        }
    }

    private fun next(end: Int, type: Char, last: Boolean): Int = when {
        last -> end
        type.isUpperCase() -> end
        else -> end + 1 // Past the NUL.
    }

    private fun textOf(type: Char, record: ByteArray, start: Int, end: Int): String? {
        val text = { String(record, start, end - start, Charsets.UTF_8) }
        return when (type) {
            'm', 'l', 'y' -> text().trim()
            // Phonetic spelling: its own line, in the slashes a reader expects.
            't' -> text().trim().takeIf { it.isNotEmpty() }?.let { "/$it/" }
            'h', 'g', 'x' -> stripMarkup(text())
            // Pictures, sound, a WordNet graph or kingsoft's own markup: nothing to read.
            else -> null
        }?.takeIf { it.isNotEmpty() }
    }

    private val BLOCK_ENDS = Regex("""(?i)<\s*(br|/p|/div|/li|/tr|/h[1-6]|/blockquote|/dd|/dt)\b[^>]*>""")
    private val NOT_SHOWN = Regex("""(?is)<(style|script)\b.*?</\1\s*>""")
    private val COMMENT = Regex("""(?s)<!--.*?-->""")
    private val TAG = Regex("""<[^>]*>""")
    private val ENTITY = Regex("""&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);""")
    private val BLANKS = Regex("""[ \t ]+""")
    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "laquo" to "«", "raquo" to "»",
    )

    /** HTML, Pango or XDXF markup as the plain lines the card shows. */
    internal fun stripMarkup(markup: String): String {
        val plain = markup.replace(COMMENT, "").replace(NOT_SHOWN, "").replace(BLOCK_ENDS, "\n").replace(TAG, "")
        val decoded = ENTITY.replace(plain) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x", ignoreCase = true) -> codePoint(body.substring(2).toIntOrNull(16))
                body.startsWith("#") -> codePoint(body.substring(1).toIntOrNull())
                else -> NAMED[body]
            } ?: match.value
        }
        return decoded.lineSequence()
            .map { it.replace(BLANKS, " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun codePoint(value: Int?): String? =
        value?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) }
}
