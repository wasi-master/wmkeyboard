package com.wasimaster.wmkeyboard.core.voice.parakeet

import com.wasimaster.wmkeyboard.core.voice.executorch.EtCatalog
import com.wasimaster.wmkeyboard.core.voice.executorch.EtEngine
import com.wasimaster.wmkeyboard.core.voice.executorch.EtModel
import com.wasimaster.wmkeyboard.core.voice.executorch.EtNative
import com.wasimaster.wmkeyboard.core.voice.executorch.EtRunner
import com.wasimaster.wmkeyboard.core.voice.executorch.EtStore
import com.wasimaster.wmkeyboard.core.voice.executorch.PieceVocab
import com.wasimaster.wmkeyboard.core.voice.executorch.Tensor
import com.wasimaster.wmkeyboard.core.voice.sherpa.QnnSpeech
import com.wasimaster.wmkeyboard.core.voice.vad.VadModels
import java.io.File

/**
 * Offline Parakeet: NVIDIA's Parakeet TDT 0.6B v3, a token-and-duration
 * transducer for 25 European languages that tells them apart itself, run on
 * [EtNative]. It reads a whole clip, but without decoding a word at a time:
 * the encoder reads the clip once, and the transducer then walks its frames,
 * skipping as many as the duration head says. Dictation runs it as a stream
 * ([newStream]) that passes over the clip as it grows, for the words so far.
 *
 * The program carries four methods (preprocessor, encoder, decoder_step,
 * joint) and its own constants as methods (blank_id, num_rnn_layers,
 * pred_hidden), so nothing about the model's shape is hard-coded here beyond
 * defaults for those. Every call runs on [EtNative.dispatcher].
 *
 * An NPU build ([EtModel.npu], k2-fsa's QNN context binaries for one
 * Snapdragon chip) runs through [QnnSpeech] instead, on the same thread.
 */
object ParakeetEngine {

    const val PROGRAM = "model.pte"
    const val TOKENIZER = "tokenizer.model"

    /** The longest clip v3's preprocessor takes: 800,000 samples. */
    const val MAX_CLIP_SECONDS = 50

    /**
     * The longest clip the recorder gives [model]: [MAX_CLIP_SECONDS], or
     * with Parakeet Ultra and its voice-activity head on the device, the
     * same two minutes whisper.cpp gets, since [ParakeetStream] then cuts it
     * at the head's pauses as it passes 50 seconds (#1173). The GPU build
     * runs without the head, so it keeps 50.
     */
    fun maxClipSeconds(filesDir: File, model: EtModel): Int =
        if (model.id == ULTRA_ID && !headFailed && VadModels.ULTRA_HEAD.isDownloaded(filesDir)) LONG_CLIP_SECONDS else MAX_CLIP_SECONDS

    /** [maxClipSeconds] with the head: whisper.cpp's two minutes. */
    const val LONG_CLIP_SECONDS = 120

    /** How far back from the limit a long dictation's cut may go to find a pause (#1173). */
    const val CUT_SEARCH_SECONDS = 15

    /** The model whose voice-activity head [VadModels.ULTRA_HEAD] is: Parakeet Ultra on the CPU. */
    const val ULTRA_ID = "parakeet-ultra"

    /** The head failed to open once this process: it is not tried again, and dictation runs without it. */
    @Volatile private var headFailed = false

    /** The TDT duration head's choices, in frames: v3's, as NeMo exports them. */
    private val DURATIONS = intArrayOf(0, 1, 2, 3, 4)

    /** Symbols one frame may emit before the decoder is moved on by force, as NeMo does. */
    private const val MAX_SYMBOLS_PER_FRAME = 10

    private var vocab: PieceVocab? = null
    private var vocabPath: String? = null

    /** Opens [model] ahead of a clip. On [EtNative.dispatcher] only. Throws when it will not load. */
    fun warm(filesDir: File, model: EtModel) {
        require(model.engine == EtEngine.PARAKEET)
        val runs = runnable(filesDir, model)
        if (runs.npu != null) return QnnSpeech.warm(filesDir, runs)
        open(filesDir, runs)
        vocabFor(filesDir, runs)
    }

    /**
     * [model]'s program, and with Parakeet Ultra its voice-activity head when
     * that is downloaded (#1173), opened as one set so neither closes the
     * other. A head that will not open is left out, and dictation goes on as
     * it did before there was one.
     */
    private fun open(filesDir: File, model: EtModel): Pair<EtNative.Program, EtNative.Program?> {
        val main = EtStore.file(filesDir, model, PROGRAM)
        val head = VadModels.ULTRA_HEAD.takeIf {
            model.id == ULTRA_ID && !headFailed && it.isDownloaded(filesDir)
        }?.file(filesDir, VadModels.ULTRA_HEAD_FILE)
        if (head != null) {
            runCatching { EtNative.programs(listOf(main, head)) }
                .onSuccess { return it[0] to it[1] }
            headFailed = true
        }
        return EtNative.programs(listOf(main)).single() to null
    }

    /**
     * Where to cut a clip too long for [program], from [head]'s logits over
     * the program's own features for it; null when it cannot say. See
     * [ParakeetCut].
     */
    internal fun cutter(program: EtRunner, head: EtRunner): (FloatArray, Int) -> Int? = { pcm, count ->
        runCatching {
            val n = minOf(count, maxSamples(program))
            val audio = if (n == pcm.size) pcm else pcm.copyOf(n)
            val proc = program.run("preprocessor", listOf(Tensor(longArrayOf(n.toLong()), audio), Tensor.long(n.toLong())))
            val mel = proc[0]
            val bins = mel.shape[1].toInt()
            val frames = mel.shape[2].toInt()
            // Whole encoder frames only: the head program takes a multiple of 8.
            val valid = minOf(proc[1].scalar.toInt(), frames).let { it - it % MEL_PER_FRAME }
            if (valid < MIN_HEAD_FRAMES) return@runCatching null
            // The features as the preprocessor padded them; the head reads the real ones.
            val data = if (valid == frames) {
                mel.floats
            } else {
                FloatArray(bins * valid) { i -> mel.floats[(i / valid) * frames + i % valid] }
            }
            val logits = head.run("vad", listOf(Tensor(longArrayOf(1, bins.toLong(), valid.toLong()), data)))[0].floats
            ParakeetCut.pick(logits, n, CUT_SEARCH_SECONDS * SAMPLE_RATE)
        }.getOrNull()
    }

    /** [pcm] (16 kHz mono, in [-1, 1]) as text. On [EtNative.dispatcher] only. */
    fun transcribe(filesDir: File, chosen: EtModel, pcm: FloatArray): String {
        val model = runnable(filesDir, chosen)
        if (model.npu != null) return QnnSpeech.transcribe(filesDir, model, pcm, minOf(pcm.size, MAX_CLIP_SECONDS * 16_000))
        val (program, head) = open(filesDir, model)
        val blank = program.constant("blank_id")?.toInt() ?: DEFAULT_BLANK
        val vocab = vocabFor(filesDir, model)
        val max = maxSamples(program)
        if (head == null || pcm.size <= max) return vocab.decode(decode(program, pcm, blank)).trim()
        // Longer than the model takes, with Ultra's head to say where the
        // pauses are (#1173): one part at a time, each cut at a pause.
        val cut = cutter(program, head)
        val parts = ArrayList<String>()
        var from = 0
        while (pcm.size - from > max) {
            val window = pcm.copyOfRange(from, from + max)
            val at = cut(window, max)?.takeIf { it in 1..max } ?: max
            parts += vocab.decode(decode(program, window.copyOf(at), blank)).trim()
            from += at
        }
        parts += vocab.decode(decode(program, pcm.copyOfRange(from, pcm.size), blank)).trim()
        return parts.filter { it.isNotEmpty() }.joinToString(" ")
    }

    /**
     * A clip heard as it is said (#1168): the words so far come from passes
     * over the clip as it grows, the finished phrase from one over all of it.
     * See [ParakeetStream]. On [EtNative.dispatcher] only. Throws when [model]
     * will not load.
     */
    fun newStream(filesDir: File, chosen: EtModel): ParakeetStream {
        require(chosen.engine == EtEngine.PARAKEET)
        val model = runnable(filesDir, chosen)
        if (model.npu != null) {
            QnnSpeech.warm(filesDir, model)
            // Each pass pads to the bucket it lands in, so a pass costs the
            // same however little was said; QnnSpeech moves to a longer
            // bucket, if one is on the device, as the phrase outgrows it.
            return ParakeetStream(MAX_CLIP_SECONDS * 16_000, transcribe = { pcm, count ->
                QnnSpeech.transcribe(filesDir, model, pcm, count)
            })
        }
        val (program, head) = open(filesDir, model)
        val vocab = vocabFor(filesDir, model)
        val blank = program.constant("blank_id")?.toInt() ?: DEFAULT_BLANK
        return ParakeetStream(
            maxSamples(program),
            transcribe = { pcm, count ->
                vocab.decode(decode(program, if (count == pcm.size) pcm else pcm.copyOf(count), blank)).trim()
            },
            cutAt = head?.let { cutter(program, it) },
        )
    }

    /**
     * [model], or for a GPU build while ExecuTorch's Vulkan build is not the
     * one loaded (its gate still downloading, or this process loaded the
     * plain build before it came), the CPU model from the same weights when
     * that is on the device. A GPU program does not load without the Vulkan
     * delegate; with no twin to fall back on, it throws, as any model that
     * will not load does.
     */
    private fun runnable(filesDir: File, model: EtModel): EtModel = cpuIfNeeded(filesDir, model)

    /**
     * [model], or the CPU model of the same weights when [model] is a GPU or
     * NPU build that cannot run in this process right now: ExecuTorch's
     * Vulkan build not the one loaded, or sherpa-onnx's QNN library or the
     * chip's QNN runtime not here yet (each is asked for, so a later
     * dictation has it). With no CPU twin on the device, it throws, as any
     * model that will not load does. Moonshine's NPU builds go through it too.
     */
    internal fun cpuIfNeeded(filesDir: File, model: EtModel): EtModel {
        val runsNow = when {
            model.npu != null -> QnnSpeech.usable()
            model.gpu -> EtNative.delegate == "vulkan" || run { EtNative.requestVulkan(); false }
            else -> true
        }
        if (runsNow) return model
        val twin = model.cpuTwin?.let(EtCatalog::byId)?.takeIf { EtStore.isDownloaded(filesDir, it) }
        val needs = if (model.npu != null) "sherpa-onnx's NPU library and this phone's NPU runtime" else "ExecuTorch's Vulkan build"
        return twin ?: error("${model.label} needs $needs, which is not loaded")
    }

    /** The longest clip the preprocessor takes, in samples; 50 s for v3. */
    fun maxSamples(program: EtRunner): Int =
        program.shape("preprocessor")?.inputs?.firstOrNull()?.dims?.lastOrNull()?.toInt() ?: DEFAULT_MAX_SAMPLES

    /**
     * The transducer's token ids for [pcm]: preprocessor, encoder, then greedy
     * TDT decoding over the encoder's frames. Pure arithmetic on [runner], so
     * a test can drive it with a fake.
     */
    internal fun decode(runner: EtRunner, pcm: FloatArray, blank: Int): List<Int> {
        val n = minOf(pcm.size, maxSamples(runner))
        if (n == 0) return emptyList()
        val audio = if (n == pcm.size) pcm else pcm.copyOf(n)
        val proc = runner.run("preprocessor", listOf(Tensor(longArrayOf(n.toLong()), audio), Tensor.long(n.toLong())))
        val mel = proc[0]
        val melLength = proc[1].scalar
        val enc = runner.run("encoder", listOf(mel, Tensor.long(melLength)))
        val frames = enc[0].floats
        val frameCount = enc[1].scalar.toInt()
        val dim = enc[0].shape.last().toInt()

        val step = runner.shape("decoder_step") ?: error("program has no decoder_step")
        var h = Tensor.zeros(step.inputs[1])
        var c = Tensor.zeros(step.inputs[2])
        // Primed with the blank token, as NeMo's TDT decoding starts.
        var predicted = runner.run("decoder_step", listOf(Tensor.long(blank.toLong(), longArrayOf(1, 1)), h, c))
        var g = predicted[0]
        h = predicted[1]
        c = predicted[2]

        val out = ArrayList<Int>()
        var t = 0
        var symbolsOnFrame = 0
        val frame = FloatArray(dim)
        while (t < frameCount) {
            System.arraycopy(frames, t * dim, frame, 0, dim)
            val joint = runner.run("joint", listOf(Tensor(longArrayOf(1, 1, dim.toLong()), frame.copyOf()), g))
            val token = joint[0].scalar.toInt()
            val duration = DURATIONS.getOrElse(joint[1].scalar.toInt()) { 1 }
            if (token == blank) {
                t += maxOf(duration, 1)
                symbolsOnFrame = 0
                continue
            }
            out += token
            predicted = runner.run("decoder_step", listOf(Tensor.long(token.toLong(), longArrayOf(1, 1)), h, c))
            g = predicted[0]
            h = predicted[1]
            c = predicted[2]
            t += duration
            if (duration == 0) {
                symbolsOnFrame++
                if (symbolsOnFrame >= MAX_SYMBOLS_PER_FRAME) {
                    t++
                    symbolsOnFrame = 0
                }
            } else {
                symbolsOnFrame = 0
            }
        }
        return out
    }

    private fun vocabFor(filesDir: File, model: EtModel): PieceVocab {
        val file = EtStore.file(filesDir, model, TOKENIZER)
        vocab?.let { if (vocabPath == file.path) return it }
        return PieceVocab.fromSentencePiece(file.readBytes()).also {
            vocab = it
            vocabPath = file.path
        }
    }

    private const val DEFAULT_BLANK = 8192
    private const val SAMPLE_RATE = 16_000

    /** The head program's shortest input, in mel frames. */
    private const val MIN_HEAD_FRAMES = 32

    /** Mel frames to an encoder frame. */
    private const val MEL_PER_FRAME = 8
    private const val DEFAULT_MAX_SAMPLES = 16_000 * 50
}
