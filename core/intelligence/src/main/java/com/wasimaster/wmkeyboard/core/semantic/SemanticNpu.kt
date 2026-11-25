package com.wasimaster.wmkeyboard.core.semantic

import android.content.Context
import com.wasimaster.wmkeyboard.core.localllm.AcceleratorVerdicts
import com.wasimaster.wmkeyboard.core.localllm.NpuSupport
import com.wasimaster.wmkeyboard.core.modules.NpuModelBuild
import com.wasimaster.wmkeyboard.core.modules.NpuModelDownloads
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import java.io.File

/**
 * Search by meaning's text encoder on the NPU: Google's own ahead-of-time
 * builds of EmbeddingGemma 2 text 270M (Apache-2.0), one per chip, in
 * litert-community's `embeddinggemma-2-text-270m-litert-lm`, the same
 * repository and commit [SemanticModels]' CPU encoder was repackaged from.
 * The vectors land in the same space, so nothing is embedded again.
 *
 * Each build is a LiteRT-LM bundle; the text encoder is one section of it,
 * a plain `.tflite` whose weights are inside it (no external buffer group),
 * with the CPU encoder's signatures (`encoder_1x128`, `_256`, `_512`, and a
 * `_1024` that is never run), inputs `embeddings` [1, n, 512] and
 * `input_mask` [1, n] and output `encodings` [1, 768], all float32: read from
 * every listed file's header, 2026-11-26. The bundle is checked whole against
 * its published SHA-256, then only that section is kept. The token table and
 * the tokenizer stay the text pack's.
 *
 * The pictures stay on the CPU: their NPU bundles are another 450 to 800 MB.
 */
object SemanticNpu {

    const val REPO = "litert-community/embeddinggemma-2-text-270m-litert-lm"
    const val REVISION = "9be6e8b90982095dc05c2bd162e4b954ee4dbac7"

    /** The text encoder section of each bundle, by [NpuSupport.normalizeSoc] chip. */
    val BUILDS: List<NpuModelBuild> = listOf(
        bundle("SM8550", "Qualcomm_SM8550", 207_896_576L, "6a5043146940e738cfa9111aa445b1cdb3dc7bbef30caef367622f14ada1a895", 7_897_088L, 140_775_152L),
        bundle("SM8650", "Qualcomm_SM8650", 207_798_272L, "7dabcb17eed982d6de4435aaf29cc56147a627f6ab20a1e37aadd7af5399457b", 7_897_088L, 140_685_040L),
        bundle("SM8750", "Qualcomm_SM8750", 187_596_800L, "b5615709768db4ca4fb4f04514e7e41b0680ccb12dd5f90371193f7fd12e7c99", 7_897_088L, 120_479_472L),
        bundle("SM8850", "Qualcomm_SM8850", 202_293_248L, "72992b649c812ae9e30ffa9ad88fc662e649f7b643500d28051fbd457ad0d22a", 7_897_088L, 135_184_112L),
        bundle("MT6991", "MediaTek_MT6991", 416_350_208L, "7acc8e334d882216207c23b7a477179d5af530d67fd8e2cb50ab42d3457f90c0", 7_897_088L, 349_227_256L),
        bundle("MT6993", "MediaTek_MT6993", 385_925_120L, "eff006451073498117f454c7ab27f274b5fe14a54af49be239e8f480ee49fa36", 7_897_088L, 318_807_404L),
        bundle("TENSOR-G5", "Google_Tensor_G5", 166_007_264L, "f066663433a31a8228b46f3271d2714a3b72a341ed4563cbc6d90f2e3dd0162d", 75_005_952L, 166_007_264L),
        bundle("TENSOR-G6", "Google_Tensor_G6", 199_532_256L, "4cd282395a7b611ec8dec95160cd31a1f471a7b1cde1fc71717aa539c2a29883", 75_005_952L, 199_532_256L),
    )

    /** One [AcceleratorVerdicts.key] per line: builds that failed on this phone's NPU. */
    const val FAILED_FILE = "semantic_npu_failed"
    private const val ATTEMPT_FILE = "semantic_npu_attempt"

    /** Used by [EmbeddingEngine] only, on its one thread. */
    internal val verdicts = AcceleratorVerdicts(FAILED_FILE, ATTEMPT_FILE)

    /** This phone's build, or null when its chip has none (or it has no NPU runtime at all). */
    fun build(soc: String? = NpuSupport.soc): NpuModelBuild? = soc?.let { s -> BUILDS.firstOrNull { it.soc == s } }

    /** Where [soc]'s encoder is kept, beside the CPU pack. */
    fun file(noBackupFilesDir: File, soc: String): File =
        File(SemanticModels.dir(noBackupFilesDir), "npu/eg2-text-encoder-${soc.lowercase()}.tflite")

    /** This phone's encoder when it is on the phone, or null. */
    fun downloadedFile(noBackupFilesDir: File, soc: String? = NpuSupport.soc): File? =
        soc?.takeIf { build(it) != null }?.let { file(noBackupFilesDir, it) }?.takeIf { it.isFile }

    fun start(context: Context) {
        val build = build() ?: return
        NpuModelDownloads.start(build, file(context.noBackupFilesDir, build.soc), NetSource.DOWNLOAD_SEMANTIC)
    }

    /** Deletes every chip's encoder: only this phone's is ever fetched, but a restored backup could hold another. */
    fun delete(context: Context) {
        for (build in BUILDS) NpuModelDownloads.delete(file(context.noBackupFilesDir, build.soc))
    }

    fun bytesOnDisk(noBackupFilesDir: File): Long = BUILDS.sumOf { NpuModelDownloads.bytesOnDisk(file(noBackupFilesDir, it.soc)) }

    /** Whether this phone's encoder failed on its NPU, as stored; the engine then runs the CPU encoder. */
    fun failedHere(context: Context): Boolean {
        val file = downloadedFile(context.noBackupFilesDir) ?: return false
        return AcceleratorVerdicts.key(context, file) in AcceleratorVerdicts.storedFailures(context, FAILED_FILE)
    }

    private fun bundle(soc: String, name: String, bytes: Long, sha256: String, start: Long, end: Long) = NpuModelBuild(
        soc = soc,
        url = "https://huggingface.co/$REPO/resolve/$REVISION/embeddinggemma-2-text-270m_$name.litertlm",
        bytes = bytes,
        sha256 = sha256,
        sectionStart = start,
        sectionEnd = end,
    )
}
