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
 * Four builds of the library bind these JNI names, and a process keeps the
 * first it loads: the plain one, and on arm64 three that each add one of
 * ExecuTorch's delegates beside XNNPACK, each its own gate, so a program
 * exported for that hardware runs its delegated parts there:
 * - `libwmexecutorch_qnn.so` ([FeatureModules.executorchQnn], never in
 *   F-Droid's build): Qualcomm's QNN, for a Snapdragon NPU the `npu` gate
 *   serves ([qnnPossible]);
 * - `libwmexecutorch_neuron.so` ([FeatureModules.executorchNeuron], never in
 *   F-Droid's build): MediaTek's NeuroPilot, for a Dimensity 9300 or 9400
 *   NPU ([neuronPossible]), through the phone's own adapter library;
 * - `libwmexecutorch_vulkan.so` ([FeatureModules.executorchVulkan]): the GPU
 *   through Vulkan 1.1 ([vulkanPossible]).
 * The first of those whose gate is on the phone and whose hardware the phone
 * has is loaded instead of the plain one ([delegate] says which); every
 * program the plain build runs, each runs the same.
 *
 * A program is not thread-safe, and the speech models are large, so every
 * speech call runs on [dispatcher], one thread, and [programs] keeps one set
 * of speech programs at a time: opening another engine's model closes the
 * last one. The glide models are small and opened apart from that set with
 * [open], and serialise their own calls.
 */
object EtNative {

    private val library = GatedLibrary({ FeatureModules.executorch }) {
        fun load(name: String) = runCatching { FeatureModules.loadLibrary(name) }.isSuccess
        (FeatureModules.executorchQnn.installed && qnnPossible() && load("wmexecutorch_qnn")) ||
            (FeatureModules.executorchNeuron.installed && neuronPossible() && load("wmexecutorch_neuron")) ||
            (FeatureModules.executorchVulkan.installed && vulkanPossible() && load("wmexecutorch_vulkan")) ||
            load("wmexecutorch")
    }

    /**
     * A MediaTek Dimensity NPU ExecuTorch's NeuroPilot backend supports: the
     * 9300 (MT6989) and 9400 (MT6991). Android 12 is the first to name the chip.
     */
    fun neuronPossible(): Boolean =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            android.os.Build.SOC_MANUFACTURER.equals("Mediatek", ignoreCase = true) &&
            android.os.Build.SOC_MODEL.uppercase() in NEURON_SOCS

    /** A GPU whose driver speaks Vulkan 1.1, which ExecuTorch's Vulkan backend needs. */
    fun vulkanPossible(): Boolean = com.wasimaster.wmkeyboard.core.util.PhoneGpu.vulkan11

    private val NEURON_SOCS = setOf("MT6989", "MT6991")

    /**
     * The delegate the loaded build carries: `qnn`, `neuron`, `vulkan`, or
     * empty for the plain build. Loads the library.
     */
    val delegate: String get() = if (available) runCatching { nativeDelegate() }.getOrDefault("") else ""

    /** Asks for the NeuroPilot build's gate, ahead of a program exported for a Dimensity NPU. */
    fun requestNeuron() {
        if (neuronPossible() && !FeatureModules.executorchNeuron.installed) FeatureModules.executorchNeuron.requestInstall()
    }

    /** Asks for the Vulkan build's gate, ahead of a program exported for the GPU. */
    fun requestVulkan() {
        if (vulkanPossible() && !FeatureModules.executorchVulkan.installed) FeatureModules.executorchVulkan.requestInstall()
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

    @JvmStatic private external fun nativeDelegate(): String

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
