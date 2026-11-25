package com.wasimaster.wmkeyboard.core.semantic

import android.content.Context
import android.graphics.Bitmap
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.localllm.AcceleratorVerdicts
import com.wasimaster.wmkeyboard.core.localllm.NpuRuntime
import com.wasimaster.wmkeyboard.core.localllm.NpuSupport
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.NativeDelivery
import com.wasimaster.wmkeyboard.core.text.GemmaTokenizer
import java.io.Closeable

/**
 * EmbeddingGemma 2 in this process: [GemmaTokenizer] and [TokenTable] on
 * this side, the graphs behind [EmbeddingRuntime] on the other. Built only
 * inside [EmbeddingService]'s process, because a loaded text encoder holds a
 * few hundred megabytes (the interpreter unpacks its int4 weights for the
 * CPU), and that must never count against the keyboard's own process.
 *
 * Opens what it needs on first use and keeps it until [close]: the vision
 * tower only for pictures, and it can be let go alone with [closeVision].
 * Not thread-safe; the service runs it on one thread.
 *
 * The text encoder runs on the NPU when this phone's build of it
 * ([SemanticNpu]) is downloaded, the NPU runtime is here and the build has
 * not failed on this phone before; otherwise on the CPU. A build that will
 * not load or answer is marked failed in [SemanticNpu.verdicts] and the CPU
 * encoder takes the same text, so a search never comes back empty for it.
 */
internal class EmbeddingEngine(private val context: Context) : Closeable {

    private var tokenizer: GemmaTokenizer? = null
    private var table: TokenTable? = null
    private var runtime: EmbeddingRuntime? = null
    private var visionOpen = false

    /** The [AcceleratorVerdicts.key] of the NPU encoder while it is the one open. */
    private var npuKey: String? = null
    private var imageEnd: FloatArray? = null

    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    private val dir get() = context.noBackupFilesDir

    /** One unit vector per text, in order. Throws when the text pack is missing or will not load. */
    fun texts(texts: List<String>, role: EmbeddingInput.Role): List<FloatArray> {
        openText()
        val tokenizer = checkNotNull(tokenizer)
        return texts.map { text -> encode(EmbeddingInput.tokens(tokenizer, text, role)) }
    }

    /** [bitmap] as a unit vector in the same space as the texts. Throws when the pictures pack is missing. */
    fun image(bitmap: Bitmap): FloatArray {
        openText()
        openVision()
        val tokenizer = checkNotNull(tokenizer)
        val table = checkNotNull(table)
        val size = EmbeddingInput.imageSize(bitmap.width, bitmap.height)
        val readable = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val scaled = Bitmap.createScaledBitmap(readable, size[0], size[1], true)
        val pixels = IntArray(size[0] * size[1])
        scaled.getPixels(pixels, 0, size[0], 0, 0, size[0], size[1])
        if (scaled !== readable) scaled.recycle()
        if (readable !== bitmap) readable.recycle()

        val patches = FloatArray(EmbeddingInput.MAX_PATCHES * EmbeddingInput.PATCH_VALUES)
        val positions = IntArray(EmbeddingInput.MAX_PATCHES * 2)
        EmbeddingInput.patches(pixels, size[0], size[1], patches, positions)
        val soft = FloatArray(EmbeddingInput.IMAGE_TOKENS * EmbeddingInput.WIDTH)
        val tokens = checkNotNull(runtime).see(patches, positions, soft)

        // <bos>, <|image>, the soft tokens, the end-of-image vector, <eos>.
        val width = EmbeddingInput.WIDTH
        val length = tokens + 4
        val rows = FloatArray(length * width)
        table.row(tokenizer.bosId, rows, 0)
        table.row(tokenizer.idOf(IMAGE_START), rows, width)
        System.arraycopy(soft, 0, rows, 2 * width, tokens * width)
        System.arraycopy(checkNotNull(imageEnd), 0, rows, (tokens + 2) * width, width)
        table.row(tokenizer.eosId, rows, (tokens + 3) * width)
        val out = FloatArray(EmbeddingInput.DIMENSIONS)
        run(rows, length, out)
        return EmbeddingInput.normalize(out)
    }

    private fun encode(ids: IntArray): FloatArray {
        val table = checkNotNull(table)
        val width = EmbeddingInput.WIDTH
        val rows = FloatArray(ids.size * width)
        for ((k, id) in ids.withIndex()) table.row(id, rows, k * width)
        val out = FloatArray(EmbeddingInput.DIMENSIONS)
        run(rows, ids.size, out)
        return EmbeddingInput.normalize(out)
    }

    /** The encoder, on the NPU while that holds, else on the CPU, which then keeps it. */
    private fun run(rows: FloatArray, length: Int, out: FloatArray) {
        val bridge = checkNotNull(runtime)
        val key = npuKey ?: return bridge.encode(rows, length, out)
        try {
            bridge.encode(rows, length, out)
            SemanticNpu.verdicts.confirm()
            return
        } catch (t: Throwable) {
            DebugLog.w(TAG, "the NPU encoder failed: ${t.javaClass.simpleName}: ${t.message}")
            SemanticNpu.verdicts.markFailed(context, key)
            npuKey = null
        }
        bridge.openText(SemanticModels.file(dir, SemanticModels.Part.TEXT_ENCODER), threads)
        bridge.encode(rows, length, out)
    }

    /**
     * Opens this phone's NPU encoder on [bridge] when it can: downloaded, the
     * runtime here, no failure on record. False leaves [bridge] for the CPU.
     */
    private fun openNpu(bridge: EmbeddingRuntime): Boolean {
        val soc = NpuSupport.soc ?: return false
        val file = SemanticNpu.downloadedFile(dir, soc) ?: return false
        val verdicts = SemanticNpu.verdicts
        verdicts.load(context)
        val key = AcceleratorVerdicts.key(context, file)
        if (key in verdicts) return false
        // Not here yet (still downloading, or Qualcomm's licence not accepted):
        // no verdict on the build, the CPU this time.
        val dispatch = NpuRuntime.directory(context, soc) ?: return false
        verdicts.begin(context, key)
        return try {
            bridge.openTextNpu(file, dispatch)
            npuKey = key
            true
        } catch (t: Throwable) {
            DebugLog.w(TAG, "the NPU encoder did not load: ${t.javaClass.simpleName}: ${t.message}")
            verdicts.markFailed(context, key)
            false
        }
    }

    private fun openText() {
        if (runtime != null) return
        check(SemanticModels.isDownloaded(dir, SemanticModels.Pack.TEXT)) { "the text model is not downloaded" }
        check(FeatureModules.litert.installed) { "the LiteRT module is not installed" }
        // LiteRT loads its library by name; a delivered one goes in first.
        check(NativeDelivery.preloadLitert()) { "libLiteRt.so would not load" }
        val bridge = FeatureModules.load<EmbeddingRuntime>(SemanticModule.BRIDGE_CLASS, context.classLoader)
        try {
            tokenizer = GemmaTokenizer.read(SemanticModels.file(dir, SemanticModels.Part.TOKENIZER))
            table = TokenTable.open(SemanticModels.file(dir, SemanticModels.Part.TOKENS))
            if (!openNpu(bridge)) bridge.openText(SemanticModels.file(dir, SemanticModels.Part.TEXT_ENCODER), threads)
        } catch (t: Throwable) {
            runCatching { bridge.close() }
            table?.close()
            table = null
            tokenizer = null
            throw t
        }
        runtime = bridge
    }

    private fun openVision() {
        if (visionOpen) return
        check(SemanticModels.isDownloaded(dir, SemanticModels.Pack.PICTURES)) { "the pictures model is not downloaded" }
        val end = SemanticModels.file(dir, SemanticModels.Part.IMAGE_END).readBytes()
        val buffer = java.nio.ByteBuffer.wrap(end).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        imageEnd = FloatArray(EmbeddingInput.WIDTH).also { buffer.get(it) }
        checkNotNull(runtime).openVision(
            SemanticModels.file(dir, SemanticModels.Part.VISION_ENCODER),
            SemanticModels.file(dir, SemanticModels.Part.VISION_ADAPTER),
            threads,
        )
        visionOpen = true
    }

    /** Lets the vision tower go, keeping the text side for queries. */
    fun closeVision() {
        if (!visionOpen) return
        runCatching { runtime?.closeVision() }
        visionOpen = false
    }

    override fun close() {
        closeVision()
        // Let go of in an orderly way: nothing for the next process to blame on the NPU.
        if (npuKey != null) SemanticNpu.verdicts.confirm()
        npuKey = null
        runCatching { runtime?.close() }
        runtime = null
        table?.close()
        table = null
        tokenizer = null
    }

    private companion object {
        const val TAG = "EmbeddingEngine"

        /** The token that opens a picture in the sequence. */
        const val IMAGE_START = "<|image>"
    }
}
