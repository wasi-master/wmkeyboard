package com.wasimaster.wmkeyboard.core.imagegen

import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import kotlinx.coroutines.flow.StateFlow

/**
 * JNI surface of libwmimagegen.so (native/imagegen-jni): MNN's CPU runtime,
 * and on arm64 its OpenCL and Vulkan ones ([nativeLoadOn]), one loaded module per handle, tensors in and the first output back as NCHW
 * floats. Optional at runtime: GitHub builds fetch it the first time it is
 * asked for, F-Droid builds it from source, Play carries it in the
 * on-demand `:feature:imagegen` split.
 *
 * Nothing here is specific to pictures, so the scanner's PaddleOCR reader
 * runs its graphs through it too where the build has no LiteRT
 * ([com.wasimaster.wmkeyboard.core.ocr.MnnPaddleRuntime]), and so does the
 * upscaler ([com.wasimaster.wmkeyboard.core.upscale.UpscaleEngine]): one MNN
 * in the app.
 *
 * Calls on one handle must not overlap; the generator's service makes them
 * all on one thread, the scanner on its own.
 */
object ImageGenNative {
    const val LIBRARY = "wmimagegen"

    private val library = GatedLibrary({ FeatureModules.imagegen }) {
        runCatching { FeatureModules.loadLibrary(LIBRARY) }.isSuccess
    }

    val available: Boolean get() = library.available

    val shipped: Boolean get() = library.shipped

    val moduleState: StateFlow<ModuleState> get() = FeatureModules.imagegen.state

    fun requestModule() = FeatureModules.imagegen.requestInstall()

    /** 0 when the file does not load or lacks the named tensors. */
    external fun nativeLoad(path: String, inputs: Array<String>, outputs: Array<String>, threads: Int): Long

    /**
     * Like [nativeLoad], on the GPU when [compute] is [COMPUTE_GPU]: MNN's
     * OpenCL backend, else its Vulkan one, else the CPU after all, float32
     * on each. A GPU backend's compiled kernels go into [cache] after the
     * first run, so the next load starts from them; null for none.
     */
    external fun nativeLoadOn(
        path: String, inputs: Array<String>, outputs: Array<String>, threads: Int, compute: Int, cache: String?,
    ): Long

    /** What a handle runs on: [BACKEND_CPU], [BACKEND_OPENCL] or [BACKEND_VULKAN]. */
    external fun nativeBackend(handle: Long): Int

    const val COMPUTE_CPU = 0
    const val COMPUTE_GPU = 1
    const val BACKEND_CPU = 0
    const val BACKEND_OPENCL = 3
    const val BACKEND_VULKAN = 7

    /**
     * [inputs] holds one FloatArray or IntArray per input, [shapes] their
     * shapes. The first output, or null; its shape is written into [outShape].
     */
    external fun nativeRun(handle: Long, inputs: Array<Any>, shapes: Array<IntArray>, outShape: IntArray?): FloatArray?

    external fun nativeFree(handle: Long)

    /** The tensors a .mnn file declares as JSON (see imagegen.hpp), or null when it is not an MNN model. */
    external fun nativeDescribe(path: String): String?
}
