package com.wasimaster.wmkeyboard.core.voice.sherpa

import com.wasimaster.wmkeyboard.core.voice.VoiceStream
import com.wasimaster.wmkeyboard.core.voice.executorch.EtEngine
import com.wasimaster.wmkeyboard.core.voice.executorch.EtModel
import com.wasimaster.wmkeyboard.core.voice.executorch.EtStore
import com.wasimaster.wmkeyboard.core.voice.vad.PhraseEnd
import com.wasimaster.wmkeyboard.core.voice.vad.VoiceVad
import java.io.File

/**
 * Offline Qwen3-ASR dictation (#1437): Alibaba's Qwen3-ASR, an audio encoder
 * in front of a Qwen3 language model, run by sherpa-onnx's offline recognizer
 * on ONNX Runtime (qwen3_jni.cpp in libwmsherpa). It is not a streaming model,
 * so as with [OmnilingualEngine] the phrase end detector ([VoiceVad]; Silero
 * unless the user picked another) cuts the microphone's audio at each short
 * pause, and the phrase before it is decoded whole while the user goes on.
 *
 * It writes punctuation and capitals itself. A dictation in one language
 * tells the model which ([Qwen3Languages.promptName]); one in several lets it
 * tell them apart. The words to listen for go into its prompt as hotwords.
 *
 * The 0.6B model takes over a gigabyte of memory once open, so it stays open
 * between dictations only until a memory trim, and opening it frees Sherpa's
 * and Omnilingual's models first. Every call here runs on [SherpaNative.dispatcher].
 */
object Qwen3AsrEngine {

    /** Decoder threads: one phrase at a time, so four keep the wait after it short. */
    private const val THREADS = 4

    /** The pause that closes a phrase for decoding, as Omnilingual's. */
    private const val PHRASE_PAUSE_SECONDS = 0.5f

    /**
     * Past this much talk without a pause the detector cuts. The decoder's
     * cache holds the prompt, the audio (about 13 tokens a second) and what
     * it writes; at 15 s all three stay under the 512 the exports allow.
     */
    private const val MAX_PHRASE_SECONDS = 15f

    /** What the cache may hold at most; sherpa-onnx keeps it under the export's own size. */
    private const val MAX_TOTAL_LEN = 1_024

    /** The decoder's default cap; each phrase sets its own by its length ([maxTokensFor]). */
    private const val MAX_NEW_TOKENS = 256

    /** Words to listen for that go into one phrase's prompt: each one costs room in the cache. */
    private const val MAX_HOTWORDS = 24

    private var recognizer = 0L
    private var recognizerDir: String? = null

    /** A `libwmsherpa.so` from before Qwen3-ASR. */
    private var bridgeMissing = false

    /** Streams not yet closed, which [release] waits for; see [SherpaEngine]. */
    private var liveStreams = 0

    /** Whether the model is open, so a memory trim knows there is something to free. Any thread. */
    @Volatile var isOpen: Boolean = false
        private set

    /** Opens [model] ahead of a phrase. Throws when it will not load. */
    fun warm(filesDir: File, model: EtModel) {
        recognizerFor(filesDir, model)
    }

    /**
     * A fresh dictation on [model] in [languageIds] (keyboard language ids).
     * [endpointMs] is the pause that ends it, or 0 for none (the user stops
     * it); the detector [vad] names cuts the phrases and hears that pause.
     * [words] are the words to listen for, most important first. Throws when
     * the model will not load.
     */
    fun newStream(
        filesDir: File,
        model: EtModel,
        endpointMs: Int,
        languageIds: List<String>,
        words: List<String> = emptyList(),
        vad: VoiceVad.Setup = VoiceVad.Setup.DEFAULT,
    ): Qwen3AsrStream {
        val rec = recognizerFor(filesDir, model)
        val cutter = VoiceVad.phraseCutter(
            filesDir,
            vad,
            EtStore.file(filesDir, model, "silero_vad.onnx"),
            endpointMs,
            PHRASE_PAUSE_SECONDS,
            MAX_PHRASE_SECONDS,
        )
        val language = Qwen3Languages.promptName(languageIds)?.toByteArray(Charsets.UTF_8)
        val hotwords = hotwords(words)?.toByteArray(Charsets.UTF_8)
        liveStreams++
        return Qwen3AsrStream(rec, cutter, language, hotwords) { liveStreams-- }
    }

    /**
     * [words] as the prompt takes them: comma-separated (a comma inside one
     * would split it, so it goes), at most [MAX_HOTWORDS]; null for none.
     */
    internal fun hotwords(words: List<String>): String? =
        words.asSequence()
            .map { it.replace(',', ' ').replace('\n', ' ').trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_HOTWORDS)
            .joinToString(",")
            .ifEmpty { null }

    /**
     * What the decoder may write for a phrase of [samples]: about ten tokens
     * a second of speech and a margin, so a phrase it starts repeating stops
     * near its own length rather than at the cap.
     */
    internal fun maxTokensFor(samples: Int): Int =
        (24 + samples / 1_600).coerceAtMost(MAX_NEW_TOKENS)

    /** The model folder loaded right now, or null; for the Resources screen (#1163). Any thread. */
    fun loadedModel(): File? = recognizerDir?.takeIf { isOpen }?.let(::File)

    /** Closes the model, unless a stream still uses it. On [SherpaNative.dispatcher] only. */
    fun release() {
        if (liveStreams > 0) return
        if (recognizer != 0L) SherpaNative.nativeQwen3Free(recognizer)
        recognizer = 0L
        recognizerDir = null
        isOpen = false
    }

    private fun recognizerFor(filesDir: File, model: EtModel): Long {
        require(model.engine == EtEngine.QWEN3)
        val dir = EtStore.dir(filesDir, model)
        if (recognizer != 0L && recognizerDir == dir.path) return recognizer
        check(SherpaNative.available) {
            if (SherpaNative.moduleReady) "ONNX Runtime is not in this build"
            else "ONNX Runtime is still downloading"
        }
        check(!bridgeMissing) { "${model.label} needs a newer sherpa-onnx library" }
        check(liveStreams == 0) { "a Qwen3-ASR phrase is still open" }
        release()
        // A gigabyte and more: nothing else of this runtime's stays beside it.
        SherpaEngine.release()
        OmnilingualEngine.release()
        fun part(prefix: String) = model.files.first { it.name.startsWith(prefix) && it.name.endsWith(".onnx") }
            .let { EtStore.file(filesDir, model, it) }.path
        val handle = try {
            SherpaNative.nativeQwen3(
                part("conv_frontend"),
                part("encoder"),
                part("decoder"),
                // vocab.json, merges.txt and tokenizer_config.json sit beside the graphs.
                dir.path,
                THREADS,
                MAX_TOTAL_LEN,
                MAX_NEW_TOKENS,
            )
        } catch (_: UnsatisfiedLinkError) {
            bridgeMissing = true
            error("${model.label} needs a newer sherpa-onnx library")
        }
        check(handle != 0L) { "${model.label} did not load" }
        recognizer = handle
        recognizerDir = dir.path
        isOpen = true
        return handle
    }
}

/**
 * One dictation through Qwen3-ASR: the [cutter]'s detector cuts the audio into
 * phrases as it is fed, and each finished phrase is decoded on the spot, so
 * [text] grows a phrase at a time. The recognizer belongs to [Qwen3AsrEngine];
 * the cutter is this stream's own, freed by [close], which [finish] calls.
 */
class Qwen3AsrStream internal constructor(
    private val recognizer: Long,
    private var cutter: PhraseEnd?,
    /** Qwen3's name for the language to write in, UTF-8; null lets the model tell. */
    private val language: ByteArray?,
    /** The words to listen for, comma-separated, UTF-8; null for none. */
    private val hotwords: ByteArray?,
    private val onClose: () -> Unit,
) : VoiceStream, AutoCloseable {

    private val heard = StringBuilder()

    override fun feed(chunk: FloatArray, count: Int) {
        val cut = cutter ?: return
        if (count <= 0) return
        val samples = if (count == chunk.size) chunk else chunk.copyOf(count)
        cut.feed(samples, count)
        decodePhrases(cut)
    }

    override fun text(): String = heard.toString()

    override fun finish(): String {
        val cut = cutter ?: return text()
        cut.detector.flush()
        decodePhrases(cut)
        close()
        return text()
    }

    /** The detector heard speech, then quiet for the pause set in "Stop after a pause". */
    override val endpoint: Boolean
        get() = cutter?.ended == true

    override fun close() {
        val cut = cutter ?: return
        cutter = null
        cut.close()
        onClose()
    }

    private fun decodePhrases(cut: PhraseEnd) {
        while (true) {
            val phrase = cut.detector.phrase() ?: return
            if (phrase.isEmpty()) continue
            val words = SherpaNative.nativeQwen3Decode(
                recognizer, phrase, phrase.size, language, hotwords, Qwen3AsrEngine.maxTokensFor(phrase.size),
            )
            append(String(words, Charsets.UTF_8).trim())
        }
    }

    private fun append(words: String) {
        if (words.isEmpty()) return
        if (heard.isNotEmpty() && !unspaced(heard.last()) && !unspaced(words.first())) heard.append(' ')
        heard.append(words)
    }

    private companion object {
        /**
         * Scripts written without spaces between words, so none goes between
         * two phrases either; nor after the full-width marks the model ends a
         * Chinese or Japanese phrase with (。，？).
         */
        fun unspaced(c: Char): Boolean = c in '\u3000'..'\u303f' || c in '\uff00'..'\uffef' ||
            when (Character.UnicodeScript.of(c.code)) {
                Character.UnicodeScript.HAN,
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA,
                Character.UnicodeScript.THAI,
                -> true
                else -> false
            }
    }
}
