package com.wasimaster.wmkeyboard.core.translate

import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.modules.NativeDelivery
import android.app.DownloadManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import com.wasimaster.wmkeyboard.core.mlkit.MlKitInit
import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import com.wasimaster.wmkeyboard.core.util.runCancellable
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ML Kit's on-device translator behind the translate tool: the model store,
 * the language identifier that stands in for "detect language", and the
 * translators themselves.
 *
 * One object for the process, because the keyboard and the settings app share
 * it and must agree. A model the settings list is downloading has to show as
 * downloading in the panel, and a model the panel is translating with must not
 * be deleted out from under it by the list. Both read [models] and both go
 * through the same lock.
 *
 * Nothing here imports ML Kit's translate or language-id libraries. Those
 * calls sit behind [TranslateRuntime], reached by reflection, because on Play
 * the libraries ship in an on-demand module the base APK is built without. See
 * [TranslateRuntime] for the split and [TranslateModule.gate] for how a
 * missing module is fetched.
 */
object OnDeviceTranslator {

    /** False in F-Droid's build, which carries no ML Kit. */
    val AVAILABLE: Boolean = BuildConfig.GOOGLE_SDKS

    /**
     * Whether the translate tool can offer this engine. The offline build has
     * the code but no way to its models: ML Kit fetches them itself and takes
     * none from a file (#1035). Language identification needs no model and
     * goes by [AVAILABLE].
     */
    val offered: Boolean get() = AVAILABLE && InternetPermission.granted

    /** Longest text translated in one go, the same cut the online clients make. */
    private const val MAX_CHARS = 2500

    private const val STATUS_TIMEOUT_MS = 20_000L
    private const val PROGRESS_POLL_MS = 700L

    /** How long a download may bring in nothing before it is called off. */
    private const val DOWNLOAD_STALL_MS = 90_000L

    /**
     * The limit for a download whose bytes cannot be seen at all (see
     * [systemDownloadProgress]). With nothing to tell slow from stuck, only
     * the clock is left, and it has to allow thirty megabytes on a bad line.
     */
    private const val DOWNLOAD_BLIND_LIMIT_MS = 10 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var loaded: TranslateRuntime? = null

    /**
     * The runtime, or null while its module is not on this install (Play
     * only). Never throws: a bridge that will not load is the same "not here"
     * to every caller, and each of them already has something to say for it.
     *
     * No restart is needed after the module arrives. SplitCompat (installed at
     * startup in Play builds) lets this process load the split's classes and
     * native libraries as soon as the install completes, and ML Kit's
     * dynamic-feature support library in the base registers the split's
     * components with the ML Kit context that is already running.
     */
    private fun runtime(context: Context): TranslateRuntime? {
        loaded?.let { return it }
        if (TranslateModule.gate.state.value != TranslateModuleState.Installed) return null
        // ML Kit loads both libraries by name; delivered ones go in first.
        if (!NativeDelivery.preload("translate_jni") || !NativeDelivery.preload("language_id_l2c_jni")) return null
        MlKitInit.ensure(context)
        return runCancellable { TranslateModule.load(context) }
            .onSuccess { loaded = it }
            .getOrNull()
    }

    /** Whether the engine is on this install. False only on Play before the module is fetched. */
    val moduleState get() = TranslateModule.gate.state

    /** Asks Play for the module. A no-op where it is compiled in. */
    fun requestModule() = TranslateModule.gate.requestInstall()

    private val _models = MutableStateFlow<Map<String, OfflineModelState>>(emptyMap())

    /**
     * Every model whose state is known, by model code. A language missing from
     * the map has not been looked at yet; call [refresh]. English is always
     * [OfflineModelState.Downloaded].
     */
    val models: StateFlow<Map<String, OfflineModelState>> = _models.asStateFlow()

    private val downloads = mutableMapOf<String, Job>()

    /**
     * Guards the translators against the two things that would break them
     * mid-sentence: another caller swapping the cached pair, and a delete
     * taking a model file away. Translation, release and delete all hold it.
     */
    private val engineLock = Mutex()

    /**
     * Re-reads which models are on the device. Cheap, and safe to call on every
     * panel open: it never touches a language that is mid-download.
     */
    suspend fun refresh(context: Context): Set<String> {
        val engine = runtime(context)
        val onDevice = engine?.let {
            withTimeoutOrNull(STATUS_TIMEOUT_MS) { runCancellable { it.downloadedModels() }.getOrNull() }
        }
        if (onDevice == null) {
            // ML Kit did not answer: not initialised, or wedged. "Checking…"
            // for ever is the wrong thing to draw, so what is not known to be
            // here is shown as not here, with its button.
            _models.update { current ->
                OfflineTranslateLanguages.SUPPORTED.associateWith { code ->
                    current[code] ?: if (code == OfflineTranslateLanguages.PIVOT) {
                        OfflineModelState.Downloaded
                    } else {
                        OfflineModelState.Missing()
                    }
                }
            }
            return downloadedNow()
        }
        val downloaded = onDevice + OfflineTranslateLanguages.PIVOT
        _models.update { current ->
            OfflineTranslateLanguages.SUPPORTED.associateWith { code ->
                val known = current[code]
                when {
                    known is OfflineModelState.Downloading -> known
                    code in downloaded -> OfflineModelState.Downloaded
                    // Keep a failure on show until the next attempt replaces it.
                    known is OfflineModelState.Missing -> known
                    else -> OfflineModelState.Missing()
                }
            }
        }
        return downloaded
    }

    private fun downloadedNow(): Set<String> =
        _models.value.filterValues { it is OfflineModelState.Downloaded }.keys +
            OfflineTranslateLanguages.PIVOT

    /**
     * Starts fetching [code]'s model. Returns at once; follow it in [models].
     * A second call for a language already on its way does nothing, so the
     * panel and the settings list can both offer the button.
     *
     * The download belongs to this object, not to whoever pressed the button:
     * the panel closes and the settings screen is left long before thirty
     * megabytes arrive.
     */
    fun download(context: Context, code: String) {
        if (code == OfflineTranslateLanguages.PIVOT || code !in OfflineTranslateLanguages.SUPPORTED) return
        val app = context.applicationContext
        synchronized(downloads) {
            if (downloads[code]?.isActive == true) return
            _models.update { it + (code to OfflineModelState.Downloading()) }
            downloads[code] = scope.launch {
                val ok = runCancellable { fetch(app, code) }.isSuccess
                synchronized(downloads) { downloads.remove(code) }
                _models.update {
                    it + (code to if (ok) OfflineModelState.Downloaded else OfflineModelState.Missing(failed = true))
                }
            }
        }
    }

    private suspend fun fetch(context: Context, code: String) {
        val engine = runtime(context) ?: throw IOException("The translation module is not installed")
        // The system DownloadManager refuses a caller without the internet
        // permission with a SecurityException (#292).
        InternetPermission.check()
        // ML Kit hands the fetch to the system DownloadManager, which treats
        // "no network" as a reason to wait, indefinitely and in silence. A
        // button that spins forever on a plane is worse than one that says no.
        if (!hasNetwork(context)) throw IOException("No network for the $code translation model")
        val task = engine.startDownload(code)
        val startedAt = SystemClock.elapsedRealtime()
        var lastBytes = 0L
        var lastMovedAt = startedAt
        var visible = false
        while (!task.isComplete) {
            delay(PROGRESS_POLL_MS)
            val now = SystemClock.elapsedRealtime()
            val progress = systemDownloadProgress(context, code)
            if (progress != null) visible = true
            if (progress != null && progress.first > lastBytes) {
                lastBytes = progress.first
                lastMovedAt = now
            }
            _models.update { current ->
                if (current[code] !is OfflineModelState.Downloading) return@update current
                current + (
                    code to OfflineModelState.Downloading(
                        bytes = lastBytes,
                        totalBytes = progress?.second?.takeIf { it > 0 } ?: 0L,
                    )
                )
            }
            val stuck = if (visible) now - lastMovedAt > DOWNLOAD_STALL_MS else now - startedAt > DOWNLOAD_BLIND_LIMIT_MS
            if (stuck) {
                // Left alone it would finish some day into a model nobody is
                // waiting for, or block the next attempt behind itself.
                withdraw(context, code)
                throw IOException("Translation model $code stopped downloading")
            }
        }
        task.await()
    }

    /**
     * Calls off a download in progress. ML Kit has no cancel of its own, so
     * this does both halves by hand: deletes the model, which is ML Kit's own
     * way of forgetting a fetch, and removes the system download behind it, so
     * thirty megabytes nobody is waiting for do not keep arriving.
     */
    fun cancelDownload(context: Context, code: String) {
        val app = context.applicationContext
        val job = synchronized(downloads) { downloads.remove(code) } ?: return
        job.cancel()
        _models.update { it + (code to OfflineModelState.Missing()) }
        scope.launch { withdraw(app, code) }
    }

    private suspend fun withdraw(context: Context, code: String) {
        runCancellable { runtime(context)?.deleteModel(code) }
        withContext(Dispatchers.IO) {
            runCancellable {
                val dm = context.getSystemService(DownloadManager::class.java) ?: return@runCancellable
                val ids = mutableListOf<Long>()
                dm.query(activeDownloads())?.use { cursor ->
                    val idColumn = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                    val uriColumn = cursor.getColumnIndex(DownloadManager.COLUMN_URI)
                    if (idColumn < 0 || uriColumn < 0) return@use
                    while (cursor.moveToNext()) {
                        if (isModelAddress(cursor.getString(uriColumn).orEmpty(), code)) ids += cursor.getLong(idColumn)
                    }
                }
                if (ids.isNotEmpty()) dm.remove(*ids.toLongArray())
            }
        }
    }

    private fun activeDownloads(): DownloadManager.Query = DownloadManager.Query().setFilterByStatus(
        DownloadManager.STATUS_PENDING or DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PAUSED,
    )

    /** Removes [code]'s model from the device. English cannot be removed. */
    suspend fun delete(context: Context, code: String) {
        if (code == OfflineTranslateLanguages.PIVOT) return
        val engine = runtime(context) ?: return
        engineLock.withLock {
            // A translator holds its model files open. Close first, or the
            // delete succeeds on paper and the next translate reads a ghost.
            if (engine.loadedPair()?.let { code == it.first || code == it.second } == true) engine.closeTranslator()
            runCancellable { engine.deleteModel(code) }
        }
        refresh(context)
        _models.update { current ->
            if (current[code] is OfflineModelState.Downloaded) current else current + (code to OfflineModelState.Missing())
        }
    }

    /**
     * Translates [text] into [target] (a picker code such as `bn` or `zh-CN`).
     *
     * [sourceOverride] is the user's own choice of source, blank for detect.
     * [hints] are the languages the user types in, the active one first; see
     * [OfflineTranslatePlan.resolveSource] for what they are used for.
     */
    suspend fun translate(
        context: Context,
        text: String,
        target: String,
        sourceOverride: String = "",
        hints: List<String> = emptyList(),
    ): OfflineTranslateResult {
        val input = text.take(MAX_CHARS)
        val targetCode = OfflineTranslateLanguages.modelCode(target)
            ?: return OfflineTranslateResult.Unsupported(target, romanized = false)
        val engine = runtime(context) ?: return OfflineTranslateResult.ModuleMissing
        return try {
            val candidates = if (sourceOverride.isBlank()) identify(engine, input) else emptyList()
            when (
                val source = OfflineTranslatePlan.resolveSource(sourceOverride, candidates, hints, target)
            ) {
                is OfflineTranslatePlan.Source.Unknown -> OfflineTranslateResult.Undetermined
                is OfflineTranslatePlan.Source.Unreadable ->
                    OfflineTranslateResult.Unsupported(source.tag, source.romanized)
                is OfflineTranslatePlan.Source.Known ->
                    translateKnown(context, engine, input, source.code, targetCode, source.guessed)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // ML Kit throws unchecked from getClient when its context never
            // came up; a keyboard must not die over a translator.
            OfflineTranslateResult.Failed(e)
        }
    }

    private suspend fun translateKnown(
        context: Context,
        engine: TranslateRuntime,
        text: String,
        source: String,
        target: String,
        guessed: Boolean,
    ): OfflineTranslateResult {
        // Already in the target language: hand it back, as the online
        // services do, rather than fail to build an en-to-en translator.
        if (source == target) return OfflineTranslateResult.Success(text, source, guessed)
        // The live query follows typing, so the shared map answers "is it
        // here?" once it has been filled; asking ML Kit on every keystroke
        // would be asking the same question sixty times a minute.
        val known = _models.value
        val downloaded = if (known.isEmpty()) refresh(context) else downloadedNow()
        val needed = OfflineTranslateLanguages.modelsNeeded(source, target)
        val missing = needed.filter { it !in downloaded }
        if (missing.isNotEmpty()) return OfflineTranslateResult.NeedsModels(source, target, missing)

        val segments = OfflineTranslatePlan.segments(text)
        val translated = try {
            engineLock.withLock {
                engine.translate(source, target, segments.filter { it.body.isNotEmpty() }.map { it.body })
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // The map can be stale: a model removed behind its back (storage
            // cleared, another process) fails here rather than above. Ask ML
            // Kit itself before calling it an engine failure.
            engineLock.withLock { engine.closeTranslator() }
            val fresh = refresh(context)
            val stillMissing = needed.filter { it !in fresh }
            if (stillMissing.isEmpty()) throw e
            return OfflineTranslateResult.NeedsModels(source, target, stillMissing)
        }
        return OfflineTranslateResult.Success(OfflineTranslatePlan.join(segments, translated), source, guessed)
    }

    /**
     * What ML Kit's language identifier makes of [text], for the Bergamot
     * engine, which has no identifier of its own. Empty while the module is
     * not here or when the identifier does not answer.
     */
    suspend fun identify(context: Context, text: String): List<OfflineTranslatePlan.Candidate> {
        val engine = runtime(context) ?: return emptyList()
        return identify(engine, text.take(MAX_CHARS))
    }

    /**
     * Identification failing is not translation failing: the hints can still
     * settle the source, and the user can always pick it.
     */
    private suspend fun identify(engine: TranslateRuntime, text: String): List<OfflineTranslatePlan.Candidate> =
        withTimeoutOrNull(STATUS_TIMEOUT_MS) { runCancellable { engine.identify(text) }.getOrNull() }.orEmpty()

    /**
     * Lets go of the loaded models. The panel calls this when it closes: a
     * keyboard process that keeps sixty megabytes of translator for a tool
     * nobody has open is first in line when Android wants memory back.
     */
    fun release() {
        val engine = loaded ?: return
        scope.launch { engineLock.withLock { engine.release() } }
    }

    private fun hasNetwork(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        return runCatching { cm.activeNetwork != null }.getOrDefault(true)
    }

    /**
     * Bytes so far and the total for [code]'s model, read off the system
     * download ML Kit started, or null when no such download can be found.
     *
     * ML Kit's API reports no progress at all. But translate 17.0.3 fetches
     * its models through `DownloadManager` (read off its bytecode: it enqueues
     * `…/translate/offline/v5/high/<version>/<pair>.zip` and waits for
     * `DOWNLOAD_COMPLETE`), and an app may query its own downloads, so the
     * numbers exist; they are just somebody else's. The row is matched by
     * address: model files are named after their language pair, `bn_en.zip`.
     * A later release that moves off `DownloadManager` costs the bar, not the
     * download: see [DOWNLOAD_BLIND_LIMIT_MS].
     */
    private suspend fun systemDownloadProgress(context: Context, code: String): Pair<Long, Long>? =
        withContext(Dispatchers.IO) {
            runCancellable {
                val dm = context.getSystemService(DownloadManager::class.java) ?: return@runCancellable null
                dm.query(activeDownloads())?.use { cursor ->
                    val uriColumn = cursor.getColumnIndex(DownloadManager.COLUMN_URI)
                    val bytesColumn = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val totalColumn = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    if (uriColumn < 0 || bytesColumn < 0 || totalColumn < 0) return@use null
                    while (cursor.moveToNext()) {
                        val uri = cursor.getString(uriColumn).orEmpty()
                        if (isModelAddress(uri, code)) {
                            return@use cursor.getLong(bytesColumn).coerceAtLeast(0L) to cursor.getLong(totalColumn)
                        }
                    }
                    null
                }
            }.getOrNull()
        }

    /** Whether [uri] is the download of [code]'s model: `…/en_bn.zip` style names. */
    internal fun isModelAddress(uri: String, code: String): Boolean {
        val file = uri.substringBefore('?').substringAfterLast('/').lowercase()
        if (!uri.contains("translate", ignoreCase = true)) return false
        return file.split('_', '-', '.').any { it == code }
    }
}
