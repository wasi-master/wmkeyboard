package com.wasimaster.wmkeyboard.core.settings.remote

import android.content.Context
import com.wasimaster.wmkeyboard.core.files.RemoteEntry
import com.wasimaster.wmkeyboard.core.files.RemoteFs
import com.wasimaster.wmkeyboard.core.settings.BackupLocation
import com.wasimaster.wmkeyboard.core.settings.DriveSpace
import com.wasimaster.wmkeyboard.core.settings.sink.DriveAuth
import com.wasimaster.wmkeyboard.core.settings.sink.DriveSink
import com.wasimaster.wmkeyboard.core.settings.sink.DriveTokenProvider
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.URLConnection
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * A Google Drive location as a [RemoteFs], in whichever space the location
 * writes its backups to ([BackupLocation.driveSpace]):
 *
 * - [DriveSpace.APP_DATA]: the top is the app's hidden `appDataFolder`, the
 *   only thing the `drive.appdata` scope can see. Nothing there shows in the
 *   Drive app; only this app (on any phone signed in to the account) reads it.
 * - [DriveSpace.FOLDER]: the top is My Drive, but the `drive.file` scope
 *   shows only the files and folders this app made itself (the backup folder
 *   and anything made here). The user's own files are not there to see, and
 *   so cannot be changed from here either.
 *
 * Drive addresses files by id and lets one folder hold several things of the
 * same name, while the Files tool addresses them by name. A listing shows
 * every one of them with its id as [RemoteEntry.key], and the Files tool
 * gives each its own path from that ([pick]). A path that names one of
 * several, on the way down or at the end, goes to the picked id only, or to
 * nothing once that one is gone; with no pick it is refused. Nothing here
 * ever picks one of two by itself. Names are compared exactly, case
 * included, as Drive does.
 *
 * Folder ids are kept for the session, from the top down, and forgotten
 * under any path this changes. An upload makes a new file whatever the name
 * (Drive never writes over one), through a resumable session read from the
 * file in pieces, and the file appears only when the last piece is in.
 * Deleting a file moves it to the Drive trash in My Drive; in `appDataFolder`,
 * which has no trash, it is deleted outright, as the backup's own rotation
 * does there.
 */
class DriveFs(@Suppress("UNUSED_PARAMETER") context: Context, location: BackupLocation) : RemoteFs {

    private val provider: DriveTokenProvider = DriveAuth.provider ?: throw RemoteAdapters.Unavailable("no Google Play services")

    private val space: DriveSpace = location.driveSpace
    private val scope: String = DriveSink.scopeFor(space)

    /** Drive's own names for the two tops, which it accepts wherever an id goes. */
    private val rootId: String = if (space == DriveSpace.APP_DATA) "appDataFolder" else "root"
    private val spaces: String = if (space == DriveSpace.APP_DATA) "appDataFolder" else "drive"

    /** One file or folder as found: its id, whether it is a folder, and its parents. */
    private data class Item(val id: String, val folder: Boolean, val parents: List<String>, val json: JsonObject)

    /** Folder path to the folder's id, this session only. Never holds a "nothing there". */
    private val folders = HashMap<List<String>, String>()

    private var token: String? = null
    private var tokenAtMs = 0L

    private val guard = UploadGuard()

    /** The id each twin path means, for the call now running. */
    private var picks: Map<List<String>, String> = emptyMap()

    override fun pick(picks: Map<List<String>, String>) {
        this.picks = picks
    }

    override val atomicUpload: Boolean get() = true

    /**
     * The provider's token, kept a while: asking Play services for it on
     * every request would be a round trip each time. A session lives only
     * minutes, well inside a token's hour.
     */
    private fun bearer(): String {
        val now = System.currentTimeMillis()
        token?.takeIf { now - tokenAtMs < TOKEN_REUSE_MS }?.let { return it }
        val fresh = runBlocking { provider.accessToken(scope) } ?: throw IOException("Google Drive is not authorised")
        token = fresh
        tokenAtMs = now
        return fresh
    }

    private fun authorized(url: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer ${bearer()}")

    // ---- Reading ---------------------------------------------------------------

    override fun list(dir: List<String>): List<RemoteEntry> {
        val parent = folderId(dir) ?: throw IOException("no such folder")
        return query("'${escape(parent)}' in parents and trashed = false", order = null, limit = Int.MAX_VALUE)
            .mapNotNull { entryOf(it.json) }
    }

    override fun stat(path: List<String>): RemoteEntry? {
        if (path.isEmpty()) {
            // One entry of the top proves the token and the space; the top itself is always a folder.
            query("'$rootId' in parents and trashed = false", order = null, limit = 1)
            return RemoteEntry("", isDirectory = true)
        }
        return lookup(path, want = null)?.let { entryOf(it.json) }
    }

    override fun download(path: List<String>, to: OutputStream) {
        val item = lookup(path, want = false) ?: throw IOException("nothing there")
        CloudFsHttp.execute(authorized("$FILES/${item.id}?alt=media").get().build(), idempotent = true).use { response ->
            // A Google Docs file has no bytes of its own and answers 403 here; it fails like any other read.
            if (!response.isSuccessful) throw CloudFsHttp.failure("drive download", response)
            CloudFsHttp.stream(response, to)
        }
    }

    // ---- Changing --------------------------------------------------------------

    /**
     * A resumable upload: the session is opened with the metadata (which
     * only ever creates a new file), then the bytes go in [CHUNK] pieces.
     * Drive answers each piece but the last with 308 and the range it holds,
     * and the next piece starts where Drive says, not where this thinks.
     */
    override fun upload(dir: List<String>, name: String, from: File, onSent: (Long) -> Unit) =
        guard.run(dir + name) { sendFile(dir, name, from, onSent) }

    private fun sendFile(dir: List<String>, name: String, from: File, onSent: (Long) -> Unit) {
        CloudFsHttp.checkNames(listOf(name))
        val parent = folderId(dir) ?: throw IOException("no such folder")
        val size = from.length()
        val mime = URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
        val metadata = buildJsonObject {
            put("name", JsonPrimitive(name))
            put("parents", JsonArray(listOf(JsonPrimitive(parent))))
        }
        val start = authorized("$UPLOAD?uploadType=resumable&fields=id")
            .header("X-Upload-Content-Type", mime)
            .header("X-Upload-Content-Length", size.toString())
            .post(metadata.toString().toRequestBody(CloudFsHttp.JSON_TYPE))
            .build()
        val session = CloudFsHttp.execute(start, idempotent = false).use { response ->
            if (!response.isSuccessful) throw CloudFsHttp.failure("drive upload start", response)
            response.header("Location")
        } ?: throw IOException("Drive gave no upload address")

        val counter = SentCounter(onSent)
        val type = mime.toMediaTypeOrNull()
        try {
            if (size == 0L) {
                // An empty file is the whole of its one piece.
                guard.finalRequest()
                if (put(authorized(session).put(ByteArray(0).toRequestBody(type)).build()) == null) return
                throw IOException("Drive did not finish an empty upload")
            }
            var offset = 0L
            var stalls = 0
            while (true) {
                val n = minOf(CHUNK, size - offset)
                val request = authorized(session)
                    .header("Content-Range", "bytes $offset-${offset + n - 1}/$size")
                    .put(FileSliceBody(from, offset, n, type, counter))
                    .build()
                if (offset + n == size) guard.finalRequest()
                val held = put(request) ?: return
                // Drive holds bytes 0..held-1; carry on from there.
                stalls = if (held <= offset) stalls + 1 else 0
                if (stalls >= MAX_STALLS || held >= size) throw IOException("Drive is not taking the upload")
                offset = held
            }
        } catch (failure: Throwable) {
            // Nothing was named yet; let the half-held upload go.
            runCatching { CloudFsHttp.client.newCall(Request.Builder().url(session).delete().build()).execute().close() }
            throw failure
        }
    }

    /** One piece of a resumable upload: null when the file is complete, else how many bytes Drive now holds. */
    private fun put(request: Request): Long? =
        CloudFsHttp.execute(request, idempotent = false).use { response ->
            when {
                response.isSuccessful -> null
                response.code == HTTP_RESUME_INCOMPLETE ->
                    // "bytes=0-N"; no Range at all means nothing was kept.
                    response.header("Range")?.substringAfterLast('-')?.trim()?.toLongOrNull()?.plus(1) ?: 0L
                else -> throw CloudFsHttp.failure("drive upload piece", response)
            }
        }

    override fun mkdir(dir: List<String>, name: String) {
        CloudFsHttp.checkNames(listOf(name))
        val parent = folderId(dir) ?: throw IOException("no such folder")
        val metadata = buildJsonObject {
            put("name", JsonPrimitive(name))
            put("mimeType", JsonPrimitive(FOLDER_MIME))
            put("parents", JsonArray(listOf(JsonPrimitive(parent))))
        }
        try {
            send(authorized("$FILES?fields=id").post(metadata.toString().toRequestBody(CloudFsHttp.JSON_TYPE)).build(), "drive mkdir")
        } finally {
            forget(dir + name)
        }
    }

    /**
     * The one thing of that name and kind goes: to the trash in My Drive,
     * or deleted outright in `appDataFolder`, which has none. A folder must be
     * empty first, because trashing or deleting one takes everything in it.
     */
    override fun delete(path: List<String>, isDirectory: Boolean) {
        if (path.isEmpty()) throw IOException("the top folder cannot be deleted")
        if (!isDirectory && guard.madeNothing(path)) return
        val item = lookup(path, want = isDirectory) ?: return
        try {
            if (item.folder && query("'${escape(item.id)}' in parents and trashed = false", order = null, limit = 1).isNotEmpty()) {
                throw IOException("the folder is not empty")
            }
            val request = if (space == DriveSpace.APP_DATA) {
                authorized("$FILES/${item.id}").delete().build()
            } else {
                authorized("$FILES/${item.id}?fields=id")
                    .patch(buildJsonObject { put("trashed", JsonPrimitive(true)) }.toString().toRequestBody(CloudFsHttp.JSON_TYPE))
                    .build()
            }
            CloudFsHttp.execute(request, idempotent = true).use { response ->
                if (!response.isSuccessful && response.code != HTTP_NOT_FOUND) throw CloudFsHttp.failure("drive delete", response)
            }
        } finally {
            forget(path)
        }
    }

    /** Drive renames by id and never replaces anything; another file of the new name would simply sit beside it. */
    override fun rename(path: List<String>, newName: String) {
        if (path.isEmpty()) throw IOException("the top folder cannot be renamed")
        CloudFsHttp.checkNames(listOf(newName))
        val item = lookup(path, want = null) ?: throw IOException("nothing there")
        try {
            val body = buildJsonObject { put("name", JsonPrimitive(newName)) }
            send(authorized("$FILES/${item.id}?fields=id").patch(body.toString().toRequestBody(CloudFsHttp.JSON_TYPE)).build(), "drive rename")
        } finally {
            forget(path)
            forget(path.dropLast(1) + newName)
        }
    }

    override fun move(path: List<String>, toDir: List<String>): Boolean {
        if (path.isEmpty()) return false
        val item = lookup(path, want = null) ?: throw IOException("nothing there")
        val dest = folderId(toDir) ?: throw IOException("no such folder")
        if (item.parents.isEmpty()) throw IOException("Drive did not say where it is")
        val url = "$FILES/${item.id}".toHttpUrl().newBuilder()
            .addQueryParameter("addParents", dest)
            .addQueryParameter("removeParents", item.parents.joinToString(","))
            .addQueryParameter("fields", "id")
            .build()
            .toString()
        try {
            send(authorized(url).patch("{}".toRequestBody(CloudFsHttp.JSON_TYPE)).build(), "drive move")
        } finally {
            forget(path)
            forget(toDir + path.last())
        }
        return true
    }

    // ---- Paths and ids ---------------------------------------------------------

    /** The id of the folder at [dir], walking down from the top by name; null when a step is missing. */
    private fun folderId(dir: List<String>): String? {
        CloudFsHttp.checkNames(dir)
        var at = rootId
        for (i in dir.indices) {
            val prefix = dir.subList(0, i + 1).toList()
            // A picked twin is looked up each time, never cached: the next
            // call may pick the other one of that name.
            at = if (picks[prefix] != null) {
                one(prefix, find(at, dir[i], want = true))?.id ?: return null
            } else {
                folders[prefix] ?: (one(prefix, find(at, dir[i], want = true))?.id?.also { folders[prefix] = it } ?: return null)
            }
        }
        return at
    }

    /** The one thing called the last of [path] in its folder: a folder when [want] is true, a file when false, either when null. */
    private fun lookup(path: List<String>, want: Boolean?): Item? {
        CloudFsHttp.checkNames(path)
        val parent = folderId(path.dropLast(1)) ?: return null
        return one(path, find(parent, path.last(), want))
    }

    /**
     * Which of [found], all of one name, [path] means: the picked id, or
     * nothing when it is no longer there; else the only one. Several and no
     * pick is refused, since any choice made here could be the wrong file.
     */
    private fun one(path: List<String>, found: List<Item>): Item? {
        picks[path]?.let { id -> return found.firstOrNull { it.id == id } }
        return when (found.size) {
            0 -> null
            1 -> found[0]
            else -> throw IOException("more than one item is called ${path.last()} there")
        }
    }

    private fun find(parent: String, name: String, want: Boolean?): List<Item> =
        query("name = '${escape(name)}' and '${escape(parent)}' in parents and trashed = false", order = null, limit = FIND_PAGE)
            // Matched again here so the comparison is exact whatever Drive's query does with case.
            .filter { it.json["name"]?.jsonPrimitive?.contentOrNull == name }
            .filter { want == null || it.folder == want }

    /** Every file matching [q] in this space, page after page, up to [limit]. */
    private fun query(q: String, order: String?, limit: Int): List<Item> {
        val out = ArrayList<Item>()
        var pageToken: String? = null
        do {
            val url = FILES.toHttpUrl().newBuilder()
                .addQueryParameter("q", q)
                .addQueryParameter("spaces", spaces)
                .addQueryParameter("pageSize", minOf(limit, PAGE).toString())
                .addQueryParameter("fields", "nextPageToken,files($FIELDS)")
                .apply { if (order != null) addQueryParameter("orderBy", order) }
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
                .toString()
            val page = CloudFsHttp.execute(authorized(url).get().build(), idempotent = true).use { response ->
                if (!response.isSuccessful) throw CloudFsHttp.failure("drive list", response)
                CloudFsHttp.jsonOf(response)
            }
            page["files"]?.jsonArray?.forEach { element ->
                val file = element.jsonObject
                val id = file["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val parents = file["parents"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                out += Item(id, file["mimeType"]?.jsonPrimitive?.contentOrNull == FOLDER_MIME, parents, file)
            }
            pageToken = page["nextPageToken"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
        } while (pageToken != null && out.size < limit)
        return out
    }

    /** Forgets the folder ids at [path] and under it, after anything that changes it. */
    private fun forget(path: List<String>) {
        folders.keys.removeAll { it.size >= path.size && it.subList(0, path.size) == path }
    }

    private fun send(request: Request, what: String) {
        CloudFsHttp.execute(request, idempotent = false).use { response ->
            if (!response.isSuccessful) throw CloudFsHttp.failure(what, response)
        }
    }

    private fun entryOf(file: JsonObject): RemoteEntry? {
        val name = file["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val id = file["id"]?.jsonPrimitive?.contentOrNull
        val modified = file["modifiedTime"]?.jsonPrimitive?.contentOrNull?.let { DriveSink.parseRfc3339(it) } ?: 0L
        return if (file["mimeType"]?.jsonPrimitive?.contentOrNull == FOLDER_MIME) {
            RemoteEntry(name, isDirectory = true, modifiedMs = modified, key = id)
        } else {
            // Drive sends the size as a string, and none at all for a Google Docs file.
            RemoteEntry(name, false, file["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: -1, modified, key = id)
        }
    }

    override fun close() {
        folders.clear()
        picks = emptyMap()
        token = null
    }

    private companion object {
        const val FILES = "https://www.googleapis.com/drive/v3/files"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        const val FIELDS = "id,name,mimeType,size,modifiedTime,parents"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val PAGE = 1000
        const val FIND_PAGE = 100
        const val HTTP_NOT_FOUND = 404
        const val HTTP_RESUME_INCOMPLETE = 308
        const val MAX_STALLS = 3
        const val TOKEN_REUSE_MS = 10 * 60_000L

        /** One resumable piece: a multiple of 256 KiB, as Drive asks. */
        const val CHUNK = 32L * 256 * 1024

        /** Drive's query language quotes with ' and escapes with a backslash. */
        fun escape(s: String): String = s.replace("\\", "\\\\").replace("'", "\\'")
    }
}
