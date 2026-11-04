package com.wasimaster.wmkeyboard.core.dictionaries

import android.os.StatFs
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import com.wasimaster.wmkeyboard.core.prediction.AospScores
import com.wasimaster.wmkeyboard.core.prediction.MappedTrie
import com.wasimaster.wmkeyboard.core.prediction.PackedTrie
import com.wasimaster.wmkeyboard.core.prediction.PackedTrieCodec
import com.wasimaster.wmkeyboard.core.prediction.RomanianSpelling
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.prediction.R
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
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

/**
 * Downloads [DictionaryCatalog] wordlists and converts them to `.wmdict`
 * binaries in [DictionaryStore]'s layout — the `.gz` never touches disk.
 *
 * Pipeline: HTTP stream -> gzip inflate -> parse the first N most frequent
 * words (the lists are sorted, so the connection is aborted early — a 41 MB
 * Thai file costs only the first few MB of transfer) -> pack into a trie ->
 * write `main.wmdict.part` -> atomic rename. Presence of the final file means
 * the whole pipeline finished, exactly like `LocalLlmStore`'s contract.
 *
 * Modeled on `WhisperDownloadManager` (process singleton, own IO scope,
 * StateFlow of per-entry status) minus Range resume: a capped download is
 * cheap enough to restart, and a partially-consumed gzip stream cannot be
 * checksummed anyway — the gzip CRC of the blocks we do read plus the trie
 * build itself validate the payload.
 */
object WordlistDownloadManager {

    sealed interface DownloadStatus {
        data object NotDownloaded : DownloadStatus

        /** [totalBytes] is the compressed size of the FULL file — an upper
         * bound; a capped download completes well before reaching it. */
        data class Downloading(val bytes: Long, val totalBytes: Long) : DownloadStatus

        /** Transfer finished; packing entries into the binary trie. */
        data object Processing : DownloadStatus

        /**
         * [size] is the tier the file on disk was fetched at, so the UI can
         * offer the others. Read from the marker beside the file, or, for a
         * file written before that marker existed, the smallest tier that
         * could have produced [wordCount].
         */
        data class Downloaded(
            val wordCount: Int,
            val sizeBytes: Long,
            val size: DictionaryCatalog.DictionarySize,
        ) : DownloadStatus

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
            /**
             * Whether the list this download was replacing is still on the
             * device. A failed *re-download* at another size leaves the old
             * file untouched — it is only ever replaced by the atomic rename
             * at the very end — so the row keeps its checkbox and its Delete
             * beside the error.
             */
            val kept: Boolean = false,
        ) : DownloadStatus
    }

    enum class FailReason { NETWORK, NO_SPACE, MALFORMED, OTHER }

    /** Frequencies below this are subtitle/scrape noise, not vocabulary. */
    private const val MIN_FREQUENCY = 2

    /** Smallest array a flat list grows to, for a cap that clamped to almost nothing. */
    private const val MIN_GROWTH = 1024

    /** Guards against pathological lines masquerading as words. */
    private const val MAX_WORD_LENGTH = 48

    /** The largest list [foldCapitals] rebuilds in place. */
    private const val MAX_REFOLD_WORDS = 600_000

    /** Rough on-disk bytes per stored word (measured ~40 B on the bundled lists). */
    private const val BYTES_PER_WORD = 64L

    private const val SPACE_MARGIN_BYTES = 8L * 1024 * 1024
    private const val PROGRESS_INTERVAL_MS = 250L
    private const val USER_AGENT = "WMKeyboard dictionary downloader"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private var activeId: String? = null

    /** A [start] that came in while another download was running. */
    private class Queued(val filesDir: File, val entry: DictionaryEntry, val size: DictionaryCatalog.DictionarySize)

    /**
     * Downloads waiting their turn, oldest first. One runs at a time — every
     * download of a language writes the same `.part` — but a second one asked
     * for meanwhile waits here rather than being dropped: adding two languages
     * in the wizard used to fetch the first one's list and silently lose the
     * second's, which left glide typing dead in that language (#661).
     */
    private val queue = ArrayDeque<Queued>()
    private val lock = Any()

    /**
     * Entries [ensure] started whose download then failed. Not retried by
     * [ensure] again this process, so a dead network is not hammered on every
     * settings change; a [start] from the user clears it.
     */
    private val givenUp = HashSet<String>()

    /** Name of the marker a user's Delete leaves, so [ensure] does not undo it. */
    private const val DECLINED_NAME = "declined"

    private val _states = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())

    /** Entry id -> status for every catalog entry touched so far. */
    val states: StateFlow<Map<String, DownloadStatus>> = _states.asStateFlow()

    val isBusy: Boolean get() = activeJob?.isActive == true

    /** Seeds [states] from disk; call when a dictionary UI appears. */
    fun refresh(filesDir: File) {
        _states.update { current ->
            DictionaryCatalog.entries.associate { entry ->
                entry.id to when {
                    entry.id == activeId && isBusy && current[entry.id] != null ->
                        current.getValue(entry.id)
                    isQueued(entry.id) && current[entry.id] != null -> current.getValue(entry.id)
                    downloadedByThisEntry(filesDir, entry) -> downloadedStatus(filesDir, entry)
                    else -> DownloadStatus.NotDownloaded
                }
            }
        }
    }

    /**
     * Fetches [entry] at [size] because something asked for it by name: a tap
     * on Download, the add-a-language prompt, the wizard. Starts now, or after
     * the download already running. Asking by hand undoes an earlier Delete
     * and an earlier give-up, so [ensure] may fetch it again later.
     */
    fun start(filesDir: File, entry: DictionaryEntry, size: DictionaryCatalog.DictionarySize) {
        synchronized(lock) { givenUp.remove(entry.id) }
        declinedFile(filesDir, entry.languageId).delete()
        enqueue(filesDir, entry, size)
    }

    /**
     * Fetches the word list of every language in [langIds] that has none: not
     * on disk, not in [bundled] (the lists in the APK), not deleted by the user,
     * not already on its way and not given up on earlier this process. The
     * keyboard calls this as its enabled languages change, so a language whose
     * list never arrived — added while another was downloading, or on an
     * install that predates this — catches up by itself (#661).
     */
    fun ensure(
        filesDir: File,
        langIds: Collection<String>,
        bundled: Set<String>,
        size: DictionaryCatalog.DictionarySize,
    ) {
        for (langId in langIds.distinct()) {
            if (langId in bundled) continue
            val entry = DictionaryCatalog.preferred(langId) ?: continue
            val skip = synchronized(lock) {
                entry.id in givenUp || entry.id == activeId || isQueued(entry.id)
            }
            if (skip) continue
            if (DictionaryStore.isDownloaded(filesDir, langId)) continue
            if (declinedFile(filesDir, langId).exists()) continue
            enqueue(filesDir, entry, size)
        }
    }

    private fun enqueue(filesDir: File, entry: DictionaryEntry, size: DictionaryCatalog.DictionarySize) {
        synchronized(lock) {
            if (entry.id == activeId) return
            // One language, one `.part`: a newer ask for the same language
            // (another size, another region) replaces the one still waiting.
            queue.removeAll { it.entry.languageId == entry.languageId }
            // [activeId] rather than [isBusy]: it is cleared under [lock] by
            // the same block that starts the next download, so nothing can
            // land in the queue after that block looked and found it empty.
            if (activeId != null) {
                queue.addLast(Queued(filesDir, entry, size))
                set(entry.id, DownloadStatus.Downloading(0, entry.approxGzBytes))
            } else {
                begin(filesDir, entry, size)
            }
        }
    }

    private fun isQueued(entryId: String): Boolean =
        synchronized(lock) { queue.any { it.entry.id == entryId } }

    private fun declinedFile(filesDir: File, langId: String): File =
        File(DictionaryStore.downloadedFile(filesDir, langId).parentFile, DECLINED_NAME)

    /** Runs the next waiting download, if any. Called with [lock] held. */
    private fun beginNext() {
        val next = queue.removeFirstOrNull() ?: return
        begin(next.filesDir, next.entry, next.size)
    }

    /** Called with [lock] held, so the job cannot finish before it is recorded. */
    private fun begin(filesDir: File, entry: DictionaryEntry, size: DictionaryCatalog.DictionarySize) {
        activeId = entry.id
        set(entry.id, DownloadStatus.Downloading(0, entry.approxGzBytes))
        activeJob = scope.launch {
            val part = DictionaryStore.partFile(filesDir, entry.languageId)
            try {
                // Clamped here, not inside: ALL's cap is Int.MAX_VALUE, which
                // would ask the free-space check for 137 GB.
                val list = fetchEntries(entry, DictionaryCatalog.wordCap(entry, size), part)
                set(entry.id, DownloadStatus.Processing)
                val trie = pack(filesDir, entry.languageId, list, part)
                val final = DictionaryStore.downloadedFile(filesDir, entry.languageId)
                if (!part.renameTo(final)) {
                    throw FailedException(FailReason.OTHER, R.string.core_pred_wordlist_save_error)
                }
                DictionaryStore.writeSourceEntryId(filesDir, entry.languageId, entry.id)
                DictionaryStore.writeDownloadedSize(filesDir, entry.languageId, size.name)
                set(entry.id, DownloadStatus.Downloaded(trie.wordCount, final.length(), size))
                // The phonetic index the data repo built over this list, so the
                // keyboard maps it rather than folding the list itself (#716).
                // Only for the list it was built over, the language's own.
                if (entry.id == entry.languageId) PhoneticIndexDownloads.start(filesDir, entry.languageId)
                // A language can have several catalog entries (pt); the one
                // just replaced must stop claiming the file.
                refresh(filesDir)
            } catch (e: kotlinx.coroutines.CancellationException) {
                part.delete()
                // Nothing was replaced — the rename is the last step — so the
                // list this one was going to replace is still there. A
                // cancelled job is no longer active, so refresh reads it from
                // disk; [activeId] is cleared below, with the queue's turn.
                refresh(filesDir)
                throw e
            } catch (e: FailedException) {
                part.delete()
                synchronized(lock) { givenUp += entry.id }
                set(
                    entry.id,
                    DownloadStatus.Failed(
                        e.reason,
                        e.messageRes,
                        e.messageArg,
                        kept = downloadedByThisEntry(filesDir, entry),
                    ),
                )
            } catch (_: Exception) {
                part.delete()
                synchronized(lock) { givenUp += entry.id }
                set(
                    entry.id,
                    DownloadStatus.Failed(
                        FailReason.NETWORK,
                        OfflineBuild.download(CommonR.string.common_error_network),
                        kept = downloadedByThisEntry(filesDir, entry),
                    ),
                )
            } catch (_: OutOfMemoryError) {
                // A large list on a device with a small heap (seen on an
                // Android TV set): the trie is built in memory, and running
                // out took the whole app down. The arrays that failed
                // are garbage by now, so saying so is safe.
                part.delete()
                synchronized(lock) { givenUp += entry.id }
                set(
                    entry.id,
                    DownloadStatus.Failed(
                        FailReason.OTHER,
                        R.string.core_pred_wordlist_memory_error,
                        kept = downloadedByThisEntry(filesDir, entry),
                    ),
                )
            } finally {
                synchronized(lock) {
                    activeId = null
                    beginNext()
                }
            }
        }
    }

    /**
     * Builds [entry]'s dictionary from a copy of its list the user fetched
     * some other way, [file], which is the repo's `.txt.gz` or the same list
     * inflated. The same parse and the same trie as a download, run on the
     * caller's thread, so a build with no internet permission can still have
     * every language's words. Returns the words kept.
     *
     * Refused while a download is running, for the same reason a second
     * download is: both write the one `.part` beside the list.
     */
    fun install(filesDir: File, entry: DictionaryEntry, size: DictionaryCatalog.DictionarySize, file: File): Int {
        check(!isBusy) { "a download is running" }
        val part = DictionaryStore.partFile(filesDir, entry.languageId)
        part.parentFile?.mkdirs()
        synchronized(lock) { activeId = entry.id }
        try {
            set(entry.id, DownloadStatus.Processing)
            val list = openList(file).use { input ->
                parse(entry, DictionaryCatalog.wordCap(entry, size), input) {}
            }
            val trie = pack(filesDir, entry.languageId, list, part)
            val final = DictionaryStore.downloadedFile(filesDir, entry.languageId)
            if (!part.renameTo(final)) throw IOException("could not move the list into place")
            DictionaryStore.writeSourceEntryId(filesDir, entry.languageId, entry.id)
            DictionaryStore.writeDownloadedSize(filesDir, entry.languageId, size.name)
            return trie.wordCount
        } finally {
            part.delete()
            // A download asked for meanwhile queued behind this one.
            synchronized(lock) {
                activeId = null
                beginNext()
            }
            refresh(filesDir)
        }
    }

    /**
     * Packs [list] into [part], keyed in lower case, and writes the capitals
     * that folding took out of it beside the list (see [DictionaryCapitals]).
     * The capitals go first: the list is only moved into place after this
     * returns, so a list on disk always has its capitals with it.
     */
    private fun pack(filesDir: File, langId: String, list: Wordlist, part: File): PackedTrie {
        // Before the fold, which rewrites the words in place: a language whose
        // capitals are letters keeps every spelling, not one shape per key.
        val spellings = if (LanguageRegistry.byId(langId).letterCaseIsSpelling) {
            DictionaryCapitals.spellingsOf(list.words, list.frequencies, list.count)
        } else {
            null
        }
        val capitals = DictionaryCapitals.fold(list.words, list.count).capitals
        val trie = PackedTrie.of(list.words, list.frequencies, list.count)
        part.outputStream().use { PackedTrieCodec.write(trie, it) }
        val file = DictionaryStore.capitalsFile(filesDir, langId)
        // Empty when the list has no capitals: the file is also the mark that
        // this list is keyed in lower case, which [foldCapitals] reads.
        // Renamed into place, never written over: the keyboard may have the
        // old one mapped, and a mapped file cut short under its reader faults.
        val scratch = File(file.path + ".part")
        scratch.outputStream().use { out -> capitals?.let { PackedTrieCodec.write(it, out) } }
        if (!scratch.renameTo(file)) scratch.delete()
        val spellingsFile = DictionaryStore.spellingsFile(filesDir, langId)
        if (spellings == null) {
            spellingsFile.delete()
        } else {
            val spellingsScratch = File(spellingsFile.path + ".part")
            spellingsScratch.outputStream().use { PackedTrieCodec.write(spellings, it) }
            if (!spellingsScratch.renameTo(spellingsFile)) spellingsScratch.delete()
        }
        return trie
    }

    /**
     * Rebuilds, from what is already on the device, every list downloaded
     * before capitals were kept apart. Such a list holds `Haus` under a key
     * that typing "haus" never reaches (#481). Nothing is fetched, and a list
     * already done is one file check. Run once at keyboard start, off the
     * main thread, before the lists are mapped.
     */
    fun foldCapitals(filesDir: File) {
        if (isBusy) return
        for (langId in DictionaryStore.downloadedLanguageIds(filesDir)) {
            if (DictionaryStore.capitalsFile(filesDir, langId).exists()) continue
            val main = DictionaryStore.downloadedFile(filesDir, langId)
            val part = DictionaryStore.partFile(filesDir, langId)
            runCatching {
                val mapped = MappedTrie.open(main) ?: return@runCatching
                // An "everything" list is millions of words, and this walk
                // puts each on the heap. Those are counted lists, lower case
                // already; the curated ones that carry capitals are far smaller.
                if (mapped.wordCount > MAX_REFOLD_WORDS) {
                    DictionaryStore.capitalsFile(filesDir, langId).createNewFile()
                    return@runCatching
                }
                val entries = mapped.entries()
                val words = arrayOfNulls<String>(entries.size)
                val frequencies = IntArray(entries.size)
                // Most frequent first, the order a download reads them in,
                // which is what decides between two spellings of one word.
                entries.sortedByDescending { it.second }.forEachIndexed { i, (word, frequency) ->
                    words[i] = word
                    frequencies[i] = frequency
                }
                pack(filesDir, langId, Wordlist(words, frequencies, entries.size), part)
                if (!part.renameTo(main)) part.delete()
            }.onFailure {
                part.delete()
                DictionaryStore.capitalsFile(filesDir, langId).delete()
            }
        }
    }

    /**
     * Rewrites, in place, a Romanian list downloaded before its spelling was
     * repaired ([RomanianSpelling]). Nothing is fetched. Run beside
     * [foldCapitals] at keyboard start, off the main thread.
     *
     * The test is four lookups on the mapped trie, so a list that is already
     * right costs nothing and this can run at every start rather than once
     * behind a marker. That is also what lets a list too big to rebuild here
     * heal by itself the moment its owner downloads one at another size.
     */
    fun respellRomanian(filesDir: File) {
        if (isBusy) return
        val langId = RomanianSpelling.LANGUAGE_ID
        val main = DictionaryStore.downloadedFile(filesDir, langId)
        if (!main.exists()) return
        val part = DictionaryStore.partFile(filesDir, langId)
        runCatching {
            val mapped = MappedTrie.open(main) ?: return@runCatching
            if (RomanianSpelling.PROBES.none { mapped.contains(it) }) return@runCatching
            // The same ceiling as [foldCapitals], for the same reason: the
            // walk below puts every word on the heap, and an "everything"
            // list is over a million of them.
            if (mapped.wordCount > MAX_REFOLD_WORDS) return@runCatching
            if (!respell(mapped, main, part)) return@runCatching
            // The capitals are a second trie under the same keys, so a key
            // with a cedilla in it has to move with the list or the spelling
            // it holds stops being reachable (see [DictionaryCapitals]).
            val capitals = DictionaryStore.capitalsFile(filesDir, langId)
            if (capitals.length() > 0) {
                MappedTrie.open(capitals)?.let {
                    respell(it, capitals, File(capitals.path + ".part"))
                }
            }
        }.onFailure { part.delete() }
    }

    /**
     * [mapped] with every key respelt, written back over [file] through
     * [part]. True when it landed.
     *
     * Serves the list and its capitals both: the trie's second column is a
     * frequency in one and a capitalisation shape in the other, and neither
     * is touched. Where two keys become one, [PackedTrie.of] keeps the larger
     * value, which is the more frequent spelling of a word and the commoner
     * shape of a name.
     */
    private fun respell(mapped: MappedTrie, file: File, part: File): Boolean {
        val entries = mapped.entries()
        val keys = arrayOfNulls<String>(entries.size)
        val values = IntArray(entries.size)
        entries.forEachIndexed { i, (key, value) ->
            keys[i] = RomanianSpelling.canonical(key)
            values[i] = value
        }
        part.outputStream().use {
            PackedTrieCodec.write(PackedTrie.of(keys, values, entries.size), it)
        }
        if (part.renameTo(file)) return true
        part.delete()
        return false
    }

    /** [file] inflated when it is gzip (the repo's form), as it is otherwise. */
    private fun openList(file: File): InputStream {
        val raw = file.inputStream().buffered()
        raw.mark(2)
        val gzip = raw.read() == 0x1f && raw.read() == 0x8b
        raw.reset()
        return if (gzip) GZIPInputStream(raw, 32 * 1024) else raw
    }

    /**
     * Stops [entryId]'s download: drops it from the queue when it is still
     * waiting there, cancels it when it is the one running. With no id, the
     * running one. The rest of the queue carries on either way.
     */
    fun cancel(entryId: String? = null) {
        val dropped = synchronized(lock) {
            queue.firstOrNull { it.entry.id == entryId }?.also { queue.remove(it) }
        }
        if (dropped != null) {
            refresh(dropped.filesDir)
            return
        }
        if (entryId == null || entryId == activeId) activeJob?.cancel()
    }

    /**
     * Deletes [entry]'s list because the user asked, and remembers that they
     * did, so [ensure] does not fetch it straight back.
     */
    fun delete(filesDir: File, entry: DictionaryEntry) {
        cancel(entry.id)
        DictionaryStore.delete(filesDir, entry.languageId)
        declinedFile(filesDir, entry.languageId).let { marker ->
            marker.parentFile?.mkdirs()
            runCatching { marker.createNewFile() }
        }
        refresh(filesDir)
    }

    private fun downloadedByThisEntry(filesDir: File, entry: DictionaryEntry): Boolean {
        if (!DictionaryStore.isDownloaded(filesDir, entry.languageId)) return false
        val source = DictionaryStore.sourceEntryId(filesDir, entry.languageId)
        return source == entry.id || (source == null && entry.id == entry.languageId)
    }

    private fun downloadedStatus(filesDir: File, entry: DictionaryEntry): DownloadStatus {
        val file = DictionaryStore.downloadedFile(filesDir, entry.languageId)
        val words = runCatching { PackedTrieCodec.readHeader(file).wordCount }.getOrDefault(0)
        return DownloadStatus.Downloaded(words, file.length(), sizeOf(filesDir, entry, words))
    }

    /**
     * The tier [entry]'s file on disk holds: what the download recorded, or,
     * for one written before the marker existed, the smallest tier whose cap
     * could have produced [words]. The guess errs small on purpose — a list cut
     * short by the frequency floor has fewer words than its tier promised, so
     * the first tier that fits is the one to offer stepping up from.
     */
    private fun sizeOf(filesDir: File, entry: DictionaryEntry, words: Int): DictionaryCatalog.DictionarySize {
        val recorded = DictionaryStore.downloadedSize(filesDir, entry.languageId)
        return DictionaryCatalog.DictionarySize.entries.firstOrNull { it.name == recorded }
            ?: DictionaryCatalog.DictionarySize.entries.firstOrNull {
                DictionaryCatalog.wordCap(entry, it) >= words
            }
            ?: DictionaryCatalog.DictionarySize.ALL
    }

    private fun set(id: String, status: DownloadStatus) {
        _states.update { it + (id to status) }
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

    /** An InputStream that counts what passes through, for progress against
     * the compressed size (the gzip stream's consumption, not inflated bytes). */
    private class CountingInputStream(private val wrapped: InputStream) : InputStream() {
        @Volatile
        var bytesRead = 0L

        override fun read(): Int = wrapped.read().also { if (it >= 0) bytesRead++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            wrapped.read(b, off, len).also { if (it > 0) bytesRead += it }

        override fun close() = wrapped.close()
    }

    /**
     * The first [count] slots of two parallel arrays: word `i` and its
     * frequency. Arrays rather than a `List<Pair>` because an "everything"
     * list runs to millions of entries, where a `Pair` and a boxed `Integer`
     * apiece is tens of MB the trie build then has to squeeze in beside (#203).
     */
    private class Wordlist(val words: Array<String?>, val frequencies: IntArray, val count: Int)

    /**
     * Streams the remote gzip list and returns its first [wordCap] usable
     * entries, aborting the transfer once the cap is hit. A flat list, one
     * with no frequencies to be sorted by, comes back whole.
     */
    private suspend fun fetchEntries(
        entry: DictionaryEntry,
        wordCap: Int,
        part: File,
    ): Wordlist {
        part.parentFile?.mkdirs()
        val free = StatFs(part.parentFile!!.path).availableBytes
        if (free < wordCap * BYTES_PER_WORD + SPACE_MARGIN_BYTES) {
            throw FailedException(FailReason.NO_SPACE, R.string.core_pred_wordlist_no_space_error)
        }

        val connection = URL(entry.url).openConnection() as HttpURLConnection
        val netCall = NetLog.call(NetSource.DOWNLOAD_WORDLIST, "GET", entry.url, route = NetLog.pathOf(entry.url))
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept-Encoding", "identity")
            netCall.status = connection.responseCode
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw FailedException(
                    FailReason.OTHER,
                    R.string.core_pred_wordlist_http_error,
                    status.toString(),
                )
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: entry.approxGzBytes

            val counting = CountingInputStream(netCall.countIn(connection.inputStream))
            var lastUpdate = 0L
            return GZIPInputStream(counting, 32 * 1024).use { input ->
                parse(entry, wordCap, input) {
                    currentCoroutineContext().ensureActive()
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate >= PROGRESS_INTERVAL_MS) {
                        lastUpdate = now
                        set(entry.id, DownloadStatus.Downloading(counting.bytesRead, total))
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

    /**
     * The first [wordCap] usable entries of an inflated `word count` list, the
     * way [fetchEntries] and [install] both read it. [onLine] runs once per
     * line, for progress and cancellation.
     */
    private inline fun parse(entry: DictionaryEntry, wordCap: Int, input: InputStream, onLine: () -> Unit): Wordlist {
        // wordCap is already clamped to the list's length, and a sorted
        // list stops at it, so only a flat one (below) ever grows these.
        var words = arrayOfNulls<String>(wordCap)
        var frequencies = IntArray(wordCap)
        var count = 0
        // Whether the noise cut below applies at all, decided by the first
        // usable line. A few repo lists are bare wordlists wearing the
        // frequency format — Bengali's 451,348 words all say `1` — and on
        // those the cut fires on line one, empties the list and reports it
        // as unreadable. A list whose *most frequent* word is already under
        // the floor has no frequencies to rank by, so there is no noise
        // tail to trim and every word is kept.
        //
        // An AOSP list has no noise tail to cut either: it is curated, and
        // its bottom is real words AOSP rated rare, down to 0.
        //
        // A flat list has no order for the word cap to choose by either.
        // Bengali's is alphabetical, so its first 300k lines stopped at
        // বিশ্ব… and every word from ভ to হ was missing — সরাসরি among
        // them, which then lost to a sibling from the kept half. A flat
        // list is taken whole, whatever size was asked for.
        val aosp = entry.source == WordlistSource.AOSP
        // A Romanian list spells its comma-below letters with a cedilla, and
        // the spelling the list carries is the one autocorrect commits
        // ([RomanianSpelling]). Respelt here, one word at a time, rather than
        // at every lookup. Both spellings of a word then land on one key, so
        // the trie ends a few thousand words under the cap; it merges them by
        // the larger count, which is the ranking the two halves earn together.
        val respell = RomanianSpelling.appliesTo(entry.languageId)
        var ranked: Boolean? = if (aosp) false else null
        input.bufferedReader().useLines { lines ->
            for (line in lines) {
                onLine()
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val separator = trimmed.lastIndexOf(' ')
                if (separator <= 0) continue
                val read = trimmed.substring(0, separator).trim()
                val word = if (respell) RomanianSpelling.canonical(read) else read
                val raw = trimmed.substring(separator + 1).toIntOrNull() ?: continue
                val frequency = if (aosp) AospScores.listCount(raw) else raw
                if (ranked == null) ranked = frequency >= MIN_FREQUENCY
                // Sorted desc, so on a ranked list only noise follows.
                if (ranked == true && frequency < MIN_FREQUENCY) break
                if (word.length > MAX_WORD_LENGTH || ' ' in word) continue
                words[count] = word
                frequencies[count] = frequency
                count++
                if (count >= words.size) {
                    if (ranked == true || aosp) break
                    words = words.copyOf(maxOf(words.size * 2, MIN_GROWTH))
                    frequencies = frequencies.copyOf(words.size)
                }
            }
        }
        if (count == 0) {
            throw FailedException(FailReason.MALFORMED, R.string.core_pred_wordlist_malformed_error)
        }
        return Wordlist(words, frequencies, count)
    }
}
