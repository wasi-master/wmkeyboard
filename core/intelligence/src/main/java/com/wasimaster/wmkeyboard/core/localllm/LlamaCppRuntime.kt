package com.wasimaster.wmkeyboard.core.localllm

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.modules.NativeConsent
import com.wasimaster.wmkeyboard.core.settings.LocalLlmBackend
import com.wasimaster.wmkeyboard.intelligence.R
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * The entry point `LocalLlmEngine` routes GGUF files through: llama.cpp, the
 * second on-device runtime. LiteRT-LM keeps every `.litertlm` and `.task`
 * file, and stays the default: a model runs here only when it is a GGUF,
 * which the user chose by picking or importing one.
 */
object LlamaCpp {

    /** Whether [modelFile] is a GGUF, so this runtime's to run. */
    fun handles(modelFile: File): Boolean = GgufFile.runsOnLlamaCpp(modelFile)

    /** Whether this build has llama.cpp, counting a module not fetched yet. Loads nothing then. */
    val shipped: Boolean get() = LlamaNative.shipped

    /** The library's delivery state, for a settings banner. */
    val moduleState get() = FeatureModules.llama.state

    fun requestModule() = FeatureModules.llama.requestInstall()

    /** Whether this phone can use the OpenCL build (gate `llamagpu`) at all. */
    val gpuLibraryUsable: Boolean get() = LlamaGpu.usable

    /**
     * Fetches the OpenCL build (gate `llamagpu`) ahead of the first GGUF
     * answer, when Compute is set to GPU on a phone that can use it; does
     * nothing anywhere else. Its own download, so that only Adreno phones
     * with GPU picked pay for it.
     */
    fun prepareGpu() {
        if (LlamaGpu.usable && !FeatureModules.llamaGpu.installed) FeatureModules.llamaGpu.requestInstall()
    }

    /**
     * The vision projector that goes with [modelFile]: a catalog model's own
     * (downloaded beside it), else a projector beside an imported file
     * ([GgufFile.projectorFor]). Null when the model cannot read pictures.
     */
    fun projectorFor(modelFile: File): File? {
        val dir = modelFile.parentFile ?: return null
        LocalLlmCatalog.models.firstOrNull { it.id == dir.name && it.fileName == modelFile.name }?.let { model ->
            return model.projector?.let { File(dir, it.fileName) }?.takeIf { it.isFile }
        }
        return GgufFile.projectorFor(modelFile)
    }

    private val instance by lazy { LlamaCppRuntime() }

    @Volatile
    private var started = false

    /**
     * The runtime, or why it cannot run yet as a user-presentable
     * [IOException]: the library is still on its way (a Play split, or a
     * GitHub build's download), it needs the download opt-in first, or this
     * build has none. [backend] decides which build of the library the first
     * call loads: Compute = GPU (or NPU, which a GGUF has no build for) on an
     * Adreno phone loads the OpenCL one.
     */
    fun runtime(context: Context, backend: LocalLlmBackend = LocalLlmBackend.CPU): LlmRuntime {
        val gate = FeatureModules.llama
        if (!gate.installed) {
            val state = gate.state.value
            if (state is ModuleState.Missing && state.consent != null) {
                val message = if (state.consent == NativeConsent.IMPORT) {
                    R.string.core_intel_llm_llama_import_needed
                } else {
                    R.string.core_intel_llm_llama_consent_needed
                }
                throw IOException(context.getString(message))
            }
            gate.requestInstall()
            throw IOException(context.getString(R.string.core_intel_llm_module_downloading))
        }
        if (!LlamaNative.loaded) LlamaNative.wantGpuLibrary = instance.gpuLibraryWanted(context, backend)
        if (!LlamaNative.available) {
            throw IOException(context.getString(R.string.core_intel_llm_llama_missing))
        }
        started = true
        return instance
    }

    /** Frees the loaded model, if any. Non-blocking, like LiteRT-LM's. */
    fun release() {
        if (started) instance.release()
    }

    /** Ends the running answer early: the text so far is what the caller gets back. */
    fun stop() {
        if (started) instance.stop()
    }

    /** Contract of [LlmRuntime.gpuMemoryMayBeStranded], for llama.cpp's GPU. */
    fun gpuMemoryMayBeStranded(): Boolean = started && instance.gpuMemoryMayBeStranded()

    /** Contract of [LlmRuntime.strandedGpuMemoryKb], for llama.cpp's GPU. */
    fun strandedGpuMemoryKb(): Long = if (started) instance.strandedGpuMemoryKb() else 0L

    /**
     * Compute asks for the GPU, but this process loaded a CPU build of the
     * library before it did, and only a new process can load the GPU one.
     * The keyboard then recycles its process once it is out of sight, the way
     * it does for stranded GPU memory; until then GGUF models run on the CPU.
     */
    val waitsForGpuRestart: Boolean get() = started && instance.waitsForGpuRestart

    /**
     * The scoring session for prediction experiments: token log-probabilities
     * of a text under a GGUF model, nothing sampled. Internal on purpose; no
     * feature uses it yet. Blocking, IO dispatcher only, and it shares the
     * runtime's lock and memory checks with generation.
     */
    internal fun score(context: Context, modelFile: File, text: String, contextTokens: Int = 1024): List<LlamaTokenScore> =
        (runtime(context) as LlamaCppRuntime).score(context, modelFile, text, contextTokens)
}

/** The phone's GPU, as far as llama.cpp's backends care. */
internal object LlamaGpu {

    /**
     * An Adreno GPU, the one llama.cpp's OpenCL backend is written for: the
     * kgsl device node Qualcomm's driver makes, or the chip maker Android
     * reports. Other GPUs (Mali, PowerVR, Xclipse) have no llama.cpp backend
     * here: its Vulkan one ran slower than the CPU on them (see
     * native/llamacpp-jni/README.md).
     */
    val adreno: Boolean by lazy {
        File("/dev/kgsl-3d0").exists() ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.SOC_MANUFACTURER.equals("QTI", ignoreCase = true)) ||
            Build.HARDWARE.equals("qcom", ignoreCase = true)
    }

    /** An Adreno GPU beside cores that run the OpenCL build (ARMv8.2): the phones it is for. */
    val usable: Boolean get() = adreno && LlamaNative.gpuLibraryPossible

    @Volatile
    private var devices: List<String>? = null

    /**
     * The GPU devices the loaded library found, by description ("QUALCOMM
     * Adreno(TM) 740"); empty for a CPU build or a driver that offered none.
     * The first call starts the backend. Lock of the runtime held.
     */
    fun devices(): List<String> = devices ?: String(LlamaNative.nativeGpuDevices(), Charsets.UTF_8)
        .lines()
        .filter { it.isNotBlank() }
        .map { it.substringAfter('\t') }
        .also { devices = it }
}

/**
 * llama.cpp behind [LlmRuntime]. One model and one context are kept loaded
 * between requests, keyed by the file, the window size and the device; a
 * request for another tears them down first. Chats and one-off requests share
 * the context: a request whose prompt begins the way the cache does decodes
 * only the rest, so turn two of a chat is warm, and switching between a chat
 * and a keyboard action costs one prompt's prefill.
 *
 * Unlike the LiteRT-LM runtime, an answer here can be stopped part way
 * ([stop]): the caller gets the text so far, and the cache keeps exactly it.
 *
 * Compute = GPU runs the model on an Adreno GPU through llama.cpp's OpenCL
 * backend, when the OpenCL build of the library is the one loaded and the
 * driver offers the GPU; everything else runs on the CPU, as does a model
 * that failed on the GPU before (remembered on disk, like LiteRT-LM's
 * verdicts). A GGUF has no NPU build, so NPU means GPU here.
 *
 * Pictures go to a vision model through its projector (an `mmproj` GGUF
 * beside the model, see [LlamaCpp.projectorFor]), loaded with the model the
 * first time a picture comes. A model without one says so with
 * [VisionUnsupportedException], which callers fall back from.
 */
internal class LlamaCppRuntime : LlmRuntime {

    private val lock = ReentrantLock()
    private var model: LlamaModel? = null
    private var session: LlamaSession? = null
    private var vision: LlamaVision? = null
    private var loadedKey: String? = null

    /** The session answering right now, for [stop]. */
    @Volatile
    private var running: LlamaSession? = null

    private val idleTimer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "llama-idle").apply { isDaemon = true }
    }
    private var idleRelease: ScheduledFuture<*>? = null

    /** Models that failed on this phone's GPU, by [AcceleratorVerdicts.key]: straight to the CPU. */
    private val gpuVerdicts = AcceleratorVerdicts(GPU_FAILED_FILE, GPU_ATTEMPT_FILE)

    /**
     * The OpenCL build itself, keyed [GPU_LIBRARY_KEY]: a driver that takes
     * the process down while the backend starts (before any model) leaves
     * its attempt behind, and the next process loads a CPU build instead.
     */
    private val libraryVerdicts = AcceleratorVerdicts(GPU_LIBRARY_FAILED_FILE, GPU_LIBRARY_ATTEMPT_FILE)

    /** The [AcceleratorVerdicts.key] of the model loaded on the GPU, for blaming it. */
    private var loadedGpuKey: String? = null

    /** Process graphics memory in KB before the first GPU model, or -1 before that. */
    @Volatile
    private var gpuBaselineKb = -1L

    /** A GPU model has been torn down, or failed to load, in this process. */
    @Volatile
    private var gpuReleased = false

    /** A GPU model is the loaded one right now. */
    @Volatile
    private var gpuLoaded = false

    /** See [LlamaCpp.waitsForGpuRestart]. */
    @Volatile
    var waitsForGpuRestart = false
        private set

    /** Model paths whose projector would not load: pictures go to the caller's fallback at once. */
    private val visionRefused = mutableSetOf<String>()

    /**
     * Whether the first load should take the OpenCL build: Compute is not
     * CPU, the phone has an Adreno GPU, and the build has not taken a process
     * down here since the last update.
     */
    fun gpuLibraryWanted(context: Context, backend: LocalLlmBackend): Boolean {
        if (backend == LocalLlmBackend.CPU || !LlamaGpu.usable) return false
        // Still to come: this process runs on the CPU, and the next one
        // loads the GPU build once it is here.
        if (!FeatureModules.llamaGpu.installed) {
            FeatureModules.llamaGpu.requestInstall()
            return false
        }
        lock.lock()
        try {
            libraryVerdicts.load(context)
            return libraryKey(context) !in libraryVerdicts
        } finally {
            lock.unlock()
        }
    }

    override fun generate(
        context: Context,
        modelFile: File,
        backend: LocalLlmBackend,
        contextTokens: Int,
        system: String,
        user: String,
        onPartial: ((String) -> Unit)?,
    ): String {
        val messages = buildList {
            if (system.isNotBlank()) add(LlamaMessage(LlamaMessage.SYSTEM, system))
            add(LlamaMessage(LlamaMessage.USER, user))
        }
        return withSession(context, modelFile, backend, contextTokens, vision = false) { active ->
            answer(context, active, messages, emptyList(), onPartial).text
        }
    }

    override fun generateWithImages(
        context: Context,
        modelFile: File,
        backend: LocalLlmBackend,
        contextTokens: Int,
        system: String,
        user: String,
        images: List<ByteArray>,
        onPartial: ((String) -> Unit)?,
    ): String {
        if (images.isEmpty()) return generate(context, modelFile, backend, contextTokens, system, user, onPartial)
        val pictures = picturesFor(context, modelFile, images)
        val messages = buildList {
            if (system.isNotBlank()) add(LlamaMessage(LlamaMessage.SYSTEM, system))
            add(LlamaMessage(LlamaMessage.USER, user))
        }
        return withSession(context, modelFile, backend, contextTokens, vision = true) { active ->
            answer(context, active, messages, pictures, onPartial).text
        }
    }

    override fun chatSession(
        context: Context,
        modelFile: File,
        backend: LocalLlmBackend,
        contextTokens: Int,
        system: String,
    ): LlmRuntime.Chat = Chat(context.applicationContext, modelFile, backend, contextTokens, system)

    override fun release() {
        if (!lock.tryLock()) return
        try {
            releaseLocked()
        } finally {
            lock.unlock()
        }
    }

    override fun stop() {
        running?.abort()
    }

    override fun gpuMemoryMayBeStranded(): Boolean = gpuReleased && !gpuLoaded

    /**
     * Graphics memory this process holds above what it held before its first
     * GPU model, while none is loaded: what a driver kept after the model was
     * freed, which only the process ending gives back.
     */
    override fun strandedGpuMemoryKb(): Long {
        if (!gpuMemoryMayBeStranded() || gpuBaselineKb < 0) return 0L
        return (AcceleratorVerdicts.graphicsKb() - gpuBaselineKb).coerceAtLeast(0L)
    }

    fun score(context: Context, modelFile: File, text: String, contextTokens: Int): List<LlamaTokenScore> =
        withSession(context, modelFile, LocalLlmBackend.CPU, contextTokens, vision = false) { active ->
            running = active
            try {
                active.score(text) ?: throw IOException(context.getString(R.string.core_intel_llm_engine_error_memory))
            } finally {
                running = null
            }
        }

    /**
     * [images] decoded for the projector, or [VisionUnsupportedException]
     * when the model has no projector, its projector failed before, or no
     * picture decodes: every case where the caller's other reader should
     * take over.
     */
    private fun picturesFor(context: Context, modelFile: File, images: List<ByteArray>): List<LlamaImage> {
        val refused = VisionUnsupportedException(context.getString(R.string.core_intel_llm_engine_error_vision))
        val projector = LlamaCpp.projectorFor(modelFile) ?: throw refused
        if (projector.path in visionRefused) throw refused
        return images.take(MAX_IMAGES).mapNotNull { decodeImage(it) }.ifEmpty { throw refused }
    }

    /**
     * A chat whose messages are rendered with the model's template on every
     * turn. The KV cache already holds the earlier turns whenever the context
     * was not unloaded or used by something else in between, and llama.cpp
     * then decodes only the new message; otherwise the whole transcript is
     * decoded again, as the real conversation rather than a summary of it.
     * Pictures travel with the message they came with; later turns keep the
     * words about them, not the pictures.
     */
    private inner class Chat(
        private val context: Context,
        private val modelFile: File,
        private val backend: LocalLlmBackend,
        private val contextTokens: Int,
        private val system: String,
    ) : LlmRuntime.Chat {

        private val history = ArrayList<ChatReplay.Turn>()

        @Volatile
        private var closed = false

        override fun sendMessage(user: String, onPartial: ((String) -> Unit)?): String =
            sendMessage(user, emptyList(), onPartial)

        override fun sendMessage(user: String, images: List<ByteArray>, onPartial: ((String) -> Unit)?): String {
            check(!closed) { "Session is closed" }
            val pictures = if (images.isEmpty()) emptyList() else picturesFor(context, modelFile, images)
            return withSession(context, modelFile, backend, contextTokens, vision = pictures.isNotEmpty()) { active ->
                val answer = answerFitting(active, user, pictures, onPartial)
                history.add(ChatReplay.Turn(fromUser = true, text = user))
                history.add(ChatReplay.Turn(fromUser = false, text = answer))
                answer
            }
        }

        /**
         * Drops the oldest exchanges until the conversation fits the window
         * with room to answer; the system prompt and the new message always
         * stay.
         */
        private fun answerFitting(
            active: LlamaSession,
            user: String,
            pictures: List<LlamaImage>,
            onPartial: ((String) -> Unit)?,
        ): String {
            var skip = 0
            while (true) {
                val messages = buildList {
                    if (system.isNotBlank()) add(LlamaMessage(LlamaMessage.SYSTEM, system))
                    history.drop(skip).forEach { turn ->
                        add(LlamaMessage(if (turn.fromUser) LlamaMessage.USER else LlamaMessage.ASSISTANT, turn.text))
                    }
                    add(LlamaMessage(LlamaMessage.USER, user))
                }
                try {
                    return answer(context, active, messages, pictures, onPartial).text
                } catch (e: LlamaPromptTooLong) {
                    if (skip >= history.size) throw e
                    skip = (skip + 2).coerceAtMost(history.size)
                }
            }
        }

        override fun seed(transcript: List<ChatReplay.Turn>) {
            lock.lock()
            try {
                if (history.isEmpty()) history.addAll(transcript)
            } finally {
                lock.unlock()
            }
        }

        /**
         * An answer Stop cut short. [sendMessage] has already recorded what
         * the model wrote, which is exactly what the cache holds, so this only
         * corrects the record when the caller's text differs.
         */
        override fun recordStoppedTurn(user: String, fullAnswer: String) {
            lock.lock()
            try {
                if (closed) return
                val last = history.lastOrNull()
                val before = history.getOrNull(history.size - 2)
                if (last != null && !last.fromUser && before?.fromUser == true && before.text == user) {
                    history[history.size - 1] = ChatReplay.Turn(fromUser = false, text = fullAnswer)
                } else {
                    history.add(ChatReplay.Turn(fromUser = true, text = user))
                    history.add(ChatReplay.Turn(fromUser = false, text = fullAnswer))
                }
            } finally {
                lock.unlock()
            }
        }

        override fun close() {
            closed = true
        }
    }

    /** Thrown inside [answer] so a chat can drop old turns and retry. */
    private class LlamaPromptTooLong(message: String) : IOException(message)

    /**
     * Loads [messages] (with [pictures] on the last user message) and samples
     * the answer. The window left after the prompt is the answer's limit,
     * capped so a model that never ends its turn stops by itself. A failure
     * on the GPU is blamed on it: the model goes to the CPU from the next try
     * on. Lock held.
     */
    private fun answer(
        context: Context,
        active: LlamaSession,
        messages: List<LlamaMessage>,
        pictures: List<LlamaImage>,
        onPartial: ((String) -> Unit)?,
    ): LlamaAnswer {
        running = active
        try {
            val promptTokens = try {
                val loadedVision = vision
                if (pictures.isNotEmpty() && loadedVision != null) {
                    active.promptChatImages(loadedVision, messages, pictures)
                } else {
                    active.promptChat(messages)
                }
            } catch (e: LlamaPromptException) {
                when (e.code) {
                    LlamaNative.PROMPT_TOO_LONG ->
                        throw LlamaPromptTooLong(context.getString(R.string.core_intel_llm_engine_error_too_long))
                    // Stopped while reading the prompt: no answer yet.
                    LlamaNative.PROMPT_ABORTED -> return LlamaAnswer("", LlamaStop.ABORTED, 0)
                    LlamaNative.PROMPT_TEMPLATE_FAILED ->
                        throw IOException(context.getString(R.string.core_intel_llm_engine_error_template), e)
                    LlamaNative.PROMPT_IMAGE_FAILED -> {
                        if (active.model.onGpu) throw gpuFailure(context, e)
                        vision?.let { visionRefused += it.file.path }
                        throw VisionUnsupportedException(context.getString(R.string.core_intel_llm_engine_error_vision), e)
                    }
                    else -> {
                        if (active.model.onGpu) throw gpuFailure(context, e)
                        throw IOException(context.getString(R.string.core_intel_llm_engine_error_memory), e)
                    }
                }
            }
            // The kernels compiled and ran a whole prompt: what crashes a
            // driver has had its chance.
            gpuVerdicts.confirm()
            val room = active.contextSize - promptTokens
            val result = active.generate(minOf(room, MAX_ANSWER_TOKENS), onPartial)
            if (result.stop == LlamaStop.ERROR && result.text.isEmpty()) {
                if (active.model.onGpu) throw gpuFailure(context, null)
                throw IOException(context.getString(R.string.core_intel_llm_engine_error_memory))
            }
            return result
        } finally {
            running = null
        }
    }

    /**
     * The GPU took the model but failed it part way, usually for memory:
     * remembered, so the retry runs on the CPU without the user having to
     * work out that the GPU was the problem. Lock held.
     */
    private fun gpuFailure(context: Context, cause: Throwable?): IOException {
        val blamed = loadedGpuKey
        releaseLocked()
        if (blamed != null) gpuVerdicts.markFailed(context, blamed)
        return IOException(context.getString(R.string.core_intel_llm_engine_error_gpu), cause)
    }

    /**
     * Runs [block] on the session for [modelFile] at [contextTokens], loading
     * it first when another (or none) is loaded, then schedules the idle
     * unload. Failures to load tear everything down and come back as
     * user-presentable [IOException]s.
     */
    private fun <T> withSession(
        context: Context,
        modelFile: File,
        backend: LocalLlmBackend,
        contextTokens: Int,
        vision: Boolean,
        block: (LlamaSession) -> T,
    ): T {
        lock.lock()
        try {
            idleRelease?.cancel(false)
            val active = obtainSession(context, modelFile, backend, contextTokens, vision)
            try {
                return block(active)
            } catch (e: IOException) {
                throw e
            } catch (e: Throwable) {
                if (active.model.onGpu) throw gpuFailure(context, e)
                releaseLocked()
                throw IOException(context.getString(R.string.core_intel_llm_engine_error_memory), e)
            }
        } finally {
            idleRelease = idleTimer.schedule({ release() }, IDLE_RELEASE_MS, TimeUnit.MILLISECONDS)
            lock.unlock()
        }
    }

    /**
     * The loaded session when it matches, else a fresh one after the memory
     * check: on the GPU when Compute asks for it and nothing speaks against
     * it, on the CPU when the GPU will not take it. With [vision], the
     * model's projector too, which a loaded session gains without a reload.
     * Lock held.
     */
    private fun obtainSession(
        context: Context,
        modelFile: File,
        backend: LocalLlmBackend,
        contextTokens: Int,
        vision: Boolean,
    ): LlamaSession {
        if (!modelFile.isFile) throw IOException(context.getString(R.string.core_intel_llm_engine_error_load))
        gpuVerdicts.load(context)
        libraryVerdicts.load(context)
        // A GPU build that would not load here will not load in the next
        // process either: remembered, so nothing waits for a restart.
        if (LlamaNative.gpuLibraryRefused && libraryKey(context) !in libraryVerdicts) {
            libraryVerdicts.markFailed(context, libraryKey(context))
        }
        val wanted = if (contextTokens > 0) contextTokens else DEFAULT_CONTEXT
        val gpuKey = AcceleratorVerdicts.key(context, modelFile)
        val wantGpu = backend != LocalLlmBackend.CPU && gpuKey !in gpuVerdicts && gpuReady(context)
        waitsForGpuRestart = backend != LocalLlmBackend.CPU && LlamaGpu.usable && FeatureModules.llamaGpu.installed &&
            !LlamaNative.gpuLibrary && libraryKey(context) !in libraryVerdicts
        val key = "${modelFile.path}|${modelFile.length()}|${modelFile.lastModified()}|$wanted|" +
            if (wantGpu) "gpu" else "cpu"
        session?.takeIf { loadedKey == key }?.let { loaded ->
            if (vision && this.vision == null) attachVision(context, modelFile, loaded.model)
            return loaded
        }
        releaseLocked()

        val projector = if (vision) LlamaCpp.projectorFor(modelFile) else null
        val memory = memoryInfo(context)
        val fileBytes = modelFile.length() + (projector?.length() ?: 0L)
        // The weights alone have to fit before anything is worth opening.
        if (fileBytes + OVERHEAD_BYTES > memory.ceiling) throw notEnoughMemory(context, fileBytes + OVERHEAD_BYTES, memory)

        if (wantGpu) {
            if (gpuBaselineKb < 0) gpuBaselineKb = AcceleratorVerdicts.graphicsKb()
            gpuVerdicts.begin(context, gpuKey)
            val onGpu = try {
                openSession(context, modelFile, wanted, memory, LlamaNative.ALL_LAYERS, key)
            } catch (e: LowMemoryException) {
                // Not the GPU's fault: the phone has no room for it either way.
                gpuVerdicts.confirm()
                throw e
            } catch (_: Throwable) {
                // Whatever it allocated before failing may be stranded too.
                gpuReleased = true
                gpuVerdicts.markFailed(context, gpuKey)
                null
            }
            if (onGpu != null) {
                loadedGpuKey = gpuKey
                gpuLoaded = true
                if (vision) attachVision(context, modelFile, onGpu.model)
                return onGpu
            }
        }
        val cpuKey = key.substringBeforeLast('|') + "|cpu"
        return openSession(context, modelFile, wanted, memory, 0, cpuKey).also {
            if (vision) attachVision(context, modelFile, it.model)
        }
    }

    /**
     * Whether the loaded library can put a model on the GPU: the OpenCL
     * build, and a driver that offers a device. Starting the backend is the
     * moment a bad driver takes the process down, so it runs inside the
     * library's own attempt. Lock held.
     */
    private fun gpuReady(context: Context): Boolean {
        if (!LlamaNative.gpuLibrary) return false
        libraryVerdicts.load(context)
        val key = libraryKey(context)
        if (key in libraryVerdicts) return false
        libraryVerdicts.begin(context, key)
        val found = runCatching { LlamaGpu.devices() }.getOrDefault(emptyList())
        libraryVerdicts.confirm()
        return found.isNotEmpty()
    }

    /** The [libraryVerdicts] key: this phone and install, no model. */
    private fun libraryKey(context: Context): String = AcceleratorVerdicts.keyPrefix(context) + GPU_LIBRARY_KEY

    /** Opens the model and a context that fits, or throws. Lock held. */
    private fun openSession(
        context: Context,
        modelFile: File,
        wanted: Int,
        memory: Memory,
        gpuLayers: Int,
        key: String,
    ): LlamaSession {
        val fileBytes = modelFile.length()
        val opened = LlamaModel.open(modelFile, gpuLayers = gpuLayers)
            ?: throw IOException(context.getString(R.string.core_intel_llm_engine_error_load))
        try {
            val trained = opened.info.trainedContext.takeIf { it > 0 } ?: wanted
            var contextSize = minOf(wanted, trained)
            // The cache grows with the window: shrink it to what fits now,
            // never below a window a short request still works in.
            val perToken = opened.info.kvBytesPerToken.coerceAtLeast(1)
            while (contextSize > MIN_CONTEXT && fileBytes + perToken * contextSize + OVERHEAD_BYTES > memory.available) {
                contextSize = (contextSize / 2).coerceAtLeast(MIN_CONTEXT)
            }
            val needed = fileBytes + perToken * contextSize + OVERHEAD_BYTES
            if (needed > memory.ceiling) throw notEnoughMemory(context, needed, memory)
            val created = LlamaSession(opened, contextSize, LlamaThreads.forDevice())
            model = opened
            session = created
            loadedKey = key
            return created
        } catch (e: Throwable) {
            session?.close()
            session = null
            opened.close()
            model = null
            loadedKey = null
            // The memory check's own message says more than the generic one.
            if (e is LowMemoryException) throw e
            throw IOException(context.getString(R.string.core_intel_llm_engine_error_memory), e)
        }
    }

    /**
     * Loads [modelFile]'s projector onto [loaded], on the same kind of device
     * as the model. One that will not load is remembered, and the picture
     * goes to the caller's fallback. Lock held.
     */
    private fun attachVision(context: Context, modelFile: File, loaded: LlamaModel) {
        val refused = VisionUnsupportedException(context.getString(R.string.core_intel_llm_engine_error_vision))
        val projector = LlamaCpp.projectorFor(modelFile) ?: throw refused
        if (projector.path in visionRefused) throw refused
        vision = LlamaVision.open(loaded, projector, loaded.onGpu, LlamaThreads.forDevice()) ?: run {
            visionRefused += projector.path
            throw refused
        }
    }

    /**
     * Free memory now ([available]) and the most a model may ever take on this
     * phone ([ceiling]). Above the ceiling a load is refused outright; between
     * the two the context shrinks first, and what is left is Android's to
     * find by closing cached apps.
     */
    private class Memory(val available: Long, val total: Long) {
        val ceiling: Long get() = maxOf(available, total * 6 / 10)
    }

    private fun memoryInfo(context: Context): Memory {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return Memory(info.availMem, info.totalMem)
    }

    private class LowMemoryException(message: String) : IOException(message)

    private fun notEnoughMemory(context: Context, needed: Long, memory: Memory) = LowMemoryException(
        context.getString(
            R.string.core_intel_llm_engine_error_low_memory,
            LocalLlmDownloadManager.formatBytes(needed),
            LocalLlmDownloadManager.formatBytes(memory.available),
        ),
    )

    private fun releaseLocked() {
        vision?.close()
        vision = null
        session?.close()
        session = null
        model?.close()
        model = null
        if (gpuLoaded) gpuReleased = true
        gpuLoaded = false
        // Torn down by this code, not by a crash.
        if (loadedKey != null) gpuVerdicts.confirm()
        loadedKey = null
        loadedGpuKey = null
    }

    companion object {
        /** The window when the setting asks for none. */
        const val DEFAULT_CONTEXT = 4096

        /** The smallest window the memory check shrinks to. */
        const val MIN_CONTEXT = 1024

        /** The longest answer, so a model that never ends its turn still stops. */
        const val MAX_ANSWER_TOKENS = 4096

        /** Compute buffers, the logits and llama.cpp's own bookkeeping, roughly. */
        const val OVERHEAD_BYTES = 160L * 1024 * 1024

        /**
         * A loaded model that nothing has used for this long is unloaded. The
         * keyboard releases its own earlier when it hides; this covers the
         * settings app's chat and assistant, which nothing else releases.
         */
        const val IDLE_RELEASE_MS = 3 * 60 * 1000L

        /**
         * Pictures one turn may carry, as on LiteRT-LM: every picture is
         * hundreds of tokens of a small window.
         */
        const val MAX_IMAGES = 3

        /**
         * The longer side a picture is scaled down to before the projector
         * sees it. Models that read a picture at its own size (Qwen-VL) spend
         * a token on every 28 by 28 pixels, so a full phone screenshot would
         * take thousands; at this size it takes around 600, and text the size
         * of a chat message stays legible.
         */
        const val MAX_IMAGE_SIDE = 1024

        /** One [AcceleratorVerdicts.key] per line: GGUF models that failed on this phone's GPU. */
        const val GPU_FAILED_FILE = "llama-gpu-failed"

        /** The key of a GPU model that has not answered yet. */
        const val GPU_ATTEMPT_FILE = "llama-gpu-attempt"

        /** The OpenCL build of the library, when starting its backend took a process down. */
        const val GPU_LIBRARY_FAILED_FILE = "llama-gpu-library-failed"
        const val GPU_LIBRARY_ATTEMPT_FILE = "llama-gpu-library-attempt"
        const val GPU_LIBRARY_KEY = "opencl"

        /**
         * [bytes] (a JPEG or PNG) as RGB pixels for the projector, scaled so
         * the longer side is at most [MAX_IMAGE_SIDE]; null when it does not
         * decode.
         */
        fun decodeImage(bytes: ByteArray): LlamaImage? = runCatching { decodeOrNull(bytes) }.getOrNull()

        private fun decodeOrNull(bytes: ByteArray): LlamaImage? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            val longer = maxOf(decoded.width, decoded.height)
            val bitmap = if (longer > MAX_IMAGE_SIDE) {
                val scale = MAX_IMAGE_SIDE.toFloat() / longer
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                    true,
                ).also { if (it !== decoded) decoded.recycle() }
            } else {
                decoded
            }
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            bitmap.recycle()
            val rgb = ByteArray(pixels.size * 3)
            for (i in pixels.indices) {
                val p = pixels[i]
                rgb[i * 3] = (p shr 16).toByte()
                rgb[i * 3 + 1] = (p shr 8).toByte()
                rgb[i * 3 + 2] = p.toByte()
            }
            return LlamaImage(width, height, rgb)
        }
    }
}

/** How many threads llama.cpp gets: the phone's big cores. */
internal object LlamaThreads {

    @Volatile
    private var cached = 0

    fun forDevice(): Int {
        cached.takeIf { it > 0 }?.let { return it }
        return pick(
            cores = Runtime.getRuntime().availableProcessors(),
            capacities = readPerCore("cpu_capacity"),
            maxFreqs = readPerCore("cpufreq/cpuinfo_max_freq"),
        ).also { cached = it }
    }

    /**
     * Big cores by the kernel's own capacity figure where it publishes one
     * (1024 is the fastest core): the cores at half of the top or more. A
     * thread on a little core holds every other thread up at each step's
     * barrier, so leaving them out is faster, not just kinder. Without
     * capacities, the cores above the slowest cluster's clock, or half the
     * cores when every one runs at the same clock. Between 1 and 8.
     */
    fun pick(cores: Int, capacities: List<Long>, maxFreqs: List<Long>): Int {
        val big = when {
            capacities.size == cores && capacities.max() > 0 -> {
                val top = capacities.max()
                capacities.count { it * 2 >= top }
            }
            maxFreqs.size == cores && maxFreqs.distinct().size > 1 -> {
                val slowest = maxFreqs.min()
                maxFreqs.count { it > slowest }
            }
            else -> (cores + 1) / 2
        }
        return big.coerceIn(1, 8)
    }

    private fun readPerCore(path: String): List<Long> {
        val cores = Runtime.getRuntime().availableProcessors()
        return (0 until cores).mapNotNull { cpu ->
            runCatching { File("/sys/devices/system/cpu/cpu$cpu/$path").readText().trim().toLong() }.getOrNull()
        }
    }
}
