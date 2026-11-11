package com.wasimaster.wmkeyboard.core.imagegen

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.wasimaster.wmkeyboard.core.localllm.NpuSupport
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.json.JSONObject

/**
 * Models the user brings: a bundle tools/imagegen/convert_sd.py wrote on a
 * computer, as a zip or as its files picked together. Recognised by its
 * files (`text_encoder.mnn`, `unet.mnn`, `vae_decoder.mnn`, `merges.txt`,
 * `wmimage.json`, any common prefix), then checked in the generator's own
 * process: each network must load and declare the tensors the generator
 * feeds it, in the shapes `wmimage.json` promises. Nothing is converted on
 * the phone.
 *
 * A Qualcomm AI Hub build comes the same way, as the zip it was downloaded
 * as or its files picked together: its three context binaries and
 * `metadata.json`, plus the CLIP tokenizer it does not ship. It carries no
 * `wmimage.json` — its schedule is the catalog's own — so it is recognised
 * by [NPU] before the MNN names, checked only as far as this phone's chip,
 * and left to the NPU to refuse a build compiled for another one. This is
 * the offline build's way to the NPU model, whose catalog download needs
 * the network it does not have.
 */
object ImageGenImport {

    /** The files a bundle needs, by the end of their names. */
    val REQUIRED = listOf("text_encoder.mnn", "unet.mnn", "vae_decoder.mnn", "merges.txt", "wmimage.json")

    /** A Qualcomm build's own files, by the end of their names. */
    val NPU = listOf(
        ImageGenModels.NPU_TEXT,
        ImageGenModels.NPU_UNET,
        ImageGenModels.NPU_VAE,
        ImageGenModels.NPU_METADATA,
    )

    /** Every name an import keeps: an MNN bundle's or a Qualcomm build's. */
    private val ACCEPTED = REQUIRED + NPU

    data class Imported(val id: String, val name: String, val bytes: Long)

    sealed interface Result {
        data class Ok(val model: Imported) : Result

        /** Some of [REQUIRED] were not among the files. */
        data class Missing(val files: List<String>) : Result

        /** The files are there but do not fit together; [reason] says how, in English. */
        data class Invalid(val reason: String) : Result

        data class Failed(val message: String) : Result
    }

    fun installed(context: Context): List<Imported> =
        ImageGenModels.importedRoot(context.noBackupFilesDir).listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.endsWith(".part") }
            .mapNotNull { dir ->
                // A Qualcomm build has no wmimage.json of its own: it runs on
                // the catalog's schedule, so only its name has to be read back.
                val spec = if (File(dir, ImageGenModels.NPU_UNET).isFile) {
                    ImageGenModels.npuSpec(dir.name)
                } else {
                    val json = MnnBackend.find(dir, "wmimage.json")?.readText() ?: return@mapNotNull null
                    ModelSpec.parse(dir.name, json) ?: return@mapNotNull null
                }
                Imported(dir.name, spec.name, dir.walkTopDown().filter { it.isFile }.sumOf { it.length() })
            }
            .sortedBy { it.name.lowercase() }

    fun delete(context: Context, id: String) {
        File(ImageGenModels.importedRoot(context.noBackupFilesDir), id).takeIf { it.isDirectory }?.deleteRecursively()
    }

    /** Copies the picked [uris] (one zip, or the bundle's files) in, checks them, and keeps them only if they pass. */
    suspend fun import(context: Context, uris: List<Uri>): Result {
        val root = ImageGenModels.importedRoot(context.noBackupFilesDir).apply { mkdirs() }
        val staging = File(root, "import-${System.currentTimeMillis()}.part")
        staging.mkdirs()
        try {
            val resolver = context.contentResolver
            val names = uris.map { displayName(context, it) }
            if (uris.size == 1 && names.single().lowercase().endsWith(".zip")) {
                resolver.openInputStream(uris.single())?.use { unzip(it, staging) } ?: return Result.Failed("could not read the file")
            } else {
                for ((uri, name) in uris.zip(names)) {
                    if (ACCEPTED.none { name.endsWith(it) }) continue
                    resolver.openInputStream(uri)?.use { input -> File(staging, name).outputStream().use { input.copyTo(it) } }
                }
            }
            // A Qualcomm build carries no wmimage.json, so it is told apart
            // from an MNN bundle by its UNet — the one file no MNN bundle has.
            if (MnnBackend.find(staging, ImageGenModels.NPU_UNET) != null) return importNpu(context, staging, root)
            val missing = REQUIRED.filter { suffix -> MnnBackend.find(staging, suffix) == null }
            if (missing.isNotEmpty()) return Result.Missing(missing)
            val json = MnnBackend.find(staging, "wmimage.json")!!.readText()
            val name = runCatching { JSONObject(json).optString("name") }.getOrNull().orEmpty()
            val id = idFor(name, MnnBackend.find(staging, "unet.mnn")!!)
            val spec = ModelSpec.parse(id, json) ?: return Result.Invalid("wmimage.json is not a format this version reads")
            ImageGen.verify(context, staging)?.let { return Result.Invalid(it) }
            val target = File(root, id)
            target.deleteRecursively()
            if (!staging.renameTo(target)) return Result.Failed("could not move the model into place")
            ensureSafety(context)
            return Result.Ok(Imported(id, spec.name, target.walkTopDown().filter { it.isFile }.sumOf { it.length() }))
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            return Result.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * A Qualcomm AI Hub build brought by hand: its three context binaries and
     * `metadata.json`, plus the CLIP tokenizer the zip does not carry. It is
     * kept as an imported model and run on this phone's NPU.
     *
     * There is nothing here to [ImageGen.verify]: a QNN context binary has no
     * tensors to read without loading it, and loading one is the first
     * picture's own business, exactly as it is for the catalog's download.
     */
    private suspend fun importNpu(context: Context, staging: File, root: File): Result {
        // Qualcomm's zip has no tokenizer, and the user should not have to
        // find one (#903): CLIP's is the same file for every model here, so
        // one already on the phone, SDXS's or another import's, comes along.
        if (MnnBackend.find(staging, "merges.txt") == null) {
            tokenizerOnPhone(context)?.let { it.copyTo(File(staging, "merges.txt")) }
        }
        val missing = (NPU + "merges.txt").filter { MnnBackend.find(staging, it) == null }
        if (missing.isNotEmpty()) return Result.Missing(missing)
        val soc = NpuSupport.qnnSoc ?: return Result.Invalid("this phone has no supported NPU")
        val metadata = MnnBackend.find(staging, ImageGenModels.NPU_METADATA)!!.readText()
        wrongSoc(metadata, soc)?.let { return Result.Invalid("that build is for $it, this phone's chip is $soc") }
        val name = ImageGenModels.Catalog.SD15_NPU.displayName
        val id = idFor(name, MnnBackend.find(staging, ImageGenModels.NPU_UNET)!!)
        val target = File(root, id)
        target.deleteRecursively()
        if (!staging.renameTo(target)) return Result.Failed("could not move the model into place")
        ensureSafety(context)
        return Result.Ok(Imported(id, name, target.walkTopDown().filter { it.isFile }.sumOf { it.length() }))
    }

    /**
     * A CLIP tokenizer already on the phone: the catalog's, downloaded with
     * SDXS or the NPU model, else any imported model's. Checked by size and
     * digest against the catalog's, so a damaged copy is not passed on.
     */
    private fun tokenizerOnPhone(context: Context): File? {
        val noBackup = context.noBackupFilesDir
        val part = ImageGenModels.Catalog.SDXS.parts.first { it.fileName.endsWith("merges.txt") }
        val candidates = ImageGenModels.Catalog.entries.map { ImageGenModels.file(noBackup, it, part) } +
            ImageGenModels.importedRoot(noBackup).listFiles().orEmpty()
                .filter { it.isDirectory && !it.name.endsWith(".part") }
                .mapNotNull { dir -> dir.listFiles().orEmpty().firstOrNull { it.name.endsWith("merges.txt") } }
        return candidates.firstOrNull { file ->
            file.isFile && file.length() == part.bytes && sha256(file).equals(part.sha256, ignoreCase = true)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * The chip a Qualcomm build's own `metadata.json` names, when it names one
     * this app knows and it is not [soc]; null when the file says nothing
     * readable (or agrees), so the build is taken at its word and the NPU
     * refuses a wrong one when it loads.
     */
    private fun wrongSoc(metadata: String, soc: String): String? {
        val upper = metadata.uppercase()
        val named = CHIPS.entries.firstOrNull { (token, _) -> token in upper }?.value ?: return null
        return named.takeIf { it != soc }
    }

    /**
     * The picture filter is fetched over the network. A build with no internet
     * (the offline build) or a download that fails still has a working model
     * without it, so this is never what turns an import away.
     */
    private suspend fun ensureSafety(context: Context) {
        runCatching { ImageGenDownloads.ensureSafety(context) }
    }

    /**
     * A chip token Qualcomm's own metadata may name, to the catalog's SoC id.
     * Ordered: the 8 Elite Gen 5's name contains the plain 8 Elite's.
     */
    private val CHIPS = linkedMapOf(
        "SM8650" to "SM8650",
        "SM8750" to "SM8750",
        "SM8850" to "SM8850",
        "SNAPDRAGON_8GEN3" to "SM8650",
        "SNAPDRAGON_8_ELITE_GEN5" to "SM8850",
        "SNAPDRAGON_8_ELITE" to "SM8750",
    )

    /**
     * Checks a bundle's three networks against [spec]; null when they fit,
     * else what is wrong. Runs in the generator's process (see [ImageGen.verify]).
     */
    internal fun check(dir: File, spec: ModelSpec): String? {
        if (!ImageGenNative.available) return "the generator's engine is not installed yet"
        fun describe(suffix: String): JSONObject? =
            MnnBackend.find(dir, suffix)?.let { ImageGenNative.nativeDescribe(it.absolutePath) }?.let(::JSONObject)
        fun inputs(o: JSONObject): Map<String, List<Int>> = o.getJSONArray("inputs").let { a ->
            (0 until a.length()).associate { i ->
                val t = a.getJSONObject(i)
                val d = t.getJSONArray("dims")
                t.getString("name") to List(d.length()) { d.getInt(it) }
            }
        }
        val text = describe("text_encoder.mnn") ?: return "text_encoder.mnn is not an MNN model"
        val unet = describe("unet.mnn") ?: return "unet.mnn is not an MNN model"
        val vae = describe("vae_decoder.mnn") ?: return "vae_decoder.mnn is not an MNN model"
        val ti = inputs(text)
        if (ti["ids"]?.lastOrNull() != ClipTokenizer.MAX_TOKENS) return "the text encoder does not take 77 token ids as \"ids\""
        val ui = inputs(unet)
        val need = if (spec.lcmDim > 0) listOf("x", "t", "e", "w") else listOf("x", "t", "e")
        need.firstOrNull { it !in ui }?.let { return "the UNet has no input \"$it\"" }
        if (ui.getValue("x").getOrNull(1) != LATENT_CHANNELS) return "the UNet's latent does not have 4 channels"
        val e = ui.getValue("e")
        if (e.lastOrNull() != spec.textDim) return "the UNet expects text width ${e.lastOrNull()}, wmimage.json says ${spec.textDim}"
        if (spec.lcmDim > 0 && ui.getValue("w").lastOrNull() != spec.lcmDim) return "the UNet's guidance input is not ${spec.lcmDim} wide"
        val vi = inputs(vae)
        if (vi["z"]?.getOrNull(1) != LATENT_CHANNELS) return "the decoder does not take a 4-channel latent as \"z\""
        return null
    }

    private const val LATENT_CHANNELS = 4

    private fun idFor(name: String, unet: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        unet.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            // The first 8 MB tell two models apart; hashing a gigabyte here would only cost time.
            var left = 8 shl 20
            while (left > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size, left))
                if (read < 0) break
                digest.update(buffer, 0, read)
                left -= read
            }
        }
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32).ifEmpty { "model" }
        return slug + "-" + digest.digest().take(4).joinToString("") { "%02x".format(it) }
    }

    private fun unzip(input: InputStream, into: File) {
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (entry.isDirectory || ACCEPTED.none { name.endsWith(it) }) continue
                File(into, name).outputStream().use { zip.copyTo(it) }
            }
        }
    }

    private fun displayName(context: Context, uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
}
