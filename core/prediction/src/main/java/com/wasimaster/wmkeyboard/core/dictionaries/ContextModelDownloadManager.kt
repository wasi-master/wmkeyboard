package com.wasimaster.wmkeyboard.core.dictionaries

import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.prediction.ContextModel
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
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
 * Fetches [ContextModelCatalog] models into `dict/<langId>/context.wmlm`,
 * next to the language's downloaded word list and n-gram pack.
 *
 * The same shape as [NgramPackDownloadManager]: [ensure] is the silent
 * automatic pass, one language at a time, a failure remembered for the
 * process; [start] is the user asking; [delete] leaves a marker so the
 * automatic pass does not fetch the model straight back; [completions] tells
 * the IME a model landed. The file is written to `.part` and renamed, so a
 * model is either whole or absent.
 */
object ContextModelDownloadManager {

    sealed interface DownloadStatus {
        data object NotDownloaded : DownloadStatus
        data class Downloading(val fraction: Float) : DownloadStatus
        /** On the device; [imported] when the user brought their own file rather than a catalogued one. */
        data class Downloaded(
            val sizeBytes: Long,
            val variant: ContextModelVariant = ContextModelVariant.STANDARD,
            val imported: Boolean = false,
        ) : DownloadStatus
        data object Failed : DownloadStatus
    }

    private const val DECLINED_NAME = "context.declined"

    /** Holds the name of the [ContextModelVariant] the language has, or should get. */
    private const val VARIANT_NAME = "context.variant"

    /**
     * Marks a model the user imported from a file of their own (FUTO U12):
     * no catalogue entry's size or digest describes it, so the automatic pass
     * must neither judge it stale nor fetch over it.
     */
    private const val IMPORTED_NAME = "context.imported"
    private const val USER_AGENT = "WMKeyboard context model downloader"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private val jobs = HashMap<String, Job>()
    private val givenUp = HashSet<String>()

    private val _completions = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Emits a language id once its model is on disk. */
    val completions: SharedFlow<String> = _completions.asSharedFlow()

    private val _states = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())
    val states: StateFlow<Map<String, DownloadStatus>> = _states.asStateFlow()

    private fun set(langId: String, status: DownloadStatus) {
        _states.update { it + (langId to status) }
    }

    fun refresh(filesDir: File) {
        _states.update { current ->
            (ContextModelCatalog.entries.map { it.languageId } + importedLanguages(filesDir)).distinct().associateWith { langId ->
                val live = current[langId]
                when {
                    live is DownloadStatus.Downloading -> live
                    isDownloaded(filesDir, langId) -> DownloadStatus.Downloaded(
                        modelFile(filesDir, langId).length(),
                        variantOf(filesDir, langId),
                        imported = isImported(filesDir, langId),
                    )
                    live == DownloadStatus.Failed -> live
                    else -> DownloadStatus.NotDownloaded
                }
            }
        }
    }

    fun modelFile(filesDir: File, langId: String): File = File(langDir(filesDir, langId), ContextModel.FILE_NAME)

    private fun langDir(filesDir: File, langId: String) = File(File(filesDir, "dict"), langId)

    fun isDownloaded(filesDir: File, langId: String): Boolean {
        val file = modelFile(filesDir, langId)
        if (!file.isFile) return false
        if (isImported(filesDir, langId)) return true
        val entry = ContextModelCatalog.entry(langId, variantOf(filesDir, langId))
            ?: ContextModelCatalog.forLanguage(langId)
        // A file from an older catalog or a different build is re-fetched when
        // its exact size no longer matches; the completed download checks the
        // digest too.
        return entry == null || file.length() == entry.bytes
    }

    /** Whether [langId]'s model is one the user imported rather than a catalogued one. */
    fun isImported(filesDir: File, langId: String): Boolean =
        File(langDir(filesDir, langId), IMPORTED_NAME).exists() && modelFile(filesDir, langId).isFile

    /** Languages holding an imported model, catalogued or not. */
    private fun importedLanguages(filesDir: File): List<String> =
        File(filesDir, "dict").listFiles().orEmpty()
            .filter { it.isDirectory && File(it, IMPORTED_NAME).exists() && File(it, ContextModel.FILE_NAME).isFile }
            .map { it.name }

    /** The size the language has on disk, or is set to fetch: the marker, else standard. */
    fun variantOf(filesDir: File, langId: String): ContextModelVariant {
        val name = runCatching { File(langDir(filesDir, langId), VARIANT_NAME).readText().trim() }.getOrNull()
        return ContextModelVariant.entries.firstOrNull { it.name == name } ?: ContextModelVariant.STANDARD
    }

    private fun rememberVariant(filesDir: File, langId: String, variant: ContextModelVariant) {
        runCatching {
            val dir = langDir(filesDir, langId)
            dir.mkdirs()
            File(dir, VARIANT_NAME).writeText(variant.name)
        }
    }

    /** Queues every catalogued language in [langIds] the device does not have. Silent. */
    fun ensure(filesDir: File, langIds: Collection<String>) {
        val wanted = langIds.distinct()
        scope.launch {
            for (langId in wanted) {
                val entry = ContextModelCatalog.entry(langId, variantOf(filesDir, langId))
                    ?: ContextModelCatalog.forLanguage(langId)
                    ?: continue
                synchronized(jobs) {
                    if (langId in givenUp || jobs[langId]?.isActive == true) return@synchronized
                    if (isDownloaded(filesDir, langId)) return@synchronized
                    if (File(langDir(filesDir, langId), DECLINED_NAME).exists()) return@synchronized
                    set(langId, DownloadStatus.Downloading(0f))
                    jobs[langId] = scope.launch {
                        try {
                            gate.withLock { download(filesDir, entry) }
                        } finally {
                            if (_states.value[langId] is DownloadStatus.Downloading) {
                                set(langId, DownloadStatus.NotDownloaded)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The user asked for [langId]'s model: retries a failed transfer and
     * undoes a Delete. With [variant], that size is fetched; a model of
     * another size already on disk is replaced.
     */
    fun start(filesDir: File, langId: String, variant: ContextModelVariant? = null) {
        if (variant != null && (variant != variantOf(filesDir, langId) || isImported(filesDir, langId))) {
            cancel(langId)
            modelFile(filesDir, langId).delete()
            File(langDir(filesDir, langId), IMPORTED_NAME).delete()
            set(langId, DownloadStatus.NotDownloaded)
        }
        if (variant != null) rememberVariant(filesDir, langId, variant)
        synchronized(jobs) { givenUp.remove(langId) }
        File(langDir(filesDir, langId), DECLINED_NAME).delete()
        ensure(filesDir, listOf(langId))
    }

    fun cancel(langId: String) {
        synchronized(jobs) { jobs[langId]?.cancel() }
    }

    /** Removes [langId]'s model and remembers the user did not want it. */
    fun delete(filesDir: File, langId: String) {
        cancel(langId)
        set(langId, DownloadStatus.NotDownloaded)
        scope.launch {
            val dir = langDir(filesDir, langId)
            modelFile(filesDir, langId).delete()
            File(dir, "${ContextModel.FILE_NAME}.part").delete()
            File(dir, IMPORTED_NAME).delete()
            runCatching {
                dir.mkdirs()
                File(dir, DECLINED_NAME).createNewFile()
            }
        }
    }

    /**
     * Installs a model file the user fetched some other way (an import):
     * copied into place when the reader accepts it. Runs on the caller's
     * thread and throws when it is not a model this build reads. [imported]
     * marks a file of the user's own, one no catalogue entry describes, so
     * the automatic pass leaves it in place (see [IMPORTED_NAME]).
     */
    fun install(filesDir: File, langId: String, source: File, imported: Boolean = false) {
        synchronized(jobs) { jobs[langId]?.cancel() }
        val target = modelFile(filesDir, langId)
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        source.inputStream().use { input -> part.outputStream().use { input.copyTo(it) } }
        val probe = ContextModel.open(part) ?: run {
            part.delete()
            throw IOException("not a context model this keyboard reads: ${source.name}")
        }
        probe.close()
        if (!part.renameTo(target)) throw IOException("rename failed for $target")
        File(langDir(filesDir, langId), DECLINED_NAME).delete()
        val marker = File(langDir(filesDir, langId), IMPORTED_NAME)
        if (imported) marker.createNewFile() else marker.delete()
        synchronized(jobs) { givenUp.remove(langId) }
        set(langId, DownloadStatus.Downloaded(target.length(), variantOf(filesDir, langId), imported))
        _completions.tryEmit(langId)
    }

    /**
     * [install] for a catalogued file the user fetched elsewhere (#1000), the
     * offline build's only way to a model: [entry]'s size is remembered with
     * it, as its download would remember it.
     */
    fun install(filesDir: File, entry: ContextModelEntry, source: File) {
        if (!ContextModelIntegrity.matches(source, entry)) {
            throw IOException("size or SHA-256 mismatch for ${source.name}")
        }
        rememberVariant(filesDir, entry.languageId, entry.variant)
        install(filesDir, entry.languageId, source)
    }

    private fun download(filesDir: File, entry: ContextModelEntry) {
        val langId = entry.languageId
        if (isDownloaded(filesDir, langId)) {
            set(langId, DownloadStatus.Downloaded(modelFile(filesDir, langId).length(), variantOf(filesDir, langId)))
            return
        }
        val ok = runCatching { fetch(filesDir, entry) }.isSuccess
        if (ok) {
            rememberVariant(filesDir, langId, entry.variant)
            set(langId, DownloadStatus.Downloaded(modelFile(filesDir, langId).length(), entry.variant))
            _completions.tryEmit(langId)
        } else {
            set(langId, DownloadStatus.Failed)
            synchronized(jobs) { givenUp.add(langId) }
        }
    }

    private fun fetch(filesDir: File, entry: ContextModelEntry) {
        val target = modelFile(filesDir, entry.languageId)
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        val url = entry.url()
        val connection = URL(url).openConnection() as HttpURLConnection
        val netCall = NetLog.call(NetSource.DOWNLOAD_NGRAM, "GET", url, route = NetLog.pathOf(url))
        try {
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            netCall.status = connection.responseCode
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode} for $url")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: entry.bytes
            var read = 0L
            netCall.countIn(connection.inputStream).use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        read += n
                        set(entry.languageId, DownloadStatus.Downloading((read.toFloat() / total).coerceIn(0f, 1f)))
                    }
                }
            }
            if (part.length() != entry.bytes) {
                throw IOException("size mismatch for $url: ${part.length()} != ${entry.bytes}")
            }
            if (!ContextModelIntegrity.matches(part, entry)) {
                throw IOException("SHA-256 mismatch for $url")
            }
            val probe = ContextModel.open(part) ?: throw IOException("not a model this keyboard reads: $url")
            probe.close()
            if (!part.renameTo(target)) throw IOException("rename failed for $target")
            File(langDir(filesDir, entry.languageId), IMPORTED_NAME).delete()
        } catch (t: Throwable) {
            part.delete()
            netCall.fail(t)
            throw t
        } finally {
            netCall.end()
            connection.disconnect()
        }
    }
}
