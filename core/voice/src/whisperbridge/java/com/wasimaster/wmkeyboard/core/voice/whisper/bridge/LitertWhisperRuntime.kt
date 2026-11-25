package com.wasimaster.wmkeyboard.core.voice.whisper.bridge

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperException
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperMel
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperRuntime
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperVocab
import com.wasimaster.wmkeyboard.voice.R
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.locks.ReentrantLock
import org.tensorflow.lite.Interpreter

/**
 * The half of offline Whisper that touches LiteRT, and nothing else: the
 * DocWolle `.tflite` graphs run the whole autoregressive decode internally,
 * so this feeds the `[1, nMel, 3000]` log-mel spectrogram, reads back the
 * `sequences` token ids and detokenizes. Reached only by reflection (see
 * `WhisperModule.BRIDGE_CLASS`), because on Play it ships in the on-demand
 * `:feature:litert` split and the base APK has no LiteRT import.
 *
 * Interpreter init memory-maps the model and is slow, so one interpreter and
 * one parsed vocab are cached and rebuilt only when the file paths change.
 *
 * Three graph shapes exist and the signature keys tell them apart: the plain
 * multilingual graphs auto-detect the language and offer transcribe/translate;
 * the grouped `TOP_WORLD`/`EUROPEAN_UNION` graphs add `serving_transcribe_lang`,
 * which takes a scalar `lang_token` so the caller forces one language; and the
 * single-language and `.en` graphs expose just `serving_default`.
 *
 * **No decoder prefix can be supplied.** Whisper itself takes an initial prompt,
 * a piece of text prepended to the decoder as `<|startofprev|>` tokens to bias
 * spelling and vocabulary, and Whisper's own language forcing works the same way.
 * Neither is reachable here: each of these graphs was exported with its whole
 * `generate()` call traced inside, `forced_decoder_ids` and all, and the exported
 * signature takes only `input_features` (plus `lang_token` where present). The
 * grouped graphs make this concrete — the language input selects between one
 * traced branch per language, so even a token they were not built with does
 * nothing. A prompt, or forcing a language no branch covers, needs graphs that
 * expose the encoder and the decoder as separate signatures and an autoregressive
 * loop written on this side; the published conversions do not.
 *
 * The NPU builds of Whisper Tiny ([transcribeNpu]) are another shape: an
 * `encode` and a `decode` signature on LiteRT's CompiledModel, the decoding
 * loop written here, so they do take a forced language.
 *
 * Public with a public no-arg constructor, both for `newInstance()`.
 */
class LitertWhisperRuntime : WhisperRuntime {


    private companion object {
        const val SIG_TRANSCRIBE = "serving_transcribe"
        const val SIG_TRANSCRIBE_LANG = "serving_transcribe_lang"
        const val SIG_TRANSLATE = "serving_translate"
        const val SIG_DEFAULT = "serving_default"
        const val INPUT_NAME = "input_features"
        const val INPUT_LANG = "lang_token"
        const val OUTPUT_NAME = "sequences"

        // The NPU builds' signatures and tensor names (litert-community/whisper-tiny).
        const val NPU_ENCODE = "encode"
        const val NPU_DECODE = "decode"
        const val NPU_MEL = "args_0"
        const val NPU_FEATURES = "args_0"
        const val NPU_TOKENS = "args_1"
        const val NPU_MASK = "args_2"
        const val NPU_OUTPUT = "output_0"

        /** A masked-out position in the decoder's additive attention mask (Google's sample uses the same). */
        const val MASKED = -0.7f * Float.MAX_VALUE

        // Multilingual Whisper's special tokens, from end-of-transcript.
        const val SOT_OFFSET = 1
        const val FIRST_LANGUAGE_OFFSET = 2
        const val TRANSLATE_OFFSET = 101
        const val TRANSCRIBE_OFFSET = 102
        const val NO_TIMESTAMPS_OFFSET = 106
    }

    /** An NPU build loaded on CompiledModel, with its buffers and the sizes read from it. */
    private class NpuGraph(
        val environment: Environment,
        val model: CompiledModel,
        val mel: TensorBuffer,
        val features: TensorBuffer,
        val tokens: TensorBuffer,
        val mask: TensorBuffer,
        val logits: TensorBuffer,
        val melValues: Int,
        val positions: Int,
        val vocabulary: Int,
    ) {
        fun close() {
            for (buffer in listOf(mel, features, tokens, mask, logits)) runCatching { buffer.close() }
            runCatching { model.close() }
            runCatching { environment.close() }
        }
    }

    private var npu: NpuGraph? = null
    private var loadedNpuPath: String? = null

    private val lock = ReentrantLock()
    private var interpreter: Interpreter? = null
    private var loadedModelPath: String? = null
    private var vocab: WhisperVocab? = null
    private var loadedVocabPath: String? = null

    /**
     * Transcribes one PCM utterance (16 kHz mono float in [-1, 1]) to text.
     * [translate] forces the translate-to-English task where the model supports
     * it. [langToken] is the Whisper `<|xx|>` token id to force on a grouped
     * graph — null (or a graph without the input) means auto-detect. Call on a
     * background dispatcher only. Throws [WhisperException] on failure, which
     * carries the string resource the UI should show.
     */
    override fun transcribe(
        modelFile: File,
        vocabFile: File,
        pcm: FloatArray,
        translate: Boolean,
        langToken: Int?,
    ): String {
        lock.lock()
        try {
            val voc = obtainVocab(vocabFile)
            val itp = try {
                obtainInterpreter(modelFile)
            } catch (e: Throwable) {
                releaseLocked()
                DebugLog.w("LitertWhisper", "${modelFile.name} did not load: ${e.javaClass.simpleName}: ${e.message}")
                // LiteRT's own library failing to link says nothing about the
                // file, and deleting and importing it again cannot help (#994).
                val message = if (e is LinkageError) {
                    R.string.core_voice_whisper_runtime_load_error
                } else {
                    R.string.core_voice_whisper_model_load_error
                }
                throw WhisperException(message, cause = e)
            }

            requireMelBins(itp, voc.nMel)

            val mel = WhisperMel.compute(pcm, voc.filters, voc.nFft, voc.nMel)
            val input = ByteBuffer
                .allocateDirect(voc.nMel * WhisperMel.MEL_LEN * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
            for (v in mel) input.putFloat(v)
            input.rewind()

            val outLen = (itp.getOutputTensor(0)?.shape()?.lastOrNull() ?: 451).coerceAtLeast(1)
            val output = Array(1) { IntArray(outLen) }

            val signature = pickSignature(itp, translate, langToken != null)
            try {
                when {
                    signature == SIG_TRANSCRIBE_LANG -> itp.runSignature(
                        mapOf(INPUT_NAME to input, INPUT_LANG to scalarInt(requireNotNull(langToken))),
                        mapOf(OUTPUT_NAME to output),
                        signature,
                    )
                    signature != null -> itp.runSignature(
                        mapOf(INPUT_NAME to input), mapOf(OUTPUT_NAME to output), signature,
                    )
                    else -> itp.run(input, output)
                }
            } catch (e: Throwable) {
                releaseLocked()
                throw WhisperException(R.string.core_voice_whisper_transcribe_error, cause = e)
            }

            return voc.decode(output[0]).trim()
        } finally {
            lock.unlock()
        }
    }

    /**
     * Loads the interpreter and the vocabulary ahead of [transcribe]. Mapping a
     * graph and building its interpreter takes about as long as a short phrase
     * takes to say, so done while the phrase is being recorded it costs the user
     * nothing, and done after it they wait for it. Best effort: whatever goes
     * wrong here goes wrong again in [transcribe], which is where it is reported.
     * Call on a background dispatcher only.
     */
    override fun warm(modelFile: File, vocabFile: File) {
        lock.lock()
        try {
            runCatching {
                obtainVocab(vocabFile)
                obtainInterpreter(modelFile)
            }.onFailure { releaseLocked() }
        } finally {
            lock.unlock()
        }
    }

    override fun transcribeNpu(
        npuModel: File,
        dispatchDir: File,
        vocabFile: File,
        pcm: FloatArray,
        translate: Boolean,
        langToken: Int?,
    ): String {
        lock.lock()
        try {
            val voc = obtainVocab(vocabFile)
            val graph = obtainNpu(npuModel, dispatchDir)
            if (voc.nMel * WhisperMel.MEL_LEN != graph.melValues) {
                throw WhisperException(R.string.core_voice_whisper_mel_bins_error, (graph.melValues / WhisperMel.MEL_LEN).toString())
            }
            graph.mel.writeFloat(WhisperMel.compute(pcm, voc.filters, voc.nFft, voc.nMel))
            graph.model.run(mapOf(NPU_MEL to graph.mel), mapOf(NPU_OUTPUT to graph.features), NPU_ENCODE)

            val eot = voc.eot
            val n = graph.positions
            val ids = IntArray(n)
            var length = 0
            // Greedy over a range of the logits at the last filled position.
            fun next(from: Int, until: Int): Int {
                graph.tokens.writeInt(ids)
                graph.model.run(
                    mapOf(NPU_FEATURES to graph.features, NPU_TOKENS to graph.tokens, NPU_MASK to graph.mask),
                    mapOf(NPU_OUTPUT to graph.logits),
                    NPU_DECODE,
                )
                val logits = graph.logits.readFloat()
                val row = (length - 1) * graph.vocabulary
                var best = from
                for (token in from until minOf(until, graph.vocabulary)) {
                    if (logits[row + token] > logits[row + best]) best = token
                }
                return best
            }
            ids[length++] = eot + SOT_OFFSET
            // Read before the slot is taken: next() looks at the last filled position.
            val language = langToken ?: next(eot + FIRST_LANGUAGE_OFFSET, eot + TRANSLATE_OFFSET)
            ids[length++] = language
            ids[length++] = eot + if (translate) TRANSLATE_OFFSET else TRANSCRIBE_OFFSET
            ids[length++] = eot + NO_TIMESTAMPS_OFFSET
            val prompt = length
            // Text tokens or the end of the transcript; never a timestamp or
            // another special token, which this prompt does not ask for.
            while (length < n) {
                val token = next(0, eot + 1)
                if (token == eot) break
                ids[length++] = token
            }
            return voc.decode(ids.copyOfRange(prompt, length)).trim()
        } catch (e: Throwable) {
            releaseNpuLocked()
            throw e
        } finally {
            lock.unlock()
        }
    }

    override fun warmNpu(npuModel: File, dispatchDir: File, vocabFile: File) {
        lock.lock()
        try {
            obtainVocab(vocabFile)
            obtainNpu(npuModel, dispatchDir)
        } catch (e: Throwable) {
            releaseNpuLocked()
            throw e
        } finally {
            lock.unlock()
        }
    }

    /**
     * The NPU build on CompiledModel, loaded once and kept until another file
     * or [release]. The causal mask never changes, so it is written here.
     * Loading it lets the CPU graph go: one Whisper in memory at a time.
     */
    private fun obtainNpu(file: File, dispatchDir: File): NpuGraph {
        npu?.let { if (loadedNpuPath == file.path) return it }
        releaseNpuLocked()
        releaseLocked()
        val environment = Environment.create(mapOf(Environment.Option.DispatchLibraryDir to dispatchDir.path))
        val model = try {
            CompiledModel.create(file.path, CompiledModel.Options(Accelerator.NPU, Accelerator.CPU), environment)
        } catch (t: Throwable) {
            environment.close()
            throw t
        }
        try {
            fun elements(name: String, signature: String, input: Boolean): Int {
                val type = if (input) model.getInputTensorType(name, signature) else model.getOutputTensorType(name, signature)
                return checkNotNull(type.layout) { "$name has no shape" }.dimensions.fold(1, Int::times)
            }
            val positions = elements(NPU_TOKENS, NPU_DECODE, input = true)
            val logits = elements(NPU_OUTPUT, NPU_DECODE, input = false)
            val graph = NpuGraph(
                environment = environment,
                model = model,
                mel = model.createInputBuffer(NPU_MEL, NPU_ENCODE),
                features = model.createOutputBuffer(NPU_OUTPUT, NPU_ENCODE),
                tokens = model.createInputBuffer(NPU_TOKENS, NPU_DECODE),
                mask = model.createInputBuffer(NPU_MASK, NPU_DECODE),
                logits = model.createOutputBuffer(NPU_OUTPUT, NPU_DECODE),
                melValues = elements(NPU_MEL, NPU_ENCODE, input = true),
                positions = positions,
                vocabulary = logits / positions,
            )
            val mask = FloatArray(positions * positions) { MASKED }
            for (row in 0 until positions) for (column in 0..row) mask[row * positions + column] = 0f
            graph.mask.writeFloat(mask)
            npu = graph
            loadedNpuPath = file.path
            return graph
        } catch (t: Throwable) {
            runCatching { model.close() }
            environment.close()
            throw t
        }
    }

    private fun releaseNpuLocked() {
        npu?.close()
        npu = null
        loadedNpuPath = null
    }

    /** Frees the cached interpreter. Non-blocking, so IME trim-memory never waits. */
    override fun loadedModel(): File? = (loadedNpuPath ?: loadedModelPath)?.let(::File)

    override fun release() {
        if (!lock.tryLock()) return
        try {
            releaseLocked()
            releaseNpuLocked()
        } finally {
            lock.unlock()
        }
    }

    /**
     * Fails a model whose spectrogram input has a different band count from the
     * filterbank shipped beside it. Whisper switched from 80 bands to 128 at
     * large-v3, so a model paired with the wrong vocab binary would otherwise
     * happily consume wrong-shaped features and decode confident nonsense
     * instead of erroring.
     */
    private fun requireMelBins(itp: Interpreter, filterBins: Int) {
        // A grouped graph's tensor 0 is the scalar lang_token, whose shape has no
        // mel axis at all — nothing to check there.
        val bins = runCatching { itp.getInputTensor(0)?.shape() }.getOrNull()
            ?.takeIf { it.size >= 3 }?.get(1)
            ?: return
        if (bins > 0 && bins != filterBins) {
            releaseLocked()
            throw WhisperException(R.string.core_voice_whisper_mel_bins_error, bins.toString())
        }
    }

    /**
     * Translating always wins over forcing a language — the translate task
     * detects the source itself, and no graph offers both at once.
     */
    private fun pickSignature(itp: Interpreter, translate: Boolean, forceLang: Boolean): String? {
        val keys = runCatching { itp.signatureKeys }.getOrNull()?.toSet() ?: return null
        if (keys.isEmpty()) return null
        return when {
            translate && SIG_TRANSLATE in keys -> SIG_TRANSLATE
            forceLang && SIG_TRANSCRIBE_LANG in keys -> SIG_TRANSCRIBE_LANG
            SIG_TRANSCRIBE in keys -> SIG_TRANSCRIBE
            SIG_DEFAULT in keys -> SIG_DEFAULT
            else -> keys.first()
        }
    }

    /** The `lang_token` scalar as a raw 4-byte buffer, which fits its `()` int32 spec. */
    private fun scalarInt(value: Int): ByteBuffer =
        ByteBuffer.allocateDirect(Int.SIZE_BYTES).order(ByteOrder.nativeOrder()).apply {
            putInt(value)
            rewind()
        }

    private fun obtainVocab(vocabFile: File): WhisperVocab {
        vocab?.let { if (loadedVocabPath == vocabFile.path) return it }
        val multilingual = !vocabFile.name.contains("_en", ignoreCase = true)
        return WhisperVocab.load(vocabFile, multilingual).also {
            vocab = it
            loadedVocabPath = vocabFile.path
        }
    }

    private fun obtainInterpreter(modelFile: File): Interpreter {
        interpreter?.let { if (loadedModelPath == modelFile.path) return it }
        releaseLocked()
        releaseNpuLocked()
        val buffer = FileInputStream(modelFile).use { fis ->
            fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, fis.channel.size())
        }
        val options = Interpreter.Options().apply {
            numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        }
        return Interpreter(buffer, options).also {
            interpreter = it
            loadedModelPath = modelFile.path
        }
    }

    private fun releaseLocked() {
        runCatching { interpreter?.close() }
        interpreter = null
        loadedModelPath = null
    }
}
