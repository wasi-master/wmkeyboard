package com.wasimaster.wmkeyboard.core.dictionaries

import com.wasimaster.wmkeyboard.core.gesture.GlideLanguageProfiles
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.prediction.PackedTrie
import com.wasimaster.wmkeyboard.core.prediction.PackedTrieCodec
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * The spellings a language's joined glide readings have to be found in (#1014).
 *
 * The decoder restarts at the trie root after any listed word, so a word list
 * says which words exist and nothing about which pairs of them form a compound:
 * `garagentor` came out as `fragenroot`, and English entries in the German list
 * made fine tails. So a joined reading stands only when its whole spelling has
 * been seen. With no lexicon there are no joins at all ([SuggestionEngine]).
 *
 * Nothing new is published for this. The data repo's frequency list for the
 * language is far longer than anyone's word cap (German's has 1.15 million rows),
 * and it is read whole here, keeping only what could be a compound: letters, at
 * least two parts' worth of them, seen at least twice. Of the issue's eight junk
 * readings none is in German's, and `garagentor` is. Held apart from the word list
 * because these spellings must stay out of completions and corrections.
 *
 * Written next to the language's list, renamed into place, so presence means
 * complete. A failure stands until the process restarts, and the keyboard goes on
 * without joins.
 */
object CompoundLexiconDownloads {

    private const val USER_AGENT = "WMKeyboard compound list downloader"

    /** Below this a spelling is as likely a typo as a word. Sorted, so reading stops at it. */
    private const val MIN_COUNT = 2

    /** Two parts of [GlideLanguageProfiles.Profile.joinMinPart]; German's is four. */
    private const val MIN_LETTERS = 8
    private const val MAX_LETTERS = 32

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = HashSet<String>()
    private val failed = HashSet<String>()

    private val _completions = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Emits a language's id once its lexicon has landed. */
    val completions: SharedFlow<String> = _completions.asSharedFlow()

    /** Whether [languageId] joins parts at all, and so has a use for a lexicon. */
    fun supports(languageId: String): Boolean = GlideLanguageProfiles.forLanguage(languageId).joinCost.isFinite()

    fun file(filesDir: File, languageId: String): File = DictionaryStore.compoundsFile(filesDir, languageId)

    /** Fetches the lexicon of each of [languages] that uses one and does not have it. */
    fun ensure(filesDir: File, languages: List<String>) {
        for (id in languages) {
            if (!supports(id) || file(filesDir, id).isFile) continue
            val entry = DictionaryCatalog.entries.firstOrNull {
                it.languageId == id && it.source == WordlistSource.FREQUENCY && it.suffix == "full"
            } ?: continue
            synchronized(this) {
                if (id in failed || !inFlight.add(id)) return@synchronized
                scope.launch {
                    val ok = runCatching { fetch(filesDir, id, entry.url) }.isSuccess
                    synchronized(this@CompoundLexiconDownloads) {
                        inFlight -= id
                        if (!ok) failed += id
                    }
                }
            }
        }
    }

    /** Builds the lexicon from [source], a copy of the language's frequency list brought in by hand. */
    fun install(filesDir: File, languageId: String, source: File) {
        if (!supports(languageId)) return
        val raw = source.inputStream().buffered()
        raw.mark(2)
        val gzip = raw.read() == 0x1f && raw.read() == 0x8b
        raw.reset()
        (if (gzip) GZIPInputStream(raw, 32 * 1024) else raw).use { write(filesDir, languageId, readCompounds(it)) }
    }

    private fun fetch(filesDir: File, languageId: String, url: String) {
        val connection = URL(url).openConnection() as HttpURLConnection
        val netCall = NetLog.call(NetSource.DOWNLOAD_WORDLIST, "GET", url, route = NetLog.pathOf(url))
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept-Encoding", "identity")
            netCall.status = connection.responseCode
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode} for $url")
            }
            val trie = GZIPInputStream(netCall.countIn(connection.inputStream), 32 * 1024).use(::readCompounds)
            write(filesDir, languageId, trie)
        } catch (t: Throwable) {
            netCall.fail(t)
            throw t
        } finally {
            netCall.end()
            connection.disconnect()
        }
    }

    private fun write(filesDir: File, languageId: String, trie: PackedTrie) {
        val target = file(filesDir, languageId)
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        try {
            part.outputStream().use { PackedTrieCodec.write(trie, it) }
            if (!part.renameTo(target)) throw IOException("could not move ${part.name} into place")
        } finally {
            part.delete()
        }
        _completions.tryEmit(languageId)
    }

    /**
     * What of an inflated `word count` list, most frequent first, could be a
     * compound, in lower case: the joined reading it is asked about is folded too.
     */
    fun readCompounds(input: InputStream): PackedTrie {
        var words = arrayOfNulls<String>(1 shl 18)
        var counts = IntArray(words.size)
        var size = 0
        input.bufferedReader().useLines { lines ->
            for (line in lines) {
                val separator = line.lastIndexOf(' ')
                if (separator <= 0) continue
                val count = line.substring(separator + 1).trim().toIntOrNull() ?: continue
                if (count < MIN_COUNT) break
                if (separator !in MIN_LETTERS..MAX_LETTERS) continue
                val word = line.substring(0, separator)
                if (!word.all(Char::isLetter)) continue
                if (size == words.size) {
                    words = words.copyOf(size * 2)
                    counts = counts.copyOf(size * 2)
                }
                words[size] = word.lowercase(Locale.ROOT)
                counts[size] = count
                size++
            }
        }
        return PackedTrie.of(words, counts, size)
    }
}
