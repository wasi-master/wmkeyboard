package com.wasimaster.wmkeyboard.core.localllm

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.intelligence.R

/**
 * How a GGUF file stores its weights, as llama.cpp's `general.file_type`
 * names it (`LLAMA_FTYPE_MOSTLY_*` in llama.h). [tag] is the spelling people
 * and file names use; a label of a format, so never translated.
 *
 * [npu] says whether ggml's Hexagon backend runs weights of this kind on a
 * Snapdragon's NPU: Q4_0, Q8_0 and MXFP4 only. A model in any other format
 * still loads with Compute on NPU, but its weights are multiplied on the CPU,
 * so NPU gains it nothing. "Mostly": a file keeps a few tensors (the
 * embeddings, the output) in another type, which never changes the verdict.
 */
enum class GgufQuant(val tag: String, val fileType: Int, val npu: Boolean, @StringRes val infoRes: Int?) {
    F32("F32", 0, false, null),
    F16("F16", 1, false, null),
    Q4_0("Q4_0", 2, true, R.string.core_intel_llm_quant_q4_0_info),
    Q4_1("Q4_1", 3, false, null),
    Q8_0("Q8_0", 7, true, R.string.core_intel_llm_quant_q8_0_info),
    Q5_0("Q5_0", 8, false, null),
    Q5_1("Q5_1", 9, false, null),
    Q2_K("Q2_K", 10, false, null),
    Q3_K_S("Q3_K_S", 11, false, null),
    Q3_K_M("Q3_K_M", 12, false, null),
    Q3_K_L("Q3_K_L", 13, false, null),
    Q4_K_S("Q4_K_S", 14, false, null),
    Q4_K_M("Q4_K_M", 15, false, R.string.core_intel_llm_quant_q4_k_m_info),
    Q5_K_S("Q5_K_S", 16, false, null),
    Q5_K_M("Q5_K_M", 17, false, null),
    Q6_K("Q6_K", 18, false, null),
    IQ2_XXS("IQ2_XXS", 19, false, null),
    IQ2_XS("IQ2_XS", 20, false, null),
    Q2_K_S("Q2_K_S", 21, false, null),
    IQ3_XS("IQ3_XS", 22, false, null),
    IQ3_XXS("IQ3_XXS", 23, false, null),
    IQ1_S("IQ1_S", 24, false, null),
    IQ4_NL("IQ4_NL", 25, false, null),
    IQ3_S("IQ3_S", 26, false, null),
    IQ3_M("IQ3_M", 27, false, null),
    IQ2_S("IQ2_S", 28, false, null),
    IQ2_M("IQ2_M", 29, false, null),
    IQ4_XS("IQ4_XS", 30, false, null),
    IQ1_M("IQ1_M", 31, false, null),
    BF16("BF16", 32, false, null),
    TQ1_0("TQ1_0", 36, false, null),
    TQ2_0("TQ2_0", 37, false, null),
    MXFP4("MXFP4", 38, true, null),
    ;

    /** The suffix of a catalog id for a row of this format: `gemma3-1b-q8-0` (ids keep to `[a-z0-9.-]`). */
    val idSuffix: String get() = tag.lowercase().replace('_', '-')

    companion object {
        fun ofFileType(type: Int): GgufQuant? = entries.firstOrNull { it.fileType == type }

        /**
         * The format a file name says, for a GGUF whose header has no
         * `general.file_type`: the longest tag that stands as a word in it
         * (`Q4_K_M` before `Q4_K`, `IQ4_XS` before `Q4`). Null when none does.
         * `fp16` counts as F16, as some uploads spell it.
         */
        fun ofName(name: String): GgufQuant? {
            val upper = name.uppercase().replace("FP16", "F16")
            return entries.sortedByDescending { it.tag.length }.firstOrNull { quant ->
                Regex("""(^|[-_.])${Regex.escape(quant.tag)}($|[-_.])""").containsMatchIn(upper)
            }
        }
    }
}
