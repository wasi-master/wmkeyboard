package com.wasimaster.wmkeyboard.core.localllm

import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary
import java.io.File

/**
 * JNI surface of llama.cpp (libwmllama.so, built from native/llamacpp-jni),
 * the second on-device runtime beside LiteRT-LM: it runs GGUF files, any of
 * them, where LiteRT-LM runs only `.litertlm` and `.task` bundles.
 *
 * Optional at runtime: GitHub builds fetch the library the first time a GGUF
 * model is used, Play builds carry it in the on-demand `:feature:llama` split,
 * and a build or install without it reads [available] as false.
 *
 * Six builds of the library, one loaded per process: the baseline, the
 * ARMv8.2 one, the ARMv8.2 one with the int8 matrix instructions
 * ([LIBRARY_I8MM]), two GPU builds on ARMv8.2: llama.cpp's OpenCL backend
 * for Adreno GPUs ([LIBRARY_OPENCL]) and its Vulkan backend for the others
 * ([LIBRARY_VULKAN], Mali, Xclipse, PowerVR), and ggml's Hexagon backend for
 * the Snapdragon NPU ([LIBRARY_HEXAGON], [LlamaHexagon]), whose device the
 * library offers as a GPU. They bind the same JNI names, so
 * the first one loaded is the one this process keeps; [wantGpuLibrary], set
 * before the first load, picks a GPU build, which runs the CPU's share just
 * as fast as the ARMv8.2 one. Every arm64 build carries Arm's KleidiAI
 * kernels, which pick themselves at run time.
 *
 * Handles are native pointers. A model handle may be shared by sessions; a
 * session handle must not be used from two threads at once, except
 * [nativeAbort]. [LlamaCppRuntime] keeps to both. Text crosses as UTF-8 bytes;
 * see native/llamacpp-jni/README.md for the whole contract.
 */
internal object LlamaNative {

    const val LIBRARY = "wmllama"
    const val LIBRARY_V82 = "wmllama_v82"
    const val LIBRARY_I8MM = "wmllama_i8mm"
    const val LIBRARY_OPENCL = "wmllama_opencl"
    const val LIBRARY_VULKAN = "wmllama_vulkan"
    const val LIBRARY_HEXAGON = "wmllama_hexagon"

    /** [nativeStopReason]: the model ended its answer. */
    const val STOP_END = 0
    const val STOP_CONTEXT_FULL = 1
    const val STOP_ABORTED = 2
    const val STOP_ERROR = 3

    /** Negative results of [nativePromptChat] and [nativePromptRaw]. */
    const val PROMPT_TOO_LONG = -1
    const val PROMPT_ABORTED = -2
    const val PROMPT_DECODE_FAILED = -3
    const val PROMPT_TEMPLATE_FAILED = -4

    /** [nativePromptChatImages]: the projector could not read a picture. */
    const val PROMPT_IMAGE_FAILED = -5

    /** Layers to offload for "all of them": more than any model has. */
    const val ALL_LAYERS = 999

    private val library = GatedLibrary({ FeatureModules.llama }, ::load)

    /** Loads the library the first time it is read with the module present. */
    val available: Boolean get() = library.available

    /** Whether this build has the library, counting a module not fetched yet. Loads nothing then. */
    val shipped: Boolean get() = library.shipped

    /**
     * The GPU build to load rather than a CPU one when the first load comes
     * ([LIBRARY_OPENCL], [LIBRARY_VULKAN] or [LIBRARY_HEXAGON]), or null for a
     * CPU build. Set by the runtime from Compute and the phone's GPU and NPU; read once.
     */
    @Volatile
    var wantGpuLibrary: String? = null

    /** A GPU build is the one loaded. */
    @Volatile
    var gpuLibrary = false
        private set

    /**
     * [wantGpuLibrary] was set, and that build did not load: no ARMv8.2
     * cores, or no vendor libOpenCL.so or libvulkan.so this app may open.
     */
    @Volatile
    var gpuLibraryRefused = false
        private set

    /** Whether this phone's cores could run a GPU build at all; read once. */
    val gpuLibraryPossible: Boolean by lazy { hasCpuFeatures(v82Features) }

    /** Whether every core has the int8 matrix instructions [LIBRARY_I8MM] needs too; read once. */
    private val i8mmPossible: Boolean by lazy { hasCpuFeatures(v82Features + "i8mm") }

    /** Some build is loaded, so [wantGpuLibrary] no longer changes anything. */
    val loaded: Boolean get() = loadedOnce

    @Volatile
    private var loadedOnce = false

    /**
     * The newest CPU build every core can run: with i8mm, else ARMv8.2 (dot
     * product, fp16), else the baseline one. Same rule as WhisperCpp: a
     * big.LITTLE part with one older core would die on an illegal
     * instruction the moment a thread landed there.
     */
    private fun load(): Boolean {
        val v82 = gpuLibraryPossible
        // A GPU build links to the vendor's libOpenCL.so or the system's
        // libvulkan.so by name: a phone whose app namespace does not offer it
        // fails this load cleanly.
        val gpu = wantGpuLibrary
        if (gpu != null) {
            if (v82 && runCatching { FeatureModules.loadLibrary(gpu) }.isSuccess) {
                gpuLibrary = true
                loadedOnce = true
                return true
            }
            gpuLibraryRefused = true
        }
        val ok = (i8mmPossible && runCatching { FeatureModules.loadLibrary(LIBRARY_I8MM) }.isSuccess) ||
            (v82 && runCatching { FeatureModules.loadLibrary(LIBRARY_V82) }.isSuccess) ||
            runCatching { FeatureModules.loadLibrary(LIBRARY) }.isSuccess
        loadedOnce = ok
        return ok
    }

    private val v82Features = listOf("asimddp", "asimdhp", "fphp")

    private fun hasCpuFeatures(wanted: List<String>): Boolean = runCatching {
        val features = File("/proc/cpuinfo").readLines()
            .filter { it.startsWith("Features") }
            .map { it.substringAfter(':').trim().split(' ').toSet() }
        features.isNotEmpty() && features.all { it.containsAll(wanted) }
    }.getOrDefault(false)

    /** llama.cpp's own line about the instruction sets it was built with. */
    external fun nativeSystemInfo(): String

    /**
     * The GPU devices the loaded build found, one `name\tdescription` line
     * each, UTF-8; empty for a CPU build. Starts the GPU backend the first time.
     */
    external fun nativeGpuDevices(): ByteArray

    /**
     * A model over [path] (mmapped when [useMmap]), or 0 when it will not
     * load. [gpuLayers] 0 keeps it and its contexts off every GPU; more puts
     * that many layers there ([ALL_LAYERS] for all).
     */
    external fun nativeModelOpen(path: String, useMmap: Boolean, gpuLayers: Int): Long

    external fun nativeModelClose(model: Long)

    /**
     * `[params, bytes, trainedContext, layers, heads, kvHeads, embedding,
     * slidingWindow, hasTemplate, vocabulary]`; see [LlamaModelInfo].
     */
    external fun nativeModelInfo(model: Long): LongArray

    /** llama.cpp's one-line description of the model, UTF-8. */
    external fun nativeModelDescription(model: Long): ByteArray

    /** A context of [contextTokens] over [model], or 0 when it cannot be allocated. */
    external fun nativeSessionOpen(model: Long, contextTokens: Int, threads: Int, batch: Int): Long

    external fun nativeSessionClose(session: Long)

    /** Temperature 0 is greedy; top-k 0, top-p 1 and min-p 0 are off; a negative seed is random. */
    external fun nativeSetSampler(session: Long, temperature: Float, topK: Int, topP: Float, minP: Float, seed: Int)

    /**
     * Renders [roles] and [contents] (UTF-8) with the model's chat template,
     * ending on the assistant's turn, and loads it, reusing whatever prefix
     * the session already holds. The prompt's length in tokens, or one of the
     * `PROMPT_` codes.
     */
    external fun nativePromptChat(session: Long, roles: Array<String>, contents: Array<ByteArray>, enableThinking: Boolean): Int

    /** [nativePromptChat] for raw text with no template. */
    external fun nativePromptRaw(session: Long, text: ByteArray, addSpecial: Boolean): Int

    /** How many tokens the last prompt kept from the session's cache. */
    external fun nativeLastReused(session: Long): Int

    /**
     * The next piece of the answer as UTF-8, possibly empty while a character
     * is incomplete; null once it is over, for the reason [nativeStopReason]
     * gives.
     */
    external fun nativeNext(session: Long): ByteArray?

    external fun nativeStopReason(session: Long): Int

    /** Stops a prompt load or an answer at its next step. Any thread. */
    external fun nativeAbort(session: Long)

    external fun nativeContextSize(session: Long): Int

    external fun nativeTokensUsed(session: Long): Int

    external fun nativeTokenize(model: Long, text: ByteArray, addSpecial: Boolean): IntArray

    external fun nativeTokenPiece(model: Long, token: Int): ByteArray

    /**
     * Each of [tokens]' log-probabilities given those before it, in nats;
     * element 0 is NaN. Replaces what the session's cache held. Null on
     * failure or when aborted.
     */
    external fun nativeScore(session: Long, tokens: IntArray): FloatArray?

    /**
     * The vision projector in [path] (a GGUF `mmproj` file) for [model], or 0
     * when it does not load or reads no pictures. On the GPU when [useGpu]
     * and the build has one.
     */
    external fun nativeVisionOpen(model: Long, path: String, useGpu: Boolean, threads: Int): Long

    external fun nativeVisionClose(vision: Long)

    /**
     * [nativePromptChat] with pictures at the start of the last user message:
     * [rgb] holds each one's pixels, three bytes each, [widths] by [heights].
     * The prompt's length in tokens, or one of the `PROMPT_` codes.
     */
    external fun nativePromptChatImages(
        session: Long,
        vision: Long,
        roles: Array<String>,
        contents: Array<ByteArray>,
        rgb: Array<ByteArray>,
        widths: IntArray,
        heights: IntArray,
        enableThinking: Boolean,
    ): Int
}

/** What [LlamaNative.nativeModelInfo] reports, named. */
internal data class LlamaModelInfo(
    val params: Long,
    val bytes: Long,
    val trainedContext: Int,
    val layers: Int,
    val heads: Int,
    val kvHeads: Int,
    val embedding: Int,
    val slidingWindow: Int,
    val hasTemplate: Boolean,
    val vocabulary: Int,
) {
    /**
     * Bytes of KV cache one token of context costs: keys and values, f16, for
     * every layer. An upper bound for sliding-window models, whose windowed
     * layers keep fewer.
     */
    val kvBytesPerToken: Long
        get() {
            if (heads <= 0) return 0
            val headSize = embedding / heads
            return layers.toLong() * 2 * kvHeads * headSize * 2
        }

    companion object {
        fun of(values: LongArray) = LlamaModelInfo(
            params = values[0],
            bytes = values[1],
            trainedContext = values[2].toInt(),
            layers = values[3].toInt(),
            heads = values[4].toInt(),
            kvHeads = values[5].toInt(),
            embedding = values[6].toInt(),
            slidingWindow = values[7].toInt(),
            hasTemplate = values[8] != 0L,
            vocabulary = values[9].toInt(),
        )
    }
}
