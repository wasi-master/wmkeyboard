package com.wasimaster.wmkeyboard.core.imagegen

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.localllm.NpuRuntime
import com.wasimaster.wmkeyboard.core.localllm.NpuSupport
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** What the generator is asked to draw. Plain values, so it crosses processes in a Bundle. */
data class ImageGenRequest(
    val modelId: String,
    /** English: the screen translates first. */
    val prompt: String,
    val negative: String = "",
    val size: Int = 0,
    val steps: Int = 0,
    /** NaN for the model's own. */
    val guidance: Float = Float.NaN,
    val seed: Long = 0L,
    val variants: Int = 1,
    val safetyFilter: Boolean = true,
    /** Draw on the phone's GPU (MNN's OpenCL or Vulkan backend) rather than its CPU; MNN models only. */
    val gpu: Boolean = false,
)

/** What the generator says while it works. */
sealed interface ImageGenEvent {
    /** The model is being loaded (the first run after the service started or idled out). */
    data object Loading : ImageGenEvent

    /** [variant]'s [step] of [steps] is done; [preview] a rough picture of it, when the model has steps to preview. */
    data class Progress(val variant: Int, val step: Int, val steps: Int, val preview: File?) : ImageGenEvent

    /** A finished picture, as a PNG in the app's cache. */
    data class Image(val variant: Int, val file: File, val seed: Long, val millis: Long) : ImageGenEvent

    /** A picture the safety filter held back, or the whole prompt when [variant] is [ImageGenEngine.PROMPT]. */
    data class Blocked(val variant: Int, val seed: Long) : ImageGenEvent

    data class Failed(val message: String) : ImageGenEvent

    /** An online service's queue holds the request: [position] ahead of it, about [seconds] to go (0 when it did not say). */
    data class Waiting(val position: Int, val seconds: Int) : ImageGenEvent

    data object Done : ImageGenEvent
}

/**
 * The generator itself, in [ImageGenService]'s process. Keeps one model
 * loaded between requests (the first forward after a load costs several
 * times a warm one), caches the encoded prompts, and draws each variant with
 * its own seed. Every network call is a separate native call, so [cancel]
 * stops it within one UNet run.
 */
class ImageGenEngine internal constructor(private val context: Context) : Closeable {

    private class Loaded(val key: String, val spec: ModelSpec, val backend: DiffusionBackend, val tokenizer: ClipTokenizer)

    private var loaded: Loaded? = null
    private var safety: NsfwFilter? = null
    private val textCache = object : LinkedHashMap<String, FloatArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > TEXT_CACHE
    }

    internal fun generate(request: ImageGenRequest, cancelled: AtomicBoolean, emit: (ImageGenEvent) -> Unit) {
        // The screen checks first, on the prompt as typed too; this is the
        // line nothing gets past, whoever is asking.
        if (PromptSafety.blocked(request.prompt) != null) {
            emit(ImageGenEvent.Blocked(PROMPT, request.seed))
            return
        }
        val model = load(request.modelId, request.gpu, emit)
        val spec = model.spec
        val size = request.size.takeIf { it in model.backend.sizes }
            ?: model.backend.sizes.minByOrNull { kotlin.math.abs(it - spec.size) }
            ?: spec.size
        val lat = size / LATENT_SCALE
        val steps = if (spec.adjustableSteps) request.steps.takeIf { it in 1..ModelSpec.MAX_STEPS } ?: spec.steps else spec.timesteps.size
        val guidance = request.guidance.takeIf { !it.isNaN() } ?: spec.guidance
        val cfg = spec.takesGuidance && guidance > 1f
        val cond = encode(model, request.prompt)
        val uncond = if (cfg) encode(model, request.negative) else null
        val lcmGuidance = if (spec.lcmDim > 0) LcmSampler.guidanceEmbedding(guidance, spec.lcmDim) else null
        val filter = if (request.safetyFilter) safetyFilter() else null
        val out = outputDir().apply { mkdirs() }
        val n = 4 * lat * lat
        for (variant in 0 until request.variants.coerceIn(1, MAX_VARIANTS)) {
            if (cancelled.get()) return
            val started = System.currentTimeMillis()
            val seed = request.seed + variant
            val noise = GaussianNoise(seed)
            val sampler = spec.sampler(steps)
            var x = sampler.start(noise.floats(n))
            val total = sampler.timesteps.size
            var lastPreview = 0L
            for (i in sampler.timesteps.indices) {
                if (cancelled.get()) return
                val t = sampler.timesteps[i]
                val input = sampler.modelInput(x, i)
                var eps = model.backend.unet(input, lat, lat, t, cond, lcmGuidance)
                if (uncond != null) {
                    if (cancelled.get()) return
                    val e0 = model.backend.unet(input, lat, lat, t, uncond, lcmGuidance)
                    eps = FloatArray(eps.size) { k -> e0[k] + guidance * (eps[k] - e0[k]) }
                }
                x = sampler.step(x, eps, i) { noise.floats(n) }
                var preview: File? = null
                val now = System.currentTimeMillis()
                if (i < total - 1 && total > 1 && now - lastPreview >= PREVIEW_EVERY_MS) {
                    sampler.denoised?.let { d ->
                        preview = File(out, "preview_$variant.png").also { save(toBitmap(model.backend.decode(d, lat, lat), size), it) }
                        lastPreview = System.currentTimeMillis()
                    }
                }
                emit(ImageGenEvent.Progress(variant, i + 1, total, preview))
            }
            if (cancelled.get()) return
            val bitmap = toBitmap(model.backend.decode(x, lat, lat), size)
            val unsafe = filter?.unsafe(bitmap) ?: 0f
            if (unsafe >= NsfwFilter.THRESHOLD) {
                bitmap.recycle()
                emit(ImageGenEvent.Blocked(variant, seed))
                continue
            }
            val file = File(out, "sticker_${System.currentTimeMillis()}_$variant.png")
            save(bitmap, file)
            bitmap.recycle()
            emit(ImageGenEvent.Image(variant, file, seed, System.currentTimeMillis() - started))
        }
    }

    private fun encode(model: Loaded, text: String): FloatArray {
        val key = "${model.key}\u0000$text"
        textCache[key]?.let { return it }
        val ids = model.tokenizer.prompt(text, model.spec.pad).ids
        return model.backend.encode(ids).also { textCache[key] = it }
    }

    private fun load(modelId: String, gpu: Boolean, emit: (ImageGenEvent) -> Unit): Loaded {
        val key = if (gpu) "$modelId\u0000gpu" else modelId
        loaded?.takeIf { it.key == key }?.let { return it }
        emit(ImageGenEvent.Loading)
        unload()
        val noBackup = context.noBackupFilesDir
        val catalog = ImageGenModels.Catalog.byId(modelId)
        val result = when {
            catalog == ImageGenModels.Catalog.SD15_NPU -> {
                val soc = NpuSupport.qnnSoc ?: throw IOException("this phone has no supported NPU")
                val build = catalog.npuBuild(soc) ?: throw IOException("no NPU build for $soc")
                val runtime = NpuRuntime.directory(context, soc) ?: throw IOException("the NPU runtime is not here yet")
                val dir = ImageGenModels.dir(noBackup, catalog)
                val tokenizer = ClipTokenizer.fromFile(File(dir, catalog.parts.first().fileName))
                Loaded(modelId, ImageGenModels.npuSpec(), QnnBackend(File(dir, build.soc), runtime), tokenizer)
            }
            catalog != null -> mnn(key, modelId, ImageGenModels.dir(noBackup, catalog), gpu)
            else -> {
                val dir = File(ImageGenModels.importedRoot(noBackup), modelId)
                if (File(dir, ImageGenModels.NPU_UNET).isFile) npu(modelId, dir) else mnn(key, modelId, dir, gpu)
            }
        }
        loaded = result
        return result
    }

    /** An imported Qualcomm build: its context binaries on this phone's NPU, with the tokenizer beside them. */
    private fun npu(id: String, dir: File): Loaded {
        val soc = NpuSupport.qnnSoc ?: throw IOException("this phone has no supported NPU")
        val runtime = NpuRuntime.directory(context, soc) ?: throw IOException("the NPU runtime is not here yet")
        val merges = MnnBackend.find(dir, "merges.txt") ?: throw IOException("no merges.txt in ${dir.name}")
        return Loaded(id, ImageGenModels.npuSpec(id), QnnBackend(dir, runtime), ClipTokenizer.fromFile(merges))
    }

    private fun mnn(key: String, id: String, dir: File, gpu: Boolean): Loaded {
        val json = MnnBackend.find(dir, "wmimage.json")?.readText() ?: throw IOException("no wmimage.json in ${dir.name}")
        val spec = ModelSpec.parse(id, json) ?: throw IOException("${dir.name} is not a model this version understands")
        val merges = MnnBackend.find(dir, "merges.txt") ?: throw IOException("no merges.txt in ${dir.name}")
        // The GPU's compiled kernels, one cache file per model, beside the
        // driver's own caches: rebuilt by the next run when the system clears it.
        val cache = if (gpu) File(context.codeCacheDir, "mnn-gpu").apply { mkdirs() }.let { File(it, "$id.cache") } else null
        val backend = MnnBackend(dir, spec, threads(), cache)
        DebugLog.d(TAG, "$id on MNN backend ${backend.backend}")
        return Loaded(key, spec, backend, ClipTokenizer.fromFile(merges))
    }

    private fun safetyFilter(): NsfwFilter? {
        safety?.let { return it }
        val file = ImageGenModels.safetyFile(context.noBackupFilesDir)
        if (!ImageGenModels.safetyDownloaded(context.noBackupFilesDir)) throw IOException("the picture filter is not downloaded")
        return NsfwFilter(file).also { safety = it }
    }

    internal fun unload() {
        loaded?.let { runCatching { it.backend.close() } }
        loaded = null
        textCache.clear()
    }

    override fun close() {
        unload()
        safety?.close()
        safety = null
    }

    private fun outputDir() = File(context.cacheDir, OUTPUT_DIR)

    companion object {
        private const val TAG = "ImageGenEngine"
        private const val LATENT_SCALE = 8
        private const val TEXT_CACHE = 16
        private const val PREVIEW_EVERY_MS = 700L
        const val MAX_VARIANTS = 4

        /** [ImageGenEvent.Blocked]'s variant when the prompt itself was refused. */
        const val PROMPT = -1

        /** Under the app's cache directory; the screen clears it when it closes. */
        const val OUTPUT_DIR = "imagegen"

        /**
         * MNN threads: the big cores. Phones with eight cores have four fast
         * ones at most; smaller counts are all-big or all-little.
         */
        fun threads(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            return when {
                cores >= 8 -> 4
                cores >= 4 -> cores / 2
                else -> cores
            }.coerceAtLeast(1)
        }

        /**
         * How many pictures one tap draws by default on this phone: four on a
         * flagship, two mid-range, one on a small phone, from its memory and
         * cores; the NPU draws two (each is 20 steps there).
         */
        fun defaultVariants(context: Context, runtime: ImageRuntime): Int {
            if (runtime == ImageRuntime.QNN) return 2
            val memory = ActivityManager.MemoryInfo().also {
                (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
            }.totalMem
            val gb = (memory / 1e9).roundToInt()
            val cores = Runtime.getRuntime().availableProcessors()
            return when {
                gb >= 10 && cores >= 8 -> 4
                gb >= 6 -> 2
                else -> 1
            }
        }

        internal fun toBitmap(chw: FloatArray, size: Int): Bitmap {
            val plane = size * size
            val pixels = IntArray(plane) { i ->
                val r = (chw[i] * 255f).roundToInt().coerceIn(0, 255)
                val g = (chw[plane + i] * 255f).roundToInt().coerceIn(0, 255)
                val b = (chw[2 * plane + i] * 255f).roundToInt().coerceIn(0, 255)
                (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
            return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        }

        private fun save(bitmap: Bitmap, file: File) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(file)) {
                tmp.delete()
                DebugLog.w(TAG, "could not write ${file.name}")
            }
        }
    }
}
