package com.wasimaster.wmkeyboard.core.emoji

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import com.wasimaster.wmkeyboard.emoji.R
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
 * Downloads [EmojiDictCatalog] dictionaries into [EmojiDictStore].
 *
 * Pipeline: HTTP stream -> gzip inflate -> [EmojiDictCodec.decode] -> write
 * `pack.tsv.part` -> atomic rename. Modeled on `WordlistDownloadManager`
 * (process singleton, own IO scope, StateFlow of per-language status) with two
 * differences, both from the payload being small — the largest is 112 KB
 * compressed:
 *
 *  - the whole file is taken, so there is no size tier and no early abort;
 *  - requests **queue** instead of being refused while one is in flight,
 *    because the auto-download pass asks for every enabled language at once
 *    and dropping all but the first would leave the rest silently missing.
 */
object EmojiDictDownloadManager {

    sealed interface DownloadStatus {
        data object NotDownloaded : DownloadStatus

        /** Waiting for the language ahead of it in the queue. */
        data object Queued : DownloadStatus
        data class Downloading(val bytes: Long, val totalBytes: Long) : DownloadStatus
        data class Downloaded(val emojiCount: Int, val sizeBytes: Long) : DownloadStatus

        /**
         * [messageRes] is the text to show the user. [messageArg] is the one
         * format argument that text takes, or "" when it takes none. The UI
         * resolves the pair together:
         * `if (messageArg.isEmpty()) stringResource(messageRes)`
         * `else stringResource(messageRes, messageArg)`.
         */
        data class Failed(
            val reason: FailReason,
            @StringRes val messageRes: Int,
            val messageArg: String = "",
        ) : DownloadStatus
    }

    enum class FailReason { NETWORK, MALFORMED, OTHER }

    private const val USER_AGENT = "WMKeyboard emoji dictionary downloader"
    private const val PROGRESS_INTERVAL_MS = 200L

    /**
     * Ceiling on the inflated payload. The largest real dictionary is ~1.5 MB
     * of JSON; this is the guard against a redirect to something else
     * entirely, since the transfer is otherwise unbounded.
     */
    private const val MAX_INFLATED_BYTES = 32L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One at a time: these are queued, not raced. */
    private val gate = Mutex()
    private val jobs = HashMap<String, Job>()

    private val _states = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())

    /** Language id -> status, for every language touched so far. */
    val states: StateFlow<Map<String, DownloadStatus>> = _states.asStateFlow()

    private val _completions = MutableSharedFlow<String>(extraBufferCapacity = 32)

    /**
     * Emits a language id once its pack has landed on disk.
     *
     * The merged emoji catalogue is built once and held in memory, so someone
     * has to say "rebuild it" — this is that signal. Whichever process ran the
     * download bumps the settings counter the keyboard watches, so a download
     * started from the settings app reaches the running IME too.
     */
    val completions: SharedFlow<String> = _completions.asSharedFlow()

    /**
     * Languages whose download failed in this process, so the automatic pass
     * does not retry a 404 on every keystroke-free moment for the rest of the
     * session. Cleared by an explicit [start] — a user tapping Download is
     * asking for exactly that retry.
     */
    private val givenUp = HashSet<String>()

    private fun giveUp(langId: String) {
        synchronized(jobs) { givenUp.add(langId) }
    }

    private fun hasGivenUp(langId: String): Boolean =
        synchronized(jobs) { langId in givenUp }

    /**
     * Seeds [states] from disk; call when a dictionary UI appears.
     *
     * Suspending because it stats every catalogue entry, and the rows that
     * call it do so from composition — a hundred-odd file checks on the main
     * thread is exactly the sort of thing that shows up as a dropped frame
     * when the screen opens.
     */
    suspend fun refresh(filesDir: File) = withContext(Dispatchers.IO) {
        _states.update { current ->
            EmojiDictCatalog.entries.associate { entry ->
                val language = entry.languageId
                val live = current[language]
                entry.languageId to when {
                    live is DownloadStatus.Downloading || live is DownloadStatus.Queued -> live
                    EmojiDictStore.isDownloaded(filesDir, language) -> downloadedStatus(filesDir, entry)
                    live is DownloadStatus.Failed -> live
                    else -> DownloadStatus.NotDownloaded
                }
            }
        }
    }

    /**
     * Downloads [entry] because the user asked: clears any earlier give-up,
     * and refetches even when a pack is already on disk.
     *
     * A language already downloading is left alone rather than restarted —
     * the row offers Cancel, not Download, while one is in flight, so this
     * only happens if two surfaces race, and the running transfer is the one
     * worth keeping.
     */
    fun start(filesDir: File, entry: EmojiDictEntry) {
        synchronized(jobs) { givenUp.remove(entry.languageId) }
        // Asking for it by hand undoes an earlier Delete.
        scope.launch { EmojiDictStore.clearDeclined(filesDir, entry.languageId) }
        enqueue(filesDir, entry, force = true)
    }

    /**
     * Queues every language in [langIds] that the catalogue covers and the
     * device does not already have — the automatic pass.
     *
     * Deliberately silent: nothing here surfaces an error, because the user
     * did not ask for a download and a failed one is not their problem to
     * solve. A language that fails is remembered in [givenUp], and one the
     * user deleted stays deleted; both are left alone until asked for by hand.
     */
    fun ensure(filesDir: File, langIds: Collection<String>) {
        val wanted = langIds.distinct()
        // Off the caller's thread: the keyboard calls this from its settings
        // collector on every emission, and "is it downloaded?" is a file stat
        // per language.
        scope.launch {
            for (langId in wanted) {
                if (hasGivenUp(langId)) continue
                val entry = EmojiDictCatalog.forLanguage(langId) ?: continue
                if (EmojiDictStore.isDownloaded(filesDir, langId)) continue
                // Deleted on purpose: fetching it again would make Delete a
                // button that undoes itself.
                if (EmojiDictStore.isDeclined(filesDir, langId)) continue
                enqueue(filesDir, entry, force = false)
            }
        }
    }

    private fun enqueue(filesDir: File, entry: EmojiDictEntry, force: Boolean) {
        val language = entry.languageId
        synchronized(jobs) {
            // One job per language, ever. Replacing a live one would race its
            // cancellation against the new job over the same status entry and
            // the same `.part` file.
            if (jobs[language]?.isActive == true) return
            set(language, DownloadStatus.Queued)
            jobs[language] = scope.launch {
                try {
                    // The gate is what makes this a queue: the coroutine is
                    // already live, but only one is past this line at a time.
                    gate.withLock { download(filesDir, entry, force) }
                } catch (e: CancellationException) {
                    // Cancelled while queued, so `download` never ran and never
                    // got to clear the status. Without this the row sits on
                    // "Waiting…" with no way back.
                    clearIfNotRunning(language)
                    throw e
                } finally {
                    synchronized(jobs) { if (jobs[language]?.isActive != true) jobs.remove(language) }
                }
            }
        }
    }

    private suspend fun download(filesDir: File, entry: EmojiDictEntry, force: Boolean) {
        val language = entry.languageId
        // Something else may have finished this language while it waited. A
        // hand-started download says so explicitly and refreshes anyway.
        if (!force && EmojiDictStore.isDownloaded(filesDir, language)) {
            set(language, downloadedStatus(filesDir, entry))
            return
        }
        val part = EmojiDictStore.partFile(filesDir, language)
        set(language, DownloadStatus.Downloading(0, entry.approxGzBytes))
        try {
            val pack = fetch(entry)
            if (pack.isEmpty) {
                throw FailedException(FailReason.MALFORMED, R.string.core_emoji_dict_error_empty)
            }
            currentCoroutineContext().ensureActive()
            part.parentFile?.mkdirs()
            part.writeText(EmojiDictCodec.encodeTsv(pack))
            val final = EmojiDictStore.packFile(filesDir, language)
            // Delete-then-rename: File.renameTo does not replace on every
            // filesystem, and a re-download of an existing pack must land.
            final.delete()
            if (!part.renameTo(final)) {
                throw FailedException(FailReason.OTHER, R.string.core_emoji_dict_error_save)
            }
            set(language, DownloadStatus.Downloaded(pack.size, final.length()))
            _completions.tryEmit(language)
        } catch (e: CancellationException) {
            part.delete()
            clearIfNotRunning(language)
            throw e
        } catch (e: FailedException) {
            part.delete()
            giveUp(language)
            set(language, DownloadStatus.Failed(e.reason, e.messageRes, e.messageArg))
        } catch (_: Exception) {
            part.delete()
            giveUp(language)
            set(
                language,
                DownloadStatus.Failed(FailReason.NETWORK, OfflineBuild.download(CommonR.string.common_error_network)),
            )
        }
    }

    /**
     * Installs [entry]'s dictionary from a copy the user fetched some other
     * way: the repo's `.json.gz`, or the same JSON inflated. Decoded and
     * stored exactly as a download is, on the caller's thread. Returns how
     * many emoji it names; throws when the file is not a dictionary.
     */
    fun install(filesDir: File, entry: EmojiDictEntry, file: File): Int {
        val language = entry.languageId
        cancel(language)
        val raw = file.inputStream().buffered()
        raw.mark(2)
        val gzip = raw.read() == 0x1f && raw.read() == 0x8b
        raw.reset()
        val text = (if (gzip) GZIPInputStream(raw) else raw).reader().use { reader ->
            val out = StringBuilder()
            val buffer = CharArray(16 * 1024)
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                out.appendRange(buffer, 0, read)
                if (out.length > MAX_INFLATED_BYTES) throw IOException("emoji dictionary too large")
            }
            out.toString()
        }
        val pack = EmojiDictCodec.decode(text)?.takeUnless { it.isEmpty }
            ?: throw IOException("not an emoji dictionary")
        val part = EmojiDictStore.partFile(filesDir, language)
        part.parentFile?.mkdirs()
        part.writeText(EmojiDictCodec.encodeTsv(pack))
        val final = EmojiDictStore.packFile(filesDir, language)
        final.delete()
        if (!part.renameTo(final)) {
            part.delete()
            throw IOException("could not move the dictionary into place")
        }
        EmojiDictStore.clearDeclined(filesDir, language)
        synchronized(jobs) { givenUp.remove(language) }
        set(language, DownloadStatus.Downloaded(pack.size, final.length()))
        _completions.tryEmit(language)
        return pack.size
    }

    fun cancel(langId: String) {
        synchronized(jobs) { jobs[langId]?.cancel() }
    }

    fun delete(filesDir: File, langId: String) {
        cancel(langId)
        // The row updates now; the file goes away on the IO scope, because a
        // recursive delete is not main-thread work.
        set(langId, DownloadStatus.NotDownloaded)
        scope.launch { EmojiDictStore.delete(filesDir, langId) }
    }

    /**
     * Resets a cancelled language to "not downloaded" — unless a fresh job has
     * already claimed it, whose status must not be clobbered by the corpse of
     * the one before.
     */
    private fun clearIfNotRunning(langId: String) {
        synchronized(jobs) {
            if (jobs[langId]?.isActive != true) set(langId, DownloadStatus.NotDownloaded)
        }
    }

    private fun downloadedStatus(filesDir: File, entry: EmojiDictEntry): DownloadStatus {
        val file = EmojiDictStore.packFile(filesDir, entry.languageId)
        return DownloadStatus.Downloaded(entry.emojiCount, file.length())
    }

    private fun set(langId: String, status: DownloadStatus) {
        _states.update { it + (langId to status) }
    }

    /**
     * A download failure that already knows which message the UI should show.
     * The exception's own `message` stays null: the text lives in [messageRes]
     * so it follows the app language.
     */
    private class FailedException(
        val reason: FailReason,
        @StringRes val messageRes: Int,
        val messageArg: String = "",
    ) : IOException()

    /** Counts what passes through, for progress against the compressed size. */
    private class CountingInputStream(private val wrapped: InputStream) : InputStream() {
        @Volatile
        var bytesRead = 0L

        override fun read(): Int = wrapped.read().also { if (it >= 0) bytesRead++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            wrapped.read(b, off, len).also { if (it > 0) bytesRead += it }

        override fun close() = wrapped.close()
    }

    private suspend fun fetch(entry: EmojiDictEntry): EmojiKeywordPack {
        val connection = URL(entry.url).openConnection() as HttpURLConnection
        val netCall = NetLog.call(NetSource.DOWNLOAD_EMOJI, "GET", entry.url, route = NetLog.pathOf(entry.url))
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            // Ask for the bytes as stored: the payload is already gzip, and a
            // transport-level re-encode would be inflated out from under the
            // GZIPInputStream below.
            connection.setRequestProperty("Accept-Encoding", "identity")
            netCall.status = connection.responseCode
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw FailedException(
                    FailReason.OTHER,
                    R.string.core_emoji_dict_error_http,
                    status.toString(),
                )
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: entry.approxGzBytes
            val counting = CountingInputStream(netCall.countIn(connection.inputStream))
            val text = StringBuilder()
            val buffer = CharArray(16 * 1024)
            var lastUpdate = 0L
            GZIPInputStream(counting, 32 * 1024).reader().use { reader ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = reader.read(buffer)
                    if (read < 0) break
                    text.appendRange(buffer, 0, read)
                    if (text.length > MAX_INFLATED_BYTES) {
                        throw FailedException(
                            FailReason.MALFORMED,
                            R.string.core_emoji_dict_error_too_large,
                        )
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate >= PROGRESS_INTERVAL_MS) {
                        lastUpdate = now
                        set(entry.languageId, DownloadStatus.Downloading(counting.bytesRead, total))
                    }
                }
            }
            return EmojiDictCodec.decode(text.toString())
                ?: throw FailedException(
                    FailReason.MALFORMED,
                    R.string.core_emoji_dict_error_not_a_list,
                )
        } catch (t: Throwable) {
            netCall.fail(t)
            throw t
        } finally {
            connection.disconnect()
            netCall.end()
        }
    }
}
