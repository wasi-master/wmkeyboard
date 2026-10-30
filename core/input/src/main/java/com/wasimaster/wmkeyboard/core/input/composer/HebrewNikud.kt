package com.wasimaster.wmkeyboard.core.input.composer

import android.content.Context
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.NotInBuild
import java.io.File
import java.text.Normalizer

/**
 * Adds niqqud to Hebrew text with Phonikud, a DictaBERT model that reads a sentence a
 * character at a time: עולם becomes עוֹלָם. It was trained for speech, so a few points
 * follow how a word sounds today rather than the grammar books.
 *
 * Two things have to be on the phone: the `onnx` gate's runtime, which the voice engines
 * and neural phonetic readings share, and the [PACK_ID] model, 308 MB. Opened on a Mac it
 * took 0.6 seconds to load and 475 MB of memory, rising to 930 MB on a long text, so it is
 * never held between uses: [release] lets it go, and the caller is expected to call that
 * soon after the last [add].
 */
object HebrewNikud {

    const val PACK_ID = "phonikud_model"

    private const val VOCAB_ASSET = "nikud/dictabert-vocab.txt"
    private const val THREADS = 2

    private var session = 0L
    private var vocab: Map<Char, Int>? = null

    /** This build can have the runtime at all. */
    val supported: Boolean get() = FeatureModules.onnx !== NotInBuild

    /** Everything is on the phone, so [add] can answer. A few file checks at most. */
    fun ready(filesDir: File): Boolean = supported && FeatureModules.onnx.installed && modelPack(filesDir) != null

    private fun modelPack(filesDir: File): File? = CjkDictStore.downloadedFileFor(filesDir, PACK_ID)

    /** [text] with its points, any it had replaced; null when the model could not run. Slow: never on the main thread. */
    @Synchronized
    fun add(context: Context, text: String): String? {
        if (!ready(context.filesDir)) return null
        return runCatching {
            val words = vocab ?: NikudCodec.parseVocab(context.assets.open(VOCAB_ASSET).bufferedReader().use { it.readText() }).also { vocab = it }
            if (session == 0L) session = open(context.filesDir)
            buildString {
                for (chunk in NikudCodec.chunks(NikudCodec.stripPoints(text))) {
                    val tokens = NikudCodec.tokenize(chunk, words)
                    val classes = requireNotNull(NikudNative.nativeRun(session, tokens.ids)) { "The run failed" }
                    append(NikudCodec.apply(chunk, tokens, classes))
                }
            }
        }.getOrNull()
    }

    /** Frees the model. The next [add] opens it again. */
    @Synchronized
    fun release() {
        val open = session
        if (open == 0L) return
        session = 0L
        runCatching { NikudNative.nativeClose(open) }
    }

    private fun open(filesDir: File): Long {
        check(NikudNative.load()) { "No runtime" }
        val handle = NikudNative.nativeOpen(requireNotNull(modelPack(filesDir)).path, THREADS)
        check(handle != 0L) { "The model did not open" }
        return handle
    }
}

/**
 * Text to token ids and the model's answer back to text, following Phonikud's own
 * Python and DictaBERT's tokenizer. No Android classes, so it runs in the JVM tests.
 */
internal object NikudCodec {

    private const val UNK = 0
    private const val CLS = 1
    private const val SEP = 2
    private const val FIRST_CHARACTER_ID = 6

    /** The model's window is 2048 tokens. A little below, since normalizing can make one character several. */
    private const val MAX_CHUNK = 2000

    private const val SHIN = 'ש'
    private const val SHIN_DOT = 'ׁ'
    private const val SIN_DOT = 'ׂ'
    private const val MAT_LECT = 1

    /** The model's classes, in its order. The second, [MAT_LECT], marks a letter standing for a vowel, which takes no point. */
    private val POINTS = arrayOf(
        "", "", "ּ", "ְ", "ֱ", "ֲ", "ֳ", "ִ", "ֵ", "ֶ", "ַ", "ָ", "ֹ", "ֺ", "ֻ",
        "ְּ", "ֱּ", "ֲּ", "ֳּ", "ִּ", "ֵּ", "ֶּ", "ַּ", "ָּ",
        "ֹּ", "ֺּ", "ֻּ", "ׇ", "ׇּ",
    )

    /** The file's one character per line, as hex, ids counting up from the first after the special tokens. */
    fun parseVocab(file: String): Map<Char, Int> {
        val out = HashMap<Char, Int>()
        file.lineSequence().filter { it.isNotEmpty() }.forEachIndexed { i, line -> out[line.toInt(16).toChar()] = FIRST_CHARACTER_ID + i }
        return out
    }

    /**
     * Points and cantillation out, as the model expects. Phonikud's own takes out the
     * block's punctuation and `|` too, which a text being rewritten should keep.
     */
    fun stripPoints(text: String): String = text.filterNot { c ->
        c.code in 0x0591..0x05BD || c == 'ֿ' || c == 'ׁ' || c == 'ׂ' || c == 'ׄ' || c == 'ׅ' || c == 'ׇ'
    }

    /** Pieces the model can take, cut at a line or sentence end or at worst a space. */
    fun chunks(text: String, max: Int = MAX_CHUNK): List<String> {
        val out = ArrayList<String>()
        var start = 0
        while (text.length - start > max) {
            val end = start + max
            var cut = (end - 1 downTo start + 1).firstOrNull { text[it] == '\n' || text[it] == '.' || text[it] == '׃' }
                ?: (end - 1 downTo start + 1).firstOrNull { text[it].isWhitespace() }
                ?: (end - 1)
            // Never between a surrogate pair.
            if (text[cut].isHighSurrogate()) cut--
            out += text.substring(start, cut + 1)
            start = cut + 1
        }
        out += text.substring(start)
        return out
    }

    /** [ids] for the model, and [tokenOf] which of them each character of the text is. */
    class Tokens(val ids: IntArray, val tokenOf: IntArray)

    /**
     * DictaBERT's tokenizer: each character by itself after NFKC, lower case and no
     * combining marks, and a run of anything outside the scripts it knows as one unknown token.
     */
    fun tokenize(text: String, vocab: Map<Char, Int>): Tokens {
        val ids = ArrayList<Int>(text.length + 2)
        ids += CLS
        val tokenOf = IntArray(text.length)
        var inForeignRun = false
        text.forEachIndexed { index, c ->
            tokenOf[index] = ids.size
            for (n in normalized(c)) {
                if (known(n)) {
                    ids += vocab[n] ?: UNK
                    inForeignRun = false
                } else if (!inForeignRun) {
                    ids += UNK
                    inForeignRun = true
                }
            }
        }
        ids += SEP
        return Tokens(ids.toIntArray(), tokenOf)
    }

    private fun normalized(c: Char): String =
        Normalizer.normalize(c.toString(), Normalizer.Form.NFKC).lowercase()
            .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }

    private fun known(c: Char): Boolean = when (c.code) {
        in 0x0590..0x05FF, in 0x0000..0x007F, in 0x200C..0x203F, in 0x20A0..0x20BF,
        in 0x2200..0x22FF, in 0x2150..0x218B, in 0xFB00..0xFB4F,
        -> true
        else -> false
    }

    /** [text] with the points [classes] names: two ints per token, its point and its shin dot. */
    fun apply(text: String, tokens: Tokens, classes: IntArray): String = buildString(text.length * 2) {
        text.forEachIndexed { index, c ->
            append(c)
            if (c in 'א'..'ת') {
                val token = tokens.tokenOf[index]
                if (c == SHIN) append(if (classes[token * 2 + 1] == 0) SHIN_DOT else SIN_DOT)
                val point = classes[token * 2]
                if (point != MAT_LECT) POINTS.getOrNull(point)?.let(::append)
            }
        }
    }
}

/** The JNI of `native/nikud-jni`; serialized by [HebrewNikud]. Handles are longs, 0 on failure. */
internal object NikudNative {

    @Volatile
    private var loaded = false

    /** Loads the runtime, then this library against it. A failure is not remembered: on Play the split may arrive later. */
    @Synchronized
    fun load(): Boolean {
        if (loaded) return true
        val ok = try {
            FeatureModules.loadLibrary("onnxruntime")
            FeatureModules.loadLibrary("wmnikud")
            true
        } catch (_: Throwable) {
            false
        }
        loaded = ok
        return ok
    }

    @JvmStatic
    external fun nativeOpen(model: String, threads: Int): Long

    @JvmStatic
    external fun nativeClose(handle: Long)

    /** Two ints per token, its point class and shin class; null when the run failed. */
    @JvmStatic
    external fun nativeRun(handle: Long, ids: IntArray): IntArray?
}
