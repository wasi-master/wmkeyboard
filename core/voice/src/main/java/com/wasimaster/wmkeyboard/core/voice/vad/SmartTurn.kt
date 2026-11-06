package com.wasimaster.wmkeyboard.core.voice.vad

import com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaNative
import java.io.File

/**
 * Smart Turn v3.2 (pipecat-ai, BSD-2-Clause): a Whisper-tiny encoder that
 * hears, from the last 8 seconds of someone talking, whether they have
 * finished what they were saying or only paused in the middle of it. It goes
 * by the voice (falling pitch, a word left hanging, an "um") rather than the
 * words, in 23 languages.
 *
 * Dictation asks it once a pause begins ([PhraseEnd]): a speaker who sounds
 * finished is let go after that short pause, one who does not is given the
 * whole pause set in "Stop after a pause". On the bridge (`smart_turn_jni.cpp`,
 * in `libwmsherpa.so`), so it rides the `onnx` gate; the model, about 8.7 MB,
 * downloads from the settings screen ([VadModels.SMART_TURN]). Its authors
 * time a run at 12 ms on a desktop CPU; a phone takes several times that, once
 * per pause.
 *
 * One model stays open, shared by every dictation; a run holds [lock], so two
 * engines' threads never run it at once.
 */
object SmartTurn {

    /** What the model reads: the last 8 s at 16 kHz. */
    const val WINDOW_SAMPLES = 8 * 16_000

    /** At or above this the speaker is taken to be finished; the model's own cut. */
    private const val FINISHED = 0.5f

    private const val THREADS = 2

    private val lock = Any()
    private var handle = 0L
    private var handlePath: String? = null

    /** A `libwmsherpa.so` from before Smart Turn: it never answers. */
    @Volatile private var bridgeMissing = false

    /** Whether a dictation started now would use it: the model on disk and ONNX Runtime here. */
    fun ready(filesDir: File): Boolean =
        !bridgeMissing && VadModels.SMART_TURN.isDownloaded(filesDir) && SherpaNative.shipped && SherpaNative.moduleReady

    /**
     * Whether the speaker of [audio] (the latest last; only its last
     * [WINDOW_SAMPLES] count) sounds finished. False when the model will not
     * answer, so the whole pause is waited out as without it.
     */
    fun finished(filesDir: File, audio: FloatArray): Boolean {
        if (audio.isEmpty()) return false
        synchronized(lock) {
            val h = open(VadModels.SMART_TURN.file(filesDir, VadModels.SMART_TURN_FILE).path)
            return h != 0L && SherpaNative.nativeSmartTurnRun(h, audio, audio.size) >= FINISHED
        }
    }

    /** Frees the model; the next pause opens it again. */
    /** The model file loaded right now, or null; for the Resources screen (#1163). Any thread. */
    fun loadedModel(): File? = handlePath?.takeIf { handle != 0L }?.let(::File)

    fun release() = synchronized(lock) {
        if (handle != 0L) SherpaNative.nativeSmartTurnFree(handle)
        handle = 0L
        handlePath = null
    }

    private fun open(path: String): Long {
        if (handle != 0L && handlePath == path) return handle
        if (bridgeMissing || !SherpaNative.available) return 0L
        if (handle != 0L) SherpaNative.nativeSmartTurnFree(handle)
        handle = try {
            SherpaNative.nativeSmartTurn(path, THREADS)
        } catch (_: UnsatisfiedLinkError) {
            bridgeMissing = true
            0L
        }
        handlePath = path.takeIf { handle != 0L }
        return handle
    }
}
