package com.wasimaster.wmkeyboard.app.storage

import android.content.Context
import com.wasimaster.wmkeyboard.core.modules.NativeDelivery
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Everything the keyboard downloaded, as one zip the user keeps (#1045): word
 * lists, RIME's packs, models and native libraries, for a phone that can't reach
 * the host they came from.
 *
 * Its own file rather than a section of the config bundle: that one is a JSON
 * string with base64 inside, and hundreds of megabytes of it would not fit in
 * memory. This streams.
 */
internal object DownloadsBundle {
    /** The [StorageCategories] that hold downloads, and nothing written by the user but RIME's own words. */
    private val CATEGORIES = listOf(
        "wordlists", "emoji_dicts", "vocab", "voice_models", "glide_models", "ai_models", "neural_phonetic",
        "romanized_dictation", "meaning_models", "sticker_models", "ocr_packs", "gif_packs",
    )
    private const val FILES = "files/"
    private const val NO_BACKUP = "nobackup/"
    private const val NATIVES = "natives/"

    /** Files written, and files an import refused. */
    class Result(val files: Int, val refused: Int = 0)

    private fun paths(roots: StorageRoots): List<File> =
        CATEGORIES.flatMap { id -> StorageCategories.byId(id)?.paths(roots).orEmpty() }

    /** Writes the bundle to [out]. Blocking; call off the main thread. */
    fun export(context: Context, out: OutputStream): Result {
        val roots = storageRoots(context)
        var count = 0
        ZipOutputStream(out).use { zip ->
            // Models and packed tries hardly compress, and these are big.
            zip.setLevel(Deflater.BEST_SPEED)
            fun add(file: File, name: String) {
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                count++
            }
            for (path in paths(roots).distinct()) {
                val prefix = when {
                    path.startsWith(roots.files) -> FILES to roots.files
                    path.startsWith(roots.noBackupFiles) -> NO_BACKUP to roots.noBackupFiles
                    else -> continue
                }
                path.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }.forEach { file ->
                    add(file, prefix.first + file.relativeTo(prefix.second).invariantSeparatorsPath)
                }
            }
            NativeDelivery.store()?.let { store ->
                for (pin in NativeDelivery.pins()) {
                    if (store.installed(pin)) add(store.fileOf(pin), NATIVES + pin.sha256 + "/" + pin.name)
                }
            }
        }
        return Result(count)
    }

    /**
     * Unpacks a bundle from [input]. Nothing lands outside the folders [export]
     * reads, so a bundle can't reach the settings or the learned data, and a
     * native library goes in only when its hash is one this build pins: those
     * are code, and this file came from anywhere. Blocking; call off the main thread.
     */
    fun import(context: Context, input: InputStream): Result {
        val roots = storageRoots(context)
        val allowed = paths(roots).map { it.canonicalFile }
        val stage = File(context.cacheDir, "downloads-bundle").apply { mkdirs() }
        var count = 0
        var refused = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name
                if (name.startsWith(NATIVES)) {
                    val staged = File(stage, "native.part")
                    staged.outputStream().use { zip.copyTo(it) }
                    val pin = NativeDelivery.store()?.pinFor(staged, NativeDelivery.pins())
                    if (pin != null && NativeDelivery.store()?.install(pin, staged) == true) count++ else refused++
                    staged.delete()
                    continue
                }
                val target = when {
                    name.startsWith(FILES) -> File(roots.files, name.removePrefix(FILES))
                    name.startsWith(NO_BACKUP) -> File(roots.noBackupFiles, name.removePrefix(NO_BACKUP))
                    else -> null
                }?.canonicalFile
                if (target == null || allowed.none { target.startsWith(it) }) {
                    refused++
                    continue
                }
                target.parentFile?.mkdirs()
                // Renamed into place: the keyboard may have the old copy mapped.
                val part = File(target.parentFile, target.name + ".part")
                part.outputStream().use { zip.copyTo(it) }
                if (part.renameTo(target)) count++ else part.delete()
            }
        }
        stage.deleteRecursively()
        return Result(count, refused)
    }
}
