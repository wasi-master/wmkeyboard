package com.wasimaster.wmkeyboard.core.voice.whisper

import android.content.Context
import com.wasimaster.wmkeyboard.core.localllm.AcceleratorVerdicts
import com.wasimaster.wmkeyboard.core.modules.LitertNpu
import com.wasimaster.wmkeyboard.core.modules.NpuModelBuild
import com.wasimaster.wmkeyboard.core.modules.NpuModelDownloads
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import java.io.File

/**
 * Whisper Tiny on the NPU: litert-community's ahead-of-time builds of
 * openai/whisper-tiny (Apache-2.0), one per chip, run by LiteRT's
 * CompiledModel in place of the catalog's DocWolle graph ([MODEL_ID]).
 *
 * The graphs are not DocWolle's: there the whole generation is traced inside
 * one signature, here `encode` takes the log-mel spectrogram ([1, 80, 3000]
 * float32) and answers the audio features ([1, 1500, 384]), and `decode`
 * takes those, 128 token ids and a [1, 1, 128, 128] additive attention mask
 * and answers the logits of every position ([1, 128, 51865]), with no cache:
 * the loop is ours ([WhisperRuntime.transcribeNpu]). Read from every listed
 * Snapdragon file's header and from the whole MT6991 file, 2026-11-26; the
 * MT6989 and MT6993 builds come from the same export and were not opened.
 * The mel filterbank and the token bytes are Whisper Tiny's own vocab file,
 * the one the DocWolle graph already downloaded beside it.
 *
 * No Tensor build exists upstream, so Tensor phones keep the CPU.
 */
object WhisperNpu {

    /** The catalog model these builds stand in for. */
    const val MODEL_ID = "tiny-multi"

    const val REPO = "litert-community/whisper-tiny"
    const val REVISION = "9d0bc311c487b282cd37dc846a0a75624e0bcd74"

    val BUILDS: List<NpuModelBuild> = listOf(
        aot("SM8550", "Qualcomm_SM8550", 119_949_648L, "c00cdd5ced2c12682754a89069e32dff3ca465e5aa648ec1d0b717330a8ee207"),
        aot("SM8650", "Qualcomm_SM8650", 119_859_536L, "e72a316010b502e9607f53ad76eb6097b445d1272272611203b8638af04336e0"),
        aot("SM8750", "Qualcomm_SM8750", 119_916_880L, "6280afec96131a07a1af111684380bf1b6bfaf3fbba2001433e9205d3d887144"),
        aot("SM8850", "Qualcomm_SM8850", 120_109_392L, "67207435852ac403b90dcb60f57631526f528ceb88ff074993816c77a2f7e147"),
        aot("MT6989", "MediaTek_MT6989", 155_634_208L, "fcc46116d520b7514e1d423e740e9703d5893f9c455f49a7acd130ef1cf58796"),
        aot("MT6991", "MediaTek_MT6991", 155_717_804L, "539386c03dcac03f1409d672a9805973f5afc93a6f1d0e0d808570d16d111aa9"),
        aot("MT6993", "MediaTek_MT6993", 155_720_152L, "8b0d0267c6c874705eff82124cebb7cabd7a843e775973bf15c5f650a59efb26"),
    )

    /** One [AcceleratorVerdicts.key] per line: builds that failed on this phone's NPU. */
    const val FAILED_FILE = "whisper_npu_failed"
    private const val ATTEMPT_FILE = "whisper_npu_attempt"

    private val verdicts = AcceleratorVerdicts(FAILED_FILE, ATTEMPT_FILE)

    /** This phone's build, or null when its chip has none. */
    fun build(soc: String? = LitertNpu.soc()): NpuModelBuild? =
        soc?.let { s -> BUILDS.firstOrNull { it.soc == s } }

    /** Where [soc]'s build is kept: beside the CPU graph, so deleting the model deletes it too. */
    fun file(filesDir: File, soc: String): File {
        val model = requireNotNull(WhisperCatalog.byId(MODEL_ID))
        return File(WhisperStore.modelDir(filesDir, model), "npu-${soc.lowercase()}.tflite")
    }

    fun start(context: Context) {
        val build = build() ?: return
        NpuModelDownloads.start(build, file(context.filesDir, build.soc), NetSource.DOWNLOAD_WHISPER)
    }

    /** Whether this phone's build failed on its NPU, as stored. */
    fun failedHere(context: Context): Boolean {
        val soc = LitertNpu.soc() ?: return false
        val target = file(context.filesDir, soc).takeIf { it.isFile } ?: return false
        return AcceleratorVerdicts.key(context, target) in AcceleratorVerdicts.storedFailures(context, FAILED_FILE)
    }

    /** What [WhisperEngine] runs instead of [modelFile]: the build, its dispatch directory, its verdict key. */
    data class Plan(val file: File, val dispatchDir: File, val key: String)

    /**
     * The NPU build to run for [modelFile], or null for the CPU: [modelFile]
     * is not Whisper Tiny's graph, this chip has no build or it is not
     * downloaded, it failed here before, or the runtime is not on the phone
     * yet (no verdict then: it is asked for, and the CPU runs this phrase).
     * A plan has its attempt recorded already: [confirm] or [markFailed] it.
     */
    @Synchronized
    fun plan(modelFile: File): Plan? {
        val context = LitertNpu.context ?: return null
        val soc = LitertNpu.soc() ?: return null
        if (build(soc) == null) return null
        val model = WhisperCatalog.byId(MODEL_ID) ?: return null
        if (modelFile.path != WhisperStore.modelFile(context.filesDir, model).path) return null
        val target = file(context.filesDir, soc).takeIf { it.isFile } ?: return null
        verdicts.load(context)
        val key = AcceleratorVerdicts.key(context, target)
        if (key in verdicts) return null
        val dispatch = LitertNpu.runtime() ?: return null
        verdicts.begin(context, key)
        return Plan(target, dispatch, key)
    }

    /** The build answered, or was let go of in an orderly way. */
    @Synchronized
    fun confirm() = verdicts.confirm()

    /** The build did not load or stopped: the CPU from now on, on this phone. */
    @Synchronized
    fun markFailed(key: String) {
        val context = LitertNpu.context ?: return
        verdicts.markFailed(context, key)
    }

    private fun aot(soc: String, name: String, bytes: Long, sha256: String) = NpuModelBuild(
        soc = soc,
        url = WhisperCatalog.downloadUrl(REPO, "whisper_tiny_30s_f32_$name.tflite", REVISION),
        bytes = bytes,
        sha256 = sha256,
    )
}
