package com.wasimaster.wmkeyboard.core.semantic

import com.wasimaster.wmkeyboard.core.endpoints.GitForge
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo
import java.io.File

/**
 * The files behind search by meaning: Google's EmbeddingGemma 2
 * (Apache-2.0), repackaged for the classic LiteRT interpreter by
 * `scripts/repack_embeddinggemma2.py` in the data repository and published as
 * assets of its `models` release under `<sha256>-<name>`, the way native
 * libraries are under `natives` (see `NativeDownloader`).
 *
 * Two packs. [Pack.TEXT] is the tokenizer, the token table and the text
 * encoder: enough to find texts and emoji by meaning, and the half every
 * search needs, since queries are typed. [Pack.PICTURES] adds the vision
 * tower, which puts pictures into the same space, so a typed query finds a
 * sticker too. Its sizes and digests are the published files', checked
 * before a download is given its name, so the address can be any mirror.
 */
object SemanticModels {

    /** The data repository's release the files are assets of. */
    const val RELEASE_TAG = "models"

    /**
     * Whether this build can run the model at all. F-Droid's build has no
     * LiteRT interpreter, so it never offers a download it could not use.
     */
    val SUPPORTED: Boolean = com.wasimaster.wmkeyboard.config.BuildConfig.GOOGLE_SDKS

    enum class Pack { TEXT, PICTURES }

    enum class Part(val pack: Pack, val fileName: String, val bytes: Long, val sha256: String) {
        TOKENIZER(
            Pack.TEXT, "eg2-tokenizer.model", 4_689_013L,
            "e594c8a90eb08d8bda498ff4747977dc827ae0c3c56b5c0d41a605a22d02ef03",
        ),
        TOKENS(
            Pack.TEXT, "eg2-tokens.bin", 68_157_456L,
            "3bc62fc4be4c2c9bc5c9902e8b41a19e698a6b218314390fa2411f4242ae5160",
        ),
        TEXT_ENCODER(
            Pack.TEXT, "eg2-text-encoder.tflite", 79_039_928L,
            "305850a79ab1a38a8d3862f5ac7d9500a386ac969c4f9783da6d81237ead038b",
        ),

        /** The emoji catalogue, embedded off the device; see [EmojiVectors]. */
        EMOJI_VECTORS(
            Pack.TEXT, "eg2-emoji-vectors.bin", 2_648_117L,
            "63326576717ebaaa3d1e9da5ee040e90f7ea962876ebe3c0a655bcec468956d6",
        ),
        VISION_ENCODER(
            Pack.PICTURES, "eg2-vision-encoder.tflite", 218_970_136L,
            "b7f069019f953e00910ad4b4b5fb029180ea3faa4fe0fa30d6fc2d37198d908b",
        ),
        VISION_ADAPTER(
            Pack.PICTURES, "eg2-vision-adapter.tflite", 1_580_532L,
            "fe5d26b805e24163ac495ef269490c6572255559bd17881cd0f66ce2758dfe3b",
        ),
        IMAGE_END(
            Pack.PICTURES, "eg2-image-end.bin", 2_048L,
            "e6599ba28ac69c35da660a0f938b589ffc94d203bcbd6797ec46df71d7c5990a",
        ),
    }

    /**
     * Bumped whenever the model or the way a text is built for it changes, so
     * every stored vector is embedded again (see [MeaningSync.stampOf]).
     */
    const val VERSION = 1

    fun parts(pack: Pack): List<Part> = Part.entries.filter { it.pack == pack }

    fun bytes(pack: Pack): Long = parts(pack).sumOf { it.bytes }

    /** Where the files live. Not backed up: they come back with one download. */
    fun dir(noBackupFilesDir: File): File = File(noBackupFilesDir, "semantic/models")

    fun file(noBackupFilesDir: File, part: Part): File = File(dir(noBackupFilesDir), part.fileName)

    fun partFile(noBackupFilesDir: File, part: Part): File = File(dir(noBackupFilesDir), part.fileName + ".part")

    /** Whole when every file of [pack] is there at its pinned length; the digest was checked before it got its name. */
    fun isDownloaded(noBackupFilesDir: File, pack: Pack): Boolean = parts(pack).all { isDownloaded(noBackupFilesDir, it) }

    fun isDownloaded(noBackupFilesDir: File, part: Part): Boolean =
        file(noBackupFilesDir, part).let { it.isFile && it.length() == part.bytes }

    /** Every byte of both packs on disk now, and of the NPU encoder ([SemanticNpu]), for the storage screen. */
    fun bytesOnDisk(noBackupFilesDir: File): Long =
        Part.entries.sumOf { file(noBackupFilesDir, it).takeIf(File::isFile)?.length() ?: 0L } +
            SemanticNpu.bytesOnDisk(noBackupFilesDir)

    /** The release asset for [part], or null when the data repository is not on GitHub. */
    fun url(part: Part): String? {
        val repo = ServiceEndpoints.repo(ServiceRepo.DATA)
        if (repo.forge != GitForge.GITHUB) return null
        return "https://${repo.host}/${repo.owner}/${repo.repo}/releases/download/$RELEASE_TAG/${part.sha256}-${part.fileName}"
    }

    /** The part a file named [name] is, by its published name or its release asset name, or null. */
    fun partOf(name: String): Part? =
        Part.entries.firstOrNull { name == it.fileName || name == "${it.sha256}-${it.fileName}" }
}
