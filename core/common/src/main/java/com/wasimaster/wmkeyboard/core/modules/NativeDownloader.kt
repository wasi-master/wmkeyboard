package com.wasimaster.wmkeyboard.core.modules

import com.wasimaster.wmkeyboard.core.download.DataMirror
import com.wasimaster.wmkeyboard.core.download.DownloadException
import com.wasimaster.wmkeyboard.core.download.FileDownloader
import com.wasimaster.wmkeyboard.core.endpoints.GitForge
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import kotlinx.coroutines.ensureActive
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import kotlin.coroutines.coroutineContext

/**
 * Fetches one [NativePin] into its [NativeStore] staging file and installs
 * it. Every request goes through [NetLog] under [NetSource.DOWNLOAD_NATIVE],
 * so the network activity log shows each host and a build without the
 * internet permission stops here.
 */
object NativeDownloader {

    /** The data repository's release that holds the project's own libraries. */
    const val RELEASE_TAG = "natives"

    private const val TIMEOUT_MS = 30_000
    private const val USER_AGENT = "WMKeyboard native delivery"

    /** The release asset for a [NativeSource.Data] pin, or null when the data repository is not on GitHub. */
    fun dataUrl(pin: NativePin): String? {
        val repo = ServiceEndpoints.repo(ServiceRepo.DATA)
        if (repo.forge != GitForge.GITHUB) return null
        return "https://${repo.host}/${repo.owner}/${repo.repo}/releases/download/$RELEASE_TAG/${pin.sha256}-${pin.name}"
    }

    /** The page listing every published library, or null when the data repository is not on GitHub. */
    fun releasePage(): String? {
        val repo = ServiceEndpoints.repo(ServiceRepo.DATA)
        if (repo.forge != GitForge.GITHUB) return null
        return "https://${repo.host}/${repo.owner}/${repo.repo}/releases/tag/$RELEASE_TAG"
    }

    /**
     * Downloads [pin] and installs it into [store]. [onBytes] gets this
     * file's running byte count. Throws on any failure; a data download goes
     * through [FileDownloader], parallel and resumable piece by piece.
     */
    suspend fun fetch(pin: NativePin, store: NativeStore, onBytes: (Long) -> Unit) {
        val part = store.partOf(pin)
        part.parentFile?.mkdirs()
        when (val source = pin.source) {
            NativeSource.Data -> {
                val url = dataUrl(pin) ?: throw IOException("the data repository is not on GitHub")
                val mirror = DataMirror.of(url)
                FileDownloader.fetchVia(url, mirror, part, pin.size, NetSource.DOWNLOAD_NATIVE, onBytes = onBytes)
                if (mirror != null) {
                    coroutineContext.ensureActive()
                    if (store.install(pin, part)) return
                    // The mirror's copy may be the bad one: the origin's decides.
                    FileDownloader.discard(part)
                    FileDownloader.fetchVia(url, null, part, pin.size, NetSource.DOWNLOAD_NATIVE, onBytes = onBytes)
                }
            }
            is NativeSource.Maven -> {
                // The entry is read whole each time: it is a few MB out of an
                // artifact, and resuming inside a deflate stream is not possible.
                part.delete()
                val remote = HttpRangeSource(source.url)
                val entry = ZipRangeReader.find(remote, source.entry)
                    ?: throw IOException("${source.entry} is not in ${source.coordinates}")
                if (entry.size != pin.size) throw IOException("${source.entry} is ${entry.size} bytes, pinned ${pin.size}")
                FileOutputStream(part).use { out ->
                    ZipRangeReader.extract(remote, entry, out) { written ->
                        onBytes(written)
                    }
                }
            }
        }
        coroutineContext.ensureActive()
        if (!store.install(pin, part)) throw IOException("${pin.name} did not match its pinned checksum")
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            // The byte offsets of a range are offsets into the file as stored.
            setRequestProperty("Accept-Encoding", "identity")
        }

    /** Tries per request of a vendor's archive, the same as [FileDownloader]'s per piece plus one. */
    private const val ATTEMPTS = 4

    /** A server that answered a range request with something other than the range: no retry helps. */
    private class NoRangeAnswer(message: String) : IOException(message)

    private fun retryable(e: IOException): Boolean = when (e) {
        is DownloadException.Http -> e.status >= 500
        is NoRangeAnswer -> false
        is UnknownHostException -> false
        else -> true
    }

    /**
     * [block] again after a dropped connection or a server error, waiting a
     * little longer each time. Every request of a vendor's archive goes
     * through here: one connection lost on a phone's mobile data used to
     * fail the whole library, and the next try began again from nothing.
     */
    private fun <T> retrying(block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: IOException) {
                attempt++
                if (!retryable(e) || attempt >= ATTEMPTS) throw e
                Thread.sleep(RETRY_BASE_MS shl attempt)
            }
        }
    }

    private const val RETRY_BASE_MS = 500L

    /** A remote file read with HTTP range requests, each one a [NetLog] row. */
    private class HttpRangeSource(private val url: String) : RangeSource {

        override val length: Long by lazy { retrying { head() } }

        private fun head(): Long {
            val connection = open(url)
            val call = NetLog.call(NetSource.DOWNLOAD_NATIVE, "HEAD", url, route = NetLog.pathOf(url))
            try {
                connection.requestMethod = "HEAD"
                call.status = connection.responseCode
                if (connection.responseCode !in 200..299) throw DownloadException.Http(connection.responseCode)
                return connection.contentLengthLong.takeIf { it > 0 } ?: throw IOException("no length for $url")
            } catch (t: Throwable) {
                call.fail(t)
                throw t
            } finally {
                connection.disconnect()
                call.end()
            }
        }

        override fun read(offset: Long, count: Int): ByteArray =
            open(offset, count.toLong()).use { input ->
                val out = ByteArray(count)
                var at = 0
                while (at < count) {
                    val n = input.read(out, at, count - at)
                    if (n < 0) throw IOException("short read from $url")
                    at += n
                }
                out
            }

        /**
         * [count] bytes from [offset] that carry on where they stopped when
         * the connection drops: the library inside an archive is one long
         * stream of a few to a dozen megabytes, which a phone moving between
         * networks rarely finishes in one go.
         */
        override fun open(offset: Long, count: Long): InputStream = Resuming(offset, count)

        private inner class Resuming(private val offset: Long, private val count: Long) : InputStream() {
            private var done = 0L
            private var current: InputStream? = null
            private var failures = 0

            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                if (done >= count) return -1
                while (true) {
                    val n = try {
                        val stream = current ?: retrying { range(offset + done, count - done) }.also { current = it }
                        stream.read(b, off, minOf(len.toLong(), count - done).toInt())
                    } catch (e: IOException) {
                        drop()
                        failures++
                        if (!retryable(e) || failures >= ATTEMPTS) throw e
                        Thread.sleep(RETRY_BASE_MS shl failures)
                        continue
                    }
                    if (n < 0) {
                        // The server closed before the range was done: ask for the rest.
                        drop()
                        failures++
                        if (failures >= ATTEMPTS) throw IOException("short read from $url")
                        continue
                    }
                    done += n
                    return n
                }
            }

            private fun drop() {
                runCatching { current?.close() }
                current = null
            }

            override fun close() = drop()
        }

        private fun range(offset: Long, count: Long): InputStream {
            val connection = open(url)
            val call = NetLog.call(NetSource.DOWNLOAD_NATIVE, "GET", url, route = NetLog.pathOf(url))
            try {
                connection.setRequestProperty("Range", "bytes=$offset-${offset + count - 1}")
                call.status = connection.responseCode
                when (val status = connection.responseCode) {
                    HttpURLConnection.HTTP_PARTIAL -> Unit
                    in 200..299 -> throw NoRangeAnswer("$url did not answer a range request (status $status)")
                    else -> throw DownloadException.Http(status)
                }
            } catch (t: Throwable) {
                call.fail(t)
                call.end()
                connection.disconnect()
                throw t
            }
            return object : FilterInputStream(call.countIn(connection.inputStream)) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        connection.disconnect()
                        call.end()
                    }
                }
            }
        }
    }
}
