package com.wasimaster.wmkeyboard.core.voice.sherpa

import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary

/**
 * sherpa-onnx built with its QNN recognizers, through
 * `native/sherpa-onnx-jni/qnn_jni.cpp` (`libwmsherpa_qnn.so`, arm64 only):
 * the offline recognizer running k2-fsa's ready-made QNN context binaries of
 * Parakeet TDT 0.6B v3 and Moonshine Tiny on a Snapdragon NPU. Driven by
 * [QnnSpeech].
 *
 * Its own gate, [FeatureModules.sherpaQnn] (`:feature:sherpaqnn` on Play,
 * never in F-Droid's build: it compiles against Qualcomm's QNN headers). It
 * names `libonnxruntime.so` as a dependency like `libwmsherpa.so` does, so it
 * loads only after [SherpaNative] has loaded ONNX Runtime; and it opens
 * Qualcomm's own libraries by path from the NPU runtime's directory
 * (`NpuRuntime` in :core:intelligence, reached through
 * [com.wasimaster.wmkeyboard.core.voice.executorch.EtNative.qnnRuntime]).
 *
 * Handles are plain longs, freed by their owner. Not thread-safe; [QnnSpeech]
 * calls it on one thread.
 */
object SherpaQnnNative {

    private val library = GatedLibrary({ FeatureModules.sherpaQnn }) {
        runCatching { FeatureModules.loadLibrary("wmsherpa_qnn") }.isSuccess
    }

    /** Whether the library is loaded, ONNX Runtime first. Loads both if it can. */
    val available: Boolean get() = SherpaNative.available && library.available

    /** Whether this build has it and the ONNX Runtime it needs, counting Play modules not fetched yet. */
    val shipped: Boolean get() = library.shipped && SherpaNative.shipped

    /** Asks for both modules it needs. Idempotent. */
    fun requestModules() {
        if (!FeatureModules.onnx.installed) SherpaNative.requestModule()
        if (!FeatureModules.sherpaQnn.installed) FeatureModules.sherpaQnn.requestInstall()
    }

    /** Points the DSP's library search at [dir], where the NPU runtime's skel is. Process-wide. */
    @JvmStatic external fun nativeSetAdspDirectory(dir: String)

    /**
     * Parakeet TDT from its context binaries; [runtimeDir] holds
     * `libQnnHtp.so` and `libQnnSystem.so`. 0 when it will not load.
     */
    @JvmStatic external fun nativeParakeet(
        encoder: String,
        decoder: String,
        joiner: String,
        tokens: String,
        runtimeDir: String,
    ): Long

    /** Moonshine from its encoder and decoder context binaries. 0 when it will not load. */
    @JvmStatic external fun nativeMoonshine(encoder: String, decoder: String, tokens: String, runtimeDir: String): Long

    @JvmStatic external fun nativeFree(recognizer: Long)

    /**
     * The first [count] samples (16 kHz mono, in [-1, 1]) as text, UTF-8, or
     * null when the decode failed. No more than the binaries' clip length:
     * sherpa-onnx cuts anything past it.
     */
    @JvmStatic external fun nativeDecode(recognizer: Long, samples: FloatArray, count: Int): ByteArray?
}
