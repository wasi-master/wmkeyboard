package com.wasimaster.wmkeyboard.core.settings.remote

import android.content.Context
import com.wasimaster.wmkeyboard.core.files.RemoteEntry
import com.wasimaster.wmkeyboard.core.settings.BackupLocation
import com.wasimaster.wmkeyboard.core.settings.sink.QnapSink
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A QNAP NAS in the Files tool, through QTS File Station
 * (`/cgi-bin/filemanager/utilRequest.cgi`), signed in the way the backup's
 * [QnapSink] signs in (its `qtoken` included).
 *
 * The top of the place is the list of shares (`get_tree` from
 * `share_root`); below that, `get_list` with `hidden_file=1` ("1: list" in
 * QNAP's File Station HTTP API v4.1; the backup sink's 0 hides them), so the
 * store's own dot-named upload in progress can be found and a folder's
 * emptiness is judged on everything in it.
 *
 * What keeps files safe: an upload asks for no overwrite (`overwrite=0`,
 * "skip"), a rename first checks the name is free (QTS also answers status 2
 * rather than replace), and a folder is deleted only once a listing shows it
 * empty, because QTS's delete takes a folder with everything inside. No
 * `force`, so a delete goes through the recycle bin where it is on.
 *
 * [move] is left to the store (copy, check, then delete). QTS's own move
 * takes `mode` (1 skip, 0 overwrite, per the same guide), but the guide does
 * not say whether it answers before a large move has finished, and a move
 * still running when the store falls back to copying is a tangle.
 */
class QnapFs(context: Context, location: BackupLocation) :
    NasFs<QnapSink>(location.nas, QnapSink(location.nas, NasFs.certRecorder(context, location))) {

    override fun signIn(): String {
        if (!config.configured) throw IOException("the QNAP location is not set up")
        val answer = sink.authenticate(code = "")
        if (answer.needsCode) throw IOException("QTS wants a two-step code: sign in again in the backup settings")
        return answer.sid ?: throw IOException("QTS refused the sign-in (${answer.error})")
    }

    override fun isMissing(code: Int): Boolean = code == STATUS_NO_EXIST || code == STATUS_PATH_MISSING

    private fun endpoint(func: String, sid: String, params: List<Pair<String, String>>): HttpUrl =
        base().newBuilder().addPathSegments("cgi-bin/filemanager/utilRequest.cgi")
            .addQueryParameter("func", func)
            .addQueryParameter("sid", sid)
            .apply { for ((k, v) in params) addQueryParameter(k, v) }
            .build()

    /** Throws unless [element] is a success: an object without `status`, or with status 1, or an array. */
    private fun check(element: JsonElement, what: String, allowed: Set<Int> = emptySet()) {
        val status = (element as? JsonObject)?.get("status")?.jsonPrimitive?.intOrNull ?: STATUS_SUCCESS
        if (status == STATUS_SUCCESS || status in allowed) return
        throw NasFs.Refused(status, status == STATUS_AUTH_FAIL, what)
    }

    /** One GET call that must succeed; its whole answer (`get_tree` sends an array, the rest an object). */
    private fun call(
        func: String,
        params: List<Pair<String, String>>,
        what: String,
        allowed: Set<Int> = emptySet(),
        via: OkHttpClient = client,
    ): JsonElement = session { sid ->
        val element = send(Request.Builder().url(endpoint(func, sid, params)).get().build(), what, via) { elementOf(it, what) }
        check(element, what, allowed)
        element
    }

    // ---- Reading ---------------------------------------------------------------

    override fun list(dir: List<String>): List<RemoteEntry> {
        CloudFsHttp.checkNames(dir)
        return if (dir.isEmpty()) shares() else folder(dir)
    }

    /**
     * The shares, from the tree's root. Only nodes whose id is one level
     * (`/Public`) are kept: a node that points deeper (a Qsync or home
     * shortcut) is not a folder at the path its name would give it.
     */
    private fun shares(): List<RemoteEntry> {
        val answer = call("get_tree", listOf("node" to "share_root", "is_iso" to "0"), "qnap shares")
        val nodes = when (answer) {
            is JsonArray -> answer
            is JsonObject -> answer["datas"]?.jsonArray.orEmpty()
            else -> emptyList()
        }
        return nodes.mapNotNull { node ->
            val id = (node as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = id.removePrefix("/")
            if (!id.startsWith("/") || name.isEmpty() || name.contains('/')) return@mapNotNull null
            RemoteEntry(name = name, isDirectory = true)
        }
    }

    private fun folder(dir: List<String>): List<RemoteEntry> {
        val out = ArrayList<RemoteEntry>()
        var start = 0
        while (true) {
            val root = call(
                "get_list",
                listOf(
                    "path" to pathOf(dir),
                    "list_mode" to "all",
                    "is_iso" to "0",
                    "start" to start.toString(),
                    "limit" to PAGE.toString(),
                    "sort" to "filename",
                    "dir" to "ASC",
                    "hidden_file" to "1",
                ),
                "qnap list",
            ) as? JsonObject ?: throw IOException("qnap list: unexpected answer")
            val items = root["datas"]?.jsonArray.orEmpty()
            items.mapNotNullTo(out) { entryOf(it.jsonObject) }
            start += items.size
            val total = root["total"]?.jsonPrimitive?.intOrNull ?: 0
            if (items.isEmpty() || start >= total) return out
        }
    }

    private fun entryOf(item: JsonObject): RemoteEntry? {
        val name = item["filename"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return null
        val isDir = item["isfolder"]?.jsonPrimitive?.intOrNull == 1
        return RemoteEntry(
            name = name,
            isDirectory = isDir,
            size = if (isDir) -1 else item["filesize"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: -1,
            modifiedMs = (item["epochmt"]?.jsonPrimitive?.longOrNull ?: 0L) * 1000L,
        )
    }

    override fun download(path: List<String>, to: OutputStream) {
        CloudFsHttp.checkNames(path)
        refuseShareLevel(path, "qnap download")
        session { sid ->
            val url = endpoint(
                "download", sid,
                listOf(
                    "isfolder" to "0",
                    "source_path" to pathOf(path.dropLast(1)),
                    "source_file" to path.last(),
                    "source_total" to "1",
                ),
            )
            send(Request.Builder().url(url).get().build(), "qnap download") { response ->
                // A refusal is a small JSON answer with a "status"; a file that
                // is itself JSON is streamed like any other.
                if (response.header("Content-Type").orEmpty().contains("json")) {
                    val root = peekObject(response)
                    if (root != null && root.containsKey("status")) check(root, "qnap download")
                }
                CloudFsHttp.stream(response, to)
            }
        }
    }

    // ---- Changing --------------------------------------------------------------

    override fun upload(dir: List<String>, name: String, from: File, onSent: (Long) -> Unit) {
        CloudFsHttp.checkNames(dir + name)
        if (dir.isEmpty()) throw IOException("qnap upload: files go inside a share")
        val counter = SentCounter(onSent)
        session { sid ->
            val url = endpoint(
                "upload", sid,
                listOf(
                    "type" to "standard",
                    "dest_path" to pathOf(dir),
                    // 0 is "skip", never replace. The name is the store's
                    // random temporary one, so there is nothing to skip.
                    "overwrite" to "0",
                    "progress" to pathOf(dir + name).replace('/', '-'),
                ),
            )
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", name, FileSliceBody(from, 0, from.length(), CloudFsHttp.OCTET_STREAM, counter))
                .build()
            val element = send(Request.Builder().url(url).post(form).build(), "qnap upload", slowClient) { elementOf(it, "qnap upload") }
            check(element, "qnap upload")
        }
    }

    override fun mkdir(dir: List<String>, name: String) {
        CloudFsHttp.checkNames(dir + name)
        if (dir.isEmpty()) throw IOException("qnap mkdir: shares are made on the NAS itself")
        // "File exists" (2, and 33 on some builds) is an error here: the name was meant to be free.
        call("createdir", listOf("dest_folder" to name, "dest_path" to pathOf(dir)), "qnap mkdir")
    }

    override fun delete(path: List<String>, isDirectory: Boolean) {
        CloudFsHttp.checkNames(path)
        refuseShareLevel(path, "qnap delete")
        val here = stat(path) ?: return
        // QTS deletes a folder with whatever is inside. The store empties it
        // first; one more look keeps anything it did not see.
        if (here.isDirectory && list(path).isNotEmpty()) throw IOException("qnap delete: the folder is not empty")
        call(
            "delete",
            listOf("path" to pathOf(path.dropLast(1)), "file_total" to "1", "file_name" to path.last(), "v" to "1"),
            "qnap delete",
            allowed = setOf(STATUS_NO_EXIST),
            via = slowClient,
        )
    }

    override fun rename(path: List<String>, newName: String) {
        CloudFsHttp.checkNames(path + newName)
        refuseShareLevel(path, "qnap rename")
        ensureFree(path.dropLast(1), newName, except = path.last())
        call(
            "rename",
            listOf("path" to pathOf(path.dropLast(1)), "source_name" to path.last(), "dest_name" to newName),
            "qnap rename",
        )
    }

    companion object {
        private const val PAGE = 500

        private const val STATUS_SUCCESS = 1
        private const val STATUS_AUTH_FAIL = 3
        private const val STATUS_NO_EXIST = 5
        private const val STATUS_PATH_MISSING = 25
    }
}
