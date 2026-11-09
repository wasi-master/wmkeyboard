package com.wasimaster.wmkeyboard.core.voice.moonshine

import com.wasimaster.wmkeyboard.core.voice.executorch.EtEngine
import com.wasimaster.wmkeyboard.core.voice.executorch.EtModel
import com.wasimaster.wmkeyboard.core.voice.executorch.EtNative
import com.wasimaster.wmkeyboard.core.voice.executorch.EtRunner
import com.wasimaster.wmkeyboard.core.voice.executorch.EtStore
import com.wasimaster.wmkeyboard.core.voice.executorch.PieceVocab
import com.wasimaster.wmkeyboard.core.voice.executorch.Tensor
import com.wasimaster.wmkeyboard.core.voice.parakeet.ParakeetEngine
import com.wasimaster.wmkeyboard.core.voice.sherpa.QnnSpeech
import java.io.File
import kotlin.math.ceil

/**
 * Offline Moonshine: Useful Sensors' Moonshine Tiny, a 27M-parameter English
 * encoder-decoder made for short phrases on small devices, run on [EtNative].
 * Unlike Whisper it reads the clip at its own length instead of padding it to
 * 30 seconds, so a short phrase costs a short encode.
 *
 * Two programs: the encoder takes raw audio, and the decoder's
 * `forward_cached` takes the tokens so far plus the encoder's output and
 * returns them with the next token on the end, keeping its attention cache
 * inside the program between calls. Every call runs on [EtNative.dispatcher].
 *
 * An NPU build ([EtModel.npu], k2-fsa's QNN context binaries for one
 * Snapdragon chip, compiled for 10 s clips) runs through [QnnSpeech] instead,
 * on the same thread; a longer clip is decoded in pieces.
 */
object MoonshineEngine {

    const val ENCODER = "moonshine_tiny_xnnpack_encoder.pte"
    const val DECODER = "moonshine_tiny_xnnpack_decoder.pte"
    const val TOKENIZER = "moonshine_tiny_tokenizer.json"

    private const val START = 1L
    private const val END = 2L

    /**
     * Tokens a second of speech can plausibly fill. Moonshine's own guidance
     * caps generation near this, which is what keeps a decoder that has fallen
     * into repeating itself from running to its limit.
     */
    private const val TOKENS_PER_SECOND = 6.5

    private var vocab: PieceVocab? = null
    private var vocabPath: String? = null

    private fun files(filesDir: File, model: EtModel) =
        listOf(EtStore.file(filesDir, model, ENCODER), EtStore.file(filesDir, model, DECODER))

    /** Opens [model] ahead of a clip. On [EtNative.dispatcher] only. Throws when it will not load. */
    fun warm(filesDir: File, model: EtModel) {
        require(model.engine == EtEngine.MOONSHINE)
        val runs = ParakeetEngine.cpuIfNeeded(filesDir, model)
        if (runs.npu != null) return QnnSpeech.warm(filesDir, runs)
        EtNative.programs(files(filesDir, runs))
        vocabFor(filesDir, runs)
    }

    /** [pcm] (16 kHz mono, in [-1, 1]) as text. On [EtNative.dispatcher] only. */
    fun transcribe(filesDir: File, chosen: EtModel, pcm: FloatArray): String {
        val model = ParakeetEngine.cpuIfNeeded(filesDir, chosen)
        if (model.npu != null) return QnnSpeech.transcribe(filesDir, model, pcm)
        val (encoder, decoder) = EtNative.programs(files(filesDir, model))
        return vocabFor(filesDir, model).decode(decode(encoder, decoder, pcm)).trim()
    }

    /** The longest clip the encoder takes, in samples: 30 s. */
    fun maxSamples(encoder: EtRunner): Int =
        encoder.shape("forward")?.inputs?.firstOrNull()?.dims?.lastOrNull()?.toInt() ?: 16_000 * 30

    /** The decoder's token ids for [pcm], without the start and end tokens. */
    internal fun decode(encoder: EtRunner, decoder: EtRunner, pcm: FloatArray): List<Int> {
        val n = minOf(pcm.size, maxSamples(encoder))
        if (n == 0) return emptyList()
        val audio = if (n == pcm.size) pcm else pcm.copyOf(n)
        val hidden = encoder.run("forward", listOf(Tensor(longArrayOf(1, n.toLong()), audio)))[0]
        val limit = decoder.shape("forward_cached")?.inputs?.firstOrNull()?.dims?.lastOrNull()?.toInt() ?: 178
        val budget = minOf(limit, ceil(n / 16_000.0 * TOKENS_PER_SECOND).toInt() + 2)
        val tokens = ArrayList<Long>().apply { add(START) }
        while (tokens.size < budget) {
            val out = decoder.run(
                "forward_cached",
                listOf(Tensor(longArrayOf(1, tokens.size.toLong()), tokens.toLongArray()), hidden),
            )[0].longs
            val next = out.last()
            if (next == END) break
            tokens += next
        }
        return tokens.drop(1).map { it.toInt() }
    }

    private fun vocabFor(filesDir: File, model: EtModel): PieceVocab {
        val file = EtStore.file(filesDir, model, TOKENIZER)
        vocab?.let { if (vocabPath == file.path) return it }
        return PieceVocab.fromTokenizerJson(file.readText()).also {
            vocab = it
            vocabPath = file.path
        }
    }
}
