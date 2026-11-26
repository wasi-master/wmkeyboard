package com.wasimaster.wmkeyboard.core.settings.remote

import android.content.Context
import com.wasimaster.wmkeyboard.core.files.RemoteEntry
import com.wasimaster.wmkeyboard.core.files.RemoteFs
import com.wasimaster.wmkeyboard.core.settings.AutoBackupRunner
import com.wasimaster.wmkeyboard.core.settings.BackupLocation
import com.wasimaster.wmkeyboard.core.settings.sink.BackupClients
import com.wasimaster.wmkeyboard.core.settings.sink.OAuthTokens
import com.wasimaster.wmkeyboard.core.settings.sink.S3Listing
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.URLEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * A OneDrive location as a [RemoteFs]: the app's folder (`Apps/<app name>`,
 * Graph's `special/approot`), which is the top here. The sign-in asks for
 * `Files.ReadWrite.AppFolder` only, so nothing else in the drive is visible.
 *
 * Items are addressed by path under the app folder (`approot:/a/b:`), which
 * Graph resolves itself, so there is no id cache to keep right. Every write
 * says `@microsoft.graph.conflictBehavior=fail`: an upload, a new folder, a
 * rename or a move onto a taken name is refused with a 409, never a
 * replacement. A simple upload and an upload session both make the file
 * appear only once it is whole.
 *
 * Microsoft rotates the refresh token on every refresh; the new one is saved
 * before it is used, as [com.wasimaster.wmkeyboard.core.settings.sink.OneDriveSink] does.
 */
class OneDriveFs(private val context: Context, private val location: BackupLocation) : RemoteFs {

    private val tokens: OAuthTokens = BackupClients.oneDrive() ?: throw RemoteAdapters.Unavailable("no OneDrive client id")

    @Volatile
    private var refreshToken: String = location.refreshToken

    private val guard = UploadGuard()

    override val atomicUpload: Boolean get() = true

    override val caseInsensitive: Boolean get() = true

    private fun bearer(): String =
        tokens.accessToken(refreshToken, onRotated = { rotated ->
            AutoBackupRunner.storeRefreshToken(context, location.id, rotated)
            refreshToken = rotated
        }) ?: throw IOException("Microsoft refused the saved sign-in")

    private fun authorized(url: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer ${bearer()}")

    // ---- Reading ---------------------------------------------------------------

    override fun list(dir: List<String>): List<RemoteEntry> {
        val out = ArrayList<RemoteEntry>()
        var url: String? = "${itemUrl(dir)}/children?\$select=$SELECT&\$top=$PAGE"
        while (url != null) {
            val page = getJson(url) ?: throw IOException("no such folder")
            page["value"]?.jsonArray?.forEach { entryOf(it.jsonObject)?.let(out::add) }
            url = page["@odata.nextLink"]?.jsonPrimitive?.contentOrNull
        }
        return out
    }

    override fun stat(path: List<String>): RemoteEntry? {
        if (path.isEmpty()) {
            getJson("$APP_ROOT?\$select=id") ?: throw IOException("the app folder is not there")
            return RemoteEntry("", isDirectory = true)
        }
        return getJson("${itemUrl(path)}?\$select=$SELECT")?.let(::entryOf)
    }

    override fun download(path: List<String>, to: OutputStream) {
        // A redirect to a pre-signed address, which OkHttp follows without our token.
        CloudFsHttp.execute(authorized("${itemUrl(path)}/content").get().build(), idempotent = true).use { response ->
            if (!response.isSuccessful) throw CloudFsHttp.failure("onedrive download", response)
            CloudFsHttp.stream(response, to)
        }
    }

    // ---- Changing --------------------------------------------------------------

    override fun upload(dir: List<String>, name: String, from: File, onSent: (Long) -> Unit) =
        guard.run(dir + name) { sendFile(dir, name, from, onSent) }

    private fun sendFile(dir: List<String>, name: String, from: File, onSent: (Long) -> Unit) {
        val size = from.length()
        val counter = SentCounter(onSent)
        val target = itemUrl(dir + name)
        if (size <= SIMPLE_MAX) {
            guard.finalRequest()
            val request = authorized("$target/content?$FAIL")
                .put(FileSliceBody(from, 0, size, CloudFsHttp.OCTET_STREAM, counter))
                .build()
            send(request, "onedrive upload")
            return
        }
        val session = CloudFsHttp.execute(
            authorized("$target/createUploadSession").post(SESSION_BODY.toRequestBody(CloudFsHttp.JSON_TYPE)).build(),
            idempotent = false,
        ).use { response ->
            if (!response.isSuccessful) throw CloudFsHttp.failure("onedrive createUploadSession", response)
            CloudFsHttp.jsonOf(response)
        }
        val uploadUrl = session["uploadUrl"]?.jsonPrimitive?.contentOrNull ?: throw IOException("OneDrive gave no upload address")
        try {
            var offset = 0L
            while (offset < size) {
                val n = minOf(CHUNK, size - offset)
                // No Authorization: the session address is its own credential, and Graph refuses both together.
                val request = Request.Builder()
                    .url(uploadUrl)
                    .header("Content-Range", "bytes $offset-${offset + n - 1}/$size")
                    .put(FileSliceBody(from, offset, n, CloudFsHttp.OCTET_STREAM, counter))
                    .build()
                if (offset + n == size) guard.finalRequest()
                send(request, "onedrive upload piece")
                offset += n
            }
        } catch (failure: Throwable) {
            // The pieces held so far are let go; no file was ever named.
            runCatching { CloudFsHttp.client.newCall(Request.Builder().url(uploadUrl).delete().build()).execute().close() }
            throw failure
        }
    }

    override fun mkdir(dir: List<String>, name: String) {
        CloudFsHttp.checkNames(listOf(name))
        val body = buildJsonObject {
            put("name", JsonPrimitive(name))
            put("folder", JsonObject(emptyMap()))
            put("@microsoft.graph.conflictBehavior", JsonPrimitive("fail"))
        }
        send(authorized("${itemUrl(dir)}/children").post(body.toString().toRequestBody(CloudFsHttp.JSON_TYPE)).build(), "onedrive mkdir")
    }

    /**
     * Looked at first, then deleted only as seen: the kind must match, a
     * folder must be empty, and `If-Match` holds the delete to the version
     * just read. OneDrive puts what it deletes in its recycle bin.
     */
    override fun delete(path: List<String>, isDirectory: Boolean) {
        if (path.isEmpty()) throw IOException("the top folder cannot be deleted")
        if (!isDirectory && guard.madeNothing(path)) return
        val item = getJson("${itemUrl(path)}?\$select=id,eTag,folder,file") ?: return
        val folder = item["folder"]?.jsonObject
        if ((folder != null) != isDirectory) throw IOException("expected a ${if (isDirectory) "folder" else "file"}")
        if (folder != null && (folder["childCount"]?.jsonPrimitive?.longOrNull ?: 0L) > 0L) throw IOException("the folder is not empty")
        val id = item["id"]?.jsonPrimitive?.contentOrNull ?: throw IOException("OneDrive gave no id")
        val request = authorized("$ITEMS/$id")
            .apply { item["eTag"]?.jsonPrimitive?.contentOrNull?.let { header("If-Match", it) } }
            .delete()
            .build()
        CloudFsHttp.execute(request, idempotent = true).use { response ->
            if (!response.isSuccessful && response.code != HTTP_NOT_FOUND) throw CloudFsHttp.failure("onedrive delete", response)
        }
    }

    override fun rename(path: List<String>, newName: String) {
        if (path.isEmpty()) throw IOException("the top folder cannot be renamed")
        CloudFsHttp.checkNames(listOf(newName))
        val body = buildJsonObject { put("name", JsonPrimitive(newName)) }
        send(authorized("${itemUrl(path)}?$FAIL").patch(body.toString().toRequestBody(CloudFsHttp.JSON_TYPE)).build(), "onedrive rename")
    }

    override fun move(path: List<String>, toDir: List<String>): Boolean {
        if (path.isEmpty()) return false
        val dest = (if (toDir.isEmpty()) getJson("$APP_ROOT?\$select=id,folder") else getJson("${itemUrl(toDir)}?\$select=id,folder"))
            ?: throw IOException("no such folder")
        if (dest["folder"] == null) throw IOException("not a folder")
        val destId = dest["id"]?.jsonPrimitive?.contentOrNull ?: throw IOException("OneDrive gave no id")
        val body = buildJsonObject { put("parentReference", buildJsonObject { put("id", JsonPrimitive(destId)) }) }
        send(authorized("${itemUrl(path)}?$FAIL").patch(body.toString().toRequestBody(CloudFsHttp.JSON_TYPE)).build(), "onedrive move")
        return true
    }

    // ---- Plumbing --------------------------------------------------------------

    /** The item at [path] under the app folder, in Graph's path addressing. */
    private fun itemUrl(path: List<String>): String {
        CloudFsHttp.checkNames(path)
        return if (path.isEmpty()) APP_ROOT else path.joinToString("/", prefix = "$APP_ROOT:/", postfix = ":") { escape(it) }
    }

    /** A GET answered as JSON, or null on a 404. */
    private fun getJson(url: String): JsonObject? =
        CloudFsHttp.execute(authorized(url).get().build(), idempotent = true).use { response ->
            when {
                response.isSuccessful -> CloudFsHttp.jsonOf(response)
                response.code == HTTP_NOT_FOUND -> null
                else -> throw CloudFsHttp.failure("onedrive GET", response)
            }
        }

    /** A change, sent once; anything but a success (a 409 for a taken name) throws. */
    private fun send(request: Request, what: String) {
        CloudFsHttp.execute(request, idempotent = false).use { response ->
            if (!response.isSuccessful) throw CloudFsHttp.failure(what, response)
        }
    }

    /** A notebook or anything else that is neither a file nor a folder is left out. */
    private fun entryOf(item: JsonObject): RemoteEntry? {
        val name = item["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val modified = item["lastModifiedDateTime"]?.jsonPrimitive?.contentOrNull?.let(S3Listing::parseIso8601) ?: 0L
        return when {
            item["folder"] != null -> RemoteEntry(name, isDirectory = true, modifiedMs = modified)
            item["file"] != null -> RemoteEntry(name, false, item["size"]?.jsonPrimitive?.longOrNull ?: -1, modified)
            else -> null
        }
    }

    /** A path segment, with the `%20` a path wants rather than `+`. */
    private fun escape(name: String): String = URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    private companion object {
        const val GRAPH = "https://graph.microsoft.com/v1.0/me/drive"
        const val APP_ROOT = "$GRAPH/special/approot"
        const val ITEMS = "$GRAPH/items"
        const val SELECT = "id,name,size,lastModifiedDateTime,folder,file"
        const val PAGE = 200
        const val FAIL = "@microsoft.graph.conflictBehavior=fail"
        const val SESSION_BODY = """{"item":{"@microsoft.graph.conflictBehavior":"fail"}}"""
        const val HTTP_NOT_FOUND = 404

        /** Graph's ceiling for a plain PUT; above it an upload session is required. */
        const val SIMPLE_MAX = 4L * 1024 * 1024

        /** One session piece: 32 × 320 KiB, a multiple of 320 KiB as Graph asks, well under its 60 MiB. */
        const val CHUNK = 32L * 320 * 1024
    }
}
