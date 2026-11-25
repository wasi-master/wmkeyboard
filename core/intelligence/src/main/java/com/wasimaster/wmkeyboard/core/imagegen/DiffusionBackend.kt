package com.wasimaster.wmkeyboard.core.imagegen

import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * The three networks of a Stable Diffusion model on one runtime. Latents are
 * always NCHW floats here; a backend whose graphs want another layout or
 * type converts at its edge.
 */
internal interface DiffusionBackend : Closeable {
    /** [ids]: [ClipTokenizer.MAX_TOKENS] token ids. The hidden states, 77 × text width. */
    fun encode(ids: IntArray): FloatArray

    /**
     * The UNet's prediction for [latent] (1 × 4 × [h] × [w]) at timestep [t]
     * under [text]; [guidance] is the LCM guidance embedding, or null.
     */
    fun unet(latent: FloatArray, h: Int, w: Int, t: Int, text: FloatArray, guidance: FloatArray?): FloatArray

    /** RGB in 0..1, 3 × [h]·8 × [w]·8, from the UNet's latent space. */
    fun decode(latent: FloatArray, h: Int, w: Int): FloatArray

    /** Sizes this backend can draw; the NPU's graphs are fixed at one. */
    val sizes: List<Int>
}

/**
 * A model bundle on MNN, the CPU path every phone has, or its GPU backends
 * when [gpuCache] is given: the file the GPU's compiled kernels are kept in.
 */
internal class MnnBackend(dir: File, private val spec: ModelSpec, threads: Int, gpuCache: File? = null) : DiffusionBackend {

    private val text: Long
    private val net: Long
    private val vae: Long

    init {
        if (!ImageGenNative.available) throw IOException("the MNN runtime is not here")
        // The text encoder runs once per prompt: the CPU, so the GPU's
        // compile time goes to the two networks that run many times.
        text = load(dir, "text_encoder.mnn", arrayOf("ids"), arrayOf("h"), threads, null)
        val unetInputs = if (spec.lcmDim > 0) arrayOf("x", "t", "e", "w") else arrayOf("x", "t", "e")
        net = try {
            load(dir, "unet.mnn", unetInputs, arrayOf("y"), threads, gpuCache?.let { File(it.path + ".unet") })
        } catch (e: IOException) {
            ImageGenNative.nativeFree(text)
            throw e
        }
        vae = try {
            load(dir, "vae_decoder.mnn", arrayOf("z"), arrayOf("img"), threads, gpuCache?.let { File(it.path + ".vae") })
        } catch (e: IOException) {
            ImageGenNative.nativeFree(text)
            ImageGenNative.nativeFree(net)
            throw e
        }
    }

    override val sizes: List<Int> get() = spec.sizes

    /** What the UNet runs on: [ImageGenNative.BACKEND_CPU], `_OPENCL` or `_VULKAN`. */
    val backend: Int get() = ImageGenNative.nativeBackend(net)

    override fun encode(ids: IntArray): FloatArray =
        run(text, arrayOf(ids), arrayOf(intArrayOf(1, ClipTokenizer.MAX_TOKENS)))

    override fun unet(latent: FloatArray, h: Int, w: Int, t: Int, text: FloatArray, guidance: FloatArray?): FloatArray {
        val inputs = mutableListOf<Any>(latent, floatArrayOf(t.toFloat()), text)
        val shapes = mutableListOf(intArrayOf(1, 4, h, w), intArrayOf(1), intArrayOf(1, ClipTokenizer.MAX_TOKENS, spec.textDim))
        if (spec.lcmDim > 0) {
            inputs.add(guidance ?: FloatArray(spec.lcmDim))
            shapes.add(intArrayOf(1, spec.lcmDim))
        }
        return run(net, inputs.toTypedArray(), shapes.toTypedArray())
    }

    override fun decode(latent: FloatArray, h: Int, w: Int): FloatArray =
        run(vae, arrayOf(latent), arrayOf(intArrayOf(1, 4, h, w)))

    private fun run(handle: Long, inputs: Array<Any>, shapes: Array<IntArray>): FloatArray =
        ImageGenNative.nativeRun(handle, inputs, shapes, null) ?: throw IOException("MNN refused the inputs")

    override fun close() {
        ImageGenNative.nativeFree(text)
        ImageGenNative.nativeFree(net)
        ImageGenNative.nativeFree(vae)
    }

    companion object {
        /** A bundle file by its role: catalog files carry a prefix (`sdxs-ds-unet.mnn`), imported ones need not. */
        fun find(dir: File, suffix: String): File? =
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(suffix) }?.minByOrNull { it.name.length }

        private fun load(
            dir: File, suffix: String, inputs: Array<String>, outputs: Array<String>, threads: Int, gpuCache: File?,
        ): Long {
            val file = find(dir, suffix) ?: throw IOException("no $suffix in ${dir.name}")
            val handle = if (gpuCache == null) {
                ImageGenNative.nativeLoad(file.absolutePath, inputs, outputs, threads)
            } else {
                ImageGenNative.nativeLoadOn(
                    file.absolutePath, inputs, outputs, threads, ImageGenNative.COMPUTE_GPU, gpuCache.absolutePath,
                )
            }
            if (handle == 0L) throw IOException("MNN could not load ${file.name}")
            return handle
        }
    }
}

/**
 * Qualcomm AI Hub's Stable Diffusion 1.5 on the Hexagon NPU: three context
 * binaries with 16-bit quantised inputs and outputs, latents NHWC, the
 * decoder ending in RGB 0..1. [QnnGraph] quantises each tensor from the
 * scale and offset its binary declares.
 */
internal class QnnBackend(dir: File, runtimeDir: File) : DiffusionBackend {

    private val text: QnnGraph
    private val net: QnnGraph
    private val vae: QnnGraph

    init {
        QnnRuntime.acquire(runtimeDir)
        val opened = ArrayList<QnnGraph>()
        try {
            text = QnnGraph.open(File(dir, ImageGenModels.NPU_TEXT)).also(opened::add)
            net = QnnGraph.open(File(dir, ImageGenModels.NPU_UNET)).also(opened::add)
            vae = QnnGraph.open(File(dir, ImageGenModels.NPU_VAE)).also(opened::add)
        } catch (e: IOException) {
            opened.forEach { it.close() }
            QnnRuntime.release()
            throw e
        }
    }

    override val sizes: List<Int> = listOf(SIZE)

    override fun encode(ids: IntArray): FloatArray =
        text.run(FloatArray(ids.size) { ids[it].toFloat() }).first()

    override fun unet(latent: FloatArray, h: Int, w: Int, t: Int, text: FloatArray, guidance: FloatArray?): FloatArray {
        val nhwc = toNhwc(latent, h, w)
        val inputs = net.inputs.map { tensor ->
            when {
                tensor.name.contains("time") -> floatArrayOf(t.toFloat())
                tensor.name.contains("text") || tensor.dims.lastOrNull() == TEXT_WIDTH -> text
                else -> nhwc
            }
        }
        return toNchw(net.run(*inputs.toTypedArray()).first(), h, w)
    }

    override fun decode(latent: FloatArray, h: Int, w: Int): FloatArray {
        val out = vae.run(toNhwc(latent, h, w)).first()
        // NHWC RGB to CHW.
        val pixels = out.size / 3
        return FloatArray(out.size) { i -> out[(i % pixels) * 3 + i / pixels] }
    }

    override fun close() {
        text.close()
        net.close()
        vae.close()
        QnnRuntime.release()
    }

    companion object {
        const val SIZE = 512
        private const val TEXT_WIDTH = 768

        private fun toNhwc(x: FloatArray, h: Int, w: Int): FloatArray {
            val c = x.size / (h * w)
            return FloatArray(x.size) { i -> x[(i % c) * h * w + i / c] }
        }

        private fun toNchw(x: FloatArray, h: Int, w: Int): FloatArray {
            val c = x.size / (h * w)
            return FloatArray(x.size) { i -> x[(i % (h * w)) * c + i / (h * w)] }
        }
    }
}

/** IEEE half precision without android.util.Half (API 26). */
internal object Half {
    private const val SUBNORMAL = 5.9604645e-8f

    fun toFloat(h: Short): Float {
        val bits = h.toInt() and 0xffff
        val sign = bits and 0x8000 shl 16
        val exp = bits shr 10 and 0x1f
        val mant = bits and 0x3ff
        // Zero and subnormals: mant × 2⁻²⁴, which a float holds exactly.
        if (exp == 0) return (if (sign != 0) -mant else mant) * SUBNORMAL
        val out = when (exp) {
            0x1f -> sign or 0x7f800000 or (mant shl 13)
            else -> sign or (exp + 112 shl 23) or (mant shl 13)
        }
        return java.lang.Float.intBitsToFloat(out)
    }

    fun fromFloat(f: Float): Short {
        val bits = java.lang.Float.floatToIntBits(f)
        val sign = bits ushr 16 and 0x8000
        val value = (bits and 0x7fffffff) + 0x1000
        return when {
            value >= 0x47800000 -> (sign or 0x7c00).toShort()
            value >= 0x38800000 -> (sign or (value - 0x38000000 ushr 13)).toShort()
            value < 0x33000000 -> sign.toShort()
            else -> {
                val v = bits and 0x7fffffff ushr 23
                (sign or ((bits and 0x7fffff or 0x800000) + (0x800000 ushr v - 102) ushr 126 - v)).toShort()
            }
        }
    }
}
