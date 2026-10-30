package com.wasimaster.wmkeyboard.core.voice.punctuation

import com.wasimaster.wmkeyboard.core.download.FileDownloader
import android.os.StatFs
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.endpoints.GitForge
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.text.GemmaTokenizer
import com.wasimaster.wmkeyboard.core.voice.executorch.EtCatalog
import com.wasimaster.wmkeyboard.core.voice.executorch.EtDownloadManager.Status
import com.wasimaster.wmkeyboard.core.voice.executorch.EtModel
import com.wasimaster.wmkeyboard.core.voice.executorch.EtStore
import com.wasimaster.wmkeyboard.core.voice.executorch.PinnedDownload
import com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaNative
import com.wasimaster.wmkeyboard.voice.R
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Add punctuation and capitals: full stops, commas, question marks and
 * sentence capitals for the offline engines that write bare words (Sherpa's
 * transducers but for its German, Spanish, Korean and eight-language models,
 * and Omnilingual), in 88 languages.
 *
 * The model is Horizon-Labs' punctuation-restoration-small (mmBERT-small
 * token classification, Apache-2.0), requantised by the data repository's
 * `scripts/export_punctuation.py`: an int8 token table and 8-bit weight-only
 * MatMuls, its labels the fp32 model's. Measured on a Mac before it was
 * chosen, against 1-800-BAD-CODE's punct_cap_seg and xlm-roberta models and
 * sherpa-onnx's CT-Transformer, with punctuation and case stripped from
 * held-out text and restored (FLORES-200 paragraphs, Tatoeba questions):
 * the best marks in all eight languages tried on FLORES, English 0.81,
 * Bengali 0.73, Hindi 0.78, Spanish 0.84, Arabic 0.73, Chinese 0.83 F1, at
 * 7 to 37 ms a phrase on two cores. See `native/sherpa-onnx-jni/README.md`.
 *
 * It writes no capitals, so a sentence start gets one by rule, and English
 * names get the ones of Sherpa's English punctuation model when that is on
 * the phone too (case right on 97% of FLORES words, against 92% by rule
 * alone); the keyboard spells the rest of the names its word lists know when
 * the phrase lands. Without this model, English still has that one.
 *
 * About 151 MB from the data repository's `models` release, pinned by size
 * and SHA-256; the weights are mapped rather than copied, so a loaded model
 * costs about 90 MB. Runs on [SherpaNative.dispatcher].
 */
object DictationPunctuation {

    /** The data repository's release the files are assets of. */
    const val RELEASE_TAG = "models"

    /** Smallest first: a bad connection fails before the big one. */
    enum class Part(val fileName: String, val bytes: Long, val sha256: String) {
        GRAPH(
            "punct-mmbert-small.onnx", 588_015L,
            "1d0ac3af30fa5de2c913f3ab80b26a94a790a988a270adcb239a3a5248f0efe7",
        ),
        TOKENIZER(
            "punct-mmbert-small.model", 4_747_941L,
            "13cb25eeae71780a9e52d3771ebbc59a7a0eb2274ebcb20e203d68460f93a6d4",
        ),

        /** The graph's weights, which it names as its external data: the file name must stay. */
        WEIGHTS(
            "punct-mmbert-small.onnx.data", 145_913_856L,
            "1927826af2b8dae8b879aec82647c74fd26ca79645fb300934607e60f0f526ae",
        ),
    }

    /** Every byte of the model, for the settings row. */
    val BYTES: Long = Part.entries.sumOf { it.bytes }

    /**
     * The 88 languages the model was trained in, as keyboard base codes.
     * Thai, Lao, Khmer, Burmese and Tibetan are not among them.
     */
    val LANGUAGES: Set<String> = setOf(
        "af", "am", "ar", "az", "be", "bg", "bn", "bs", "ca", "ckb", "cs", "cy", "da", "de", "el", "en",
        "eo", "es", "et", "eu", "fa", "fi", "fo", "fr", "ga", "gl", "gu", "ha", "he", "hi", "hr", "hu",
        "hy", "id", "is", "it", "ja", "jv", "ka", "kk", "kn", "ko", "ky", "lb", "lt", "lv", "mk", "ml",
        "mn", "mr", "ms", "mt", "ne", "nl", "no", "or", "pa", "pl", "ps", "pt", "ro", "ru", "rw", "sd",
        "si", "sk", "sl", "so", "sq", "sr", "su", "sv", "sw", "ta", "te", "tg", "tl", "tr", "tt", "ug",
        "uk", "ur", "uz", "vi", "xh", "yo", "zh", "zu",
    )

    /** Keyboard codes the model knows by another name. */
    private val ALIASES = mapOf("nb" to "no", "nn" to "no", "fil" to "tl", "iw" to "he", "in" to "id")

    /** [languageId] (`en_GB`, `zh-TW`) as the base code [LANGUAGES] holds. */
    fun base(languageId: String): String {
        val code = languageId.trim().lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
        return ALIASES[code] ?: code
    }

    /** Whether the model punctuates [languageId]. */
    fun supports(languageId: String): Boolean = base(languageId) in LANGUAGES

    /**
     * Whether what [model] writes needs this: it spells words and nothing
     * else. Everything that punctuates for itself (Whisper, Whistle, Asr4All,
     * Parakeet, Moonshine, the system recognizer, a server) is never touched.
     */
    fun needed(model: EtModel): Boolean = !model.writesPunctuation

    // ---- Files ------------------------------------------------------------

    private const val DIR = "punctuation"
    private const val SPACE_MARGIN_BYTES = 32L * 1024 * 1024

    fun rootDir(filesDir: File): File = File(filesDir, DIR)

    fun file(filesDir: File, part: Part): File = File(rootDir(filesDir), part.fileName)

    /** Whole when every file is there at its pinned length; each was hash-checked before it got its name. */
    fun isDownloaded(filesDir: File): Boolean = Part.entries.all { file(filesDir, it).length() == it.bytes }

    fun bytesOnDisk(filesDir: File): Long =
        FileDownloader.bytesUnder(rootDir(filesDir))

    /** The release asset for [part], or null when the data repository is not on GitHub. */
    fun url(part: Part): String? {
        val repo = ServiceEndpoints.repo(ServiceRepo.DATA)
        if (repo.forge != GitForge.GITHUB) return null
        return "https://${repo.host}/${repo.owner}/${repo.repo}/releases/download/$RELEASE_TAG/${part.sha256}-${part.fileName}"
    }

    /** Whether this build can run it at all, counting a runtime not fetched yet. */
    val runtimeShipped: Boolean get() = SherpaNative.shipped

    // ---- Running ----------------------------------------------------------

    /** Intra-op threads; a phrase takes tens of milliseconds on two. */
    private const val THREADS = 2

    private var tokenizer: GemmaTokenizer? = null
    private var tagger = 0L
    private var english = 0L
    private var englishDir: String? = null

    /** A `libwmsherpa.so` from before the tagger was built into it. */
    @Volatile private var bridgeMissing = false

    /** Whether a model is loaded, so a memory trim knows there is something to free. Any thread. */
    @Volatile var isOpen: Boolean = false
        private set

    /**
     * [text], a finished phrase in [languageIds], with marks and sentence
     * capitals. The multilingual model when it is downloaded and knows every
     * one of the languages; for English alone, Sherpa's English model's
     * capitals over it, or that model on its own without it; [choice]
     * ([PunctuationChoice]) can rule either out, and a model the user imported
     * for these languages comes first. Otherwise [text] as it was. On
     * [SherpaNative.dispatcher] only.
     */
    fun punctuate(
        filesDir: File,
        text: String,
        languageIds: List<String>,
        choice: String = PunctuationChoice.AUTO,
    ): String {
        val languages = languageIds.map(::base).ifEmpty { listOf("en") }
        // A line break said aloud ("new line") is already in the text; each
        // line is a passage of its own.
        return text.split('\n').joinToString("\n") { line -> punctuateLine(filesDir, line, languages, choice) }
    }

    private fun punctuateLine(filesDir: File, line: String, languages: List<String>, choice: String): String {
        val raw = PunctuationText.normalize(line)
        if (raw.isEmpty()) return line
        PunctuationCustomModels.forLanguages(filesDir, languages)?.let { model ->
            onlinePunctuated(model.dir.path, model.graph, model.vocab, raw)?.let { return it }
        }
        val cased = if (choice != PunctuationChoice.MULTILINGUAL && languages.all { it == "en" }) englishCased(filesDir, raw) else null
        val handle = if (choice != PunctuationChoice.ENGLISH && languages.all { it in LANGUAGES }) taggerFor(filesDir) else 0L
        val tok = tokenizer
        if (handle == 0L || tok == null) return cased ?: line
        val encoded = PunctuationText.encode(raw, tok::encode, tok::piece)
        val labels = PunctuationText.labels(encoded.ids) { SherpaNative.nativeTag(handle, it, it.size) }
            ?: return cased ?: line
        val marks = PunctuationText.marks(raw, encoded.ends, labels)
        val names = cased?.let { PunctuationText.casedWords(raw, it) }.orEmpty()
        return PunctuationText.render(raw, marks, languages, names)
    }

    /** Frees both models; the next phrase loads them again. On [SherpaNative.dispatcher] only. */
    fun release() {
        if (tagger != 0L) SherpaNative.nativeTaggerFree(tagger)
        if (english != 0L) SherpaNative.nativePunctuationFree(english)
        tagger = 0L
        tokenizer = null
        english = 0L
        englishDir = null
        isOpen = false
    }

    private fun taggerFor(filesDir: File): Long {
        // Deleted since it was loaded: the mapping would still read, but the
        // user asked for it gone.
        if (!isDownloaded(filesDir)) {
            if (tagger != 0L) SherpaNative.nativeTaggerFree(tagger)
            tagger = 0L
            tokenizer = null
            return 0L
        }
        if (tagger != 0L) return tagger
        if (bridgeMissing || !SherpaNative.available) return 0L
        val tok = runCatching { GemmaTokenizer.read(file(filesDir, Part.TOKENIZER)) }.getOrNull() ?: return 0L
        val handle = try {
            SherpaNative.nativeTagger(file(filesDir, Part.GRAPH).path, THREADS)
        } catch (_: UnsatisfiedLinkError) {
            bridgeMissing = true
            0L
        }
        if (handle == 0L) return 0L
        tokenizer = tok
        tagger = handle
        isOpen = true
        return handle
    }

    /** [raw] through Sherpa's English model, when it is on the phone; null otherwise. */
    private fun englishCased(filesDir: File, raw: String): String? {
        val model = EtCatalog.sherpaPunctuation
        if (!EtStore.isDownloaded(filesDir, model)) return null
        return onlinePunctuated(
            EtStore.dir(filesDir, model).path,
            EtStore.file(filesDir, model, "model.int8.onnx"),
            EtStore.file(filesDir, model, "bpe.vocab"),
            raw,
        )
    }

    /** [raw] through the online punctuation model in [dir], loaded in place of whichever was. */
    private fun onlinePunctuated(dir: String, graph: File, vocab: File, raw: String): String? {
        if (!SherpaNative.available) return null
        if (english == 0L || englishDir != dir) {
            if (english != 0L) SherpaNative.nativePunctuationFree(english)
            english = SherpaNative.nativePunctuation(graph.path, vocab.path)
            englishDir = if (english != 0L) dir else null
        }
        if (english == 0L) return null
        isOpen = true
        return String(SherpaNative.nativePunctuate(english, raw.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    // ---- Downloading ------------------------------------------------------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _status = MutableStateFlow<Status>(Status.NotDownloaded)

    /** Where the model download stands, for the settings row. */
    val status: StateFlow<Status> = _status.asStateFlow()

    val isBusy: Boolean get() = job?.isActive == true

    /** Seeds [status] from disk; call when the screen appears. */
    fun refresh(filesDir: File) {
        if (isBusy) return
        val onDisk = bytesOnDisk(filesDir)
        _status.value = when {
            isDownloaded(filesDir) -> Status.Downloaded
            onDisk > 0 -> Status.Paused(onDisk, BYTES)
            else -> Status.NotDownloaded
        }
    }

    fun download(filesDir: File) {
        if (isBusy || isDownloaded(filesDir)) return
        _status.value = Status.Downloading(bytesOnDisk(filesDir), BYTES)
        job = scope.launch {
            try {
                val dir = rootDir(filesDir).apply { mkdirs() }
                val needed = BYTES - bytesOnDisk(filesDir) + SPACE_MARGIN_BYTES
                if (StatFs(dir.path).availableBytes < needed) {
                    throw PinnedDownload.FailedException(
                        R.string.core_voice_download_space_error,
                        (needed / 1e6).roundToInt().toString(),
                    )
                }
                var done = 0L
                for (part in Part.entries) {
                    val target = file(filesDir, part)
                    if (target.length() != part.bytes) {
                        val url = url(part) ?: throw PinnedDownload.FailedException(CommonR.string.common_error_network)
                        PinnedDownload.fetch(url, target, part.bytes, part.sha256, "$DIR/${part.fileName}", NetSource.DOWNLOAD_WHISPER) {
                            _status.value = Status.Downloading(done + it, BYTES)
                        }
                    }
                    done += part.bytes
                }
                _status.value = Status.Downloaded
            } catch (e: CancellationException) {
                val onDisk = bytesOnDisk(filesDir)
                _status.value = if (onDisk > 0) Status.Paused(onDisk, BYTES) else Status.NotDownloaded
                throw e
            } catch (e: PinnedDownload.FailedException) {
                _status.value = Status.Failed(e.messageRes, e.messageArg)
            } catch (_: Exception) {
                _status.value = Status.Failed(CommonR.string.common_error_network)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /**
     * Deletes the model. A loaded one keeps its mapped weights until it is
     * released, which the next memory trim or phrase does.
     */
    fun delete(filesDir: File) {
        cancel()
        rootDir(filesDir).listFiles { f -> !f.isDirectory }.orEmpty().forEach { it.delete() }
        _status.value = Status.NotDownloaded
    }

    /** [delete], and the models the user imported, which live in the same folder. */
    fun deleteAll(filesDir: File) {
        delete(filesDir)
        rootDir(filesDir).deleteRecursively()
        PunctuationCustomModels.load(filesDir, force = true)
    }
}
