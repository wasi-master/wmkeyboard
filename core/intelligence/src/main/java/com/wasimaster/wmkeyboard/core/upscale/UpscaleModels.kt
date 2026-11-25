package com.wasimaster.wmkeyboard.core.upscale

import com.wasimaster.wmkeyboard.core.imagegen.ImageGenModels
import com.wasimaster.wmkeyboard.core.localllm.NpuSupport
import java.io.File

/** What a picture is, which decides the network that enlarges it. */
enum class UpscaleKind {
    /** Stickers, drawings, cartoons, screenshots of text: flat colour and hard lines. */
    ART,

    /** Camera pictures: texture, noise, soft focus. */
    PHOTO,
}

/**
 * The upscaler's networks: one per [UpscaleKind], each a pinned `.mnn` file
 * from the data repository's `models` release (`<sha256>-<name>`), exported by
 * its `scripts/export_upscalers_mnn.py` from nagadomi's own waifu2x weights.
 * They run on the sticker generator's MNN library (`libwmimagegen`, the
 * `imagegen` gate), so the upscaler brings no native code of its own.
 *
 * Both are waifu2x's UpConv7 (MIT): six 3x3 convolutions at the small
 * picture's size and one transposed convolution to twice it, trained to undo
 * light JPEG noise as they enlarge ("noise1"). The art network is trained on
 * illustrations, the photo network on photographs. ×4 is two passes.
 *
 * Chosen by measurement (2026-10-21, Apple M2, MNN 3.6.1 CPU, 4 threads)
 * against Real-ESRGAN's compact SRVGG networks (realesr-animevideov3,
 * realesr-general-x4v3), its RRDB anime network and waifu2x's CUNet and
 * Swin-UNet: the most faithful of all of them on stickers, line art and
 * photos (OpenMoji stickers ×4 with transparency 38.9 dB against 30.4 dB for
 * the next fastest, realesr-animevideov3), and the fastest at photo sizes
 * (1080p ×2 in 2.7 s against 11.8 s), with no invented texture. MNN's
 * conversions of CUNet and Swin-UNet draw wrong pixels; RRDB takes 25 times
 * as long. MNN also draws wrong pixels in float16 here, so float32 it is.
 *
 * Every file is checked against its SHA-256 before it is used: a network that
 * loads but is not the expected one would draw nonsense rather than fail.
 *
 * On a Snapdragon with an NPU the same two networks can run on its Hexagon
 * tensor processor instead ([NPU_BUILDS]): the same weights, compiled by
 * Qualcomm's QAIRT for one chip each (tools/qnn-context), run by the sticker
 * generator's QNN runner. Qualcomm AI Hub publishes no per-chip build of any
 * upscaler, only chip-independent ones that the phone would have to compile
 * itself, and its upscalers measured worse than these on photos (Real-ESRGAN
 * General x4v3 against UpConv7 photo, six photos, 2026-11-26: 24.1 to 29.2 dB
 * against 24.5 to 34.5 dB, often under plain bicubic).
 */
object UpscaleModels {

    enum class Model(
        val id: String,
        val kind: UpscaleKind,
        val part: ImageGenModels.Part,
    ) {
        ART(
            id = "waifu2x-upconv7-art",
            kind = UpscaleKind.ART,
            part = ImageGenModels.Part(
                "sr-waifu2x-upconv7-art.mnn",
                2_212_756L,
                "a01167275376f18e224a17cf24e2f1dcec5bb636d21d7bf460abd5451631dd84",
            ),
        ),
        PHOTO(
            id = "waifu2x-upconv7-photo",
            kind = UpscaleKind.PHOTO,
            part = ImageGenModels.Part(
                "sr-waifu2x-upconv7-photo.mnn",
                2_212_756L,
                "7a306a9e37f86d3a48ec9d17724771b4229ebaf64f694c8534ca7e162be5704d",
            ),
        ),
        ;

        companion object {
            fun of(kind: UpscaleKind): Model = entries.first { it.kind == kind }

            fun byId(id: String): Model? = entries.firstOrNull { it.id == id }
        }
    }

    /** What one pass enlarges by. */
    const val SCALE = 2

    /**
     * Pixels of the small picture the network reads around each tile and
     * eats: unpadded convolutions shrink the input by this much on every
     * side, so a tile read with this margin comes back exactly twice its size.
     */
    const val CONTEXT = 7

    /**
     * The tile's side, in pixels of the small picture. Per pixel a 128 tile
     * costs what a 256 one does (1.04 against 1.07 µs on the M2), and MNN's
     * working memory for it is 82 MB rather than 188.
     */
    const val TILE = 128

    /** The network's input and output, as the export names them. */
    const val INPUT = "image"
    const val OUTPUT = "upscaled"

    /**
     * One network compiled for one Snapdragon's NPU: a QNN context binary
     * whose graph takes one [NPU_INPUT] square, channels last, float16, and
     * answers twice its tile. A data repository `models` release asset like
     * the `.mnn` files.
     */
    class NpuBuild(val kind: UpscaleKind, val soc: String, val part: ImageGenModels.Part)

    /**
     * The pinned NPU builds, from tools/qnn-context's pins.txt. A kind and chip
     * with no entry here run on the CPU, as everything did before.
     */
    val NPU_BUILDS: List<NpuBuild> = listOf(
        // None published yet: .github/workflows/qnn-context.yml makes them.
    )

    /** An [NPU_BUILDS] entry: the file name follows from the kind and the chip. */
    fun npu(kind: UpscaleKind, soc: String, bytes: Long, sha256: String) = NpuBuild(
        kind,
        soc,
        ImageGenModels.Part("sr-waifu2x-upconv7-${kind.name.lowercase()}-${soc.lowercase()}.bin", bytes, sha256),
    )

    /** [kind]'s NPU build for [soc] (this phone's Snapdragon), or null. */
    fun npuBuild(kind: UpscaleKind, soc: String? = NpuSupport.qnnSoc): NpuBuild? =
        soc?.let { s -> NPU_BUILDS.firstOrNull { it.kind == kind && it.soc == s && it.part.sha256.isNotEmpty() } }

    /** The NPU graph's input side: a [TILE] tile and its [CONTEXT] on each side. */
    const val NPU_INPUT = TILE + 2 * CONTEXT

    fun npuFile(noBackupFilesDir: File, build: NpuBuild): File = File(dir(noBackupFilesDir), build.part.fileName)

    fun isDownloaded(noBackupFilesDir: File, build: NpuBuild): Boolean =
        npuFile(noBackupFilesDir, build).let { it.isFile && it.length() == build.part.bytes }

    /** The factors on offer. */
    val FACTORS = intArrayOf(2, 4)

    /** Beside the sticker generator's files, so its storage row counts these too. */
    fun dir(noBackupFilesDir: File): File = File(ImageGenModels.root(noBackupFilesDir), "upscale")

    fun file(noBackupFilesDir: File, model: Model): File = File(dir(noBackupFilesDir), model.part.fileName)

    fun isDownloaded(noBackupFilesDir: File, model: Model): Boolean =
        file(noBackupFilesDir, model).let { it.isFile && it.length() == model.part.bytes }
}
