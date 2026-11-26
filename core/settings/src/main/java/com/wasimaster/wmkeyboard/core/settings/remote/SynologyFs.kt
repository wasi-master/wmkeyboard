package com.wasimaster.wmkeyboard.core.settings.remote

import android.content.Context
import com.wasimaster.wmkeyboard.core.files.RemoteEntry
import com.wasimaster.wmkeyboard.core.settings.BackupLocation
import com.wasimaster.wmkeyboard.core.settings.sink.SynologySink
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
 * A Synology NAS in the Files tool, through DSM's File Station API
 * (`/webapi/entry.cgi`), signed in the way the backup's [SynologySink] signs
 * in (device token included, so an account with 2FA needs no code here).
 *
 * The top of the place is the list of shared folders (`list_share`); below
 * that, `list` with the folder's path. What keeps a file from being written
 * over: an upload sends no `overwrite` field (DSM then refuses a taken name,
 * where "false" would skip it in silence and still answer success), a rename
 * or a move first checks the name is free, a move sends no `overwrite`
 * either, and a delete is never recursive, so a folder the store did not
 * empty is refused rather than wiped.
 *
 * Parameters that may hold several paths are one-element JSON arrays, the
 * form the File Station API guide's examples use: a bare value there is
 * split at commas, so a name with a comma in it would name other files.
 * Single paths go as they are, as the backup sink sends them. CopyMove is a
 * version 3 API (the guide documents its start and status from 3 on) and
 * takes its destination and task id as JSON strings, as the guide shows.
 */
class SynologyFs(context: Context, location: BackupLocation) :
    NasFs<SynologySink>(location.nas, SynologySink(location.nas, NasFs.certRecorder(context, location))) {

    /** Whether a CopyMove task id goes bare; switched on when DSM refuses the JSON string the guide shows. */
    private var bareTaskId = false

    override fun signIn(): String {
        if (!config.configured) throw IOException("the Synology location is not set up")
        val answer = sink.authenticate(otp = "")
        if (answer.needsCode) throw IOException("DSM wants a two-step code: sign in again in the backup settings")
        return answer.sid ?: throw IOException("DSM refused the sign-in (${answer.error})")
    }

    override fun isMissing(code: Int): Boolean = code == NO_SUCH_FILE

    private fun endpoint(api: String, method: String, sid: String, params: List<Pair<String, String>>): HttpUrl =
        base().newBuilder().addPathSegments("webapi/entry.cgi")
            .addQueryParameter("api", api)
            .addQueryParameter("version", if (api == COPY_MOVE) COPY_MOVE_VERSION else VERSION)
            .addQueryParameter("method", method)
            .apply { for ((k, v) in params) addQueryParameter(k, v) }
            .addQueryParameter("_sid", sid)
            .build()

    /**
     * Throws the refusal in [root] unless it says `success`. A method's own
     * code (900 for Delete, 1100 CreateFolder, 1200 Rename) only says it
     * failed; the file's reason, 408 for one that is not there, is in
     * `error.errors`, and that is the code given.
     */
    private fun check(root: JsonObject, what: String) {
        if (root["success"]?.jsonPrimitive?.booleanOrNull == true) return
        val error = root["error"]?.jsonObject
        val code = error?.get("code")?.jsonPrimitive?.intOrNull ?: 0
        val detail = runCatching { error?.get("errors")?.jsonArray?.firstOrNull()?.jsonObject?.get("code")?.jsonPrimitive?.intOrNull }.getOrNull()
        val reason = if (code in WRAPPING && detail != null) detail else code
        throw NasFs.Refused(reason, code in EXPIRED, what)
    }

    /** One GET call that must answer `success`; its whole answer. */
    private fun call(
        api: String,
        method: String,
        params: List<Pair<String, String>>,
        what: String,
        via: OkHttpClient = client,
    ): JsonObject = session { sid ->
        val root = send(Request.Builder().url(endpoint(api, method, sid, params)).get().build(), what, via) { objectOf(it, what) }
        check(root, what)
        root
    }

    // ---- Reading ---------------------------------------------------------------

    override fun list(dir: List<String>): List<RemoteEntry> {
        CloudFsHttp.checkNames(dir)
        return if (dir.isEmpty()) {
            pages("list_share", "shares", listOf("additional" to "[\"time\"]"))
        } else {
            pages("list", "files", listOf("folder_path" to pathOf(dir), "additional" to "[\"size\",\"time\"]"))
        }
    }

    /** Every page of a listing; [key] is the array the entries come in. */
    private fun pages(method: String, key: String, params: List<Pair<String, String>>): List<RemoteEntry> {
        val out = ArrayList<RemoteEntry>()
        var offset = 0
        while (true) {
            val root = call(
                "SYNO.FileStation.List", method,
                params + listOf("offset" to offset.toString(), "limit" to PAGE.toString()),
                "synology $method",
            )
            val data = root["data"]?.jsonObject
            val items = data?.get(key)?.jsonArray.orEmpty()
            items.mapNotNullTo(out) { entryOf(it.jsonObject) }
            offset += items.size
            val total = data?.get("total")?.jsonPrimitive?.intOrNull ?: 0
            if (items.isEmpty() || offset >= total) return out
        }
    }

    private fun entryOf(item: JsonObject): RemoteEntry? {
        val name = item["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return null
        val isDir = item["isdir"]?.jsonPrimitive?.booleanOrNull == true
        val extra = item["additional"]?.jsonObject
        return RemoteEntry(
            name = name,
            isDirectory = isDir,
            size = if (isDir) -1 else extra?.get("size")?.jsonPrimitive?.longOrNull ?: -1,
            modifiedMs = (extra?.get("time")?.jsonObject?.get("mtime")?.jsonPrimitive?.longOrNull ?: 0L) * 1000L,
        )
    }

    override fun download(path: List<String>, to: OutputStream) {
        CloudFsHttp.checkNames(path)
        refuseShareLevel(path, "synology download")
        session { sid ->
            val url = endpoint(
                "SYNO.FileStation.Download", "download", sid,
                listOf("path" to array(pathOf(path)), "mode" to "download"),
            )
            send(Request.Builder().url(url).get().build(), "synology download") { response ->
                // A refusal is a JSON answer with a 200; a file that is itself
                // JSON has no "success" in it, and is streamed like any other.
                if (response.header("Content-Type").orEmpty().contains("json")) {
                    val root = peekObject(response)
                    if (root != null && root.containsKey("success")) {
                        check(root, "synology download")
                        throw IOException("synology download answered with JSON, not the file")
                    }
                }
                CloudFsHttp.stream(response, to)
            }
        }
    }

    // ---- Changing --------------------------------------------------------------

    override fun upload(dir: List<String>, name: String, from: File, onSent: (Long) -> Unit) {
        CloudFsHttp.checkNames(dir + name)
        if (dir.isEmpty()) throw IOException("synology upload: files go inside a shared folder")
        val counter = SentCounter(onSent)
        session { sid ->
            // The file part last: DSM reads the fields in order and must know
            // where the file goes before it starts arriving. No "overwrite"
            // field, so a name that is somehow taken is an error.
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("path", pathOf(dir))
                .addFormDataPart("create_parents", "false")
                .addFormDataPart("file", name, FileSliceBody(from, 0, from.length(), CloudFsHttp.OCTET_STREAM, counter))
                .build()
            val url = endpoint("SYNO.FileStation.Upload", "upload", sid, emptyList())
            val root = send(Request.Builder().url(url).post(form).build(), "synology upload", slowClient) { objectOf(it, "synology upload") }
            check(root, "synology upload")
        }
    }

    override fun mkdir(dir: List<String>, name: String) {
        CloudFsHttp.checkNames(dir + name)
        if (dir.isEmpty()) throw IOException("synology mkdir: shared folders are made on the NAS itself")
        // force_parent off: a folder already there is an error, not a quiet success.
        call(
            "SYNO.FileStation.CreateFolder", "create",
            listOf("folder_path" to array(pathOf(dir)), "name" to array(name), "force_parent" to "false"),
            "synology mkdir",
        )
    }

    override fun delete(path: List<String>, isDirectory: Boolean) {
        CloudFsHttp.checkNames(path)
        refuseShareLevel(path, "synology delete")
        // The blocking "delete" method, which answers once it is done; never
        // recursive, so a folder with anything left in it is refused.
        try {
            call(
                "SYNO.FileStation.Delete", "delete",
                listOf("path" to array(pathOf(path)), "recursive" to "false"),
                "synology delete",
                slowClient,
            )
        } catch (failure: NasFs.Refused) {
            if (!isMissing(failure.code)) throw failure
        }
    }

    override fun rename(path: List<String>, newName: String) {
        CloudFsHttp.checkNames(path + newName)
        refuseShareLevel(path, "synology rename")
        ensureFree(path.dropLast(1), newName, except = path.last())
        call(
            "SYNO.FileStation.Rename", "rename",
            listOf("path" to array(pathOf(path)), "name" to array(newName)),
            "synology rename",
        )
    }

    /**
     * CopyMove with `remove_src`, polled to the end. Only true once the item
     * is at its new place and gone from the old one; anything else throws,
     * and the store copies instead (at worst leaving two copies, never none).
     */
    override fun move(path: List<String>, toDir: List<String>): Boolean {
        CloudFsHttp.checkNames(path + toDir)
        if (path.size < 2 || toDir.isEmpty()) return false
        ensureFree(toDir, path.last())
        // No "overwrite": DSM then stops on a name taken at the destination,
        // where "false" means skip and "true" replace.
        val started = call(
            COPY_MOVE, "start",
            listOf(
                "path" to array(pathOf(path)),
                "dest_folder_path" to JsonPrimitive(pathOf(toDir)).toString(),
                "remove_src" to "true",
                "accurate_progress" to "false",
            ),
            "synology move",
        )
        val task = started["data"]?.jsonObject?.get("taskid")?.jsonPrimitive?.contentOrNull
            ?: throw IOException("synology move: no task id")
        await(task)
        if (stat(toDir + path.last()) == null || stat(path) != null) {
            throw IOException("synology move finished without the item in its new place only")
        }
        return true
    }

    /**
     * Polls a CopyMove task until DSM says it finished. A task whose status
     * stops changing for [STALL_MS] is given up on (and left running on the
     * NAS: stopping a move halfway could leave half a copy at the destination).
     */
    private fun await(task: String) {
        var last = ""
        var lastChange = System.currentTimeMillis()
        var pause = FIRST_PAUSE_MS
        while (true) {
            val data = status(task)
            if (data["finished"]?.jsonPrimitive?.booleanOrNull == true) return
            val shape = data.toString()
            val now = System.currentTimeMillis()
            if (shape != last) {
                last = shape
                lastChange = now
            } else if (now - lastChange > STALL_MS) {
                throw IOException("synology move made no progress for ${STALL_MS / 60_000} minutes; left running on the NAS")
            }
            Thread.sleep(pause)
            pause = (pause * 2).coerceAtMost(MAX_PAUSE_MS)
        }
    }

    /**
     * One status answer. The task id goes as a JSON string, the form the API
     * guide's examples show; if DSM calls that an invalid parameter it goes
     * again bare.
     */
    private fun status(task: String): JsonObject {
        fun ask(quoted: Boolean): JsonObject = call(
            COPY_MOVE, "status",
            listOf("taskid" to if (quoted) JsonPrimitive(task).toString() else task),
            "synology move status",
        )["data"]?.jsonObject ?: throw IOException("synology move status: no data")
        if (bareTaskId) return ask(false)
        return try {
            ask(true)
        } catch (failure: NasFs.Refused) {
            if (failure.code != BAD_PARAMETER && failure.code != NO_PARAMETER) throw failure
            ask(false).also { bareTaskId = true }
        }
    }

    companion object {
        private const val VERSION = "2"
        private const val COPY_MOVE = "SYNO.FileStation.CopyMove"
        private const val COPY_MOVE_VERSION = "3"

        /** A method's own "it failed" (Delete, CopyMove, CreateFolder, Rename), whose reason is in `errors`. */
        private val WRAPPING = setOf(900, 1000, 1001, 1002, 1100, 1200)
        private const val PAGE = 1000

        private const val NO_PARAMETER = 101
        private const val BAD_PARAMETER = 120
        private const val NO_SUCH_FILE = 408

        /** Session timed out, interrupted by another login, or its id unknown. */
        private val EXPIRED = setOf(106, 107, 119)

        private const val FIRST_PAUSE_MS = 200L
        private const val MAX_PAUSE_MS = 2_000L
        private const val STALL_MS = 10 * 60_000L
    }
}
