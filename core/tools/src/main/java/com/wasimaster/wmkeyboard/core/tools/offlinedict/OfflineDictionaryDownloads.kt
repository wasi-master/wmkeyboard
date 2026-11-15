package com.wasimaster.wmkeyboard.core.tools.offlinedict

import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Downloads [OfflineDictionaryCatalog] packs into [OfflineDictionaries], the
 * way `VocabDownloadManager` fetches vocabulary packs: a process singleton,
 * one download at a time, a StateFlow per pack. Every download is a tap.
 * The file streams straight through gzip to disk; nothing holds it in memory.
 */
object OfflineDictionaryDownloads {

    sealed interface Status {
        data object NotDownloaded : Status
        data object Queued : Status
        data class Downloading(val bytes: Long, val totalBytes: Long) : Status
        /** [outdated]: an older build of the pack, which a download replaces (#1085). */
        data class Downloaded(val sizeBytes: Long, val outdated: Boolean = false) : Status

        /** [httpStatus] is the server's answer when it refused, 0 for a network or file failure. */
        data class Failed(val notAPack: Boolean, val httpStatus: Int = 0) : Status
    }

    private const val USER_AGENT = "WMKeyboard dictionary downloader"
    private const val PROGRESS_INTERVAL_MS = 200L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private val jobs = HashMap<String, Job>()

    private val _states = MutableStateFlow<Map<String, Status>>(emptyMap())

    /** Pack id → status. */
    val states: StateFlow<Map<String, Status>> = _states.asStateFlow()

    /** Seeds [states] from disk, leaving a download in flight alone. */
    fun refresh(filesDir: File) {
        _states.update { current ->
            OfflineDictionaryCatalog.entries.associate { pack ->
                val status = current[pack.id]
                val busy = status is Status.Queued || status is Status.Downloading
                pack.id to if (busy) {
                    status!!
                } else {
                    val file = OfflineDictionaries.file(filesDir, pack.id)
                    if (file.isFile) {
                        Status.Downloaded(file.length(), OfflineDictionaries.isOutdated(filesDir, pack))
                    } else {
                        status?.takeIf { it is Status.Failed } ?: Status.NotDownloaded
                    }
                }
            }
        }
    }

    fun start(filesDir: File, pack: OfflineDictionaryPack) {
        synchronized(jobs) {
            if (jobs[pack.id]?.isActive == true) return
            set(pack.id, Status.Queued)
            jobs[pack.id] = scope.launch {
                try {
                    gate.withLock { download(filesDir, pack) }
                } finally {
                    synchronized(jobs) { jobs.remove(pack.id) }
                }
            }
        }
    }

    fun cancel(id: String) {
        synchronized(jobs) { jobs[id]?.cancel() }
    }

    fun delete(filesDir: File, id: String) {
        cancel(id)
        OfflineDictionaries.delete(filesDir, id)
        set(id, Status.NotDownloaded)
    }

    private suspend fun download(filesDir: File, pack: OfflineDictionaryPack) {
        val url = pack.url
        val connection = URL(url).openConnection() as HttpURLConnection
        val netCall = NetLog.call(NetSource.DOWNLOAD_DICTIONARY, "GET", url, route = NetLog.pathOf(url))
        set(pack.id, Status.Downloading(0, pack.approxGzBytes))
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            // The payload is gzip already; a transport encoding would be undone under it.
            connection.setRequestProperty("Accept-Encoding", "identity")
            val code = connection.responseCode
            netCall.status = code
            if (code != HttpURLConnection.HTTP_OK) {
                set(pack.id, Status.Failed(notAPack = false, httpStatus = code))
                return
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: pack.approxGzBytes
            val counting = CountingInputStream(netCall.countIn(connection.inputStream))
            val context = currentCoroutineContext()
            var lastUpdate = 0L
            val installed = counting.use {
                OfflineDictionaries.install(filesDir, it, expectedId = pack.id) {
                    context.ensureActive()
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate >= PROGRESS_INTERVAL_MS) {
                        lastUpdate = now
                        set(pack.id, Status.Downloading(counting.bytesRead, total))
                    }
                }
            }
            set(pack.id, Status.Downloaded(installed.sizeBytes))
        } catch (e: CancellationException) {
            netCall.fail(e)
            set(pack.id, if (OfflineDictionaries.isInstalled(filesDir, pack.id)) {
                Status.Downloaded(OfflineDictionaries.file(filesDir, pack.id).length(), OfflineDictionaries.isOutdated(filesDir, pack))
            } else {
                Status.NotDownloaded
            })
            throw e
        } catch (e: OfflineDictionaries.NotAPackException) {
            netCall.fail(e)
            set(pack.id, Status.Failed(notAPack = true))
        } catch (e: Exception) {
            // Offline, refused, out of space: the row says it failed and offers the retry.
            netCall.fail(e)
            set(pack.id, Status.Failed(notAPack = false))
        } finally {
            connection.disconnect()
            netCall.end()
        }
    }

    private fun set(id: String, status: Status) {
        _states.update { it + (id to status) }
    }

    private class CountingInputStream(private val wrapped: InputStream) : InputStream() {
        @Volatile
        var bytesRead = 0L

        override fun read(): Int = wrapped.read().also { if (it >= 0) bytesRead++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            wrapped.read(b, off, len).also { if (it > 0) bytesRead += it }

        override fun close() = wrapped.close()
    }
}
