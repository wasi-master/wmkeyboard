package com.wasimaster.wmkeyboard.core.handwriting

import android.content.Context
import com.wasimaster.wmkeyboard.core.download.DownloadException
import com.wasimaster.wmkeyboard.core.download.FileDownloader
import com.wasimaster.wmkeyboard.core.endpoints.GitForge
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.modules.NativeConsent
import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.script.LanguageDef
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.intelligence.R
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handwriting for the scripts zinnia does not read, where ML Kit is not in
 * the build (F-Droid's) or has no model: PaddleOCR's PP-OCRv5 line
 * recognisers, one per script, reading the ink drawn as a line of text
 * ([InkOcrInk], [InkOcrRecognizer]). [HandwritingModels] routes every tag
 * that starts with [PREFIX] here, so the panel, the settings screen and the
 * service treat one of these exactly like an ML Kit model.
 *
 * Text recognisers, not handwriting ones: no open model reads pen strokes
 * for these languages, and these read handwriting well enough for the
 * keyboard's dictionaries to make up much of the rest ([InkOcrDecoder]).
 * About half of all words came out right on messy traced English
 * (2026-10-25), so ML Kit stays the first choice wherever it is here.
 *
 * A model is ready when two things are on the device: MNN, which runs it
 * (libwmimagegen, gate [FeatureModules.imagegen], shared with the sticker
 * generator and the F-Droid scanner), and the [Pack]'s two files, the graph
 * and its character list, from the data repository's [RELEASE_TAG] release,
 * pinned by size and SHA-256. One download fetches all of it, library first.
 * Every language of a script shares its pack: the list in the settings shows
 * one row per script, for the scripts of the languages the user types in.
 *
 * Exported by the data repository's `scripts/export_ppocrv5_ink_mnn.py` from
 * PaddlePaddle's own weights (Apache-2.0), pinned to their Hugging Face
 * commits there. The language lists are PaddleOCR's own for each model,
 * narrowed to the script the keyboard writes the language in.
 */
object InkOcrModels {

    const val PREFIX = "inkocr-"

    /** The data repository's release the packs are assets of. */
    const val RELEASE_TAG = "models"

    /** One published file. */
    class Part(val fileName: String, val bytes: Long, val sha256: String)

    /** Latin letters a Cyrillic recogniser reads in place of their Cyrillic look-alikes. */
    private val CYRILLIC_FOLDS = mapOf(
        "a" to "а", "c" to "с", "e" to "е", "o" to "о", "p" to "р", "x" to "х", "y" to "у",
        "A" to "А", "B" to "В", "C" to "С", "E" to "Е", "H" to "Н", "K" to "К", "M" to "М",
        "O" to "О", "P" to "Р", "T" to "Т", "X" to "Х",
    )

    /** The same for the Greek recogniser. */
    private val GREEK_FOLDS = mapOf(
        "o" to "ο", "A" to "Α", "B" to "Β", "E" to "Ε", "Z" to "Ζ", "H" to "Η", "I" to "Ι",
        "K" to "Κ", "M" to "Μ", "N" to "Ν", "O" to "Ο", "P" to "Ρ", "T" to "Τ", "Y" to "Υ", "X" to "Χ",
    )

    /**
     * One script's recogniser. [languages] are the keyboard's language ids
     * it reads, of those written in [script].
     */
    enum class Pack(
        val id: String,
        val displayName: String,
        val shortLabel: String,
        val script: ScriptId,
        val languages: Set<String>,
        val model: Part,
        val keys: Part,
        val rightToLeft: Boolean = false,
        val cased: Boolean = false,
        val folds: Map<String, String> = emptyMap(),
        /** Whether the keyboard's word lists can weigh its readings: not for a script written without spaces. */
        val lexicon: Boolean = true,
    ) {
        LATIN(
            "latin", "Latin script", "ABC", ScriptId.LATIN,
            setOf(
                "af", "sq", "az", "eu", "bs", "ca", "hr", "cs", "da", "nl", "en", "et", "fi", "fr", "gl",
                "de", "hu", "is", "id", "ga", "it", "ku", "la", "lv", "lt", "lb", "ms", "mt", "mi", "nb",
                "no", "nn", "oc", "pi", "pl", "pt", "qu", "ro", "rm", "sk", "sl", "es", "sw", "sv", "tl",
                "fil", "tr", "uz", "vi", "cy",
            ),
            Part("ppocrv5-ink-latin.mnn", 8_010_784L, "63caead36bedc97f0012d625c4d042e6f07b9db99c8c57749f3a7ae248f9033f"),
            Part("ppocrv5-ink-latin-keys.txt", 2_616L, "ccbcc45730b3fbbd9050c5bc74db6a99067141ef1035e3d14889a84a6b9b1aff"),
            cased = true,
        ),
        EAST_SLAVIC(
            "eslav", "Русский, українська, беларуская · East Slavic", "АБВ", ScriptId.CYRILLIC,
            setOf("ru", "uk", "be"),
            Part("ppocrv5-ink-eslav.mnn", 7_856_388L, "254079e8bdbc9ebcb8467da5817038e6518048606e26aa67a6a40c3040825a2a"),
            Part("ppocrv5-ink-eslav-keys.txt", 1_663L, "3e95f1581557162870cacdba5af91a4c6be2890710d395b0c3c7578e7ee5e6eb"),
            cased = true,
            folds = CYRILLIC_FOLDS,
        ),
        CYRILLIC(
            "cyrillic", "Кирилица · Cyrillic script", "КИР", ScriptId.CYRILLIC,
            setOf(
                "ab", "ady", "av", "ba", "bua", "bg", "ce", "cv", "dar", "inh", "kbd", "xal", "kaa", "kk",
                "kv", "ky", "lbe", "lez", "mk", "mn", "os", "sah", "sr", "tab", "tg", "tt", "tyv", "udm",
            ),
            Part("ppocrv5-ink-cyrillic.mnn", 8_017_560L, "b28d8ce7f2ae337e344cc1d645aee0646c6a186eac0c44c874ee9c9abebe412a"),
            Part("ppocrv5-ink-cyrillic-keys.txt", 2_781L, "db40aa52ceb112055be80c694afdf655d5d2c4f7873704524cc16a447ca913ba"),
            cased = true,
            folds = CYRILLIC_FOLDS,
        ),
        ARABIC(
            "arabic", "العربية · Arabic script", "عرب", ScriptId.ARABIC,
            setOf("ar", "bal", "ckb", "ku", "ps", "fa", "sd", "ur", "ug"),
            Part("ppocrv5-ink-arabic.mnn", 7_967_708L, "ac88964e36ef6a11c5fbc21b167b4f16cfb54a45c39f91fa843cda0cace6000e"),
            Part("ppocrv5-ink-arabic-keys.txt", 2_369L, "7f92f7dbb9b75a4787a83bfb4f6d14a8ab515525130c9d40a9036f61cf6999e9"),
            rightToLeft = true,
        ),
        DEVANAGARI(
            "devanagari", "देवनागरी · Devanagari", "देव", ScriptId.DEVANAGARI,
            setOf("anp", "bho", "bh", "bgc", "hi", "kok", "gom", "mag", "mai", "mr", "ne", "new", "sa", "sat"),
            Part("ppocrv5-ink-devanagari.mnn", 7_881_072L, "fdc348f16380e2b6bb6f96ad96b7841d0b5d0f1121ebb9d317f49c84caebd39b"),
            Part("ppocrv5-ink-devanagari-keys.txt", 1_943L, "09c7440bfc5477e5c41052304b6b185aff8c4a5e8b2b4c23c1c706f6fe1ee9fc"),
        ),
        TAMIL(
            "ta", "தமிழ் · Tamil", "தமி", ScriptId.TAMIL,
            setOf("ta"),
            Part("ppocrv5-ink-ta.mnn", 7_854_452L, "74856f3a83f36645861d1314ef8156a03a7bc1d908e9f436580ad1d1ef47e2ac"),
            Part("ppocrv5-ink-ta-keys.txt", 1_723L, "85b541352ae18dc6ba6d47152d8bf8adff6b0266e605d2eef2990c1bf466117b"),
        ),
        TELUGU(
            "te", "తెలుగు · Telugu", "తెలు", ScriptId.TELUGU,
            setOf("te"),
            Part("ppocrv5-ink-te.mnn", 7_867_520L, "624000d6d7da4c2925ae4757557a65b0619d5f64d72ac63277c0071ca4b3090e"),
            Part("ppocrv5-ink-te-keys.txt", 1_831L, "42f83f5d3fdb50778e4fa5b66c58d99a59ab7792151c5e74f34b8ffd7b61c9d6"),
        ),
        THAI(
            "th", "ไทย · Thai", "ไทย", ScriptId.THAI,
            setOf("th"),
            Part("ppocrv5-ink-th.mnn", 7_859_776L, "35f3b54ed562c6ad16efe962b92ffca1603f9c481f1c123f12a18c855811b3ef"),
            Part("ppocrv5-ink-th-keys.txt", 1_767L, "57f5406f94bb6688fb7077f7be65f08bbd71cecf48c01ea26c522cb5c4836b7a"),
            lexicon = false,
        ),
        GREEK(
            "el", "Ελληνικά · Greek", "ΑΒΓ", ScriptId.GREEK,
            setOf("el"),
            Part("ppocrv5-ink-el.mnn", 7_777_496L, "d7cd3c487a6696ff1d9e335df36ec5f0907488174101fab3436dd23443456f0e"),
            Part("ppocrv5-ink-el-keys.txt", 1_103L, "31defc62c0c3ad3674a82da6192226a2ba98ef4ff014a7045cb88d59f9c3de31"),
            cased = true,
            folds = GREEK_FOLDS,
        ),
        KOREAN(
            "korean", "한국어 · Korean", "한", ScriptId.HANGUL,
            setOf("ko"),
            Part("ppocrv5-ink-korean.mnn", 13_387_540L, "45108b8bde1b9011ca1d380ceacc0009f05d137ddb2a7aa0d0501c1675ada1f9"),
            Part("ppocrv5-ink-korean-keys.txt", 47_451L, "a88071c68c01707489baa79ebe0405b7beb5cca229f4fc94cc3ef992328802d7"),
        ),
        ;

        val tag: String get() = PREFIX + id
        val parts: List<Part> get() = listOf(model, keys)
        val bytes: Long get() = model.bytes + keys.bytes
    }

    @Volatile
    private var filesDir: File? = null

    /** Where the packs live; set once at startup, before any status check. */
    fun attach(filesDir: File) {
        this.filesDir = filesDir
    }

    fun isTag(tag: String): Boolean = tag.startsWith(PREFIX)

    fun packFor(tag: String): Pack? = Pack.entries.firstOrNull { it.tag == tag }

    /** The pack [language] writes with, or null for a language none of them reads. */
    fun packFor(language: LanguageDef): Pack? =
        Pack.entries.firstOrNull { language.script == it.script && language.id in it.languages }

    fun tagFor(language: LanguageDef): String? = packFor(language)?.tag

    fun displayName(tag: String): String = packFor(tag)?.displayName ?: tag

    fun shortLabel(tag: String): String = packFor(tag)?.shortLabel ?: tag.removePrefix(PREFIX).uppercase()

    /** What the download brings in, which is also its size on disk. */
    fun installedBytes(tag: String): Long = packFor(tag)?.bytes ?: 0L

    fun dir(filesDir: File): File = File(filesDir, "handwriting/inkocr")

    fun file(filesDir: File, part: Part): File = File(dir(filesDir), part.fileName)

    /**
     * Where [part] downloads from: the data repository's [RELEASE_TAG]
     * release, as `<sha256>-<name>`. Null when the data repository is not
     * on GitHub.
     */
    fun url(part: Part): String? {
        val repo = ServiceEndpoints.repo(ServiceRepo.DATA)
        if (repo.forge != GitForge.GITHUB) return null
        return "https://${repo.host}/${repo.owner}/${repo.repo}/releases/download/$RELEASE_TAG/${part.sha256}-${part.fileName}"
    }

    /** Where [tag]'s files are published, for the offline import's list of links. */
    fun packUrls(tag: String): List<String> = packFor(tag)?.parts?.mapNotNull(::url).orEmpty()

    /** The part a file named [name] is, by its published name with or without the digest in front. */
    fun partOf(name: String): Part? {
        val leaf = name.substringAfterLast('/')
        return Pack.entries.flatMap { it.parts }.firstOrNull { leaf == it.fileName || leaf == "${it.sha256}-${it.fileName}" }
    }

    /** The library is loadable and both of [tag]'s files are on disk. */
    suspend fun isDownloaded(tag: String): Boolean {
        val pack = packFor(tag) ?: return false
        if (!FeatureModules.imagegen.installed) return false
        val dir = filesDir ?: return false
        return withContext(Dispatchers.IO) { pack.parts.all { file(dir, it).isFile } }
    }

    /**
     * Fetches whatever [tag] still lacks: MNN's library through its gate,
     * then the pack's two files, one progress readout for all of it. Throws
     * [HandwritingDownloadException] with a message for the user when
     * downloads of libraries are not allowed on this build yet, an
     * [IOException] on any other failure.
     */
    suspend fun download(context: Context, tag: String, onProgress: (HandwritingDownloadProgress) -> Unit) {
        val pack = packFor(tag) ?: throw IOException("No ink model for $tag")
        val gate = FeatureModules.imagegen
        if (!gate.installed) {
            val here = HandwritingModels.awaitLibrary(gate, onProgress)
            if (!here) {
                val state = gate.state.value
                if (state is ModuleState.Missing && state.consent != null) {
                    val message = if (state.consent == NativeConsent.IMPORT) {
                        R.string.core_intel_handwriting_inkocr_import_needed
                    } else {
                        R.string.core_intel_handwriting_inkocr_consent_needed
                    }
                    throw HandwritingDownloadException(context.getString(message))
                }
                throw IOException("The MNN library did not arrive")
            }
        }
        val dir = dir(context.filesDir).apply { mkdirs() }
        val missing = pack.parts.filter { !file(context.filesDir, it).isFile }
        if (missing.isEmpty()) return
        InternetPermission.check()
        var done = pack.bytes - missing.sumOf { it.bytes }
        for (part in missing) {
            val url = url(part) ?: throw IOException("No download source for ${part.fileName}")
            try {
                FileDownloader.fetchVerified(
                    url = url,
                    target = File(dir, part.fileName),
                    size = part.bytes,
                    sha256 = part.sha256,
                    source = NetSource.DOWNLOAD_HANDWRITING,
                    what = part.fileName,
                ) { written -> onProgress(HandwritingDownloadProgress(bytes = done + written, totalBytes = pack.bytes)) }
            } catch (e: DownloadException) {
                throw IOException("${part.fileName} did not download", e)
            }
            done += part.bytes
        }
    }

    /**
     * Installs one of the packs' files from a copy fetched by hand. False
     * when it is not the published file, which is then left out. Runs on
     * the caller's thread.
     */
    fun install(filesDir: File, part: Part, source: File): Boolean {
        if (FileDownloader.sha256(source) != part.sha256) return false
        val target = file(filesDir, part)
        target.parentFile?.mkdirs()
        val staged = File(target.parentFile, "${part.fileName}.part")
        try {
            source.inputStream().use { input -> staged.outputStream().use { input.copyTo(it) } }
            target.delete()
            check(staged.renameTo(target)) { "could not move the file into place" }
        } finally {
            staged.delete()
        }
        return true
    }

    /** The pack whose file [part] is. */
    fun packOf(part: Part): Pack? = Pack.entries.firstOrNull { part in it.parts }

    /** Removes [tag]'s files; the library stays, shared with the sticker generator and the scanner. */
    fun delete(tag: String) {
        val dir = filesDir ?: return
        val pack = packFor(tag) ?: return
        pack.parts.forEach {
            file(dir, it).delete()
            FileDownloader.discard(File(dir(dir), "${it.fileName}.part"))
        }
    }

    /** A recogniser over [tag]'s pack; null while the library or a file is missing. */
    internal fun recognizer(tag: String): InkOcrRecognizer? {
        val pack = packFor(tag) ?: return null
        val dir = filesDir ?: return null
        val model = file(dir, pack.model)
        val keys = file(dir, pack.keys)
        if (!model.isFile || !keys.isFile) return null
        return InkOcrRecognizer.open(pack, model, keys)
    }
}
