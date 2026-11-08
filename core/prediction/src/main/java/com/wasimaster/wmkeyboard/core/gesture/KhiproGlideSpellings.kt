package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.MappedTrie
import com.wasimaster.wmkeyboard.core.prediction.PackedTrie
import com.wasimaster.wmkeyboard.core.prediction.PackedTrieCodec
import com.wasimaster.wmkeyboard.core.prediction.WordSource
import com.wasimaster.wmkeyboard.core.transliteration.Khipro
import com.wasimaster.wmkeyboard.core.transliteration.KhiproRomanizer
import com.wasimaster.wmkeyboard.core.transliteration.SortedWords
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

/**
 * The Khipro spellings of a Bangla word list, worked out on the device and
 * kept on disk, so a swipe over the Khipro grid can be read as any word of the
 * list (#541): the bundled one, a downloaded one, an imported one, all of it.
 *
 * Every word goes through [KhiproRomanizer], which runs Khipro's own rules
 * backwards and keeps a spelling only if Khipro turns it back into the word.
 * That is a few hundred microseconds a word, minutes for a 450,000-word list
 * on a phone, so it is done once, on every core, and written as a `.wmdict`
 * that is mapped rather than read; it is redone only when the list changes.
 *
 * The data repo publishes those spellings for its own list
 * (`bn_khipro_glide.txt.gz`, see `KhiproGlideDownloads`), worked out once on a
 * computer, and a list built with that file at hand reads its words' spellings
 * from it: only the words it does not list (the bundled list's extras, an
 * import) are spelled on the device, which is seconds rather than minutes of
 * every core (#593).
 *
 * Khipro Dark spells by its own rules (হবে is `hobe` in Classic, `hbe` in
 * Dark), so its spellings are worked out and kept apart, in
 * `khipro_dark_glide.wmdict`, from its own table: a Dark swipe decoded
 * against Classic's spellings read `hbe` as হচ্ছে, whose `hccfe` draws nearly
 * the same stroke.
 *
 * The slicer `/` counts as a key a stroke passes through, so words with
 * চন্দ্রবিন্দু and খণ্ড-ত are spelled too; a word that needs any other
 * non-letter key has no spelling and is typed.
 */
object KhiproGlideSpellings {

    /** Bumped whenever the romanizer's output could change, so old caches are rebuilt. */
    private const val VERSION = 2

    private fun fileName(dark: Boolean) = if (dark) "khipro_dark_glide.wmdict" else "khipro_glide.wmdict"
    private fun signatureName(dark: Boolean) = if (dark) "khipro_dark_glide.sig" else "khipro_glide.sig"

    /** Words per task handed to a core. */
    private const val CHUNK = 2_000

    /**
     * The spellings of [words], from [dir] when they were already worked out
     * for this exact list, built and saved there otherwise. Null when they
     * could not be built (a full disk); the caller then has nothing to glide
     * with, the same as before.
     *
     * [downloadedSpec] is the revision of a Khipro spec set downloaded since
     * the bundled one (`KhiproSpecs`), or null while the bundled specs type.
     * The spellings are redone under a new one, and [premade]'s, worked out
     * under the bundled specs, count only where Khipro still turns them into
     * their word.
     *
     * [dark] spells in Khipro Dark rather than Classic; [premade] must then be
     * Dark's table.
     */
    fun load(
        words: SortedWords,
        dir: File,
        premade: File? = null,
        downloadedSpec: String? = null,
        dark: Boolean = false,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): WordSource? {
        val file = File(dir, fileName(dark))
        val signatureFile = File(dir, signatureName(dark))
        val table = premade?.takeIf { it.isFile }
        // The table's size is part of the signature, so a cache worked out on
        // the device is replaced once the table arrives, and again if a newer
        // one does; its spellings are the same, only some were missing.
        val signature = signatureOf(words) + (table?.let { ":p${it.length()}" } ?: "") +
            (downloadedSpec?.let { ":k$it" } ?: "")
        if (file.isFile && runCatching { signatureFile.readText() }.getOrNull() == signature) {
            MappedTrie.open(file)?.let { return it }
        }
        val trie = build(words, table, verify = downloadedSpec != null, dark, onProgress)
        return runCatching {
            dir.mkdirs()
            val part = File(dir, "${file.name}.part")
            part.outputStream().buffered().use { PackedTrieCodec.write(trie, it) }
            check(part.renameTo(file)) { "could not move ${part.name} into place" }
            signatureFile.writeText(signature)
            MappedTrie.open(file)
        }.getOrNull() ?: trie
    }

    /** The spellings of a few words held in memory: the user's own, which change as they type. */
    fun ofWords(words: List<Pair<String, Int>>, dark: Boolean = false): WordSource {
        val spelled = ArrayList<String>()
        val frequencies = ArrayList<Int>()
        for ((word, frequency) in words) {
            for (spelling in KhiproRomanizer.spellings(word, dark)) {
                spelled += spelling
                frequencies += frequency.coerceAtLeast(1)
            }
        }
        return PackedTrie.of(spelled.toTypedArray<String?>(), frequencies.toIntArray(), spelled.size)
    }

    /** [onProgress] hears from the worker threads, a chunk at a time; only a build reports. */
    private fun build(
        words: SortedWords,
        table: File?,
        verify: Boolean,
        dark: Boolean,
        onProgress: ((Int, Int) -> Unit)?,
    ): PackedTrie {
        val listed = BooleanArray(words.size)
        val premade = table?.let { runCatching { readTable(words, it, listed, verify, dark) }.getOrNull() }
        if (premade == null) listed.fill(false)
        // Only the words the table did not cover are worked out here, and only
        // that work is reported: a list the table covers builds in seconds and
        // raises no progress notification at all.
        val todo = (0 until words.size).filter { !listed[it] }
        if (todo.isNotEmpty()) {
            onProgress?.invoke(0, todo.size)
            KhiproRomanizer.warm(dark)
        }
        val finished = AtomicInteger()
        val cores = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val pool = Executors.newFixedThreadPool(cores)
        try {
            val tasks = todo.chunked(CHUNK).map { chunk ->
                Callable {
                    val spelled = ArrayList<String>()
                    val frequencies = ArrayList<Int>()
                    for (i in chunk) {
                        val frequency = frequencyOf(words, i)
                        for (spelling in KhiproRomanizer.spellings(words.word(i), dark)) {
                            spelled += spelling
                            frequencies += frequency
                        }
                    }
                    val done = finished.addAndGet(chunk.size)
                    onProgress?.invoke(done, todo.size)
                    spelled to frequencies
                }
            }
            val parts = listOfNotNull(premade) + pool.invokeAll(tasks).map { it.get() }
            val count = parts.sumOf { it.first.size }
            val all = arrayOfNulls<String>(count)
            val frequencies = IntArray(count)
            var at = 0
            for ((spelled, freqs) in parts) {
                for (j in spelled.indices) {
                    all[at] = spelled[j]
                    frequencies[at] = freqs[j]
                    at++
                }
            }
            // Sorts both arrays in place; a spelling met twice keeps its larger frequency.
            return PackedTrie.of(all, frequencies, count)
        } finally {
            pool.shutdown()
        }
    }

    private fun frequencyOf(words: SortedWords, i: Int): Int =
        (-words.rank(i)).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()

    /**
     * The spellings [table] lists for words of [words], with their frequencies,
     * marking each word it covered in [listed]. A table line is
     * `word<TAB>spelling[<TAB>spelling…]`; its words outside the list (a list
     * downloaded at a smaller size) are passed over. With [verify], a spelling
     * counts only if Khipro still converts it to the word, and a word with
     * none left is not marked, so it is spelled on the device.
     */
    private fun readTable(
        words: SortedWords,
        table: File,
        listed: BooleanArray,
        verify: Boolean,
        dark: Boolean,
    ): Pair<List<String>, List<Int>> {
        val variant = Khipro.Variant.of(dark, desktop = false)
        val spelled = ArrayList<String>()
        val frequencies = ArrayList<Int>()
        GZIPInputStream(table.inputStream()).bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isEmpty() || line[0] == '#') continue
                val fields = line.split('\t')
                if (fields.size < 2) continue
                val i = indexOf(words, fields[0])
                if (i < 0 || listed[i]) continue
                val frequency = frequencyOf(words, i)
                for (f in 1 until fields.size) {
                    if (fields[f].isEmpty()) continue
                    if (verify && Khipro.convert(fields[f], variant) != fields[0]) continue
                    spelled += fields[f]
                    frequencies += frequency
                    listed[i] = true
                }
            }
        }
        return spelled to frequencies
    }

    /** Where [word] is in [words], which are in [String.compareTo] order, or -1. */
    private fun indexOf(words: SortedWords, word: String): Int {
        var lo = 0
        var hi = words.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val order = compare(words, mid, word)
            when {
                order < 0 -> lo = mid + 1
                order > 0 -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /** [String.compareTo] of word [i] against [word], without building the word. */
    private fun compare(words: SortedWords, i: Int, word: String): Int {
        val length = words.length(i)
        val common = minOf(length, word.length)
        for (j in 0 until common) {
            val diff = words.charAt(i, j) - word[j]
            if (diff != 0) return diff
        }
        return length - word.length
    }

    /** Which list the cache was built for: its size and every word, hashed, and the romanizer's version. */
    private fun signatureOf(words: SortedWords): String {
        var hash = 1125899906842597L
        for (i in 0 until words.size) {
            val length = words.length(i)
            for (j in 0 until length) hash = 31 * hash + words.charAt(i, j).code
            hash = 31 * hash + words.rank(i)
        }
        return "v$VERSION:${words.size}:$hash"
    }
}
