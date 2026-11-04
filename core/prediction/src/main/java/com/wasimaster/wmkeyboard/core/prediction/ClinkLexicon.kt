package com.wasimaster.wmkeyboard.core.prediction

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads a Clink lexicon (`.clex`), the word list in a language pack of the iOS
 * keyboard Clink (`anti-ltd/clink-language-packs` and its forks).
 *
 * Little-endian throughout, as Clink's own `build-pack.py` writes it:
 *
 * | Field | Size |
 * |---|---|
 * | `CLEX`, then version, word count `count`, alphabet size `n` | 16 bytes |
 * | the alphabet, one code point each | `4 × n` |
 * | a letter-transition table | `(n + 1) × n` |
 * | each word's byte offset, then the end | `4 × (count + 1)` |
 * | each word's score, `(log10(p) + 9) × 28` clamped to 0..255 | `count` |
 * | each word's length in characters | `count` |
 * | the words, UTF-8, sorted by bytes | the rest |
 *
 * The score is already a log-scaled byte, the same shape as an AOSP `f`, so it
 * takes the same step onto this app's scale. The transition table is Clink's
 * spelling model and has no use here.
 */
object ClinkLexicon {

    const val FILE_EXTENSION = "clex"

    /** Whether [head] starts with a lexicon's magic. */
    fun looksLikeClex(head: ByteArray): Boolean =
        head.size >= HEADER_BYTES && head[0] == 'C'.code.toByte() && head[1] == 'L'.code.toByte() &&
            head[2] == 'E'.code.toByte() && head[3] == 'X'.code.toByte()

    /** Every word with its frequency on this app's scale, or empty when [bytes] is not a readable lexicon. */
    fun read(bytes: ByteArray): List<Pair<String, Int>> =
        runCatching { parse(bytes) }.getOrDefault(emptyList())

    /** The words as this app's plain word list, `word frequency` a line. */
    fun toText(bytes: ByteArray): String = buildString {
        for ((word, frequency) in read(bytes)) append(word).append(' ').append(frequency).append('\n')
    }

    private fun parse(bytes: ByteArray): List<Pair<String, Int>> {
        if (!looksLikeClex(bytes)) return emptyList()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(MAGIC_BYTES)
        val version = buffer.int
        val count = buffer.int
        val letters = buffer.int
        if (version != VERSION || count <= 0 || count > MAX_WORDS || letters !in 0..MAX_LETTERS) return emptyList()

        val offsetsAt = HEADER_BYTES + 4L * letters + (letters + 1L) * letters
        val scoresAt = offsetsAt + 4L * (count + 1)
        val wordsAt = scoresAt + 2L * count
        if (wordsAt > bytes.size) return emptyList()

        buffer.position(offsetsAt.toInt())
        val offsets = IntArray(count + 1) { buffer.int }
        val out = ArrayList<Pair<String, Int>>(count)
        for (i in 0 until count) {
            val start = wordsAt + offsets[i]
            val end = wordsAt + offsets[i + 1]
            if (start < wordsAt || end < start || end > bytes.size) return emptyList()
            val word = String(bytes, start.toInt(), (end - start).toInt(), Charsets.UTF_8)
            if (word.isEmpty() || word.any(Char::isWhitespace)) continue
            val score = bytes[(scoresAt + i).toInt()].toInt() and BYTE_MASK
            out += word to DictionaryLoader.scaleAospFrequency(score)
        }
        return out
    }

    private const val MAGIC_BYTES = 4
    private const val HEADER_BYTES = 16
    private const val VERSION = 1
    private const val MAX_WORDS = 2_000_000
    private const val MAX_LETTERS = 4096
    private const val BYTE_MASK = 0xFF
}
