package com.wasimaster.wmkeyboard.core.settings.sink

import com.wasimaster.wmkeyboard.core.settings.BoxConfig
import com.wasimaster.wmkeyboard.core.util.runCancellable
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * A [BackupSink] over a folder in the user's Box, through the Box REST API.
 *
 * Box has no app-folder scope like Dropbox's and OneDrive's, so the folder is
 * an ordinary one ([BoxConfig.folder], made on first use) and the grant is
 * the whole account's. Everything is addressed by id: the folder's is looked
 * up by name from the top once per sink, and a file's comes back with the
 * listing.
 *
 * Box's refresh tokens are single use and last 60 days, so every refresh
 * hands back a new one, which [onRefreshTokenRotated] stores before anything
 * else happens. A location left unused for two months has to be signed in to
 * again. The Files tool refreshes the same token, so a refused one is
 * checked against [storedRefreshToken], the one saved now, before the
 * location counts as signed out.
 */
class BoxSink(
    refreshToken: String,
    private val tokens: OAuthTokens,
    private val config: BoxConfig,
    private val storedRefreshToken: () -> String? = { null },
    private val onRefreshTokenRotated: (String) -> Unit = {},
) : BackupSink {

    @Volatile
    private var refreshToken: String = refreshToken

    @Volatile
    private var folderId: String? = null

    override val id: String get() = ID

    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy { BackupHttp.client() }

    private fun bearer(): String {
        val onRotated = { rotated: String ->
            refreshToken = rotated
            onRefreshTokenRotated(rotated)
        }
        tokens.accessToken(refreshToken, onRotated = onRotated)?.let { return it }
        // Single use: the Files tool may have spent the one this sink started
        // with and saved its successor.
        val stored = runCatching { storedRefreshToken() }.getOrNull()
        if (stored != null && stored != refreshToken) {
            refreshToken = stored
            tokens.accessToken(stored, onRotated = onRotated)?.let { return it }
        }
        BackupLog.w("box: refresh refused")
        throw BackupSinkException(SinkError.PERMISSION_LOST)
    }

    private fun authorized(url: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer ${bearer()}")

    override suspend fun readiness(): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable {
            folder()
            Unit
        }
    }

    override suspend fun write(
        name: String,
        mimeType: String,
        body: (OutputStream) -> Unit,
    ): Result<SinkEntry> = withContext(Dispatchers.IO) {
        runCancellable {
            val bytes = ByteArrayOutputStream().also(body).toByteArray()
            val parent = folder()
            BackupLog.d("box write $name ${bytes.size} B")
            val uploaded = try {
                upload("$UPLOAD/files/content", attributes(name, parent), name, bytes)
            } catch (clash: BoxConflict) {
                // A file of this name is an older copy of ours: a new version
                // of it rather than a second file, which Box would refuse.
                val existing = clash.id ?: throw BackupSinkException(SinkError.IO)
                upload("$UPLOAD/files/$existing/content", buildJsonObject { put("name", JsonPrimitive(name)) }, name, bytes)
            }
            uploaded ?: SinkEntry(name, name, bytes.size.toLong(), 0L)
        }
    }

    override suspend fun list(): Result<List<SinkEntry>> = withContext(Dispatchers.IO) {
        runCancellable {
            items(folder()).filter { it.second == "file" && AutoBackupNaming.isListed(it.first.name) }.map { it.first }
        }
    }

    override suspend fun read(entry: SinkEntry): Result<InputStream> = withContext(Dispatchers.IO) {
        runCancellable {
            // A redirect to a signed download address, which OkHttp follows
            // and sends without our token, as it should.
            call(authorized("$API/files/${entry.id}/content").get().build()) { it.body?.bytes() ?: ByteArray(0) }
                .inputStream()
        }
    }

    override suspend fun delete(entry: SinkEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable {
            call(authorized("$API/files/${entry.id}").delete().build(), allowMissing = true) { }
        }
    }

    /** The backup folder's id, made level by level where missing. */
    private fun folder(): String {
        folderId?.let { return it }
        var at = ROOT_ID
        for (part in config.folder.split('/').map { it.trim() }.filter { it.isNotEmpty() }) {
            at = items(at).firstOrNull { it.second == "folder" && it.first.name == part }?.first?.id
                ?: makeFolder(at, part)
        }
        return at.also { folderId = it }
    }

    private fun makeFolder(parent: String, name: String): String {
        val body = buildJsonObject {
            put("name", JsonPrimitive(name))
            put("parent", buildJsonObject { put("id", JsonPrimitive(parent)) })
        }
        return try {
            val created = call(authorized("$API/folders").post(body.toString().toRequestBody(JSON_TYPE)).build()) {
                it.body?.string().orEmpty()
            }
            json.parseToJsonElement(created).jsonObject["id"]?.jsonPrimitive?.contentOrNull
                ?: throw BackupSinkException(SinkError.IO)
        } catch (clash: BoxConflict) {
            // Made meanwhile, by another run or by hand.
            clash.id ?: throw BackupSinkException(SinkError.IO)
        }
    }

    /** Every item in a folder, with its Box type: `file`, `folder` or `web_link`. */
    private fun items(folder: String): List<Pair<SinkEntry, String>> {
        val out = ArrayList<Pair<SinkEntry, String>>()
        var offset = 0L
        do {
            val page = call(
                authorized("$API/folders/$folder/items?fields=id,name,size,modified_at,type&limit=$PAGE&offset=$offset")
                    .get().build(),
            ) { it.body?.string().orEmpty() }
            val root = runCatching { json.parseToJsonElement(page).jsonObject }.getOrNull()
                ?: throw BackupSinkException(SinkError.IO)
            val entries = root["entries"]?.jsonArray.orEmpty()
            for (element in entries) {
                val item = element.jsonObject
                val entry = entryOf(item) ?: continue
                out += entry to item["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            offset += entries.size
            val total = root["total_count"]?.jsonPrimitive?.longOrNull ?: 0L
        } while (entries.isNotEmpty() && offset < total)
        return out
    }

    private fun attributes(name: String, parent: String) = buildJsonObject {
        put("name", JsonPrimitive(name))
        put("parent", buildJsonObject { put("id", JsonPrimitive(parent)) })
    }

    private fun upload(url: String, attributes: JsonObject, name: String, bytes: ByteArray): SinkEntry? {
        // Attributes first: Box reads the parts in order and needs to know
        // where the file goes before the file itself arrives.
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("attributes", attributes.toString())
            .addFormDataPart("file", name, bytes.toRequestBody(OCTET_STREAM))
            .build()
        val answer = call(authorized(url).post(form).build()) { it.body?.string().orEmpty() }
        val first = runCatching {
            json.parseToJsonElement(answer).jsonObject["entries"]?.jsonArray?.firstOrNull()?.jsonObject
        }.getOrNull()
        return entryOf(first)
    }

    private fun entryOf(item: JsonObject?): SinkEntry? {
        val id = item?.get("id")?.jsonPrimitive?.contentOrNull ?: return null
        val name = item["name"]?.jsonPrimitive?.contentOrNull ?: return null
        return SinkEntry(
            id = id,
            name = name,
            sizeBytes = item["size"]?.jsonPrimitive?.longOrNull ?: -1L,
            modifiedAtMs = item["modified_at"]?.jsonPrimitive?.contentOrNull?.let(::parseOffsetTime) ?: 0L,
        )
    }

    /** A 409, with the id of what is already there when Box said. */
    private class BoxConflict(val id: String?) : Exception()

    private fun <T> call(request: Request, allowMissing: Boolean = false, read: (Response) -> T): T {
        val what = "${request.method} ${request.url.encodedPath}"
        val response = try {
            client.newCall(request).execute()
        } catch (failure: Throwable) {
            if (failure is BackupSinkException) throw failure
            BackupLog.w("box $what failed to connect", failure)
            throw BackupSinkException(SinkError.IO, failure)
        }
        response.use {
            BackupLog.d("box $what -> ${it.code}")
            if (it.isSuccessful) return read(it)
            if (allowMissing && it.code == HTTP_NOT_FOUND) return read(it)
            val body = it.peekBody(ERROR_PEEK).string()
            if (it.code == HTTP_CONFLICT) throw BoxConflict(conflictId(body))
            val error = statusError(it.code, body)
            BackupLog.w("box $what -> ${it.code} => $error")
            throw BackupSinkException(error)
        }
    }

    private fun conflictId(body: String): String? = runCatching {
        val conflicts = json.parseToJsonElement(body).jsonObject["context_info"]?.jsonObject?.get("conflicts")
        // An object for a file, an array of one for a folder.
        val item = runCatching { conflicts?.jsonArray?.firstOrNull()?.jsonObject }.getOrNull() ?: conflicts?.jsonObject
        item?.get("id")?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    companion object {
        const val ID = "box"

        const val AUTHORIZE_URL = "https://account.box.com/api/oauth2/authorize"
        const val TOKEN_URL = "https://api.box.com/oauth2/token"

        /**
         * Box takes only HTTPS redirect addresses (or a loopback one), never
         * a custom scheme, so the sign-in comes back through a page on the
         * docs site that hands the code on to `wmkeyboard://oauth`. See
         * docs/public/oauth/index.html.
         */
        const val REDIRECT_URI = "https://wmkeyboard.pages.dev/oauth/"

        private const val API = "https://api.box.com/2.0"
        private const val UPLOAD = "https://upload.box.com/api/2.0"
        private const val ROOT_ID = "0"
        private const val PAGE = 1000

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_CONFLICT = 409
        private const val HTTP_TOO_LARGE = 413
        private const val HTTP_INSUFFICIENT_STORAGE = 507
        private const val ERROR_PEEK = 4096L

        /** What a Box failure means to the user. Public for the tests. */
        fun statusError(code: Int, body: String = ""): SinkError = when {
            body.contains("storage_limit_exceeded") || code == HTTP_INSUFFICIENT_STORAGE -> SinkError.OUT_OF_SPACE
            code == HTTP_TOO_LARGE -> SinkError.OUT_OF_SPACE
            code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN -> SinkError.PERMISSION_LOST
            code == HTTP_NOT_FOUND -> SinkError.TARGET_MISSING
            else -> SinkError.IO
        }

        /** Box writes `2012-12-12T10:53:43-08:00`, with the offset that [S3Listing.parseIso8601] drops. */
        fun parseOffsetTime(value: String): Long = runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).parse(value)?.time ?: 0L
        }.getOrDefault(0L)

        private val JSON_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
        private val OCTET_STREAM = "application/octet-stream".toMediaTypeOrNull()
    }
}
