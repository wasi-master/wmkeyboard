package com.wasimaster.wmkeyboard.core.input.composer

import com.wasimaster.wmkeyboard.core.input.rime.RimeStore
import java.io.File

/**
 * The homophone search's tables on the device: which [HomophoneIndex] a
 * language reads, built from what is installed and kept until a table
 * changes.
 *
 * - Chinese (Pinyin, Zhuyin, T9, and Cangjie, stroke or Wubi typists with the
 *   pinyin pack): the pinyin pack's readings, which carry no tones.
 * - Cantonese: the Jyutping pack.
 * - Japanese: the kana pack (`ja_kana`); a kana before the caret is its own
 *   reading, so the kanji spelled by it are listed.
 * - RIME: its schemas' `.dict.yaml` sources under `rime/shared/`, for a
 *   language whose pack is not on the device (Jyutping's for Cantonese, the
 *   rest for Chinese). The compiled tables librime reads have no reverse
 *   lookup this side can use.
 *
 * Building walks a whole table (a million rows for kana), so it runs off the
 * main thread, once per [CjkDictionaries.generation] and RIME deploy.
 */
object Homophones {

    private class Cached(val key: String, val index: HomophoneIndex)

    @Volatile
    private var cache: Map<String, Cached> = emptyMap()

    /** Which table [langId] reads, and in which alphabet; null for a language with none. */
    private fun readingFor(langId: String): ConversionWordLists.Reading? =
        ConversionWordLists.readingFor(langId.substringBefore('_').substringBefore('-'))

    /**
     * The characters that sound like [char] in [langId]'s tables, [char]
     * left out. Empty when nothing installed knows it. Blocking: call off the
     * main thread.
     */
    fun of(filesDir: File?, langId: String, char: String, limit: Int = HomophoneIndex.DEFAULT_LIMIT): List<String> {
        if (!HomophoneIndex.isSearchable(char)) return emptyList()
        // A kana is Japanese whatever the keyboard is set to.
        val script = Character.UnicodeScript.of(char.codePointAt(0))
        val kana = script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA
        val reading = if (kana) ConversionWordLists.Reading.KANA else readingFor(langId) ?: ConversionWordLists.Reading.PINYIN
        val index = indexFor(filesDir, reading)
        return index.homophones(char, limit, fallbackReading = if (kana) toHiragana(char) else null)
    }

    /** Forgets every index; the next search builds afresh. */
    fun invalidate() {
        cache = emptyMap()
    }

    private fun indexFor(filesDir: File?, reading: ConversionWordLists.Reading): HomophoneIndex {
        val table = when (reading) {
            ConversionWordLists.Reading.PINYIN -> CjkDictionaries.pinyin
            ConversionWordLists.Reading.JYUTPING -> CjkDictionaries.jyutping
            ConversionWordLists.Reading.KANA -> CjkDictionaries.japanese
        }
        val rimeDicts = if (table.isEmpty && reading != ConversionWordLists.Reading.KANA && filesDir != null) {
            rimeDictFiles(filesDir, reading)
        } else {
            emptyList()
        }
        val key = "${CjkDictionaries.generation}:${table.size}:" +
            rimeDicts.joinToString(",") { "${it.name}@${it.lastModified()}" }
        cache[reading.name]?.takeIf { it.key == key }?.let { return it.index }
        val index = HomophoneIndex.build(
            if (!table.isEmpty) entriesOf(table) else rimeDicts.asSequence().flatMap { rimeEntries(it, reading) },
        )
        cache = cache + (reading.name to Cached(key, index))
        return index
    }

    /**
     * [table]'s single-character rows. The table answers by reading, so the
     * readings that hold a one-character word are found in one walk and each
     * is then asked for its words, best first.
     */
    private fun entriesOf(table: ConversionDictionary): Sequence<HomophoneIndex.Entry> {
        val readings = LinkedHashSet<String>()
        // Two UTF-16 units: a character outside the Basic Multilingual Plane.
        table.forEachRow { reading, wordLength, _ -> if (wordLength <= 2) readings.add(reading) }
        return readings.asSequence().flatMap { reading ->
            val words = table.exact(reading).filter(HomophoneIndex::isOneCharacter)
            words.asSequence().mapIndexed { i, word ->
                HomophoneIndex.Entry(reading, word, (words.size - i).toDouble())
            }
        }
    }

    /** The `.dict.yaml` sources RIME's schemas put down that spell [reading]'s alphabet. */
    private fun rimeDictFiles(filesDir: File, reading: ConversionWordLists.Reading): List<File> {
        val shared = RimeStore.sharedDir(filesDir)
        if (!shared.isDirectory) return emptyList()
        return shared.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".dict.yaml") }
            .filter { file ->
                val cantonese = "jyut" in file.name.lowercase()
                if (reading == ConversionWordLists.Reading.JYUTPING) cantonese else !cantonese
            }
            .sortedBy { it.path }
            .toList()
    }

    /** One `.dict.yaml` file's entries; nothing for a file that cannot be read. */
    private fun rimeEntries(file: File, reading: ConversionWordLists.Reading): Sequence<HomophoneIndex.Entry> =
        runCatching { file.useLines { rimeEntries(it, reading).toList() } }.getOrDefault(emptyList()).asSequence()

    /** A `.dict.yaml`'s single-character lines, read as an imported list is. */
    internal fun rimeEntries(lines: Sequence<String>, reading: ConversionWordLists.Reading): Sequence<HomophoneIndex.Entry> =
        ConversionWordLists.rows(lines.filter(::singleCharacterLine), reading).asSequence()
            .map { HomophoneIndex.Entry(it.reading, it.word, it.frequency.toDouble()) }

    /** A `.dict.yaml` line whose word, the first field, is one character: skips the phrase rows cheaply. */
    internal fun singleCharacterLine(line: String): Boolean {
        val tab = line.indexOf('\t')
        return tab > 0 && HomophoneIndex.isOneCharacter(line.substring(0, tab))
    }

    /** Katakana as hiragana, the alphabet the kana pack's readings are in. */
    internal fun toHiragana(text: String): String = buildString {
        for (c in text) append(if (c in 'ァ'..'ヶ') c - 0x60 else c)
    }
}
