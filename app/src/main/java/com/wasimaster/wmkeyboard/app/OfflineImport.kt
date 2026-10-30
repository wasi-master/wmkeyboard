package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.modules.NativeDelivery
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.dictionaries.AvroDictionaryDownloads
import com.wasimaster.wmkeyboard.core.dictionaries.CompoundLexiconDownloads
import com.wasimaster.wmkeyboard.core.dictionaries.ContextModelCatalog
import com.wasimaster.wmkeyboard.core.dictionaries.ContextModelDownloadManager
import com.wasimaster.wmkeyboard.core.dictionaries.DictionaryCatalog
import com.wasimaster.wmkeyboard.core.dictionaries.DictionaryEntry
import com.wasimaster.wmkeyboard.core.dictionaries.KhiproGlideDownloads
import com.wasimaster.wmkeyboard.core.dictionaries.NgramPackCatalog
import com.wasimaster.wmkeyboard.core.dictionaries.NgramPackDownloadManager
import com.wasimaster.wmkeyboard.core.dictionaries.NgramPackEntry
import com.wasimaster.wmkeyboard.core.dictionaries.PhoneticIndexDownloads
import com.wasimaster.wmkeyboard.core.dictionaries.WordlistDownloadManager
import com.wasimaster.wmkeyboard.core.dictionaries.WordlistSource
import com.wasimaster.wmkeyboard.core.emoji.EmojiDictCatalog
import com.wasimaster.wmkeyboard.core.emoji.EmojiDictDownloadManager
import com.wasimaster.wmkeyboard.core.gesture.latinime.LatinImeLibrary
import com.wasimaster.wmkeyboard.core.gesture.latinime.LatinImePins
import com.wasimaster.wmkeyboard.core.handwriting.HandwritingModels
import com.wasimaster.wmkeyboard.core.handwriting.InkOcrModels
import com.wasimaster.wmkeyboard.core.handwriting.InkPackImports
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictCatalog
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictDownloadManager
import com.wasimaster.wmkeyboard.core.input.rime.RimeRuntime
import com.wasimaster.wmkeyboard.core.input.rime.RimeStore
import com.wasimaster.wmkeyboard.core.localllm.GgufFile
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmCatalog
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmDownloadManager
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmStore
import com.wasimaster.wmkeyboard.core.localllm.ModelFormat
import com.wasimaster.wmkeyboard.core.ocr.OcrLanguages
import com.wasimaster.wmkeyboard.core.ocr.OcrPacks
import com.wasimaster.wmkeyboard.core.ocr.PaddleOcrModel
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.stickers.CutoutModel
import com.wasimaster.wmkeyboard.core.tools.TranslateClient
import com.wasimaster.wmkeyboard.core.translate.BergamotCatalog
import com.wasimaster.wmkeyboard.core.translate.BergamotTranslator
import com.wasimaster.wmkeyboard.core.translate.OfflineTranslation
import com.wasimaster.wmkeyboard.core.tools.offlinedict.OfflineDictionaries
import com.wasimaster.wmkeyboard.core.tools.offlinedict.OfflineDictionaryDownloads
import com.wasimaster.wmkeyboard.core.tools.offlinegif.OfflineGifPacks
import com.wasimaster.wmkeyboard.core.tools.stardict.StarDictArchive
import com.wasimaster.wmkeyboard.core.tools.yomichan.YomichanDictionaries
import com.wasimaster.wmkeyboard.core.tools.yomichan.YomichanImport
import com.wasimaster.wmkeyboard.core.transliteration.KhiproSpecs
import com.wasimaster.wmkeyboard.core.xlit.IndicXlitDownloads
import com.wasimaster.wmkeyboard.core.xlit.IndicXlitLatinDownloads
import com.wasimaster.wmkeyboard.core.xlit.IndicXlitModels
import com.wasimaster.wmkeyboard.core.voice.executorch.EtDownloadManager
import com.wasimaster.wmkeyboard.core.voice.executorch.EtImport
import com.wasimaster.wmkeyboard.core.voice.futoswipe.FutoSwipe
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperDownloadManager
import com.wasimaster.wmkeyboard.core.voice.whistle.WhistleModelDownloadManager
import com.wasimaster.wmkeyboard.core.voice.whistle.WhistleModelStore
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Installs files the user fetched on some other device or in a browser: the
 * way anything the app downloads can reach a phone that cannot download it
 * itself, above all a build with no internet permission.
 *
 * Each file is recognised by its name, which is the one it is published
 * under: a word list is `de_full.txt.gz` in the data repository and in a
 * language pack, a Whisper graph is the file Hugging Face serves. A zip is
 * opened and every file in it looked at the same way, whatever folders it
 * keeps them in, so a language pack (`wmkb-lang-de.zip`, holding the
 * language's word list, word pairs and emoji names) installs in one go. The
 * exception is a CleverKeys GIF pack, which is a zip that is itself the
 * thing installed ([OfflineGifPacks]), a Yomichan dictionary, and a RIME
 * schema zip of the user's own: any zip holding a `*.schema.yaml` that is
 * not one of the published packs, unpacked whole for the RIME engine.
 *
 * Every file goes through the same code its download would: the same parse,
 * the same checksum where the download has one, the same place on disk. So
 * nothing here knows a format, only which installer a name belongs to.
 */
object OfflineImport {

    /**
     * One line of the report the user reads afterwards. [messageRes] takes
     * [args] as its format arguments.
     */
    data class Outcome(
        val fileName: String,
        val ok: Boolean,
        @StringRes val messageRes: Int,
        val args: List<String> = emptyList(),
    )

    /**
     * What a file opened from outside the app is, in the words of the question
     * asked before it installs: [titleRes] and [bodyRes], the body taking the
     * file name. Told the same way [run] tells it, so the question names what
     * the import then does: a `.so` is a library, never language files.
     */
    enum class Kind(@StringRes val titleRes: Int, @StringRes val bodyRes: Int) {
        /** Word lists, word pairs, emoji names, conversion tables, phonetic tables, a language pack zip. */
        LANGUAGE_FILES(R.string.import_offline_title, R.string.import_offline_body),

        /** Google's gesture typing library (`libjni_latinime.so`, `libjni_latinimegoogle.so`). */
        GESTURE_LIBRARY(R.string.import_offline_gesture_title, R.string.import_offline_gesture_body),

        /** A native library this build delivers on demand, loose or in a vendor's `.aar`. */
        NATIVE_LIBRARY(R.string.import_offline_native_title, R.string.import_offline_native_body),

        /** A voice, text recognition, background remover, AI or handwriting model. */
        MODEL(R.string.import_offline_model_title, R.string.import_offline_model_body),

        /** An offline dictionary or a Yomichan dictionary, for the Dictionary tool. */
        DICTIONARY(R.string.import_offline_dictionary_title, R.string.import_offline_dictionary_body),

        /** A CleverKeys GIF pack. */
        GIF_PACK(R.string.import_offline_gif_title, R.string.import_offline_gif_body),

        /** A zip of RIME schemas. */
        RIME_SCHEMAS(R.string.import_offline_rime_title, R.string.import_offline_rime_body),

        /** An interface language pack. */
        APP_LANGUAGE(R.string.import_offline_app_language_title, R.string.import_offline_app_language_body),

        /** A zip holding files of more than one of the kinds above. */
        MIXED(R.string.import_offline_mixed_title, R.string.import_offline_mixed_body),
    }

    /** What marks a zip as RIME schemas: librime's schema file name. */
    private const val RIME_SCHEMA_SUFFIX = ".schema.yaml"

    /** Names inside a language pack that are not for this app, and are passed over without comment. */
    private val IGNORED = listOf(
        Regex("""(?i)^(readme|license|licence|notice|copying)([._-].*)?$"""),
        Regex("""^.*_offensive\.txt(\.gz)?$"""),
        Regex("""^\..*"""),
    )

    /**
     * Whether [name] is one of the published files this installs, by name
     * alone: a word list, a word-pair half, an emoji name list, a conversion
     * dictionary, a Bengali phonetic table or an offline dictionary. What
     * opening a file from outside the app goes by, to send a language pack
     * here rather than call it nobody's file.
     */
    fun recognises(name: String): Boolean = kindOf(name) != null

    /** [recognises], saying what the file is; null when it is none of them. */
    fun kindOf(name: String): Kind? {
        val leaf = name.substringAfterLast('/').substringAfterLast('\\')
        return when {
            DictionaryCatalog.byFileName(leaf) != null ||
                NgramPackCatalog.byFileName(leaf) != null ||
                EmojiDictCatalog.byFileName(leaf) != null ||
                CjkDictCatalog.byFileName(leaf) != null ||
                ContextModelCatalog.byFileName(leaf) != null ||
                leaf == AvroDictionaryDownloads.PUBLISHED_NAME ||
                leaf == KhiproGlideDownloads.PUBLISHED_NAME ||
                KhiproSpecs.isSetFile(leaf) ||
                PhoneticIndexDownloads.languageOfPublishedName(leaf) != null -> Kind.LANGUAGE_FILES
            OfflineDictionaries.recognises(leaf) -> Kind.DICTIONARY
            LatinImeLibrary.supported && LatinImePins.isLibraryName(leaf) -> Kind.GESTURE_LIBRARY
            isNativeLibrary(leaf) || isLibraryArchive(leaf) -> Kind.NATIVE_LIBRARY
            LanguagePackDownloads.isPackName(leaf) -> Kind.APP_LANGUAGE
            else -> null
        }
    }

    /**
     * What a `.so` whose name says nothing (renamed by a browser or a chat
     * app) is, by its [sha256]: Google's gesture library or one of this
     * build's feature libraries, each pinned by hash. Null for any other.
     */
    fun kindOfLibrary(sha256: String): Kind? = when {
        LatinImeLibrary.supported && LatinImePins.abiOf(sha256) != null -> Kind.GESTURE_LIBRARY
        NativeDelivery.active && NativeDelivery.pins().any { it.sha256.equals(sha256, ignoreCase = true) } ->
            Kind.NATIVE_LIBRARY
        else -> null
    }

    /**
     * A native library this build delivers on demand: its own name, or the
     * `<sha256>-<name>` the data repository's release publishes it as. The
     * hash decides whether it installs.
     */
    private fun isNativeLibrary(leaf: String): Boolean =
        NativeDelivery.active && leaf.endsWith(".so") &&
            NativeDelivery.pins().any { leaf == it.name || leaf.endsWith("-${it.name}") }

    /**
     * A vendor's Android library (`.aar`) that one of this build's libraries
     * is read out of (ML Kit, LiteRT, ONNX Runtime, Qualcomm's QNN), by the
     * name its Maven repository serves it under. Imported whole.
     */
    private fun isLibraryArchive(leaf: String): Boolean =
        NativeDelivery.active && leaf.endsWith(".aar") &&
            NativeDelivery.pins().any { NativeDelivery.publishedName(it) == leaf }

    /**
     * [recognises], plus the files told by their name *and* their size, or
     * by an extension nothing else uses: a Whisper model or its vocabulary,
     * the Whistle model, OCR data, the background remover, an on-device AI
     * model, a handwriting pack, and by size alone a file of one of the
     * other dictation engines' models ([EtImport]). [size] is -1 when the provider does not
     * say, and then only the names that need no size can match.
     */
    fun recognises(context: Context, name: String, size: Long): Boolean = kindOf(context, name, size) != null

    /** [recognises], saying what the file is; every file only it tells is a model. */
    fun kindOf(context: Context, name: String, size: Long): Kind? {
        kindOf(name)?.let { return it }
        // A StarDict book, the shape they are mostly shipped in.
        if (StarDictArchive.isTarName(name)) return Kind.DICTIONARY
        val leaf = name.substringAfterLast('/').substringAfterLast('\\')
        val extension = leaf.substringAfterLast('.', "").lowercase()
        val model = (size > 0 && WhisperDownloadManager.recognises(leaf, size)) ||
            leaf == WhistleModelStore.MODEL_FILE_NAME || leaf == CutoutModel.FILE_NAME ||
            PaddleOcrModel.partOf(leaf) != null || OcrPacks.packOf(leaf) != null || InkOcrModels.partOf(leaf) != null ||
            IndicXlitModels.partOf(leaf) != null || FutoSwipe.sizeMatches(size) ||
            ModelFormat.entries.any { it.extension == extension } ||
            (size > 0 && InkPackImports.isPackFile(context, leaf, size)) ||
            EtImport.sizeMatches(size) || BergamotTranslator.sizeMatches(size)
        return if (model) Kind.MODEL else null
    }

    /**
     * Whether a zip with these entries is something this installs: a
     * language pack (any entry [recognises]), a CleverKeys GIF pack, a
     * Yomichan dictionary or RIME schemas, the three zips installed whole.
     */
    fun recognisesArchive(entryNames: List<String>): Boolean = kindOfArchive(entryNames) != null

    /**
     * [recognisesArchive], saying what the zip is. The three zips installed
     * whole come first, in the order [run] tries them; any other zip is what
     * its recognised files are, or [Kind.MIXED] when they are of more than
     * one kind.
     */
    fun kindOfArchive(entryNames: List<String>): Kind? {
        val leaves = entryNames.map { it.substringAfterLast('/') }
        if (OfflineGifPacks.MANIFEST_NAME in leaves && OfflineGifPacks.DATABASE_NAME in leaves) return Kind.GIF_PACK
        if (YomichanDictionaries.looksLikeDictionary(entryNames) || StarDictArchive.holdsBook(entryNames)) return Kind.DICTIONARY
        if (leaves.any { it.endsWith(RIME_SCHEMA_SUFFIX) }) return Kind.RIME_SCHEMAS
        val kinds = leaves.mapNotNullTo(HashSet(), ::kindOf)
        return when (kinds.size) {
            0 -> null
            1 -> kinds.single()
            else -> Kind.MIXED
        }
    }

    /**
     * Installs what [uris] hold and reports on each file, in order.
     * [wordlistSize] is how much of a word list to keep, as a download would
     * ask. [onFile] names the file being worked on, for a progress line.
     * [choose] asks which list to install when the files hold more than one
     * for a language (English's US and UK lists), returning null to install
     * none. Blocking between choices: call it off the main thread.
     */
    suspend fun run(
        context: Context,
        uris: List<Uri>,
        wordlistSize: DictionaryCatalog.DictionarySize,
        choose: suspend (langId: String, options: List<DictionaryEntry>) -> DictionaryEntry?,
        onFile: (String) -> Unit = {},
    ): List<Outcome> {
        val staging = File(context.cacheDir, "offline_import").apply { deleteRecursively(); mkdirs() }
        try {
            val outcomes = ArrayList<Outcome>()
            val files = ArrayList<File>()
            uris.forEachIndexed { index, uri ->
                val name = displayName(context, uri)
                onFile(name)
                val dir = File(staging, index.toString()).apply { mkdirs() }
                val staged = runCatching { stage(context, uri, File(dir, name)) }.getOrElse {
                    outcomes += failure(name, it)
                    return@forEachIndexed
                }
                // Any .aar, whatever a browser renamed it to: the entries
                // the pins name decide, and the rest of it is not ours.
                if (NativeDelivery.active && name.endsWith(".aar", ignoreCase = true)) {
                    outcomes += installLibraryArchive(name, staged)
                    staged.delete()
                    return@forEachIndexed
                }
                // A StarDict book in a tar. Installed from here, like the zips
                // below that are the thing itself.
                if (StarDictArchive.isTarName(name)) {
                    outcomes += installStarDict(context, name, staged, onFile)
                    staged.delete()
                    return@forEachIndexed
                }
                // A handwriting pack is a zip ML Kit unpacks itself, and a
                // published RIME pack is checked against its pin whole, so
                // both are installed as files rather than opened here.
                if (!isZip(staged) || keptWhole(name) || InkPackImports.isPack(context, staged)) {
                    files += staged
                    return@forEachIndexed
                }
                val isGifPack = runCatching { ZipFile(staged).use { OfflineGifPacks.isPack(it) } }.getOrDefault(false)
                val isYomichan = !isGifPack &&
                    runCatching { ZipFile(staged).use { YomichanDictionaries.isDictionary(it) } }.getOrDefault(false)
                val isStarDict = !isGifPack && !isYomichan && runCatching {
                    ZipFile(staged).use { zip -> StarDictArchive.holdsBook(zip.entries().asSequence().map { it.name }.toList()) }
                }.getOrDefault(false)
                val isRimeSchemas = !isGifPack && !isYomichan && !isStarDict && runCatching {
                    ZipFile(staged).use { zip -> zip.entries().asSequence().any { it.name.endsWith(RIME_SCHEMA_SUFFIX) } }
                }.getOrDefault(false)
                if (isGifPack) {
                    outcomes += installGifPack(context, name, staged)
                } else if (isRimeSchemas) {
                    outcomes += installRimeSchemas(context, name, staged)
                    staged.delete()
                } else if (isYomichan) {
                    outcomes += installYomichan(context, name, staged, onFile)
                    staged.delete()
                } else if (isStarDict) {
                    outcomes += installStarDict(context, name, staged, onFile)
                    staged.delete()
                } else {
                    runCatching { files += unzip(staged, File(dir, "zip")) }
                        .onFailure { outcomes += Outcome(name, false, R.string.offline_import_unreadable_zip) }
                    staged.delete()
                }
            }
            val (ink, rest) = files.partition { InkPackImports.isPack(context, it) }
            outcomes += installAll(context, rest, wordlistSize, choose, onFile)
            if (ink.isNotEmpty()) {
                onFile(ink.joinToString(", ") { it.name })
                outcomes += installInk(context, ink)
            }
            // The handwriting library arriving lets the models whose packs
            // came in before it install now (#946).
            runCatching { InkPackImports.installWaiting(context) }.getOrNull()?.forEach { (tag, ok) ->
                val label = HandwritingModels.displayName(tag)
                outcomes += if (ok) {
                    Outcome(label, true, R.string.offline_import_handwriting_done, listOf(label))
                } else {
                    Outcome(label, false, R.string.offline_import_failed)
                }
            }
            // The translation library arriving unpacks the models waiting for it.
            if (BergamotTranslator.available) runCatching { BergamotTranslator.refresh(context) }
            // A RIME pack or schema zip changes what RIME compiles; the
            // runtime notices and the RIME screen deploys again.
            RimeRuntime.recheck(context)
            return outcomes
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Zips installed as they are rather than opened: a published RIME pack,
     * checked against its pin whole, and any APK. An APK is a zip too, and an
     * interface language pack has to reach [LanguagePackDownloads.import]
     * intact for its signature to be checked; unpacked, it is a manifest and a
     * resource table nothing here uses.
     */
    internal fun keptWhole(name: String): Boolean =
        name.endsWith(".apk", ignoreCase = true) || CjkDictCatalog.byFileName(name) != null

    /**
     * Sorts [files] by what they are, then installs them. Sorting first is
     * what lets the two halves of a word-pair pack meet, and lets a language
     * pack carrying several lists for one language ask which one to install.
     */
    private suspend fun installAll(
        context: Context,
        files: List<File>,
        size: DictionaryCatalog.DictionarySize,
        choose: suspend (langId: String, options: List<DictionaryEntry>) -> DictionaryEntry?,
        onFile: (String) -> Unit,
    ): List<Outcome> {
        val filesDir = context.filesDir
        val outcomes = ArrayList<Outcome>()
        val wordlists = LinkedHashMap<String, MutableList<Pair<DictionaryEntry, File>>>()
        val pairs = LinkedHashMap<NgramPackEntry, Array<File?>>()
        val single = ArrayList<File>()
        val khipro = ArrayList<File>()
        for (file in files) {
            val name = file.name
            if (IGNORED.any { it.matches(name) }) continue
            if (KhiproSpecs.isSetFile(name)) {
                khipro += file
                continue
            }
            // A language pack carries a CJK table's earlier version too, for
            // app versions that pin it; this one would fail its checksum.
            if (CjkDictCatalog.isLegacyFileName(name)) continue
            // A pack also carries a list's earlier file (bn_full beside
            // bn_full_v2) for the app versions that fetch it: taken only from
            // an older pack, which has no current one to prefer.
            val wordlist = DictionaryCatalog.byFileName(name)
                ?: DictionaryCatalog.byLegacyFileName(name)?.takeIf { legacy ->
                    files.none { DictionaryCatalog.byFileName(it.name)?.fileName == legacy.fileName }
                }
            val pair = if (wordlist == null) NgramPackCatalog.byFileName(name) else null
            when {
                wordlist != null -> wordlists.getOrPut(wordlist.languageId) { ArrayList() } += wordlist to file
                pair != null -> pairs.getOrPut(pair.first) { arrayOfNulls(2) }[if (pair.second) 1 else 0] = file
                else -> single += file
            }
        }
        if (khipro.isNotEmpty()) outcomes += installKhiproSpecs(khipro, onFile)
        val notDictation = installEtModels(context, single, outcomes, onFile)
        for (file in installBergamotModels(context, notDictation, outcomes, onFile)) {
            onFile(file.name)
            outcomes += installOne(context, file)
        }
        for ((langId, candidates) in wordlists) {
            // A pack carries every list a language has (English: AOSP United
            // States, AOSP United Kingdom, Frequency), and only one can be in
            // use, so which one is the user's to say, as a download asks.
            val picked = if (candidates.size == 1) {
                candidates.single().first
            } else {
                choose(langId, candidates.map { it.first })
            }
            val chosen = candidates.firstOrNull { it.first == picked }
            if (chosen == null) {
                candidates.forEach { (_, other) ->
                    outcomes += Outcome(other.name, true, R.string.offline_import_wordlist_not_chosen)
                }
                continue
            }
            val (entry, file) = chosen
            onFile(file.name)
            outcomes += attempt(file.name, busy = WordlistDownloadManager.isBusy) {
                val words = WordlistDownloadManager.install(filesDir, entry, size, file)
                // An offline build's only way to its compound spellings: the
                // list that holds them, read whole (#1014).
                if (entry.source == WordlistSource.FREQUENCY) {
                    runCatching { CompoundLexiconDownloads.install(filesDir, entry.languageId, file) }
                }
                Outcome(
                    file.name, true, R.string.offline_import_wordlist_done,
                    listOf(languageName(langId), count(words)),
                )
            }
            // The lists the language pack also carried for this language were
            // not wrong, only not the one picked; say so rather than drop them.
            candidates.filter { it.second != file }.forEach { (_, other) ->
                outcomes += Outcome(other.name, true, R.string.offline_import_wordlist_skipped, listOf(file.name))
            }
        }
        for ((entry, halves) in pairs) {
            val label = halves.filterNotNull().joinToString(", ") { it.name }
            onFile(label)
            outcomes += attempt(label) {
                NgramPackDownloadManager.install(filesDir, entry, halves[0], halves[1])
                Outcome(label, true, R.string.offline_import_word_pairs_done, listOf(languageName(entry.languageId)))
            }
        }
        return outcomes
    }

    /**
     * A Khipro spec set (#909): the manifest and the files it names, together,
     * as the data repository's `data/bn/khipro/` folder holds them. Checked as
     * a download of the set is, and installed whole or not at all.
     */
    private suspend fun installKhiproSpecs(files: List<File>, onFile: (String) -> Unit): Outcome {
        val manifest = files.firstOrNull { it.name == KhiproSpecs.MANIFEST_NAME }
            ?: return Outcome(files.first().name, false, R.string.offline_import_khipro_specs_no_manifest)
        onFile(manifest.name)
        return when (val outcome = KhiproSpecs.install(manifest) { name -> files.firstOrNull { it.name == name } }) {
            is KhiproSpecs.Outcome.Installed -> Outcome(
                manifest.name, true, R.string.offline_import_khipro_specs_done,
                listOf(khiproRevisionDate(outcome.revision)),
            )
            KhiproSpecs.Outcome.UpToDate -> Outcome(manifest.name, true, R.string.offline_import_khipro_specs_not_newer)
            is KhiproSpecs.Outcome.Refused ->
                Outcome(manifest.name, false, R.string.offline_import_khipro_specs_refused, listOf(outcome.reason))
            KhiproSpecs.Outcome.Unsupported -> Outcome(manifest.name, false, R.string.offline_import_khipro_specs_unsupported)
        }
    }

    /**
     * The dictation models' files (#954), told by size and checksum rather
     * than name: see [EtImport]. Adds a line per file it placed to
     * [outcomes] and returns the files that are none of them, for
     * [installOne].
     */
    private fun installEtModels(
        context: Context,
        files: List<File>,
        outcomes: MutableList<Outcome>,
        onFile: (String) -> Unit,
    ): List<File> {
        val (candidates, rest) = files.partition { EtImport.sizeMatches(it.length()) }
        if (candidates.isEmpty()) return rest
        if (EtDownloadManager.isBusy) {
            candidates.forEach { outcomes += Outcome(it.name, false, R.string.offline_import_busy) }
            return rest
        }
        onFile(candidates.joinToString(", ") { it.name })
        val result = runCatching { EtImport.install(context.filesDir, candidates) }.getOrElse { error ->
            candidates.forEach { outcomes += failure(it.name, error) }
            return rest
        }
        for (r in result.results) {
            outcomes += when (r) {
                is EtImport.Result.Placed -> if (r.ready.isNotEmpty()) {
                    Outcome(r.fileName, true, R.string.offline_import_et_ready, listOf(r.ready.joinToString(", ") { it.label }))
                } else {
                    Outcome(r.fileName, true, R.string.offline_import_et_placed, listOf(r.models.joinToString(", ") { it.label }))
                }
                is EtImport.Result.Shared -> Outcome(r.fileName, false, R.string.offline_import_et_shared)
            }
        }
        EtDownloadManager.refresh(context.filesDir)
        return rest + result.unclaimed
    }

    /**
     * Firefox Translations' model files (#1035), told by size and checksum since
     * their published names say nothing. Adds a line per file it placed to
     * [outcomes] and returns the files that are none of them.
     */
    private suspend fun installBergamotModels(
        context: Context,
        files: List<File>,
        outcomes: MutableList<Outcome>,
        onFile: (String) -> Unit,
    ): List<File> {
        val (candidates, rest) = files.partition { BergamotTranslator.sizeMatches(it.length()) }
        if (candidates.isEmpty() || !BergamotTranslator.available) return files
        if (BergamotTranslator.isBusy) {
            candidates.forEach { outcomes += Outcome(it.name, false, R.string.offline_import_busy) }
            return rest
        }
        onFile(candidates.joinToString(", ") { it.name })
        val result = runCatching { BergamotTranslator.importFiles(context, candidates) }.getOrElse { error ->
            candidates.forEach { outcomes += failure(it.name, error) }
            return rest
        }
        val engine = context.getString(R.string.tooldetail_translate_bergamot_engine)
        fun names(directions: List<BergamotCatalog.Direction>) = directions.joinToString(", ") {
            OfflineTranslation.label(BergamotTranslator.keyOf(it), TranslateClient::languageName, engine)
        }
        for (r in result.results) {
            outcomes += when (r) {
                is BergamotTranslator.Imported.Placed -> if (r.ready.isNotEmpty()) {
                    Outcome(r.fileName, true, R.string.offline_import_et_ready, listOf(names(r.ready)))
                } else {
                    Outcome(r.fileName, true, R.string.offline_import_bergamot_waiting, listOf(names(r.directions)))
                }
                is BergamotTranslator.Imported.Shared -> Outcome(r.fileName, false, R.string.offline_import_et_shared)
            }
        }
        return rest + result.unclaimed
    }

    /** Everything that is one file, one installer. */
    private fun installOne(context: Context, file: File): Outcome {
        val filesDir = context.filesDir
        val name = file.name
        EmojiDictCatalog.byFileName(name)?.let { entry ->
            return attempt(name) {
                val count = EmojiDictDownloadManager.install(filesDir, entry, file)
                Outcome(name, true, R.string.offline_import_emoji_done, listOf(languageName(entry.languageId), count(count)))
            }
        }
        CjkDictCatalog.byFileName(name)?.let { pack ->
            return attempt(name, busy = CjkDictDownloadManager.isBusy) {
                kotlinx.coroutines.runBlocking { CjkDictDownloadManager.install(filesDir, pack, file) }
                Outcome(name, true, R.string.offline_import_cjk_done, listOf(context.getString(pack.displayNameRes)))
            }
        }
        if (WhisperDownloadManager.recognises(name, file.length())) {
            return attempt(name, busy = WhisperDownloadManager.isBusy) {
                when (val result = WhisperDownloadManager.install(filesDir, file, name)) {
                    is WhisperDownloadManager.Installed.Ready ->
                        Outcome(name, true, R.string.offline_import_whisper_ready, listOf(result.model.displayName))
                    is WhisperDownloadManager.Installed.NeedsVocab -> Outcome(
                        name, true, R.string.offline_import_whisper_needs_vocab,
                        listOf(result.model.displayName, result.vocabFile),
                    )
                    is WhisperDownloadManager.Installed.Vocab ->
                        Outcome(name, true, R.string.offline_import_whisper_vocab, listOf(count(result.models.size)))
                    null -> Outcome(name, false, R.string.offline_import_unknown)
                }
            }
        }
        // A language's context model (#1000), by the name it is published under.
        ContextModelCatalog.byFileName(name)?.let { entry ->
            return attempt(name) {
                ContextModelDownloadManager.install(filesDir, entry, file)
                Outcome(name, true, R.string.offline_import_context_model_done, listOf(languageName(entry.languageId)))
            }
        }
        // The transliteration models (#998): romanized dictation's, and the
        // phonetic layouts' neural readings. Checked as their download is.
        IndicXlitModels.partOf(name)?.let { (pack, part) ->
            return attempt(name) {
                val downloads = if (pack === IndicXlitModels.LATIN) IndicXlitLatinDownloads else IndicXlitDownloads
                when {
                    !downloads.install(context, part, file) -> Outcome(name, false, R.string.offline_import_mismatch)
                    pack.isDownloaded(context.noBackupFilesDir) -> Outcome(name, true, R.string.offline_import_xlit_ready)
                    else -> Outcome(name, true, R.string.offline_import_xlit_waiting)
                }
            }
        }
        // FUTO Swipe's two files (#1002), both served as model_fp32.pte, so
        // told by size and digest whatever they are called.
        if (FutoSwipe.sizeMatches(file.length())) {
            return attempt(name) {
                when {
                    !FutoSwipe.install(filesDir, file) -> Outcome(name, false, R.string.offline_import_mismatch)
                    FutoSwipe.isDownloaded(filesDir) -> Outcome(name, true, R.string.offline_import_futo_ready)
                    else -> Outcome(name, true, R.string.offline_import_futo_waiting)
                }
            }
        }
        if (name == WhistleModelStore.MODEL_FILE_NAME) {
            return attempt(name, busy = WhistleModelDownloadManager.isBusy) {
                if (WhistleModelStore.install(filesDir, file)) {
                    WhistleModelDownloadManager.refresh(filesDir)
                    Outcome(name, true, R.string.offline_import_whistle_ready)
                } else {
                    Outcome(name, false, R.string.offline_import_mismatch)
                }
            }
        }
        PaddleOcrModel.partOf(name)?.let { part ->
            return attempt(name) {
                if (OcrPacks.installPaddle(filesDir, part, file)) {
                    Outcome(name, true, R.string.offline_import_ocr_done, listOf(OcrLanguages.nameOf(PaddleOcrModel.PACK)))
                } else {
                    Outcome(name, false, R.string.offline_import_mismatch)
                }
            }
        }
        InkOcrModels.partOf(name)?.let { part ->
            return attempt(name) {
                val pack = InkOcrModels.packOf(part)
                when {
                    pack == null || !InkOcrModels.install(filesDir, part, file) -> Outcome(name, false, R.string.offline_import_mismatch)
                    pack.parts.all { InkOcrModels.file(filesDir, it).isFile } ->
                        Outcome(name, true, R.string.offline_import_handwriting_done, listOf(pack.displayName))
                    else -> Outcome(name, true, R.string.offline_import_handwriting_waiting)
                }
            }
        }
        OcrPacks.packOf(name)?.let { pack ->
            return attempt(name) {
                OcrPacks.install(filesDir, pack, file)
                Outcome(name, true, R.string.offline_import_ocr_done, listOf(OcrLanguages.nameOf(pack)))
            }
        }
        if (name == AvroDictionaryDownloads.PUBLISHED_NAME) {
            return attempt(name) {
                AvroDictionaryDownloads.install(filesDir, file)
                Outcome(name, true, R.string.offline_import_avro_done)
            }
        }
        if (name == KhiproGlideDownloads.PUBLISHED_NAME) {
            return attempt(name, busy = KhiproGlideDownloads.isBusy) {
                KhiproGlideDownloads.install(filesDir, file)
                Outcome(name, true, R.string.offline_import_khipro_glide_done)
            }
        }
        PhoneticIndexDownloads.languageOfPublishedName(name)?.let { langId ->
            return attempt(name, busy = PhoneticIndexDownloads.isBusy(langId)) {
                PhoneticIndexDownloads.install(filesDir, langId, file)
                Outcome(name, true, R.string.offline_import_phonetic_index_done, listOf(languageName(langId)))
            }
        }
        if (OfflineDictionaries.recognises(name)) {
            return attempt(name) {
                val installed = OfflineDictionaries.install(filesDir, file)
                OfflineDictionaryDownloads.refresh(filesDir)
                Outcome(name, true, R.string.offline_import_dictionary_done, listOf(installed.pack.name))
            }
        }
        if (name == CutoutModel.FILE_NAME) {
            return attempt(name) {
                if (CutoutModel.install(filesDir, file)) {
                    Outcome(name, true, R.string.offline_import_cutout_done)
                } else {
                    Outcome(name, false, R.string.offline_import_mismatch)
                }
            }
        }
        if (LanguagePackDownloads.isPackName(name)) {
            return attempt(name) {
                val qualifier = LanguagePackDownloads.import(context, file)
                if (qualifier != null) {
                    Outcome(name, true, R.string.offline_import_language_pack_done, listOf(qualifier))
                } else {
                    Outcome(name, false, R.string.offline_import_mismatch)
                }
            }
        }
        // Google's gesture library, the user's own file: told by its name, or
        // by its hash for a .so renamed on the way. Only the pinned builds.
        if (LatinImeLibrary.supported &&
            (LatinImePins.isLibraryName(name) || (name.endsWith(".so") && LatinImeLibrary.isPinned(file)))
        ) {
            return attempt(name) {
                when (val result = LatinImeLibrary.import(context.noBackupFilesDir, file)) {
                    LatinImeLibrary.ImportResult.Installed -> Outcome(name, true, R.string.offline_import_gesture_library_done)
                    is LatinImeLibrary.ImportResult.OtherAbi -> Outcome(
                        name, false, R.string.typing_glide_library_result_other_abi,
                        listOf(result.abi, LatinImeLibrary.processAbi()),
                    )
                    else -> Outcome(name, false, R.string.offline_import_mismatch)
                }
            }
        }
        // A feature library: told by its name, or by its hash for a .so
        // renamed on the way, the same as the gesture library above.
        if (isNativeLibrary(name) || (NativeDelivery.active && name.endsWith(".so"))) {
            return attempt(name) {
                val pin = NativeDelivery.import(file)
                when {
                    pin != null -> Outcome(name, true, R.string.offline_import_native_done, listOf(pin.name))
                    // A published name with the wrong bytes is a damaged
                    // download; a .so nothing names is someone else's file.
                    isNativeLibrary(name) -> Outcome(name, false, R.string.offline_import_mismatch)
                    else -> Outcome(name, false, R.string.offline_import_unknown)
                }
            }
        }
        val extension = name.substringAfterLast('.', "").lowercase()
        // A GGUF is told by its first four bytes as well, so a model whose
        // name lost its extension on the way (a browser, a chat app) still
        // installs; it gets the extension back, which is what the model
        // list and the runtime routing go by.
        val gguf = extension != ModelFormat.GGUF.extension && GgufFile.isGguf(file)
        if (gguf || ModelFormat.entries.any { it.extension == extension }) {
            // F-Droid's build has llama.cpp but no LiteRT-LM: only a GGUF runs.
            if (!gguf && extension != ModelFormat.GGUF.extension && !BuildConfig.ENABLE_LITERT_LM) {
                return Outcome(name, false, R.string.models_llm_import_error_gguf_only)
            }
            return attempt(name) {
                // A catalog model goes where its download would, so its row
                // reads as downloaded, and so does a catalog model's vision
                // projector; anything else is the user's own, a projector
                // included, which then pairs with the imported model beside it.
                val model = LocalLlmCatalog.models.firstOrNull { it.fileName == name }
                    ?: LocalLlmCatalog.models.firstOrNull { it.projector?.fileName == name }
                // A pinned catalog file has to be the pinned bytes, as its
                // download would be checked.
                val expected = model?.let { LocalLlmDownloadManager.expectedSha256(it, name) }
                if (expected != null && !LocalLlmDownloadManager.sha256Of(file).equals(expected, ignoreCase = true)) {
                    return@attempt Outcome(name, false, R.string.offline_import_mismatch)
                }
                val target = if (model != null) {
                    LocalLlmStore.modelFile(filesDir, model, name)
                } else {
                    File(LocalLlmStore.customDir(filesDir), if (gguf) "$name.${ModelFormat.GGUF.extension}" else name)
                }
                copyInto(file, target)
                Outcome(name, true, R.string.offline_import_llm_done, listOf(model?.displayName ?: target.name))
            }
        }
        return Outcome(name, false, R.string.offline_import_unknown)
    }

    /**
     * Handwriting packs (#666): kept until every pack a model needs is here,
     * then the model installs through ML Kit as its download would.
     */
    private suspend fun installInk(context: Context, files: List<File>): List<Outcome> {
        val results = runCatching { InkPackImports.install(context, files) }.getOrElse { error ->
            return files.map { failure(it.name, error) }
        }
        return results.map { result ->
            when (result) {
                is InkPackImports.Result.Installed -> Outcome(
                    result.fileName, true, R.string.offline_import_handwriting_done,
                    listOf(result.tags.map(HandwritingModels::displayName).distinct().joinToString(", ")),
                )
                is InkPackImports.Result.Waiting ->
                    Outcome(result.fileName, true, R.string.offline_import_handwriting_waiting)
                is InkPackImports.Result.NeedsLibrary ->
                    Outcome(result.fileName, true, R.string.offline_import_handwriting_needs_library)
                is InkPackImports.Result.Mismatch -> Outcome(result.fileName, false, R.string.offline_import_mismatch)
                is InkPackImports.Result.Failed -> Outcome(result.fileName, false, R.string.offline_import_failed)
            }
        }
    }

    /**
     * A Yomichan / Yomitan dictionary (#765): a zip that is itself the thing
     * installed, as a GIF pack is. Large ones take minutes, so the progress
     * line carries a percentage.
     */
    private fun installYomichan(context: Context, name: String, zip: File, onFile: (String) -> Unit): Outcome = attempt(name) {
        var shown = -1
        val info = YomichanDictionaries.import(context.filesDir, zip) { done, total ->
            val percent = if (total > 0) (done * 100 / total).toInt() else 0
            if (percent != shown) {
                shown = percent
                onFile("$name · $percent%")
            }
        }
        Outcome(name, true, R.string.offline_import_yomichan_done, listOf(info.title, count(info.terms)))
    }

    /** A StarDict book: into the same list as a Yomichan dictionary, one line per book the archive held. */
    private fun installStarDict(context: Context, name: String, archive: File, onFile: (String) -> Unit): List<Outcome> {
        var shown = -1
        val infos = runCatching {
            YomichanDictionaries.importStarDict(context.filesDir, archive) { done, total ->
                val percent = if (total > 0) (done * 100 / total).toInt().coerceAtMost(100) else 0
                if (percent != shown) {
                    shown = percent
                    onFile("$name · $percent%")
                }
            }
        }.getOrElse { return listOf(failure(name, it)) }
        return infos.map { Outcome(name, true, R.string.offline_import_yomichan_done, listOf(it.title, count(it.terms))) }
    }

    /** The pinned libraries an `.aar` holds, one line each. */
    private fun installLibraryArchive(name: String, archive: File): List<Outcome> {
        val installed = runCatching { NativeDelivery.importArchive(archive) }.getOrElse { return listOf(failure(name, it)) }
        if (installed.isEmpty()) return listOf(Outcome(name, false, R.string.offline_import_unknown))
        return installed.map { (pin, ok) ->
            if (ok) {
                Outcome(name, true, R.string.offline_import_native_done, listOf(pin.name))
            } else {
                Outcome(name, false, R.string.offline_import_mismatch)
            }
        }
    }

    /** A RIME schema zip of the user's own, for the RIME engine to compile on its next deploy. */
    private fun installRimeSchemas(context: Context, name: String, zip: File): Outcome = attempt(name) {
        val imported = RimeStore.import(context.filesDir, zip, name)
        Outcome(name, true, R.string.offline_import_rime_done, listOf(imported.schemas.joinToString(", ").ifEmpty { imported.name }))
    }

    private fun installGifPack(context: Context, name: String, zip: File): Outcome = attempt(name) {
        when (val result = OfflineGifPacks.import(context.filesDir, zip)) {
            is OfflineGifPacks.ImportResult.Imported -> Outcome(
                name, true, R.string.offline_import_gif_done,
                listOf(result.pack.name, count(result.pack.gifCount)),
            )
            is OfflineGifPacks.ImportResult.PreviewsOnly ->
                Outcome(name, false, R.string.offline_import_gif_previews_only, listOf(result.name))
            OfflineGifPacks.ImportResult.NoSpace -> Outcome(name, false, R.string.offline_import_no_space)
            OfflineGifPacks.ImportResult.NotAPack -> Outcome(name, false, R.string.offline_import_unknown)
        }
    }

    /**
     * Runs one install, turning whatever it throws into a line of the report.
     * [busy] is its installer's download running, which it must not race:
     * both would write the one file.
     */
    private inline fun attempt(name: String, busy: Boolean = false, block: () -> Outcome): Outcome {
        if (busy) return Outcome(name, false, R.string.offline_import_busy)
        return try {
            block()
        } catch (e: Exception) {
            failure(name, e)
        }
    }

    private fun failure(name: String, error: Throwable): Outcome = when (error) {
        is NoSpaceException -> Outcome(name, false, R.string.offline_import_no_space)
        is YomichanImport.NoSpaceException -> Outcome(name, false, R.string.offline_import_no_space)
        is YomichanImport.NotADictionaryException -> Outcome(name, false, R.string.offline_import_mismatch)
        is OfflineDictionaries.NotAPackException -> Outcome(name, false, R.string.offline_import_mismatch)
        else -> Outcome(name, false, R.string.offline_import_failed)
    }

    private class NoSpaceException : IOException()

    private fun languageName(langId: String): String = LanguageRegistry.byId(langId).displayName

    private fun count(n: Int): String = java.text.NumberFormat.getIntegerInstance().format(n)

    /**
     * The provider's name for [uri], reduced to its last segment so no
     * directory part of it can reach a path.
     */
    // DISPLAY_NAME is sanitised by File(..).name below; lint cannot see the reduction.
    @Suppress("UnsanitizedFilenameFromContentProvider")
    private fun displayName(context: Context, uri: Uri): String {
        val reported = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()
        return File(reported).name.trim().ifEmpty { "file" }
    }

    private fun stage(context: Context, uri: Uri, target: File): File {
        val size = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
            }
        }.getOrNull() ?: -1L
        // Staged once, then installed once more: room for both, and a margin.
        if (size > 0 && StatFs(target.parentFile!!.path).availableBytes < size * 2 + SPACE_MARGIN_BYTES) {
            throw NoSpaceException()
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { input.copyTo(it) }
        } ?: throw IOException("cannot open $uri")
        return target
    }

    private fun isZip(file: File): Boolean =
        file.inputStream().use { input ->
            val head = ByteArray(4)
            input.read(head) == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
                head[2] == 3.toByte() && head[3] == 4.toByte()
        }

    /**
     * Every file in [zip], flattened into [into] by its last name segment.
     * The folders a pack keeps its files in say nothing the name does not,
     * and flattening is also what keeps an entry like `../../x` inside [into].
     */
    private fun unzip(zip: File, into: File): List<File> {
        into.mkdirs()
        val out = ArrayList<File>()
        ZipFile(zip).use { archive ->
            for (entry in archive.entries()) {
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast('/').substringAfterLast('\\')
                if (name.isEmpty() || entry.name.startsWith("__MACOSX/")) continue
                val target = File(into, name)
                if (target.exists()) continue
                archive.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                out += target
            }
        }
        return out
    }

    private fun copyInto(source: File, target: File) {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "${target.name}.part")
        try {
            source.inputStream().use { input -> part.outputStream().use { input.copyTo(it) } }
            target.delete()
            check(part.renameTo(target)) { "could not move $target into place" }
        } finally {
            part.delete()
        }
    }

    private const val SPACE_MARGIN_BYTES = 32L * 1024 * 1024
}
