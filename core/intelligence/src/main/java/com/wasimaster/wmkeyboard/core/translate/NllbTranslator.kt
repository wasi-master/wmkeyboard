package com.wasimaster.wmkeyboard.core.translate

import android.content.Context
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.modules.DownloadedModuleGate
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.FeatureModules.awaitInstalled
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.modules.NativeConsent
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
import java.text.BreakIterator
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Meta's NLLB-200 (No Language Left Behind, the distilled 600M model in
 * CTranslate2's int8 form) behind the translate tool: the on-device engine
 * for the pairs Bergamot has no model for. One download of about 630 MB
 * covers all 200 of its languages, in every direction, with no pivot.
 *
 * It is the heavy engine of the three. The model is held in memory while
 * the panel translates (about 620 MB) and let go when the panel closes, and
 * a short sentence takes seconds rather than Bergamot's fraction of one. So
 * the default translate list puts it after Bergamot: a pair Bergamot has
 * downloaded goes there first.
 *
 * The weights are under CC BY-NC 4.0 (non-commercial use). They come from a
 * pinned revision of a Hugging Face repository, each file checked against
 * its SHA-256, and only after the user has agreed to that licence
 * ([NativeConsent.NLLB]); this project never hands them out itself.
 *
 * Model keys in [models]: [MODEL_KEY] for the model, [ENGINE_KEY] for the
 * CTranslate2 library on a build that downloads it.
 */
object NllbTranslator {

    const val KEY_PREFIX = "nllb:"

    /** The model, the one download every pair needs. */
    const val MODEL_KEY = "${KEY_PREFIX}model"

    /** The library itself, where this build fetches it on demand. */
    const val ENGINE_KEY = "${KEY_PREFIX}engine"

    /** CTranslate2's conversion of facebook/nllb-200-distilled-600M, int8 weights. */
    private const val REPO = "JustFrederik/nllb-200-distilled-600M-ct2-int8"
    private const val REVISION = "302d78f00e6fdb50a1064059df7c392b735e9d05"

    /** The licence the weights come under (Meta's, kept by the converted copy). */
    const val LICENCE_URL = "https://creativecommons.org/licenses/by-nc/4.0/legalcode"

    /** One file of the model repository, at [REVISION]. */
    class ModelFile(val name: String, val bytes: Long, val sha256: String) {
        val url: String get() = "https://huggingface.co/$REPO/resolve/$REVISION/$name"
    }

    val FILES: List<ModelFile> = listOf(
        ModelFile("config.json", 159, "0c2f6fa2057c7264d052fb4a62ba3476eeae70487acddfa8e779a53a00cbf44c"),
        ModelFile("shared_vocabulary.txt", 2_568_098, "a132a83330f45514c2476eb81d1d69b3c41762264d16ce0a7ea982e5d6c728e5"),
        ModelFile("sentencepiece.bpe.model", 4_852_054, "14bb8dfb35c0ffdea7bc01e56cea38b9e3d5efcdcb9c251d6b40538e1aab555a"),
        ModelFile("model.bin", 622_595_991, "ed1beaf75134de7505315a5223162f56acff397eff6b50638a500d3936fe707b"),
    )

    /** What the model costs to download, and takes on the phone. */
    val modelBytes: Long = FILES.sumOf { it.bytes }

    /** Longest text translated in one go, the same cut the other engines make. */
    private const val MAX_CHARS = 2500

    /**
     * Sentences per call into the engine: one call decodes them side by side,
     * which costs little more than one alone, and a long text can still be
     * called off between calls.
     */
    private const val BATCH = 8

    /** Greedy search: a beam of two costs a third more time for little a reader would notice. */
    private const val BEAM = 1

    /** Written last into the model's folder, once every file in it is verified. */
    private const val COMPLETE = ".complete"

    /**
     * Set once at startup from the phone's memory ([Ct2Native.enoughMemory]):
     * below it the engine is never offered.
     */
    @Volatile
    private var roomy = true

    /** Reads the phone's memory once, before [available] is first asked. */
    fun checkMemory(context: Context) {
        roomy = Ct2Native.enoughMemory(context.applicationContext)
    }

    /**
     * Whether this build and phone can run the engine at all: a 64-bit
     * process, the memory for the model, and a way to fetch it, which only
     * the internet gives. Loads nothing.
     */
    val available: Boolean get() = Ct2Native.supported && roomy && InternetPermission.granted

    fun isNllbKey(key: String): Boolean = key.startsWith(KEY_PREFIX)

    /** What fetching [key] costs: the model, or the library's bytes still to come. */
    fun downloadBytes(key: String): Long = when (key) {
        ENGINE_KEY -> (FeatureModules.ct2 as? DownloadedModuleGate)?.missingBytes ?: 0L
        MODEL_KEY -> modelBytes
        else -> 0L
    }

    /** Whether the user has agreed to the weights' licence, which every download needs first. */
    val licenceAccepted: Boolean get() = NativeConsent.has(NativeConsent.NLLB)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _models = MutableStateFlow<Map<String, OfflineModelState>>(emptyMap())

    /** [MODEL_KEY]'s state, plus [ENGINE_KEY] while the library is a download. Empty until the first [refresh]. */
    val models: StateFlow<Map<String, OfflineModelState>> = _models.asStateFlow()

    private var download: Job? = null

    /** Guards the loaded model: translation, release and delete all hold it. */
    private val engineLock = Mutex()

    private var handle = 0L

    @Volatile
    private var watchingEngine = false

    @Volatile
    private var scanned = false

    private fun root(context: Context): File = File(context.applicationContext.noBackupFilesDir, "nllb")

    private fun dir(context: Context): File = File(root(context), REVISION)

    private fun installed(context: Context): Boolean {
        val dir = dir(context)
        return File(dir, COMPLETE).isFile && FILES.all { File(dir, it.name).length() == it.bytes }
    }

    /** Re-reads whether the model is on the phone, and drops an older revision's folder. */
    suspend fun refresh(context: Context): Boolean = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        watchEngine()
        runCatching { root(app).listFiles().orEmpty().filter { it.name != REVISION }.forEach { it.deleteRecursively() } }
        val here = installed(app)
        _models.update { current ->
            val known = current[MODEL_KEY]
            val next = HashMap<String, OfflineModelState>()
            next[MODEL_KEY] = when {
                known is OfflineModelState.Downloading -> known
                here -> OfflineModelState.Downloaded
                known is OfflineModelState.Missing -> known
                else -> OfflineModelState.Missing()
            }
            engineState()?.let { next[ENGINE_KEY] = it }
            next
        }
        scanned = true
        here
    }

    private fun engineState(): OfflineModelState? {
        val gate = FeatureModules.ct2
        if (!gate.onDemand) return null
        return when (val state = gate.state.value) {
            ModuleState.Installed -> OfflineModelState.Downloaded
            is ModuleState.Installing -> OfflineModelState.Downloading(state.bytes, state.totalBytes)
            is ModuleState.Missing -> OfflineModelState.Missing(failed = state.failed)
        }
    }

    private fun watchEngine() {
        if (watchingEngine || !FeatureModules.ct2.onDemand) return
        watchingEngine = true
        scope.launch {
            FeatureModules.ct2.state.collect {
                engineState()?.let { state -> _models.update { models -> models + (ENGINE_KEY to state) } }
            }
        }
    }

    /**
     * Starts fetching the model (or the library, for [ENGINE_KEY]). Returns
     * at once; follow it in [models]. Does nothing until the licence is
     * accepted ([licenceAccepted]): the caller shows it first.
     */
    fun download(context: Context, key: String) {
        val app = context.applicationContext
        if (!licenceAccepted) {
            DebugLog.w(TAG, "download asked for before the licence was accepted")
            return
        }
        if (key == ENGINE_KEY) {
            watchEngine()
            FeatureModules.ct2.requestInstall()
            return
        }
        if (key != MODEL_KEY) return
        synchronized(this) {
            if (download?.isActive == true) return
            _models.update { it + (MODEL_KEY to OfflineModelState.Downloading(0, modelBytes)) }
            download = scope.launch {
                val outcome = runCancellable { fetch(app) }
                outcome.exceptionOrNull()?.let { DebugLog.w(TAG, "fetching the NLLB model failed: $it") }
                synchronized(this@NllbTranslator) { download = null }
                _models.update {
                    it + (MODEL_KEY to if (outcome.isSuccess) OfflineModelState.Downloaded else OfflineModelState.Missing(failed = true))
                }
            }
        }
    }

    private suspend fun fetch(context: Context) {
        InternetPermission.check()
        // The library comes with the model, as its own row ([ENGINE_KEY]):
        // six hundred megabytes nothing on the phone can read would be a waste.
        watchEngine()
        if (!FeatureModules.ct2.awaitInstalled()) throw IOException("The CTranslate2 library could not be fetched")
        val dir = dir(context)
        dir.mkdirs()
        File(dir, COMPLETE).delete()
        var done = 0L
        for (file in FILES) {
            val target = File(dir, file.name)
            if (target.length() == file.bytes && FileDownloader.sha256(target) == file.sha256) {
                done += file.bytes
                continue
            }
            target.delete()
            val part = File(dir, "${file.name}.part")
            val before = done
            transfer(file.url, file.bytes, part) { bytes -> progress(before + bytes) }
            coroutineContext.ensureActive()
            if (part.length() != file.bytes || FileDownloader.sha256(part) != file.sha256) {
                part.delete()
                throw IOException("${file.name} did not match its pinned checksum")
            }
            if (!part.renameTo(target)) throw IOException("could not keep ${file.name}")
            done += file.bytes
            progress(done)
        }
        File(dir, COMPLETE).writeText(REVISION)
    }

    private fun progress(bytes: Long) {
        _models.update { current ->
            if (current[MODEL_KEY] !is OfflineModelState.Downloading) current
            else current + (MODEL_KEY to OfflineModelState.Downloading(bytes, modelBytes))
        }
    }

    /** Downloads [url] into [part] through [FileDownloader]: parallel, and resumed piece by piece. */
    private suspend fun transfer(url: String, size: Long, part: File, onBytes: (Long) -> Unit) {
        FileDownloader.fetch(url, part, size, NetSource.DOWNLOAD_TRANSLATE, onBytes = onBytes)
    }

    /**
     * Calls off the download in progress. What it fetched stays, so the next
     * attempt resumes rather than starting the 600 MB again; [delete] clears it.
     */
    fun cancelDownload(key: String) {
        if (key != MODEL_KEY) return
        val job = synchronized(this) { download.also { download = null } } ?: return
        job.cancel()
        _models.update { it + (MODEL_KEY to OfflineModelState.Missing()) }
    }

    /** Removes the model from the phone, unloading it first. */
    suspend fun delete(context: Context) {
        engineLock.withLock {
            if (handle != 0L) Ct2Native.nativeFree(handle)
            handle = 0L
            withContext(Dispatchers.IO) { root(context).deleteRecursively() }
        }
        _models.update { it + (MODEL_KEY to OfflineModelState.Missing()) }
    }

    /**
     * Translates [text] into [target] (a picker code such as `bn` or `zh-TW`).
     * [sourceOverride], [hints] and [identified] as for [BergamotTranslator.translate].
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
        val targetCode = NllbLanguages.modelCode(target)
            ?: return OfflineTranslateResult.Unsupported(target, romanized = NllbLanguages.isRomanized(target))
        if (!Ct2Native.supported) {
            return OfflineTranslateResult.Failed(UnsupportedOperationException("NLLB needs a 64-bit phone"))
        }
        return try {
            val candidates = when {
                sourceOverride.isNotBlank() -> emptyList()
                identified.isNotEmpty() -> identified
                else -> ScriptLanguageGuess.candidates(input)
            }
            when (val source = OfflineTranslatePlan.resolveSource(sourceOverride, candidates, hints, target, NllbLanguages)) {
                is OfflineTranslatePlan.Source.Unknown -> OfflineTranslateResult.Undetermined
                is OfflineTranslatePlan.Source.Unreadable -> OfflineTranslateResult.Unsupported(source.tag, source.romanized)
                is OfflineTranslatePlan.Source.Known -> translateKnown(context, input, source.code, targetCode, source.guessed)
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
        if (!scanned) refresh(context)
        val missing = mutableListOf<String>()
        if (!FeatureModules.ct2.installed) missing.add(ENGINE_KEY)
        if (_models.value[MODEL_KEY] !is OfflineModelState.Downloaded) missing.add(MODEL_KEY)
        if (missing.isNotEmpty()) return OfflineTranslateResult.NeedsModels(source, target, missing)
        if (!Ct2Native.available) {
            return OfflineTranslateResult.Failed(IllegalStateException("The CTranslate2 library did not load"))
        }
        val segments = OfflineTranslatePlan.segments(text)
        val bodies = segments.filter { it.body.isNotEmpty() }.map { it.body }
        val translated = try {
            withContext(Dispatchers.Default) { run(context, bodies, source, target) }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // The state can be stale: files cleared behind its back fail here.
            if (refresh(context)) throw e
            return OfflineTranslateResult.NeedsModels(source, target, listOf(MODEL_KEY))
        }
        return OfflineTranslateResult.Success(OfflineTranslatePlan.join(segments, translated), source, guessed)
    }

    /**
     * Each line of [bodies] translated sentence by sentence (the model was
     * trained on sentences, and drops the end of a long paragraph), then
     * rejoined with the spaces the line had between them.
     */
    private suspend fun run(context: Context, bodies: List<String>, source: String, target: String): List<String> {
        if (bodies.isEmpty()) return emptyList()
        val lines = bodies.map { sentences(it) }
        val flat = lines.flatten()
        val out = ArrayList<String>(flat.size)
        for (chunk in flat.chunked(BATCH)) {
            coroutineContext.ensureActive()
            val input = chunk.map { it.trim().encodeToByteArray() }.toTypedArray()
            val answer = engineLock.withLock {
                Ct2Native.nativeTranslate(load(context), input, source, target, BEAM)
            }
            answer.mapTo(out) { it.decodeToString() }
        }
        var next = 0
        return lines.map { line ->
            line.joinToString("") { sentence ->
                val trailing = sentence.substring(sentence.trimEnd().length)
                out[next++] + trailing
            }
        }
    }

    /** [line] cut into sentences, each keeping the spaces after it. */
    internal fun sentences(line: String): List<String> {
        val breaks = BreakIterator.getSentenceInstance(Locale.ROOT)
        breaks.setText(line)
        val parts = mutableListOf<String>()
        var start = breaks.first()
        var end = breaks.next()
        while (end != BreakIterator.DONE) {
            val part = line.substring(start, end)
            if (part.isNotBlank()) parts.add(part) else if (parts.isNotEmpty()) parts[parts.lastIndex] += part
            start = end
            end = breaks.next()
        }
        return parts.ifEmpty { listOf(line) }
    }

    /** The loaded model, loading it if it is not in memory. Holds [engineLock]. */
    private fun load(context: Context): Long {
        if (handle != 0L) return handle
        // Half the cores, up to four: the big ones on most phones, and the
        // keyboard stays responsive while it works.
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
        handle = Ct2Native.nativeLoad(dir(context).path, threads)
        return handle
    }

    /**
     * Lets go of the model. The panel calls this when it closes: six hundred
     * megabytes held for a tool nobody has open is what gets a keyboard killed.
     */
    /** Whether the model is loaded; for the Resources screen (#1163). Any thread. */
    val isLoaded: Boolean get() = handle != 0L

    fun release() {
        if (handle == 0L) return
        scope.launch {
            engineLock.withLock {
                if (handle != 0L) Ct2Native.nativeFree(handle)
                handle = 0L
            }
        }
    }

    private const val TAG = "Nllb"
}
