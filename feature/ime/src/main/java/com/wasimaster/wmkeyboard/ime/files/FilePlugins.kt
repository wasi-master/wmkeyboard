package com.wasimaster.wmkeyboard.ime.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.wasimaster.wmkeyboard.core.files.DocFiles
import com.wasimaster.wmkeyboard.core.files.FileEntry
import com.wasimaster.wmkeyboard.core.plugins.PluginPreviewRef
import com.wasimaster.wmkeyboard.core.plugins.PluginPreviewRunner
import com.wasimaster.wmkeyboard.core.plugins.PluginStore
import com.wasimaster.wmkeyboard.core.plugins.PluginSyntax
import com.wasimaster.wmkeyboard.core.plugins.PreviewSource
import java.io.IOException
import java.util.concurrent.Semaphore

/**
 * What plugins add to the file manager: previews for file types, grid
 * thumbnails, and syntax colouring. Built by the service once plugins are on;
 * the controller asks it which plugins fit a file and never runs one itself.
 *
 * The match tables are rebuilt when the store's [PluginStore.revision] moves,
 * so the grid's per-cell question ("does a plugin draw this one?") is a scan
 * of a short list, not a lock on the store.
 */
class FilePlugins(
    private val context: Context,
    private val store: PluginStore,
    val runner: PluginPreviewRunner,
) {
    @Volatile
    private var tables: Tables? = null

    private class Tables(
        val revision: Int,
        val previews: List<PluginPreviewRef>,
        val syntax: List<com.wasimaster.wmkeyboard.core.plugins.PluginSyntaxRef>,
    )

    private fun tables(): Tables {
        val revision = store.revision.value
        tables?.takeIf { it.revision == revision }?.let { return it }
        return Tables(revision, store.previews(), store.syntaxes()).also { tables = it }
    }

    /** The previews that read a file called [name] of type [mime], the closest fit first. */
    fun previewsFor(name: String, mime: String?): List<PluginPreviewRef> =
        tables().previews.filter { it.preview.matches(name, mime) }.sortedByDescending { it.preview.fit(name, mime) }

    /** The preview [key] names, when it may still run. */
    fun preview(key: String): PluginPreviewRef? = tables().previews.firstOrNull { it.key == key }

    /** A plugin grammar for a file called [name], or null. */
    fun grammarFor(name: String): PluginSyntax.Grammar? =
        tables().syntax.firstOrNull { it.syntax.matches(name) }?.let(PluginSyntax::grammar)

    /** A plugin grammar for a code block's [language] (a file extension), or null. */
    fun grammarForLanguage(language: String): PluginSyntax.Grammar? =
        if (language.isBlank()) null else tables().syntax.firstOrNull { it.syntax.answers(language) }?.let(PluginSyntax::grammar)

    /** The preview that draws [entry]'s grid picture, if any plugin does. */
    fun thumbnailFor(entry: FileEntry): PluginPreviewRef? =
        if (entry.isDirectory) null else previewsFor(entry.name, entry.mime).firstOrNull { it.preview.thumbnails }

    private val decoded = LruCache<String, Bitmap>(MEMORY_THUMBS)
    private val gate = Semaphore(PARALLEL_THUMBS)

    /**
     * [entry]'s plugin thumbnail, decoded at about [px] on its long side, or
     * null. Blocking: the grid calls it from a background coroutine. At most
     * two run at once, so a fast scroll queues rather than starts dozens of
     * interpreters.
     */
    fun thumbnail(entry: FileEntry, px: Int): Bitmap? {
        val ref = thumbnailFor(entry) ?: return null
        val key = "${entry.path}|${entry.modifiedMs}|${entry.size}|${ref.key}@${ref.plugin.version}"
        decoded.get(key)?.let { return it }
        gate.acquire()
        try {
            decoded.get(key)?.let { return it }
            val bytes = runner.thumbnail(ref, source(entry)) ?: return null
            val bitmap = decode(bytes, px) ?: return null
            decoded.put(key, bitmap)
            return bitmap
        } finally {
            gate.release()
        }
    }

    /** [entry] as a preview reads it: its facts, and its bytes from wherever it lives. */
    fun source(entry: FileEntry): PreviewSource = EntrySource(context.applicationContext, entry)

    private class EntrySource(private val app: Context, private val entry: FileEntry) : PreviewSource {
        override val name: String get() = entry.name
        override val mime: String? get() = entry.mime.ifEmpty { null }
        override val size: Long get() = entry.size
        override val modified: Long? get() = entry.modifiedMs.takeIf { it > 0 }
        override val identity: String get() = entry.path

        override fun read(offset: Long, length: Int): ByteArray {
            val input = DocFiles.input(app, entry.path) ?: throw IOException("cannot open ${entry.name}")
            input.use { stream ->
                var skipped = 0L
                while (skipped < offset) {
                    val n = stream.skip(offset - skipped)
                    if (n <= 0) {
                        if (stream.read() < 0) return ByteArray(0)
                        skipped++
                    } else {
                        skipped += n
                    }
                }
                val out = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = stream.read(out, read, length - read)
                    if (n < 0) break
                    read += n
                }
                return if (read == length) out else out.copyOf(read)
            }
        }
    }

    companion object {
        private const val MEMORY_THUMBS = 64
        private const val PARALLEL_THUMBS = 2

        /** A plugin's picture, scaled down while decoding so a 4000-pixel cover costs a thumbnail's memory. */
        fun decode(bytes: ByteArray, px: Int): Bitmap? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return@runCatching null
            var sample = 1
            while (longest / (sample * 2) >= px) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }
}
