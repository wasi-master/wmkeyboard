package com.wasimaster.wmkeyboard.core.voice.executorch

import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * ExecuTorch, built for the keyboard's own models alone, through
 * `native/executorch-jni`: a selective build carrying only the operators those
 * models use, the XNNPACK delegate and the Module extension. Asr4All, Parakeet
 * and Moonshine all run on it, each with its own Kotlin pipeline over
 * [Program], and so does the FUTO Swipe glide engine (`futoswipe`).
 *
 * The libraries ship in `src/executorch/jniLibs`, behind their own gate,
 * [FeatureModules.executorch]: Play builds move them into the on-demand
 * `:feature:executorch` split and GitHub and F-Droid builds fetch them on
 * first use, so [shipped] says whether the build has the runtime and
 * [available] whether it is loaded.
 *
 * Two builds of the library bind these JNI names, and a process keeps the
 * first it loads: the plain one, and on arm64 the QNN one
 * (`libwmexecutorch_qnn.so`, its own gate [FeatureModules.executorchQnn],
 * never in F-Droid's build), which adds ExecuTorch's Qualcomm delegate so a
 * program exported for a Snapdragon NPU runs its delegated parts there. The
 * QNN build is loaded instead of the plain one once its gate is on the phone
 * and [qnnPossible] says the phone has an NPU the `npu` gate serves; every
 * program the plain build runs, it runs the same.
 *
 * A program is not thread-safe, and the speech models are large, so every
 * speech call runs on [dispatcher], one thread, and [programs] keeps one set
 * of speech programs at a time: opening another engine's model closes the
 * last one. The glide models are small and opened apart from that set with
 * [open], and serialise their own calls.
 */
object EtNative {

    private val library = GatedLibrary({ FeatureModules.executorch }) {
        val qnn = FeatureModules.executorchQnn.installed && qnnPossible() &&
            runCatching { FeatureModules.loadLibrary("wmexecutorch_qnn") }.isSuccess
        qnn || runCatching { FeatureModules.loadLibrary("wmexecutorch") }.isSuccess
    }

    /**
     * Whether this phone has a Snapdragon NPU the `npu` gate has a runtime
     * for. Set by the app at start (`NpuSupport` lives in :core:intelligence);
     * false until then, so the plain build is what loads.
     */
    @Volatile
    var qnnPossible: () -> Boolean = { false }

    /**
     * Unpacks or finds the QNN runtime for this phone's NPU and loads its
     * libraries into the process, returning their directory, or null where
     * there is none. Set by the app at start (`NpuRuntime`).
     */
    @Volatile
    var qnnRuntime: () -> File? = { null }

    /** Whether the loaded build carries the Qualcomm delegate. Loads the library. */
    val qnn: Boolean get() = available && runCatching { nativeHasQnn() }.getOrDefault(false)

    /** Asks for the QNN build's gate, ahead of a program exported for the NPU. */
    fun requestQnn() {
        if (qnnPossible() && !FeatureModules.executorchQnn.installed) FeatureModules.executorchQnn.requestInstall()
    }

    /** Whether the runtime is loaded on this device and build. */
    val available: Boolean get() = library.available

    /** Whether this build has the runtime, counting a Play module not fetched yet. */
    val shipped: Boolean get() = library.shipped

    /** The one thread every program call runs on. */
    val dispatcher: CoroutineDispatcher by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "executorch").apply { isDaemon = true } }
            .asCoroutineDispatcher()
    }

    /** An opened program, memory-mapped. [close] frees it. */
    class Program internal constructor(private var handle: Long) : EtRunner, AutoCloseable {

        private val shapes = HashMap<String, MethodShape?>()

        override fun shape(method: String): MethodShape? = shapes.getOrPut(method) {
            nativeMethodMeta(handle, method)?.let(::parseShape)
        }

        override fun run(method: String, inputs: List<Tensor>): List<Tensor> {
            check(handle != 0L) { "program is closed" }
            val out = nativeExecute(
                handle,
                method,
                Array(inputs.size) { inputs[it].data },
                Array(inputs.size) { inputs[it].shape },
            )
            return List(out.size / 2) { i ->
                Tensor(out[i * 2 + 1] as? LongArray ?: LongArray(0), out[i * 2] ?: FloatArray(0))
            }
        }

        /** A constant method's value, such as Parakeet's `blank_id`, or null without one. */
        fun constant(method: String): Long? =
            runCatching { run(method, emptyList()).firstOrNull()?.scalar }.getOrNull()

        override fun close() {
            val h = handle
            handle = 0L
            if (h != 0L) nativeClose(h)
        }
    }

    /**
     * Opens [file], a program exported with the Qualcomm delegate, with the
     * NPU runtime loaded and the DSP pointed at its skel first. Null when this
     * build or phone cannot: the plain build, no NPU runtime, or a program for
     * another chip.
     */
    fun openQnn(file: File): Program? {
        if (!qnn) return null
        val dir = qnnRuntime() ?: return null
        nativeSetQnnDirectory(dir.absolutePath)
        return open(file)
    }

    /** Opens [file], or null when it is not a program this runtime can load. */
    fun open(file: File): Program? {
        check(available) {
            if (FeatureModules.executorch.installed) "the ExecuTorch runtime is not in this build"
            else "the ExecuTorch runtime is still downloading"
        }
        val h = nativeOpen(file.absolutePath)
        return if (h == 0L) null else Program(h)
    }

    private var openFiles: List<String> = emptyList()
    private var openPrograms: List<Program> = emptyList()

    /** Whether any program is open, so a memory trim knows there is something to free. Any thread. */
    @Volatile var isOpen: Boolean = false
        private set

    /**
     * The programs for [files], opened if they are not the set already open,
     * which is closed first. On [dispatcher] only. Throws when one will not load.
     */
    fun programs(files: List<File>): List<Program> {
        val paths = files.map { it.absolutePath }
        if (paths == openFiles && openPrograms.isNotEmpty()) return openPrograms
        release()
        val opened = ArrayList<Program>(files.size)
        try {
            for (f in files) opened += open(f) ?: error("${f.name} did not load")
        } catch (t: Throwable) {
            opened.forEach { it.close() }
            throw t
        }
        openFiles = paths
        openPrograms = opened
        isOpen = true
        return opened
    }

    /** Closes whatever is open. On [dispatcher] only, so never under a running model. */
    fun release() {
        openPrograms.forEach { it.close() }
        openPrograms = emptyList()
        openFiles = emptyList()
        isOpen = false
    }

    /** Reads [nativeMethodMeta]'s flat layout: counts, then dtype, rank, dims per tensor. */
    internal fun parseShape(flat: LongArray): MethodShape {
        var at = 2
        fun next(): TensorSpec {
            val dtype = flat[at++].toInt()
            val rank = flat[at++].toInt()
            val dims = LongArray(rank) { flat[at++] }
            return TensorSpec(dtype, dims)
        }
        val inputs = List(flat[0].toInt()) { next() }
        val outputs = List(flat[1].toInt()) { next() }
        return MethodShape(inputs, outputs)
    }

    @JvmStatic private external fun nativeHasQnn(): Boolean

    @JvmStatic private external fun nativeSetQnnDirectory(dir: String)

    @JvmStatic private external fun nativeOpen(path: String): Long

    @JvmStatic private external fun nativeClose(handle: Long)

    @JvmStatic private external fun nativeMethodMeta(handle: Long, method: String): LongArray?

    @JvmStatic private external fun nativeExecute(
        handle: Long,
        method: String,
        inputs: Array<Any>,
        shapes: Array<LongArray>,
    ): Array<Any?>
}
