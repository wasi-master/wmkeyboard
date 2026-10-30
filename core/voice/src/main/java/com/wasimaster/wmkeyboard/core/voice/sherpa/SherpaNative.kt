package com.wasimaster.wmkeyboard.core.voice.sherpa

import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow

/**
 * sherpa-onnx, through `native/sherpa-onnx-jni`: k2-fsa's speech toolkit over
 * Microsoft's ONNX Runtime, plus the VITS runner that reads text aloud with
 * Meta's MMS voices ([com.wasimaster.wmkeyboard.core.voice.speech.MmsSpeech])
 * and the token classifier that punctuates dictation. Two libraries, both behind the `onnx` gate
 * (`:feature:onnx` on Play): `libonnxruntime.so`, Microsoft's own build from
 * Maven Central, and `libwmsherpa.so`, this project's bridge, which names the
 * runtime as a dependency and so must come second. The runtime is about
 * 33 MB on arm64; nothing fetches it until someone picks the Sherpa engine.
 *
 * Handles are plain longs, freed by their owners. Nothing here is
 * thread-safe, so every call runs on [dispatcher], one thread.
 */
object SherpaNative {

    private val library = GatedLibrary({ FeatureModules.onnx }) {
        runCatching {
            FeatureModules.loadLibrary("onnxruntime")
            FeatureModules.loadLibrary("wmsherpa")
        }.isSuccess
    }

    /** Whether both libraries are loaded on this device and build. */
    val available: Boolean get() = library.available

    /** Whether this build has them, counting a module not fetched yet. */
    val shipped: Boolean get() = library.shipped

    /** Where the `onnx` module stands, for the settings screen's banner. */
    val moduleState: StateFlow<ModuleState> get() = FeatureModules.onnx.state

    /** Whether the module is here; true wherever the APK carries the libraries. */
    val moduleReady: Boolean get() = FeatureModules.onnx.installed

    /** Asks for the module. Idempotent. */
    fun requestModule() = FeatureModules.onnx.requestInstall()

    /** The one thread every handle is used on. */
    val dispatcher: CoroutineDispatcher by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "sherpa").apply { isDaemon = true } }
            .asCoroutineDispatcher()
    }

    // Streaming recognizer: a transducer. Paths are the model's files; 0 when
    // they will not load. [trailingSilence] is the pause, in seconds, after
    // which the recognizer's own endpoint fires.
    @JvmStatic external fun nativeRecognizer(
        encoder: String,
        decoder: String,
        joiner: String,
        tokens: String,
        threads: Int,
        trailingSilence: Float,
    ): Long

    /**
     * The same transducer by modified beam search, the only search that
     * follows a stream's hotwords ([nativeHotwordStream]). [modelingUnit] is
     * "bpe" or "cjkchar+bpe", nothing else (sherpa-onnx exits on any other);
     * hotwords are cut into pieces over [bpeVocab] ([SherpaHotwords.vocab]),
     * each matching piece worth [hotwordsScore]. Not in a bridge built before
     * hotwords: that one throws [UnsatisfiedLinkError].
     */
    @JvmStatic external fun nativeHotwordRecognizer(
        encoder: String,
        decoder: String,
        joiner: String,
        tokens: String,
        threads: Int,
        trailingSilence: Float,
        modelingUnit: String,
        bpeVocab: String,
        hotwordsScore: Float,
    ): Long

    /**
     * A streaming Paraformer (#1005): no joiner, greedy only, so no hotwords. Not
     * in a bridge built before it: that one throws [UnsatisfiedLinkError].
     */
    @JvmStatic external fun nativeParaformerRecognizer(
        encoder: String,
        decoder: String,
        tokens: String,
        threads: Int,
        trailingSilence: Float,
    ): Long

    @JvmStatic external fun nativeRecognizerFree(recognizer: Long)

    @JvmStatic external fun nativeStream(recognizer: Long): Long

    /** A stream listening for [hotwords]: UTF-8, one phrase a line ([SherpaHotwords.lines]). */
    @JvmStatic external fun nativeHotwordStream(recognizer: Long, hotwords: ByteArray): Long

    @JvmStatic external fun nativeStreamFree(stream: Long)

    @JvmStatic external fun nativeFeed(recognizer: Long, stream: Long, samples: FloatArray, count: Int)

    @JvmStatic external fun nativeFinish(recognizer: Long, stream: Long)

    /** The transcript so far, UTF-8. */
    @JvmStatic external fun nativeText(recognizer: Long, stream: Long): ByteArray

    @JvmStatic external fun nativeEndpoint(recognizer: Long, stream: Long): Boolean

    // Silero VAD. Feeding returns whether speech is going on now.
    @JvmStatic external fun nativeVad(model: String, threshold: Float, minSilence: Float, minSpeech: Float): Long

    @JvmStatic external fun nativeVadFree(vad: Long)

    @JvmStatic external fun nativeVadFeed(vad: Long, samples: FloatArray, count: Int): Boolean

    @JvmStatic external fun nativeVadReset(vad: Long)

    // Silero as a segmenter: it keeps each phrase between pauses of at least
    // [minSilence] seconds, and past [maxSpeech] seconds of talk it settles
    // for a much shorter pause. Fed with [nativeVadFeed]; the phrases come out
    // oldest first, null when there is none.
    @JvmStatic external fun nativeSegmenter(
        model: String,
        threshold: Float,
        minSilence: Float,
        minSpeech: Float,
        maxSpeech: Float,
    ): Long

    @JvmStatic external fun nativeVadSegment(vad: Long): FloatArray?

    /** The clip is over: speech still going on becomes a last phrase. */
    @JvmStatic external fun nativeVadFlush(vad: Long)

    // A detector handle, Silero, TEN or FireRedVAD, is a live dictation's own
    // and belongs to the one thread feeding it, which for Asr4All is not
    // [dispatcher]; it shares nothing with the recognizer.

    /** Model families [nativeVadOf] runs and [nativeVadProbe] names. */
    const val VAD_SILERO = 0
    const val VAD_TEN = 1

    /**
     * [nativeVad] ([keep] false) or [nativeSegmenter] ([keep] true) for a
     * model of [kind], [VAD_SILERO] or [VAD_TEN]. sherpa-onnx exits the
     * process on a model it does not recognise, so [model] is one this app
     * pinned or one [nativeVadProbe] passed. Not in a bridge built before it:
     * that one throws [UnsatisfiedLinkError].
     */
    @JvmStatic external fun nativeVadOf(
        kind: Int,
        model: String,
        threshold: Float,
        minSilence: Float,
        minSpeech: Float,
        maxSpeech: Float,
        keep: Boolean,
    ): Long

    /**
     * Which family the ONNX file at [model] is: [VAD_SILERO], [VAD_TEN], or -1
     * for neither. Opens it with plain ONNX Runtime and makes the checks
     * sherpa-onnx would exit on, so it is safe on any file.
     */
    @JvmStatic external fun nativeVadProbe(model: String): Int

    // FireRedVAD's streaming DFSMN (vad_jni.cpp): Kaldi filterbanks with the
    // CMVN statistics of its cmvn.ark ([mean], and [scale] the inverse
    // standard deviation, 80 each), one speech probability per 10 ms frame.
    @JvmStatic external fun nativeFireRed(model: String, mean: FloatArray, scale: FloatArray, threads: Int): Long

    @JvmStatic external fun nativeFireRedFree(vad: Long)

    /** [count] samples in; the probability of each 10 ms frame they complete, oldest first. */
    @JvmStatic external fun nativeFireRedFeed(vad: Long, samples: FloatArray, count: Int): FloatArray

    @JvmStatic external fun nativeFireRedReset(vad: Long)

    // Smart Turn v3 (smart_turn_jni.cpp): whether a speaker has finished.
    @JvmStatic external fun nativeSmartTurn(model: String, threads: Int): Long

    @JvmStatic external fun nativeSmartTurnFree(turn: Long)

    /** The chance, 0 to 1, that the speaker of the last 8 s of the first [count] [samples] has finished; below 0 on failure. */
    @JvmStatic external fun nativeSmartTurnRun(turn: Long, samples: FloatArray, count: Int): Float

    // Meta's Omnilingual ASR CTC model on ONNX Runtime, a whole phrase at a time.
    @JvmStatic external fun nativeOmnilingual(model: String, tokens: String, threads: Int): Long

    @JvmStatic external fun nativeOmnilingualFree(model: Long)

    /**
     * The first [count] samples of a phrase (16 kHz mono, in [-1, 1]) as text,
     * UTF-8. [allowed] flags the token ids that may come out (see
     * [OmnilingualScriptMask]); null lets the model write any of them.
     */
    @JvmStatic external fun nativeOmnilingualDecode(
        model: Long,
        samples: FloatArray,
        count: Int,
        allowed: BooleanArray?,
    ): ByteArray

    // Online punctuation: CNN-BiLSTM over BPE pieces, punctuation and capitals.
    @JvmStatic external fun nativePunctuation(model: String, vocab: String): Long

    @JvmStatic external fun nativePunctuationFree(punctuation: Long)

    /** [text] (UTF-8) punctuated and cased, UTF-8. */
    @JvmStatic external fun nativePunctuate(punctuation: Long, text: ByteArray): ByteArray

    // A token classifier on ONNX Runtime: `input_ids` (and an all-ones
    // `attention_mask` when the model takes one) in, a label per token out.
    // Dictation's multilingual punctuation model
    // ([com.wasimaster.wmkeyboard.core.voice.punctuation.DictationPunctuation]).
    @JvmStatic external fun nativeTagger(model: String, threads: Int): Long

    @JvmStatic external fun nativeTaggerFree(tagger: Long)

    /** The label (argmax) of each of the first [count] of [ids], specials included; null when the run fails. */
    @JvmStatic external fun nativeTag(tagger: Long, ids: IntArray, count: Int): IntArray?

    // Speech out: one VITS voice (Meta's MMS-TTS). The text is turned into
    // token ids on the Kotlin side; this runs the network.
    @JvmStatic external fun nativeVits(model: String, threads: Int): Long

    @JvmStatic external fun nativeVitsFree(vits: Long)

    /** The model's metadata entry [key] (`sample_rate`, `add_blank`), UTF-8; empty when absent. */
    @JvmStatic external fun nativeVitsMeta(vits: Long, key: String): ByteArray

    /**
     * One sentence's samples in [-1, 1] at the model's rate, or null when the
     * run fails. The scales are VITS's own: sampling noise, duration (1 / speed)
     * and duration noise.
     */
    @JvmStatic external fun nativeVitsRun(
        vits: Long,
        ids: LongArray,
        noiseScale: Float,
        lengthScale: Float,
        noiseScaleW: Float,
    ): FloatArray?

    // Speech enhancement: a streaming DPDFNet denoiser (the Strong level of
    // Reduce background noise, [com.wasimaster.wmkeyboard.core.voice.StrongDenoise]).
    // A handle belongs to the one capture thread using it, not [dispatcher];
    // it shares nothing with the recognizer. Output trails input by [delay]
    // samples, which the bridge drops at the start and brings out at
    // [nativeDenoiseFinish]; [keep] of the input is mixed back under it.
    @JvmStatic external fun nativeDenoiser(model: String, threads: Int, delay: Int, keep: Float): Long

    @JvmStatic external fun nativeDenoiserFree(denoiser: Long)

    /** [count] samples of [samples] (16 kHz, [-1, 1]) in; what is ready out, denoised. */
    @JvmStatic external fun nativeDenoise(denoiser: Long, samples: FloatArray, count: Int): FloatArray

    /** The clip's last samples, and the denoiser reset for the next clip. */
    @JvmStatic external fun nativeDenoiseFinish(denoiser: Long): FloatArray
}
