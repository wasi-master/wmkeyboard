package com.wasimaster.wmkeyboard.core.handwriting

import android.content.Context
import android.os.SystemClock
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import com.wasimaster.wmkeyboard.core.script.LanguageDef
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.util.runCancellable
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One sampled point of a handwriting stroke, in canvas pixels. */
data class HwPoint(val x: Float, val y: Float, val t: Long)

/** A finished stroke: the points between one touch-down and its release. */
data class HwStroke(val points: List<HwPoint>)

/**
 * How far a model download has got. ML Kit reports nothing at all — it hands
 * back a single Task that either completes or does not — so these numbers are
 * measured rather than reported: Mobile Data Download writes the model into
 * the app's own `datadownload` directory as it goes, and that directory
 * growing *is* the download working.
 *
 * There is deliberately no total. Nothing in ML Kit's API says how large a
 * model will be, and a bar filling against a guessed size is a worse lie than
 * a megabyte counter that is always true.
 */
data class HandwritingDownloadProgress(
    /** Bytes this download has written so far. */
    val bytes: Long = 0,
    /** Mean speed since the download started; 0 before the first byte. */
    val bytesPerSecond: Long = 0,
    /** How long the byte count has stood still. */
    val stalledForMs: Long = 0,
)

/** Awaits a Play-services Task without the coroutines-play-services artifact. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    addOnCanceledListener { if (cont.isActive) cont.cancel() }
}

/**
 * ML Kit Digital Ink model catalog and download management. Shared by the
 * IME (recognition, in-panel download) and the settings app (model manager),
 * so both always agree on which models exist and their language tags.
 */
object HandwritingModels {

    /**
     * ML Kit's own catalogue, minus the non-language recognizers (autodraw,
     * emoji, shapes) and the gesture models, which all carry an `-x-` private
     * subtag. Read once — it is a static table inside the library.
     */
    private val allIdentifiers: List<DigitalInkRecognitionModelIdentifier> by lazy {
        runCatching {
            DigitalInkRecognitionModelIdentifier.allModelIdentifiers()
                .filter { !it.languageTag.contains("-x-") }
        }.getOrDefault(emptyList())
    }

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
     * when ML Kit has no model for it. Among a language's variants: the one
     * matching the language's own locale wins, then the bare tag, then one
     * written in the language's own script — that last step is what keeps
     * Sanskrit on `sa-Deva-IN` rather than the romanised `sa-Latn`.
     */
    fun tagFor(language: LanguageDef): String? {
        val subtag = SUBTAG_ALIASES[language.id] ?: language.id
        val candidates = allIdentifiers.filter { it.languageSubtag == subtag }
        if (candidates.isEmpty()) return null
        val locale = language.localeTag.lowercase()
        val region = locale.substringAfter('-', "")
        val script = SCRIPT_CODES[language.script]
        return candidates.firstOrNull { it.languageTag.lowercase() == locale }?.languageTag
            ?: candidates.firstOrNull { it.languageTag.lowercase() == subtag }?.languageTag
            ?: candidates.filter { it.scriptSubtag == script }
                .minByOrNull { it.languageTag.length }?.languageTag
            ?: candidates.firstOrNull {
                it.scriptSubtag.isNullOrEmpty() && it.regionSubtag.orEmpty().lowercase() == region
            }?.languageTag
            ?: candidates.minByOrNull { it.languageTag.length }?.languageTag
    }

    /** The recognition model tag for a language id; falls back to the id itself. */
    fun tagForLangId(langId: String): String =
        tagFor(LanguageRegistry.byId(langId)) ?: if (langId == "en") "en-US" else langId

    /**
     * The models worth offering: one per language the user actually types in,
     * skipping the ones ML Kit cannot recognize. This is what the settings
     * screen lists, so enabling a language is all it takes for its model to
     * show up there.
     */
    fun modelsFor(languages: List<LanguageDef>): List<HandwritingLanguage> =
        languages.distinctBy { it.id }.mapNotNull { language ->
            tagFor(language)?.let { HandwritingLanguage(it, language.displayName) }
        }.distinctBy { it.tag }

    /**
     * Compact badge for the in-panel language toggle. The keyboard's own
     * language id rather than ML Kit's tag, so the badge reads NB and TL where
     * the model is called `no` and `fil-Latn`.
     */
    fun shortLabel(tag: String): String =
        (languageForTag(tag)?.id ?: tag.substringBefore('-')).uppercase()

    fun displayName(tag: String): String = languageForTag(tag)?.displayName ?: tag

    private fun languageForTag(tag: String): LanguageDef? {
        val subtag = tag.substringBefore('-')
        return LanguageRegistry.all.firstOrNull { (SUBTAG_ALIASES[it.id] ?: it.id) == subtag }
    }

    /**
     * Lazy, not eager: `getInstance` throws when ML Kit's init provider was
     * skipped (see MlKitInit), and a throw out of this object's initializer
     * poisons the class for the whole process — every later touch of the
     * catalogue, even the parts that never go near ML Kit, would then fail
     * with NoClassDefFoundError. Deferring it keeps the damage inside the
     * three download calls that actually need the manager.
     */
    private val manager: RemoteModelManager by lazy { RemoteModelManager.getInstance() }

    /** Mobile Data Download's own directory name, under one of the app dirs. */
    private const val MDD_DIRECTORY = "datadownload"
    private const val PROGRESS_POLL_MS = 1_000L
    private const val MILLIS_PER_SECOND = 1_000L

    /**
     * How long a download may bring in nothing before the panel calls it off.
     * Generous: ink models arrive in bursts with real gaps between them.
     */
    private const val DOWNLOAD_GIVE_UP_MS = 90_000L

    /** A "is it here?" check should be instant; this is only a backstop. */
    private const val STATUS_TIMEOUT_MS = 15_000L

    fun model(tag: String): DigitalInkRecognitionModel? {
        val identifier = runCatching {
            DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag)
        }.getOrNull() ?: return null
        return DigitalInkRecognitionModel.builder(identifier).build()
    }

    suspend fun isDownloaded(tag: String): Boolean {
        val model = model(tag) ?: return false
        // Bounded for the same reason the download below is: this Task comes
        // out of the same machinery, and a status check that never answers
        // strands the panel on its "checking" spinner.
        return withTimeoutOrNull(STATUS_TIMEOUT_MS) {
            runCancellable { manager.isModelDownloaded(model).await() }.getOrDefault(false)
        } ?: false
    }

    /**
     * Downloads the model for [tag], reporting measured progress through
     * [onProgress] roughly once a second. Throws on failure (no network, no
     * space) and on a download that stops making progress.
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
        val model = model(tag) ?: throw IllegalArgumentException("No model for $tag")
        val stores = modelStoreDirs(context)
        // Everything already in there belongs to models downloaded before
        // this one, so measure the growth rather than the total.
        val baseline = bytesOnDisk(stores)
        val task = manager.download(model, DownloadConditions.Builder().build())
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
            onProgress(
                HandwritingDownloadProgress(
                    bytes = bytes,
                    bytesPerSecond = bytes * MILLIS_PER_SECOND / (now - startedAt).coerceAtLeast(1L),
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
     * Where Mobile Data Download keeps ink models. ML Kit builds this as
     * `<filesDir>/datadownload`, but which base directory it starts from has
     * moved between releases, so watch every plausible one — the ones that do
     * not exist simply weigh nothing.
     */
    private fun modelStoreDirs(context: Context): List<File> =
        listOf(context.filesDir, context.noBackupFilesDir, context.cacheDir)
            .filterNotNull()
            .distinct()
            .map { File(it, MDD_DIRECTORY) }

    private suspend fun bytesOnDisk(dirs: List<File>): Long = withContext(Dispatchers.IO) {
        runCancellable {
            dirs.sumOf { dir ->
                if (!dir.isDirectory) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }
        }.getOrDefault(0L)
    }

    suspend fun delete(tag: String) {
        val model = model(tag) ?: return
        runCancellable { manager.deleteDownloadedModel(model).await() }
    }
}

data class HandwritingLanguage(val tag: String, val displayName: String)

/**
 * A recognizer for the active language. Holds one ML Kit recognizer at a
 * time; switching languages closes the old one. All calls are main-thread
 * safe — ML Kit runs recognition on its own executor.
 */
class HandwritingRecognizerCache {

    private var recognizer: DigitalInkRecognizer? = null
    private var recognizerTag: String? = null

    private fun recognizerFor(tag: String): DigitalInkRecognizer? {
        if (recognizerTag == tag) return recognizer
        recognizer?.close()
        recognizer = null
        recognizerTag = null
        val model = HandwritingModels.model(tag) ?: return null
        recognizer = DigitalInkRecognition.getClient(
            DigitalInkRecognizerOptions.builder(model).build()
        )
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
        val recognizer = recognizerFor(tag)
            ?: error("No recognizer for $tag")
        val inkBuilder = Ink.builder()
        for (stroke in strokes) {
            val strokeBuilder = Ink.Stroke.builder()
            for (point in stroke.points) {
                strokeBuilder.addPoint(Ink.Point.create(point.x, point.y, point.t))
            }
            inkBuilder.addStroke(strokeBuilder.build())
        }
        val contextBuilder = RecognitionContext.builder()
            // ML Kit caps pre-context at 20 chars; longer values throw.
            .setPreContext(preContext.takeLast(20))
        if (writingAreaWidth > 0f && writingAreaHeight > 0f) {
            contextBuilder.setWritingArea(WritingArea(writingAreaWidth, writingAreaHeight))
        }
        val result = recognizer.recognize(inkBuilder.build(), contextBuilder.build()).await()
        return result.candidates
            .map { it.text }
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
