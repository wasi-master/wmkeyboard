package com.wasimaster.wmkeyboard.core.vocab

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import com.wasimaster.wmkeyboard.tools.R
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Downloads [VocabCatalog] packs and their translation sidecars into
 * [VocabPacks]. Modeled on `EmojiDictDownloadManager`: a process singleton
 * with its own IO scope, a StateFlow of per-pack status, and downloads that
 * queue behind one another. Nothing here runs by itself — every download is
 * a tap — so there is no give-up list and no automatic pass.
 *
 * Pipeline: HTTP stream → gzip inflate → the inflated JSON streamed to
 * `<id>.wmvocab.json.part` → [VocabPackFile.decode] of that file →
 * delete-then-rename. The pack lands inflated so every later reader is one
 * parse.
 */
object VocabDownloadManager {

    sealed interface DownloadStatus {
        data object NotDownloaded : DownloadStatus
        data object Queued : DownloadStatus
        data class Downloading(val bytes: Long, val totalBytes: Long) : DownloadStatus
        data class Downloaded(val wordCount: Int, val sizeBytes: Long) : DownloadStatus

        /** [messageRes] takes [messageArg] as its one format argument when that is not empty. */
        data class Failed(
            val reason: FailReason,
            @StringRes val messageRes: Int,
            val messageArg: String = "",
        ) : DownloadStatus
    }

    enum class FailReason { NETWORK, MALFORMED, OTHER }

    private const val USER_AGENT = "WMKeyboard vocabulary downloader"
    private const val PROGRESS_INTERVAL_MS = 200L
    /** What [VocabPackFile.decode] reads at most: a bigger pack would download and never load. */
    private const val MAX_INFLATED_BYTES = VocabPackFile.MAX_BYTES

    /** A translation sidecar's ceiling, as it was before downloads went to disk. */
    private const val MAX_TRANSLATION_BYTES = 16L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private val jobs = HashMap<String, Job>()

    private val _states = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())

    /** Pack id → status, for every catalogue pack touched so far. */
    val states: StateFlow<Map<String, DownloadStatus>> = _states.asStateFlow()

    private val _translationStates = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())

    /** `"<packId>/<code>"` → status for translation sidecars. */
    val translationStates: StateFlow<Map<String, DownloadStatus>> = _translationStates.asStateFlow()

    private val _completions = MutableSharedFlow<String>(extraBufferCapacity = 32)

    /** Emits a pack id once its file (or a sidecar of it) landed on disk. */
    val completions: SharedFlow<String> = _completions.asSharedFlow()

    /** Seeds [states] from disk; call when a pack list appears. */
    suspend fun refresh(filesDir: File) = withContext(Dispatchers.IO) {
        _states.update { current ->
            VocabCatalog.entries.associate { entry ->
                val status = current[entry.id]
                val resting = status == null || status is DownloadStatus.Downloaded ||
                    status is DownloadStatus.NotDownloaded
                entry.id to if (!resting) {
                    status
                } else {
                    val file = VocabPacks.packFile(filesDir, entry.langId, entry.id)
                    val disabled = File(file.parentFile, file.name + VocabPacks.DISABLED_SUFFIX)
                    val present = listOf(file, disabled).firstOrNull { it.isFile }
                    if (present != null) DownloadStatus.Downloaded(entry.wordCount, present.length()) else DownloadStatus.NotDownloaded
                }
            }
        }
        _translationStates.update { current ->
            val out = HashMap<String, DownloadStatus>()
            for (entry in VocabCatalog.entries) {
                for (code in entry.translationCodes) {
                    val key = translationKey(entry.id, code)
                    val status = current[key]
                    val resting = status == null || status is DownloadStatus.Downloaded ||
                        status is DownloadStatus.NotDownloaded
                    out[key] = if (!resting) {
                        status
                    } else {
                        val file = VocabPacks.translationFile(filesDir, entry.langId, entry.id, code)
                        if (file.isFile) DownloadStatus.Downloaded(0, file.length()) else DownloadStatus.NotDownloaded
                    }
                }
            }
            out
        }
    }

    fun translationKey(packId: String, code: String): String = "$packId/$code"

    /**
     * Downloads (or re-downloads) one pack; the row shows progress meanwhile.
     * [translations] are the sidecar codes to fetch right after it lands, so
     * a pack arrives with the user's languages rather than waiting for a
     * second visit to the packs screen.
     */
    fun start(filesDir: File, entry: VocabCatalogEntry, translations: Collection<String> = emptyList()) {
        val wanted = translations.filter { it in entry.translationCodes }
        enqueue(entry.id, ::setPack) {
            downloadPack(filesDir, entry)
            if (states.value[entry.id] is DownloadStatus.Downloaded) startTranslations(filesDir, entry, wanted)
        }
    }

    /** Downloads translation sidecars for [codes] the catalogue offers for [entry]. */
    fun startTranslations(filesDir: File, entry: VocabCatalogEntry, codes: Collection<String>) {
        for (code in codes) {
            if (code !in entry.translationCodes) continue
            enqueue(translationKey(entry.id, code), ::setTranslation) { downloadTranslation(filesDir, entry, code) }
        }
    }

    fun cancel(id: String) {
        synchronized(jobs) { jobs[id]?.cancel() }
    }

    fun delete(filesDir: File, entry: VocabCatalogEntry) {
        cancel(entry.id)
        setPack(entry.id, DownloadStatus.NotDownloaded)
        scope.launch {
            val file = VocabPacks.packFile(filesDir, entry.langId, entry.id)
            val disabled = File(file.parentFile, file.name + VocabPacks.DISABLED_SUFFIX)
            listOf(file, disabled).firstOrNull { it.isFile }?.let { VocabPacks.remove(it) }
            for (code in entry.translationCodes) {
                setTranslation(translationKey(entry.id, code), DownloadStatus.NotDownloaded)
            }
        }
    }

    fun deleteTranslation(filesDir: File, entry: VocabCatalogEntry, code: String) {
        val key = translationKey(entry.id, code)
        cancel(key)
        setTranslation(key, DownloadStatus.NotDownloaded)
        scope.launch {
            VocabPacks.translationFile(filesDir, entry.langId, entry.id, code).delete()
            _completions.tryEmit(entry.id)
        }
    }

    private fun enqueue(key: String, set: (String, DownloadStatus) -> Unit, block: suspend () -> Unit) {
        synchronized(jobs) {
            if (jobs[key]?.isActive == true) return
            set(key, DownloadStatus.Queued)
            jobs[key] = scope.launch {
                try {
                    gate.withLock { block() }
                } catch (e: CancellationException) {
                    synchronized(jobs) { if (jobs[key]?.isActive != true) set(key, DownloadStatus.NotDownloaded) }
                    throw e
                } finally {
                    synchronized(jobs) { if (jobs[key]?.isActive != true) jobs.remove(key) }
                }
            }
        }
    }

    private suspend fun downloadPack(filesDir: File, entry: VocabCatalogEntry) {
        val id = entry.id
        val part = VocabPacks.partFile(filesDir, entry.langId, id)
        setPack(id, DownloadStatus.Downloading(0, entry.approxGzBytes))
        try {
            part.parentFile?.mkdirs()
            fetchInflated(entry.url, entry.approxGzBytes, part) { bytes, total ->
                setPack(id, DownloadStatus.Downloading(bytes, total))
            }
            // Read back the way every later reader opens the pack, so a pack
            // that downloads is one that loads.
            val pack = part.inputStream().use { VocabPackFile.decode(it) }
                ?: throw FailedException(FailReason.MALFORMED, R.string.core_tools_vocab_error_not_a_pack)
            if (pack.words.isEmpty()) {
                throw FailedException(FailReason.MALFORMED, R.string.core_tools_vocab_error_empty)
            }
            currentCoroutineContext().ensureActive()
            val final = VocabPacks.packFile(filesDir, entry.langId, id)
            val disabled = File(final.parentFile, final.name + VocabPacks.DISABLED_SUFFIX)
            // A re-download of a pack the user switched off stays off.
            val target = if (disabled.isFile) disabled else final
            target.delete()
            if (!part.renameTo(target)) {
                throw FailedException(FailReason.OTHER, R.string.core_tools_vocab_error_save)
            }
            setPack(id, DownloadStatus.Downloaded(pack.words.size, target.length()))
            _completions.tryEmit(id)
        } catch (e: CancellationException) {
            part.delete()
            synchronized(jobs) { if (jobs[id]?.isActive != true) setPack(id, DownloadStatus.NotDownloaded) }
            throw e
        } catch (e: FailedException) {
            part.delete()
            setPack(id, DownloadStatus.Failed(e.reason, e.messageRes, e.messageArg))
        } catch (_: Exception) {
            part.delete()
            setPack(id, DownloadStatus.Failed(FailReason.NETWORK, OfflineBuild.download(CommonR.string.common_error_network)))
        }
    }

    private suspend fun downloadTranslation(filesDir: File, entry: VocabCatalogEntry, code: String) {
        val key = translationKey(entry.id, code)
        val part = VocabPacks.translationPartFile(filesDir, entry.langId, entry.id, code)
        setTranslation(key, DownloadStatus.Downloading(0, 0))
        try {
            part.parentFile?.mkdirs()
            fetchInflated(entry.translationUrl(code), 0, part, MAX_TRANSLATION_BYTES) { bytes, total ->
                setTranslation(key, DownloadStatus.Downloading(bytes, total))
            }
            if (!startsWithBrace(part)) {
                throw FailedException(FailReason.MALFORMED, R.string.core_tools_vocab_error_not_a_pack)
            }
            currentCoroutineContext().ensureActive()
            val final = VocabPacks.translationFile(filesDir, entry.langId, entry.id, code)
            final.delete()
            if (!part.renameTo(final)) {
                throw FailedException(FailReason.OTHER, R.string.core_tools_vocab_error_save)
            }
            setTranslation(key, DownloadStatus.Downloaded(0, final.length()))
            _completions.tryEmit(entry.id)
        } catch (e: CancellationException) {
            part.delete()
            synchronized(jobs) { if (jobs[key]?.isActive != true) setTranslation(key, DownloadStatus.NotDownloaded) }
            throw e
        } catch (e: FailedException) {
            part.delete()
            setTranslation(key, DownloadStatus.Failed(e.reason, e.messageRes, e.messageArg))
        } catch (_: Exception) {
            part.delete()
            setTranslation(key, DownloadStatus.Failed(FailReason.NETWORK, OfflineBuild.download(CommonR.string.common_error_network)))
        }
    }

    /** Whether [file]'s first non-blank character opens a JSON object. */
    private fun startsWithBrace(file: File): Boolean = file.inputStream().buffered().use { input ->
        var b = input.read()
        while (b >= 0 && Character.isWhitespace(b)) b = input.read()
        b == '{'.code
    }

    private fun setPack(id: String, status: DownloadStatus) {
        _states.update { it + (id to status) }
    }

    private fun setTranslation(key: String, status: DownloadStatus) {
        _translationStates.update { it + (key to status) }
    }

    private class FailedException(
        val reason: FailReason,
        @StringRes val messageRes: Int,
        val messageArg: String = "",
    ) : IOException()

    private class CountingInputStream(private val wrapped: InputStream) : InputStream() {
        @Volatile
        var bytesRead = 0L

        override fun read(): Int = wrapped.read().also { if (it >= 0) bytesRead++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            wrapped.read(b, off, len).also { if (it > 0) bytesRead += it }

        override fun close() = wrapped.close()
    }

    /**
     * Streams a gzipped text file inflated into [target], reporting compressed
     * progress. Straight to disk: held as a String on the way, a large pack
     * cost several times its size in heap (UTF-16, plus every doubling of the
     * builder and the copy out of it) and ran small heaps out of memory.
     */
    private suspend fun fetchInflated(
        url: String,
        approxBytes: Long,
        target: File,
        maxBytes: Long = MAX_INFLATED_BYTES,
        progress: (bytes: Long, total: Long) -> Unit,
    ) {
        val connection = URL(url).openConnection() as HttpURLConnection
        val netCall = NetLog.call(NetSource.DOWNLOAD_VOCAB, "GET", url, route = NetLog.pathOf(url))
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            // The payload is already gzip; a transport re-encode would be
            // inflated out from under the GZIPInputStream below.
            connection.setRequestProperty("Accept-Encoding", "identity")
            netCall.status = connection.responseCode
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw FailedException(FailReason.OTHER, R.string.core_tools_vocab_error_http, status.toString())
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: approxBytes
            val counting = CountingInputStream(netCall.countIn(connection.inputStream))
            val buffer = ByteArray(16 * 1024)
            var written = 0L
            var lastUpdate = 0L
            GZIPInputStream(counting, 32 * 1024).use { input ->
                target.outputStream().buffered().use { output ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        if (written > maxBytes) {
                            throw FailedException(FailReason.MALFORMED, R.string.core_tools_vocab_error_too_large)
                        }
                        output.write(buffer, 0, read)
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= PROGRESS_INTERVAL_MS) {
                            lastUpdate = now
                            progress(counting.bytesRead, total)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            netCall.fail(t)
            throw t
        } finally {
            connection.disconnect()
            netCall.end()
        }
    }
}
