package com.wasimaster.wmkeyboard.core.imagegen

import java.io.File
import java.text.Normalizer
import java.util.Locale

/**
 * CLIP's byte-level BPE tokenizer, the one every Stable Diffusion text
 * encoder was trained with, done the way Hugging Face's `CLIPTokenizer` does
 * it (which is what diffusers feeds the model): NFC, whitespace collapsed,
 * lowercase, OpenAI's pre-tokenising pattern, then byte-level BPE over
 * `merges.txt`.
 *
 * The vocabulary is built from the merges alone, as OpenAI's own
 * `simple_tokenizer` does: the 256 byte symbols, the same with `</w>`, one
 * entry per merge, then the two specials. That is exactly the published
 * `vocab.json` (checked for SD 1.x and SDXS: 49,408 entries, identical), so
 * a model bundle only has to carry the merges.
 *
 * A prompt has room for [MAX_TOKENS] - 2 tokens between the start and end
 * markers; [prompt] says which words fell past that, so the UI can show them
 * instead of dropping them silently.
 */
class ClipTokenizer(mergeLines: Sequence<String>) {

    /** A prompt ready for the text encoder. */
    class Prompt(
        /** [MAX_TOKENS] ids: start, the prompt, end, then padding. */
        val ids: IntArray,
        /** Tokens of the prompt the encoder sees, at most [MAX_TOKENS] - 2. */
        val used: Int,
        /** Tokens the whole prompt needed. */
        val needed: Int,
        /** The words that did not fit, as the tokenizer saw them (lowercased); empty when all did. */
        val ignored: String,
    )

    private val ranks = HashMap<String, Int>()
    private val encoder = HashMap<String, Int>()
    private val cache = HashMap<String, List<String>>()

    init {
        val merges = mergeLines
            .filter { it.isNotBlank() && !it.startsWith("#version") }
            .take(MERGES)
            .map { line -> line.trim().split(' ').let { it[0] to it[1] } }
            .toList()
        val vocab = ArrayList<String>(merges.size + 2 * VOCAB_BYTES.size + 2)
        vocab.addAll(VOCAB_BYTES)
        VOCAB_BYTES.mapTo(vocab) { it + END_OF_WORD }
        merges.mapTo(vocab) { it.first + it.second }
        vocab.add(START)
        vocab.add(END)
        vocab.forEachIndexed { i, v -> encoder[v] = i }
        merges.forEachIndexed { i, (a, b) -> ranks[pairKey(a, b)] = i }
    }

    val startId: Int = encoder.getValue(START)
    val endId: Int = encoder.getValue(END)

    /** The prompt's token ids, without start or end markers. */
    fun encode(text: String): IntArray = pieces(clean(text)).flatMap { it.second.asIterable() }.toIntArray()

    /**
     * [text] as the encoder's input: start, as many tokens as fit, end, then
     * [pad] (the end marker for SD 1.x's tokenizer, `!` (0) for SD 2's).
     */
    fun prompt(text: String, pad: Int = endId): Prompt {
        val cleaned = clean(text)
        val room = MAX_TOKENS - 2
        val ids = IntArray(MAX_TOKENS) { pad }
        ids[0] = startId
        var used = 0
        var needed = 0
        var cutAt = -1
        for ((start, tokens) in pieces(cleaned)) {
            needed += tokens.size
            for (id in tokens) {
                if (used < room) {
                    ids[1 + used] = id
                    used++
                } else if (cutAt < 0) {
                    cutAt = start
                }
            }
        }
        ids[1 + used] = endId
        return Prompt(ids, used, needed, if (cutAt >= 0) cleaned.substring(cutAt).trim() else "")
    }

    /** Each pre-token's start in [cleaned] and its ids. */
    private fun pieces(cleaned: String): List<Pair<Int, IntArray>> {
        val out = ArrayList<Pair<Int, IntArray>>()
        val m = PATTERN.matcher(cleaned)
        while (m.find()) {
            val piece = m.group()
            val symbols = buildString { for (b in piece.toByteArray(Charsets.UTF_8)) append(BYTE_TO_SYMBOL[b.toInt() and 0xff]) }
            val ids = bpe(symbols).map { encoder[it] ?: encoder.getValue(END) }.toIntArray()
            out.add(m.start() to ids)
        }
        return out
    }

    private fun bpe(token: String): List<String> {
        cache[token]?.let { return it }
        var word: List<String> = token.dropLast(1).map { it.toString() } + (token.last() + END_OF_WORD)
        while (word.size > 1) {
            var best = Int.MAX_VALUE
            var at = -1
            for (i in 0 until word.size - 1) {
                val r = ranks[pairKey(word[i], word[i + 1])] ?: continue
                if (r < best) {
                    best = r
                    at = i
                }
            }
            if (at < 0) break
            val a = word[at]
            val b = word[at + 1]
            val merged = ArrayList<String>(word.size)
            var i = 0
            while (i < word.size) {
                if (i < word.size - 1 && word[i] == a && word[i + 1] == b) {
                    merged.add(a + b)
                    i += 2
                } else {
                    merged.add(word[i])
                    i++
                }
            }
            word = merged
        }
        if (cache.size > CACHE_LIMIT) cache.clear()
        cache[token] = word
        return word
    }

    companion object {
        /** CLIP's context: start marker, 75 tokens, end marker. */
        const val MAX_TOKENS = 77

        const val START = "<|startoftext|>"
        const val END = "<|endoftext|>"
        private const val END_OF_WORD = "</w>"

        /** 49,152 - 256 - 2: the merges the vocabulary has room for. */
        private const val MERGES = 48_894
        private const val CACHE_LIMIT = 20_000

        /**
         * GPT-2's map from bytes to printable characters: the printable
         * Latin-1 ranges keep their own code, every other byte is shifted
         * past 255. [VOCAB_BYTES] is the map's own order (printable ranges
         * first), which is the first 256 vocabulary ids; [BYTE_TO_SYMBOL] is
         * indexed by byte value, for encoding.
         */
        private val BYTE_MAP: Pair<List<String>, Array<String>> = run {
            val bytes = ArrayList<Int>()
            bytes.addAll('!'.code..'~'.code)
            bytes.addAll('¡'.code..'¬'.code)
            bytes.addAll('®'.code..'ÿ'.code)
            val chars = ArrayList(bytes)
            var n = 0
            for (b in 0 until 256) {
                if (b !in bytes) {
                    bytes.add(b)
                    chars.add(256 + n)
                    n++
                }
            }
            val byByte = Array(256) { "" }
            for (i in bytes.indices) byByte[bytes[i]] = chars[i].toChar().toString()
            chars.map { it.toChar().toString() } to byByte
        }
        private val VOCAB_BYTES: List<String> = BYTE_MAP.first
        private val BYTE_TO_SYMBOL: Array<String> = BYTE_MAP.second

        // No UNICODE_CHARACTER_CLASS: Android's Pattern throws on that flag,
        // which made this companion fail to initialise and every on-device
        // model read as unreadable (#1398). Android's regex is ICU, where
        // \s, \p{L} and \p{N} are Unicode already; \p{Z} keeps the JVM's
        // ASCII-only \s from splitting no-break spaces off as symbols in tests.
        private val PATTERN = java.util.regex.Pattern.compile(
            "<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|\\p{L}+|\\p{N}|[^\\s\\p{Z}\\p{L}\\p{N}]+",
            java.util.regex.Pattern.CASE_INSENSITIVE,
        )

        private val SPACES = Regex("\\s+")

        private fun pairKey(a: String, b: String) = "$a\u0001$b"

        fun clean(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFC).replace(SPACES, " ").trim().lowercase(Locale.ROOT)

        fun fromFile(merges: File): ClipTokenizer = merges.bufferedReader(Charsets.UTF_8).useLines { ClipTokenizer(it) }
    }
}
