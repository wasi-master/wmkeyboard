package com.wasimaster.wmkeyboard.core.voice.vosk

import com.wasimaster.wmkeyboard.core.voice.VoiceStream
import com.wasimaster.wmkeyboard.core.voice.executorch.EtEngine
import com.wasimaster.wmkeyboard.core.voice.executorch.EtModel
import com.wasimaster.wmkeyboard.core.voice.executorch.EtStore
import java.io.File

/**
 * Offline Vosk dictation (#1165): Alpha Cephei's small Kaldi models, one per
 * language, decoded while the user speaks so the words reach the field as
 * they are said. Each is about 40 MB and runs on any phone. The words come
 * in lower case with no marks;
 * [com.wasimaster.wmkeyboard.core.voice.punctuation.DictationPunctuation]
 * adds them where it can, as for Sherpa.
 *
 * The phrase end is not Vosk's own: the service wraps each stream in the
 * detector the user picked, the way it does Asr4All's, so "Stop after a
 * pause" means the same pause on every engine.
 *
 * One model stays open between dictations, since loading one takes a second
 * or two, until another takes its place. Every call here runs on
 * [VoskNative.dispatcher].
 */
object VoskEngine {

    /** Vosk's models are trained on 16 kHz audio, which is what the recorder delivers. */
    private const val SAMPLE_RATE = 16_000f

    private var model = 0L
    private var modelDir: String? = null

    /**
     * Streams not yet closed. Each one's recogniser points into the model, so
     * [release] leaves the model alone until this is back to 0.
     */
    private var liveStreams = 0

    /** Whether a model is open, so a memory trim knows there is something to free. Any thread. */
    @Volatile var isOpen: Boolean = false
        private set

    /** Opens [model] ahead of a phrase. Throws when it will not load. */
    fun warm(filesDir: File, model: EtModel) {
        modelFor(filesDir, model)
    }

    /** A fresh phrase on [model]. Throws when the model will not load. */
    fun newStream(filesDir: File, model: EtModel): VoskStream {
        val handle = modelFor(filesDir, model)
        val recognizer = VoskNative.nativeRecognizer(handle, SAMPLE_RATE)
        check(recognizer != 0L) { "Vosk could not start a recogniser" }
        liveStreams++
        return VoskStream(NativeDecoder(recognizer)) { liveStreams-- }
    }

    /** The model folder loaded right now, or null; for the Resources screen (#1163). Any thread. */
    fun loadedModel(): File? = modelDir?.takeIf { isOpen }?.let(::File)

    /** Closes the open model, unless a stream still uses it. On [VoskNative.dispatcher] only. */
    fun release() {
        if (liveStreams > 0) return
        if (model != 0L) VoskNative.nativeModelFree(model)
        model = 0L
        modelDir = null
        isOpen = false
    }

    private fun modelFor(filesDir: File, wanted: EtModel): Long {
        require(wanted.engine == EtEngine.VOSK)
        val dir = EtStore.dir(filesDir, wanted)
        if (model != 0L && modelDir == dir.path) return model
        check(VoskNative.available) {
            if (VoskNative.moduleReady) "Vosk is not in this build" else "Vosk is still downloading"
        }
        // Another model's phrase still open would be left pointing at a freed
        // model; the service closes one before it starts the next.
        check(liveStreams == 0) { "a Vosk phrase is still open" }
        if (model != 0L) VoskNative.nativeModelFree(model)
        model = 0L
        modelDir = null
        isOpen = false
        val handle = VoskNative.nativeModel(dir.path)
        check(handle != 0L) { "${wanted.label} did not load" }
        model = handle
        modelDir = dir.path
        isOpen = true
        return handle
    }

    private class NativeDecoder(private val recognizer: Long) : VoskDecoder {
        override fun accept(samples: FloatArray, count: Int): Int = VoskNative.nativeAccept(recognizer, samples, count)
        override fun result(): String = String(VoskNative.nativeResult(recognizer), Charsets.UTF_8)
        override fun partial(): String = String(VoskNative.nativePartial(recognizer), Charsets.UTF_8)
        override fun final(): String = String(VoskNative.nativeFinal(recognizer), Charsets.UTF_8)
        override fun free() = VoskNative.nativeRecognizerFree(recognizer)
    }
}

/** One Vosk recogniser, as [VoskStream] uses it: the native one, or a test's. */
internal interface VoskDecoder {
    /** 1 when an utterance just ended, 0 while one goes on, -1 on an error. */
    fun accept(samples: FloatArray, count: Int): Int

    /** The utterance that just ended, as JSON. */
    fun result(): String

    /** The utterance under way, as JSON. */
    fun partial(): String

    /** The last utterance, flushed, as JSON. */
    fun final(): String

    fun free()
}

/**
 * One phrase through a Vosk recogniser. Vosk splits what it hears into
 * utterances at its own short pauses and hands each one over as it ends;
 * this keeps them, so [text] and [finish] are the whole phrase. Its
 * recogniser is its own, freed by [close], which [finish] calls.
 */
class VoskStream internal constructor(
    private var decoder: VoskDecoder?,
    private val onClose: () -> Unit,
) : VoiceStream, AutoCloseable {

    private val done = ArrayList<String>()

    override fun feed(chunk: FloatArray, count: Int) {
        val d = decoder ?: return
        if (count <= 0) return
        if (d.accept(chunk, count) == 1) {
            VoskText.text(d.result()).takeIf { it.isNotEmpty() }?.let { done += it }
        }
    }

    override fun text(): String {
        val d = decoder ?: return VoskText.join(done, "")
        return VoskText.join(done, VoskText.partial(d.partial()))
    }

    override fun finish(): String {
        val d = decoder ?: return VoskText.join(done, "")
        VoskText.text(d.final()).takeIf { it.isNotEmpty() }?.let { done += it }
        close()
        return VoskText.join(done, "")
    }

    override fun close() {
        val d = decoder ?: return
        decoder = null
        d.free()
        onClose()
    }
}
