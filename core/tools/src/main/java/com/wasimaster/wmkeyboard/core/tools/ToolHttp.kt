package com.wasimaster.wmkeyboard.core.tools

import android.content.Context
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.core.netlog.NetCall
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.netlog.NoInternetPermissionException
import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import com.wasimaster.wmkeyboard.core.netlog.track
import com.wasimaster.wmkeyboard.tools.R
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import com.wasimaster.wmkeyboard.common.R as CommonR

/**
 * A tool network call that failed with something the user should read.
 *
 * [apiMessage] holds the provider's own words when the response carried any;
 * those are already in the provider's language and pass straight through.
 * Otherwise [messageRes] names our wording and [status] is the one argument it
 * takes. [ToolHttp.friendlyMessage] turns the pair into a line for a panel.
 *
 * [headers] carries the response headers the caller asked [ToolHttp.getWithHeaders]
 * to keep, and is empty for every other caller. It exists because some providers
 * answer "you are out of requests" with the same status they use for "your key is
 * wrong" — Unsplash sends 403 for both — and only a header tells the two apart.
 * Telling a rate-limited user their key was rejected sends them off to replace a
 * key that was fine.
 */
class ToolHttpException(
    @StringRes val messageRes: Int,
    val status: Int = 0,
    val apiMessage: String? = null,
    val headers: Map<String, String> = emptyMap(),
) : IOException(apiMessage ?: "HTTP $status")

/** A response body together with the headers the caller asked to keep. */
class HttpResponse(val body: String, val headers: Map<String, String>)

/**
 * Tiny blocking HTTP helper shared by the network tool clients (translate,
 * KLIPY/GIPHY, Google search). Same HttpURLConnection approach as [WeatherClient];
 * always call on an IO dispatcher. On an HTTP error it tries to surface the
 * API's own error message (Google APIs return `{"error": {"message": …}}`)
 * so the panels can show something actionable instead of a bare failure.
 */
// Public (not internal) because the network clients in :feature:tools —
// AiClient, GiphyClient, KlipyClient — live one module up and share this
// plumbing. Still not a public API in spirit; nothing outside core.tools
// should call it.
object ToolHttp {

    /**
     * Browser-ish UA for every request: arbitrary image hosts surfaced by
     * Google image/GIF search often 403 the default Java agent, and the
     * APIs don't mind either way. Public because the media image loader
     * sends it too: Wikimedia's thumb and upload hosts answer 403 to
     * OkHttp's default `okhttp/x.y` agent.
     */
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0 Mobile Safari/537.36"

    fun get(
        url: String,
        timeoutMs: Int = 10_000,
        headers: Map<String, String> = emptyMap(),
        source: NetSource? = null,
        route: String? = null,
        background: Boolean? = null,
    ): String = getWithHeaders(url, timeoutMs, headers, source = source, route = route, background = background).body

    /**
     * [get] that also hands back the response headers named in [wantHeaders],
     * on the failure path as well as the success one — a rate-limit header is
     * most worth reading on exactly the response that failed, so the failure
     * carries them on [ToolHttpException.headers].
     *
     * Header lookup is case-insensitive (HttpURLConnection follows the spec
     * here), so [wantHeaders] may name them in any case.
     */
    fun getWithHeaders(
        url: String,
        timeoutMs: Int = 10_000,
        headers: Map<String, String> = emptyMap(),
        wantHeaders: Set<String> = emptySet(),
        source: NetSource? = null,
        route: String? = null,
        background: Boolean? = null,
    ): HttpResponse = metered(url, "GET", source, route, background) { connection, call ->
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.setRequestProperty("User-Agent", USER_AGENT)
        for ((name, value) in headers) connection.setRequestProperty(name, value)
        val status = connection.responseCode
        call.status = status
        val kept = wantHeaders.mapNotNull { name ->
            connection.getHeaderField(name)?.let { name to it }
        }.toMap()
        if (status !in 200..299) {
            throw httpFailure(status, connection.readError(call), kept)
        }
        HttpResponse(connection.readBody(call), kept)
    }

    /**
     * POSTs [form] URL-encoded. [wantHeaders] are kept on a failure, the way
     * [getWithHeaders] keeps them: a 429's `Retry-After` is what says how long
     * to wait.
     */
    fun postForm(
        url: String,
        form: Map<String, String>,
        timeoutMs: Int = 10_000,
        source: NetSource? = null,
        route: String? = null,
        headers: Map<String, String> = emptyMap(),
        wantHeaders: Set<String> = emptySet(),
    ): String = metered(url, "POST", source, route) { connection, call ->
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        for ((name, value) in headers) connection.setRequestProperty(name, value)
        val body = form.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}"
        }
        connection.send(body.toByteArray(), call)
        val status = connection.responseCode
        call.status = status
        if (status !in 200..299) {
            val kept = wantHeaders.mapNotNull { name -> connection.getHeaderField(name)?.let { name to it } }.toMap()
            throw httpFailure(status, connection.readError(call), kept)
        }
        connection.readBody(call)
    }

    fun postJson(
        url: String,
        body: String,
        timeoutMs: Int = 60_000,
        headers: Map<String, String> = emptyMap(),
        source: NetSource? = null,
        route: String? = null,
    ): String = metered(url, "POST", source, route) { connection, call ->
        connection.connectTimeout = 10_000
        connection.readTimeout = timeoutMs
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        for ((name, value) in headers) connection.setRequestProperty(name, value)
        connection.send(body.toByteArray(), call)
        val status = connection.responseCode
        call.status = status
        if (status !in 200..299) throw httpFailure(status, connection.readError(call))
        connection.readBody(call)
    }

    /** One file part of a [postMultipart] upload. */
    class FilePart(val field: String, val fileName: String, val mimeType: String, val bytes: ByteArray)

    /**
     * POSTs a `multipart/form-data` body: the text [fields] in order, then [file].
     * Built for the OpenAI-style audio upload, which is the only shape the
     * transcription servers agree on. Fails the same way [postJson] does.
     */
    fun postMultipart(
        url: String,
        fields: List<Pair<String, String>>,
        file: FilePart,
        timeoutMs: Int = 60_000,
        headers: Map<String, String> = emptyMap(),
        source: NetSource? = null,
        route: String? = null,
        savedBytes: Long = 0,
    ): String {
        val boundary = "----wmkb" + java.util.UUID.randomUUID().toString().replace("-", "")
        val body = multipartBody(boundary, fields, file)
        return metered(url, "POST", source, route) { connection, call ->
            connection.connectTimeout = 10_000
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            connection.send(body, call)
            val status = connection.responseCode
            call.status = status
            if (status !in 200..299) throw httpFailure(status, connection.readError(call))
            // Counted as saved only once the server took the smaller file.
            call.bytesSaved = savedBytes
            connection.readBody(call)
        }
    }

    /** What [postMultipartUnfollowed] got back. */
    class RawResponse(val status: Int, val location: String?, val body: String)

    /**
     * [postMultipart] that neither follows a redirect nor throws on a status
     * outside 200..299, and whose [file] may be left out to send the text
     * [fields] alone.
     *
     * For the reverse image search sites (#349): they answer an upload with a
     * redirect to the results page, and that address is the whole answer. The
     * page is for the user's browser to open, not for this client to fetch.
     */
    fun postMultipartUnfollowed(
        url: String,
        fields: List<Pair<String, String>>,
        file: FilePart?,
        timeoutMs: Int = 30_000,
        source: NetSource? = null,
        route: String? = null,
    ): RawResponse {
        val boundary = "----wmkb" + java.util.UUID.randomUUID().toString().replace("-", "")
        val body = multipartBody(boundary, fields, file)
        return metered(url, "POST", source, route) { connection, call ->
            connection.connectTimeout = 10_000
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.send(body, call)
            val status = connection.responseCode
            call.status = status
            val text = if (status >= 400) {
                connection.readError(call).orEmpty()
            } else {
                // A redirect often has no body at all, and some servers
                // answer the read with an error rather than an empty stream.
                runCatching { connection.readBody(call) }.getOrDefault("")
            }
            RawResponse(status, connection.getHeaderField("Location"), text)
        }
    }

    /** The bytes [postMultipart] sends; separate so a test can read them. */
    internal fun multipartBody(
        boundary: String,
        fields: List<Pair<String, String>>,
        file: FilePart?,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream((file?.bytes?.size ?: 0) + 1024)
        fun line(text: String) = out.write("$text\r\n".toByteArray(Charsets.UTF_8))
        for ((name, value) in fields) {
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"$name\"")
            line("")
            line(value)
        }
        if (file != null) {
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"${file.field}\"; filename=\"${file.fileName}\"")
            line("Content-Type: ${file.mimeType}")
            line("")
            out.write(file.bytes)
            line("")
        }
        line("--$boundary--")
        return out.toByteArray()
    }

    /**
     * POSTs [body], then hands each response line to [onLine] as it arrives —
     * for the streaming shapes the AI providers use (SSE, or the newline-
     * delimited JSON Ollama returns). [onRequestSent] fires once the request
     * body is on its way, which is the moment the request stops being "in
     * flight" and starts being "the model is working on it".
     *
     * [onLine] returns whether to keep reading: `false` closes the connection
     * where it stands, so an abandoned request stops holding a socket (and, for
     * a metered provider, stops being billed for tokens nobody will see).
     * Throwing from it also aborts, which is how a mid-stream error event is
     * surfaced.
     */
    fun postJsonStream(
        url: String,
        body: String,
        timeoutMs: Int = 120_000,
        headers: Map<String, String> = emptyMap(),
        onRequestSent: () -> Unit = {},
        source: NetSource? = null,
        route: String? = null,
        onLine: (String) -> Boolean,
    ): Unit = metered(url, "POST", source, route) { connection, call ->
        connection.connectTimeout = 10_000
        connection.readTimeout = timeoutMs
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        for ((name, value) in headers) connection.setRequestProperty(name, value)
        // Opens the socket as well as the stream.
        connection.send(body.toByteArray(), call)
        // The client has nothing left to do, so from here on the clock is
        // the server's. This has to be reported *before* asking for the
        // status: reading it blocks until the response headers arrive, and
        // a streaming AI endpoint holds those until its model produces the
        // first chunk. Reporting after it would mean the caller shows
        // "connecting" for the whole wait and "waiting" for no time at all.
        onRequestSent()
        val status = connection.responseCode
        call.status = status
        if (status !in 200..299) throw httpFailure(status, connection.readError(call))
        call.countIn(connection.inputStream).bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (!onLine(line)) break
                }
            }
    }

    /** What [bytes] got back: the body as it came, its media type, and the headers asked for. */
    class BytesResponse(
        /** The media type alone, lower case, without its parameters. Empty when the server sent none. */
        val contentType: String,
        val body: ByteArray,
        val headers: Map<String, String>,
    )

    /**
     * One request whose answer is kept as bytes rather than text: a picture
     * an image service sends back as the body, or fetches from where it put
     * it. [body] is sent with [contentType] when given. Fails the way
     * [postJson] does; a body past [maxBytes] fails as too large.
     */
    @Suppress("LongParameterList")
    fun bytes(
        url: String,
        method: String = "GET",
        body: ByteArray? = null,
        contentType: String? = null,
        headers: Map<String, String> = emptyMap(),
        wantHeaders: Set<String> = emptySet(),
        timeoutMs: Int = 120_000,
        maxBytes: Long = 40L * 1024 * 1024,
        source: NetSource? = null,
        route: String? = null,
    ): BytesResponse = metered(url, method, source, route) { connection, call ->
        connection.connectTimeout = 15_000
        connection.readTimeout = timeoutMs
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.requestMethod = method
        for ((name, value) in headers) connection.setRequestProperty(name, value)
        if (body != null) {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            if (contentType != null) connection.setRequestProperty("Content-Type", contentType)
            connection.send(body, call)
        }
        val status = connection.responseCode
        call.status = status
        val kept = wantHeaders.mapNotNull { name -> connection.getHeaderField(name)?.let { name to it } }.toMap()
        if (status !in 200..299) throw httpFailure(status, connection.readError(call), kept)
        val type = connection.contentType.orEmpty().substringBefore(';').trim().lowercase()
        val out = java.io.ByteArrayOutputStream()
        call.countIn(connection.inputStream).use { input ->
            val buffer = ByteArray(16_384)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw ToolHttpException(R.string.core_tools_error_file_too_large)
                out.write(buffer, 0, read)
            }
        }
        BytesResponse(type, out.toByteArray(), kept)
    }

    /** [bytes] with a `multipart/form-data` body of [fields] and an optional [file]. */
    @Suppress("LongParameterList")
    fun multipartBytes(
        url: String,
        fields: List<Pair<String, String>>,
        file: FilePart? = null,
        headers: Map<String, String> = emptyMap(),
        wantHeaders: Set<String> = emptySet(),
        timeoutMs: Int = 120_000,
        source: NetSource? = null,
        route: String? = null,
    ): BytesResponse {
        val boundary = "----wmkb" + java.util.UUID.randomUUID().toString().replace("-", "")
        return bytes(
            url, "POST", multipartBody(boundary, fields, file), "multipart/form-data; boundary=$boundary",
            headers, wantHeaders, timeoutMs, source = source, route = route,
        )
    }

    /** What [exchange] hands its reader: the response before its body is read. */
    class Exchange(
        val status: Int,
        /** The media type alone, lower case, without its parameters. Empty when the server sent none. */
        val contentType: String,
        /** The response headers the caller named, by the name it gave them. */
        val headers: Map<String, String>,
        /** The body, or the error body for a failure. Null when there is none (a 202). */
        val body: java.io.BufferedReader?,
    )

    /**
     * One request whose response the caller reads itself, a line at a time
     * if it wants: for a protocol where the status, a header and the body's
     * type all decide what happens next, and where the body may be a stream
     * that has to be read only as far as one answer. Built for MCP (#573).
     *
     * Never throws on a status: [read] sees every response, failures
     * included, with the error body in [Exchange.body]. The connection closes
     * when [read] returns, which is how a stream is abandoned once the answer
     * it carried has arrived.
     */
    @Suppress("LongParameterList")
    fun <T> exchange(
        url: String,
        method: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        wantHeaders: Set<String> = emptySet(),
        timeoutMs: Int = 60_000,
        source: NetSource? = null,
        route: String? = null,
        read: (Exchange) -> T,
    ): T = metered(url, method, source, route) { connection, call ->
        connection.connectTimeout = 10_000
        connection.readTimeout = timeoutMs
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.requestMethod = method
        for ((name, value) in headers) connection.setRequestProperty(name, value)
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.send(body.toByteArray(), call)
        }
        val status = connection.responseCode
        call.status = status
        val kept = wantHeaders.mapNotNull { name -> connection.getHeaderField(name)?.let { name to it } }.toMap()
        val type = connection.contentType.orEmpty().substringBefore(';').trim().lowercase()
        val stream = if (status >= 400) connection.errorStream else runCatching { connection.inputStream }.getOrNull()
        val reader = stream?.let(call::countIn)?.bufferedReader()
        try {
            read(Exchange(status, type, kept, reader))
        } finally {
            runCatching { reader?.close() }
        }
    }

    /** What [binaryExchange] hands its reader: the response with its body still unread, as bytes. */
    class BinaryExchange(
        val status: Int,
        /** The media type alone, lower case, without its parameters. Empty when the server sent none. */
        val contentType: String,
        /** The response headers the caller named, by the name it gave them. */
        val headers: Map<String, String>,
        /** The body, or the error body for a failure. Null when there is none. */
        val body: java.io.InputStream?,
    )

    /**
     * [exchange] for a body that is not text: a request of raw [body] bytes,
     * and a response the caller reads as a byte stream, a frame at a time if
     * it likes. Built for Privatemode's encrypted bodies (#1145), whose
     * streamed answer must be opened as it arrives. Never throws on a status;
     * [onRequestSent] is called once the body is out, as [postJsonStream] does.
     */
    @Suppress("LongParameterList")
    fun <T> binaryExchange(
        url: String,
        body: ByteArray,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
        wantHeaders: Set<String> = emptySet(),
        timeoutMs: Int = 120_000,
        onRequestSent: () -> Unit = {},
        source: NetSource? = null,
        route: String? = null,
        read: (BinaryExchange) -> T,
    ): T = metered(url, "POST", source, route) { connection, call ->
        connection.connectTimeout = 10_000
        connection.readTimeout = timeoutMs
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(body.size)
        connection.setRequestProperty("Content-Type", contentType)
        for ((name, value) in headers) connection.setRequestProperty(name, value)
        connection.send(body, call)
        onRequestSent()
        val status = connection.responseCode
        call.status = status
        val kept = wantHeaders.mapNotNull { name -> connection.getHeaderField(name)?.let { name to it } }.toMap()
        val type = connection.contentType.orEmpty().substringBefore(';').trim().lowercase()
        val stream = if (status >= 400) connection.errorStream else runCatching { connection.inputStream }.getOrNull()
        val input = stream?.let(call::countIn)
        try {
            read(BinaryExchange(status, type, kept, input))
        } finally {
            runCatching { input?.close() }
        }
    }

    /**
     * Streams a URL into [target] (creating parent dirs); deletes the partial file on
     * failure. [maxBytes] aborts the transfer once exceeded rather than trusting a
     * (possibly absent or false) Content-Length header.
     *
     * [onProgress] is called on this thread as bytes arrive, with the bytes written
     * so far and the expected total — which is -1 whenever the server didn't say
     * (no Content-Length, or a compressed transfer, where the header describes the
     * wire bytes and not the file). Callers show an indeterminate spinner for -1
     * rather than inventing a total.
     */
    fun download(
        url: String,
        target: File,
        timeoutMs: Int = 20_000,
        maxBytes: Long = Long.MAX_VALUE,
        onProgress: ((Long, Long) -> Unit)? = null,
        headers: Map<String, String> = emptyMap(),
        source: NetSource? = null,
        route: String? = null,
        background: Boolean? = null,
    ) {
        target.parentFile?.mkdirs()
        try {
            metered(url, "GET", source, route, background) { connection, call ->
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.setRequestProperty("User-Agent", USER_AGENT)
                for ((name, value) in headers) connection.setRequestProperty(name, value)
                connection.instanceFollowRedirects = true
                val status = connection.responseCode
                call.status = status
                if (status !in 200..299) throw httpFailure(status, null)
                // Only trustworthy for an identity transfer: with gzip or chunked
                // encoding the header counts wire bytes, so a percentage from it
                // would run past 100 or stall short of it.
                val expected = if (connection.contentEncoding == null) {
                    connection.contentLengthLong.takeIf { it > 0L } ?: -1L
                } else {
                    -1L
                }
                onProgress?.invoke(0L, expected)
                call.countIn(connection.inputStream).use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > maxBytes) {
                                throw ToolHttpException(R.string.core_tools_error_file_too_large)
                            }
                            output.write(buffer, 0, read)
                            onProgress?.invoke(total, expected)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    /**
     * Opens [url] as one logged request: the connection is closed and the
     * network activity log row written however [block] ends. [source] falls
     * back to whatever service the host belongs to, and [background] to that
     * source's own default.
     */
    private inline fun <T> metered(
        url: String,
        method: String,
        source: NetSource?,
        route: String?,
        background: Boolean? = null,
        block: (HttpURLConnection, NetCall) -> T,
    ): T {
        val resolved = source ?: NetLog.inferSource(url)
        return NetLog.call(resolved, method, url, route, background ?: resolved.background).track { call ->
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                block(connection, call)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun HttpURLConnection.send(bytes: ByteArray, call: NetCall) {
        outputStream.use { it.write(bytes) }
        call.sent(bytes.size.toLong())
    }

    private fun HttpURLConnection.readBody(call: NetCall): String =
        call.countIn(inputStream).bufferedReader().use { it.readText() }

    private fun HttpURLConnection.readError(call: NetCall): String? =
        errorStream?.let(call::countIn)?.bufferedReader()?.use { it.readText() }

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /**
     * A short, human-facing line for any failure a tool network call can throw.
     * Connectivity problems (no network, DNS, timeout, refused) get a plain
     * "you're offline"-style message instead of the raw
     * `Unable to resolve host "api.giphy.com"…` the OS produces. An HTTP error
     * shows the provider's own words when it sent any, and our wording for the
     * status when it did not.
     */
    fun friendlyMessage(context: Context, t: Throwable): String =
        friendlyMessage(t) { res, arg -> if (arg == null) context.getString(res) else context.getString(res, arg) }

    /**
     * [friendlyMessage] with the strings looked up by [text], which is given a
     * resource and the one argument it formats, or null when it takes none.
     * Public so a caller's JVM tests, which have no Context, can hold it to
     * the same wording (#828).
     */
    fun friendlyMessage(t: Throwable, text: (res: Int, arg: Any?) -> String): String = when (t) {
        is ToolHttpException -> httpText(t, text)
        is NoInternetPermissionException -> text(OfflineBuild.refused(), null)
        is UnknownHostException -> text(CommonR.string.common_error_network, null)
        is SocketTimeoutException -> text(CommonR.string.common_error_timeout, null)
        is ConnectException -> text(R.string.core_tools_error_server_unreachable, null)
        else -> t.message?.takeIf { it.isNotBlank() }
            ?: text(CommonR.string.common_error_generic, null)
    }

    /**
     * Whether [t] says the server could not be reached at all: no connection,
     * no such host, nothing listening, no answer in time, or a gateway in front
     * of it reporting the same (#452). A server that answered with a refusal
     * was reached, so that is not this.
     */
    fun isUnreachable(t: Throwable): Boolean = when (t) {
        is ToolHttpException -> t.status in HttpURLConnection.HTTP_BAD_GATEWAY..HttpURLConnection.HTTP_GATEWAY_TIMEOUT
        is NoInternetPermissionException -> false
        is UnknownHostException, is SocketTimeoutException, is java.net.SocketException -> true
        else -> false
    }

    /** The provider's own words, or our wording for the status it answered with. */
    private fun httpText(t: ToolHttpException, text: (res: Int, arg: Any?) -> String): String {
        t.apiMessage?.takeIf { it.isNotBlank() }?.let { return it }
        // A failure with no status of its own (the size cap) takes no argument.
        if (t.status <= 0) return text(t.messageRes, null)
        return text(t.messageRes, t.status)
    }

    /** The failure to throw for an HTTP status outside 200..299. */
    fun httpFailure(
        status: Int,
        body: String?,
        headers: Map<String, String> = emptyMap(),
    ): ToolHttpException =
        ToolHttpException(statusMessageRes(status), status, apiErrorText(body), headers)

    /**
     * The provider's own error text, when the response body carried any.
     * Google APIs return `{"error": {"message": …}}` and Brave
     * `{"error": {"detail": …}}`; Tavily returns `{"detail": {"error": …}}`,
     * or `{"error": …}` when rate-limited. The image services add two more:
     * `{"message": …}` (AI Horde, Stability's account calls) and
     * `{"errors": […]}` (Stability, Cloudflare), a list of strings or of
     * objects with a message. A bare `{"detail": …}` string is left out: it
     * is usually a framework's "Not Found", which says less than our own
     * wording for the status. Anything else reads as no message at all.
     */
    fun apiErrorText(body: String?): String? = body?.let {
        runCatching {
            val root = Json.parseToJsonElement(it).jsonObject
            val error = root["error"]
            fun text(e: kotlinx.serialization.json.JsonElement?): String? =
                (e as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
            (error as? JsonObject)?.let { e -> e["message"] ?: e["detail"] }
                ?.let { m -> (m as? JsonPrimitive)?.content }
                ?: text(error)
                ?: text((root["detail"] as? JsonObject)?.get("error"))
                ?: text(root["message"])
                ?: (root["errors"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.let { first ->
                    text(first) ?: text((first as? JsonObject)?.get("message"))
                }
        }.getOrNull()
    }?.takeIf { it.isNotBlank() }

    /** Our own wording for an HTTP status, for when the API sent none. */
    @StringRes
    fun statusMessageRes(status: Int): Int = when (status) {
        400 -> R.string.core_tools_error_http_bad_request
        401, 403 -> R.string.core_tools_error_http_key_rejected
        429 -> R.string.core_tools_error_http_rate_limit
        else -> R.string.core_tools_error_http_failed
    }
}
