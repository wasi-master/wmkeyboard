package com.wasimaster.wmkeyboard.core.upscale

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.engine.NativeEngine
import com.wasimaster.wmkeyboard.core.imagegen.ImageGenEngine
import com.wasimaster.wmkeyboard.core.imagegen.ImageGenNative
import com.wasimaster.wmkeyboard.core.imagegen.QnnGraph
import com.wasimaster.wmkeyboard.core.imagegen.QnnImageNative
import com.wasimaster.wmkeyboard.core.imagegen.QnnRuntime
import com.wasimaster.wmkeyboard.core.localllm.AcceleratorVerdicts
import com.wasimaster.wmkeyboard.core.localllm.NpuRuntime
import com.wasimaster.wmkeyboard.core.localllm.NpuSupport
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** What the upscaler is asked to do. Plain values, so it crosses processes in a Bundle. */
data class UpscaleRequest(
    /** A picture file any decoder reads. */
    val input: String,
    /** Where the result goes: PNG when the picture has transparency, JPEG when it has none. */
    val output: String,
    val kind: UpscaleKind,
    /** 2 or 4. */
    val factor: Int,
)

/**
 * The upscaler itself, in the sticker generator's process
 * ([com.wasimaster.wmkeyboard.core.imagegen.ImageGenService]): one network
 * loaded at a time, run a tile at a time so neither the picture's float copy
 * nor the network's working memory ever covers the whole picture. ×4 is two ×2
 * passes. A cancel lands between two tiles.
 *
 * The network runs on MNN's CPU runtime, or on a Snapdragon's NPU when its
 * build for that chip is pinned and downloaded ([UpscaleModels.npuBuild]) and
 * Qualcomm's runtime is already on the phone for another NPU feature: the
 * upscaler never fetches that runtime or asks for its licence on its own. An
 * NPU run that fails falls back to the CPU at once, and the build is not tried
 * again on this phone until the app or the system is updated
 * ([AcceleratorVerdicts], which also catches a run that took the process down).
 *
 * Transparency goes through the network too, as a grey picture of its own
 * after the colour: measured on stickers at ×4, an alpha channel enlarged by
 * plain filtering instead leaves the outline soft (28.6 dB against 36.7 for
 * the same network). The colour under fully transparent pixels is filled in from the
 * visible edge first (see [UpscaleTiles.bleed]), or the network would draw
 * whatever the encoder left there into the sticker's outline.
 */
class UpscaleEngine internal constructor(private val context: Context) : Closeable {

    private var handle = 0L
    private var loadedId: String? = null

    /** NPU builds that failed on this phone; read on this process's first NPU run. */
    private val verdicts = AcceleratorVerdicts(NPU_FAILED_FILE, NPU_ATTEMPT_FILE)

    /** One way of running the network over a whole picture, a ×2 pass at a time. */
    private interface Network {
        /** How many tiles a pass over a [width] by [height] picture takes. */
        fun tiles(width: Int, height: Int): Int

        /** One ×2 pass; [put] gets each tile's result where it belongs. False when cancelled. */
        fun pass(pixels: IntArray, width: Int, height: Int, cancelled: AtomicBoolean, put: (Int, Int, Int, Int, IntArray) -> Unit): Boolean
    }

    /** Something the NPU did wrong, as opposed to the picture or the disk: the CPU takes over. */
    private class NpuFailure(message: String, cause: Throwable? = null) : IOException(message, cause)

    /**
     * Enlarges [request]'s picture into its output file. [progress] gets
     * (tiles done, tiles in all) after each tile; it starts over if the NPU
     * fails partway and the CPU redoes the picture. False when it was cancelled.
     */
    internal fun run(request: UpscaleRequest, cancelled: AtomicBoolean, progress: (Int, Int) -> Unit): Boolean {
        val passes = when (request.factor) {
            2 -> 1
            4 -> 2
            else -> throw IOException("×${request.factor} is not a factor the upscaler offers")
        }
        val source = BitmapFactory.decodeFile(request.input, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw IOException("the picture would not decode")
        val width = source.width
        val height = source.height
        val colour = IntArray(width * height)
        source.getPixels(colour, 0, width, 0, 0, width, height)
        source.recycle()
        val alpha = if (UpscaleTiles.hasAlpha(colour)) UpscaleTiles.alphaAsGrey(colour) else null
        if (alpha != null) UpscaleTiles.bleed(colour, width, height)

        npu(request.kind)?.let { (npu, key) ->
            try {
                verdicts.begin(context, key)
                val done = enlarge(npuNetwork(npu), colour, alpha, width, height, passes, request, cancelled, progress)
                verdicts.confirm()
                return done
            } catch (e: NpuFailure) {
                DebugLog.w(TAG, "NPU upscale failed, the CPU takes over: ${e.message}")
                verdicts.markFailed(context, key)
            } finally {
                // Anything else (a cancel, a full disk) is not the NPU's doing.
                verdicts.confirm()
                // The runtime holds the HTP at full clocks while it is open:
                // let it go between pictures rather than for the idle minutes.
                npu.close()
                QnnRuntime.release()
            }
        }
        val net = load(UpscaleModels.Model.of(request.kind))
        return enlarge(cpuNetwork(net), colour, alpha, width, height, passes, request, cancelled, progress)
    }

    /** Every pass of [net] over the picture, written to [request]'s output. Leaves [colour] and [alpha] as they were. */
    private fun enlarge(
        net: Network,
        colour: IntArray,
        alpha: IntArray?,
        width0: Int,
        height0: Int,
        passes: Int,
        request: UpscaleRequest,
        cancelled: AtomicBoolean,
        progress: (Int, Int) -> Unit,
    ): Boolean {
        var width = width0
        var height = height0
        var rgb = colour
        var grey = alpha
        val total = (0 until passes).sumOf { p -> net.tiles(width shl p, height shl p) } * (if (grey != null) 2 else 1)
        var done = 0
        val step = { if (!cancelled.get()) progress(++done, total) }

        // Every pass but the last into an array twice the size; the last one
        // straight into the result, alpha first so the colour can carry it.
        repeat(passes - 1) {
            grey = grey?.let { pass(net, it, width, height, cancelled, step) ?: return false }
            rgb = pass(net, rgb, width, height, cancelled, step) ?: return false
            width *= UpscaleModels.SCALE
            height *= UpscaleModels.SCALE
        }
        val bigAlpha = grey?.let { pass(net, it, width, height, cancelled, step) ?: return false }
        grey = null
        val outWidth = width * UpscaleModels.SCALE
        val out = Bitmap.createBitmap(outWidth, height * UpscaleModels.SCALE, Bitmap.Config.ARGB_8888)
        try {
            val finished = net.pass(rgb, width, height, cancelled) { x, y, w, h, tile ->
                if (bigAlpha != null && !NativeEngine.mergeAlpha(tile, w, h, bigAlpha, outWidth, y * outWidth + x)) {
                    for (row in 0 until h) {
                        val from = (y + row) * outWidth + x
                        for (col in 0 until w) {
                            val i = row * w + col
                            tile[i] = (UpscaleTiles.greyToAlpha(bigAlpha[from + col]) shl 24) or (tile[i] and RGB_MASK)
                        }
                    }
                }
                out.setPixels(tile, 0, w, x, y, w, h)
                step()
            }
            if (!finished) return false
            write(out, File(request.output), bigAlpha != null)
            return true
        } finally {
            out.recycle()
        }
    }

    /** One ×2 pass of [pixels] into a new array, or null when cancelled. */
    private fun pass(
        net: Network,
        pixels: IntArray,
        width: Int,
        height: Int,
        cancelled: AtomicBoolean,
        step: () -> Unit,
    ): IntArray? {
        val outWidth = width * UpscaleModels.SCALE
        val out = IntArray(outWidth * height * UpscaleModels.SCALE)
        val finished = net.pass(pixels, width, height, cancelled) { x, y, w, h, rgb ->
            for (row in 0 until h) System.arraycopy(rgb, row * w, out, (y + row) * outWidth + x, w)
            step()
        }
        return if (finished) out else null
    }

    /** [UpscaleTiles.pass] on the loaded MNN network, its answer checked for shape. */
    private fun cpuNetwork(net: Long) = object : Network {
        private val outShape = IntArray(4)

        override fun tiles(width: Int, height: Int) = UpscaleTiles.tiles(width, height, UpscaleTiles.side(width, height)).size

        override fun pass(
            pixels: IntArray, width: Int, height: Int, cancelled: AtomicBoolean, put: (Int, Int, Int, Int, IntArray) -> Unit,
        ) = UpscaleTiles.pass(pixels, width, height, network = { input, inSide ->
            val outSide = (inSide - 2 * UpscaleModels.CONTEXT) * UpscaleModels.SCALE
            val result = ImageGenNative.nativeRun(net, arrayOf(input), arrayOf(intArrayOf(1, 3, inSide, inSide)), outShape)
                ?: throw IOException("MNN refused the tile")
            if (outShape[1] != 3 || outShape[2] != outSide || outShape[3] != outSide) {
                throw IOException("the network answered ${outShape.joinToString("×")} for a $inSide tile")
            }
            result
        }, cancelled = { cancelled.get() }, put = put)
    }

    /** [UpscaleTiles.passChannelsLast] on the NPU graph; anything it refuses is an [NpuFailure]. */
    private fun npuNetwork(graph: QnnGraph) = object : Network {
        override fun tiles(width: Int, height: Int) = UpscaleTiles.tiles(width, height, UpscaleModels.TILE).size

        override fun pass(
            pixels: IntArray, width: Int, height: Int, cancelled: AtomicBoolean, put: (Int, Int, Int, Int, IntArray) -> Unit,
        ) = UpscaleTiles.passChannelsLast(pixels, width, height, network = { input ->
            try {
                graph.run(input).first()
            } catch (e: IOException) {
                throw NpuFailure(e.message ?: "the NPU refused the tile", e)
            }
        }, cancelled = { cancelled.get() }, put = put)
    }

    /**
     * [kind]'s NPU graph, loaded, with its failure key, or null to use the
     * CPU: no build for this chip, not downloaded, Qualcomm's runtime not on
     * the phone, or a failure remembered. The QNN runtime is acquired with it.
     */
    private fun npu(kind: UpscaleKind): Pair<QnnGraph, String>? {
        val soc = NpuSupport.qnnSoc ?: return null
        val build = UpscaleModels.npuBuild(kind, soc) ?: return null
        val noBackup = context.noBackupFilesDir
        if (!UpscaleModels.isDownloaded(noBackup, build) || !QnnImageNative.available || !NpuRuntime.installed(soc)) return null
        val file = UpscaleModels.npuFile(noBackup, build)
        verdicts.load(context)
        val key = AcceleratorVerdicts.key(context, file)
        if (key in verdicts) return null
        val runtime = NpuRuntime.directory(context, soc) ?: return null
        verdicts.begin(context, key)
        try {
            QnnRuntime.acquire(runtime)
        } catch (e: IOException) {
            DebugLog.w(TAG, "QNN runtime: ${e.message}")
            verdicts.markFailed(context, key)
            return null
        }
        val graph = try {
            QnnGraph.open(file).also { checkShape(it) }
        } catch (e: IOException) {
            DebugLog.w(TAG, "${file.name}: ${e.message}")
            verdicts.markFailed(context, key)
            QnnRuntime.release()
            return null
        }
        verdicts.confirm()
        return graph to key
    }

    /** The graph must be what tools/qnn-context makes: one NPU_INPUT square in, twice the tile out, RGB. */
    private fun checkShape(graph: QnnGraph) {
        val inSide = UpscaleModels.NPU_INPUT
        val outSide = UpscaleModels.TILE * UpscaleModels.SCALE
        val input = graph.inputs.singleOrNull()?.dims
        val output = graph.outputs.firstOrNull()?.dims
        val fits = input != null && output != null &&
            input.contentEquals(intArrayOf(1, inSide, inSide, 3)) && output.contentEquals(intArrayOf(1, outSide, outSide, 3))
        if (!fits) {
            graph.close()
            throw IOException("the NPU build takes ${input?.joinToString("×")} and answers ${output?.joinToString("×")}")
        }
    }

    private fun write(bitmap: Bitmap, target: File, alpha: Boolean) {
        target.parentFile?.mkdirs()
        val staged = File(target.path + ".part")
        staged.outputStream().use { stream ->
            val format = if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            if (!bitmap.compress(format, if (alpha) PNG_QUALITY else JPEG_QUALITY, stream)) {
                throw IOException("the result would not encode")
            }
        }
        target.delete()
        if (!staged.renameTo(target)) throw IOException("could not move the result into place")
    }

    private fun load(model: UpscaleModels.Model): Long {
        if (loadedId == model.id && handle != 0L) return handle
        unload()
        if (!ImageGenNative.available) throw IOException("the MNN runtime is not here")
        val noBackup = context.noBackupFilesDir
        if (!UpscaleModels.isDownloaded(noBackup, model)) throw IOException("${model.id} is not downloaded")
        val file = UpscaleModels.file(noBackup, model)
        val h = ImageGenNative.nativeLoad(
            file.path,
            arrayOf(UpscaleModels.INPUT),
            arrayOf(UpscaleModels.OUTPUT),
            ImageGenEngine.threads(),
        )
        if (h == 0L) throw IOException("${file.name} would not load")
        handle = h
        loadedId = model.id
        return h
    }

    private fun unload() {
        if (handle != 0L) ImageGenNative.nativeFree(handle)
        handle = 0L
        loadedId = null
    }

    override fun close() = unload()

    private companion object {
        const val TAG = "UpscaleEngine"
        const val NPU_FAILED_FILE = "upscale-npu-failed.txt"
        const val NPU_ATTEMPT_FILE = "upscale-npu-attempt.txt"
        const val RGB_MASK = 0x00FFFFFF
        const val PNG_QUALITY = 100
        const val JPEG_QUALITY = 95
    }
}
