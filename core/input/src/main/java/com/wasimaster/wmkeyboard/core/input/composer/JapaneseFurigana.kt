package com.wasimaster.wmkeyboard.core.input.composer

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Writes the readings after a selection's kanji: 学校へ行きます becomes
 * 学校（がっこう）へ行（い）きます. A field can't hold ruby, so the reading goes in brackets.
 *
 * The table is the [PACK_ID] pack, words that hold kanji with their commonest reading,
 * cut from the Mozc dictionary the kana conversion uses. It is sorted and read from a
 * mapped file, so it costs no heap. Words are chosen by the cheapest path through the
 * text, with no grammar behind it, so a word with two readings gets its commoner one.
 */
object JapaneseFurigana {

    const val PACK_ID = "ja_furigana"

    /** The pack is on the phone. A file check. */
    fun ready(filesDir: File): Boolean = CjkDictStore.downloadedFileFor(filesDir, PACK_ID) != null

    /** [text] with its readings, or null without the pack. Reads a file: off the main thread. */
    fun add(filesDir: File, text: String): String? {
        val pack = CjkDictStore.downloadedFileFor(filesDir, PACK_ID) ?: return null
        return runCatching {
            RandomAccessFile(pack, "r").use { file ->
                val map = file.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
                FuriganaCodec.annotate(text, MappedWords(map))
            }
        }.getOrNull()
    }

    /** `word<TAB>reading<TAB>frequency` lines sorted by their bytes, searched in place. */
    private class MappedWords(private val map: ByteBuffer) : FuriganaCodec.Words {

        override fun find(word: String): FuriganaCodec.Entry? {
            val key = word.toByteArray()
            var low = 0
            var high = map.limit()
            while (low < high) {
                val middle = lineStart((low + high) ushr 1)
                val order = compare(key, middle)
                if (order == 0) return read(middle, key.size)
                if (order < 0) high = middle else low = lineEnd(middle) + 1
            }
            return null
        }

        private fun lineStart(from: Int): Int {
            var at = from
            while (at > 0 && map.get(at - 1) != NEWLINE) at--
            return at
        }

        private fun lineEnd(from: Int): Int {
            var at = from
            while (at < map.limit() && map.get(at) != NEWLINE) at++
            return at
        }

        /** [key] against the word of the line at [line]: the tab sorts below every byte of a word. */
        private fun compare(key: ByteArray, line: Int): Int {
            var i = 0
            while (true) {
                val theirs = if (line + i < map.limit()) map.get(line + i).toInt() and 0xFF else TAB
                if (i == key.size) return if (theirs == TAB) 0 else -1
                if (theirs == TAB) return 1
                val mine = key[i].toInt() and 0xFF
                if (mine != theirs) return mine - theirs
                i++
            }
        }

        private fun read(line: Int, wordBytes: Int): FuriganaCodec.Entry {
            val end = lineEnd(line)
            val bytes = ByteArray(end - line - wordBytes - 1)
            for (i in bytes.indices) bytes[i] = map.get(line + wordBytes + 1 + i)
            val rest = String(bytes)
            val tab = rest.indexOf('\t')
            return FuriganaCodec.Entry(rest.substring(0, tab), rest.substring(tab + 1).toIntOrNull() ?: 0)
        }

        private companion object {
            const val NEWLINE = '\n'.code.toByte()
            const val TAB = '\t'.code
        }
    }
}

/** The path through the text and the reading's alignment. No Android classes, so it runs in the JVM tests. */
internal object FuriganaCodec {

    class Entry(val reading: String, val frequency: Int)

    fun interface Words {
        fun find(word: String): Entry?
    }

    private const val MAX_WORD = 12

    // Fewer words first, then commoner ones. A kanji alone has the least to say for itself, since
    // nothing around it pins its reading, so a word that takes its kana along beats it.
    private const val WORD_COST = 40_000
    private const val LONE_KANJI_COST = 25_000
    private const val KANA_COST = 8_000
    private const val UNKNOWN_KANJI_COST = 70_000

    private fun isKanji(c: Char) = c in '一'..'鿿' || c in '㐀'..'䶿' || c == '々'

    private fun isHiragana(c: Char) = c in 'ぁ'..'ゖ'

    private fun toHiragana(c: Char) = if (c in 'ァ'..'ヶ') c - 0x60 else c

    fun annotate(text: String, words: Words): String {
        val n = text.length
        if (text.none(::isKanji)) return text
        val best = IntArray(n + 1) { Int.MAX_VALUE }
        val from = IntArray(n + 1)
        val entryAt = arrayOfNulls<Entry>(n + 1)
        best[0] = 0
        for (i in 0 until n) {
            if (best[i] == Int.MAX_VALUE) continue
            // One character by itself: always possible, so the path never breaks.
            val single = best[i] + if (isKanji(text[i])) UNKNOWN_KANJI_COST else KANA_COST
            if (single < best[i + 1]) {
                best[i + 1] = single
                from[i + 1] = i
                entryAt[i + 1] = null
            }
            if (!isKanji(text[i]) && !isHiragana(text[i]) && toHiragana(text[i]) == text[i]) continue
            for (end in i + 1..minOf(n, i + MAX_WORD)) {
                val word = text.substring(i, end)
                if (word.none(::isKanji)) continue
                val entry = words.find(word) ?: continue
                val lone = if (word.length == 1) LONE_KANJI_COST else 0
                val cost = best[i] + WORD_COST + lone - entry.frequency.coerceAtMost(WORD_COST - 1)
                if (cost < best[end]) {
                    best[end] = cost
                    from[end] = i
                    entryAt[end] = entry
                }
            }
        }
        val pieces = ArrayList<String>()
        var at = n
        while (at > 0) {
            val start = from[at]
            val entry = entryAt[at]
            pieces += if (entry == null) text.substring(start, at) else ruby(text.substring(start, at), entry.reading)
            at = start
        }
        return pieces.asReversed().joinToString("")
    }

    /** [word] with [reading] after each run of its kanji, the kana it shares left out: 取り扱い → 取（と）り扱（あつか）い. */
    fun ruby(word: String, reading: String): String {
        val runs = ArrayList<Pair<Boolean, String>>()
        for (c in word) {
            val kanji = isKanji(c)
            if (runs.isNotEmpty() && runs.last().first == kanji) {
                runs[runs.lastIndex] = kanji to runs.last().second + c
            } else {
                runs += kanji to c.toString()
            }
        }
        // Where the word's own kana sit in the reading fixes what each kanji run reads as.
        val out = StringBuilder()
        var at = 0
        runs.forEachIndexed { i, (kanji, piece) ->
            if (!kanji) {
                out.append(piece)
                at += piece.length
                return@forEachIndexed
            }
            val next = runs.getOrNull(i + 1)?.second?.map(::toHiragana)?.joinToString("")
            val end = if (next == null) reading.length else reading.indexOf(next, at + piece.length.coerceAtMost(1))
            if (end < 0 || end <= at) return "$word（$reading）"
            out.append(piece).append('（').append(reading, at, end).append('）')
            at = end
        }
        return out.toString()
    }
}
