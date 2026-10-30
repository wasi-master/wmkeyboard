package com.wasimaster.wmkeyboard.core.input.composer

import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.NotInBuild
import java.io.File

/**
 * Adds tashkeel to Arabic text with CATT (abjadai, Apache-2.0), its encoder-only model
 * exported to ONNX as one graph and quantized to int8: كتب becomes كَتَبَ.
 *
 * Two things have to be on the phone: the `onnx` gate's runtime, and the [PACK_ID] model,
 * 21 MB. On a Mac it opened in 0.1 seconds and took 145 MB of memory, so it is let go soon
 * after use: the caller is expected to call [release] a while after the last [add].
 */
object ArabicTashkeel {

    const val PACK_ID = "catt_tashkeel"

    private const val THREADS = 2

    private var session = 0L

    /** This build can have the runtime at all. */
    val supported: Boolean get() = FeatureModules.onnx !== NotInBuild

    /** Everything is on the phone, so [add] can answer. A few file checks at most. */
    fun ready(filesDir: File): Boolean = supported && FeatureModules.onnx.installed && modelPack(filesDir) != null

    private fun modelPack(filesDir: File): File? = CjkDictStore.downloadedFileFor(filesDir, PACK_ID)

    /** [text] with its tashkeel, any it had replaced; null when the model could not run. Never on the main thread. */
    @Synchronized
    fun add(filesDir: File, text: String): String? {
        if (!ready(filesDir)) return null
        return runCatching {
            if (session == 0L) session = open(filesDir)
            buildString {
                for (chunk in NikudCodec.chunks(TashkeelCodec.stripMarks(text), TashkeelCodec.MAX_LETTERS)) {
                    val tokens = TashkeelCodec.tokenize(chunk)
                    if (tokens.ids.isEmpty()) {
                        append(chunk)
                        continue
                    }
                    val classes = requireNotNull(NikudNative.nativeTag(session, tokens.ids)) { "The run failed" }
                    append(TashkeelCodec.apply(chunk, tokens, classes))
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
 * Text to CATT's letter ids and its answer back to text, following its tokenizer: Arabic
 * letters in Buckwalter's order, and anything else, however much of it, one space. No
 * Android classes, so it runs in the JVM tests.
 */
internal object TashkeelCodec {

    /** The model's window is 1024 letters. */
    const val MAX_LETTERS = 1000

    private const val SPACE_ID = 3
    private const val SHADDA = 'ّ'

    /** Letter to id, by the order of the model's table. */
    private val LETTERS: Map<Char, Int> = mapOf(
        'ش' to 4, 'ؤ' to 5, 'ء' to 6, 'ذ' to 7, 'إ' to 8, 'أ' to 9, 'ا' to 10,
        'ض' to 11, 'ع' to 12, 'ح' to 13, 'ص' to 14, 'ط' to 15, 'ى' to 16, 'ظ' to 17,
        'ب' to 18, 'د' to 19, 'ف' to 20, 'غ' to 21, 'ه' to 22, 'ج' to 23, 'ك' to 24,
        'ل' to 25, 'م' to 26, 'ن' to 27, 'ة' to 28, 'ق' to 29, 'ر' to 30, 'س' to 31,
        'ت' to 32, 'ث' to 33, 'و' to 34, 'خ' to 35, 'ي' to 36, 'ز' to 37, 'آ' to 38,
        'ئ' to 39,
        // Hamzat wasl reads as a plain alif, as the tokenizer has it.
        'ٱ' to 10,
    )

    /** By class id. The first four are padding, the two ends and "nothing". */
    private val MARKS = arrayOf(
        "", "", "", "",
        "$SHADDAُ", "$SHADDAٌ", "$SHADDAَ", "$SHADDAً", "$SHADDAِ", "$SHADDAٍ",
        "ً", "ٍ", "ٌ", "َ", "ِ", "ْ", "ُ", "$SHADDA",
    )

    /** Marks and the stretching tatweel out, as the model expects. */
    fun stripMarks(text: String): String = text.filterNot { it in 'ً'..'ْ' || it == 'ـ' || it == 'ٰ' }

    /** [ids] for the model, and [tokenOf] which of them each letter of the text is, -1 for anything else. */
    class Tokens(val ids: IntArray, val tokenOf: IntArray)

    fun tokenize(text: String): Tokens {
        val ids = ArrayList<Int>(text.length)
        val tokenOf = IntArray(text.length) { -1 }
        text.forEachIndexed { index, c ->
            val letter = LETTERS[c]
            if (letter != null) {
                tokenOf[index] = ids.size
                ids += letter
            } else if (ids.isNotEmpty() && ids.last() != SPACE_ID) {
                ids += SPACE_ID
            }
        }
        if (ids.isNotEmpty() && ids.last() == SPACE_ID) ids.removeAt(ids.lastIndex)
        return Tokens(ids.toIntArray(), tokenOf)
    }

    /** [text] with the marks [classes] names, one int per token. Everything but the letters stays as it was. */
    fun apply(text: String, tokens: Tokens, classes: IntArray): String = buildString(text.length * 2) {
        text.forEachIndexed { index, c ->
            append(c)
            val token = tokens.tokenOf[index]
            if (token >= 0) MARKS.getOrNull(classes[token])?.let(::append)
        }
    }
}
