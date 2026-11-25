package com.wasimaster.wmkeyboard.core.semantic.bridge

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.wasimaster.wmkeyboard.core.semantic.EmbeddingInput
import com.wasimaster.wmkeyboard.core.semantic.EmbeddingRuntime
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter

/**
 * The half of search by meaning that touches LiteRT, and nothing else:
 * EmbeddingGemma 2's text encoder, and its vision encoder and adapter, each
 * run through the named signature that fits. Reached only by reflection (see
 * `SemanticModule.BRIDGE_CLASS`), because on Play it ships in the on-demand
 * `:feature:litert` split and the base APK has no LiteRT import.
 *
 * The encoder carries three signatures, 128, 256 and 512 tokens, sharing one
 * set of weights. The interpreter prepares subgraph 0 when it is built, which
 * the repackaging made the 128-token one; the longer two are prepared the
 * first time a text needs them.
 *
 * The NPU build of the encoder ([openTextNpu]) runs on LiteRT's
 * CompiledModel instead, with the same signatures and tensor names; the
 * vision graphs always run on the interpreter.
 *
 * Public with a public no-arg constructor, both for `newInstance()`.
 */
class LitertEmbeddingRuntime : EmbeddingRuntime {

    private var text: Interpreter? = null

    /** The NPU encoder, when [openTextNpu] loaded one, and the environment that holds its dispatch library. */
    private var npu: CompiledModel? = null
    private var npuEnvironment: Environment? = null

    /** Per signature length: the NPU encoder's embeddings, mask and output buffers. */
    private val npuBuffers = HashMap<Int, Array<TensorBuffer>>()

    private var vision: Interpreter? = null
    private var adapter: Interpreter? = null

    /** Per signature length: the embeddings, the mask, the output. */
    private val textBuffers = HashMap<Int, Array<ByteBuffer>>()
    private var visionBuffers: Array<ByteBuffer>? = null

    override fun openText(encoder: File, threads: Int) {
        closeText()
        text = Interpreter(encoder, Interpreter.Options().apply { numThreads = threads })
    }

    override fun openTextNpu(encoder: File, dispatchDir: File) {
        closeText()
        val environment = Environment.create(mapOf(Environment.Option.DispatchLibraryDir to dispatchDir.path))
        // The CPU beside the NPU takes whatever the compiler left outside the
        // dispatch op: the MediaTek builds keep a few reshapes there.
        npu = try {
            CompiledModel.create(encoder.path, CompiledModel.Options(Accelerator.NPU, Accelerator.CPU), environment)
        } catch (t: Throwable) {
            environment.close()
            throw t
        }
        npuEnvironment = environment
    }

    override fun encode(embeddings: FloatArray, length: Int, out: FloatArray) {
        npu?.let { model ->
            encodeNpu(model, embeddings, length, out)
            return
        }
        val interpreter = checkNotNull(text) { "the text encoder is not open" }
        val sequence = EmbeddingInput.sequenceLength(length)
        val rows = minOf(length, sequence)
        val (input, mask, output) = textBuffers.getOrPut(sequence) {
            arrayOf(
                direct(sequence * EmbeddingInput.WIDTH * Float.SIZE_BYTES),
                direct(sequence * Float.SIZE_BYTES),
                direct(EmbeddingInput.DIMENSIONS * Float.SIZE_BYTES),
            )
        }
        input.clear()
        val floats = input.asFloatBuffer()
        floats.put(embeddings, 0, rows * EmbeddingInput.WIDTH)
        while (floats.hasRemaining()) floats.put(0f)
        mask.clear()
        val maskFloats = mask.asFloatBuffer()
        for (k in 0 until sequence) maskFloats.put(if (k < rows) 1f else 0f)
        input.rewind()
        mask.rewind()
        output.clear()
        interpreter.runSignature(
            mapOf("embeddings" to input, "input_mask" to mask),
            mapOf("encodings" to output),
            "encoder_1x$sequence",
        )
        output.rewind()
        output.asFloatBuffer().get(out, 0, EmbeddingInput.DIMENSIONS)
    }

    /** [encode] on the NPU build: the same signature and names, through TensorBuffers. */
    private fun encodeNpu(model: CompiledModel, embeddings: FloatArray, length: Int, out: FloatArray) {
        val sequence = EmbeddingInput.sequenceLength(length)
        val rows = minOf(length, sequence)
        val signature = "encoder_1x$sequence"
        val (input, mask, output) = npuBuffers.getOrPut(sequence) {
            arrayOf(
                model.createInputBuffer("embeddings", signature),
                model.createInputBuffer("input_mask", signature),
                model.createOutputBuffer("encodings", signature),
            )
        }
        val padded = FloatArray(sequence * EmbeddingInput.WIDTH)
        System.arraycopy(embeddings, 0, padded, 0, rows * EmbeddingInput.WIDTH)
        input.writeFloat(padded)
        mask.writeFloat(FloatArray(sequence) { if (it < rows) 1f else 0f })
        model.run(mapOf("embeddings" to input, "input_mask" to mask), mapOf("encodings" to output), signature)
        val encodings = output.readFloat()
        System.arraycopy(encodings, 0, out, 0, EmbeddingInput.DIMENSIONS)
    }

    override fun openVision(encoder: File, adapter: File, threads: Int) {
        closeVision()
        val options = Interpreter.Options().apply { numThreads = threads }
        val vision = Interpreter(encoder, options)
        val adapting = try {
            Interpreter(adapter, options)
        } catch (t: Throwable) {
            vision.close()
            throw t
        }
        this.vision = vision
        this.adapter = adapting
        val tokens = EmbeddingInput.IMAGE_TOKENS
        visionBuffers = arrayOf(
            direct(EmbeddingInput.MAX_PATCHES * EmbeddingInput.PATCH_VALUES * Float.SIZE_BYTES),
            direct(EmbeddingInput.MAX_PATCHES * 2 * Int.SIZE_BYTES),
            direct(tokens * FEATURES * Float.SIZE_BYTES),
            direct(tokens),
            direct(tokens * EmbeddingInput.WIDTH * Float.SIZE_BYTES),
        )
    }

    override fun see(patches: FloatArray, positions: IntArray, out: FloatArray): Int {
        val encoder = checkNotNull(vision) { "the vision encoder is not open" }
        val adapting = checkNotNull(adapter)
        val (images, xy, features, mask, soft) = checkNotNull(visionBuffers)
        images.clear()
        images.asFloatBuffer().put(patches)
        xy.clear()
        xy.asIntBuffer().put(positions)
        features.clear()
        mask.clear()
        encoder.runSignature(
            mapOf("images" to images, "positions_xy" to xy),
            mapOf("features" to features, "mask" to mask),
            "vision_${EmbeddingInput.IMAGE_TOKENS}",
        )
        features.rewind()
        soft.clear()
        adapting.runSignature(
            mapOf("soft_tokens" to features),
            mapOf("mm_embedding" to soft),
            "vision_adapter_${EmbeddingInput.IMAGE_TOKENS}",
        )
        var valid = 0
        for (k in 0 until EmbeddingInput.IMAGE_TOKENS) if (mask.get(k).toInt() != 0) valid++
        soft.rewind()
        soft.asFloatBuffer().get(out, 0, valid * EmbeddingInput.WIDTH)
        return valid
    }

    override fun closeVision() {
        vision?.close()
        adapter?.close()
        vision = null
        adapter = null
        visionBuffers = null
    }

    override fun close() {
        closeVision()
        closeText()
    }

    private fun closeText() {
        text?.close()
        text = null
        textBuffers.clear()
        for (buffers in npuBuffers.values) buffers.forEach { runCatching { it.close() } }
        npuBuffers.clear()
        runCatching { npu?.close() }
        npu = null
        runCatching { npuEnvironment?.close() }
        npuEnvironment = null
    }

    private fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

    private companion object {
        /** Width of the vision encoder's features, before the adapter. */
        const val FEATURES = 768
    }
}
