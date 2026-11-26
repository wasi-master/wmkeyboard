package com.wasimaster.wmkeyboard.core.settings.remote

import android.content.Context
import com.wasimaster.wmkeyboard.core.files.RemoteEntry
import com.wasimaster.wmkeyboard.core.files.RemoteFs
import com.wasimaster.wmkeyboard.core.settings.AutoBackupRunner
import com.wasimaster.wmkeyboard.core.settings.BackupLocation
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.sink.BackupClients
import com.wasimaster.wmkeyboard.core.settings.sink.BoxSink
import com.wasimaster.wmkeyboard.core.settings.sink.OAuthTokens
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * A Box location as a [RemoteFs], from the top of the account's All Files
 * (folder `0`). Box has no app-folder scope: the sign-in grants the whole
 * account, which is how the backup's sink can make its folder at the top,
 * so the same reach is shown here rather than the backup folder alone.
 *
 * Box addresses everything by id. A path is walked from `0` by name, and the
 * ids found are kept for the session; every change forgets the paths it
 * touched and everything under them. An id from the cache is checked against
 * the item's current name and folder before anything is done to it, so a
 * stale one is looked up again rather than acted on.
 *
 * Box refuses a taken name with a 409 on every create, rename and move, and
 * an upload here only ever makes a new file (never the "new version" call),
 * so nothing is replaced. A single upload and a committed upload session
 * both appear only once whole. Deletes go to Box's trash; a folder is
 * deleted with `recursive=false`, which Box refuses unless it is empty.
 *
 * Box's refresh tokens are single use: each refresh hands back a new one,
 * saved before it is used, as [BoxSink] does. One the backup spent first is
 * replaced by the successor it saved ([storedRefreshToken]).
 */
class BoxFs(private val context: Context, private val location: BackupLocation) : RemoteFs {

    private val tokens: OAuthTokens = BackupClients.box() ?: throw RemoteAdapters.Unavailable("no Box client id")

    @Volatile
    private var refreshToken: String = location.refreshToken

    /** One thing found at a path: its id, whether it is a folder, and its etag when known. */
    private data class Item(val id: String, val folder: Boolean, val etag: String? = null)

    /** Path to item, for this session only. Never holds a "nothing there". */
    private val ids = HashMap<List<String>, Item>()

    private val guard = UploadGuard()

    override val atomicUpload: Boolean get() = true

    override val caseInsensitive: Boolean get() = true

    private fun bearer(): String {
        val onRotated = { rotated: String ->
            AutoBackupRunner.storeRefreshToken(context, location.id, rotated)
            refreshToken = rotated
        }
        tokens.accessToken(refreshToken, onRotated = onRotated)?.let { return it }
        // Single use: the backup may have spent the token this session opened
        // with and saved its successor. That one is tried before giving up.
        val stored = storedRefreshToken()
        if (stored != null && stored != refreshToken) {
            refreshToken = stored
            tokens.accessToken(stored, onRotated = onRotated)?.let { return it }
        }
        throw IOException("Box refused the saved sign-in")
    }

    /** The refresh token saved for this location now, which another session may have rotated. */
    private fun storedRefreshToken(): String? = runCatching {
        runBlocking {
            SettingsRepository(context.applicationContext).settings.first().autoBackup.locations
                .firstOrNull { it.id == location.id }?.refreshToken?.takeIf { it.isNotEmpty() }
        }
    }.getOrNull()

    private fun authorized(url: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer ${bearer()}")

    // ---- Reading ---------------------------------------------------------------

    override fun list(dir: List<String>): List<RemoteEntry> {
        val (folder, _) = find(dir) ?: throw IOException("no such folder")
        if (!folder.folder) throw IOException("not a folder")
        return children(dir, folder.id).map { it.first }
    }

    override fun stat(path: List<String>): RemoteEntry? {
        if (path.isEmpty()) {
            getItem(Item(ROOT, true), withPath = false) ?: throw IOException("the top folder is not there")
            return RemoteEntry("", isDirectory = true)
        }
        val found = find(path) ?: return null
        return entryOf(found.second)
    }

    override fun download(path: List<String>, to: OutputStream) {
        val (item, _) = find(path) ?: throw IOException("nothing there")
        if (item.folder) throw IOException("a folder has no content")
        // A redirect to a signed download address, which OkHttp follows without our token.
        CloudFsHttp.execute(authorized("$API/files/${item.id}/content").get().build(), idempotent = true).use { response ->
            if (!response.isSuccessful) throw CloudFsHttp.failure("box download", response)
            CloudFsHttp.stream(response, to)
        }
    }

    // ---- Changing --------------------------------------------------------------

    override fun upload(dir: List<String>, name: String, from: File, onSent: (Long) -> Unit) =
        guard.run(dir + name) { sendFile(dir, name, from, onSent) }

    private fun sendFile(dir: List<String>, name: String, from: File, onSent: (Long) -> Unit) {
        CloudFsHttp.checkNames(listOf(name))
        val parent = folderAt(dir)
        val size = from.length()
        val counter = SentCounter(onSent)
        try {
            if (size <= SINGLE_MAX) {
                // Attributes first: Box reads the parts in order and needs to know where the file goes.
                val attributes = buildJsonObject {
                    put("name", JsonPrimitive(name))
                    put("parent", buildJsonObject { put("id", JsonPrimitive(parent)) })
                }
                val form = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("attributes", attributes.toString())
                    .addFormDataPart("file", name, FileSliceBody(from, 0, size, CloudFsHttp.OCTET_STREAM, counter))
                    .build()
                guard.finalRequest()
                send(authorized("$UPLOAD/files/content").post(form).build(), "box upload")
            } else {
                uploadChunked(parent, name, from, size, counter)
            }
        } finally {
            forget(dir + name)
        }
    }

    /**
     * Box's chunked upload, for anything over [SINGLE_MAX]: a session sized
     * up front (which already refuses a taken name), parts of the size Box
     * picks each with its SHA-1, then a commit with the whole file's SHA-1,
     * after which the file appears. A failure aborts the session.
     */
    private fun uploadChunked(parent: String, name: String, from: File, size: Long, counter: SentCounter) {
        val created = sendJson(
            authorized("$UPLOAD/files/upload_sessions").post(
                buildJsonObject {
                    put("folder_id", JsonPrimitive(parent))
                    put("file_size", JsonPrimitive(size))
                    put("file_name", JsonPrimitive(name))
                }.toString().toRequestBody(CloudFsHttp.JSON_TYPE),
            ).build(),
            "box upload session",
        )
        val session = created["id"]?.jsonPrimitive?.contentOrNull ?: throw IOException("Box gave no upload session")
        val endpoints = created["session_endpoints"]?.jsonObject
        val partUrl = endpoints?.get("upload_part")?.jsonPrimitive?.contentOrNull ?: "$UPLOAD/files/upload_sessions/$session"
        val commitUrl = endpoints?.get("commit")?.jsonPrimitive?.contentOrNull ?: "$UPLOAD/files/upload_sessions/$session/commit"
        val abortUrl = endpoints?.get("abort")?.jsonPrimitive?.contentOrNull ?: "$UPLOAD/files/upload_sessions/$session"
        val partSize = created["part_size"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: throw IOException("Box gave no part size")
        try {
            val whole = MessageDigest.getInstance("SHA-1")
            val parts = ArrayList<JsonObject>()
            var offset = 0L
            while (offset < size) {
                val n = minOf(partSize, size - offset)
                val digest = CloudFsHttp.sha1(from, offset, n, also = whole)
                val answer = sendJson(
                    authorized(partUrl)
                        .header("Content-Range", "bytes $offset-${offset + n - 1}/$size")
                        .header("Digest", "sha=${Base64.encode(digest)}")
                        .put(FileSliceBody(from, offset, n, CloudFsHttp.OCTET_STREAM, counter))
                        .build(),
                    "box upload part",
                )
                parts += answer["part"]?.jsonObject ?: throw IOException("Box did not acknowledge a part")
                offset += n
            }
            guard.finalRequest()
            commit(commitUrl, JsonArray(parts), Base64.encode(whole.digest()))
        } catch (failure: Throwable) {
            runCatching { CloudFsHttp.client.newCall(authorized(abortUrl).delete().build()).execute().close() }
            throw failure
        }
    }

    /** The commit, which Box may answer with 202 "still putting it together, ask again in a moment". */
    private fun commit(url: String, parts: JsonArray, sha: String) {
        val body = buildJsonObject { put("parts", parts) }.toString()
        repeat(COMMIT_TRIES) {
            val request = authorized(url)
                .header("Digest", "sha=$sha")
                .post(body.toRequestBody(CloudFsHttp.JSON_TYPE))
                .build()
            val wait = CloudFsHttp.execute(request, idempotent = false).use { response ->
                if (response.code == HTTP_ACCEPTED) {
                    CloudFsHttp.retryAfterMs(response) ?: COMMIT_WAIT_MS
                } else if (response.isSuccessful) {
                    return
                } else {
                    throw CloudFsHttp.failure("box commit", response)
                }
            }
            Thread.sleep(wait.coerceIn(0, MAX_COMMIT_WAIT_MS))
        }
        throw IOException("Box did not finish the upload")
    }

    override fun mkdir(dir: List<String>, name: String) {
        CloudFsHttp.checkNames(listOf(name))
        val parent = folderAt(dir)
        val body = buildJsonObject {
            put("name", JsonPrimitive(name))
            put("parent", buildJsonObject { put("id", JsonPrimitive(parent)) })
        }
        try {
            send(authorized("$API/folders").post(body.toString().toRequestBody(CloudFsHttp.JSON_TYPE)).build(), "box mkdir")
        } finally {
            forget(dir + name)
        }
    }

    override fun delete(path: List<String>, isDirectory: Boolean) {
        if (path.isEmpty()) throw IOException("the top folder cannot be deleted")
        if (!isDirectory && guard.madeNothing(path)) return
        val (item, _) = find(path) ?: return
        if (item.folder != isDirectory) throw IOException("expected a ${if (isDirectory) "folder" else "file"}")
        val url = if (item.folder) "$API/folders/${item.id}?recursive=false" else "$API/files/${item.id}"
        val request = authorized(url)
            .apply { item.etag?.let { header("If-Match", it) } }
            .delete()
            .build()
        try {
            CloudFsHttp.execute(request, idempotent = true).use { response ->
                if (!response.isSuccessful && response.code != HTTP_NOT_FOUND) throw CloudFsHttp.failure("box delete", response)
            }
        } finally {
            forget(path)
        }
    }

    override fun rename(path: List<String>, newName: String) {
        if (path.isEmpty()) throw IOException("the top folder cannot be renamed")
        CloudFsHttp.checkNames(listOf(newName))
        update(path, buildJsonObject { put("name", JsonPrimitive(newName)) }, path.dropLast(1) + newName)
    }

    override fun move(path: List<String>, toDir: List<String>): Boolean {
        if (path.isEmpty()) return false
        val dest = folderAt(toDir)
        update(path, buildJsonObject { put("parent", buildJsonObject { put("id", JsonPrimitive(dest)) }) }, toDir + path.last())
        return true
    }

    /** A PUT on the item at [path]; Box answers a taken name with 409 rather than replace it. */
    private fun update(path: List<String>, body: JsonObject, to: List<String>) {
        val (item, _) = find(path) ?: throw IOException("nothing there")
        val url = "$API/${if (item.folder) "folders" else "files"}/${item.id}"
        try {
            send(authorized(url).put(body.toString().toRequestBody(CloudFsHttp.JSON_TYPE)).build(), "box update")
        } finally {
            forget(path)
            forget(to)
        }
    }

    // ---- Paths and ids ---------------------------------------------------------

    /** The id of the folder at [dir], or a failure when there is none. */
    private fun folderAt(dir: List<String>): String {
        if (dir.isEmpty()) return ROOT
        val (item, _) = find(dir) ?: throw IOException("no such folder")
        if (!item.folder) throw IOException("not a folder")
        return item.id
    }

    /**
     * What is at [path] now, with Box's own answer for it, or null when
     * nothing is.
     *
     * Ids come from the cache where it has them, but nothing is acted on
     * until Box confirms the item still has this name and sits under these
     * folders (its `path_collection`). An id that fails that check, or a
     * cached folder that has gone, empties the cache and the path is walked
     * again from the top by name, so a folder renamed or replaced elsewhere
     * can never send a change to the wrong file.
     */
    private fun find(path: List<String>): Pair<Item, JsonObject>? {
        CloudFsHttp.checkNames(path)
        if (path.isEmpty()) {
            val root = getItem(Item(ROOT, true), withPath = false) ?: return null
            return Item(ROOT, true, root.etag()) to root
        }
        val cached = try {
            resolve(path)
        } catch (e: CloudHttpException) {
            if (e.code != HTTP_NOT_FOUND) throw e
            null.also { ids.clear() }
        }
        if (cached != null) {
            verified(path, cached)?.let { return cached.copy(etag = it.etag()) to it }
        } else if (path.size == 1 || parentHolds(path)) {
            // Listed fresh from a folder that is still where it should be: really not there.
            return null
        }
        ids.clear()
        val fresh = resolve(path) ?: return null
        val current = verified(path, fresh) ?: throw IOException("\"${path.last()}\" changed while it was looked up")
        return fresh.copy(etag = current.etag()) to current
    }

    /** Whether the cached folder above [path] is still that folder, so a fresh listing of it can be trusted. */
    private fun parentHolds(path: List<String>): Boolean {
        val dir = path.dropLast(1)
        val parent = ids[dir] ?: return false
        return verified(dir, parent) != null
    }

    /**
     * The item at [path] by name, level by level: a cached id where there is
     * one, else a fresh listing of the folder above (an exact name first,
     * then one that differs only in case, which Box treats as the same).
     */
    private fun resolve(path: List<String>): Item? {
        var at = Item(ROOT, true)
        for (i in path.indices) {
            if (!at.folder) return null
            val prefix = path.subList(0, i + 1).toList()
            val next = ids[prefix] ?: run {
                val listed = children(prefix.dropLast(1), at.id)
                (listed.firstOrNull { it.first.name == prefix.last() } ?: listed.firstOrNull { it.first.name.equals(prefix.last(), ignoreCase = true) })
                    ?.second
                    ?.also { ids[prefix] = it }
            } ?: return null
            at = next
        }
        return at
    }

    /** Box's current answer for [item] when it is still called the last of [path] and sits under the rest of it, else null. */
    private fun verified(path: List<String>, item: Item): JsonObject? {
        val current = getItem(item, withPath = true) ?: return null
        val name = current["name"]?.jsonPrimitive?.contentOrNull ?: return null
        if (!name.equals(path.last(), ignoreCase = true)) return null
        val above = current["path_collection"]?.jsonObject?.get("entries")?.jsonArray ?: return null
        // The first is All Files (id 0), then one per folder of the path.
        if (above.size != path.size) return null
        if (above.first().jsonObject["id"]?.jsonPrimitive?.contentOrNull != ROOT) return null
        for (k in 1 until above.size) {
            val folder = above[k].jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: return null
            if (!folder.equals(path[k - 1], ignoreCase = true)) return null
        }
        return current
    }

    /** Forgets [path] and everything cached under it, after anything that changes it. */
    private fun forget(path: List<String>) {
        ids.keys.removeAll { it.size >= path.size && it.subList(0, path.size) == path }
    }

    /** Every file and folder in the folder [id] (web links left out), caching each one's id under [dir]. */
    private fun children(dir: List<String>, id: String): List<Pair<RemoteEntry, Item>> {
        val out = ArrayList<Pair<RemoteEntry, Item>>()
        var marker: String? = null
        do {
            val url = buildString {
                append("$API/folders/$id/items?fields=$FIELDS&limit=$PAGE&usemarker=true")
                marker?.let { append("&marker=").append(URLEncoder.encode(it, "UTF-8")) }
            }
            val page = CloudFsHttp.execute(authorized(url).get().build(), idempotent = true).use { response ->
                if (!response.isSuccessful) throw CloudFsHttp.failure("box list", response)
                CloudFsHttp.jsonOf(response)
            }
            for (element in page["entries"]?.jsonArray.orEmpty()) {
                val item = element.jsonObject
                val entry = entryOf(item) ?: continue
                val itemId = item["id"]?.jsonPrimitive?.contentOrNull ?: continue
                val found = Item(itemId, entry.isDirectory, item.etag())
                ids[dir + entry.name] = found
                out += entry to found
            }
            marker = page["next_marker"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
        } while (marker != null)
        return out
    }

    /** The item as Box has it now, with the folders above it when [withPath], or null when it is gone. */
    private fun getItem(item: Item, withPath: Boolean): JsonObject? {
        val fields = if (withPath) "$FIELDS,path_collection" else FIELDS
        val url = "$API/${if (item.folder) "folders" else "files"}/${item.id}?fields=$fields"
        return CloudFsHttp.execute(authorized(url).get().build(), idempotent = true).use { response ->
            when {
                response.isSuccessful -> CloudFsHttp.jsonOf(response)
                response.code == HTTP_NOT_FOUND -> null
                else -> throw CloudFsHttp.failure("box GET", response)
            }
        }
    }

    private fun JsonObject.etag(): String? = this["etag"]?.jsonPrimitive?.contentOrNull

    private fun send(request: Request, what: String) {
        CloudFsHttp.execute(request, idempotent = false).use { response ->
            if (!response.isSuccessful) throw CloudFsHttp.failure(what, response)
        }
    }

    private fun sendJson(request: Request, what: String): JsonObject =
        CloudFsHttp.execute(request, idempotent = false).use { response ->
            if (!response.isSuccessful) throw CloudFsHttp.failure(what, response)
            CloudFsHttp.jsonOf(response)
        }

    /** A file or a folder; a web link, which has no bytes to show, is null. */
    private fun entryOf(item: JsonObject): RemoteEntry? {
        val name = item["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val modified = item["modified_at"]?.jsonPrimitive?.contentOrNull?.let { BoxSink.parseOffsetTime(it) } ?: 0L
        return when (item["type"]?.jsonPrimitive?.contentOrNull) {
            "folder" -> RemoteEntry(name, isDirectory = true, modifiedMs = modified)
            "file" -> RemoteEntry(name, false, item["size"]?.jsonPrimitive?.longOrNull ?: -1, modified)
            else -> null
        }
    }

    override fun close() {
        ids.clear()
    }

    private companion object {
        const val API = "https://api.box.com/2.0"
        const val UPLOAD = "https://upload.box.com/api/2.0"
        const val ROOT = "0"
        const val PAGE = 1000
        const val FIELDS = "id,type,name,size,modified_at,etag"

        /** Box's limit for a single upload; above it, a chunked upload session. */
        const val SINGLE_MAX = 50L * 1024 * 1024

        const val HTTP_ACCEPTED = 202
        const val HTTP_NOT_FOUND = 404
        const val COMMIT_TRIES = 30
        const val COMMIT_WAIT_MS = 2_000L
        const val MAX_COMMIT_WAIT_MS = 30_000L
    }
}
