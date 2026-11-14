package com.wasimaster.wmkeyboard.core.voice.vad

import com.wasimaster.wmkeyboard.core.voice.VoiceStream
import com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaNative
import java.io.File

/**
 * How the engines that decode while the user speaks (Asr4All, Sherpa,
 * Omnilingual) hear a phrase end (#976): the "Phrase end detector" setting,
 * and the [SpeechDetector] and [PhraseEnd] it builds.
 *
 * - [SILERO]: Silero VAD v4 (MIT), a 2 MB network; what Sherpa and
 *   Omnilingual have always used. Their models carry a copy, and Asr4All
 *   gets one of its own from [VadModels.SILERO].
 * - [FIRERED]: FireRedVAD's streaming model (Apache-2.0), 2.3 MB, which beat
 *   Silero and TEN on its authors' 102-language benchmark (F1 97.6 against
 *   96.0 and 95.2), most of all on false alarms (2.7% against 9.4%).
 * - [LOUDNESS]: no model, only how loud each 10 ms is, the way the recorder's
 *   own "Stop after a pause" has always worked for Whisper and the server.
 * - [CUSTOM]: a Silero or TEN model the user imported ([VadModels.import]).
 * - [AUTO]: Silero where it can run, else Loudness; what Asr4All had (no
 *   Silero) and Sherpa had stay as they were.
 *
 * Each runs on ONNX Runtime but Loudness; one that cannot run here (no
 * runtime, its file missing) gives way to [AUTO]'s pick, so dictation always
 * has some way to stop by itself. Smart Turn ([SmartTurn]) can sit on top of
 * any of them.
 */
object VoiceVad {

    /** Stored values of the setting (`WhisperSettings.vad`). */
    const val AUTO = "auto"
    const val SILERO = "silero"
    const val FIRERED = "firered"
    const val LOUDNESS = "loudness"
    const val CUSTOM = "custom"
    val CHOICES = listOf(AUTO, SILERO, FIRERED, LOUDNESS, CUSTOM)

    /** The speech threshold's default: Silero's and FireRedVAD's own 0.5. */
    const val DEFAULT_THRESHOLD = 0.5f
    val THRESHOLD_RANGE = 0.1f..0.9f

    /**
     * The quiet after which a detector says speech has stopped, for an engine
     * that only needs to know when the user is done. Short, so Smart Turn is
     * asked soon after the last word (its authors pair it with 0.2 s); the
     * rest of the pause is counted by [PhraseEnd].
     */
    const val HANG_SECONDS = 0.3f

    /** Shorter than this is a click or a cough, not speech. */
    private const val MIN_SPEECH_SECONDS = 0.25f

    /** With no pause in it, a phrase is cut this long at most (cutting detectors only). */
    private const val NO_CUT_SECONDS = 300f

    /**
     * Loudness's RMS at the default threshold, the recorder's own
     * `WhisperRecorder.SPEECH_RMS`; the threshold scales it, so 0.25 hears a
     * voice half as loud and 0.75 wants one half again as loud.
     */
    private const val LOUDNESS_RMS_AT_DEFAULT = 0.015f

    private const val FIRERED_THREADS = 1

    private const val SAMPLE_RATE = 16_000

    /** The setting's three values, read once per dictation. */
    data class Setup(
        val choice: String = AUTO,
        val threshold: Float = DEFAULT_THRESHOLD,
        val smartTurn: Boolean = false,
        /** Smart Turn may only lengthen the wait (#1319); see [PhraseEnd]. */
        val conservative: Boolean = false,
        /** Quiet waited on top of the end, in ms (#1320); see [PhraseEnd]. */
        val graceMs: Int = 0,
    ) {
        companion object {
            val DEFAULT = Setup()
        }
    }

    /** A `libwmsherpa.so` from before [SherpaNative.nativeVadOf]: Silero only, through the old calls. */
    @Volatile private var bridgeOld = false

    /** A `libwmsherpa.so` from before FireRedVAD: [FIRERED] gives way to Silero. */
    @Volatile private var fireRedMissing = false

    /**
     * Which of [CHOICES] (never [AUTO]) a dictation started now would really
     * use for [choice], given [ownSilero], the Silero file the engine's model
     * carries (null for none). Loads nothing heavy; fine on the main thread.
     */
    fun resolve(filesDir: File, choice: String, ownSilero: File? = null): String {
        val onnx = SherpaNative.shipped && SherpaNative.moduleReady
        fun silero() = if (onnx && sileroFile(filesDir, ownSilero) != null) SILERO else LOUDNESS
        return when (choice) {
            SILERO -> silero()
            FIRERED -> if (onnx && !fireRedMissing && VadModels.FIRERED.isDownloaded(filesDir)) FIRERED else silero()
            CUSTOM -> {
                val kind = VadModels.customKind(filesDir)
                if (onnx && kind != null && (kind == SherpaNative.VAD_SILERO || !bridgeOld)) CUSTOM else silero()
            }
            LOUDNESS -> LOUDNESS
            else -> silero()
        }
    }

    /**
     * The phrase end of a live dictation that ends after [pauseMs] of quiet
     * ([PhraseEnd]), or null for 0 (the user stops it). On the engine's thread.
     */
    fun phraseEnd(filesDir: File, setup: Setup, ownSilero: File?, pauseMs: Int): PhraseEnd? {
        if (pauseMs <= 0) return null
        val hang = minOf(HANG_SECONDS, pauseMs / 1000f)
        val detector = detector(filesDir, setup, ownSilero, hang, cuts = false, maxPhraseSeconds = NO_CUT_SECONDS)
        return PhraseEnd(
            detector, quietAfter(pauseMs, hang), smartTurnDir(filesDir, setup),
            graceSamples = graceSamples(setup), conservative = setup.conservative,
        )
    }

    /**
     * A detector that cuts the audio into phrases at pauses of [hangSeconds]
     * and at [maxPhraseSeconds] of talk without one, with the dictation's end
     * after [pauseMs] (never, for 0): Omnilingual's. On the engine's thread.
     */
    fun phraseCutter(
        filesDir: File,
        setup: Setup,
        ownSilero: File?,
        pauseMs: Int,
        hangSeconds: Float,
        maxPhraseSeconds: Float,
    ): PhraseEnd {
        val detector = detector(filesDir, setup, ownSilero, hangSeconds, cuts = true, maxPhraseSeconds = maxPhraseSeconds)
        val quiet = if (pauseMs > 0) quietAfter(pauseMs, hangSeconds) else -1
        return PhraseEnd(
            detector, quiet, smartTurnDir(filesDir, setup).takeIf { pauseMs > 0 },
            graceSamples = graceSamples(setup), conservative = setup.conservative,
        )
    }

    /** [Setup.graceMs] in samples. */
    private fun graceSamples(setup: Setup): Int =
        (setup.graceMs.coerceIn(GRACE_RANGE_MS).toLong() * SAMPLE_RATE / 1000).toInt()

    /** The grace setting's range, in ms (#1320). */
    val GRACE_RANGE_MS = 0..3_000

    /** How many times the pause a speaker conservative Smart Turn hears as unfinished waits (#1319). */
    const val UNFINISHED_PAUSE_FACTOR = 2

    /** [inner] ending itself by [end]; for an engine with no ear of its own (Asr4All). */
    fun endpointed(inner: VoiceStream, end: PhraseEnd?): VoiceStream = if (end == null) inner else Endpointed(inner, end)

    /** Samples of quiet past the detector's [hang] that make up [pauseMs]. */
    private fun quietAfter(pauseMs: Int, hang: Float): Int =
        ((pauseMs / 1000f - hang).coerceAtLeast(0f) * SAMPLE_RATE).toInt()

    private fun smartTurnDir(filesDir: File, setup: Setup): File? =
        filesDir.takeIf { setup.smartTurn && SmartTurn.ready(filesDir) }

    private fun sileroFile(filesDir: File, own: File?): File? =
        own?.takeIf { it.isFile } ?: VadModels.SILERO.file(filesDir, VadModels.SILERO_FILE).takeIf {
            VadModels.SILERO.isDownloaded(filesDir)
        }

    private fun detector(
        filesDir: File,
        setup: Setup,
        ownSilero: File?,
        hangSeconds: Float,
        cuts: Boolean,
        maxPhraseSeconds: Float,
    ): SpeechDetector {
        val threshold = setup.threshold.coerceIn(THRESHOLD_RANGE)
        val opened = when (resolve(filesDir, setup.choice, ownSilero)) {
            SILERO -> sileroFile(filesDir, ownSilero)?.let {
                sherpa(SherpaNative.VAD_SILERO, it, threshold, hangSeconds, cuts, maxPhraseSeconds)
            }
            CUSTOM -> VadModels.customKind(filesDir)?.let {
                sherpa(it, VadModels.customFile(filesDir), threshold, hangSeconds, cuts, maxPhraseSeconds)
            }
            FIRERED -> fireRed(filesDir, threshold, hangSeconds, cuts, maxPhraseSeconds)
            else -> null
        }
        return opened ?: frames(LoudnessFrames(LOUDNESS_RMS_AT_DEFAULT * threshold / DEFAULT_THRESHOLD), 0.5f, hangSeconds, cuts, maxPhraseSeconds)
    }

    private fun sherpa(kind: Int, file: File, threshold: Float, hang: Float, cuts: Boolean, maxPhrase: Float): SpeechDetector? {
        if (!SherpaNative.available) return null
        val handle = try {
            if (bridgeOld) throw UnsatisfiedLinkError()
            SherpaNative.nativeVadOf(kind, file.path, threshold, hang, MIN_SPEECH_SECONDS, maxPhrase, cuts)
        } catch (_: UnsatisfiedLinkError) {
            bridgeOld = true
            when {
                kind != SherpaNative.VAD_SILERO -> 0L
                cuts -> SherpaNative.nativeSegmenter(file.path, threshold, hang, MIN_SPEECH_SECONDS, maxPhrase)
                else -> SherpaNative.nativeVad(file.path, threshold, hang, MIN_SPEECH_SECONDS)
            }
        }
        return if (handle == 0L) null else SherpaVadDetector(handle)
    }

    private fun fireRed(filesDir: File, threshold: Float, hang: Float, cuts: Boolean, maxPhrase: Float): SpeechDetector? {
        if (!SherpaNative.available) return null
        val (mean, scale) = runCatching {
            KaldiCmvn.parse(VadModels.FIRERED.file(filesDir, VadModels.FIRERED_CMVN).readBytes())
        }.getOrNull() ?: return null
        val handle = try {
            SherpaNative.nativeFireRed(VadModels.FIRERED.file(filesDir, VadModels.FIRERED_FILE).path, mean, scale, FIRERED_THREADS)
        } catch (_: UnsatisfiedLinkError) {
            fireRedMissing = true
            0L
        }
        if (handle == 0L) return null
        return frames(FireRedFrames(handle), threshold, hang, cuts, maxPhrase)
    }

    private fun frames(source: FrameSource, threshold: Float, hang: Float, cuts: Boolean, maxPhrase: Float): SpeechDetector =
        FrameDetector(
            source,
            threshold = threshold,
            minSpeech = framesOf(MIN_SPEECH_SECONDS),
            hang = framesOf(hang).coerceAtLeast(1),
            maxSpeech = framesOf(maxPhrase),
            cuts = cuts,
        )

    private fun framesOf(seconds: Float): Int = (seconds * 100).toInt()

    /** A stream whose end [end] hears; finishing or closing it frees [end] too. */
    private class Endpointed(private val inner: VoiceStream, private val end: PhraseEnd) : VoiceStream, AutoCloseable {

        override fun feed(chunk: FloatArray, count: Int) {
            inner.feed(chunk, count)
            end.feed(chunk, count)
        }

        override fun text(): String = inner.text()

        override fun finish(): String = try {
            inner.finish()
        } finally {
            close()
        }

        override val endpoint: Boolean get() = end.ended

        override fun close() {
            end.close()
            (inner as? AutoCloseable)?.close()
        }
    }
}
