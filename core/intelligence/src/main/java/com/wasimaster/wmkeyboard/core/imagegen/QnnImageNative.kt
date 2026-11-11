package com.wasimaster.wmkeyboard.core.imagegen

import com.wasimaster.wmkeyboard.core.localllm.NpuSupport
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary

/**
 * JNI surface of libwmimagegen_qnn.so (native/imagegen-qnn): Qualcomm AI
 * Hub's Stable Diffusion context binaries on the Hexagon NPU, through the QNN
 * runtime NpuRuntime unpacks for Compute = NPU. It rides the `npu` gate with
 * that runtime: arm64 only, never in F-Droid's build.
 */
object QnnImageNative {
    const val LIBRARY = "wmimagegen_qnn"

    private val library = GatedLibrary({ FeatureModules.imagegen }) {
        NpuSupport.qnnSoc != null && runCatching { FeatureModules.loadLibrary(LIBRARY) }.isSuccess
    }

    val available: Boolean get() = library.available

    /** Opens the QNN runtime in [runtimeDir] (NpuRuntime's directory for this chip). Idempotent. */
    external fun nativeInit(runtimeDir: String): Boolean

    /** One context binary's first graph, or 0. */
    external fun nativeLoad(path: String): Long

    /** {"graph","inputs":[{"name","dims","type","bytes","scale","offset"}],"outputs":[...]}; a value is (q + offset) * scale. */
    external fun nativeDescribe(handle: Long): String?

    /** One byte array per input and per output, in [nativeDescribe]'s order and sizes. */
    external fun nativeRun(handle: Long, inputs: Array<ByteArray>, outputs: Array<ByteArray>): Boolean

    external fun nativeFree(handle: Long)

    /** Releases the runtime and the HTP's performance vote. */
    external fun nativeShutdown()
}
