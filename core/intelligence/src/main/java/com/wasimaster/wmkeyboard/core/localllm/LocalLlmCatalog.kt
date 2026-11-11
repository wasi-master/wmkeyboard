package com.wasimaster.wmkeyboard.core.localllm

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.intelligence.R

/**
 * Container format of a model file. `.litertlm` and `.task` load through the
 * LiteRT-LM engine (`.task` is the older MediaPipe bundle kept for models
 * that don't publish a `.litertlm` yet); `.gguf` through llama.cpp.
 */
enum class ModelFormat(val extension: String, val runtime: LocalLlmRuntime) {
    LITERTLM("litertlm", LocalLlmRuntime.LITERT_LM),
    TASK("task", LocalLlmRuntime.LITERT_LM),
    GGUF("gguf", LocalLlmRuntime.LLAMA_CPP),
}

/**
 * The engine that runs a model. The user picks it by picking the model: each
 * catalog row and imported file belongs to one. LiteRT-LM is the default and
 * the only one Compute (GPU, NPU) applies to; llama.cpp runs any GGUF, on the
 * CPU. Names of software, so never translated.
 */
enum class LocalLlmRuntime(val label: String) {
    LITERT_LM("LiteRT-LM"),
    LLAMA_CPP("llama.cpp"),
}

/**
 * How much a model can be trusted, shown as a badge next to its name.
 * [STANDARD] is the unremarkable middle — it gets no badge, so its
 * [badgeRes] is null.
 */
enum class ModelTier(@StringRes val badgeRes: Int?) {
    /** Verified good on this keyboard's prompts. */
    RECOMMENDED(R.string.core_intel_llm_tier_recommended_label),
    STANDARD(null),
    /** In the catalog but never actually exercised here. */
    UNTESTED(R.string.core_intel_llm_tier_untested_label),
    /** Works, but small enough that output quality is a gamble. */
    EXPERIMENTAL(R.string.core_intel_llm_tier_experimental_label),
}

/**
 * An ahead-of-time build of a catalog model for one chip's NPU (#804),
 * published in the same Hugging Face repository as the standard file unless
 * [repo] says otherwise. Only the NPU runs it: it holds the chip's compiled
 * graph, not weights a CPU or GPU can use.
 */
data class NpuBuild(
    /** The chip it was compiled for, as [NpuSupport.normalizeSoc] spells it. */
    val soc: String,
    /** Exact file in the repository, next to the standard one. */
    val fileName: String,
    /** Exact size from huggingface.co/api. */
    val sizeBytes: Long,
    /**
     * SHA-256 of the file at [revision] (the LFS oid Hugging Face reports),
     * checked before the download counts. Null where the repository is gated
     * and the API does not show it without a token.
     */
    val sha256: String? = null,
    /** A repository of its own, for a build someone else published; null: the model's. */
    val repo: String? = null,
    /** The commit it is fetched from, set with [sha256]; null: the model's revision. */
    val revision: String? = null,
)

/**
 * A GGUF vision model's projector (its `mmproj` file), published in the same
 * repository at the same commit as the model, downloaded beside it. llama.cpp
 * reads pictures through it; without it the model answers text only.
 */
data class ModelProjector(
    /** Exact file under the repo at the model's revision. */
    val fileName: String,
    /** SHA-256 at that revision, checked like the model's. */
    val sha256: String,
    /** Exact size from huggingface.co/api. */
    val sizeBytes: Long,
)

/** One downloadable model in the curated catalog. */
data class LocalLlmModel(
    /** Stable key — used as the storage directory name and the settings value. */
    val id: String,
    val displayName: String,
    /** Parameter count as shown to the user, e.g. "270M". */
    val params: String,
    /** Hugging Face repo, e.g. "litert-community/Gemma3-1B-IT". */
    val repo: String,
    /**
     * The repository commit the file is fetched from. "main" for the older
     * LiteRT-LM rows; a GGUF row pins a commit, so the [sha256] it is
     * checked against can never be moved by a new upload.
     */
    val revision: String = "main",
    /**
     * SHA-256 of [fileName] at [revision] (the LFS oid Hugging Face reports),
     * checked before a download or an import counts as the model. Null where
     * the row predates pinning.
     */
    val sha256: String? = null,
    /** Exact file under the repo's resolve/main/. */
    val fileName: String,
    /** Approximate size — preflight space check and progress fallback. */
    val sizeBytes: Long,
    /** Gated repos need an accepted license + HF token to download. */
    val gated: Boolean,
    /**
     * The licence the weights are published under, as the repository's own
     * model card names it: an SPDX identifier where there is one, or the
     * name of the vendor's terms. A licence identifier, so never translated.
     * Shown on the model row so the terms are in view before the download,
     * gated or not. Checked against huggingface.co/api/models on 2026-09-22.
     */
    val license: String,
    val format: ModelFormat,
    val tier: ModelTier,
    /** Advisory total-device-RAM floor; the UI warns below this, never blocks. */
    val minRamMb: Int,
    /** One-line description for the model row; resolved by the UI layer. */
    @StringRes val descriptionRes: Int,
    /**
     * Reasoning model whose template opens a think block in the prompt —
     * output is bare reasoning up to a `</think>` (see AiThinking).
     */
    val reasoning: Boolean = false,
    /**
     * The file carries a vision encoder, so it can read a picture (#234: the
     * reply tool's screenshots). Only set where the published file is known
     * to: a model family that has eyes elsewhere is not enough.
     */
    val vision: Boolean = false,
    /** A GGUF vision model's projector, fetched with it; null for every other model. */
    val projector: ModelProjector? = null,
    /**
     * Builds of this model for chips' NPUs, which a download fetches instead
     * of [fileName] when Compute is NPU on one of those chips. Empty for most
     * models: upstream publishes them for a few small ones only.
     */
    val npuBuilds: List<NpuBuild> = emptyList(),
) {
    /** The engine that runs it, from its format. */
    val runtime: LocalLlmRuntime get() = format.runtime

    /** What downloading the standard file costs: the model and its projector. */
    val downloadBytes: Long get() = sizeBytes + (projector?.sizeBytes ?: 0L)
}

/**
 * Curated on-device models, best first within each runtime. The LiteRT-LM
 * files are 4/8-bit mobile quantizations published by Google's
 * litert-community on Hugging Face; the GGUF files below them run on
 * llama.cpp and come from the model makers' own repositories or ggml-org's,
 * each pinned to a commit and a SHA-256.
 *
 * The order is a quality ranking, not a size ranking — the settings list and
 * the panel's model picker both render in catalog order, and what a user
 * wants at the top is the model most likely to write well, not the one that
 * downloads fastest. Adding a model = inserting one entry at its rank.
 */
object LocalLlmCatalog {

    const val TOKEN_URL = "https://huggingface.co/settings/tokens"

    /**
     * The rows this build can run: every one where LiteRT-LM is present, the
     * GGUF ones alone in F-Droid's build, which carries no Google artifact.
     */
    val models: List<LocalLlmModel> by lazy {
        if (BuildConfig.ENABLE_LITERT_LM) allModels else allModels.filter { it.runtime == LocalLlmRuntime.LLAMA_CPP }
    }

    private val allModels: List<LocalLlmModel> = listOf(
        LocalLlmModel(
            id = "gemma4-e2b",
            displayName = "Gemma 4 E2B",
            params = "2B",
            repo = "litert-community/gemma-4-E2B-it-litert-lm",
            fileName = "gemma-4-E2B-it.litertlm",
            sizeBytes = 2_590_000_000L,
            gated = false,
            license = "Apache-2.0",
            format = ModelFormat.LITERTLM,
            tier = ModelTier.RECOMMENDED,
            minRamMb = 6144,
            descriptionRes = R.string.core_intel_llm_model_gemma4_e2b_subtitle,
            vision = true,
            // Google's Tensor builds, text only like every NPU build here: a
            // picture still goes to the standard file.
            npuBuilds = listOf(
                NpuBuild(
                    "TENSOR-G5",
                    "gemma-4-E2B-it_Google_Tensor_G5.litertlm",
                    3_206_299_658L,
                    sha256 = "72d056066f34362adcecf6b76995de67ad899f6f33dfe9079593c1acd2984191",
                    revision = GEMMA4_E2B_NPU_REVISION,
                ),
                NpuBuild(
                    "TENSOR-G6",
                    "gemma-4-E2B-it_Google_Tensor_G6.litertlm",
                    3_453_960_202L,
                    sha256 = "d6cbe8d625f4965a8c32565f65ae774d19598d0d967a4c2101a7e04988daaf58",
                    revision = GEMMA4_E2B_NPU_REVISION,
                ),
            ),
        ),
        LocalLlmModel(
            id = "gemma4-e4b",
            displayName = "Gemma 4 E4B",
            params = "4B",
            repo = "litert-community/gemma-4-E4B-it-litert-lm",
            fileName = "gemma-4-E4B-it.litertlm",
            sizeBytes = 3_660_000_000L,
            gated = false,
            license = "Apache-2.0",
            format = ModelFormat.LITERTLM,
            tier = ModelTier.RECOMMENDED,
            minRamMb = 8192,
            descriptionRes = R.string.core_intel_llm_model_gemma4_e4b_subtitle,
            vision = true,
        ),
        LocalLlmModel(
            // NVIDIA's post-training of Gemma 4 E4B for tool use, instruction
            // following and grounding, converted by litert-community for
            // LiteRT-LM 0.18 (image and audio encoders left out, so text
            // only). Thinking stays off unless asked for, so not a reasoning
            // row. OpenMDW 1.1, which allows commercial use. Pinned like the
            // GGUF rows: commit and SHA-256s from huggingface.co/api, 2026-11-11.
            id = "nemotron-bolt-e4b",
            displayName = "Nemotron 3.5 Bolt E4B",
            params = "4B",
            repo = "litert-community/NVIDIA-Nemotron-3.5-Bolt-E4B",
            revision = "a8486857161b7b0820c4885c2281b59a56b2b618",
            sha256 = "9c72c03456c6115033737247d7a3495dbacd714cff8b5a4b40049c3e37b7648a",
            fileName = "NVIDIA-Nemotron-3.5-Bolt-E4B_int4.litertlm",
            sizeBytes = 4_404_805_632L,
            gated = false,
            license = "OpenMDW-1.1",
            format = ModelFormat.LITERTLM,
            tier = ModelTier.STANDARD,
            minRamMb = 8192,
            descriptionRes = R.string.core_intel_llm_model_nemotron_bolt_e4b_subtitle,
            npuBuilds = listOf(
                NpuBuild(
                    "SM8850",
                    "NVIDIA-Nemotron-3.5-Bolt-E4B_qualcomm_sm8850.litertlm",
                    5_038_493_603L,
                    sha256 = "86af693f6f2e59246f2a252008f3bbefc73ff10f75876b924238d4f7b0bac7a1",
                ),
            ),
        ),
        LocalLlmModel(
            id = "gemma3-1b",
            displayName = "Gemma 3 1B",
            params = "1B",
            repo = "litert-community/Gemma3-1B-IT",
            fileName = "gemma3-1b-it-int4.litertlm",
            sizeBytes = 584_000_000L,
            gated = true,
            license = "Gemma Terms of Use",
            format = ModelFormat.LITERTLM,
            tier = ModelTier.UNTESTED,
            minRamMb = 4096,
            descriptionRes = R.string.core_intel_llm_model_gemma3_1b_subtitle,
            // Sizes from huggingface.co/api on 2026-11-11. Every chip upstream
            // compiles Gemma 3 1B for: Snapdragon 8 Gen 2 to 8 Elite Gen 5,
            // Dimensity 9300 to 9500, Tensor G3 to G6. The G3 and G4 builds
            // are a community repository's (litert-community hosts it; its
            // card reports both verified on a Pixel 8 Pro and a Pixel 9), with
            // a 4,096-token window; Google publishes none for those chips.
            npuBuilds = listOf(
                NpuBuild("SM8550", "Gemma3-1B-IT_q4_ekv1280_sm8550.litertlm", 690_143_232L),
                NpuBuild("SM8650", "Gemma3-1B-IT_q4_ekv1280_sm8650.litertlm", 690_094_080L),
                NpuBuild("SM8750", "Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm", 689_291_264L),
                NpuBuild("SM8850", "Gemma3-1B-IT_q4_ekv1280_sm8850.litertlm", 693_747_712L),
                NpuBuild("MT6989", "Gemma3-1B-IT_q4_ekv1280_mt6989.litertlm", 1_032_830_976L),
                NpuBuild("MT6991", "Gemma3-1B-IT_q4_ekv1280_mt6991.litertlm", 1_033_814_016L),
                NpuBuild("MT6993", "Gemma3-1B-IT_q4_ekv1280_mt6993.litertlm", 1_020_411_904L),
                NpuBuild(
                    "TENSOR-G3",
                    "Gemma3-1B-IT_int4hadamard_aot_ekv4096_G3.litertlm",
                    874_631_968L,
                    sha256 = "43f1b935cc24fb2e636f3ef910ad02ee838cebe2393900da3d2a629ffd091c3e",
                    repo = TENSOR_GEMMA3_REPO,
                    revision = TENSOR_GEMMA3_REVISION,
                ),
                NpuBuild(
                    "TENSOR-G4",
                    "Gemma3-1B-IT_int4hadamard_aot_ekv4096_G4.litertlm",
                    874_631_968L,
                    sha256 = "1c2962c8b18ff600ab37a1b15dfcccd6d1e5d8c155ad1ea46fe5fd0ab50dd6ee",
                    repo = TENSOR_GEMMA3_REPO,
                    revision = TENSOR_GEMMA3_REVISION,
                ),
                NpuBuild("TENSOR-G5", "Gemma3-1B-IT_q8_ekv1280_Google_Tensor_G5.litertlm", 2_018_723_808L),
                NpuBuild("TENSOR-G6", "Gemma3-1B-IT_q8_ekv1280_Google_Tensor_G6.litertlm", 2_026_063_840L),
            ),
        ),
        LocalLlmModel(
            id = "qwen25-1.5b",
            displayName = "Qwen 2.5 1.5B",
            params = "1.5B",
            repo = "litert-community/Qwen2.5-1.5B-Instruct",
            fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            sizeBytes = 1_600_000_000L,
            gated = false,
            license = "Apache-2.0",
            format = ModelFormat.LITERTLM,
            tier = ModelTier.STANDARD,
            minRamMb = 6144,
            descriptionRes = R.string.core_intel_llm_model_qwen25_15b_subtitle,
        ),
        LocalLlmModel(
            id = "qwen3-0.6b",
            displayName = "Qwen 3 0.6B",
            params = "0.6B",
            repo = "litert-community/Qwen3-0.6B-int4",
            fileName = "qwen3_0.6b_q4_block32_ekv1280.litertlm",
            sizeBytes = 347_000_000L,
            gated = false,
            license = "Apache-2.0",
            format = ModelFormat.LITERTLM,
            tier = ModelTier.EXPERIMENTAL,
            minRamMb = 3072,
            descriptionRes = R.string.core_intel_llm_model_qwen3_06b_subtitle,
            reasoning = true,
        ),
        LocalLlmModel(
            id = "qwen25-0.5b",
            displayName = "Qwen 2.5 0.5B",
            params = "0.5B",
            repo = "litert-community/Qwen2.5-0.5B-Instruct",
            fileName = "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            sizeBytes = 546_000_000L,
            gated = false,
            license = "Apache-2.0",
            format = ModelFormat.TASK,
            tier = ModelTier.EXPERIMENTAL,
            minRamMb = 3072,
            descriptionRes = R.string.core_intel_llm_model_qwen25_05b_subtitle,
        ),
        LocalLlmModel(
            id = "gemma3-270m",
            displayName = "Gemma 3 270M",
            params = "270M",
            repo = "litert-community/gemma-3-270m-it",
            fileName = "gemma3-270m-it-q8.litertlm",
            sizeBytes = 304_000_000L,
            gated = true,
            license = "Gemma Terms of Use",
            format = ModelFormat.LITERTLM,
            tier = ModelTier.UNTESTED,
            minRamMb = 2048,
            descriptionRes = R.string.core_intel_llm_model_gemma3_270m_subtitle,
            npuBuilds = listOf(
                NpuBuild("SM8550", "gemma3-270m-it-q8.qualcomm.sm8550.litertlm", 461_750_272L),
                NpuBuild("SM8650", "gemma3-270m-it-q8.qualcomm.sm8650.litertlm", 461_766_656L),
                NpuBuild("SM8750", "gemma3-270m-it-q8.qualcomm.sm8750.litertlm", 461_340_672L),
                NpuBuild("SM8850", "gemma3-270m-it-q8.qualcomm.sm8850.litertlm", 463_011_840L),
                NpuBuild("MT6991", "gemma3-270m-it-q8.mediatek.mt6991.litertlm", 554_467_328L),
                NpuBuild("MT6993", "gemma3-270m-it-q8.mediatek.mt6993.litertlm", 554_663_936L),
            ),
        ),
        LocalLlmModel(
            // SmolLM v1's .task bundle behaved like a base model (no chat
            // template applied); the v2 .litertlm is the proper instruct build.
            id = "smollm2-135m",
            displayName = "SmolLM2 135M",
            params = "135M",
            repo = "litert-community/SmolLM2-135M-Instruct",
            fileName = "SmolLM2_135M_Instruct.litertlm",
            sizeBytes = 143_000_000L,
            gated = false,
            license = "Apache-2.0",
            format = ModelFormat.LITERTLM,
            tier = ModelTier.EXPERIMENTAL,
            minRamMb = 2048,
            descriptionRes = R.string.core_intel_llm_model_smollm2_135m_subtitle,
        ),
        // GGUF models for llama.cpp. Sizes, commits and SHA-256s from
        // huggingface.co/api (paths-info) on 2026-10-20, the vision rows on
        // 2026-10-21. Licence per row, as each repository's model card (or,
        // for ggml-org's conversions, the original model's) names it.
        LocalLlmModel(
            id = "gguf-qwen2.5-vl-3b",
            displayName = "Qwen 2.5 VL 3B (GGUF)",
            params = "3B",
            repo = "ggml-org/Qwen2.5-VL-3B-Instruct-GGUF",
            revision = "5037fcf163dd95d1e41d1974465f0898ed108ca2",
            fileName = "Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf",
            sha256 = "d02fe9b69ad8cadbbd228e387667af66612c44bed29ffc8eb1e7caf9ac486c12",
            sizeBytes = 1_929_901_056L,
            gated = false,
            license = "Qwen Research License",
            format = ModelFormat.GGUF,
            tier = ModelTier.UNTESTED,
            minRamMb = 8192,
            descriptionRes = R.string.core_intel_llm_model_gguf_qwen25_vl_3b_subtitle,
            vision = true,
            projector = ModelProjector(
                fileName = "mmproj-Qwen2.5-VL-3B-Instruct-Q8_0.gguf",
                sha256 = "980c9b2f78c04e6cff93d277ada09e768394f112d75db3b4e9dea8a69f9fb904",
                sizeBytes = 844_757_728L,
            ),
        ),
        LocalLlmModel(
            id = "gguf-qwen3-1.7b",
            displayName = "Qwen 3 1.7B (GGUF)",
            params = "1.7B",
            repo = "Qwen/Qwen3-1.7B-GGUF",
            revision = "90862c4b9d2787eaed51d12237eafdfe7c5f6077",
            fileName = "Qwen3-1.7B-Q8_0.gguf",
            sha256 = "061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a",
            sizeBytes = 1_834_426_016L,
            gated = false,
            license = "Apache-2.0",
            format = ModelFormat.GGUF,
            tier = ModelTier.UNTESTED,
            minRamMb = 6144,
            descriptionRes = R.string.core_intel_llm_model_gguf_qwen3_17b_subtitle,
            reasoning = true,
        ),
        LocalLlmModel(
            id = "gguf-llama3.2-1b",
            displayName = "Llama 3.2 1B (GGUF)",
            params = "1B",
            repo = "bartowski/Llama-3.2-1B-Instruct-GGUF",
            revision = "067b946cf014b7c697f3654f621d577a3e3afd1c",
            fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            sha256 = "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83",
            sizeBytes = 807_694_464L,
            gated = false,
            license = "Llama 3.2 Community License",
            format = ModelFormat.GGUF,
            tier = ModelTier.UNTESTED,
            minRamMb = 4096,
            descriptionRes = R.string.core_intel_llm_model_gguf_llama32_1b_subtitle,
        ),
        LocalLlmModel(
            id = "gguf-gemma3-1b",
            displayName = "Gemma 3 1B (GGUF)",
            params = "1B",
            repo = "ggml-org/gemma-3-1b-it-GGUF",
            revision = "f9c28bcd85737ffc5aef028638d3341d49869c27",
            fileName = "gemma-3-1b-it-Q4_K_M.gguf",
            sha256 = "8ccc5cd1f1b3602548715ae25a66ed73fd5dc68a210412eea643eb20eb75a135",
            sizeBytes = 806_058_240L,
            gated = false,
            license = "Gemma Terms of Use",
            format = ModelFormat.GGUF,
            tier = ModelTier.UNTESTED,
            minRamMb = 4096,
            descriptionRes = R.string.core_intel_llm_model_gguf_gemma3_1b_subtitle,
        ),
        LocalLlmModel(
            id = "gguf-qwen3-0.6b",
            displayName = "Qwen 3 0.6B (GGUF)",
            params = "0.6B",
            repo = "Qwen/Qwen3-0.6B-GGUF",
            revision = "23749fefcc72300e3a2ad315e1317431b06b590a",
            fileName = "Qwen3-0.6B-Q8_0.gguf",
            sha256 = "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031",
            sizeBytes = 639_446_688L,
            gated = false,
            license = "Apache-2.0",
            format = ModelFormat.GGUF,
            tier = ModelTier.EXPERIMENTAL,
            minRamMb = 3072,
            descriptionRes = R.string.core_intel_llm_model_qwen3_06b_subtitle,
            reasoning = true,
        ),
        LocalLlmModel(
            id = "gguf-qwen2.5-0.5b",
            displayName = "Qwen 2.5 0.5B (GGUF)",
            params = "0.5B",
            repo = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
            revision = "9217f5db79a29953eb74d5343926648285ec7e67",
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
            sizeBytes = 491_400_032L,
            gated = false,
            license = "Apache-2.0",
            format = ModelFormat.GGUF,
            tier = ModelTier.EXPERIMENTAL,
            minRamMb = 3072,
            descriptionRes = R.string.core_intel_llm_model_qwen25_05b_subtitle,
        ),
        LocalLlmModel(
            id = "gguf-smolvlm-500m",
            displayName = "SmolVLM 500M (GGUF)",
            params = "500M",
            repo = "ggml-org/SmolVLM-500M-Instruct-GGUF",
            revision = "72e986006ef53e37cdd3f6d4241c90b0f01df376",
            fileName = "SmolVLM-500M-Instruct-Q8_0.gguf",
            sha256 = "9d4612de6a42214499e301494a3ecc2be0abdd9de44e663bda63f1152fad1bf4",
            sizeBytes = 436_806_912L,
            gated = false,
            license = "Apache-2.0",
            format = ModelFormat.GGUF,
            tier = ModelTier.EXPERIMENTAL,
            minRamMb = 3072,
            descriptionRes = R.string.core_intel_llm_model_gguf_smolvlm_500m_subtitle,
            vision = true,
            projector = ModelProjector(
                fileName = "mmproj-SmolVLM-500M-Instruct-Q8_0.gguf",
                sha256 = "d1eb8b6b23979205fdf63703ed10f788131a3f812c7b1f72e0119d5d81295150",
                sizeBytes = 108_783_360L,
            ),
        ),
        LocalLlmModel(
            id = "gguf-smollm2-360m",
            displayName = "SmolLM2 360M (GGUF)",
            params = "360M",
            repo = "HuggingFaceTB/SmolLM2-360M-Instruct-GGUF",
            revision = "593b5a2e04c8f3e4ee880263f93e0bd2901ad47f",
            fileName = "smollm2-360m-instruct-q8_0.gguf",
            sha256 = "48ab3034d0dd401fbc721eb1df3217902fee7dab9078992d66431f09b7750201",
            sizeBytes = 386_404_992L,
            gated = false,
            license = "Apache-2.0",
            format = ModelFormat.GGUF,
            tier = ModelTier.EXPERIMENTAL,
            minRamMb = 2048,
            descriptionRes = R.string.core_intel_llm_model_gguf_smollm2_360m_subtitle,
        ),
        LocalLlmModel(
            id = "gguf-gemma3-270m",
            displayName = "Gemma 3 270M (GGUF)",
            params = "270M",
            repo = "ggml-org/gemma-3-270m-it-GGUF",
            revision = "e7647be17ae1108f2f605ed061ca0608b171afff",
            fileName = "gemma-3-270m-it-Q8_0.gguf",
            sha256 = "0ef57d2c838458a1952664260dcba38e5bdda37494f3af732f06e4add24068e3",
            sizeBytes = 291_545_600L,
            gated = false,
            license = "Gemma Terms of Use",
            format = ModelFormat.GGUF,
            tier = ModelTier.EXPERIMENTAL,
            minRamMb = 2048,
            descriptionRes = R.string.core_intel_llm_model_gemma3_270m_subtitle,
        ),
    )

    init {
        check(models.map { it.id }.toSet().size == models.size) { "catalog ids must be unique" }
    }

    /** The commit of gemma-4-E2B-it-litert-lm the Tensor builds are pinned at (2026-11-11). */
    private const val GEMMA4_E2B_NPU_REVISION = "2d74ac41a33d7da82f39f7d5720a04c68514a103"

    /** The community repository of Gemma 3 1B for Tensor G3 and G4, and its commit (2026-11-11). */
    private const val TENSOR_GEMMA3_REPO = "litert-community/Gemma3-1B-IT-Tensor-NPU"
    private const val TENSOR_GEMMA3_REVISION = "1e14d0b91070f0fab29071364412299b688e5b68"

    fun byId(id: String): LocalLlmModel? = models.firstOrNull { it.id == id }

    /** [model]'s build for the NPU of [soc], or null when it has none. */
    fun npuBuild(model: LocalLlmModel, soc: String?): NpuBuild? =
        soc?.let { chip -> model.npuBuilds.firstOrNull { it.soc == chip } }

    /**
     * The file a download of [model] fetches, and its size: the NPU build for
     * [npuSoc] when one is asked for and exists, the standard file otherwise.
     */
    fun downloadTarget(model: LocalLlmModel, npuSoc: String?): Pair<String, Long> =
        npuBuild(model, npuSoc)?.let { it.fileName to it.sizeBytes } ?: (model.fileName to model.sizeBytes)

    /** What downloading [model] costs in all, for the user: [downloadTarget] plus a vision model's projector. */
    fun downloadSize(model: LocalLlmModel, npuSoc: String?): Long =
        npuBuild(model, npuSoc)?.sizeBytes ?: model.downloadBytes

    /**
     * Whether the model behind a selection id can read a picture: true or
     * false for a catalog model, null for an imported file (`custom:<name>`),
     * whose capability nothing on disk declares. A caller treats null as
     * "try it": the engine says so cleanly when it cannot.
     */
    fun canSee(id: String?): Boolean? {
        if (id.isNullOrBlank()) return null
        // An imported GGUF sees when a projector sits beside it, which only
        // the engine looks for: null, like any other imported file.
        if (id.startsWith("custom:")) return null
        return byId(id)?.vision
    }

    /** The runtime behind a selection id: a catalog row's, or an imported file's by its extension. */
    fun runtimeOf(id: String?): LocalLlmRuntime? {
        if (id.isNullOrBlank()) return null
        if (id.startsWith("custom:")) {
            val extension = id.substringAfterLast('.', "")
            return ModelFormat.entries.firstOrNull { it.extension.equals(extension, ignoreCase = true) }?.runtime
        }
        return byId(id)?.runtime
    }

    /**
     * Where [fileName] of [model] is fetched from: the model's repository at
     * its revision, or an NPU build's own repository and commit when it has
     * them. A build in its own repository with no commit of its own is
     * fetched from that repository's main branch, never the model's commit,
     * which names nothing there.
     */
    fun downloadUrl(model: LocalLlmModel, fileName: String = model.fileName): String {
        val build = model.npuBuilds.firstOrNull { it.fileName == fileName }
        val repo = build?.repo ?: model.repo
        val revision = build?.revision ?: if (build?.repo != null) "main" else model.revision
        return "https://huggingface.co/$repo/resolve/$revision/$fileName"
    }

    /** The repo page: the licence text, and where a gated model's terms are accepted. */
    fun licenseUrl(model: LocalLlmModel): String = "https://huggingface.co/${model.repo}"
}
