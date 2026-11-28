package com.wasimaster.wmkeyboard.core.voice.sherpa

import com.wasimaster.wmkeyboard.core.voice.VoiceStream
import com.wasimaster.wmkeyboard.core.voice.executorch.EtEngine
import com.wasimaster.wmkeyboard.core.voice.executorch.EtModel
import com.wasimaster.wmkeyboard.core.voice.executorch.EtStore
import com.wasimaster.wmkeyboard.core.voice.executorch.SherpaKind
import com.wasimaster.wmkeyboard.core.voice.vad.PhraseEnd
import com.wasimaster.wmkeyboard.core.voice.vad.VoiceVad
import java.io.File
import java.util.Locale

/**
 * Offline Sherpa dictation: a streaming transducer per language (a Zipformer,
 * or NVIDIA's Nemotron, which covers 28 at once and is told the language per
 * stream), decoded while the user speaks, so the words reach the field as they
 * are said. The phrase end detector ([VoiceVad]; Silero, carried beside every
 * model, unless the user picked another) hears the end of a phrase sooner and
 * more surely than a loudness threshold. Marks and capitals come
 * after, for the models that write none, from
 * [com.wasimaster.wmkeyboard.core.voice.punctuation.DictationPunctuation].
 *
 * A model of one of sherpa-onnx's offline families ([SherpaKind]: Dolphin,
 * Moonshine's other languages, SenseVoice, CTC models) is no streaming one:
 * as with [Qwen3AsrEngine], the detector cuts the audio at each short pause
 * and the phrase before it is decoded whole ([SherpaPhraseStream]).
 *
 * Words to listen for (#305) become hotwords ([SherpaHotwords]), which only
 * modified beam search follows. It costs more than greedy search, so a
 * phrase with no words this model can spell is decoded greedily. The offline
 * families take none.
 *
 * One model stays open between dictations, since opening one takes about as
 * long as a short phrase does to say, until another takes its place (or the
 * same one is needed with the other search). Every call here runs on
 * [SherpaNative.dispatcher].
 */
object SherpaEngine {

    /** Decoder threads: two keeps up with speech on any phone that has two cores to spare. */
    private const val THREADS = 2

    /** The pause that ends a phrase when the user has not set one. */
    private const val DEFAULT_ENDPOINT_MS = 1_200

    /**
     * What each token of a hotword adds to a path that is spelling it:
     * sherpa-onnx's own default, kept after measuring seven models on
     * synthetic clips. Above 1.0 more weight found at most one more rare name
     * (English 9 to 11 of 75 from 1 without hotwords, Russian 5 of 8 from 2,
     * Chinese 19 of 30 from 13); at 2.0 a 195-word list began to change
     * ordinary English sentences (8.3% of words wrong became 8.9%), and at
     * 3.0 it tacked words on (10.8%). Bengali found nothing at any weight.
     */
    const val HOTWORDS_SCORE = 1.5f

    /** The vocabulary [SherpaHotwords.vocab] writes, kept in the model's folder. */
    private const val VOCAB_FILE = "hotwords.vocab"

    /** Threads for a phrase-at-a-time model: one phrase at a time, so four keep the wait after it short. */
    private const val OFFLINE_THREADS = 4

    /** The pause that closes a phrase for an offline model, as Omnilingual's. */
    private const val PHRASE_PAUSE_SECONDS = 0.5f

    /**
     * Past this much talk without a pause the detector cuts a phrase for an
     * offline model. Moonshine and Dolphin read 30 seconds at most.
     */
    private const val MAX_PHRASE_SECONDS = 20f

    /** The offline model open now, if any, and its folder; a streaming one and an offline one are never open together. */
    private var offline = 0L
    private var offlineDir: String? = null

    /** A `libwmsherpa.so` from before per-stream options: Nemotron hears in `auto`. */
    private var optionMissing = false

    private var recognizer = 0L
    private var recognizerDir: String? = null
    private var recognizerBeam = false

    /** A `libwmsherpa.so` from before hotwords: every phrase is decoded greedily. */
    private var bridgeMissing = false

    /** A model folder whose beam recognizer would not open; it is decoded greedily. */
    private var beamFailedDir: String? = null

    /** How the open model's folder spells hotwords, read once from its tokens.txt. */
    private var spelling: Pair<String, SherpaHotwords>? = null

    /**
     * Streams not yet closed. Each one uses the recognizer handle, so
     * [release] leaves it alone until this is back to 0: a memory trim
     * between the microphone closing and the phrase finishing must not free
     * what the finish is about to read.
     */
    private var liveStreams = 0

    /** Whether a model is open, so a memory trim knows there is something to free. Any thread. */
    @Volatile var isOpen: Boolean = false
        private set

    /** Opens [model] ahead of a phrase. Throws when it will not load. */
    fun warm(filesDir: File, model: EtModel) {
        if (model.sherpaKind.offline) offlineFor(filesDir, model) else recognizerFor(filesDir, model)
    }

    /**
     * A fresh phrase on [model] in [languageIds] (keyboard language ids), which
     * a model with [EtModel.languageTags] is told. [endpointMs] is the pause
     * that ends it, or 0 for none (the user stops it), heard by the detector
     * [vad] names. [words] are the words to listen for, most important first;
     * the ones the model cannot spell are dropped. Throws when the model will
     * not load.
     */
    fun newStream(
        filesDir: File,
        model: EtModel,
        endpointMs: Int,
        words: List<String> = emptyList(),
        vad: VoiceVad.Setup = VoiceVad.Setup.DEFAULT,
        languageIds: List<String> = emptyList(),
    ): VoiceStream {
        if (model.sherpaKind.offline) return phraseStream(filesDir, model, endpointMs, vad, languageIds)
        val hotwords = if (words.isEmpty() || bridgeMissing || !model.hasJoiner) {
            emptyList()
        } else {
            spellingFor(filesDir, model).lines(words)
        }
        val rec = recognizerFor(filesDir, model, beam = hotwords.isNotEmpty())
        val stream = if (recognizerBeam) {
            SherpaNative.nativeHotwordStream(rec, hotwords.joinToString("\n").toByteArray(Charsets.UTF_8))
        } else {
            SherpaNative.nativeStream(rec)
        }
        check(stream != 0L) { "sherpa-onnx could not start a stream" }
        SherpaLanguages.tagFor(model, languageIds)?.let { tag -> setLanguage(stream, tag) }
        val end = try {
            VoiceVad.phraseEnd(filesDir, vad, EtStore.file(filesDir, model, "silero_vad.onnx"), endpointMs)
        } catch (e: Throwable) {
            SherpaNative.nativeStreamFree(stream)
            throw e
        }
        liveStreams++
        return SherpaStream(rec, stream, end) { liveStreams-- }
    }

    /** Tells a Nemotron stream its language; an older bridge leaves it on `auto`. */
    private fun setLanguage(stream: Long, tag: String) {
        if (optionMissing) return
        try {
            SherpaNative.nativeStreamOption(stream, "language".toByteArray(Charsets.UTF_8), tag.toByteArray(Charsets.UTF_8))
        } catch (_: UnsatisfiedLinkError) {
            optionMissing = true
        }
    }

    /** A dictation on an offline [model]: phrases cut at each pause and decoded whole. */
    private fun phraseStream(
        filesDir: File,
        model: EtModel,
        endpointMs: Int,
        vad: VoiceVad.Setup,
        languageIds: List<String>,
    ): SherpaPhraseStream {
        val rec = offlineFor(filesDir, model)
        val cutter = VoiceVad.phraseCutter(
            filesDir,
            vad,
            EtStore.file(filesDir, model, "silero_vad.onnx"),
            endpointMs,
            PHRASE_PAUSE_SECONDS,
            MAX_PHRASE_SECONDS,
        )
        val language = SherpaLanguages.tagFor(model, languageIds)?.toByteArray(Charsets.UTF_8)
        liveStreams++
        return SherpaPhraseStream(rec, cutter, language) { liveStreams-- }
    }

    /** The model folder loaded right now, or null; for the Resources screen (#1163). Any thread. */
    fun loadedModel(): File? = (recognizerDir ?: offlineDir)?.takeIf { isOpen }?.let(::File)

    /** Closes whatever is open, unless a stream still uses it. On [SherpaNative.dispatcher] only. */
    fun release() {
        if (liveStreams > 0) return
        if (recognizer != 0L) SherpaNative.nativeRecognizerFree(recognizer)
        recognizer = 0L
        recognizerDir = null
        spelling = null
        freeOffline()
        isOpen = false
    }

    private fun freeOffline() {
        if (offline != 0L) SherpaNative.nativeOfflineFree(offline)
        offline = 0L
        offlineDir = null
    }

    /** [model]'s offline recognizer, opened in place of whatever this runtime holds. */
    private fun offlineFor(filesDir: File, model: EtModel): Long {
        require(model.engine == EtEngine.SHERPA && model.sherpaKind.offline)
        val dir = EtStore.dir(filesDir, model)
        if (offline != 0L && offlineDir == dir.path) return offline
        check(SherpaNative.available) {
            if (SherpaNative.moduleReady) "ONNX Runtime is not in this build"
            else "ONNX Runtime is still downloading"
        }
        check(liveStreams == 0) { "a Sherpa phrase is still open" }
        // Nothing else of this runtime's stays beside it: a phrase model is
        // hundreds of megabytes, and the streaming one gives way.
        release()
        OmnilingualEngine.release()
        Qwen3AsrEngine.release()
        fun named(vararg prefixes: String): String? = model.files
            .firstOrNull { f -> f.name != "silero_vad.onnx" && prefixes.any(f.name::startsWith) }
            ?.let { EtStore.file(filesDir, model, it).path }
        val tokens = EtStore.file(filesDir, model, "tokens.txt").path
        val (a, b, c) = when (model.sherpaKind) {
            SherpaKind.MOONSHINE -> Triple(named("encoder"), named("decoder_model_merged"), null)
            SherpaKind.TRANSDUCER -> Triple(named("encoder"), named("decoder"), named("joiner"))
            else -> Triple(named("model"), null, null)
        }
        val handle = try {
            SherpaNative.nativeOffline(model.sherpaKind.native, checkNotNull(a) { "${model.label} has no model file" }, b, c, tokens, OFFLINE_THREADS)
        } catch (_: UnsatisfiedLinkError) {
            error("${model.label} needs a newer sherpa-onnx library")
        }
        check(handle != 0L) { "${model.label} did not load" }
        offline = handle
        offlineDir = dir.path
        isOpen = true
        return handle
    }

    private fun spellingFor(filesDir: File, model: EtModel): SherpaHotwords {
        val dir = EtStore.dir(filesDir, model).path
        spelling?.let { (forDir, known) -> if (forDir == dir) return known }
        val parsed = SherpaHotwords.parse(EtStore.file(filesDir, model, "tokens.txt").readText())
        spelling = dir to parsed
        return parsed
    }

    /**
     * The vocabulary sherpa-onnx cuts hotwords with, written whole before it
     * is named: its reader exits the process on a line it cannot parse.
     */
    private fun vocabFor(filesDir: File, model: EtModel, hotwords: SherpaHotwords): File {
        val file = File(EtStore.dir(filesDir, model), VOCAB_FILE)
        val text = hotwords.vocab()
        if (file.isFile && file.length() == text.toByteArray(Charsets.UTF_8).size.toLong()) return file
        val part = File(file.path + ".part")
        part.writeText(text, Charsets.UTF_8)
        check(part.renameTo(file)) { "could not write ${file.name}" }
        return file
    }

    /**
     * [model]'s recognizer, by beam search when [beam] asks and it can be
     * had, greedy otherwise; [recognizerBeam] says which it is.
     */
    private fun recognizerFor(filesDir: File, model: EtModel, beam: Boolean = false): Long {
        require(model.engine == EtEngine.SHERPA && !model.punctuation)
        val dir = EtStore.dir(filesDir, model)
        val wanted = beam && model.hasJoiner && !bridgeMissing && beamFailedDir != dir.path
        if (recognizer != 0L && recognizerDir == dir.path && recognizerBeam == wanted) return recognizer
        check(SherpaNative.available) {
            if (SherpaNative.moduleReady) "ONNX Runtime is not in this build"
            else "ONNX Runtime is still downloading"
        }
        // Another model's phrase still open would be left pointing at a freed
        // recognizer; the service closes one before it starts the next.
        check(liveStreams == 0) { "a Sherpa phrase is still open" }
        if (recognizer != 0L) SherpaNative.nativeRecognizerFree(recognizer)
        recognizer = 0L
        recognizerDir = null
        // An offline model's, Omnilingual's and Qwen3-ASR's memory goes back first.
        freeOffline()
        OmnilingualEngine.release()
        Qwen3AsrEngine.release()
        fun part(prefix: String) = model.files.first { it.name.startsWith(prefix) }.let { EtStore.file(filesDir, model, it) }.path
        val beamed = if (wanted) {
            try {
                val hotwords = spellingFor(filesDir, model)
                SherpaNative.nativeHotwordRecognizer(
                    part("encoder"),
                    part("decoder"),
                    part("joiner"),
                    part("tokens"),
                    THREADS,
                    DEFAULT_ENDPOINT_MS / 1000f,
                    hotwords.unit,
                    vocabFor(filesDir, model, hotwords).path,
                    HOTWORDS_SCORE,
                )
            } catch (_: UnsatisfiedLinkError) {
                bridgeMissing = true
                0L
            } catch (_: Exception) {
                0L
            }
        } else {
            0L
        }
        // A beam recognizer that will not open leaves a greedy one: the
        // words go unheard, the dictation still works.
        if (wanted && beamed == 0L) beamFailedDir = dir.path
        val handle = if (beamed != 0L) {
            beamed
        } else if (!model.hasJoiner) {
            try {
                SherpaNative.nativeParaformerRecognizer(
                    part("encoder"),
                    part("decoder"),
                    part("tokens"),
                    THREADS,
                    DEFAULT_ENDPOINT_MS / 1000f,
                )
            } catch (_: UnsatisfiedLinkError) {
                error("${model.label} needs a newer sherpa-onnx library")
            }
        } else {
            SherpaNative.nativeRecognizer(
                part("encoder"),
                part("decoder"),
                part("joiner"),
                part("tokens"),
                THREADS,
                DEFAULT_ENDPOINT_MS / 1000f,
            )
        }
        check(handle != 0L) { "${model.label} did not load" }
        recognizer = handle
        recognizerDir = dir.path
        recognizerBeam = beamed != 0L
        isOpen = true
        return handle
    }
}

/**
 * One phrase through a Sherpa recognizer. Its recognizer belongs to
 * [SherpaEngine] and outlives it; its stream and phrase end are its own,
 * freed by [close], which [finish] calls.
 */
class SherpaStream internal constructor(
    private val recognizer: Long,
    private var stream: Long,
    /** Hears the phrase end; null when the user ends it. */
    private var end: PhraseEnd?,
    private val onClose: () -> Unit,
) : VoiceStream, AutoCloseable {

    override fun feed(chunk: FloatArray, count: Int) {
        if (stream == 0L || count <= 0) return
        // JNI copies the array whole; a slice keeps a short count from
        // dragging the rest of a large one across.
        val samples = if (count == chunk.size) chunk else chunk.copyOf(count)
        SherpaNative.nativeFeed(recognizer, stream, samples, count)
        end?.feed(samples, count)
    }

    override fun text(): String = if (stream == 0L) "" else readable(SherpaNative.nativeText(recognizer, stream))

    override fun finish(): String {
        if (stream == 0L) return ""
        SherpaNative.nativeFinish(recognizer, stream)
        val raw = readable(SherpaNative.nativeText(recognizer, stream))
        close()
        return raw
    }

    /** The detector heard speech and then the pause. */
    override val endpoint: Boolean
        get() = stream != 0L && end?.ended == true

    override fun close() {
        if (stream == 0L) return
        SherpaNative.nativeStreamFree(stream)
        end?.close()
        stream = 0L
        end = null
        onClose()
    }

    private companion object {
        /**
         * The models trained on audiobooks spell in capitals ("HELLO
         * WORLD"); the keyboard's casing, or the punctuation pass, decides
         * the case instead. Text with any small letter is left alone.
         */
        fun readable(bytes: ByteArray): String {
            val text = String(bytes, Charsets.UTF_8).trim()
            val shouting = text.any { it.isUpperCase() } && text.none { it.isLowerCase() }
            return if (shouting) text.lowercase(Locale.ROOT) else text
        }
    }
}

/**
 * One dictation through an offline Sherpa model: the [cutter]'s detector cuts
 * the audio into phrases as it is fed, and each finished phrase is decoded on
 * the spot, so [text] grows a phrase at a time. The recognizer belongs to
 * [SherpaEngine]; the cutter is this stream's own, freed by [close], which
 * [finish] calls.
 */
class SherpaPhraseStream internal constructor(
    private val recognizer: Long,
    private var cutter: PhraseEnd?,
    /** The tag the model is told the language by, UTF-8; null lets it tell. */
    private val language: ByteArray?,
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
            val words = SherpaNative.nativeOfflineDecode(recognizer, phrase, phrase.size, language)
            append(clean(String(words, Charsets.UTF_8)))
        }
    }

    private fun append(words: String) {
        if (words.isEmpty()) return
        if (heard.isNotEmpty() && !unspaced(heard.last()) && !unspaced(words.first())) heard.append(' ')
        heard.append(words)
    }

    internal companion object {
        /**
         * A phrase as the model wrote it, less what is not text: the tags
         * SenseVoice and Dolphin can leave in (`<|zh|>`, `<0.00>`), and the
         * spaces around it.
         */
        fun clean(raw: String): String =
            raw.replace(TAG, "").replace(SPACES, " ").trim()

        private val TAG = Regex("<\\|[^|>]*\\|>|<[0-9.]+>")
        private val SPACES = Regex("\\s{2,}")

        /**
         * Scripts written without spaces between words, so none goes between
         * two phrases either; nor after the full-width marks a Chinese or
         * Japanese phrase ends with (。，？).
         */
        fun unspaced(c: Char): Boolean = c in '　'..'〿' || c in '＀'..'￯' ||
            when (Character.UnicodeScript.of(c.code)) {
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
