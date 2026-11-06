package com.wasimaster.wmkeyboard.core.voice.sherpa

import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.voice.VoiceStream
import com.wasimaster.wmkeyboard.core.voice.executorch.EtEngine
import com.wasimaster.wmkeyboard.core.voice.executorch.EtModel
import com.wasimaster.wmkeyboard.core.voice.executorch.EtStore
import com.wasimaster.wmkeyboard.core.voice.vad.PhraseEnd
import com.wasimaster.wmkeyboard.core.voice.vad.VoiceVad
import java.io.File

/**
 * Offline Omnilingual dictation: Meta's Omnilingual ASR, the 300M CTC model,
 * on ONNX Runtime (omnilingual_jni.cpp in libwmsherpa). One model for every
 * language it knows, with no language to pick; it is not a streaming model,
 * so the phrase end detector ([VoiceVad]; Silero unless the user picked
 * another) cuts the microphone's audio at each short pause and the phrase
 * before the pause is decoded whole while the user goes on talking.
 * The words reach the field a phrase at a time rather than a word at a time.
 *
 * Left to itself the model spells in whichever script it thinks it hears, so
 * each phrase is decoded with its output held to the scripts of the languages
 * being dictated ([OmnilingualScriptMask]).
 *
 * The model takes about 1 GB of memory once open, so it stays open between
 * dictations only until a memory trim, and opening it frees Sherpa's
 * streaming model first. Every call here runs on [SherpaNative.dispatcher].
 */
object OmnilingualEngine {

    /** Decoder threads. One phrase at a time, so four keep the wait after it short. */
    private const val THREADS = 4

    /**
     * The pause that closes a phrase for decoding: short, so the words show
     * soon after each breath. "Stop after a pause" still decides when the
     * whole dictation ends.
     */
    private const val PHRASE_PAUSE_SECONDS = 0.5f

    /**
     * The model reads at most 40 seconds at once; past this much talk without
     * a pause the detector settles for a much shorter one (Silero, TEN) or
     * cuts there (FireRedVAD, Loudness), so no phrase gets there.
     */
    private const val MAX_PHRASE_SECONDS = 20f

    private var recognizer = 0L
    private var recognizerDir: String? = null

    /** The open model's tokens.txt, indexed by id, for [OmnilingualScriptMask]. */
    private var tokens: List<String> = emptyList()

    /** Masks already built for the open model, by the scripts they allow. */
    private val masks = HashMap<Set<Character.UnicodeScript>, BooleanArray?>()

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
     * A fresh dictation on [model] in [languageIds] (keyboard language ids),
     * whose scripts the text keeps to. [endpointMs] is the pause that ends it,
     * or 0 for none (the user stops it); the detector [vad] names cuts the
     * phrases and hears that pause. Throws when the model will not load.
     */
    fun newStream(
        filesDir: File,
        model: EtModel,
        endpointMs: Int,
        languageIds: List<String>,
        vad: VoiceVad.Setup = VoiceVad.Setup.DEFAULT,
    ): OmnilingualStream {
        val rec = recognizerFor(filesDir, model)
        val allowed = maskFor(languageIds)
        // The detector has already waited out the phrase pause when it says
        // the speech stopped; the rest of the user's pause is counted on top.
        val cutter = VoiceVad.phraseCutter(
            filesDir,
            vad,
            EtStore.file(filesDir, model, "silero_vad.onnx"),
            endpointMs,
            PHRASE_PAUSE_SECONDS,
            MAX_PHRASE_SECONDS,
        )
        liveStreams++
        return OmnilingualStream(rec, cutter, allowed) { liveStreams-- }
    }

    /**
     * The tokens a dictation in [languageIds] may write, or null for any: a
     * language this build does not know, or one written in a notation, says
     * nothing about the script to expect.
     */
    private fun maskFor(languageIds: List<String>): BooleanArray? {
        val scripts = languageIds.map { id ->
            LanguageRegistry.byId(id).takeIf { it !== LanguageRegistry.GENERIC }?.script ?: return null
        }
        val unicode = OmnilingualScriptMask.unicodeScripts(scripts) ?: return null
        return masks.getOrPut(unicode) { OmnilingualScriptMask.build(tokens, unicode) }
    }

    /** Closes the model, unless a stream still uses it. On [SherpaNative.dispatcher] only. */
    /** The model folder loaded right now, or null; for the Resources screen (#1163). Any thread. */
    fun loadedModel(): File? = recognizerDir?.takeIf { isOpen }?.let(::File)

    fun release() {
        if (liveStreams > 0) return
        if (recognizer != 0L) SherpaNative.nativeOmnilingualFree(recognizer)
        recognizer = 0L
        recognizerDir = null
        tokens = emptyList()
        masks.clear()
        isOpen = false
    }

    private fun recognizerFor(filesDir: File, model: EtModel): Long {
        require(model.engine == EtEngine.OMNILINGUAL)
        val dir = EtStore.dir(filesDir, model)
        if (recognizer != 0L && recognizerDir == dir.path) return recognizer
        check(SherpaNative.available) {
            if (SherpaNative.moduleReady) "ONNX Runtime is not in this build"
            else "ONNX Runtime is still downloading"
        }
        check(liveStreams == 0) { "an Omnilingual phrase is still open" }
        release()
        // A gigabyte beside Sherpa's own model would not fit next to the
        // keyboard on most phones.
        SherpaEngine.release()
        val tokensFile = EtStore.file(filesDir, model, "tokens.txt")
        val handle = SherpaNative.nativeOmnilingual(
            EtStore.file(filesDir, model, "model.int8.onnx").path,
            tokensFile.path,
            THREADS,
        )
        check(handle != 0L) { "${model.label} did not load" }
        tokens = OmnilingualScriptMask.readTokens(tokensFile)
        recognizer = handle
        recognizerDir = dir.path
        isOpen = true
        return handle
    }
}

/**
 * One dictation through the Omnilingual model: the [cutter]'s detector cuts
 * the audio into phrases as it is fed, and each finished phrase is decoded on
 * the spot, so [text] grows a phrase at a time. The recognizer belongs to
 * [OmnilingualEngine]; the cutter is this stream's own, freed by [close],
 * which [finish] calls.
 */
class OmnilingualStream internal constructor(
    private val recognizer: Long,
    private var cutter: PhraseEnd?,
    /** The token ids a phrase may be written with ([OmnilingualScriptMask]); null for any. */
    private val allowed: BooleanArray?,
    private val onClose: () -> Unit,
) : VoiceStream, AutoCloseable {

    private val heard = StringBuilder()

    override fun feed(chunk: FloatArray, count: Int) {
        val cut = cutter ?: return
        if (count <= 0) return
        // JNI copies the array whole; a slice keeps a short count from
        // dragging the rest of a large one across.
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
            val words = SherpaNative.nativeOmnilingualDecode(recognizer, phrase, phrase.size, allowed)
            append(String(words, Charsets.UTF_8).trim())
        }
    }

    private fun append(words: String) {
        if (words.isEmpty()) return
        if (heard.isNotEmpty() && !unspaced(heard.last()) && !unspaced(words.first())) heard.append(' ')
        heard.append(words)
    }

    private companion object {
        /** Scripts written without spaces between words, so none goes between two phrases either. */
        fun unspaced(c: Char): Boolean = when (Character.UnicodeScript.of(c.code)) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.THAI,
            Character.UnicodeScript.LAO,
            Character.UnicodeScript.KHMER,
            Character.UnicodeScript.MYANMAR,
            -> true
            else -> false
        }
    }
}
