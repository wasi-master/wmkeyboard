package com.wasimaster.wmkeyboard.core.settings.remote

import com.wasimaster.wmkeyboard.core.settings.sink.BackupHttp
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

/**
 * What the four OAuth cloud adapters ([DriveFs], [DropboxFs], [OneDriveFs],
 * [BoxFs]) share: one HTTP client, a bounded wait on "slow down" answers, a
 * request body that streams a slice of a file, and the byte counting the
 * Files tool's progress bar hears.
 *
 * Nothing here decides what is safe to write; each adapter picks the
 * service's refuse-on-conflict mode itself.
 */
internal object CloudFsHttp {

    val json = Json { ignoreUnknownKeys = true }

    /** The backup's own client: the internet gate, the network log, redirects followed. */
    val client: OkHttpClient by lazy { BackupHttp.client() }

    val OCTET_STREAM: MediaType = "application/octet-stream".toMediaType()
    val JSON_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()

    private const val HTTP_TOO_MANY = 429
    private const val HTTP_UNAVAILABLE = 503
    private const val MAX_RETRIES = 3
    private const val MAX_WAIT_MS = 30_000L
    private const val ERROR_PEEK = 4096L

    /**
     * Sends [request] and returns the answer, which the caller closes.
     *
     * When [idempotent], a 429 or 503 is waited out (its `Retry-After`, or a
     * doubling pause) and the request sent again, at most [MAX_RETRIES]
     * times. Anything that changes a file is sent once: a second try of a
     * create the server did half-take could make a second file.
     */
    fun execute(request: Request, idempotent: Boolean, client: OkHttpClient = this.client): Response {
        var attempt = 0
        while (true) {
            val response = client.newCall(request).execute()
            val busy = response.code == HTTP_TOO_MANY || response.code == HTTP_UNAVAILABLE
            if (!busy || !idempotent || attempt >= MAX_RETRIES) return response
            val wait = retryAfterMs(response) ?: (1000L shl attempt)
            if (wait > MAX_WAIT_MS) return response
            response.close()
            Thread.sleep(wait)
            attempt++
        }
    }

    /** `Retry-After` in milliseconds, when it is a number of seconds. */
    fun retryAfterMs(response: Response): Long? =
        response.header("Retry-After")?.trim()?.toLongOrNull()?.coerceAtLeast(0)?.times(1000)

    /** The failure for an answer that was not a success, with the start of its body for the log. */
    fun failure(what: String, response: Response): CloudHttpException {
        val body = runCatching { response.peekBody(ERROR_PEEK).string() }.getOrDefault("")
        return CloudHttpException(response.code, body, "$what -> ${response.code} ${body.take(300)}")
    }

    /** The answer's body as JSON, or a failure saying it was not. */
    fun jsonOf(response: Response): JsonObject = parse(response.body.string())

    fun parse(text: String): JsonObject = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        ?: throw IOException("the service answered with something that is not JSON")

    /**
     * A JSON value written with every character outside printable ASCII as
     * `\uXXXX`. Dropbox takes its arguments in an HTTP header, and a header
     * value may hold nothing else; OkHttp refuses one that does.
     */
    fun asciiJson(value: JsonElement): String {
        val text = value.toString()
        val out = StringBuilder(text.length + 16)
        for (c in text) {
            if (c.code in 0x20..0x7e) out.append(c) else out.append("\\u%04x".format(c.code))
        }
        return out.toString()
    }

    /** Copies the answer's body into [to] as it arrives; [to] may throw to stop, which goes through. */
    fun stream(response: Response, to: OutputStream) {
        response.body.byteStream().use { input -> input.copyTo(to, BUFFER) }
    }

    /** SHA-1 of [length] bytes of [file] from [offset], for Box's upload parts. */
    fun sha1(file: File, offset: Long, length: Long, also: MessageDigest? = null): ByteArray {
        val digest = MessageDigest.getInstance("SHA-1")
        FileInputStream(file).use { input ->
            input.channel.position(offset)
            val buffer = ByteArray(BUFFER)
            var left = length
            while (left > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n < 0) throw IOException("the file got shorter while it was read")
                digest.update(buffer, 0, n)
                also?.update(buffer, 0, n)
                left -= n
            }
        }
        return digest.digest()
    }

    /** A segment of a path must be a name, never a way out of its folder. */
    fun checkNames(path: List<String>) {
        for (name in path) {
            if (name.isEmpty() || name == "." || name == ".." || name.contains('/')) {
                throw IOException("not a file name: \"$name\"")
            }
        }
    }

    const val BUFFER = 64 * 1024
}

/** An HTTP answer that was not a success; [code] and the start of [body] say which. */
internal class CloudHttpException(val code: Int, val body: String, message: String) : IOException(message)

/**
 * Turns positions reached in an upload into the increments the Files tool
 * adds up. Only new ground counts, so a piece sent twice is not counted
 * twice and the total never passes the file's size.
 */
internal class SentCounter(private val onSent: (Long) -> Unit) {
    private var high = 0L

    fun reached(position: Long) {
        if (position > high) {
            val step = position - high
            high = position
            onSent(step)
        }
    }
}

/**
 * [length] bytes of [file] from [offset], read as they are sent so a file
 * of any size passes through a small buffer. One-shot, so OkHttp never
 * quietly sends it a second time behind the adapter's back.
 */
internal class FileSliceBody(
    private val file: File,
    private val offset: Long,
    private val length: Long,
    private val type: MediaType?,
    private val counter: SentCounter?,
) : RequestBody() {

    override fun contentType(): MediaType? = type

    override fun contentLength(): Long = length

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        FileInputStream(file).use { input ->
            input.channel.position(offset)
            val buffer = ByteArray(CloudFsHttp.BUFFER)
            var sent = 0L
            while (sent < length) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), length - sent).toInt())
                if (n < 0) throw IOException("the file got shorter while it was sent")
                sink.write(buffer, 0, n)
                sent += n
                counter?.reached(offset + sent)
            }
        }
    }
}

/**
 * Keeps the Files tool's clean-up after a failed upload from deleting
 * something that is not the upload's.
 *
 * When an upload fails, [com.wasimaster.wmkeyboard.core.files.RemoteFiles]
 * deletes the name it uploaded to, to take away a half-made file. With the
 * refuse-on-conflict uploads these adapters use, a failure usually means the
 * service made nothing: it refused, often because the name is taken after
 * all (someone else made it a moment ago, or the service counts two names as
 * one that the Files tool told apart, such as two Unicode spellings of "é").
 * Deleting that name then would delete the other file. So an upload that
 * failed before its last request went out, or was answered with an error,
 * is marked as having made nothing, and the delete that follows it is let
 * through without touching anything. Only a last request that was sent and
 * then lost without an answer leaves the clean-up to go ahead, because then
 * the file may exist and be the upload's own.
 */
internal class UploadGuard {
    private var nothingMade: List<String>? = null
    private var finalSent = false

    fun <T> run(path: List<String>, block: () -> T): T {
        nothingMade = null
        finalSent = false
        try {
            return block()
        } catch (failure: Throwable) {
            // A stop from the caller is not an IOException: the last request never finished, so nothing was made.
            if (!finalSent || failure is CloudHttpException || failure !is IOException) nothingMade = path
            throw failure
        }
    }

    /** Called just before the request that would make the file appear under its name. */
    fun finalRequest() {
        finalSent = true
    }

    /** Whether [path] is the upload that just made nothing; answers true once. */
    fun madeNothing(path: List<String>): Boolean {
        val hit = nothingMade == path
        nothingMade = null
        return hit
    }
}
