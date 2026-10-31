package com.wasimaster.wmkeyboard.core.dictionaries

import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.prediction.NgramPackBuilder
import com.wasimaster.wmkeyboard.core.prediction.NgramPackCodec
import com.wasimaster.wmkeyboard.core.prediction.WordKey
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

/**
 * Fetches [NgramPackCatalog] packs and compiles them on device into
 * `dict/<langId>/ngrams.wmng`, next to that language's downloaded wordlist.
 *
 * Pipeline: two HTTP streams -> gzip inflate -> parse `words count` lines
 * (count-descending, so each read simply stops at its cap) -> intern the words
 * into one shared vocabulary -> [NgramPackCodec] write -> `.part` -> atomic
 * rename. Presence == valid, exactly like the wordlists.
 *
 * Both lists land in a single file. They share a vocabulary, so splitting them
 * would store it twice, and one atomic rename makes a half-installed pack
 * impossible rather than something to clean up after.
 *
 * Modeled on `EmojiDictDownloadManager`'s automatic pass: [ensure] is
 * silent, queues one language at a time, and a language that fails is
 * remembered for the process rather than retried on every settings emission.
 * [states] drives the "Word pairs" row on a language's screen; [completions]
 * tells the IME to re-map the freshly landed pack.
 */
object NgramPackDownloadManager {

    sealed interface DownloadStatus {
        data object NotDownloaded : DownloadStatus

        /** Queued or transferring. The two reads stop at caps, so there is no
         * total to count towards, and the row shows an indeterminate bar. */
        data object Downloading : DownloadStatus
        data class Downloaded(val sizeBytes: Long) : DownloadStatus
        data object Failed : DownloadStatus
    }

    /**
     * Marker left behind when the user deletes a pack, so the automatic pass
     * does not fetch it straight back. Same contract as the emoji keywords'.
     */
    private const val DECLINED_NAME = "ngrams.declined"

    /** Names the lists the installed pack was compiled from. */
    private const val STEM_NAME = "ngrams.stem"

    private const val USER_AGENT = "WMKeyboard ngram pack downloader"

    /** Compile caps: the lists are count-descending, so taking the head
     * keeps the mass that matters. Sized so the on-device compile stays in
     * tens of MB of transient heap and the mapped file in the tens of MB. */
    private const val MAX_BIGRAMS = 150_000
    private const val MAX_TRIGRAMS = 75_000

    /** Words longer than this are junk lines, not vocabulary. */
    private const val MAX_WORD_LENGTH = 48

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private val jobs = HashMap<String, Job>()
    private val givenUp = HashSet<String>()

    private val _completions = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Emits a language id once its pack is on disk. */
    val completions: SharedFlow<String> = _completions.asSharedFlow()

    private val _states = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())

    /** Language id -> status, for every catalogued language seen so far. */
    val states: StateFlow<Map<String, DownloadStatus>> = _states.asStateFlow()

    private fun set(langId: String, status: DownloadStatus) {
        _states.update { it + (langId to status) }
    }

    /** Seeds [states] from disk; call when a row appears. */
    fun refresh(filesDir: File) {
        _states.update { current ->
            NgramPackCatalog.entries.associate { entry ->
                val langId = entry.languageId
                val live = current[langId]
                langId to when {
                    live == DownloadStatus.Downloading -> live
                    isDownloaded(filesDir, langId) ->
                        DownloadStatus.Downloaded(packFile(filesDir, langId).length())
                    live == DownloadStatus.Failed -> live
                    else -> DownloadStatus.NotDownloaded
                }
            }
        }
    }

    fun packFile(filesDir: File, langId: String): File =
        File(langDir(filesDir, langId), "ngrams.wmng")

    private fun langDir(filesDir: File, langId: String) =
        File(File(filesDir, "dict"), langId)

    fun isDownloaded(filesDir: File, langId: String): Boolean =
        packFile(filesDir, langId).isFile

    /**
     * Whether the installed pack is [entry]'s current one. Only a rebuilt pack
     * can be out of date, and an install from before the rebuild has no stem
     * file, which reads as the old pack it is. The old pack keeps predicting
     * until the new one lands over it.
     */
    private fun isCurrent(filesDir: File, entry: NgramPackEntry): Boolean {
        if (!entry.isRebuilt) return true
        val stem = File(langDir(filesDir, entry.languageId), STEM_NAME)
        return runCatching { stem.readText() }.getOrNull() == entry.bigramStem
    }

    private fun markCurrent(filesDir: File, entry: NgramPackEntry) {
        runCatching { File(langDir(filesDir, entry.languageId), STEM_NAME).writeText(entry.bigramStem) }
    }

    /**
     * Removes the two-file `.wmdict` packs of the format this replaced.
     *
     * Eagerly, before the presence check rather than after a successful
     * download: they are some 17 MB per language of a format nothing reads any
     * more, and leaving them until the new pack lands is exactly the case where
     * a full disk stops the new pack from landing at all.
     */
    private fun deleteLegacy(filesDir: File, langId: String) {
        File(langDir(filesDir, langId), "bigrams.wmdict").delete()
        File(langDir(filesDir, langId), "trigrams.wmdict").delete()
    }

    /**
     * Queues every catalogued language in [langIds] the device does not
     * already have. Silent by design: the user did not ask, so a failure is
     * remembered ([givenUp]) rather than surfaced, until the next process.
     */
    fun ensure(filesDir: File, langIds: Collection<String>) {
        val wanted = langIds.distinct()
        scope.launch {
            for (langId in wanted) {
                val entry = NgramPackCatalog.forLanguage(langId) ?: continue
                synchronized(jobs) {
                    if (langId in givenUp || jobs[langId]?.isActive == true) return@synchronized
                    deleteLegacy(filesDir, langId)
                    if (isDownloaded(filesDir, langId) && isCurrent(filesDir, entry)) return@synchronized
                    // Deleted on purpose: fetching it again would make Delete
                    // a button that undoes itself.
                    if (File(langDir(filesDir, langId), DECLINED_NAME).exists()) return@synchronized
                    set(langId, DownloadStatus.Downloading)
                    jobs[langId] = scope.launch {
                        try {
                            gate.withLock { download(filesDir, entry) }
                        } finally {
                            // Cancelled or failed without a verdict: the row
                            // must not sit on the bar forever.
                            if (_states.value[langId] == DownloadStatus.Downloading) {
                                set(langId, DownloadStatus.NotDownloaded)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Fetches one language's pack because the user asked — from the prompt
     * shown as a language is added, or a row under Settings › Languages.
     *
     * The same work [ensure] does, minus the give-up memory: a tap on Download
     * is a request to retry exactly the transfer that failed earlier.
     */
    fun start(filesDir: File, langId: String) {
        synchronized(jobs) { givenUp.remove(langId) }
        // Asking for it by hand undoes an earlier Delete.
        File(langDir(filesDir, langId), DECLINED_NAME).delete()
        ensure(filesDir, listOf(langId))
    }

    fun cancel(langId: String) {
        synchronized(jobs) { jobs[langId]?.cancel() }
    }

    /**
     * Removes [langId]'s pack and records that the user did not want it, so
     * the automatic pass leaves the language alone until they ask again.
     */
    fun delete(filesDir: File, langId: String) {
        cancel(langId)
        set(langId, DownloadStatus.NotDownloaded)
        scope.launch {
            val dir = langDir(filesDir, langId)
            packFile(filesDir, langId).delete()
            File(dir, "ngrams.wmng.part").delete()
            runCatching {
                dir.mkdirs()
                File(dir, DECLINED_NAME).createNewFile()
            }
        }
    }

    /**
     * Compiles [entry]'s pack from copies of its lists the user fetched some
     * other way, gzip or not. Either may be missing: a pack of bigrams alone
     * still predicts the next word, just from one word back. Runs on the
     * caller's thread and throws when neither list holds a usable line.
     */
    fun install(filesDir: File, entry: NgramPackEntry, bigrams: File?, trigrams: File?) {
        val langId = entry.languageId
        synchronized(jobs) { jobs[langId]?.cancel() }
        deleteLegacy(filesDir, langId)
        val builder = NgramPackBuilder()
        bigrams?.let { file ->
            read(openLocal(file), MAX_BIGRAMS, parts = 2) { words, count ->
                builder.addBigram(words[0], words[1], count)
            }
        }
        trigrams?.let { file ->
            read(openLocal(file), MAX_TRIGRAMS, parts = 3) { words, count ->
                builder.addTrigram(words[0], words[1], words[2], count)
            }
        }
        if (builder.isEmpty) throw IOException("no usable lines in the word pair lists for $langId")
        write(filesDir, langId, builder)
        // Whichever lists it came from, nothing fetches over what was imported.
        markCurrent(filesDir, entry)
        // An import is a request for the pack, so it undoes an earlier Delete.
        File(langDir(filesDir, langId), DECLINED_NAME).delete()
        synchronized(jobs) { givenUp.remove(langId) }
        set(langId, DownloadStatus.Downloaded(packFile(filesDir, langId).length()))
        _completions.tryEmit(langId)
    }

    /** A local list, inflated when it is gzip (the repo's form), as it is otherwise. */
    private fun openLocal(file: File): InputStream {
        val raw = file.inputStream().buffered()
        raw.mark(2)
        val gzip = raw.read() == 0x1f && raw.read() == 0x8b
        raw.reset()
        return if (gzip) GZIPInputStream(raw) else raw
    }

    private fun download(filesDir: File, entry: NgramPackEntry) {
        val langId = entry.languageId
        if (isDownloaded(filesDir, langId) && isCurrent(filesDir, entry)) {
            set(langId, DownloadStatus.Downloaded(packFile(filesDir, langId).length()))
            return
        }
        val ok = runCatching { compile(filesDir, entry) }.isSuccess
        if (ok) {
            set(langId, DownloadStatus.Downloaded(packFile(filesDir, langId).length()))
            _completions.tryEmit(langId)
        } else {
            set(langId, DownloadStatus.Failed)
            synchronized(jobs) { givenUp.add(langId) }
        }
    }

    private fun compile(filesDir: File, entry: NgramPackEntry) {
        val builder = NgramPackBuilder()
        read(entry.bigramUrl(), MAX_BIGRAMS, parts = 2) { words, count ->
            builder.addBigram(words[0], words[1], count)
        }
        read(entry.trigramUrl(), MAX_TRIGRAMS, parts = 3) { words, count ->
            builder.addTrigram(words[0], words[1], words[2], count)
        }
        if (builder.isEmpty) throw IOException("empty ngram lists for ${entry.languageId}")
        write(filesDir, entry.languageId, builder)
        markCurrent(filesDir, entry)
    }

    private fun write(filesDir: File, langId: String, builder: NgramPackBuilder) {
        val target = packFile(filesDir, langId)
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        part.outputStream().use { out -> NgramPackCodec.write(builder.build(), out) }
        if (!part.renameTo(target)) throw IOException("rename failed for $target")
    }

    private inline fun read(url: String, cap: Int, parts: Int, accept: (List<String>, Int) -> Unit) {
        val raw = openStream(url)
        // The gzip header is read here, so a bad one must still close the
        // connection and end its network-log entry.
        val inflated = try {
            GZIPInputStream(raw)
        } catch (t: Throwable) {
            raw.close()
            throw t
        }
        read(inflated, cap, parts, accept)
    }

    /** [stream] is the inflated list. */
    private inline fun read(stream: InputStream, cap: Int, parts: Int, accept: (List<String>, Int) -> Unit) {
        var taken = 0
        stream.use { raw ->
            raw.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (taken >= cap) break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                    val separator = trimmed.lastIndexOf(' ')
                    if (separator <= 0) continue
                    val count = trimmed.substring(separator + 1).toIntOrNull() ?: continue
                    val words = trimmed.substring(0, separator).trim().split(' ')
                    if (words.size != parts) continue
                    if (words.any { it.isEmpty() || it.length > MAX_WORD_LENGTH }) continue
                    accept(words.map { WordKey.of(it) }, count)
                    taken++
                }
            }
        }
    }

    /**
     * The stream outlives this function, so the network log's call ends when
     * the caller closes it rather than here.
     */
    private fun openStream(url: String): InputStream {
        val connection = URL(url).openConnection() as HttpURLConnection
        val netCall = NetLog.call(NetSource.DOWNLOAD_NGRAM, "GET", url, route = NetLog.pathOf(url))
        try {
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            netCall.status = connection.responseCode
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                throw IOException("HTTP ${connection.responseCode} for $url")
            }
            return object : FilterInputStream(netCall.countIn(connection.inputStream)) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        netCall.end()
                    }
                }
            }
        } catch (t: Throwable) {
            netCall.fail(t)
            netCall.end()
            throw t
        }
    }
}
