package com.wasimaster.wmkeyboard.core.grammar

import com.wasimaster.wmkeyboard.core.download.FileDownloader
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * GiellaLT's grammar checkers (Sámi languages and Faroese): the
 * [DivvunGrammarCatalog] bundle a language's row downloads into
 * `grammar/giella/<langId>/grammar.drb`, and the checks the grammar tool
 * runs on it through divvun-runtime ([DivvunGrammarNative]).
 *
 * A bundle is hundreds of megabytes (North Sámi's is 1.5 GB), so it is fetched
 * only when the user asks, from the grammar tool's settings, and checked
 * against its pinned SHA-256 before it takes the place of the old one. It is
 * read where it lies, mapped, and opened the first time a check needs it:
 * that first check waits for the pipeline to load, the rest do not. One
 * bundle is open at a time.
 */
object DivvunGrammar {

    sealed interface Status {
        data object NotInstalled : Status

        data class Downloading(val bytes: Long, val totalBytes: Long) : Status

        data class Installed(val sizeBytes: Long, val version: String?) : Status

        data object Failed : Status
    }

    private const val BUNDLE = "grammar.drb"
    private const val VERSION = "grammar.version"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()

    private val _states = MutableStateFlow<Map<String, Status>>(emptyMap())

    /** Language id -> status, for every language asked about so far. */
    val states: StateFlow<Map<String, Status>> = _states.asStateFlow()

    /** Bumped whenever a bundle is installed or removed, for the keyboard to read [installed] again. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun languageDir(filesDir: File, langId: String) = File(filesDir, "grammar/giella/$langId")

    fun bundleFile(filesDir: File, langId: String) = File(languageDir(filesDir, langId), BUNDLE)

    /** The languages with a bundle on the phone. One directory listing. */
    fun installed(filesDir: File): Set<String> =
        File(filesDir, "grammar/giella").listFiles().orEmpty()
            .filter { File(it, BUNDLE).isFile }
            .mapTo(HashSet()) { it.name }

    /** Whether the library is on the device, without loading it. */
    val moduleReady: Boolean get() = FeatureModules.divvunGrammar.installed

    fun requestModule() = FeatureModules.divvunGrammar.requestInstall()

    private fun set(langId: String, status: Status) {
        _states.update { it + (langId to status) }
    }

    private fun installedStatus(filesDir: File, langId: String): Status.Installed? {
        val file = bundleFile(filesDir, langId).takeIf { it.isFile } ?: return null
        val version = runCatching { File(languageDir(filesDir, langId), VERSION).readText().trim() }
            .getOrNull()?.ifEmpty { null }
        return Status.Installed(file.length(), version)
    }

    /** What is on disk for [langId], unless a download of it is under way. */
    fun refresh(filesDir: File, langId: String) {
        _states.update { current ->
            val live = current[langId]
            val status = when {
                live is Status.Downloading -> live
                else -> installedStatus(filesDir, langId) ?: if (live == Status.Failed) live else Status.NotInstalled
            }
            current + (langId to status)
        }
    }

    /** Fetches [langId]'s catalogued bundle, and the library with it. */
    fun start(filesDir: File, langId: String) {
        val entry = DivvunGrammarCatalog.forLanguage(langId) ?: return
        if (!moduleReady) requestModule()
        synchronized(jobs) {
            if (jobs[langId]?.isActive == true) return
            set(langId, Status.Downloading(0L, entry.bytes))
            jobs[langId] = scope.launch {
                val part = File(languageDir(filesDir, langId), "$BUNDLE.part")
                try {
                    FileDownloader.fetch(entry.url, part, entry.bytes, NetSource.DOWNLOAD_WORDLIST) {
                        set(langId, Status.Downloading(it, entry.bytes))
                    }
                    val got = FileDownloader.sha256(part)
                    if (got != entry.sha256) throw IOException("SHA-256 mismatch for ${entry.url}: $got")
                    // The open bundle, if it is this language's, maps the old file.
                    closeIf(langId)
                    val target = bundleFile(filesDir, langId)
                    if (!part.renameTo(target)) throw IOException("could not move ${part.path}")
                    File(languageDir(filesDir, langId), VERSION).writeText(entry.version)
                    set(langId, installedStatus(filesDir, langId) ?: Status.Failed)
                    _revision.update { it + 1 }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A hash that did not match is no file to resume.
                    FileDownloader.discard(part)
                    set(langId, Status.Failed)
                } finally {
                    if (_states.value[langId] is Status.Downloading) {
                        set(langId, installedStatus(filesDir, langId) ?: Status.NotInstalled)
                    }
                }
            }
        }
    }

    fun cancel(langId: String) {
        synchronized(jobs) { jobs[langId]?.cancel() }
    }

    fun delete(filesDir: File, langId: String) {
        cancel(langId)
        scope.launch {
            closeIf(langId)
            languageDir(filesDir, langId).deleteRecursively()
            set(langId, Status.NotInstalled)
            _revision.update { it + 1 }
        }
    }

    // --- Checking ---------------------------------------------------------

    /** Loading a bundle and checking with it, one thing at a time. */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "divvun-grammar") }
    private val dispatcher = worker.asCoroutineDispatcher()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var handle = 0L
    @Volatile private var openLanguage: String? = null

    /** Whether a bundle is open; for the Resources screen. Any thread. */
    val isLoaded: Boolean get() = handle != 0L

    /** The language whose bundle is open, or null; for the Resources screen. Any thread. */
    val loadedLanguage: String? get() = openLanguage

    /** Whether [langId] has a bundle on the phone and the library to read it. Loads the library. */
    fun ready(filesDir: File, langId: String): Boolean =
        bundleFile(filesDir, langId).isFile && moduleReady && DivvunGrammarNative.available

    /**
     * [text]'s grammar errors in [langId], as the grammar tool's lints, with
     * messages in the phone's language where the bundle has it, else English.
     * Null when this cannot check it: no bundle, no library, or a bundle that
     * does not open. The first check of a language waits for its pipeline.
     */
    suspend fun check(filesDir: File, langId: String, text: String): List<GrammarLint>? {
        if (text.isBlank()) return emptyList()
        if (!ready(filesDir, langId)) return null
        return withContext(dispatcher) {
            val h = openLocked(filesDir, langId)
            if (h == 0L) return@withContext null
            val locales = listOf(Locale.getDefault().language, "en").distinct().joinToString(",")
            val answer = runCatching { DivvunGrammarNative.nativeCheck(h, text, locales) }.getOrNull()
                ?: return@withContext null
            if (!answer.startsWith("ok:")) return@withContext null
            runCatching { lints(answer.removePrefix("ok:"), text) }.getOrNull()
        }
    }

    /** Closes the open bundle. Posted behind any check under way. */
    fun release() {
        if (handle == 0L) return
        worker.execute { closeLocked() }
    }

    private fun closeIf(langId: String) {
        if (openLanguage == langId) runCatching { worker.submit { closeLocked() }.get() }
    }

    private fun openLocked(filesDir: File, langId: String): Long {
        if (handle != 0L && openLanguage == langId) return handle
        closeLocked()
        val h = runCatching { DivvunGrammarNative.nativeOpen(bundleFile(filesDir, langId).path) }.getOrDefault(0L)
        if (h != 0L) {
            handle = h
            openLanguage = langId
        }
        return h
    }

    private fun closeLocked() {
        val h = handle
        handle = 0L
        openLanguage = null
        if (h != 0L) runCatching { DivvunGrammarNative.nativeClose(h) }
    }

    @Serializable
    private data class Output(val errors: List<Error> = emptyList())

    @Serializable
    private data class Error(
        val form: String = "",
        val start: Int,
        val end: Int,
        @SerialName("error_id") val errorId: String = "",
        val title: String = "",
        val description: String = "",
        val suggestions: List<String> = emptyList(),
    )

    /**
     * The checker's JSON as lints. Each suggestion replaces the whole span, the
     * way GiellaLT's checkers offer them; a suggestion equal to the span is
     * dropped. Spans outside [text] are dropped too.
     */
    internal fun lints(raw: String, text: String): List<GrammarLint> =
        json.decodeFromString<Output>(raw).errors
            .filter { it.start in 0..it.end && it.end <= text.length }
            .map { e ->
                val original = text.substring(e.start, e.end)
                GrammarLint(
                    start = e.start,
                    end = e.end,
                    original = original,
                    kind = e.errorId,
                    message = e.description,
                    suggestions = e.suggestions.distinct().filter { it != original }.map { GrammarFix("replace", it) },
                    title = e.title,
                    rule = e.errorId,
                )
            }
}
