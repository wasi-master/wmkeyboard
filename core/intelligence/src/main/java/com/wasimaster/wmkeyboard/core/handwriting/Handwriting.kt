package com.wasimaster.wmkeyboard.core.handwriting

import android.content.Context
import android.os.SystemClock
import com.wasimaster.wmkeyboard.core.input.composer.CjkConfig
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.NativeDelivery
import com.wasimaster.wmkeyboard.core.modules.FeatureModules.awaitInstalled
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.modules.NotInBuild
import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import com.wasimaster.wmkeyboard.core.script.LanguageDef
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.util.runCancellable
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One sampled point of a handwriting stroke, in canvas pixels. */
data class HwPoint(val x: Float, val y: Float, val t: Long)

/** A finished stroke: the points between one touch-down and its release. */
data class HwStroke(val points: List<HwPoint>)

/**
 * A model download that failed for a reason the user can act on, with the
 * sentence to show them: the panel puts [message] where it would otherwise
 * say only that the download failed.
 */
class HandwritingDownloadException(message: String) : IOException(message)

/**
 * How far a model download has got. ML Kit reports nothing at all — it hands
 * back a single Task that either completes or does not — so these numbers are
 * measured rather than reported: Mobile Data Download writes the model into
 * the app's own `datadownload` directory as it goes, and that directory
 * growing *is* the download working.
 *
 * [totalBytes] comes from ML Kit's own shipped figures rather than its API —
 * see [HandwritingModelSizes] — so the bar is real wherever it is drawn, and
 * simply absent for the handful of tags those figures do not cover.
 */
data class HandwritingDownloadProgress(
    /** Bytes this download has written so far; never goes backwards. */
    val bytes: Long = 0,
    /** What [bytes] is counting up to, or 0 when the size is not known. */
    val totalBytes: Long = 0,
    /** Mean speed since the download started; 0 before the first byte. */
    val bytesPerSecond: Long = 0,
    /** How long the byte count has stood still. */
    val stalledForMs: Long = 0,
) {
    /** 0f..1f for a determinate bar, or null when there is no total. */
    val fraction: Float?
        get() = if (totalBytes > 0) (bytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

/**
 * One of ML Kit's model tags, taken apart the way its own identifier does it:
 * `sa-Deva-IN` is language `sa`, script `Deva`, region `IN`; `no` has neither
 * a script nor a region. The script is the one the model reads whether or
 * not the tag spells it (`af` is Latn), from [InkModelTags], as the library's
 * identifier reports it. Parsed here rather than asked of the library so the
 * mapping works before the library's module is on the device (Play).
 * InkModelTagsTest pins all three parts to the library's own subtags.
 */
internal data class InkTag(val tag: String) {
    private val parts = tag.split('-')
    val language: String = parts[0]
    val script: String? = parts.getOrNull(1)?.takeIf { it.length == SCRIPT_LENGTH && it.all(Char::isLetter) }
        ?: InkModelTags.SCRIPTS[tag]
    val region: String? = parts.drop(1).firstOrNull {
        (it.length == REGION_ALPHA_LENGTH && it.all(Char::isLetter)) ||
            (it.length == REGION_DIGIT_LENGTH && it.all(Char::isDigit))
    }

    private companion object {
        const val SCRIPT_LENGTH = 4
        const val REGION_ALPHA_LENGTH = 2
        const val REGION_DIGIT_LENGTH = 3
    }
}

/**
 * Handwriting model catalog and download management. Shared by the IME
 * (recognition, in-panel download) and the settings app (model manager), so
 * both always agree on which models exist and their language tags.
 *
 * Three recognisers sit behind it. ML Kit's Digital Ink reads 126 of the
 * keyboard's languages wherever the build carries it. zinnia
 * ([ZinniaModels], tags starting `zinnia-`) reads Chinese and Japanese only,
 * and takes them where ML Kit is not in the build (F-Droid's), where ML Kit
 * has no model (Cantonese), or where the user picked it ([preferZinnia]).
 * PaddleOCR's line recognisers ([InkOcrModels], tags starting `inkocr-`)
 * read Latin, Cyrillic, Arabic, Devanagari, Tamil, Telugu, Thai, Greek and
 * Korean writing wherever ML Kit has no model for the language, which in
 * F-Droid's build is everywhere. Every function here takes any kind of tag.
 *
 * Nothing here imports ML Kit. Those calls sit behind [InkRuntime], reached
 * by reflection, because on Play the library ships in an on-demand module
 * the base APK is built without; [download] fetches that module before the
 * model, so to the panel the two are one download with one readout.
 */
object HandwritingModels {

    /**
     * ML Kit's own catalogue, minus the non-language recognizers (autodraw,
     * emoji, shapes) and the gesture models, which all carry an `-x-` private
     * subtag. Compiled in (see [InkModelTags]) rather than read off the
     * library, which on Play may not be here yet.
     */
    private val allIdentifiers: List<InkTag> by lazy { InkModelTags.ALL.map(::InkTag) }

    private val allTags: Set<String> by lazy { InkModelTags.ALL.toSet() }

    /**
     * Whether this build carries ML Kit's recogniser at all, here or on its
     * way as a Play module. False in F-Droid's build, where every language
     * zinnia cannot read has no handwriting.
     */
    val mlKitInBuild: Boolean get() = FeatureModules.handwriting !== NotInBuild

    /**
     * Chinese and Japanese write through zinnia even though ML Kit is here:
     * the Handwriting tool's "Offline recogniser for Chinese and Japanese"
     * switch, pushed in by whoever reads settings (the keyboard's settings
     * collector, the tool's settings screen). Moot without ML Kit, where
     * zinnia is the only recogniser those languages have.
     */
    @Volatile
    var preferZinnia: Boolean = false

    /** Where the recogniser's module stands. Always installed outside Play. */
    val moduleState: StateFlow<ModuleState> get() = FeatureModules.handwriting.state

    /** Asks Play for the module. A no-op where it is compiled in. */
    fun requestModule() = FeatureModules.handwriting.requestInstall()

    @Volatile
    private var loaded: InkRuntime? = null

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
    private fun runtime(): InkRuntime? {
        loaded?.let { return it }
        if (!FeatureModules.handwriting.installed) return null
        // ML Kit loads its library by name; a delivered one goes in first.
        if (!NativeDelivery.preload("digitalink")) return null
        return runCancellable {
            FeatureModules.load<InkRuntime>(InkModule.BRIDGE_CLASS, HandwritingModels::class.java.classLoader!!)
        }.onSuccess { loaded = it }.getOrNull()
    }

    /** Recogniser side of the same runtime; null while the module is missing. */
    internal fun runtimeForRecognition(): InkRuntime? = runtime()

    /**
     * Language ids whose ML Kit subtag is spelled differently. Only the ones
     * the keyboard's own registry actually offers are worth listing.
     */
    private val SUBTAG_ALIASES = mapOf(
        "nb" to "no",   // Bokmål — ML Kit ships plain Norwegian
        "tl" to "fil",  // Tagalog — ML Kit ships Filipino
    )

    /** ISO 15924 codes, the way ML Kit spells its script subtags. */
    private val SCRIPT_CODES = mapOf(
        ScriptId.LATIN to "Latn", ScriptId.CYRILLIC to "Cyrl", ScriptId.GREEK to "Grek",
        ScriptId.ARMENIAN to "Armn", ScriptId.GEORGIAN to "Geor", ScriptId.ARABIC to "Arab",
        ScriptId.HEBREW to "Hebr", ScriptId.SYRIAC to "Syrc", ScriptId.DEVANAGARI to "Deva",
        ScriptId.BENGALI to "Beng", ScriptId.GURMUKHI to "Guru", ScriptId.GUJARATI to "Gujr",
        ScriptId.ORIYA to "Orya", ScriptId.TAMIL to "Taml", ScriptId.TELUGU to "Telu",
        ScriptId.KANNADA to "Knda", ScriptId.MALAYALAM to "Mlym", ScriptId.SINHALA to "Sinh",
        ScriptId.THAI to "Thai", ScriptId.LAO to "Laoo", ScriptId.KHMER to "Khmr",
        ScriptId.MYANMAR to "Mymr", ScriptId.HANGUL to "Hang", ScriptId.ETHIOPIC to "Ethi",
        ScriptId.THAANA to "Thaa", ScriptId.JAPANESE to "Jpan", ScriptId.HAN to "Hani",
        ScriptId.TIFINAGH to "Tfng", ScriptId.CHEROKEE to "Cher", ScriptId.NKO to "Nkoo",
        ScriptId.CANADIAN_ABORIGINAL_SYLLABICS to "Cans", ScriptId.TIBETAN to "Tibt",
    )

    /**
     * The recognition model tag for one of the keyboard's languages, or null
     * when no recogniser has a model for it: zinnia's for Chinese and
     * Japanese when it is the one to use (see the class comment), ML Kit's
     * otherwise, and PaddleOCR's where ML Kit has none. [zinniaPicked] is
     * the user's switch; the settings screen passes the value it shows, the
     * keyboard leaves it to [preferZinnia].
     */
    fun tagFor(language: LanguageDef, zinniaPicked: Boolean = preferZinnia): String? {
        val mlKit = if (mlKitInBuild) mlKitTagFor(language) else null
        val zinnia = ZinniaModels.tagFor(language, CjkConfig.traditionalOutput)
            ?: return mlKit ?: InkOcrModels.tagFor(language)
        return if (zinniaPicked || mlKit == null) zinnia else mlKit
    }

    /**
     * ML Kit's tag for [language], or null when it has no model for it.
     * Among a language's variants: the one matching the language's own
     * locale wins, then the bare tag, then one written in the language's own
     * script — that last step is what keeps Sanskrit on `sa-Deva-IN` rather
     * than the romanised `sa-Latn`.
     */
    private fun mlKitTagFor(language: LanguageDef): String? {
        val subtag = SUBTAG_ALIASES[language.id] ?: language.id
        val candidates = allIdentifiers.filter { it.language == subtag }
        if (candidates.isEmpty()) return null
        val locale = language.localeTag.lowercase()
        val region = locale.substringAfter('-', "")
        val script = SCRIPT_CODES[language.script]
        return candidates.firstOrNull { it.tag.lowercase() == locale }?.tag
            ?: candidates.firstOrNull { it.tag.lowercase() == subtag }?.tag
            ?: candidates.filter { it.script == script }
                .minByOrNull { it.tag.length }?.tag
            ?: candidates.firstOrNull {
                it.script == null && it.region.orEmpty().lowercase() == region
            }?.tag
            // Never a model of another script: Sindhi in Arabic letters has no
            // use for sd-Deva, and the ink recognisers may read it after all.
            ?: candidates.filter { it.script == null }.minByOrNull { it.tag.length }?.tag
    }

    /**
     * The recognition model tag for a language id; falls back to the id
     * itself, which [hasModel] then answers no for.
     */
    fun tagForLangId(langId: String): String =
        tagFor(LanguageRegistry.byId(langId)) ?: if (langId == "en" && mlKitInBuild) "en-US" else langId

    /**
     * The models worth offering: one per language the user actually types in,
     * skipping the ones no recogniser can read. This is what the settings
     * screen lists, so enabling a language is all it takes for its model to
     * show up there. Chinese through zinnia lists both its models, since the
     * Traditional output switch picks between them at writing time; the
     * languages of one script that PaddleOCR reads share one row, its model.
     */
    fun modelsFor(languages: List<LanguageDef>, zinniaPicked: Boolean = preferZinnia): List<HandwritingLanguage> =
        languages.distinctBy { it.id }.flatMap { language ->
            val tag = tagFor(language, zinniaPicked) ?: return@flatMap emptyList()
            if (ZinniaModels.isTag(tag)) {
                ZinniaModels.tagsFor(language).map { HandwritingLanguage(it, ZinniaModels.displayName(it)) }
            } else if (InkOcrModels.isTag(tag)) {
                listOf(HandwritingLanguage(tag, InkOcrModels.displayName(tag)))
            } else {
                listOf(HandwritingLanguage(tag, language.displayName))
            }
        }.distinctBy { it.tag }

    /**
     * Compact badge for the in-panel language toggle. The keyboard's own
     * language id rather than ML Kit's tag, so the badge reads NB and TL where
     * the model is called `no` and `fil-Latn`.
     */
    fun shortLabel(tag: String): String =
        if (ZinniaModels.isTag(tag)) {
            ZinniaModels.shortLabel(tag)
        } else if (InkOcrModels.isTag(tag)) {
            InkOcrModels.shortLabel(tag)
        } else {
            (languageForTag(tag)?.id ?: tag.substringBefore('-')).uppercase()
        }

    fun displayName(tag: String): String = when {
        ZinniaModels.isTag(tag) -> ZinniaModels.displayName(tag)
        InkOcrModels.isTag(tag) -> InkOcrModels.displayName(tag)
        else -> languageForTag(tag)?.displayName ?: tag
    }

    /**
     * Whether a word written with [tag] gets a capital at the start of a
     * sentence: English through ML Kit, and every script with capitals
     * through PaddleOCR. Other ML Kit models are left as they read.
     */
    fun capitalizesSentences(tag: String): Boolean =
        tag == "en-US" || InkOcrModels.packFor(tag)?.cased == true

    private fun languageForTag(tag: String): LanguageDef? {
        val subtag = tag.substringBefore('-')
        return LanguageRegistry.all.firstOrNull { (SUBTAG_ALIASES[it.id] ?: it.id) == subtag }
    }

    /**
     * The bridge's once-per-process reset of Mobile Data Download's group
     * record, which every ink call has to follow; see `MlKitInkRuntime`.
     * Recognition goes through it too, since the recogniser's model lookup
     * waits on the same registration.
     */
    internal suspend fun prepare(engine: InkRuntime) = engine.prepare()

    /**
     * Mobile Data Download's instance directory for ink, under `filesDir`.
     * The files land in `<instance>/shared/datadownload/public/…`.
     */
    private const val MDD_INSTANCE_DIRECTORY = "mlkit_digital_ink_recognition"

    /** Mobile Data Download's own directory name, under one of the app dirs. */
    private const val MDD_DIRECTORY = "datadownload"
    private const val PROGRESS_POLL_MS = 1_000L
    private const val MILLIS_PER_SECOND = 1_000L

    /**
     * How long a download may bring in nothing before the panel calls it off.
     * Generous: ink models arrive in bursts with real gaps between them.
     */
    private const val DOWNLOAD_GIVE_UP_MS = 90_000L

    /**
     * A "is it here?" check waits on ML Kit registering its file groups, which
     * is about ten seconds the first time in a process (see `MlKitInkRuntime.prepare`) and
     * instant after that. This is only a backstop for slower phones.
     */
    private const val STATUS_TIMEOUT_MS = 60_000L

    /** Whether a recogniser in this build has a model for [tag] at all. */
    fun hasModel(tag: String): Boolean = when {
        ZinniaModels.isTag(tag) -> ZinniaModels.packFor(tag) != null
        InkOcrModels.isTag(tag) -> InkOcrModels.packFor(tag) != null
        else -> mlKitInBuild && tag in allTags
    }

    /**
     * Whether [tag]'s model is on the device. False on Play until the
     * recogniser's module is here, since there is nothing to ask.
     */
    suspend fun isDownloaded(tag: String): Boolean {
        if (ZinniaModels.isTag(tag)) return ZinniaModels.isDownloaded(tag)
        if (InkOcrModels.isTag(tag)) return InkOcrModels.isDownloaded(tag)
        if (!hasModel(tag)) return false
        val engine = runtime() ?: return false
        engine.prepare()
        // Bounded for the same reason the download below is: this Task comes
        // out of the same machinery, and a status check that never answers
        // strands the panel on its "checking" spinner.
        return withTimeoutOrNull(STATUS_TIMEOUT_MS) {
            runCancellable { engine.isDownloaded(tag) }.getOrDefault(false)
        } ?: false
    }

    /**
     * Downloads the model for [tag], reporting measured progress through
     * [onProgress] roughly once a second. Throws on failure (no network, no
     * space) and on a download that stops making progress.
     *
     * On Play the recogniser's own module comes first, with Play's byte
     * counts on the same readout, so the panel's one Download button covers
     * both and a cancelled wait leaves Play finishing the module on its own.
     *
     * ML Kit 19 fetches ink models through Mobile Data Download, whose future
     * can stay pending long after the bytes have arrived — and, when a fetch
     * goes wrong, forever. Neither it nor the Task it becomes can be
     * cancelled, so the only way out is one of our own: watch the files land,
     * and give up once nothing has arrived for a while.
     */
    suspend fun download(
        context: Context,
        tag: String,
        onProgress: (HandwritingDownloadProgress) -> Unit = {},
    ) {
        if (ZinniaModels.isTag(tag)) return ZinniaModels.download(context, tag, onProgress)
        if (InkOcrModels.isTag(tag)) return InkOcrModels.download(context, tag, onProgress)
        require(hasModel(tag)) { "No model for $tag" }
        // Packs imported from another device (#666) are served off disk, so
        // that download needs neither the network nor the permission for it.
        InkPackImports.attach(context)
        if (withContext(Dispatchers.IO) { InkPackImports.covers(context, tag) }) {
            InkPackImports.waiving { fetch(context, tag, onProgress) }
            return
        }
        // Without the internet permission ML Kit's downloader refuses to
        // start, and OkHttp's lookup throws a SecurityException (#292).
        InternetPermission.check()
        fetch(context, tag, onProgress)
    }

    private suspend fun fetch(
        context: Context,
        tag: String,
        onProgress: (HandwritingDownloadProgress) -> Unit,
    ) {
        val moduleHere = FeatureModules.handwriting.awaitInstalled { bytes, total ->
            onProgress(HandwritingDownloadProgress(bytes = bytes, totalBytes = total))
        }
        if (!moduleHere) throw IOException("The handwriting module did not arrive from Google Play")
        val engine = runtime() ?: throw IOException("The handwriting module is not loadable yet")
        engine.prepare()
        val stores = modelStoreDirs(context)
        val total = HandwritingModelSizes.installedBytes(context, tag)
        // Everything already in there belongs to models downloaded before
        // this one, so measure the growth rather than the total.
        val baseline = bytesOnDisk(stores)
        val task = engine.startDownload(tag)
        val startedAt = SystemClock.elapsedRealtime()
        var lastBytes = 0L
        var lastGrewAt = startedAt
        while (!task.isComplete) {
            delay(PROGRESS_POLL_MS)
            // The files being there is the fact that matters; the Task
            // agreeing is a nicety we cannot wait on forever.
            if (isDownloaded(tag)) return
            val now = SystemClock.elapsedRealtime()
            val bytes = (bytesOnDisk(stores) - baseline).coerceAtLeast(0L)
            if (bytes > lastBytes) {
                lastBytes = bytes
                lastGrewAt = now
            }
            // Report the high-water mark, not the instant reading: a pack
            // arrives as a zip and is then unpacked beside it, so the
            // directory briefly holds both and shrinks again when the zip
            // goes. A bar that ran backwards there would read as a fault.
            val reported = if (total > 0L) lastBytes.coerceAtMost(total) else lastBytes
            onProgress(
                HandwritingDownloadProgress(
                    bytes = reported,
                    totalBytes = total,
                    bytesPerSecond = lastBytes * MILLIS_PER_SECOND / (now - startedAt).coerceAtLeast(1L),
                    stalledForMs = now - lastGrewAt,
                ),
            )
            if (now - lastGrewAt > DOWNLOAD_GIVE_UP_MS) {
                // Drop the half-written file group: left in place, the next
                // attempt rejoins this stuck download instead of starting a
                // new one, which is how one failure becomes a permanent one.
                delete(tag)
                throw IOException("Handwriting model $tag stopped downloading")
            }
        }
        val failure = runCancellable { task.await() }.exceptionOrNull() ?: return
        // Same clean-up, unless the model is actually here and only the
        // update check failed — deleting a working model over a flaky
        // network would be the worse bug.
        if (!isDownloaded(tag)) delete(tag)
        throw failure
    }

    /**
     * Where Mobile Data Download keeps ink models. 19.0.0 writes them under
     * `<filesDir>/mlkit_digital_ink_recognition/shared/datadownload/public`,
     * named after its MDD instance. Watching only a bare `datadownload` found
     * nothing there, so every download looked stalled at zero bytes and was
     * called off, and its files deleted, after [DOWNLOAD_GIVE_UP_MS]. The bare
     * directory stays on the list in case a later release moves it back; the
     * ones that do not exist simply weigh nothing.
     */
    private fun modelStoreDirs(context: Context): List<File> =
        listOf(context.filesDir, context.noBackupFilesDir, context.cacheDir)
            .filterNotNull()
            .distinct()
            .flatMap { listOf(File(it, MDD_INSTANCE_DIRECTORY), File(it, MDD_DIRECTORY)) }

    private suspend fun bytesOnDisk(dirs: List<File>): Long = withContext(Dispatchers.IO) {
        runCancellable {
            dirs.sumOf { dir ->
                if (!dir.isDirectory) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }
        }.getOrDefault(0L)
    }

    suspend fun delete(tag: String) {
        if (ZinniaModels.isTag(tag)) return ZinniaModels.delete(tag)
        if (InkOcrModels.isTag(tag)) return InkOcrModels.delete(tag)
        if (!hasModel(tag)) return
        val engine = runtime() ?: return
        engine.prepare()
        engine.delete(tag)
    }
}

data class HandwritingLanguage(val tag: String, val displayName: String)

/**
 * A recognizer for the active language. Holds one recognizer at a time, ML
 * Kit's, zinnia's or PaddleOCR's; switching languages closes the old one.
 * All calls are main-thread safe — ML Kit runs recognition on its own
 * executor, the other two on the default dispatcher.
 */
class HandwritingRecognizerCache {

    /**
     * The keyboard's word lists, for PaddleOCR's reader to weigh its
     * readings against ([InkOcrDecoder]); ML Kit and zinnia read without
     * them. Null reads plainly.
     */
    @Volatile
    var lexicon: InkLexicon? = null

    private var recognizer: InkRuntime.Recognizer? = null
    private var recognizerTag: String? = null

    private fun recognizerFor(tag: String, open: () -> InkRuntime.Recognizer?): InkRuntime.Recognizer? {
        if (recognizerTag == tag) return recognizer
        close()
        recognizer = open() ?: return null
        recognizerTag = tag
        return recognizer
    }

    /**
     * Recognizes [strokes] as text. [preContext] is the text before the
     * cursor (last ~20 chars) and [writingAreaWidth]/[writingAreaHeight]
     * the canvas size in px — both improve accuracy (case, segmentation).
     * Returns candidate texts, best first; empty when nothing is recognized.
     * Throws when the model is missing or recognition fails.
     */
    suspend fun recognize(
        tag: String,
        strokes: List<HwStroke>,
        preContext: String,
        writingAreaWidth: Float,
        writingAreaHeight: Float,
        maxCandidates: Int = 4,
    ): List<String> {
        if (strokes.isEmpty()) return emptyList()
        // Bracketed so the elvis covers every branch, not only the last.
        val recognizer = (
            if (ZinniaModels.isTag(tag)) {
                recognizerFor(tag) { ZinniaModels.recognizer(tag) }
            } else if (InkOcrModels.isTag(tag)) {
                recognizerFor(tag) { InkOcrModels.recognizer(tag) }?.also { (it as? InkOcrRecognizer)?.lexicon = lexicon }
            } else {
                val engine = HandwritingModels.runtimeForRecognition() ?: error("Handwriting runtime is not here")
                HandwritingModels.prepare(engine)
                recognizerFor(tag) { engine.recognizer(tag) }
            }
            ) ?: error("No recognizer for $tag")
        return recognizer.recognize(strokes, preContext, writingAreaWidth, writingAreaHeight)
            .filter { it.isNotEmpty() }
            .distinct()
            .take(maxCandidates)
    }

    fun close() {
        recognizer?.close()
        recognizer = null
        recognizerTag = null
    }
}
