package com.wasimaster.wmkeyboard.core.settings.remote

import android.content.Context
import com.wasimaster.wmkeyboard.core.files.RemoteEntry
import com.wasimaster.wmkeyboard.core.files.RemoteFs
import com.wasimaster.wmkeyboard.core.settings.BackupLocation
import com.wasimaster.wmkeyboard.core.settings.PCloudConfig
import com.wasimaster.wmkeyboard.core.settings.sink.BackupHttp
import com.wasimaster.wmkeyboard.core.settings.sink.PCloudApi
import com.wasimaster.wmkeyboard.core.settings.sink.PCloudSink
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The whole of a pCloud account in the Files tool, through pCloud's own HTTP
 * API on the region the account lives in ([PCloudConfig.region]), with the
 * token the backup's sign-in kept ([PCloudConfig.auth]). There is no session
 * to open: the token is the session, and it lasts until it is revoked.
 *
 * The top of the place is the account's root folder; every call goes by
 * path. pCloud replaces a file of the same name on an upload with
 * `renameifexists` off, and on `renamefile` always ("replaced atomically",
 * docs.pcloud.com), so:
 *
 * - an upload sets `renameifexists`, which leaves a file already there alone
 *   and puts the new one under another name; the answer's name is checked,
 *   and an upload that landed anywhere but the asked-for name is deleted (by
 *   its own file id) and refused. `nopartial` keeps a cut-off upload from
 *   leaving a short file behind. Together they make an upload whole or
 *   absent, and never a replacement, which pCloud itself decides; so uploads
 *   go straight to their names ([atomicUpload]) rather than through a
 *   temporary name and a `renamefile`, whose look first and rename after
 *   leave a moment in which a new file of that name would be replaced.
 * - a rename or a move first checks, right before, that the name is free
 *   (`renamefolder` refuses a taken name with 2004; `renamefile` does not).
 * - a folder goes with `deletefolder`, which refuses one that is not empty.
 */
class PCloudFs(context: Context, location: BackupLocation) : RemoteFs {

    /** A non-zero `result` from pCloud, by its number. */
    class Refused(val result: Int, what: String) : IOException("$what -> result $result")

    private val config: PCloudConfig = location.pcloud

    override val atomicUpload: Boolean get() = true

    private val client: OkHttpClient by lazy { BackupHttp.client() }

    /** Given longer to answer after the last byte of a large upload, while pCloud stores it. */
    private val slowClient: OkHttpClient by lazy { client.newBuilder().readTimeout(SLOW_REPLY_MINUTES, TimeUnit.MINUTES).build() }

    private fun url(method: String, query: List<Pair<String, String>>): HttpUrl {
        if (!config.configured) throw IOException("signed out of pCloud: sign in again in the backup settings")
        return HttpUrl.Builder().scheme("https").host(config.region.apiHost).addPathSegment(method)
            .addQueryParameter("auth", config.auth)
            .apply { for ((k, v) in query) addQueryParameter(k, v) }
            .build()
    }

    /** One API call; a non-zero `result` is a [Refused]. */
    private fun call(method: String, vararg query: Pair<String, String>): JsonObject =
        exchange(Request.Builder().url(url(method, query.toList())).get().build(), method, client)

    private fun exchange(request: Request, method: String, via: OkHttpClient): JsonObject = try {
        PCloudApi.call(via, request, "pcloud $method", raw = true)
    } catch (failure: PCloudApi.Failure) {
        throw Refused(failure.result, "pcloud $method")
    }

    private fun pathOf(path: List<String>): String = "/" + path.joinToString("/")

    // ---- Reading ---------------------------------------------------------------

    override fun list(dir: List<String>): List<RemoteEntry> {
        CloudFsHttp.checkNames(dir)
        val answer = call("listfolder", "path" to pathOf(dir))
        return answer["metadata"]?.jsonObject?.get("contents")?.jsonArray.orEmpty().mapNotNull { entryOf(it.jsonObject) }
    }

    private fun entryOf(item: JsonObject): RemoteEntry? {
        val name = item["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return null
        val isDir = item["isfolder"]?.jsonPrimitive?.booleanOrNull == true
        return RemoteEntry(
            name = name,
            isDirectory = isDir,
            size = if (isDir) -1 else item["size"]?.jsonPrimitive?.longOrNull ?: -1,
            modifiedMs = item["modified"]?.jsonPrimitive?.contentOrNull?.let { PCloudSink.parseRfc1123(it) } ?: 0L,
        )
    }

    /** From the folder's listing: pCloud's `stat` answers for files only. A missing folder above is "nothing here". */
    override fun stat(path: List<String>): RemoteEntry? {
        CloudFsHttp.checkNames(path)
        if (path.isEmpty()) return RemoteEntry(name = "", isDirectory = true)
        val siblings = try {
            list(path.dropLast(1))
        } catch (failure: Refused) {
            if (failure.result in MISSING) return null
            throw failure
        }
        return siblings.firstOrNull { it.name == path.last() }
    }

    override fun download(path: List<String>, to: OutputStream) {
        CloudFsHttp.checkNames(path)
        if (path.isEmpty()) throw IOException("pcloud download: the top is a folder")
        val link = call("getfilelink", "path" to pathOf(path))
        val host = link["hosts"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
        val at = link["path"]?.jsonPrimitive?.contentOrNull
        if (host.isNullOrEmpty() || at.isNullOrEmpty()) throw IOException("pcloud download: no link")
        client.newCall(Request.Builder().url("https://$host$at").get().build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("pcloud download -> HTTP ${response.code}")
            CloudFsHttp.stream(response, to)
        }
    }

    // ---- Changing --------------------------------------------------------------

    override fun upload(dir: List<String>, name: String, from: File, onSent: (Long) -> Unit) {
        CloudFsHttp.checkNames(dir + name)
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", name,
                FileSliceBody(from, 0, from.length(), CloudFsHttp.OCTET_STREAM, SentCounter(onSent)),
            )
            .build()
        val query = listOf(
            "path" to pathOf(dir),
            "filename" to name,
            "nopartial" to "1",
            "renameifexists" to "1",
        )
        val answer = exchange(Request.Builder().url(url("uploadfile", query)).post(form).build(), "uploadfile", slowClient)
        val landed = answer["metadata"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw IOException("pcloud upload: no file in the answer")
        val landedName = landed["name"]?.jsonPrimitive?.contentOrNull
        if (landedName != name) {
            // The name was taken after all, so pCloud kept the other file and
            // named ours differently. Ours goes again, by its own id, which
            // cannot be anyone else's file.
            landed["fileid"]?.jsonPrimitive?.longOrNull?.let { id ->
                runCatching { call("deletefile", "fileid" to id.toString()) }
            }
            throw IOException("pcloud upload: \"$name\" was taken")
        }
    }

    override fun mkdir(dir: List<String>, name: String) {
        CloudFsHttp.checkNames(dir + name)
        // createfolder, not createfolderifnotexists: a folder already there is an error.
        call("createfolder", "path" to pathOf(dir + name))
    }

    override fun delete(path: List<String>, isDirectory: Boolean) {
        CloudFsHttp.checkNames(path)
        if (path.isEmpty()) throw IOException("pcloud delete: not the account's root")
        try {
            // deletefolder takes only an empty folder; deletefolderrecursive is never used.
            call(if (isDirectory) "deletefolder" else "deletefile", "path" to pathOf(path))
        } catch (failure: Refused) {
            if (failure.result !in MISSING) throw failure
        }
    }

    override fun rename(path: List<String>, newName: String) {
        CloudFsHttp.checkNames(path + newName)
        if (path.isEmpty()) throw IOException("pcloud rename: not the account's root")
        val dir = path.dropLast(1)
        val siblings = list(dir)
        val source = siblings.firstOrNull { it.name == path.last() } ?: throw IOException("pcloud rename: nothing to rename")
        refuseTaken(siblings, newName, except = path.last())
        relocate(path, dir + newName, source.isDirectory)
    }

    override fun move(path: List<String>, toDir: List<String>): Boolean {
        CloudFsHttp.checkNames(path + toDir)
        if (path.isEmpty()) return false
        val source = stat(path) ?: throw IOException("pcloud move: nothing to move")
        refuseTaken(list(toDir), path.last())
        relocate(path, toDir + path.last(), source.isDirectory)
        return true
    }

    /**
     * renamefile / renamefolder to a whole new path. The target never ends
     * in a slash (that would mean "into"), and has just been checked free:
     * renamefile would replace a file already there.
     */
    private fun relocate(from: List<String>, to: List<String>, isDirectory: Boolean) {
        call(if (isDirectory) "renamefolder" else "renamefile", "path" to pathOf(from), "topath" to pathOf(to))
    }

    /** Refuses when [entries] hold [name], ignoring case and leaving out [except]. */
    private fun refuseTaken(entries: List<RemoteEntry>, name: String, except: String? = null) {
        if (entries.any { it.name != except && it.name.equals(name, ignoreCase = true) }) {
            throw IOException("pcloud: \"$name\" is already taken there")
        }
    }

    companion object {
        private const val SLOW_REPLY_MINUTES = 10L

        /** 2002 a missing part of the path, 2005 a missing folder, 2009 a missing file. */
        private val MISSING = setOf(2002, 2005, 2009)
    }
}
