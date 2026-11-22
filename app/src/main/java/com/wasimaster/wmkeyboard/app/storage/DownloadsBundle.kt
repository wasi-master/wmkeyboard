package com.wasimaster.wmkeyboard.app.storage

import android.content.Context
import com.wasimaster.wmkeyboard.core.modules.NativeDelivery
import java.io.File
import java.io.FilterInputStream
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

    /**
     * How far a save or restore is (#1522): [bytes] of [total] (0 while the
     * size is not known), and the files done so far.
     */
    fun interface Progress {
        fun report(bytes: Long, total: Long, files: Int)
    }

    /**
     * Writes the bundle to [out], reporting through [progress] as it goes.
     * Blocking; call off the main thread.
     */
    fun export(context: Context, out: OutputStream, progress: Progress = Progress { _, _, _ -> }): Result {
        val roots = storageRoots(context)
        // Listed first, so the bar has a total from the start.
        val entries = ArrayList<Pair<File, String>>()
        for (path in paths(roots).distinct()) {
            val prefix = when {
                path.startsWith(roots.files) -> FILES to roots.files
                path.startsWith(roots.noBackupFiles) -> NO_BACKUP to roots.noBackupFiles
                else -> continue
            }
            path.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }.forEach { file ->
                entries += file to prefix.first + file.relativeTo(prefix.second).invariantSeparatorsPath
            }
        }
        NativeDelivery.store()?.let { store ->
            for (pin in NativeDelivery.pins()) {
                if (store.installed(pin)) entries += store.fileOf(pin) to NATIVES + pin.sha256 + "/" + pin.name
            }
        }
        val total = entries.sumOf { it.first.length() }
        var bytes = 0L
        var count = 0
        progress.report(0, total, 0)
        ZipOutputStream(out).use { zip ->
            // Models and packed tries hardly compress, and these are big.
            zip.setLevel(Deflater.BEST_SPEED)
            val buffer = ByteArray(BUFFER)
            for ((file, name) in entries) {
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        zip.write(buffer, 0, read)
                        bytes += read
                        progress.report(bytes, total, count)
                    }
                }
                zip.closeEntry()
                count++
                progress.report(bytes, total, count)
            }
        }
        return Result(count)
    }

    /**
     * Unpacks a bundle from [input]. Nothing lands outside the folders [export]
     * reads, so a bundle can't reach the settings or the learned data, and a
     * native library goes in only when its hash is one this build pins: those
     * are code, and this file came from anywhere. [size] is the file's length
     * when the caller knows it, for [progress]. Blocking; call off the main thread.
     */
    fun import(
        context: Context,
        input: InputStream,
        size: Long = 0,
        progress: Progress = Progress { _, _, _ -> },
    ): Result {
        val roots = storageRoots(context)
        val allowed = paths(roots).map { it.canonicalFile }
        val stage = File(context.cacheDir, "downloads-bundle").apply { mkdirs() }
        var count = 0
        var refused = 0
        val counted = CountingInput(input) { read -> progress.report(read, size, count) }
        ZipInputStream(counted).use { zip ->
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

    private const val BUFFER = 64 * 1024

    /** Counts the compressed bytes read, which is what [import]'s total is in. */
    private class CountingInput(
        input: InputStream,
        private val onRead: (Long) -> Unit,
    ) : FilterInputStream(input) {
        private var read = 0L

        override fun read(): Int {
            val byte = super.read()
            if (byte >= 0) advance(1)
            return byte
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) advance(n.toLong())
            return n
        }

        override fun skip(n: Long): Long {
            val skipped = super.skip(n)
            if (skipped > 0) advance(skipped)
            return skipped
        }

        private fun advance(n: Long) {
            read += n
            onRead(read)
        }
    }
}
