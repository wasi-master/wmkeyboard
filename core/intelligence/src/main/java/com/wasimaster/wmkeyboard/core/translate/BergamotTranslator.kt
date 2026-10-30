package com.wasimaster.wmkeyboard.core.translate

import android.content.Context
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.modules.DownloadedModuleGate
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.FeatureModules.awaitInstalled
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import com.wasimaster.wmkeyboard.core.download.FileDownloader
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.util.runCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Mozilla's Bergamot translator behind the translate tool: the free on-device
 * engine, which needs no Google services. It runs the models Firefox
 * Translations uses, downloaded one language direction at a time from
 * Mozilla's own servers and checked against [BergamotCatalog]'s pins.
 *
 * The same shape as [OnDeviceTranslator], and one object per process for the
 * same reason: the keyboard and the settings app share it, so a download the
 * list started shows in the panel, and a model the panel is translating with
 * is not deleted out from under it.
 *
 * Model keys in [models] carry [KEY_PREFIX] (`bergamot:fr-en`), so they sit
 * beside ML Kit's language codes in one map without meaning the same thing.
 * [ENGINE_KEY] stands for the engine's own library on a build that downloads
 * it, so the panel can offer it like any other missing piece.
 */
object BergamotTranslator {

    const val KEY_PREFIX = "bergamot:"

    /** The library itself, where this build fetches it on demand. */
    const val ENGINE_KEY = "${KEY_PREFIX}engine"

    /** Longest text translated in one go, the same cut the other engines make. */
    private const val MAX_CHARS = 2500

    /** Sentences the engine remembers, so retyping a line does not translate it again. */
    private const val CACHE_SENTENCES = 256

    /** Models kept in memory at once: a route through English needs two. */
    private const val LOADED_MAX = 2

    /** Written last into a direction's folder, once every file in it is verified. */
    private const val COMPLETE = ".complete"

    /**
     * Whether this build and phone can run the engine at all: Android 9 or
     * later. Every build has the library somewhere (the APK, a Play split, a
     * pin), and the offline build imports it and the models ([importFiles]), so
     * this loads nothing: it is read at startup, and the library is megabytes.
     */
    val available: Boolean get() = BergamotNative.supported

    fun keyOf(direction: BergamotCatalog.Direction): String = KEY_PREFIX + direction.key

    fun isBergamotKey(key: String): Boolean = key.startsWith(KEY_PREFIX)

    /** The direction a model key names, or null (for [ENGINE_KEY] too). */
    fun directionOf(key: String): BergamotCatalog.Direction? =
        if (isBergamotKey(key)) BergamotLanguages.direction(key.removePrefix(KEY_PREFIX)) else null

    /** What fetching [key] costs: its files, or the library's bytes still to come. */
    fun downloadBytes(key: String): Long = when (key) {
        ENGINE_KEY -> (FeatureModules.bergamot as? DownloadedModuleGate)?.missingBytes ?: 0L
        else -> directionOf(key)?.downloadBytes ?: 0L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _models = MutableStateFlow<Map<String, OfflineModelState>>(emptyMap())

    /**
     * Every direction whose state is known, by model key, plus [ENGINE_KEY]
     * while the library is a download. Empty until the first [refresh].
     */
    val models: StateFlow<Map<String, OfflineModelState>> = _models.asStateFlow()

    private val downloads = mutableMapOf<String, Job>()

    /** Guards the service and the loaded models: translation, release and delete all hold it. */
    private val engineLock = Mutex()

    private var service = 0L

    /** Loaded models by direction key, least recently used first. */
    private val loaded = LinkedHashMap<String, Long>(4, 0.75f, true)

    @Volatile
    private var watchingEngine = false

    /** Whether [refresh] has looked at the disk yet; [ENGINE_KEY] alone does not count. */
    @Volatile
    private var scanned = false

    private fun root(context: Context): File = File(context.applicationContext.noBackupFilesDir, "bergamot")

    private fun dirOf(context: Context, direction: BergamotCatalog.Direction): File =
        File(root(context), "${direction.key}/${direction.version}")

    private fun installed(context: Context, direction: BergamotCatalog.Direction): Boolean {
        val dir = dirOf(context, direction)
        return File(dir, COMPLETE).isFile && direction.files.all { File(dir, it.name).length() == it.rawSize }
    }

    /**
     * Re-reads which directions are on the phone, and drops what a past
     * catalogue left behind. Cheap: it looks at file sizes, and never touches
     * a direction that is mid-download. Returns the model keys that are here.
     */
    suspend fun refresh(context: Context): Set<String> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        watchEngine()
        runCatching { pruneStale(app) }
        if (!InternetPermission.granted) runCatching { finishImports(app) }
        val here = BergamotLanguages.directions.filter { installed(app, it) }.mapTo(HashSet()) { keyOf(it) }
        _models.update { current ->
            val next = HashMap<String, OfflineModelState>()
            for (direction in BergamotLanguages.directions) {
                val key = keyOf(direction)
                val known = current[key]
                next[key] = when {
                    known is OfflineModelState.Downloading -> known
                    key in here -> OfflineModelState.Downloaded
                    // A failure stays on show until the next attempt replaces it.
                    known is OfflineModelState.Missing -> known
                    else -> OfflineModelState.Missing()
                }
            }
            engineState()?.let { next[ENGINE_KEY] = it }
            next
        }
        scanned = true
        here
    }

    /** A folder for a direction the catalogue no longer has, or for an older version of one. */
    private fun pruneStale(context: Context) {
        val dirs = root(context).listFiles().orEmpty()
        for (pairDir in dirs) {
            val direction = BergamotLanguages.direction(pairDir.name)
            if (direction == null) {
                pairDir.deleteRecursively()
                continue
            }
            for (versionDir in pairDir.listFiles().orEmpty()) {
                if (versionDir.name != direction.version) versionDir.deleteRecursively()
            }
        }
    }

    private fun engineState(): OfflineModelState? {
        val gate = FeatureModules.bergamot
        if (!gate.onDemand) return null
        return when (val state = gate.state.value) {
            ModuleState.Installed -> OfflineModelState.Downloaded
            is ModuleState.Installing -> OfflineModelState.Downloading(state.bytes, state.totalBytes)
            is ModuleState.Missing -> OfflineModelState.Missing(failed = state.failed)
        }
    }

    /** Mirrors the library's own download into [models], so the panel's offer shows it like a model. */
    private fun watchEngine() {
        if (watchingEngine || !FeatureModules.bergamot.onDemand) return
        watchingEngine = true
        scope.launch {
            FeatureModules.bergamot.state.collect {
                engineState()?.let { state -> _models.update { models -> models + (ENGINE_KEY to state) } }
            }
        }
    }

    /**
     * Starts fetching [key]'s files (or the library, for [ENGINE_KEY]).
     * Returns at once; follow it in [models]. A second call for a direction on
     * its way does nothing. The download belongs to this object, not to the
     * screen that asked.
     */
    fun download(context: Context, key: String) {
        val app = context.applicationContext
        if (key == ENGINE_KEY) {
            watchEngine()
            FeatureModules.bergamot.requestInstall()
            return
        }
        val direction = directionOf(key) ?: return
        synchronized(downloads) {
            if (downloads[key]?.isActive == true) return
            _models.update { it + (key to OfflineModelState.Downloading(0, direction.downloadBytes)) }
            downloads[key] = scope.launch {
                val outcome = runCancellable { fetch(app, direction, key) }
                outcome.exceptionOrNull()?.let { DebugLog.w(TAG, "fetching ${direction.key} failed: $it") }
                synchronized(downloads) { downloads.remove(key) }
                _models.update {
                    it + (key to if (outcome.isSuccess) OfflineModelState.Downloaded else OfflineModelState.Missing(failed = true))
                }
            }
        }
    }

    private suspend fun fetch(context: Context, direction: BergamotCatalog.Direction, key: String) {
        InternetPermission.check()
        // The engine unpacks the files, so it is fetched first; its bytes are
        // its own row ([ENGINE_KEY]). F-Droid without the download opt-in stops here.
        watchEngine()
        if (!FeatureModules.bergamot.awaitInstalled()) throw IOException("The Bergamot library could not be fetched")
        if (!BergamotNative.available) throw IOException("The Bergamot library does not load on this phone")
        val dir = dirOf(context, direction)
        dir.mkdirs()
        File(dir, COMPLETE).delete()
        val total = direction.downloadBytes
        var done = 0L
        for (file in direction.files) {
            val target = File(dir, file.name)
            // A file left by an attempt that failed on a later one.
            if (target.length() == file.rawSize && FileDownloader.sha256(target) == file.rawSha256) {
                done += file.size
                continue
            }
            target.delete()
            val packed = File(dir, "${file.name}.zst")
            transfer(file.url, file.size, packed) { bytes -> progress(key, done + bytes, total) }
            if (FileDownloader.sha256(packed) != file.sha256) {
                packed.delete()
                throw IOException("${file.name} did not match its pinned checksum")
            }
            coroutineContext.ensureActive()
            unpack(dir, file, packed)
            done += file.size
            progress(key, done, total)
        }
        File(dir, COMPLETE).writeText(direction.version)
    }

    /** [packed], its checksum already checked, into the file the engine reads. Removes [packed]. */
    private fun unpack(dir: File, file: BergamotCatalog.File, packed: File) {
        val unpacked = File(dir, "${file.name}.tmp")
        val error = BergamotNative.nativeUnzstd(packed.path, unpacked.path)
        packed.delete()
        if (error != null || unpacked.length() != file.rawSize || FileDownloader.sha256(unpacked) != file.rawSha256) {
            unpacked.delete()
            throw IOException("${file.name} did not unpack: ${error ?: "checksum"}")
        }
        if (!unpacked.renameTo(File(dir, file.name))) throw IOException("could not keep ${file.name}")
    }

    /** What one imported file came to. */
    sealed interface Imported {
        val fileName: String

        /** Kept for [directions]; each of [ready] now has everything it needs. */
        data class Placed(
            override val fileName: String,
            val directions: List<BergamotCatalog.Direction>,
            val ready: List<BergamotCatalog.Direction>,
        ) : Imported

        /** A file several directions share, and none of them started: import it with one's own files. */
        data class Shared(override val fileName: String) : Imported
    }

    /** [importFiles]'s answer: an [Imported] per catalogue file, and the files that were none. */
    class ImportOutcome(val results: List<Imported>, val unclaimed: List<File>)

    private val publishedSizes: Set<Long> by lazy {
        BergamotLanguages.directions.flatMapTo(HashSet()) { d -> d.files.flatMap { listOf(it.size, it.rawSize) } }
    }

    /** Whether some direction has a file exactly [size] bytes long, packed or unpacked: worth hashing. */
    fun sizeMatches(size: Long): Boolean = size > 0 && size in publishedSizes

    /** Whether a download is writing to the folders [importFiles] would. */
    val isBusy: Boolean get() = synchronized(downloads) { downloads.isNotEmpty() }

    /**
     * Model files fetched on another device (#1035). Mozilla serves them under
     * names that say nothing, so each is told by its size and then its SHA-256,
     * as a download is checked, and can come zstd-packed as published or already
     * unpacked. A packed file waits beside its direction until the library that
     * unpacks it is here ([finishImports]). A file more than one direction holds
     * goes only to the directions already started, as [EtImport] does it.
     */
    suspend fun importFiles(context: Context, files: List<File>): ImportOutcome = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val matched = LinkedHashMap<File, List<Pair<BergamotCatalog.Direction, BergamotCatalog.File>>>()
        val hashes = HashMap<File, String>()
        val unclaimed = ArrayList<File>()
        for (file in files) {
            val size = file.length()
            val bySize = BergamotLanguages.directions.flatMap { d ->
                d.files.filter { it.size == size || it.rawSize == size }.map { d to it }
            }
            val sha = if (bySize.isEmpty()) "" else FileDownloader.sha256(file)
            val hits = bySize.filter { (_, entry) -> entry.sha256 == sha || entry.rawSha256 == sha }
            if (hits.isEmpty()) {
                unclaimed += file
            } else {
                matched[file] = hits
                hashes[file] = sha
            }
        }
        val started = HashSet<String>()
        matched.values.filter { it.size == 1 }.forEach { started += it.single().first.key }
        BergamotLanguages.directions.filterTo(ArrayList()) { !dirOf(app, it).list().isNullOrEmpty() }
            .forEach { started += it.key }
        val placed = ArrayList<Pair<String, List<BergamotCatalog.Direction>>>()
        val results = ArrayList<Imported>()
        for ((file, hits) in matched) {
            val targets = if (hits.size == 1) hits else hits.filter { it.first.key in started }
            if (targets.isEmpty()) {
                results += Imported.Shared(file.name)
                continue
            }
            for ((direction, entry) in targets) {
                val dir = dirOf(app, direction).apply { mkdirs() }
                val raw = entry.rawSha256 == hashes[file]
                val target = File(dir, if (raw) entry.name else "${entry.name}.zst")
                val part = File(dir, "${target.name}.import")
                try {
                    file.copyTo(part, overwrite = true)
                    target.delete()
                    if (!part.renameTo(target)) throw IOException("could not keep ${entry.name}")
                } finally {
                    part.delete()
                }
            }
            placed += file.name to targets.map { it.first }
        }
        finishImports(app)
        refresh(app)
        placed.forEach { (name, directions) ->
            results += Imported.Placed(name, directions, directions.filter { installed(app, it) })
        }
        ImportOutcome(results, unclaimed)
    }

    /**
     * Unpacks what imports left waiting, once the library is here, and marks each
     * direction with all its files done. Also runs when the library itself
     * arrives. A packed file that fails its checksum is dropped: the import checked
     * it, so it can only be a download cut short.
     */
    private fun finishImports(context: Context) {
        val active = synchronized(downloads) { downloads.keys.toSet() }
        for (direction in BergamotLanguages.directions) {
            if (keyOf(direction) in active) continue
            val dir = dirOf(context, direction)
            if (!dir.isDirectory || File(dir, COMPLETE).isFile) continue
            for (file in direction.files) {
                val packed = File(dir, "${file.name}.zst")
                if (!packed.isFile || !BergamotNative.available) continue
                if (FileDownloader.sha256(packed) == file.sha256) unpack(dir, file, packed) else packed.delete()
            }
            if (direction.files.all { File(dir, it.name).length() == it.rawSize }) {
                File(dir, COMPLETE).writeText(direction.version)
            }
        }
    }

    private fun progress(key: String, bytes: Long, total: Long) {
        _models.update { current ->
            if (current[key] !is OfflineModelState.Downloading) current
            else current + (key to OfflineModelState.Downloading(bytes, total))
        }
    }

    /** Downloads [url] into [part] through [FileDownloader]: parallel, and resumed piece by piece. */
    private suspend fun transfer(url: String, size: Long, part: File, onBytes: (Long) -> Unit) {
        FileDownloader.fetch(url, part, size, NetSource.DOWNLOAD_TRANSLATE, onBytes = onBytes)
    }

    /** Calls off a download in progress and removes what it had fetched. */
    fun cancelDownload(context: Context, key: String) {
        val app = context.applicationContext
        val direction = directionOf(key) ?: return
        val job = synchronized(downloads) { downloads.remove(key) } ?: return
        job.cancel()
        _models.update { it + (key to OfflineModelState.Missing()) }
        scope.launch {
            job.join()
            if (!installed(app, direction)) dirOf(app, direction).deleteRecursively()
        }
    }

    /** Removes [key]'s files from the phone, unloading the model first. */
    suspend fun delete(context: Context, key: String) {
        val direction = directionOf(key) ?: return
        engineLock.withLock {
            loaded.remove(direction.key)?.let { BergamotNative.nativeFreeModel(it) }
            withContext(Dispatchers.IO) { dirOf(context, direction).parentFile?.deleteRecursively() }
        }
        _models.update { it + (key to OfflineModelState.Missing()) }
    }

    /**
     * Translates [text] into [target] (a picker code such as `bn` or `zh-TW`).
     *
     * [sourceOverride] is the user's own choice of source, blank for detect.
     * [identified] is what a real language identifier said, when the phone has
     * one (ML Kit's); without it the source is guessed from the script and
     * common words ([ScriptLanguageGuess]). [hints] are the languages the user
     * types in, the active one first.
     */
    suspend fun translate(
        context: Context,
        text: String,
        target: String,
        sourceOverride: String = "",
        hints: List<String> = emptyList(),
        identified: List<OfflineTranslatePlan.Candidate> = emptyList(),
    ): OfflineTranslateResult {
        val input = text.take(MAX_CHARS)
        val targetCode = BergamotLanguages.modelCode(target)?.takeIf(BergamotLanguages::writes)
            ?: return OfflineTranslateResult.Unsupported(target, romanized = BergamotLanguages.isRomanized(target))
        if (!BergamotNative.supported) {
            return OfflineTranslateResult.Failed(UnsupportedOperationException("Bergamot needs Android 9 or later"))
        }
        return try {
            val candidates = when {
                sourceOverride.isNotBlank() -> emptyList()
                identified.isNotEmpty() -> identified
                else -> ScriptLanguageGuess.candidates(input)
            }
            when (
                val source = OfflineTranslatePlan.resolveSource(sourceOverride, candidates, hints, target, BergamotLanguages)
            ) {
                is OfflineTranslatePlan.Source.Unknown -> OfflineTranslateResult.Undetermined
                is OfflineTranslatePlan.Source.Unreadable ->
                    OfflineTranslateResult.Unsupported(source.tag, source.romanized)
                is OfflineTranslatePlan.Source.Known ->
                    translateKnown(context, input, source.code, targetCode, source.guessed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            OfflineTranslateResult.Failed(e)
        }
    }

    private suspend fun translateKnown(
        context: Context,
        text: String,
        source: String,
        target: String,
        guessed: Boolean,
    ): OfflineTranslateResult {
        if (source == target) return OfflineTranslateResult.Success(text, source, guessed)
        val route = BergamotLanguages.route(source, target)
            ?: return OfflineTranslateResult.Unsupported(
                if (BergamotLanguages.reads(source)) target else source,
                romanized = false,
            )
        if (!scanned) refresh(context)
        val missing = route.map(::keyOf).filter { _models.value[it] !is OfflineModelState.Downloaded }.toMutableList()
        if (!FeatureModules.bergamot.installed) missing.add(0, ENGINE_KEY)
        if (missing.isNotEmpty()) return OfflineTranslateResult.NeedsModels(source, target, missing)
        if (!BergamotNative.available) {
            return OfflineTranslateResult.Failed(IllegalStateException("The Bergamot library did not load"))
        }
        val segments = OfflineTranslatePlan.segments(text)
        val bodies = segments.filter { it.body.isNotEmpty() }.map { it.body }
        val translated = try {
            withContext(Dispatchers.Default) { engineLock.withLock { run(context, route, bodies) } }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // The map can be stale: files cleared behind its back fail here.
            val fresh = refresh(context)
            val gone = route.map(::keyOf).filter { it !in fresh }
            if (gone.isEmpty()) throw e
            return OfflineTranslateResult.NeedsModels(source, target, gone)
        }
        return OfflineTranslateResult.Success(OfflineTranslatePlan.join(segments, translated), source, guessed)
    }

    /** Runs [bodies] along [route]. Holds [engineLock]. */
    private fun run(context: Context, route: List<BergamotCatalog.Direction>, bodies: List<String>): List<String> {
        if (bodies.isEmpty()) return emptyList()
        if (service == 0L) service = BergamotNative.nativeNewService(CACHE_SENTENCES)
        val keep = route.map { it.key }.toSet()
        val handles = route.map { load(context, it, keep) }
        val input = bodies.map { it.encodeToByteArray() }.toTypedArray()
        val output = if (handles.size == 1) {
            BergamotNative.nativeTranslate(service, handles[0], input)
        } else {
            BergamotNative.nativePivot(service, handles[0], handles[1], input)
        }
        return output.map { it.decodeToString() }
    }

    /** [direction]'s model, loading it if it is not in memory. Holds [engineLock]. */
    private fun load(context: Context, direction: BergamotCatalog.Direction, keep: Set<String>): Long {
        loaded[direction.key]?.let { return it }
        // Each model is tens of megabytes of native memory: drop the least
        // recently used one that this translation does not need.
        while (loaded.size >= LOADED_MAX) {
            val evict = loaded.keys.firstOrNull { it !in keep } ?: break
            loaded.remove(evict)?.let { BergamotNative.nativeFreeModel(it) }
        }
        val dir = dirOf(context, direction)
        fun path(kind: BergamotCatalog.Kind) = direction.files.firstOrNull { it.kind == kind }?.let { File(dir, it.name).path }
        val model = direction.files.first { it.kind == BergamotCatalog.Kind.MODEL }
        val vocabs = path(BergamotCatalog.Kind.VOCAB)?.let { arrayOf(it) }
            ?: arrayOf(
                path(BergamotCatalog.Kind.SRC_VOCAB) ?: throw IOException("no source vocabulary"),
                path(BergamotCatalog.Kind.TRG_VOCAB) ?: throw IOException("no target vocabulary"),
            )
        val handle = BergamotNative.nativeLoadModel(
            File(dir, model.name).path,
            path(BergamotCatalog.Kind.LEX),
            vocabs,
            int8shiftAll = model.name.endsWith("intgemm8.bin"),
        )
        loaded[direction.key] = handle
        return handle
    }

    /**
     * Lets go of the loaded models and the service. The panel calls this when
     * it closes: a keyboard holding seventy megabytes of translator for a tool
     * nobody has open is first in line when Android wants memory back.
     */
    fun release() {
        if (service == 0L && loaded.isEmpty()) return
        scope.launch {
            engineLock.withLock {
                for (handle in loaded.values) BergamotNative.nativeFreeModel(handle)
                loaded.clear()
                if (service != 0L) BergamotNative.nativeFreeService(service)
                service = 0L
            }
        }
    }

    private const val TAG = "Bergamot"
}
