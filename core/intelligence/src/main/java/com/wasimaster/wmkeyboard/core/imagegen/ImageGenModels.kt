package com.wasimaster.wmkeyboard.core.imagegen

import com.wasimaster.wmkeyboard.core.endpoints.GitForge
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo
import com.wasimaster.wmkeyboard.core.localllm.NpuSupport
import com.wasimaster.wmkeyboard.core.modules.NativeConsent
import java.io.File
import org.json.JSONObject

/** Which runtime a model runs on. */
enum class ImageRuntime { MNN, QNN }

/**
 * Everything the generator needs to drive one model: what `wmimage.json`
 * says for an MNN bundle (written by tools/imagegen/convert_sd.py), or the
 * catalog's own values for an NPU build.
 */
data class ModelSpec(
    val id: String,
    val name: String,
    val runtime: ImageRuntime,
    val licence: String,
    val textDim: Int,
    /** The LCM guidance embedding's width, or 0 for an ordinary UNet. */
    val lcmDim: Int,
    val size: Int,
    val sizes: List<Int>,
    val steps: Int,
    /** Fixed timesteps (one-step models), or empty to derive them from [steps] and [spacing]. */
    val timesteps: IntArray,
    val guidance: Float,
    /** "euler" or "lcm". */
    val scheduler: String,
    /** "trailing" or "leading". */
    val spacing: String,
    val vPrediction: Boolean,
    val trainSteps: Int,
    val betaStart: Double,
    val betaEnd: Double,
    val betaSchedule: String,
    val pad: Int,
) {
    /** Whether guidance (and so a negative prompt) means anything: an ordinary UNet run with CFG. */
    val takesGuidance: Boolean get() = scheduler != "lcm" && lcmDim == 0 && timesteps.size != 1

    /** Whether the step count can be changed: not for a model distilled to fixed timesteps. */
    val adjustableSteps: Boolean get() = timesteps.isEmpty()

    fun schedule() = NoiseSchedule(betaStart, betaEnd, betaSchedule, trainSteps)

    fun sampler(steps: Int): Sampler {
        val schedule = schedule()
        return when (scheduler) {
            "lcm" -> LcmSampler(schedule, steps, vPrediction)
            else -> EulerSampler(
                schedule,
                if (timesteps.isNotEmpty()) timesteps else schedule.timesteps(steps, spacing),
                if (timesteps.isNotEmpty()) "trailing" else spacing,
                vPrediction,
            )
        }
    }

    companion object {
        /** Reads a bundle's `wmimage.json`; null when it is not one this app understands. */
        fun parse(id: String, json: String): ModelSpec? = runCatching {
            val o = JSONObject(json)
            if (o.optInt("format", 0) != 1 || o.optString("runtime", "mnn") != "mnn") return null
            val sizes = o.optJSONArray("sizes")?.let { a -> List(a.length()) { a.getInt(it) } }.orEmpty()
            val timesteps = o.optJSONArray("timesteps")?.let { a -> IntArray(a.length()) { a.getInt(it) } } ?: IntArray(0)
            val size = o.optInt("size", 512)
            ModelSpec(
                id = id,
                name = o.optString("name", id),
                runtime = ImageRuntime.MNN,
                licence = o.optString("licence", ""),
                textDim = o.getInt("text_dim"),
                lcmDim = o.optInt("lcm_dim", 0),
                size = size,
                sizes = sizes.ifEmpty { listOf(size) }.filter { it in MIN_SIZE..MAX_SIZE && it % 64 == 0 },
                steps = o.optInt("steps", 4).coerceIn(1, MAX_STEPS),
                timesteps = timesteps,
                guidance = o.optDouble("guidance", 0.0).toFloat(),
                scheduler = o.optString("scheduler", "euler"),
                spacing = o.optString("spacing", "trailing"),
                vPrediction = o.optString("prediction", "epsilon") == "v_prediction",
                trainSteps = o.optInt("train_steps", 1000),
                betaStart = o.optDouble("beta_start", 0.00085),
                betaEnd = o.optDouble("beta_end", 0.012),
                betaSchedule = o.optString("beta_schedule", "scaled_linear"),
                pad = o.optInt("pad", 49407),
            ).takeIf { it.textDim > 0 && it.sizes.isNotEmpty() }
        }.getOrNull()

        const val MIN_SIZE = 256
        const val MAX_SIZE = 768
        const val MAX_STEPS = 50
    }
}

/**
 * The sticker generator's models: the catalog the app offers, pinned by size
 * and SHA-256, and where everything lives on disk.
 *
 * - [Catalog.SDXS]: SDXS-512 Dreamshaper (IDKiro, CreativeML OpenRAIL++-M),
 *   one step, converted to MNN by tools/imagegen/convert_sd.py and published
 *   as assets of the data repository's `models` release (`<sha256>-<name>`).
 *   Runs on every phone's CPU.
 * - [Catalog.SD15_NPU]: Stable Diffusion 1.5 as Qualcomm AI Hub compiled it
 *   for one Snapdragon's Hexagon NPU (w8a16, QAIRT 2.50), fetched from
 *   Qualcomm's own bucket and never mirrored. One zip per chip.
 *
 * Plus [SAFETY]: the picture filter's classifier (Falconsai's NSFW ViT,
 * Apache-2.0), which every model downloads with it.
 */
object ImageGenModels {

    const val RELEASE_TAG = "models"

    /** One pinned file. [url] is absolute for a vendor's file, else a `models` release asset. */
    class Part(val fileName: String, val bytes: Long, val sha256: String, val url: String? = null)

    /** A Qualcomm AI Hub zip and the files it unpacks to. */
    class NpuBuild(val soc: String, val zip: Part, val entries: List<String>)

    enum class Catalog(
        val id: String,
        val displayName: String,
        val runtime: ImageRuntime,
        val licence: String,
        val parts: List<Part>,
        val npuBuilds: List<NpuBuild> = emptyList(),
    ) {
        SDXS(
            id = "sdxs-dreamshaper",
            displayName = "SDXS Dreamshaper",
            runtime = ImageRuntime.MNN,
            licence = NativeConsent.OPENRAIL_PP,
            parts = listOf(
                Part("sdxs-ds-wmimage.json", 489L, "6cc049f94e0b8c5f04c009df049dbdb8a017e43af6541621abe01e3835c056a0"),
                Part("sdxs-ds-merges.txt", 524_619L, "9fd691f7c8039210e0fced15865466c65820d09b63988b0174bfe25de299051a"),
                Part("sdxs-ds-text_encoder.mnn", 246_550_752L, "2c317b828bfe12f22b3a8dc51190b2e0a172ce1bcc412297399654543b3e5acb"),
                Part("sdxs-ds-unet.mnn", 632_280_940L, "ebf37f1404d4114b07939863f222f191b6ff75ba5c6e042c5c3c9e7e21c25d3e"),
                Part("sdxs-ds-vae_decoder.mnn", 2_465_260L, "06bb91f53739c42062559c726912d54aefde3ce14bf9fdfe6b901d37e11ed6a2"),
            ),
        ),
        SD15_NPU(
            id = "sd15-npu",
            displayName = "Stable Diffusion 1.5 (NPU)",
            runtime = ImageRuntime.QNN,
            licence = NativeConsent.OPENRAIL_M,
            // The tokenizer is CLIP's, the same file SDXS ships.
            parts = listOf(Part("sdxs-ds-merges.txt", 524_619L, "9fd691f7c8039210e0fced15865466c65820d09b63988b0174bfe25de299051a")),
            npuBuilds = listOf(
                aiHub(
                    "SM8650", "snapdragon_8gen3", 709_724_514L,
                    "0e9299c8b3344ddb6463eefcc9ab7dad232312dff42990badba5a5b379b153f8",
                ),
                aiHub(
                    "SM8750", "snapdragon_8_elite_for_galaxy", 709_785_222L,
                    "a895f6f96b441d55a3aed101d4c192dc20feabf888402772976a5f29b1871255",
                ),
                aiHub(
                    "SM8850", "snapdragon_8_elite_gen5_for_galaxy", 710_771_252L,
                    "9e2aade77178fb487dd929c88a60f830b2edd05a71bbe052798a253b87042c5b",
                ),
            ),
        ),
        ;

        /** The NPU build for this phone's chip, or null (no NPU, another chip, a CPU model). */
        fun npuBuild(soc: String? = NpuSupport.qnnSoc): NpuBuild? =
            soc?.let { s -> npuBuilds.firstOrNull { it.soc == s && it.zip.sha256.isNotEmpty() } }

        /** Whether this phone can run it at all. */
        val runsHere: Boolean get() = runtime == ImageRuntime.MNN || npuBuild() != null

        /** Every byte the model downloads, the safety classifier included unless it is already here. */
        fun downloadBytes(): Long = parts.sumOf { it.bytes } + (npuBuild()?.zip?.bytes ?: 0L)

        companion object {
            fun byId(id: String): Catalog? = entries.firstOrNull { it.id == id }
        }
    }

    val SAFETY = Part("nsfw-vit-falconsai.mnn", 87_454_824L, "be15cad824b0161ee2f91074a4cabfaea42e034ba761b0ee50b093736e721a70")

    /** The AI Hub model release the NPU builds are from. */
    private const val AI_HUB_RELEASE =
        "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/stable_diffusion_v1_5/releases/v0.64.0"

    private fun aiHub(soc: String, chip: String, bytes: Long, sha256: String): NpuBuild {
        val name = "stable_diffusion_v1_5-qnn_context_binary-w8a16-qualcomm_$chip.zip"
        return NpuBuild(soc, Part(name, bytes, sha256, "$AI_HUB_RELEASE/$name"), listOf(NPU_TEXT, NPU_UNET, NPU_VAE, NPU_METADATA))
    }

    const val NPU_TEXT = "text_encoder.bin"
    const val NPU_UNET = "unet.bin"
    const val NPU_VAE = "vae.bin"
    const val NPU_METADATA = "metadata.json"


    /**
     * The spec the NPU builds run with: Stable Diffusion 1.5's own schedule
     * ("leading" timesteps, never above 951, which the UNet's quantised
     * timestep input could not hold past 968) on Euler, 20 steps with
     * guidance, as Qualcomm's own demo runs it.
     */
    fun npuSpec(id: String = Catalog.SD15_NPU.id): ModelSpec = ModelSpec(
        id = id,
        name = Catalog.SD15_NPU.displayName,
        runtime = ImageRuntime.QNN,
        licence = NativeConsent.OPENRAIL_M,
        textDim = 768,
        lcmDim = 0,
        size = 512,
        sizes = listOf(512),
        steps = 20,
        timesteps = IntArray(0),
        guidance = 7.5f,
        scheduler = "euler",
        spacing = "leading",
        vPrediction = false,
        trainSteps = 1000,
        betaStart = 0.00085,
        betaEnd = 0.012,
        betaSchedule = "scaled_linear",
        pad = 49407,
    )

    /** Where everything lives. Not backed up: one download brings it back. */
    fun root(noBackupFilesDir: File): File = File(noBackupFilesDir, "imagegen")

    fun dir(noBackupFilesDir: File, model: Catalog): File = File(root(noBackupFilesDir), "models/${model.id}")

    fun importedRoot(noBackupFilesDir: File): File = File(root(noBackupFilesDir), "imported")

    fun safetyFile(noBackupFilesDir: File): File = File(root(noBackupFilesDir), SAFETY.fileName)

    /** A catalog part in its model's directory. */
    fun file(noBackupFilesDir: File, model: Catalog, part: Part): File = File(dir(noBackupFilesDir, model), part.fileName)

    /** Whether [model] is whole on disk for this phone: every part at its pinned length, and the NPU files unpacked. */
    fun isDownloaded(noBackupFilesDir: File, model: Catalog): Boolean {
        val dir = dir(noBackupFilesDir, model)
        if (!model.parts.all { File(dir, it.fileName).let { f -> f.isFile && f.length() == it.bytes } }) return false
        val npu = model.npuBuild() ?: return model.runtime == ImageRuntime.MNN
        return File(dir, npu.soc).let { d -> npu.entries.all { File(d, it).isFile } && File(d, STAMP).isFile }
    }

    fun safetyDownloaded(noBackupFilesDir: File): Boolean =
        safetyFile(noBackupFilesDir).let { it.isFile && it.length() == SAFETY.bytes }

    /** Marks an NPU build's directory as completely unpacked. */
    const val STAMP = ".complete"

    /** A `models` release asset, or null when the data repository is not on GitHub. */
    fun url(part: Part): String? {
        part.url?.let { return it }
        val repo = ServiceEndpoints.repo(ServiceRepo.DATA)
        if (repo.forge != GitForge.GITHUB) return null
        return "https://${repo.host}/${repo.owner}/${repo.repo}/releases/download/$RELEASE_TAG/${part.sha256}-${part.fileName}"
    }

    /** Every byte the generator keeps on disk, for the storage screen. */
    fun bytesOnDisk(noBackupFilesDir: File): Long =
        root(noBackupFilesDir).walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
