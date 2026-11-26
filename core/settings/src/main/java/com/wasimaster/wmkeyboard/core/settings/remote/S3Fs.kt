package com.wasimaster.wmkeyboard.core.settings.remote

import android.content.Context
import com.wasimaster.wmkeyboard.core.files.FilePlaces
import com.wasimaster.wmkeyboard.core.files.RemoteEntry
import com.wasimaster.wmkeyboard.core.files.RemoteFs
import com.wasimaster.wmkeyboard.core.net.InternetGate
import com.wasimaster.wmkeyboard.core.net.NetLogInterceptor
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.settings.BackupLocation
import com.wasimaster.wmkeyboard.core.settings.sink.AwsSigV4
import com.wasimaster.wmkeyboard.core.settings.sink.S3Listing
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink

/**
 * A bucket on anything that speaks the S3 API, from the backup location's
 * endpoint, region and key pair, signed by the backup's own [AwsSigV4].
 *
 * The top is the bucket's, not the backup's prefix: the whole bucket shows.
 * S3 has no folders, only keys, so a folder here is a key prefix ending in
 * `/`, listed with `/` as the delimiter; making one writes the empty marker
 * object `name/` that the AWS console makes, and removing one removes it.
 *
 * Nothing moves server-side in S3 either: a rename is a copy, checked by
 * size, and only then the original's delete. A folder's rename copies every
 * key under it first and deletes the originals only once all the copies are
 * in. Uploads are a single `PUT` up to [MULTIPART_ABOVE], a multipart upload
 * past it, streamed from the file in either case.
 *
 * A `PUT` replaces whatever has the key, and only `If-None-Match: *` makes
 * the service refuse instead; not every service honours it (Backblaze B2
 * errors on it, Garage ignores it and writes over). So each service is asked
 * once, on an object of our own ([honoursIfNoneMatch]). One that honours it
 * gets uploads straight to their names, the header on every write; any other
 * gets the store's temporary name and a rename, whose copy comes right after
 * a look at the target. Every write looks right before it, header or not.
 */
class S3Fs(
    @Suppress("UNUSED_PARAMETER") context: Context,
    location: BackupLocation,
) : RemoteFs {

    private val config = location.s3

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(InternetGate)
            .addNetworkInterceptor(NetLogInterceptor(NetSource.BACKUP, NetLogInterceptor.PATH) { false })
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
            // A redirect is a wrong region or endpoint, and the signature would not follow it anyway.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /** This session's answer when the probe could not be made (no rights at the top, say): not asked again. */
    private var unprobed = false

    /** Whether this service refused `If-None-Match` on CopyObject; MinIO ignores it there, others may refuse it. */
    private var copyRefusesCondition = false

    /**
     * The key of an upload that failed. A failed S3 upload leaves nothing,
     * so the delete [com.wasimaster.wmkeyboard.core.files.RemoteFiles] sends
     * after one could only reach an object that was there before it. Skipped
     * once.
     */
    private var failedUpload: String? = null

    /** Only where the service itself refuses to write over a key: elsewhere a look and a write are two steps. */
    override val atomicUpload: Boolean get() = runCatching { honoursIfNoneMatch() }.getOrDefault(false)

    // ---- Requests ------------------------------------------------------------------

    private val host: String
        get() {
            val endpoint = config.endpoint.trim().ifEmpty { "https://s3.${config.region}.amazonaws.com" }
            val bare = endpoint.substringAfter("://").trimEnd('/')
            return if (config.pathStyle) bare else "${config.bucket}.$bare"
        }

    private val scheme: String
        get() = if (config.endpoint.trim().startsWith("http://", ignoreCase = true)) "http" else "https"

    /** The signed path of [key], or the bucket's own when null. */
    private fun pathFor(key: String?): String = when {
        key == null -> if (config.pathStyle) "/${config.bucket}" else "/"
        config.pathStyle -> "/${config.bucket}/${AwsSigV4.encode(key, encodeSlash = false)}"
        else -> "/${AwsSigV4.encode(key, encodeSlash = false)}"
    }

    /**
     * A request signed as [S3Sink][com.wasimaster.wmkeyboard.core.settings.sink.S3Sink]
     * signs its own. [signedHeaders] are `x-amz-*` ones, which S3 wants
     * signed; [plainHeaders] go along unsigned, so a proxy that rewrites one
     * cannot break the signature.
     */
    private fun request(
        method: String,
        key: String?,
        query: Map<String, String> = emptyMap(),
        bodySha: String = AwsSigV4.EMPTY_BODY_SHA256,
        body: RequestBody? = null,
        signedHeaders: Map<String, String> = emptyMap(),
        plainHeaders: Map<String, String> = emptyMap(),
    ): Request {
        if (config.bucket.isEmpty() || config.accessKeyId.isEmpty() || config.secretAccessKey.isEmpty()) {
            throw IOException("S3 is not set up")
        }
        val path = pathFor(key)
        val auth = AwsSigV4.sign(
            AwsSigV4.Request(
                method = method,
                path = path,
                query = query,
                headers = mapOf("host" to host) + signedHeaders,
                bodySha256 = bodySha,
            ),
            accessKeyId = config.accessKeyId,
            secretAccessKey = config.secretAccessKey,
            region = config.region,
            timestampMs = System.currentTimeMillis(),
        )
        val url = buildString {
            append(scheme).append("://").append(host).append(path)
            if (query.isNotEmpty()) {
                append('?')
                append(query.toSortedMap().entries.joinToString("&") { (k, v) -> "${AwsSigV4.encode(k)}=${AwsSigV4.encode(v)}" })
            }
        }
        return Request.Builder()
            .url(url)
            .method(method, body ?: if (method == "PUT" || method == "POST") ByteArray(0).toRequestBody(null) else null)
            .apply {
                for ((name, value) in auth) header(name, value)
                for ((name, value) in signedHeaders) header(name, value)
                for ((name, value) in plainHeaders) header(name, value)
            }
            .build()
    }

    /** A failed answer, with S3's error code when the body had one. */
    private class S3Failure(val status: Int, val code: String) : IOException("s3: $status $code")

    private fun send(request: Request): Response = client.newCall(request).execute()

    /** [request]'s answer to [read] when it succeeded; an [S3Failure] otherwise. */
    private fun <T> call(request: Request, read: (Response) -> T): T = send(request).use { response ->
        if (!response.isSuccessful) throw failureOf(response)
        read(response)
    }

    private fun failureOf(response: Response): S3Failure {
        val text = runCatching { response.peekBody(ERROR_BODY_BYTES).string() }.getOrDefault("")
        return S3Failure(response.code, xmlText(text, "Code").orEmpty())
    }

    // ---- Keys ----------------------------------------------------------------------

    private fun keyOf(path: List<String>): String {
        path.forEach(::checkName)
        return path.joinToString("/")
    }

    /** The prefix that lists the folder [dir]: empty at the top, `a/b/` below it. */
    private fun prefixOf(dir: List<String>): String = if (dir.isEmpty()) "" else keyOf(dir) + "/"

    private class Head(val size: Long, val modifiedMs: Long, val contentType: String?)

    /** The object at [key], or null when there is none. Throws when S3 will not say. */
    private fun head(key: String): Head? = send(request("HEAD", key)).use { response ->
        when {
            response.code == HTTP_NOT_FOUND -> null
            response.isSuccessful -> Head(
                size = response.header("Content-Length")?.toLongOrNull() ?: -1L,
                modifiedMs = response.headers.getDate("Last-Modified")?.time ?: 0L,
                contentType = response.header("Content-Type"),
            )
            // 403 here is as likely a missing key without list rights as a refusal: unknown.
            else -> throw failureOf(response)
        }
    }

    /**
     * One page of the listing under [prefix]. [delimiter] folds deeper keys
     * into folders. Keys come URL-encoded, so a name with spaces at its ends
     * or characters XML cannot carry arrives as it is.
     */
    private fun page(prefix: String, delimiter: Boolean, token: String?, max: Int): S3Listing.Page {
        val query = buildMap {
            put("list-type", "2")
            put("max-keys", max.toString())
            put("encoding-type", "url")
            if (prefix.isNotEmpty()) put("prefix", prefix)
            if (delimiter) put("delimiter", "/")
            token?.let { put("continuation-token", it) }
        }
        val xml = call(request("GET", null, query)) { it.body?.string().orEmpty() }
        // S3Listing answers "nothing" for anything it cannot read; here that
        // would make taken names look free, so only a real listing passes.
        if (!xml.contains("<ListBucketResult")) throw IOException("s3: not a listing")
        val page = S3Listing.parse(xml)
        if (!ENCODED.containsMatchIn(xml)) return page
        return S3Listing.Page(
            keys = page.keys.map { it.copy(key = decode(it.key)) },
            continuationToken = page.continuationToken,
            prefixes = page.prefixes.map(::decode),
        )
    }

    /** Whether any key at all starts with [prefix]. */
    private fun anyUnder(prefix: String): Boolean {
        val one = page(prefix, delimiter = false, token = null, max = 1)
        return one.keys.isNotEmpty() || one.prefixes.isNotEmpty()
    }

    /** Every key under [prefix], at any depth. */
    private fun allUnder(prefix: String): List<S3Listing.Key> {
        val out = ArrayList<S3Listing.Key>()
        var token: String? = null
        do {
            val page = page(prefix, delimiter = false, token = token, max = PAGE)
            out += page.keys
            token = page.continuationToken
        } while (!token.isNullOrEmpty())
        return out
    }

    // ---- Reading -------------------------------------------------------------------

    override fun list(dir: List<String>): List<RemoteEntry> {
        failedUpload = null
        val prefix = prefixOf(dir)
        val out = ArrayList<RemoteEntry>()
        var token: String? = null
        do {
            val page = page(prefix, delimiter = true, token = token, max = PAGE)
            for (item in page.keys) {
                val name = item.key.removePrefix(prefix)
                // The folder's own marker, and keys with no name of their own.
                if (!item.key.startsWith(prefix) || !listable(name)) continue
                out += RemoteEntry(name, isDirectory = false, size = item.sizeBytes, modifiedMs = item.modifiedAtMs)
            }
            for (folder in page.prefixes) {
                val name = folder.removePrefix(prefix).removeSuffix("/")
                if (!folder.startsWith(prefix) || !listable(name)) continue
                out += RemoteEntry(name, isDirectory = true)
            }
            token = page.continuationToken
        } while (!token.isNullOrEmpty())
        return out
    }

    override fun stat(path: List<String>): RemoteEntry? {
        failedUpload = null
        if (path.isEmpty()) {
            // One key, which proves the bucket and the signature without a listing's worth of bytes.
            page("", delimiter = true, token = null, max = 1)
            return RemoteEntry("", isDirectory = true)
        }
        val key = keyOf(path)
        head(key)?.let { return RemoteEntry(path.last(), isDirectory = false, size = it.size, modifiedMs = it.modifiedMs) }
        return if (anyUnder("$key/")) RemoteEntry(path.last(), isDirectory = true) else null
    }

    override fun download(path: List<String>, to: OutputStream) {
        failedUpload = null
        if (path.isEmpty()) throw IOException("the top is a folder")
        call(request("GET", keyOf(path))) { response ->
            val body = response.body ?: throw IOException("s3: no body")
            // OkHttp throws when a body ends before its Content-Length, so a cut-off download fails.
            body.byteStream().use { it.copyTo(to, BUFFER) }
        }
    }

    // ---- Writing -------------------------------------------------------------------

    override fun upload(dir: List<String>, name: String, from: File, onSent: (Long) -> Unit) {
        failedUpload = null
        checkName(name)
        val key = keyOf(dir + name)
        try {
            val progress = Progress(onSent)
            val type = FilePlaces.mimeOf(name)?.toMediaTypeOrNull()
            if (from.length() > MULTIPART_ABOVE) {
                // Asked before the parts are spent, and again before they become the object.
                if (head(key) != null) throw IOException("$name is already there")
                multipart(key, from, type, progress)
            } else {
                single(key, from, type, progress)
            }
        } catch (failure: Throwable) {
            failedUpload = key
            throw failure
        }
    }

    private fun single(key: String, from: File, type: MediaType?, progress: Progress) {
        val size = from.length()
        val sha = sha256(from, 0, size)
        conditional(key) { condition ->
            call(
                request(
                    "PUT", key,
                    bodySha = sha,
                    body = FileRange(from, 0, size, type, progress),
                    plainHeaders = condition,
                ),
            ) { }
        }
    }

    /**
     * A multipart upload: each part hashed, then streamed, from its own
     * stretch of the file. Anything that fails aborts it, so the parts do not
     * sit in the bucket being billed for.
     */
    private fun multipart(key: String, from: File, type: MediaType?, progress: Progress) {
        val size = from.length()
        val uploadId = initiate(key, type?.toString())
        try {
            val partSize = partSizeFor(size, MIN_PART)
            val etags = ArrayList<String>()
            var offset = 0L
            while (offset < size) {
                val length = minOf(partSize, size - offset)
                val number = etags.size + 1
                val etag = call(
                    request(
                        "PUT", key,
                        query = mapOf("partNumber" to number.toString(), "uploadId" to uploadId),
                        bodySha = sha256(from, offset, length),
                        body = FileRange(from, offset, length, null, progress),
                    ),
                ) { it.header("ETag") } ?: throw IOException("s3: a part came back with no ETag")
                etags += etag
                offset += length
            }
            complete(key, uploadId, etags)
        } catch (failure: Throwable) {
            abort(key, uploadId)
            throw failure
        }
    }

    private fun initiate(key: String, contentType: String?): String {
        val xml = call(
            request(
                "POST", key,
                query = mapOf("uploads" to ""),
                body = ByteArray(0).toRequestBody(contentType?.toMediaTypeOrNull()),
            ),
        ) { it.body?.string().orEmpty() }
        return xmlText(xml, "UploadId")?.takeIf { it.isNotEmpty() } ?: throw IOException("s3: no upload id")
    }

    private fun complete(key: String, uploadId: String, etags: List<String>) {
        val body = buildString {
            append("<CompleteMultipartUpload>")
            etags.forEachIndexed { i, etag ->
                append("<Part><PartNumber>").append(i + 1).append("</PartNumber><ETag>")
                append(escape(etag)).append("</ETag></Part>")
            }
            append("</CompleteMultipartUpload>")
        }.toByteArray(Charsets.UTF_8)
        conditional(key) { condition ->
            val xml = call(
                request(
                    "POST", key,
                    query = mapOf("uploadId" to uploadId),
                    bodySha = AwsSigV4.hex(AwsSigV4.sha256(body)),
                    body = body.toRequestBody("application/xml".toMediaTypeOrNull()),
                    plainHeaders = condition,
                ),
            ) { it.body?.string().orEmpty() }
            // S3 can answer 200 and still fail, with the error in the body.
            if (!xml.contains("CompleteMultipartUploadResult")) {
                throw IOException("s3: the upload did not complete (${xmlText(xml, "Code").orEmpty()})")
            }
        }
    }

    private fun abort(key: String, uploadId: String) {
        runCatching { call(request("DELETE", key, query = mapOf("uploadId" to uploadId))) { } }
    }

    /**
     * Runs a write to [key] right after a look that nothing has it, and with
     * `If-None-Match: *` where the service honours that, so a key that
     * appeared meanwhile is refused rather than replaced. A service that
     * answers 501 to the header on this call (it may take it on a PUT and
     * not on a multipart's completion) is looked at again and written
     * without it.
     */
    private fun conditional(key: String, write: (Map<String, String>) -> Unit) {
        refuseTaken(key)
        if (honoursIfNoneMatch()) {
            try {
                write(IF_NONE_MATCH)
                return
            } catch (failure: S3Failure) {
                if (failure.status == HTTP_PRECONDITION_FAILED) throw IOException("the name was taken meanwhile", failure)
                if (failure.status != HTTP_NOT_IMPLEMENTED && failure.code != "NotImplemented") throw failure
            }
            refuseTaken(key)
        }
        write(emptyMap())
    }

    private fun refuseTaken(key: String) {
        if (head(key) != null) throw IOException("${key.trimEnd('/').substringAfterLast('/')} is already there")
    }

    /**
     * Whether a `PUT` with `If-None-Match: *` over a key that exists is
     * refused (412) here, learned once per service and kept for the process.
     * Asked of a hidden object of our own at the top, written twice and
     * removed, so a service that ignores the header writes over nothing but
     * that. Unknown counts as no.
     */
    private fun honoursIfNoneMatch(): Boolean {
        val service = "$scheme://$host${pathFor(null)}"
        HONOURS[service]?.let { return it }
        if (unprobed) return false
        val answer = probe()
        if (answer == null) {
            unprobed = true
            return false
        }
        HONOURS[service] = answer
        return answer
    }

    /** True on a 412, false when the second write went through or the header was refused; null when it could not be asked. */
    private fun probe(): Boolean? {
        val key = PROBE_NAME + java.util.UUID.randomUUID().toString().take(PROBE_TOKEN)
        if (runCatching { call(request("PUT", key)) { } }.isFailure) return null
        try {
            val code = runCatching { send(request("PUT", key, plainHeaders = IF_NONE_MATCH)).use { it.code } }.getOrNull() ?: return null
            return when {
                code == HTTP_PRECONDITION_FAILED -> true
                // A 2xx wrote over it; a 400 or 501 is a service that will not take the header.
                code in 200..299 || code == HTTP_BAD_REQUEST || code == HTTP_NOT_IMPLEMENTED -> false
                // Anything else (a 403, a 503) says nothing about the header.
                else -> null
            }
        } finally {
            runCatching { call(request("DELETE", key)) { } }
        }
    }

    override fun mkdir(dir: List<String>, name: String) {
        failedUpload = null
        checkName(name)
        val marker = keyOf(dir + name) + "/"
        conditional(marker) { condition -> call(request("PUT", marker, plainHeaders = condition)) { } }
    }

    override fun delete(path: List<String>, isDirectory: Boolean) {
        if (path.isEmpty()) throw IOException("the top cannot be removed")
        val key = keyOf(path) + if (isDirectory) "/" else ""
        if (!isDirectory && failedUpload == key) {
            failedUpload = null
            return
        }
        failedUpload = null
        // S3 answers 204 for a key that was never there; some services 404.
        send(request("DELETE", key)).use { response ->
            if (!response.isSuccessful && response.code != HTTP_NOT_FOUND) throw failureOf(response)
        }
    }

    override fun rename(path: List<String>, newName: String) {
        failedUpload = null
        if (path.isEmpty()) throw IOException("the top cannot be renamed")
        checkName(newName)
        relocate(path, path.dropLast(1) + newName)
    }

    override fun move(path: List<String>, toDir: List<String>): Boolean {
        failedUpload = null
        if (path.isEmpty()) return false
        relocate(path, toDir + path.last())
        return true
    }

    /** Moves the file or folder at [from] to [to], copying before any delete. */
    private fun relocate(from: List<String>, to: List<String>) {
        val fromKey = keyOf(from)
        val toKey = keyOf(to)
        val file = head(fromKey)
        if (file != null) {
            if (head(toKey) != null || anyUnder("$toKey/")) throw IOException("${to.last()} is already there")
            copyChecked(fromKey, toKey, file)
            call(request("DELETE", fromKey)) { }
            return
        }
        moveTree("$fromKey/", "$toKey/")
    }

    /**
     * Every key under [fromPrefix] copied under [toPrefix], each checked, and
     * the originals deleted only once all of them are. When a copy fails the
     * copies made so far are taken back (the prefix was empty before, so they
     * are all ours) and the originals stay as they were.
     */
    private fun moveTree(fromPrefix: String, toPrefix: String) {
        if (anyUnder(toPrefix) || head(toPrefix.removeSuffix("/")) != null) {
            throw IOException("${toPrefix.removeSuffix("/").substringAfterLast('/')} is already there")
        }
        val keys = allUnder(fromPrefix).filter { it.key.startsWith(fromPrefix) }
        if (keys.isEmpty()) throw IOException("nothing is there to move")
        val copied = ArrayList<String>()
        try {
            for (item in keys) {
                val target = toPrefix + item.key.removePrefix(fromPrefix)
                copyChecked(item.key, target, Head(item.sizeBytes, item.modifiedAtMs, null))
                copied += target
            }
        } catch (failure: Throwable) {
            for (target in copied) runCatching { call(request("DELETE", target)) { } }
            throw failure
        }
        for (item in keys) call(request("DELETE", item.key)) { }
    }

    /**
     * [fromKey] copied to [toKey] on the server, then the copy's size checked
     * against [source]'s. A single CopyObject up to 5 GB, which is S3's limit
     * for one; a multipart copy past it.
     */
    private fun copyChecked(fromKey: String, toKey: String, source: Head) {
        val copySource = "${config.bucket}/${AwsSigV4.encode(fromKey, encodeSlash = false)}"
        if (source.size <= MAX_SINGLE_COPY) {
            copyOnce(copySource, toKey)
        } else {
            copyMultipart(copySource, toKey, source.size, source.contentType ?: head(fromKey)?.contentType)
        }
        val landed = head(toKey) ?: throw IOException("s3: the copy is not there")
        if (source.size >= 0 && landed.size != source.size) {
            throw IOException("s3: the copy is ${landed.size} bytes, not ${source.size}")
        }
    }

    /**
     * One CopyObject onto [toKey], which a copy replaces like a PUT: looked
     * at right before, and sent with `If-None-Match: *` where the service
     * honours that on a PUT (AWS takes it on a copy too, MinIO ignores it
     * there, so the look is what guards). A 400 or 501 to the header has it
     * looked at again and sent without, for the rest of the session.
     */
    private fun copyOnce(copySource: String, toKey: String) {
        fun copy(condition: Map<String, String>) {
            val xml = call(request("PUT", toKey, signedHeaders = mapOf("x-amz-copy-source" to copySource), plainHeaders = condition)) {
                it.body?.string().orEmpty()
            }
            // A 200 can still carry an error, sent once the copy failed part way.
            if (!xml.contains("CopyObjectResult")) throw IOException("s3: the copy failed (${xmlText(xml, "Code").orEmpty()})")
        }
        refuseTaken(toKey)
        val condition = if (!copyRefusesCondition && honoursIfNoneMatch()) IF_NONE_MATCH else emptyMap()
        try {
            copy(condition)
        } catch (failure: S3Failure) {
            if (failure.status == HTTP_PRECONDITION_FAILED) throw IOException("the name was taken meanwhile", failure)
            if (condition.isEmpty() || (failure.status != HTTP_BAD_REQUEST && failure.status != HTTP_NOT_IMPLEMENTED)) throw failure
            copyRefusesCondition = true
            refuseTaken(toKey)
            copy(emptyMap())
        }
    }

    /** A copy past 5 GB, in UploadPartCopy ranges; aborted on any failure, as [multipart] is. */
    private fun copyMultipart(copySource: String, toKey: String, size: Long, contentType: String?) {
        val uploadId = initiate(toKey, contentType)
        try {
            val partSize = partSizeFor(size, MIN_COPY_PART)
            val etags = ArrayList<String>()
            var offset = 0L
            while (offset < size) {
                val last = minOf(offset + partSize, size) - 1
                val xml = call(
                    request(
                        "PUT", toKey,
                        query = mapOf("partNumber" to (etags.size + 1).toString(), "uploadId" to uploadId),
                        signedHeaders = mapOf(
                            "x-amz-copy-source" to copySource,
                            "x-amz-copy-source-range" to "bytes=$offset-$last",
                        ),
                    ),
                ) { it.body?.string().orEmpty() }
                etags += xmlText(xml, "ETag")?.takeIf { xml.contains("CopyPartResult") }
                    ?: throw IOException("s3: a part did not copy (${xmlText(xml, "Code").orEmpty()})")
                offset = last + 1
            }
            complete(toKey, uploadId, etags)
        } catch (failure: Throwable) {
            abort(toKey, uploadId)
            throw failure
        }
    }

    override fun close() {
        failedUpload = null
    }

    /**
     * Turns a request body's position in the file into the byte counts
     * [onSent] wants. Only progress past the furthest point so far counts, so
     * a body OkHttp sends twice, or a write tried again, is not counted twice.
     */
    private class Progress(private val onSent: (Long) -> Unit) {
        private var furthest = 0L

        fun at(position: Long) {
            if (position <= furthest) return
            val delta = position - furthest
            furthest = position
            onSent(delta)
        }
    }

    /** [length] bytes of [file] from [offset], read afresh each time OkHttp writes it. */
    private class FileRange(
        private val file: File,
        private val offset: Long,
        private val length: Long,
        private val type: MediaType?,
        private val progress: Progress,
    ) : RequestBody() {
        override fun contentType(): MediaType? = type
        override fun contentLength(): Long = length

        override fun writeTo(sink: BufferedSink) {
            RandomAccessFile(file, "r").use { input ->
                input.seek(offset)
                val buffer = ByteArray(BUFFER)
                var left = length
                while (left > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (n < 0) throw EOFException("the file got shorter while it was sent")
                    sink.write(buffer, 0, n)
                    left -= n
                    progress.at(offset + length - left)
                }
            }
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_S = 15L
        private const val READ_TIMEOUT_S = 60L
        private const val WRITE_TIMEOUT_S = 120L

        private const val PAGE = 1000
        private const val BUFFER = 64 * 1024
        private const val ERROR_BODY_BYTES = 8L * 1024

        /** Past this a file goes up in parts, each its own request that can fail alone. */
        private const val MULTIPART_ABOVE = 64L * 1024 * 1024
        private const val MIN_PART = 16L * 1024 * 1024
        private const val MIN_COPY_PART = 512L * 1024 * 1024
        private const val MAX_PARTS = 10_000L
        private const val MAX_SINGLE_COPY = 5L * 1024 * 1024 * 1024

        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_PRECONDITION_FAILED = 412
        private const val HTTP_NOT_IMPLEMENTED = 501

        private val IF_NONE_MATCH = mapOf("If-None-Match" to "*")

        /** Hidden from the Files tool by the store's temporary-upload mark, like any upload in progress. */
        private const val PROBE_NAME = ".wmkb-probe.wmpart-"
        private const val PROBE_TOKEN = 8

        /** Per service (scheme, host, bucket): whether it refuses a write over a key when asked with `If-None-Match: *`. */
        private val HONOURS = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

        private val ENCODED = Regex("""<EncodingType>\s*url\s*</EncodingType>""", RegexOption.IGNORE_CASE)

        /** Parts no smaller than [minimum], and few enough for S3's 10,000, rounded to a whole MiB. */
        private fun partSizeFor(size: Long, minimum: Long): Long {
            val mib = 1024L * 1024
            val needed = (size + MAX_PARTS - 1) / MAX_PARTS
            return maxOf(minimum, (needed + mib - 1) / mib * mib)
        }

        private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")

        /** A name a listing can show and a path can carry back: not empty, no slash, not `.` or `..`. */
        private fun listable(name: String): Boolean = name.isNotEmpty() && '/' !in name && name != "." && name != ".."

        /** Refuses a name that would make a different key: a slash is another folder, `.` and `..` are rewritten in a URL. */
        private fun checkName(name: String) {
            if (!listable(name)) throw IOException("not a name S3 can carry here: $name")
        }

        private fun sha256(file: File, offset: Long, length: Long): String {
            val digest = MessageDigest.getInstance("SHA-256")
            RandomAccessFile(file, "r").use { input ->
                input.seek(offset)
                val buffer = ByteArray(BUFFER)
                var left = length
                while (left > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (n < 0) throw EOFException("the file got shorter while it was read")
                    digest.update(buffer, 0, n)
                    left -= n
                }
            }
            return AwsSigV4.hex(digest.digest())
        }

        /** The text of the first `<tag>` in [xml], entities undone; null when there is none. */
        private fun xmlText(xml: String, tag: String): String? {
            val start = xml.indexOf("<$tag>").takeIf { it >= 0 } ?: return null
            val end = xml.indexOf("</$tag>", start).takeIf { it >= 0 } ?: return null
            return xml.substring(start + tag.length + 2, end).trim()
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&#34;", "\"").replace("&amp;", "&")
        }

        private fun escape(text: String): String =
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
