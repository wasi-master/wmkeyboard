package com.wasimaster.wmkeyboard.core.voice.parakeet

import com.wasimaster.wmkeyboard.core.voice.executorch.EtEngine
import com.wasimaster.wmkeyboard.core.voice.executorch.EtModel
import com.wasimaster.wmkeyboard.core.voice.executorch.EtNative
import com.wasimaster.wmkeyboard.core.voice.executorch.EtRunner
import com.wasimaster.wmkeyboard.core.voice.executorch.EtStore
import com.wasimaster.wmkeyboard.core.voice.executorch.PieceVocab
import com.wasimaster.wmkeyboard.core.voice.executorch.Tensor
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
 */
object ParakeetEngine {

    const val PROGRAM = "model.pte"
    const val TOKENIZER = "tokenizer.model"

    /** The longest clip v3's preprocessor takes: 800,000 samples. */
    const val MAX_CLIP_SECONDS = 50

    /** The TDT duration head's choices, in frames: v3's, as NeMo exports them. */
    private val DURATIONS = intArrayOf(0, 1, 2, 3, 4)

    /** Symbols one frame may emit before the decoder is moved on by force, as NeMo does. */
    private const val MAX_SYMBOLS_PER_FRAME = 10

    private var vocab: PieceVocab? = null
    private var vocabPath: String? = null

    /** Opens [model] ahead of a clip. On [EtNative.dispatcher] only. Throws when it will not load. */
    fun warm(filesDir: File, model: EtModel) {
        require(model.engine == EtEngine.PARAKEET)
        EtNative.programs(listOf(EtStore.file(filesDir, model, PROGRAM)))
        vocabFor(filesDir, model)
    }

    /** [pcm] (16 kHz mono, in [-1, 1]) as text. On [EtNative.dispatcher] only. */
    fun transcribe(filesDir: File, model: EtModel, pcm: FloatArray): String {
        val program = EtNative.programs(listOf(EtStore.file(filesDir, model, PROGRAM))).single()
        val tokens = decode(program, pcm, program.constant("blank_id")?.toInt() ?: DEFAULT_BLANK)
        return vocabFor(filesDir, model).decode(tokens).trim()
    }

    /**
     * A clip heard as it is said (#1168): the words so far come from passes
     * over the clip as it grows, the finished phrase from one over all of it.
     * See [ParakeetStream]. On [EtNative.dispatcher] only. Throws when [model]
     * will not load.
     */
    fun newStream(filesDir: File, model: EtModel): ParakeetStream {
        require(model.engine == EtEngine.PARAKEET)
        val program = EtNative.programs(listOf(EtStore.file(filesDir, model, PROGRAM))).single()
        val vocab = vocabFor(filesDir, model)
        val blank = program.constant("blank_id")?.toInt() ?: DEFAULT_BLANK
        return ParakeetStream(maxSamples(program), transcribe = { pcm, count ->
            vocab.decode(decode(program, if (count == pcm.size) pcm else pcm.copyOf(count), blank)).trim()
        })
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
    private const val DEFAULT_MAX_SAMPLES = 16_000 * 50
}
