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
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * A Dropbox location as a [RemoteFs]: the app's own folder, which is the
 * top here. The app is registered with Dropbox's App Folder access, so the
 * token sees `Apps/<app name>` and nothing else in the account, and every
 * path below is relative to that folder (its top is the empty path `""`).
 *
 * Paths, not ids, address everything, and Dropbox folds case, so this needs
 * no cache. Every write uses the refusing form of its call: uploads are
 * `mode=add`, `autorename=false`, `strict_conflict=true`, and moves are
 * `move_v2`, which never replaces what is at the target (a change of case
 * alone goes by way of a second name, [renameCase]). An upload, single
 * or by session, appears at its name only once it is whole.
 *
 * The refresh token is the location's; Dropbox does not rotate it, but one
 * that it did send back is saved exactly as the sink's would be.
 */
class DropboxFs(private val context: Context, private val location: BackupLocation) : RemoteFs {

    private val tokens: OAuthTokens = BackupClients.dropbox() ?: throw RemoteAdapters.Unavailable("no Dropbox client id")

    @Volatile
    private var refreshToken: String = location.refreshToken

    private val guard = UploadGuard()

    override val atomicUpload: Boolean get() = true

    override val caseInsensitive: Boolean get() = true

    private fun bearer(): String =
        tokens.accessToken(refreshToken, onRotated = { rotated ->
            // Saved before the token is used, as the backup's sinks do.
            AutoBackupRunner.storeRefreshToken(context, location.id, rotated)
            refreshToken = rotated
        }) ?: throw IOException("Dropbox refused the saved sign-in")

    // ---- Reading ---------------------------------------------------------------

    override fun list(dir: List<String>): List<RemoteEntry> {
        val out = ArrayList<RemoteEntry>()
        var page = rpc(
            "files/list_folder",
            buildJsonObject {
                put("path", JsonPrimitive(pathOf(dir)))
                put("limit", JsonPrimitive(LIST_LIMIT))
            },
        )
        while (true) {
            page["entries"]?.jsonArray?.forEach { entryOf(it.jsonObject)?.let(out::add) }
            if (page["has_more"]?.jsonPrimitive?.booleanOrNull != true) break
            val cursor = page["cursor"]?.jsonPrimitive?.contentOrNull ?: throw IOException("Dropbox gave no cursor")
            page = rpc("files/list_folder/continue", buildJsonObject { put("cursor", JsonPrimitive(cursor)) })
        }
        return out
    }

    override fun stat(path: List<String>): RemoteEntry? {
        if (path.isEmpty()) {
            // Dropbox has no metadata for the top; one entry of a listing proves the token and the folder.
            rpc("files/list_folder", buildJsonObject {
                put("path", JsonPrimitive(""))
                put("limit", JsonPrimitive(1))
            })
            return RemoteEntry("", isDirectory = true)
        }
        return metadata(path)?.let(::entryOf)
    }

    /** The metadata at [path], or null when Dropbox says nothing is there. */
    private fun metadata(path: List<String>): JsonObject? = try {
        rpc("files/get_metadata", buildJsonObject { put("path", JsonPrimitive(pathOf(path))) })
    } catch (e: CloudHttpException) {
        if (isNotFound(e)) null else throw e
    }

    override fun download(path: List<String>, to: OutputStream) {
        val request = Request.Builder()
            .url("$CONTENT/files/download")
            .header("Authorization", "Bearer ${bearer()}")
            .header("Dropbox-API-Arg", CloudFsHttp.asciiJson(buildJsonObject { put("path", JsonPrimitive(pathOf(path))) }))
            // Dropbox wants a POST with no body at all here.
            .post(ByteArray(0).toRequestBody(null))
            .build()
        CloudFsHttp.execute(request, idempotent = true).use { response ->
            if (!response.isSuccessful) throw CloudFsHttp.failure("dropbox download", response)
            CloudFsHttp.stream(response, to)
        }
    }

    // ---- Changing --------------------------------------------------------------

    override fun upload(dir: List<String>, name: String, from: File, onSent: (Long) -> Unit) =
        guard.run(dir + name) { sendFile(dir, name, from, onSent) }

    private fun sendFile(dir: List<String>, name: String, from: File, onSent: (Long) -> Unit) {
        val target = pathOf(dir + name)
        val size = from.length()
        val counter = SentCounter(onSent)
        val commit = buildJsonObject {
            put("path", JsonPrimitive(target))
            // Never a new version of something already there: a taken name fails.
            put("mode", JsonPrimitive("add"))
            put("autorename", JsonPrimitive(false))
            put("strict_conflict", JsonPrimitive(true))
            put("mute", JsonPrimitive(true))
        }
        if (size <= SINGLE_MAX) {
            guard.finalRequest()
            content("files/upload", commit, FileSliceBody(from, 0, size, CloudFsHttp.OCTET_STREAM, counter))
            return
        }
        // An upload session: the pieces are held by Dropbox and nothing has a
        // name until finish, which commits under the same refusing rules.
        val first = minOf(CHUNK, size)
        val started = content(
            "files/upload_session/start",
            buildJsonObject { put("close", JsonPrimitive(false)) },
            FileSliceBody(from, 0, first, CloudFsHttp.OCTET_STREAM, counter),
        )
        val session = started["session_id"]?.jsonPrimitive?.contentOrNull ?: throw IOException("Dropbox gave no upload session")
        var offset = first
        while (offset < size) {
            val n = minOf(CHUNK, size - offset)
            content(
                "files/upload_session/append_v2",
                buildJsonObject {
                    put("cursor", cursor(session, offset))
                    put("close", JsonPrimitive(false))
                },
                FileSliceBody(from, offset, n, CloudFsHttp.OCTET_STREAM, counter),
            )
            offset += n
        }
        guard.finalRequest()
        content(
            "files/upload_session/finish",
            buildJsonObject {
                put("cursor", cursor(session, size))
                put("commit", commit)
            },
            ByteArray(0).toRequestBody(CloudFsHttp.OCTET_STREAM),
        )
    }

    private fun cursor(session: String, offset: Long) = buildJsonObject {
        put("session_id", JsonPrimitive(session))
        put("offset", JsonPrimitive(offset))
    }

    override fun mkdir(dir: List<String>, name: String) {
        rpc(
            "files/create_folder_v2",
            buildJsonObject {
                put("path", JsonPrimitive(pathOf(dir + name)))
                put("autorename", JsonPrimitive(false))
            },
            idempotent = false,
        )
    }

    /**
     * `delete_v2` takes a folder with everything in it, so what is there is
     * looked at first: a file is deleted only at the revision just seen
     * (`parent_rev`), and a folder only when it is empty. Dropbox keeps
     * deleted files for its restore window either way.
     */
    override fun delete(path: List<String>, isDirectory: Boolean) {
        if (path.isEmpty()) throw IOException("the top folder cannot be deleted")
        if (!isDirectory && guard.madeNothing(path)) return
        val meta = metadata(path) ?: return
        val folder = meta[".tag"]?.jsonPrimitive?.contentOrNull == "folder"
        if (folder != isDirectory) throw IOException("expected a ${if (isDirectory) "folder" else "file"} at ${pathOf(path)}")
        val args = buildJsonObject {
            put("path", JsonPrimitive(pathOf(path)))
            if (!folder) meta["rev"]?.jsonPrimitive?.contentOrNull?.let { put("parent_rev", JsonPrimitive(it)) }
        }
        if (folder) {
            val inside = rpc("files/list_folder", buildJsonObject {
                put("path", JsonPrimitive(pathOf(path)))
                put("limit", JsonPrimitive(1))
            })
            if (inside["entries"]?.jsonArray?.isNotEmpty() == true) throw IOException("the folder is not empty")
        }
        try {
            rpc("files/delete_v2", args, idempotent = false)
        } catch (e: CloudHttpException) {
            if (!isNotFound(e)) throw e
        }
    }

    override fun rename(path: List<String>, newName: String) {
        if (path.isEmpty()) throw IOException("the top folder cannot be renamed")
        val to = path.dropLast(1) + newName
        if (path.last() != newName && path.last().equals(newName, ignoreCase = true)) renameCase(path, to) else relocate(path, to)
    }

    /**
     * A change of case alone, which `move_v2` refuses ("we do not currently
     * support case-only renaming", Dropbox's own API spec): by way of a name
     * nothing else has, then to [to]. A refused second step puts the old name
     * back. Each step is a `move_v2`, so neither can replace anything.
     */
    private fun renameCase(from: List<String>, to: List<String>) {
        val aside = from.dropLast(1) + "${from.last()}.wmcase-${UUID.randomUUID().toString().take(8)}"
        relocate(from, aside)
        try {
            relocate(aside, to)
        } catch (failure: Throwable) {
            runCatching { relocate(aside, from) }
            throw failure
        }
    }

    override fun move(path: List<String>, toDir: List<String>): Boolean {
        if (path.isEmpty()) return false
        relocate(path, toDir + path.last())
        return true
    }

    /** `move_v2`, which fails with `to/conflict` rather than replace anything at [to]. */
    private fun relocate(from: List<String>, to: List<String>) {
        rpc(
            "files/move_v2",
            buildJsonObject {
                put("from_path", JsonPrimitive(pathOf(from)))
                put("to_path", JsonPrimitive(pathOf(to)))
                put("autorename", JsonPrimitive(false))
                put("allow_ownership_transfer", JsonPrimitive(false))
            },
            idempotent = false,
        )
    }

    // ---- Plumbing --------------------------------------------------------------

    /** A path as Dropbox writes it: `""` for the top, else `/a/b`. */
    private fun pathOf(path: List<String>): String {
        CloudFsHttp.checkNames(path)
        return if (path.isEmpty()) "" else path.joinToString("/", prefix = "/")
    }

    /** An RPC call on the API host: JSON in, JSON out. */
    private fun rpc(endpoint: String, body: JsonObject, idempotent: Boolean = true): JsonObject {
        val request = Request.Builder()
            .url("$API/$endpoint")
            .header("Authorization", "Bearer ${bearer()}")
            .post(body.toString().toRequestBody(CloudFsHttp.JSON_TYPE))
            .build()
        return CloudFsHttp.execute(request, idempotent).use { response ->
            if (!response.isSuccessful) throw CloudFsHttp.failure("dropbox $endpoint", response)
            CloudFsHttp.jsonOf(response)
        }
    }

    /** A content call: arguments in the header, bytes in the body. Sent once. */
    private fun content(endpoint: String, arg: JsonObject, body: RequestBody): JsonObject {
        val request = Request.Builder()
            .url("$CONTENT/$endpoint")
            .header("Authorization", "Bearer ${bearer()}")
            .header("Dropbox-API-Arg", CloudFsHttp.asciiJson(arg))
            .post(body)
            .build()
        return CloudFsHttp.execute(request, idempotent = false).use { response ->
            if (!response.isSuccessful) throw CloudFsHttp.failure("dropbox $endpoint", response)
            val text = response.body.string()
            // append_v2 answers with a JSON null.
            if (text.isBlank() || text.trim() == "null") JsonObject(emptyMap()) else CloudFsHttp.parse(text)
        }
    }

    /** Dropbox answers a missing path with a 409 whose summary names `not_found`. */
    private fun isNotFound(e: CloudHttpException): Boolean =
        e.code == HTTP_CONFLICT && e.body.contains("not_found")

    private fun entryOf(item: JsonObject): RemoteEntry? {
        val name = item["name"]?.jsonPrimitive?.contentOrNull ?: return null
        return when (item[".tag"]?.jsonPrimitive?.contentOrNull) {
            "folder" -> RemoteEntry(name, isDirectory = true)
            "file" -> RemoteEntry(
                name = name,
                isDirectory = false,
                size = item["size"]?.jsonPrimitive?.longOrNull ?: -1,
                modifiedMs = item["server_modified"]?.jsonPrimitive?.contentOrNull?.let(S3Listing::parseIso8601) ?: 0,
            )
            else -> null
        }
    }

    private companion object {
        const val API = "https://api.dropboxapi.com/2"
        const val CONTENT = "https://content.dropboxapi.com/2"
        const val LIST_LIMIT = 2000
        const val HTTP_CONFLICT = 409

        /** Dropbox's ceiling for one `files/upload`. */
        const val SINGLE_MAX = 150L * 1024 * 1024

        /** One session piece: a multiple of 4 MiB, as Dropbox asks. */
        const val CHUNK = 32L * 1024 * 1024
    }
}
