package com.wasimaster.wmkeyboard.core.voice.sherpa

import com.wasimaster.wmkeyboard.core.voice.executorch.EtEngine
import com.wasimaster.wmkeyboard.core.voice.executorch.EtModel
import com.wasimaster.wmkeyboard.core.voice.executorch.EtNative
import com.wasimaster.wmkeyboard.core.voice.executorch.EtStore
import java.io.File

/**
 * Parakeet and Moonshine on a Snapdragon NPU: k2-fsa's QNN context binaries
 * ([EtModel.npu]) through sherpa-onnx's QNN recognizers ([SherpaQnnNative]).
 *
 * A set of binaries is compiled for one clip length ([EtModel.npuSeconds]):
 * sherpa-onnx pads a shorter clip to it with silence, so a short phrase costs
 * a whole bucket on the NPU, and cuts a longer one. So a clip goes to the
 * shortest bucket of the same model on the device that holds it whole, the
 * one already open when that one does, and a clip longer than every bucket is
 * cut into pieces at its quietest moments ([cuts]) and decoded piece by piece.
 *
 * One recognizer is open at a time; opening one closes ExecuTorch's programs
 * first, and [EtNative.programs] closes this one, so the NPU build and a CPU
 * build never sit in memory together. Every call runs on
 * [EtNative.dispatcher], the thread Parakeet and Moonshine already use.
 */
object QnnSpeech {

    private const val SAMPLE_RATE = 16_000

    /** How far back from a piece's limit [cuts] looks for a pause: 3 s. */
    private const val LOOKBACK_SAMPLES = 3 * SAMPLE_RATE

    /** The window [cuts] measures loudness over: 20 ms. */
    private const val WINDOW_SAMPLES = 320

    private var recognizer = 0L
    private var recognizerModel: EtModel? = null
    private var recognizerDir: String? = null

    /** Whether a recognizer is open, so a memory trim knows there is something to free. Any thread. */
    @Volatile var isOpen: Boolean = false
        private set

    /** The model folder loaded right now, or null; for the Resources screen. Any thread. */
    fun loadedModel(): File? = recognizerDir?.takeIf { isOpen }?.let(::File)

    /**
     * Whether an NPU build can run in this process now: the QNN library
     * loaded and the chip's QNN runtime unpacked. Asks for whichever module
     * is missing, so a later dictation finds it. Unpacks the runtime the
     * first time (up to ~50 MB), so never on the main thread.
     */
    fun usable(): Boolean {
        if (!SherpaQnnNative.available) {
            SherpaQnnNative.requestModules()
            return false
        }
        return EtNative.qnnRuntime() != null
    }

    /** Opens [model]'s shortest bucket ahead of a clip. Throws when it will not load. */
    fun warm(filesDir: File, model: EtModel) {
        open(filesDir, buckets(filesDir, model).first())
    }

    /**
     * The first [count] samples of [pcm] (16 kHz mono, in [-1, 1]) as text.
     * Throws when the binaries will not load.
     */
    fun transcribe(filesDir: File, model: EtModel, pcm: FloatArray, count: Int = pcm.size): String {
        val n = minOf(count, pcm.size)
        if (n <= 0) return ""
        val bucket = pick(buckets(filesDir, model), recognizerModel, n)
        val handle = open(filesDir, bucket)
        return cuts(pcm, n, bucket.npuSamples).mapNotNull { range ->
            val piece = if (range.first == 0 && range.last == pcm.size - 1) pcm else pcm.copyOfRange(range.first, range.last + 1)
            SherpaQnnNative.nativeDecode(handle, piece, piece.size)?.toString(Charsets.UTF_8)?.trim()
        }.filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** Closes the open recognizer. On [EtNative.dispatcher] only, so never under a running decode. */
    fun release() {
        if (recognizer != 0L) SherpaQnnNative.nativeFree(recognizer)
        recognizer = 0L
        recognizerModel = null
        recognizerDir = null
        isOpen = false
    }

    /**
     * [model] and its siblings on the device: the other buckets of the same
     * model for the same chip, shortest first. [model] itself is always one,
     * downloaded or not, so a missing file fails where it is opened.
     */
    private fun buckets(filesDir: File, model: EtModel): List<EtModel> {
        require(model.npu != null) { "${model.label} is not an NPU build" }
        val siblings = EtStore.downloaded(filesDir, model.engine)
            .filter { it.npu == model.npu && it.cpuTwin == model.cpuTwin }
        return (siblings + model).distinctBy { it.id }.sortedBy { it.npuSeconds }
    }

    /**
     * The bucket a clip of [samples] goes to: [open] when it is one of
     * [buckets] and holds the clip whole (no reload mid-dictation), else the
     * shortest that holds it, else the longest, whose pieces [cuts] makes.
     */
    internal fun pick(buckets: List<EtModel>, open: EtModel?, samples: Int): EtModel {
        open?.takeIf { o -> buckets.any { it.id == o.id } && o.npuSamples >= samples }?.let { return it }
        return buckets.firstOrNull { it.npuSamples >= samples } ?: buckets.last()
    }

    /**
     * The first [count] samples of [pcm] as pieces of at most [max] samples,
     * each cut in the quietest 20 ms of the [LOOKBACK_SAMPLES] before its
     * limit, so a word is rarely split. Pure arithmetic, for a test.
     */
    internal fun cuts(pcm: FloatArray, count: Int, max: Int): List<IntRange> {
        val n = minOf(count, pcm.size)
        if (n <= 0 || max <= 0) return emptyList()
        val out = ArrayList<IntRange>()
        var start = 0
        while (n - start > max) {
            val limit = start + max
            val from = maxOf(start + max / 2, limit - LOOKBACK_SAMPLES)
            var best = limit
            var bestEnergy = Double.MAX_VALUE
            var at = from
            while (at + WINDOW_SAMPLES <= limit) {
                var energy = 0.0
                for (i in at until at + WINDOW_SAMPLES) energy += pcm[i] * pcm[i]
                // Later wins a tie: the longer piece leaves less for the rest.
                if (energy <= bestEnergy) {
                    bestEnergy = energy
                    best = at + WINDOW_SAMPLES / 2
                }
                at += WINDOW_SAMPLES
            }
            out += start until best
            start = best
        }
        out += start until n
        return out
    }

    /** [model]'s recognizer, opened if it is not the one open. */
    private fun open(filesDir: File, model: EtModel): Long {
        val dir = EtStore.dir(filesDir, model)
        if (recognizer != 0L && recognizerDir == dir.path) return recognizer
        check(SherpaQnnNative.available) {
            if (SherpaQnnNative.shipped) "sherpa-onnx's NPU library is still downloading"
            else "sherpa-onnx's NPU library is not in this build"
        }
        val runtime = EtNative.qnnRuntime() ?: error("this phone's NPU runtime is not here")
        release()
        // ExecuTorch's CPU programs go back first: the NPU build replaces them.
        EtNative.release()
        SherpaQnnNative.nativeSetAdspDirectory(runtime.absolutePath)
        fun file(name: String) = File(dir, name).path
        val handle = when (model.engine) {
            EtEngine.PARAKEET -> SherpaQnnNative.nativeParakeet(
                file("encoder.bin"), file("decoder.bin"), file("joiner.bin"), file("tokens.txt"), runtime.path,
            )
            EtEngine.MOONSHINE -> SherpaQnnNative.nativeMoonshine(
                file("encoder.bin"), file("decoder.bin"), file("tokens.txt"), runtime.path,
            )
            else -> error("${model.engine} has no NPU build")
        }
        check(handle != 0L) { "${model.label} did not load on the NPU" }
        recognizer = handle
        recognizerModel = model
        recognizerDir = dir.path
        isOpen = true
        return handle
    }
}
