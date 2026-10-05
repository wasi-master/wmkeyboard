package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.dictionaries.DictionaryCatalog
import com.wasimaster.wmkeyboard.core.dictionaries.DictionaryEntry
import com.wasimaster.wmkeyboard.core.dictionaries.NgramPackCatalog
import com.wasimaster.wmkeyboard.core.dictionaries.NgramPackDownloadManager
import com.wasimaster.wmkeyboard.core.dictionaries.NgramPackEntry
import com.wasimaster.wmkeyboard.core.dictionaries.WordlistDownloadManager
import com.wasimaster.wmkeyboard.core.emoji.EmojiDictCatalog
import com.wasimaster.wmkeyboard.core.emoji.EmojiDictDownloadManager
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictCatalog
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictDownloadManager
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmCatalog
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmStore
import com.wasimaster.wmkeyboard.core.localllm.ModelFormat
import com.wasimaster.wmkeyboard.core.ocr.OcrLanguages
import com.wasimaster.wmkeyboard.core.ocr.OcrPacks
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.stickers.CutoutModel
import com.wasimaster.wmkeyboard.core.tools.offlinegif.OfflineGifPacks
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
 * thing installed ([OfflineGifPacks]).
 *
 * Every file goes through the same code its download would: the same parse,
 * the same checksum where the download has one, the same place on disk. So
 * nothing here knows a format, only which installer a name belongs to.
 */
internal object OfflineImport {

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

    /** Names inside a language pack that are not for this app, and are passed over without comment. */
    private val IGNORED = listOf(
        Regex("""(?i)^(readme|license|licence|notice|copying)([._-].*)?$"""),
        Regex("""^.*_offensive\.txt(\.gz)?$"""),
        Regex("""^\..*"""),
    )

    /**
     * Installs what [uris] hold and reports on each file, in order.
     * [wordlistSize] is how much of a word list to keep, as a download would
     * ask. [onFile] names the file being worked on, for a progress line.
     * Blocking: call it off the main thread.
     */
    fun run(
        context: Context,
        uris: List<Uri>,
        wordlistSize: DictionaryCatalog.DictionarySize,
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
                if (!isZip(staged)) {
                    files += staged
                    return@forEachIndexed
                }
                val isGifPack = runCatching { ZipFile(staged).use { OfflineGifPacks.isPack(it) } }.getOrDefault(false)
                if (isGifPack) {
                    outcomes += installGifPack(context, name, staged)
                } else {
                    runCatching { files += unzip(staged, File(dir, "zip")) }
                        .onFailure { outcomes += Outcome(name, false, R.string.offline_import_unreadable_zip) }
                    staged.delete()
                }
            }
            outcomes += installAll(context, files, wordlistSize, onFile)
            return outcomes
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Sorts [files] by what they are, then installs them. Sorting first is
     * what lets the two halves of a word-pair pack meet, and lets a language
     * pack carrying several lists for one language install the one a
     * download would have picked.
     */
    private fun installAll(
        context: Context,
        files: List<File>,
        size: DictionaryCatalog.DictionarySize,
        onFile: (String) -> Unit,
    ): List<Outcome> {
        val filesDir = context.filesDir
        val outcomes = ArrayList<Outcome>()
        val wordlists = LinkedHashMap<String, MutableList<Pair<DictionaryEntry, File>>>()
        val pairs = LinkedHashMap<NgramPackEntry, Array<File?>>()
        for (file in files) {
            val name = file.name
            if (IGNORED.any { it.matches(name) }) continue
            val wordlist = DictionaryCatalog.byFileName(name)
            val pair = if (wordlist == null) NgramPackCatalog.byFileName(name) else null
            when {
                wordlist != null -> wordlists.getOrPut(wordlist.languageId) { ArrayList() } += wordlist to file
                pair != null -> pairs.getOrPut(pair.first) { arrayOfNulls(2) }[if (pair.second) 1 else 0] = file
                else -> {
                    onFile(name)
                    outcomes += installOne(context, file)
                }
            }
        }
        for ((langId, candidates) in wordlists) {
            val preferred = DictionaryCatalog.preferred(langId)
            val (entry, file) = candidates.firstOrNull { it.first == preferred } ?: candidates.first()
            onFile(file.name)
            outcomes += attempt(file.name, busy = WordlistDownloadManager.isBusy) {
                val words = WordlistDownloadManager.install(filesDir, entry, size, file)
                Outcome(
                    file.name, true, R.string.offline_import_wordlist_done,
                    listOf(languageName(langId), count(words)),
                )
            }
            // The lists the language pack also carried for this language were
            // not wrong, only not the one to use; say so rather than drop them.
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
        OcrPacks.packOf(name)?.let { pack ->
            return attempt(name) {
                OcrPacks.install(filesDir, pack, file)
                Outcome(name, true, R.string.offline_import_ocr_done, listOf(OcrLanguages.nameOf(pack)))
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
        val extension = name.substringAfterLast('.', "").lowercase()
        if (ModelFormat.entries.any { it.extension == extension }) {
            return attempt(name) {
                // A catalog model goes where its download would, so its row
                // reads as downloaded; anything else is the user's own.
                val model = LocalLlmCatalog.models.firstOrNull { it.fileName == name }
                val target = if (model != null) {
                    LocalLlmStore.modelFile(filesDir, model)
                } else {
                    File(LocalLlmStore.customDir(filesDir), name)
                }
                copyInto(file, target)
                Outcome(name, true, R.string.offline_import_llm_done, listOf(model?.displayName ?: name))
            }
        }
        return Outcome(name, false, R.string.offline_import_unknown)
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
