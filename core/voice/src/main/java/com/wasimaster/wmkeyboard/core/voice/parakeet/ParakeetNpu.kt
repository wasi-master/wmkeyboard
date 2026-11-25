package com.wasimaster.wmkeyboard.core.voice.parakeet

import com.wasimaster.wmkeyboard.core.voice.executorch.EtModel
import com.wasimaster.wmkeyboard.core.voice.executorch.EtNative
import com.wasimaster.wmkeyboard.core.voice.executorch.EtNpu
import com.wasimaster.wmkeyboard.core.voice.executorch.EtNpuCatalog
import com.wasimaster.wmkeyboard.core.voice.executorch.EtRunner
import com.wasimaster.wmkeyboard.core.voice.executorch.EtStore
import com.wasimaster.wmkeyboard.core.voice.executorch.MethodShape
import com.wasimaster.wmkeyboard.core.voice.executorch.Tensor
import com.wasimaster.wmkeyboard.core.voice.sherpa.QnnSpeech
import java.io.File

/**
 * Parakeet on an NPU through ExecuTorch's own delegate ([EtModel.etNpu]):
 * two programs, the encoder compiled for the chip and for one clip length
 * ([EtModel.npuSeconds]), and the CPU parts every chip shares (preprocessor,
 * decoder step, joint, constants). A clip is padded with silence to the
 * encoder's length, which has no padding mask; a longer one is cut at its
 * quietest moments, and a clip goes to the shortest bucket on the device
 * that holds it, as [QnnSpeech] does for sherpa-onnx's builds. On
 * [EtNative.dispatcher] only; [EtNpu.orCpuTwin] around every call.
 */
internal object ParakeetNpu {

    /** The encoder method for the CPU program's methods, the one the decoding walks. */
    private class Split(private val cpu: EtRunner, private val encoder: EtRunner) : EtRunner {
        override fun shape(method: String): MethodShape? =
            if (method == ENCODER) encoder.shape(FORWARD) else cpu.shape(method)

        override fun run(method: String, inputs: List<Tensor>): List<Tensor> =
            if (method == ENCODER) encoder.run(FORWARD, inputs) else cpu.run(method, inputs)
    }

    private const val ENCODER = "encoder"
    private const val FORWARD = "forward"

    /** The bucket whose programs are open, so a stream stays on it while the clip fits. */
    private var openModel: EtModel? = null

    fun warm(filesDir: File, model: EtModel) {
        open(filesDir, buckets(filesDir, model).first())
    }

    /** The first [count] samples of [pcm] as text, piece by piece when longer than the longest bucket. */
    fun transcribe(filesDir: File, model: EtModel, pcm: FloatArray, count: Int): String {
        val n = minOf(count, pcm.size)
        if (n <= 0) return ""
        val bucket = QnnSpeech.pick(buckets(filesDir, model), openModel, n)
        val (runner, blank) = open(filesDir, bucket)
        val vocab = ParakeetEngine.vocabFor(filesDir, bucket)
        return QnnSpeech.cuts(pcm, n, bucket.npuSamples).map { range ->
            val piece = pcm.copyOfRange(range.first, range.last + 1)
            vocab.decode(decode(runner, piece, bucket.npuSamples, blank)).trim()
        }.filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun open(filesDir: File, model: EtModel): Pair<EtRunner, Int> {
        val (cpu, encoder) = EtNpu.programs(
            model,
            listOf(
                EtStore.file(filesDir, model, EtNpuCatalog.AUX_FILE),
                EtStore.file(filesDir, model, EtNpuCatalog.ENCODER_FILE),
            ),
        )
        openModel = model
        return Split(cpu, encoder) to (cpu.constant("blank_id")?.toInt() ?: DEFAULT_BLANK)
    }

    /** [model] and the other buckets of it on the device (same chip, same delegate), shortest first. */
    private fun buckets(filesDir: File, model: EtModel): List<EtModel> {
        val siblings = EtStore.downloaded(filesDir, model.engine).filter {
            it.etNpu == model.etNpu && it.npu == model.npu && it.cpuTwin == model.cpuTwin
        }
        return (siblings + model).distinctBy { it.id }.sortedBy { it.npuSeconds }
    }

    /**
     * Token ids for [piece], padded with silence to [samples]: the CPU
     * preprocessor over the whole bucket, the NPU encoder over its fixed
     * frames, then the same greedy TDT walk as the CPU build, over every
     * frame (the silence at the end costs a joint call a frame and writes
     * nothing). Pure arithmetic on [runner], for a test.
     */
    internal fun decode(runner: EtRunner, piece: FloatArray, samples: Int, blank: Int): List<Int> {
        if (piece.isEmpty()) return emptyList()
        val audio = if (piece.size == samples) piece else piece.copyOf(samples)
        val proc = runner.run(
            "preprocessor",
            listOf(Tensor(longArrayOf(samples.toLong()), audio), Tensor.long(samples.toLong())),
        )
        val frames = runner.shape(ENCODER)?.inputs?.firstOrNull()?.dims?.lastOrNull()?.toInt()
            ?: error("the NPU program has no encoder")
        val enc = runner.run(ENCODER, listOf(fit(proc[0], frames)))[0]
        return ParakeetEngine.walk(runner, enc.floats, enc.shape[1].toInt(), enc.shape.last().toInt(), blank)
    }

    /**
     * [mel] (`[1, bins, n]`) as exactly [frames] frames: the export makes the
     * two the same, so this only guards a preprocessor that rounds a frame
     * differently, cutting or zero-padding the end.
     */
    internal fun fit(mel: Tensor, frames: Int): Tensor {
        val bins = mel.shape[1].toInt()
        val have = mel.shape[2].toInt()
        if (have == frames) return mel
        val src = mel.floats
        val out = FloatArray(bins * frames)
        val keep = minOf(have, frames)
        for (b in 0 until bins) System.arraycopy(src, b * have, out, b * frames, keep)
        return Tensor(longArrayOf(1, bins.toLong(), frames.toLong()), out)
    }

    private const val DEFAULT_BLANK = 8192
}
