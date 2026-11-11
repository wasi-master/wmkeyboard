package com.wasimaster.wmkeyboard.core.localllm

import android.os.Build
import com.wasimaster.wmkeyboard.config.BuildConfig
import java.io.File

/**
 * Whose NPU a chip has, and so which LiteRT dispatch library drives it (#804).
 * Qualcomm's needs Qualcomm's QNN libraries beside it, under Qualcomm's own
 * licence; MediaTek's and Google's are Apache-2.0 and drive the phone's own
 * vendor drivers (NeuroPilot, the Tensor TPU's), which the system already has.
 */
enum class NpuVendor(val dispatchLibrary: String) {
    QUALCOMM("libLiteRtDispatch_Qualcomm.so"),
    MEDIATEK("libLiteRtDispatch_MediaTek.so"),
    GOOGLE_TENSOR("libLiteRtDispatch_GoogleTensor.so"),
}

/**
 * Which phones can run on-device models on their NPU (#804), and which
 * runtime libraries that takes.
 *
 * NPU targets are per chip, not per brand: a model has to be compiled ahead of
 * time for one SoC (the catalog's [NpuBuild]s). On a Snapdragon the QNN
 * runtime has one DSP library pair per Hexagon generation, and this build
 * packages the pairs in [HTP_ARCH] only. A Dimensity or a Tensor needs only
 * LiteRT's dispatch library for its vendor: the drivers are the phone's.
 * A chip outside these lists has no NPU choice at all, whatever models exist
 * for it upstream. Adding a Snapdragon is one entry here and its libraries in
 * the `wmkeyboard.npu-runtime` convention and `native/gates.json`; adding a
 * Dimensity or a Tensor is one entry here.
 */
object NpuSupport {

    /**
     * Snapdragon -> the Hexagon tensor processor generation its QNN libraries
     * are named after (`libQnnHtpV79Stub.so` and so on). SM8550 is the
     * Snapdragon 8 Gen 2 (Galaxy S23 series), SM8650 the 8 Gen 3 (Galaxy S24
     * series), SM8750 the 8 Elite (Galaxy S25 series), SM8850 the 8 Elite
     * Gen 5 (Galaxy S26 Ultra).
     */
    internal val HTP_ARCH = mapOf(
        "SM8550" to "V73",
        "SM8650" to "V75",
        "SM8750" to "V79",
        "SM8850" to "V81",
    )

    /**
     * Dimensity chips LiteRT-LM has NPU builds for: MT6989 is the Dimensity
     * 9300, MT6991 the 9400, MT6993 the 9500. Older APUs have builds of
     * single-shot models upstream, but no language model, so no NPU choice.
     */
    internal val MEDIATEK_CHIPS = setOf("MT6989", "MT6991", "MT6993")

    /**
     * Tensor chips with an NPU build of a language model upstream: the G3
     * (Pixel 8 series) and G4 (Pixel 9 series), in a community repository,
     * and Google's own for the G5 (Pixel 10 series) and G6. Spelled "TENSOR-G5", from the "Tensor G5"
     * the system reports as its SoC model (LiteRT-LM reads the same
     * property and spells it the same way).
     */
    internal val TENSOR_CHIPS = setOf("TENSOR-G3", "TENSOR-G4", "TENSOR-G5", "TENSOR-G6")

    /**
     * The chip id a [Build.SOC_MODEL] value stands for, or null. Vendors add
     * suffixes ("SM8750-AC" on some Galaxy builds, "MT6991V/CZA" on some
     * Dimensity ones) and the odd lowercase, so only the `SM` or `MT` + four
     * digits prefix counts; a Tensor is "Tensor G" and its generation.
     */
    fun normalizeSoc(raw: String?): String? {
        val text = raw.orEmpty().trim().uppercase()
        SOC_PATTERN.find(text)?.let { return it.value }
        return TENSOR_PATTERN.find(text)?.let { "TENSOR-G${it.groupValues[1]}" }
    }

    /** Whose NPU [soc] has, or null for a chip this build has no runtime for. */
    fun vendor(soc: String?): NpuVendor? = when {
        soc == null -> null
        soc in HTP_ARCH -> NpuVendor.QUALCOMM
        soc in MEDIATEK_CHIPS -> NpuVendor.MEDIATEK
        soc in TENSOR_CHIPS -> NpuVendor.GOOGLE_TENSOR
        else -> null
    }

    /**
     * [normalizeSoc], narrowed to the chips this build has a runtime for, on
     * the platform the runtime needs: API 31 and a 64-bit ARM process.
     */
    fun supportedSoc(rawSoc: String?, sdkInt: Int, primaryAbi: String?): String? {
        if (sdkInt < MIN_SDK || primaryAbi != ARM64) return null
        return normalizeSoc(rawSoc)?.takeIf { vendor(it) != null }
    }

    /**
     * This phone's NPU target for LiteRT-LM models, or null when there is
     * none: not a listed chip, an older Android, an F-Droid build. Fixed for
     * the process.
     */
    val soc: String? by lazy {
        if (!BuildConfig.ENABLE_NPU || Build.VERSION.SDK_INT < MIN_SDK) return@lazy null
        supportedSoc(Build.SOC_MODEL, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.firstOrNull())
    }

    /**
     * [soc] when it is a Snapdragon: the target of everything that runs on
     * Qualcomm's QNN libraries directly rather than through LiteRT (the
     * sticker generator's Stable Diffusion, sherpa-onnx's and ExecuTorch's
     * QNN builds). Null on a Dimensity or a Tensor.
     */
    val qnnSoc: String? get() = soc?.takeIf { vendor(it) == NpuVendor.QUALCOMM }

    /** Whether the Compute row may offer NPU on this phone. */
    val available: Boolean get() = soc != null

    /**
     * The runtime files [soc] needs, in the order they are loaded. On a
     * Snapdragon: the QNN system and HTP libraries, the generation's stub
     * (ARM code that talks to the DSP) and skel (Hexagon code the DSP loads),
     * and the dispatch library last; never `libQnnHtpPrepare.so`, which
     * compiles a model on the phone, since every catalog NPU build arrives
     * compiled. On a Dimensity or a Tensor: the vendor's dispatch library
     * alone. Empty for a chip this build has no runtime for.
     */
    fun runtimeLibraries(soc: String): List<String> = when (vendor(soc)) {
        NpuVendor.QUALCOMM -> {
            val arch = HTP_ARCH.getValue(soc)
            listOf(
                "libQnnSystem.so",
                "libQnnHtp.so",
                "libQnnHtp${arch}Stub.so",
                "libQnnHtp${arch}Skel.so",
                NpuVendor.QUALCOMM.dispatchLibrary,
            )
        }
        NpuVendor.MEDIATEK -> listOf(NpuVendor.MEDIATEK.dispatchLibrary)
        NpuVendor.GOOGLE_TENSOR -> listOf(NpuVendor.GOOGLE_TENSOR.dispatchLibrary)
        null -> emptyList()
    }

    /**
     * The native-delivery gates [soc]'s [runtimeLibraries] come in, as
     * `native/gates.json` splits them: on a Snapdragon the shared QNN gate and
     * its generation's, otherwise the vendor's dispatch library on its own,
     * so a Dimensity never fetches Qualcomm's files or asks for their licence.
     */
    fun deliveryGates(soc: String): List<String> = when (vendor(soc)) {
        NpuVendor.QUALCOMM -> listOf(GATE, "$GATE-${HTP_ARCH.getValue(soc)}")
        NpuVendor.MEDIATEK -> listOf("$GATE-mediatek")
        NpuVendor.GOOGLE_TENSOR -> listOf("$GATE-tensor")
        null -> emptyList()
    }

    /**
     * Whether [fileName] names [soc] as a word of its own: "sm8750" in
     * "…_q4_ekv1280_sm8750.litertlm", and for a Tensor its generation alone
     * ("…_Google_Tensor_G5", "…_aot_ekv4096_G4"), the way upstream names them.
     */
    fun namesChip(fileName: String, soc: String): Boolean {
        val words = fileName.lowercase().split(NON_WORD)
        val chip = soc.lowercase()
        return chip.removePrefix(TENSOR_PREFIX) in words
    }

    /** The shared Qualcomm gate; the per-chip ones are this with a suffix. */
    const val GATE = "npu"

    private const val MIN_SDK = 31
    private const val ARM64 = "arm64-v8a"
    private val SOC_PATTERN = Regex("^(SM|MT)\\d{4}")
    private const val TENSOR_PREFIX = "tensor-"
    private val NON_WORD = Regex("[^a-z0-9]+")
    private val TENSOR_PATTERN = Regex("^(?:GOOGLE\\s+)?TENSOR\\s*G(\\d+)")
}

/**
 * Which file an engine with Compute set to NPU, or to CPU or GPU, should load
 * for one selected model (#804). A catalog model can have two files side by
 * side in its directory: the standard build every backend runs, and an
 * ahead-of-time build for this phone's chip that only the NPU can run. Either
 * may be missing, so the engine needs both answers to fall back.
 */
object NpuRouting {

    /**
     * [npu] is the NPU build to try first, [standard] the file every other
     * backend loads. Both are existing files or null.
     */
    data class Plan(val npu: File?, val standard: File?)

    /**
     * Resolves [modelFile], the file `LocalLlmStore.selectedModelFile` handed
     * out (whichever of the two builds is on disk), for a phone whose NPU
     * target is [soc] (null: no NPU). An imported file is taken at its word:
     * one whose name carries the chip id ("…sm8750.litertlm") is an NPU build,
     * anything else a standard one (see [NpuSupport.namesChip]).
     */
    fun plan(modelFile: File, soc: String?): Plan {
        val dir = modelFile.parentFile
        val model = dir?.let { LocalLlmCatalog.byId(it.name) }
            ?.takeIf { m -> modelFile.name == m.fileName || m.npuBuilds.any { it.fileName == modelFile.name } }
        if (model == null) {
            val existing = modelFile.takeIf { it.isFile }
            val npuNamed = soc != null && NpuSupport.namesChip(modelFile.name, soc)
            return if (npuNamed) Plan(npu = existing, standard = null) else Plan(npu = null, standard = existing)
        }
        val npu = soc?.let { LocalLlmCatalog.npuBuild(model, it) }
            ?.let { File(dir, it.fileName) }
            ?.takeIf { it.isFile }
        val standard = File(dir, model.fileName).takeIf { it.isFile }
        return Plan(npu = npu, standard = standard)
    }
}
