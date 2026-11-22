package com.wasimaster.wmkeyboard.core.voice.executorch

import com.wasimaster.wmkeyboard.core.download.DownloadException
import com.wasimaster.wmkeyboard.core.download.FileDownloader
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.netlog.track
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A speech model fetched straight from a Hugging Face repository (#1437): the
 * user names the repository (`owner/name`, or its address, optionally a
 * revision and a folder in it), the keyboard lists its files, finds a model it
 * can run among them, and downloads just those files at the commit the listing
 * named, so nothing can change between the listing and the download. What it
 * finds is one of:
 *
 * - [Found.Qwen3]: a Qwen3-ASR export for sherpa-onnx (three graphs and the Qwen
 *   tokenizer), for Offline Qwen3-ASR.
 * - [Found.Sherpa]: a streaming transducer or Paraformer for sherpa-onnx, for
 *   Offline Sherpa.
 * - [Found.Whisper]: whisper.cpp ggml files, any one of which is a model of its
 *   own, for Offline Whisper.
 *
 * Installing is the importers' job, as for files picked on the phone:
 * [EtCustomModels] for the first two, the Whisper manager for the third. A file
 * the listing gives an LFS SHA-256 for is checked against it.
 */
object HuggingFaceModels {

    const val HOST = "https://huggingface.co"

    /** A repository, a revision (a branch, tag or commit) and a folder in it ("" for the top). */
    data class Ref(val repo: String, val revision: String = "main", val folder: String = "")

    /** One file of a repository at a commit. [sha256] is the LFS one, null for a small file kept in git. */
    data class RepoFile(val path: String, val bytes: Long, val sha256: String?) {
        val name: String get() = path.substringAfterLast('/')
    }

    /** A repository's files at [commit], the revision resolved. */
    data class Listing(val repo: String, val commit: String, val files: List<RepoFile>)

    /** A model found in a listing: the files to fetch for it. */
    sealed interface Found {
        val files: List<RepoFile>
        val bytes: Long get() = files.sumOf { it.bytes }

        data class Qwen3(override val files: List<RepoFile>) : Found
        data class Sherpa(override val files: List<RepoFile>) : Found

        /** Each of [files] is a whole model; the user picks one. */
        data class Whisper(override val files: List<RepoFile>) : Found
    }

    private val OWNER_NAME = Regex("""[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*""")

    /**
     * What the user typed, read as a repository: `owner/name`, the same with
     * `huggingface.co/` in front (with or without `https://`), or a page of
     * it (`…/tree/<revision>/<folder>`, `…/blob/<revision>/<file>`). Null for
     * anything else, such as a dataset or a Space.
     */
    fun parse(input: String): Ref? {
        var text = input.trim().substringBefore('?').substringBefore('#').trimEnd('/')
        text = text.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        text = text.removePrefix("huggingface.co/").removePrefix("hf.co/")
        if (text.startsWith("datasets/") || text.startsWith("spaces/")) return null
        val parts = text.split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val repo = "${parts[0]}/${parts[1]}"
        if (!OWNER_NAME.matches(repo)) return null
        if (parts.size == 2) return Ref(repo)
        val kind = parts[2]
        if ((kind != "tree" && kind != "blob" && kind != "resolve") || parts.size < 4) return null
        val rest = parts.drop(4)
        // A file's page names the file; its folder is what is wanted.
        val folder = (if (kind == "tree") rest else rest.dropLast(1)).joinToString("/")
        return Ref(repo, parts[3], folder)
    }

    /** Where Hugging Face lists [ref]'s files with their sizes and LFS hashes. */
    fun listingUrl(ref: Ref): String = "$HOST/api/models/${ref.repo}/revision/${encode(ref.revision)}?blobs=true"

    /** Where a file of [repo] at [commit] downloads from. */
    fun fileUrl(repo: String, commit: String, path: String): String =
        "$HOST/$repo/resolve/$commit/${path.split('/').joinToString("/") { encode(it) }}"

    private fun encode(segment: String): String = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    /** The model-info answer [listingUrl] gives, read. Throws [IOException] for one it cannot read. */
    fun parseListing(repo: String, json: String): Listing {
        val root = try {
            Json.parseToJsonElement(json).jsonObject
        } catch (e: IllegalArgumentException) {
            throw IOException("not a model listing", e)
        }
        val commit = root["sha"]?.jsonPrimitive?.content ?: throw IOException("no commit in the listing")
        val files = root["siblings"]?.jsonArray.orEmpty().mapNotNull { element ->
            val file = element as? JsonObject ?: return@mapNotNull null
            val path = file["rfilename"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val lfs = file["lfs"] as? JsonObject
            val bytes = file["size"]?.jsonPrimitive?.longOrNull ?: lfs?.get("size")?.jsonPrimitive?.longOrNull
                ?: return@mapNotNull null
            RepoFile(path, bytes, lfs?.get("sha256")?.jsonPrimitive?.content)
        }
        return Listing(repo, commit, files)
    }

    /** What models the keyboard can run among [listing]'s files under [folder], best fit first. */
    fun find(listing: Listing, folder: String = ""): List<Found> {
        val prefix = if (folder.isEmpty()) "" else "${folder.trimEnd('/')}/"
        val inFolder = listing.files.filter { it.path.startsWith(prefix) }
        // A name that repeats under the folder (test clips, a copy in a
        // subfolder) counts once, the copy nearest the top.
        val byName = inFolder.sortedBy { it.path.count { c -> c == '/' } }.distinctBy { it.name }
        val names = byName.map { it.name }
        fun pick(wanted: List<String>) = wanted.map { n -> byName.first { it.name == n } }
        return buildList {
            if (EtCustomModels.isQwen3(names)) {
                EtCustomModels.qwen3Parts(names)?.let { add(Found.Qwen3(pick(it))) }
            } else {
                EtCustomModels.parts(names)?.let { add(Found.Sherpa(pick(it))) }
            }
            val ggml = inFolder.filter(::looksGgml).sortedBy { it.bytes }
            if (ggml.isNotEmpty()) add(Found.Whisper(ggml))
        }
    }

    /**
     * A file that may be a whisper.cpp model: a `.bin` with ggml or whisper in
     * its name. The Whisper importer reads its header before taking it.
     */
    private fun looksGgml(file: RepoFile): Boolean {
        val name = file.name.lowercase()
        return name.endsWith(".bin") && ("ggml" in name || "whisper" in name) && file.bytes > MIN_GGML_BYTES
    }

    /** Whisper.cpp's smallest model, Tiny at q5_0, is about 32 MB; nothing that small is a model. */
    private const val MIN_GGML_BYTES = 8L shl 20

    /**
     * Fetches the file listing of [ref]'s repository. [token] is the
     * user's Hugging Face token, sent to huggingface.co only, for a gated or
     * private repository; blank for none. Throws [DownloadException.Http] for
     * a status that is not a success and [IOException] for the network.
     */
    suspend fun list(ref: Ref, token: String = ""): Listing = withContext(Dispatchers.IO) {
        val url = listingUrl(ref)
        NetLog.call(NetSource.DOWNLOAD_WHISPER, "GET", url, route = "/api/models").track { call ->
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("User-Agent", FileDownloader.USER_AGENT)
                connection.setRequestProperty("Accept", "application/json")
                if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer ${token.trim()}")
                val status = connection.responseCode
                call.status = status
                if (status !in 200..299) throw DownloadException.Http(status)
                val body = call.countIn(connection.inputStream).use { it.readBytes() }
                parseListing(ref.repo, String(body, Charsets.UTF_8))
            } finally {
                connection.disconnect()
            }
        }
    }

    private const val TIMEOUT_MS = 20_000

    /**
     * Downloads [files] of [listing]'s repository at its commit into [dir],
     * each under its own name, resuming what an earlier try left. A file with
     * an LFS hash is checked against it; one without is checked for its
     * length. [onBytes] hears the bytes done of [total] a few times a second.
     * Returns the files in [files]' order. Throws a [DownloadException] the
     * caller can name, an [IOException] for the network.
     */
    suspend fun download(
        dir: File,
        listing: Listing,
        files: List<RepoFile>,
        token: String = "",
        onBytes: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): List<File> {
        dir.mkdirs()
        val total = files.sumOf { it.bytes }
        val headers = if (token.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${token.trim()}")
        var base = 0L
        return files.map { file ->
            val target = File(dir, file.name)
            if (!(target.isFile && target.length() == file.bytes)) {
                val url = fileUrl(listing.repo, listing.commit, file.path)
                val progress: (Long) -> Unit = { written -> onBytes(base + written, total) }
                val sha = file.sha256
                if (sha != null) {
                    FileDownloader.fetchVerified(
                        url, target, file.bytes, sha, NetSource.DOWNLOAD_WHISPER,
                        what = "${listing.repo}/${file.path}", headers = headers, onBytes = progress,
                    )
                } else {
                    val part = File(dir, "${file.name}.part")
                    FileDownloader.fetch(url, part, file.bytes, NetSource.DOWNLOAD_WHISPER, headers = headers, onBytes = progress)
                    target.delete()
                    if (!part.renameTo(target)) throw IOException("could not move ${file.name} into place")
                    FileDownloader.rangesOf(part).delete()
                }
            }
            base += file.bytes
            onBytes(base, total)
            target
        }
    }
}
