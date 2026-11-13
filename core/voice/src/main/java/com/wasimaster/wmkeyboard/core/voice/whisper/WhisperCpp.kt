package com.wasimaster.wmkeyboard.core.voice.whisper

import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.voice.R
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.flow.StateFlow

/**
 * whisper.cpp, the second runtime offline Whisper runs on (#440), for the
 * catalog's [WhisperFormat.GGML] models. Built from `native/whispercpp-jni`.
 *
 * Where the LiteRT graphs have the whole generation loop traced inside them,
 * whisper.cpp runs it here, so everything Whisper itself can do is reachable:
 * any of its 99 languages can be named rather than guessed, detection can be
 * kept to the languages being dictated in, a prompt can carry the user's own
 * words, beam search can be asked for, text can be read while it decodes, a
 * transcription can be stopped part way, and a clip can run past 30 seconds.
 *
 * The libraries ship in `src/main/jniLibs` for arm64 (two builds, picked by
 * [load]) and armv7. Play builds move them into the on-demand
 * `:feature:speech` split, fetched the first time a GGML model is used, so
 * [shipped] says whether the build has them and [available] whether they are
 * loaded. A build without them (F-Droid deletes prebuilt libraries) offers no
 * GGML models; there is no stub.
 *
 * One model is open at a time, and kept open between clips, since loading it
 * takes as long as a short phrase does to say. It is open on the CPU builds,
 * or on the OpenCL one ([WhisperCppGpu]) when the caller asks for the GPU and
 * the phone has one that build runs on; a GPU that cannot open the model, or
 * fails on a clip, hands it to the CPU builds and is not asked again.
 */
object WhisperCpp {

    /** What a clip came to. [language] is the Whisper code it was read as. */
    class Result(val text: String, val language: String?)

    private val lock = ReentrantLock()

    /**
     * Guards [handle] against being freed under [partial] or [abort], which run
     * on other threads than [transcribe]. Never held across a transcription.
     */
    private val handleLock = Any()

    @Volatile private var handle = 0L
    private var openPath: String? = null

    /** [handle] is the OpenCL library's, not a CPU build's. */
    @Volatile private var openGpu = false

    private val library = GatedLibrary({ FeatureModules.speech }, ::load)

    /** Whether a whisper.cpp library is loaded on this device and build. */
    val available: Boolean get() = library.available

    /** Whether this build has whisper.cpp, counting a Play module not fetched yet. */
    val shipped: Boolean get() = library.shipped

    /** Where the libraries' Play module stands. Always installed outside Play. */
    val moduleState: StateFlow<ModuleState> get() = FeatureModules.speech.state

    /** Whether the libraries are on the device, without loading them. */
    val moduleReady: Boolean get() = FeatureModules.speech.installed

    /** Asks Play for the libraries' module. A no-op where they are in the base. */
    fun requestModule() = FeatureModules.speech.requestInstall()

    /**
     * The ARMv8.2 build where every core has the dot-product and half-precision
     * instructions it is compiled for, else the baseline one. A core without
     * them would die on an illegal instruction, so the check reads every
     * core's flags, not the first: big.LITTLE parts mix generations.
     */
    private fun load(): Boolean {
        if (hasArmV82Features() && runCatching { FeatureModules.loadLibrary("wmwhispercpp_v82") }.isSuccess) return true
        return runCatching { FeatureModules.loadLibrary("wmwhispercpp") }.isSuccess
    }

    internal fun hasArmV82Features(): Boolean = runCatching {
        val features = File("/proc/cpuinfo").readLines()
            .filter { it.startsWith("Features") }
            .map { it.substringAfter(':').trim().split(' ').toSet() }
        features.isNotEmpty() && features.all { "asimddp" in it && "asimdhp" in it && "fphp" in it }
    }.getOrDefault(false)

    /** Threads for one transcription: a phone's big cores, not its little ones too. */
    private val threads: Int get() = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    /**
     * Opens [modelFile] if it is not the model already open, on the GPU when
     * [gpu] asks for it and the phone can. Called while the phrase is still
     * being said, so the first clip does not wait for it; on the GPU's first
     * use that includes the driver compiling its kernels, which takes seconds.
     * Blocking; call off the main thread. Throws [WhisperException].
     */
    fun warm(modelFile: File, gpu: Boolean = false) {
        lock.lock()
        try {
            openLocked(modelFile, gpu)
        } finally {
            lock.unlock()
        }
    }

    /**
     * [pcm] (16 kHz mono, in [-1, 1], any length) as text, or null when [abort]
     * stopped it.
     *
     * @param languages Whisper codes. None lets the model detect among all 99;
     *   one is forced; several are detected among those alone.
     * @param translate English out, whatever language comes in.
     * @param prompt words to steer spelling and vocabulary towards, or null.
     * @param beamSize 1 decodes greedily; more keeps that many hypotheses.
     * @param gpu run it on the GPU where the phone can ([WhisperCppPlan.wantsGpu]).
     * Blocking; call off the main thread. Throws [WhisperException].
     */
    fun transcribe(
        modelFile: File,
        pcm: FloatArray,
        languages: List<String>,
        translate: Boolean,
        prompt: String?,
        beamSize: Int,
        gpu: Boolean = false,
    ): Result? {
        lock.lock()
        try {
            val codes = languages.toTypedArray()
            var h = openLocked(modelFile, gpu)
            if (openGpu) {
                try {
                    val bytes = WhisperCppGpu.attempt(WhisperCppGpu.modelKey(modelFile)) {
                        WhisperCppGpu.transcribe(h, pcm, codes, translate, prompt, beamSize, threads)
                    }
                    return bytes?.let(::result)
                } catch (_: IllegalStateException) {
                    // The GPU failed on this clip and is not asked again for
                    // this model: the CPU builds take it, and the next ones.
                    h = openLocked(modelFile, gpu)
                }
            }
            val bytes = try {
                nativeTranscribe(h, pcm, codes, translate, prompt, beamSize, threads)
            } catch (e: IllegalStateException) {
                throw WhisperException(R.string.core_voice_whisper_transcribe_error, cause = e)
            } ?: return null
            return result(bytes)
        } finally {
            lock.unlock()
        }
    }

    private fun result(bytes: ByteArray): Result {
        val out = String(bytes, Charsets.UTF_8)
        val language = out.substringBefore('\n').ifEmpty { null }
        return Result(out.substringAfter('\n').trim(), language)
    }

    /**
     * What the running transcription has decoded so far, empty when none is
     * running or nothing is decoded yet. A preview: words can still change.
     * Safe from any thread while [transcribe] runs.
     */
    fun partial(): String {
        val bytes = synchronized(handleLock) {
            val h = handle
            if (h == 0L) return ""
            if (openGpu) WhisperCppGpu.partial(h) else nativePartial(h)
        }
        // A byte-level token can end half way through a character.
        return String(bytes, Charsets.UTF_8).trimEnd('\uFFFD').trim()
    }

    /** Stops the running transcription at its next step; it returns null. Any thread. */
    fun abort() = synchronized(handleLock) {
        val h = handle
        if (h == 0L) return@synchronized
        if (openGpu) WhisperCppGpu.abort(h) else nativeAbort(h)
    }

    /** The model file loaded right now, or null; for the Resources screen (#1163). Any thread. */
    fun loadedModel(): File? = openPath?.takeIf { handle != 0L }?.let(::File)

    /** Closes the open model. Skipped while a transcription holds it, so trim-memory never waits. */
    fun release() {
        if (!lock.tryLock()) return
        try {
            closeLocked()
        } finally {
            lock.unlock()
        }
    }

    private fun openLocked(modelFile: File, gpu: Boolean): Long {
        if (!available) {
            // Play, before the split is here: the same error the LiteRT
            // graphs give while their interpreter is on its way.
            if (!moduleReady) throw WhisperException(R.string.core_voice_whisper_module_downloading)
            error("whisper.cpp is not available in this build")
        }
        val path = modelFile.canonicalPath
        val key = if (gpu) WhisperCppGpu.modelKey(modelFile) else ""
        val onGpu = gpu && WhisperCppGpu.ready(key)
        if (handle != 0L && openPath == path && openGpu == onGpu) return handle
        closeLocked()
        if (onGpu) {
            // Null when the driver or the memory said no on the way in: a
            // model too big for what the GPU may take comes back as no handle,
            // and one whose backend would not start comes back on the CPU.
            val h = runCatching { WhisperCppGpu.attempt(key) { WhisperCppGpu.open(path, true) } }.getOrDefault(0L)
            if (h != 0L && WhisperCppGpu.onGpu(h)) {
                setOpen(h, path, gpu = true)
                return h
            }
            if (h != 0L) WhisperCppGpu.close(h)
            WhisperCppGpu.fail(key)
        }
        val h = nativeOpen(path)
        if (h == 0L) throw WhisperException(R.string.core_voice_whisper_model_load_error)
        setOpen(h, path, gpu = false)
        return h
    }

    private fun setOpen(h: Long, path: String, gpu: Boolean) = synchronized(handleLock) {
        handle = h
        openPath = path
        openGpu = gpu
    }

    private fun closeLocked() = synchronized(handleLock) {
        val h = handle
        handle = 0L
        openPath = null
        if (h != 0L) {
            if (openGpu) WhisperCppGpu.close(h) else nativeClose(h)
        }
        openGpu = false
    }

    @JvmStatic private external fun nativeOpen(path: String): Long

    @JvmStatic private external fun nativeClose(handle: Long)

    @JvmStatic private external fun nativeTranscribe(
        handle: Long,
        pcm: FloatArray,
        languages: Array<String>,
        translate: Boolean,
        prompt: String?,
        beamSize: Int,
        threads: Int,
    ): ByteArray?

    @JvmStatic private external fun nativePartial(handle: Long): ByteArray

    @JvmStatic private external fun nativeAbort(handle: Long)
}
