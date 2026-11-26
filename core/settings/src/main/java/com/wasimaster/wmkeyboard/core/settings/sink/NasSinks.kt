package com.wasimaster.wmkeyboard.core.settings.sink

import com.wasimaster.wmkeyboard.core.settings.NasConfig
import com.wasimaster.wmkeyboard.core.util.runCancellable
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * The parts a Synology and a QNAP location share: where the NAS is, the TLS
 * rules for reaching it, and one sign-in per sink.
 *
 * **HTTPS only**, as for WebDAV, with the same exception for an address on a
 * Tailscale network: both logins send the password in the request. Most NAS
 * boxes on a home network answer HTTPS with a certificate they made
 * themselves, which no authority vouches for; [NasConfig.ownCertificate]
 * accepts that one and pins it ([CertPin]).
 *
 * Each sink signs in on its first call and keeps the session for the run. A
 * call that comes back "session expired" signs in again once.
 */
abstract class NasSink(
    protected val config: NasConfig,
    private val defaultPort: Int,
    /** Records the certificate the first connection saw; see [NasConfig.certPin]. */
    private val onCertificate: (String) -> Unit,
) : BackupSink {

    protected val json = Json { ignoreUnknownKeys = true }

    private val address: HttpUrl? by lazy { baseUrl(config.server, defaultPort) }

    protected val client: OkHttpClient by lazy {
        BackupHttp.client(pin = if (config.ownCertificate) CertPin(config.certPin, onCertificate) else null)
    }

    /** The folder, with a leading slash and none at the end. */
    protected val folder: String =
        "/" + config.path.replace('\\', '/').split('/').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("/")

    @Volatile
    protected var session: String? = null

    protected abstract fun login(): String

    /** Whether a failed call means the session is gone, and a new one is worth one try. */
    protected abstract fun isExpired(failure: NasFailure): Boolean

    protected fun base(): HttpUrl {
        val url = address ?: throw BackupSinkException(SinkError.NOT_CONFIGURED)
        if (url.scheme != "https" && !WebDavSink.isTailnet(url.toString())) throw BackupSinkException(SinkError.NOT_CONFIGURED)
        return url
    }

    /**
     * This sink's client, certificate pin and all, for the Files tool's NAS
     * adapters: they sign in through this sink and must then hold the NAS to
     * the very certificate that sign-in was held to, not a second pin of
     * their own that a first connection could set differently.
     */
    internal val http: OkHttpClient get() = client

    /** The NAS's address under the same HTTPS-only rule as [base], for the same adapters. */
    internal fun address(): HttpUrl = base()

    /** Runs [block] with a session, signing in again once if the NAS says it expired. */
    protected fun <T> withSession(block: (String) -> T): T {
        val sid = session ?: login().also { session = it }
        return try {
            block(sid)
        } catch (failure: NasFailure) {
            if (!isExpired(failure)) throw BackupSinkException(failure.error)
            session = null
            val again = login().also { session = it }
            try {
                block(again)
            } catch (second: NasFailure) {
                throw BackupSinkException(second.error)
            }
        }
    }

    /** One request: connection failures mapped, the body handed to [read] on a 2xx. */
    protected fun <T> send(request: Request, what: String, read: (Response) -> T): T {
        val response = try {
            client.newCall(request).execute()
        } catch (failure: Throwable) {
            if (failure is BackupSinkException) throw failure
            val error = if (BackupHttp.isCertificateRefusal(failure)) SinkError.UNSAFE else SinkError.IO
            BackupLog.w("$what failed to connect => $error", failure)
            throw BackupSinkException(error, failure)
        }
        response.use {
            if (!it.isSuccessful) {
                BackupLog.w("$what -> HTTP ${it.code}")
                throw BackupSinkException(
                    when (it.code) {
                        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> SinkError.PERMISSION_LOST
                        HTTP_NOT_FOUND -> SinkError.TARGET_MISSING
                        else -> SinkError.IO
                    },
                )
            }
            return read(it)
        }
    }

    protected fun pathOf(name: String) = if (folder == "/") "/$name" else "$folder/$name"

    /** A failure the NAS reported in its answer, by its own code. */
    class NasFailure(val code: Int, val error: SinkError) : Exception("nas $code")

    companion object {
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404

        /**
         * The address for what the user typed: `nas.local`, `nas.local:5001`,
         * or a whole URL. A bare host gets HTTPS on [defaultPort]. Public for
         * the tests.
         */
        fun baseUrl(server: String, defaultPort: Int): HttpUrl? {
            val typed = server.trim().trimEnd('/')
            if (typed.isEmpty()) return null
            val withScheme = if (typed.contains("://")) typed else "https://$typed"
            val parsed = withScheme.toHttpUrlOrNull() ?: return null
            val explicitPort = Regex("^[a-z]+://[^/]+:\\d+", RegexOption.IGNORE_CASE).containsMatchIn(withScheme)
            return parsed.newBuilder().apply { if (!explicitPort) port(defaultPort) }.encodedPath("/").build()
        }

        internal val OCTET_STREAM = "application/octet-stream".toMediaTypeOrNull()
    }
}

/**
 * A [BackupSink] over a folder on a Synology NAS, through DSM's File Station
 * API (`/webapi/entry.cgi`). Synology Drive's own "My Drive" is the folder
 * `/home/Drive`, so a path under it is a Synology Drive backup; any shared
 * folder works the same way.
 *
 * Signs in with [NasConfig.deviceToken] when there is one, which is what lets
 * an account with 2FA sign in at night with nobody to type a code. The token
 * comes from [NasLogin.synology], run once from the settings screen.
 */
class SynologySink(config: NasConfig, onCertificate: (String) -> Unit = {}) :
    NasSink(config, DEFAULT_PORT, onCertificate) {

    override val id: String get() = ID

    override fun login(): String {
        if (!config.configured) throw BackupSinkException(SinkError.NOT_CONFIGURED)
        val answer = authenticate(otp = "")
        return answer.sid ?: throw BackupSinkException(answer.error ?: SinkError.IO)
    }

    /** The sign-in itself, for the sink and for [NasLogin]. */
    internal fun authenticate(otp: String): NasLogin.Answer {
        val form = FormBody.Builder()
            .add("api", "SYNO.API.Auth")
            .add("version", "6")
            .add("method", "login")
            .add("account", config.user.trim())
            .add("passwd", config.password)
            .add("session", "FileStation")
            .add("format", "sid")
            .apply {
                if (otp.isNotEmpty()) {
                    add("otp_code", otp.filter { !it.isWhitespace() })
                    add("enable_device_token", "yes")
                    add("device_name", DEVICE_NAME)
                } else if (config.deviceToken.isNotEmpty()) {
                    add("device_name", DEVICE_NAME)
                    add("device_id", config.deviceToken)
                }
            }
            .build()
        val url = base().newBuilder().addPathSegments("webapi/entry.cgi").build()
        val root = send(Request.Builder().url(url).post(form).build(), "synology login") { parse(it) }
        if (root["success"]?.jsonPrimitive?.booleanOrNull == true) {
            val data = root["data"]?.jsonObject
            return NasLogin.Answer(
                sid = data?.get("sid")?.jsonPrimitive?.contentOrNull,
                deviceToken = data?.get("did")?.jsonPrimitive?.contentOrNull,
            )
        }
        val code = errorCode(root)
        BackupLog.w("synology login -> error $code")
        return when (code) {
            AUTH_NEEDS_OTP, AUTH_ENFORCE_OTP -> NasLogin.Answer(needsCode = true, error = SinkError.PERMISSION_LOST)
            else -> NasLogin.Answer(error = authError(code))
        }
    }

    override fun isExpired(failure: NasFailure): Boolean =
        failure.code == SESSION_TIMEOUT || failure.code == SESSION_INTERRUPTED || failure.code == SID_NOT_FOUND

    private fun api(api: String, method: String, vararg query: Pair<String, String>): (String) -> HttpUrl = { sid ->
        base().newBuilder().addPathSegments("webapi/entry.cgi")
            .addQueryParameter("api", api)
            .addQueryParameter("version", "2")
            .addQueryParameter("method", method)
            .apply { for ((k, v) in query) addQueryParameter(k, v) }
            .addQueryParameter("_sid", sid)
            .build()
    }

    private fun ok(url: (String) -> HttpUrl, what: String): JsonObject = withSession { sid ->
        val root = send(Request.Builder().url(url(sid)).get().build(), what) { parse(it) }
        if (root["success"]?.jsonPrimitive?.booleanOrNull != true) {
            val code = errorCode(root)
            BackupLog.w("$what -> error $code")
            throw NasFailure(code, fileError(code))
        }
        root
    }

    override suspend fun readiness(): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable { makeFolder() }
    }

    private fun makeFolder() {
        if (folder == "/") return
        val parent = folder.substringBeforeLast('/').ifEmpty { "/" }
        val leaf = folder.substringAfterLast('/')
        // force_parent makes every missing level; a folder already there is
        // no error. The top level is a shared folder, which only DSM's own
        // settings can make.
        ok(
            api(
                "SYNO.FileStation.CreateFolder", "create",
                "folder_path" to JsonArray(listOf(JsonPrimitive(parent))).toString(),
                "name" to JsonArray(listOf(JsonPrimitive(leaf))).toString(),
                "force_parent" to "true",
            ),
            "synology mkdir",
        )
    }

    override suspend fun write(
        name: String,
        mimeType: String,
        body: (OutputStream) -> Unit,
    ): Result<SinkEntry> = withContext(Dispatchers.IO) {
        runCancellable {
            val bytes = ByteArrayOutputStream().also(body).toByteArray()
            makeFolder()
            withSession { sid ->
                // The file part last: DSM reads the fields in order, and the
                // path has to be known before the file starts arriving.
                val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("path", folder)
                    .addFormDataPart("create_parents", "true")
                    .addFormDataPart("overwrite", "true")
                    .addFormDataPart("file", name, bytes.toRequestBody(OCTET_STREAM))
                    .build()
                val root = send(
                    Request.Builder().url(api("SYNO.FileStation.Upload", "upload")(sid)).post(form).build(),
                    "synology upload",
                ) { parse(it) }
                if (root["success"]?.jsonPrimitive?.booleanOrNull != true) {
                    val code = errorCode(root)
                    BackupLog.w("synology upload -> error $code")
                    throw NasFailure(code, fileError(code))
                }
            }
            SinkEntry(pathOf(name), name, bytes.size.toLong(), 0L)
        }
    }

    override suspend fun list(): Result<List<SinkEntry>> = withContext(Dispatchers.IO) {
        runCancellable {
            val out = ArrayList<SinkEntry>()
            var offset = 0
            do {
                val root = ok(
                    api(
                        "SYNO.FileStation.List", "list",
                        "folder_path" to folder,
                        "offset" to offset.toString(),
                        "limit" to PAGE.toString(),
                        "additional" to "[\"size\",\"time\"]",
                    ),
                    "synology list",
                )
                val data = root["data"]?.jsonObject
                val files = data?.get("files")?.jsonArray.orEmpty()
                for (element in files) {
                    val file = element.jsonObject
                    if (file["isdir"]?.jsonPrimitive?.booleanOrNull == true) continue
                    val name = file["name"]?.jsonPrimitive?.contentOrNull ?: continue
                    if (!AutoBackupNaming.isListed(name)) continue
                    val extra = file["additional"]?.jsonObject
                    out += SinkEntry(
                        id = file["path"]?.jsonPrimitive?.contentOrNull ?: pathOf(name),
                        name = name,
                        sizeBytes = extra?.get("size")?.jsonPrimitive?.longOrNull ?: -1L,
                        modifiedAtMs = (extra?.get("time")?.jsonObject?.get("mtime")?.jsonPrimitive?.longOrNull ?: 0L) * 1000L,
                    )
                }
                offset += files.size
                val total = data?.get("total")?.jsonPrimitive?.intOrNull ?: 0
            } while (files.isNotEmpty() && offset < total)
            out
        }
    }

    override suspend fun read(entry: SinkEntry): Result<InputStream> = withContext(Dispatchers.IO) {
        runCancellable {
            withSession { sid ->
                val url = api("SYNO.FileStation.Download", "download", "path" to pathOf(entry.name), "mode" to "download")(sid)
                send(Request.Builder().url(url).get().build(), "synology download") { response ->
                    // A failure is a JSON answer with a 200; a file is bytes.
                    val type = response.header("Content-Type").orEmpty()
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    if (type.startsWith("application/json")) {
                        val code = runCatching { errorCode(json.parseToJsonElement(bytes.decodeToString()).jsonObject) }.getOrDefault(0)
                        throw NasFailure(code, fileError(code))
                    }
                    bytes.inputStream()
                }
            }
        }
    }

    override suspend fun delete(entry: SinkEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable {
            try {
                ok(
                    api(
                        "SYNO.FileStation.Delete", "delete",
                        "path" to JsonArray(listOf(JsonPrimitive(pathOf(entry.name)))).toString(),
                        "recursive" to "false",
                    ),
                    "synology delete",
                )
            } catch (gone: BackupSinkException) {
                if (gone.reason != SinkError.TARGET_MISSING) throw gone
            }
            Unit
        }
    }

    private fun parse(response: Response): JsonObject =
        runCatching { json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject }.getOrNull()
            ?: throw BackupSinkException(SinkError.IO)

    private fun errorCode(root: JsonObject): Int =
        root["error"]?.jsonObject?.get("code")?.jsonPrimitive?.intOrNull ?: 0

    companion object {
        const val ID = "synology"
        const val DEFAULT_PORT = 5001
        private const val DEVICE_NAME = "WM Keyboard"
        private const val PAGE = 1000

        private const val SESSION_TIMEOUT = 106
        private const val SESSION_INTERRUPTED = 107
        private const val SID_NOT_FOUND = 119
        private const val AUTH_NEEDS_OTP = 403
        private const val AUTH_ENFORCE_OTP = 406

        /**
         * What a failed sign-in means: 400 is a wrong name or password, 401 a
         * disabled account, 402 one without permission, 404 a wrong 2FA code,
         * 407 an address DSM has blocked. Public for the tests.
         */
        fun authError(code: Int): SinkError = when (code) {
            400, 401, 402, 403, 404, 406, 408, 409, 410 -> SinkError.PERMISSION_LOST
            else -> SinkError.IO
        }

        /**
         * What a File Station error means: 105 and 407 are a missing
         * permission, 408 a missing file or folder, 415 and 416 a full quota
         * or disk, 411 a read-only volume. Public for the tests.
         */
        fun fileError(code: Int): SinkError = when (code) {
            105, 106, 107, 119, 403, 407, 411 -> SinkError.PERMISSION_LOST
            408, 599 -> SinkError.TARGET_MISSING
            415, 416 -> SinkError.OUT_OF_SPACE
            else -> SinkError.IO
        }
    }
}

/**
 * A [BackupSink] over a folder on a QNAP NAS, through the QTS File Station
 * API (`/cgi-bin/filemanager/utilRequest.cgi`), signed in by
 * `/cgi-bin/authLogin.cgi`.
 *
 * QTS answers every file call with HTTP 200 and a `status` number, 1 for
 * success; [statusError] reads the rest. [NasConfig.deviceToken] holds the
 * `qtoken` a sign-in with a two-step code hands back, which lets later
 * sign-ins skip the code.
 */
class QnapSink(config: NasConfig, onCertificate: (String) -> Unit = {}) :
    NasSink(config, DEFAULT_PORT, onCertificate) {

    override val id: String get() = ID

    override fun login(): String {
        if (!config.configured) throw BackupSinkException(SinkError.NOT_CONFIGURED)
        val answer = authenticate(code = "")
        return answer.sid ?: throw BackupSinkException(answer.error ?: SinkError.IO)
    }

    internal fun authenticate(code: String): NasLogin.Answer {
        val form = FormBody.Builder()
            .add("user", config.user.trim())
            // QTS's own page sends the password base64 encoded ("ezEncode"),
            // and so the server expects it that way.
            .add("pwd", kotlin.io.encoding.Base64.encode(config.password.toByteArray(Charsets.UTF_8)))
            .add("serviceKey", "1")
            .add("remme", "1")
            .apply {
                if (code.isNotEmpty()) add("security_code", code.filter { !it.isWhitespace() })
                if (config.deviceToken.isNotEmpty()) add("qtoken", config.deviceToken)
            }
            .build()
        val url = base().newBuilder().addPathSegments("cgi-bin/authLogin.cgi").build()
        val xml = send(Request.Builder().url(url).post(form).build(), "qnap login") { it.body?.string().orEmpty() }
        val sid = tag(xml, "authSid")
        if (tag(xml, "authPassed") == "1" && !sid.isNullOrEmpty()) {
            return NasLogin.Answer(sid = sid, deviceToken = tag(xml, "qtoken")?.takeIf { it.isNotEmpty() })
        }
        BackupLog.w("qnap login refused (2sv=${tag(xml, "need_2sv")})")
        return if (tag(xml, "need_2sv") == "1") {
            NasLogin.Answer(needsCode = true, error = SinkError.PERMISSION_LOST)
        } else {
            NasLogin.Answer(error = SinkError.PERMISSION_LOST)
        }
    }

    override fun isExpired(failure: NasFailure): Boolean = failure.code == STATUS_AUTH_FAIL

    private fun util(func: String, vararg query: Pair<String, String>): (String) -> HttpUrl = { sid ->
        base().newBuilder().addPathSegments("cgi-bin/filemanager/utilRequest.cgi")
            .addQueryParameter("func", func)
            .addQueryParameter("sid", sid)
            .apply { for ((k, v) in query) addQueryParameter(k, v) }
            .build()
    }

    private fun status(root: JsonObject, what: String, allowed: Set<Int> = emptySet()) {
        val status = root["status"]?.jsonPrimitive?.intOrNull ?: STATUS_SUCCESS
        if (status == STATUS_SUCCESS || status in allowed) return
        BackupLog.w("$what -> status $status")
        throw NasFailure(status, statusError(status))
    }

    private fun get(url: (String) -> HttpUrl, what: String, allowed: Set<Int> = emptySet()): JsonObject =
        withSession { sid ->
            val root = send(Request.Builder().url(url(sid)).get().build(), what) { parse(it) }
            status(root, what, allowed)
            root
        }

    override suspend fun readiness(): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable { makeFolder() }
    }

    /** Makes each level below the share; a level already there answers "name taken". */
    private fun makeFolder() {
        val parts = folder.split('/').filter { it.isNotEmpty() }
        var at = "/" + (parts.firstOrNull() ?: return)
        for (part in parts.drop(1)) {
            get(util("createdir", "dest_folder" to part, "dest_path" to at), "qnap mkdir", allowed = setOf(STATUS_EXISTS, STATUS_NAME_DUP))
            at += "/$part"
        }
    }

    override suspend fun write(
        name: String,
        mimeType: String,
        body: (OutputStream) -> Unit,
    ): Result<SinkEntry> = withContext(Dispatchers.IO) {
        runCancellable {
            val bytes = ByteArrayOutputStream().also(body).toByteArray()
            makeFolder()
            withSession { sid ->
                val url = util(
                    "upload",
                    "type" to "standard",
                    "dest_path" to folder,
                    "overwrite" to "1",
                    "progress" to pathOf(name).replace('/', '-'),
                )(sid)
                val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", name, bytes.toRequestBody(OCTET_STREAM))
                    .build()
                val root = send(Request.Builder().url(url).post(form).build(), "qnap upload") { parse(it) }
                status(root, "qnap upload")
            }
            SinkEntry(pathOf(name), name, bytes.size.toLong(), 0L)
        }
    }

    override suspend fun list(): Result<List<SinkEntry>> = withContext(Dispatchers.IO) {
        runCancellable {
            val out = ArrayList<SinkEntry>()
            var start = 0
            do {
                val root = get(
                    util(
                        "get_list",
                        "path" to folder,
                        "list_mode" to "all",
                        "is_iso" to "0",
                        "start" to start.toString(),
                        "limit" to PAGE.toString(),
                        "sort" to "filename",
                        "dir" to "ASC",
                        "hidden_file" to "0",
                    ),
                    "qnap list",
                )
                val files = root["datas"]?.jsonArray.orEmpty()
                for (element in files) {
                    val file = element.jsonObject
                    if (file["isfolder"]?.jsonPrimitive?.intOrNull == 1) continue
                    val name = file["filename"]?.jsonPrimitive?.contentOrNull ?: continue
                    if (!AutoBackupNaming.isListed(name)) continue
                    out += SinkEntry(
                        id = pathOf(name),
                        name = name,
                        sizeBytes = file["filesize"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: -1L,
                        modifiedAtMs = (file["epochmt"]?.jsonPrimitive?.longOrNull ?: 0L) * 1000L,
                    )
                }
                start += files.size
                val total = root["total"]?.jsonPrimitive?.intOrNull ?: 0
            } while (files.isNotEmpty() && start < total)
            out
        }
    }

    override suspend fun read(entry: SinkEntry): Result<InputStream> = withContext(Dispatchers.IO) {
        runCancellable {
            withSession { sid ->
                val url = util(
                    "download",
                    "isfolder" to "0",
                    "source_path" to folder,
                    "source_file" to entry.name,
                    "source_total" to "1",
                )(sid)
                send(Request.Builder().url(url).get().build(), "qnap download") { response ->
                    val type = response.header("Content-Type").orEmpty()
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    if (type.contains("json")) {
                        val root = runCatching { json.parseToJsonElement(bytes.decodeToString()).jsonObject }.getOrNull()
                        if (root != null) status(root, "qnap download")
                    }
                    bytes.inputStream()
                }
            }
        }
    }

    override suspend fun delete(entry: SinkEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable {
            get(
                util("delete", "path" to folder, "file_total" to "1", "file_name" to entry.name, "v" to "1"),
                "qnap delete",
                allowed = setOf(STATUS_NO_EXIST),
            )
            Unit
        }
    }

    private fun parse(response: Response): JsonObject =
        runCatching { json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject }.getOrNull()
            ?: throw BackupSinkException(SinkError.IO)

    companion object {
        const val ID = "qnap"
        const val DEFAULT_PORT = 443
        private const val PAGE = 500

        private const val STATUS_SUCCESS = 1
        private const val STATUS_EXISTS = 2
        private const val STATUS_AUTH_FAIL = 3
        private const val STATUS_NO_EXIST = 5
        private const val STATUS_NAME_DUP = 33

        /** One field of authLogin.cgi's XML, with or without CDATA. Public for the tests. */
        fun tag(xml: String, name: String): String? =
            Regex("<$name>\\s*(?:<!\\[CDATA\\[(.*?)]]>|([^<]*))\\s*</$name>", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.let { (it.groupValues[1].ifEmpty { it.groupValues[2] }).trim() }

        /**
         * What a QTS File Station `status` means: 3 a session that is gone,
         * 4, 10, 11 and 69 a missing permission or two-step check, 5 and 25 a
         * missing file or folder, 9 a full quota. Public for the tests.
         */
        fun statusError(status: Int): SinkError = when (status) {
            3, 4, 10, 11, 69 -> SinkError.PERMISSION_LOST
            5, 25 -> SinkError.TARGET_MISSING
            9 -> SinkError.OUT_OF_SPACE
            else -> SinkError.IO
        }
    }
}

/**
 * The settings screen's sign-in to a NAS: tried once with the password, and
 * again with a 2FA code if the NAS asks for one. A code sign-in hands back
 * the device token the location keeps.
 */
object NasLogin {

    data class Answer(
        val sid: String? = null,
        val deviceToken: String? = null,
        val needsCode: Boolean = false,
        val error: SinkError? = null,
    )

    suspend fun synology(config: NasConfig, code: String, onCertificate: (String) -> Unit = {}): Answer =
        withContext(Dispatchers.IO) {
            runCatching { SynologySink(config, onCertificate).authenticate(code) }
                .getOrElse { Answer(error = (it as? BackupSinkException)?.reason ?: SinkError.IO) }
        }

    suspend fun qnap(config: NasConfig, code: String, onCertificate: (String) -> Unit = {}): Answer =
        withContext(Dispatchers.IO) {
            runCatching { QnapSink(config, onCertificate).authenticate(code) }
                .getOrElse { Answer(error = (it as? BackupSinkException)?.reason ?: SinkError.IO) }
        }
}
