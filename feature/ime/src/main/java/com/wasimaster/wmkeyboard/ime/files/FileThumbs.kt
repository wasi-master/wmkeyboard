package com.wasimaster.wmkeyboard.ime.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import com.wasimaster.wmkeyboard.core.files.DocFiles
import com.wasimaster.wmkeyboard.core.files.FileDetails
import com.wasimaster.wmkeyboard.core.files.FileEntry
import com.wasimaster.wmkeyboard.core.files.FileKind
import com.wasimaster.wmkeyboard.core.files.FileKinds
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Semaphore
import java.util.zip.ZipInputStream

/**
 * Grid pictures for the kinds the keyboard previews itself but MediaStore
 * has no thumbnail for: a PDF's first page, a recording's cover art, an app
 * package's icon, the first lines of a text or code file, and the first
 * picture inside a zip (a comic, a folder of photos).
 *
 * Each is made once per file version and kept on disk, so scrolling back and
 * opening the folder again cost a file read, not a PDF render. At most two
 * are made at once, so a fast scroll queues instead of starting dozens.
 */
object FileThumbs {

    /** Whether [entry] is a kind this draws a picture for. */
    fun covers(entry: FileEntry): Boolean {
        if (entry.isDirectory) return false
        val kind = FileKinds.of(entry)
        return kind == FileKind.PDF || kind == FileKind.AUDIO || kind == FileKind.APK ||
            FileKinds.isTextual(kind) || (kind == FileKind.ARCHIVE || kind == FileKind.EBOOK) && FileKinds.isZip(entry.name)
    }

    private val memory = LruCache<String, Bitmap>(MEMORY_THUMBS)
    private val misses = LruCache<String, Boolean>(MEMORY_MISSES)
    private val gate = Semaphore(PARALLEL)
    private var writes = 0

    /**
     * [entry]'s picture about [px] on its long side, or null when the file has
     * none (a recording without art, a locked PDF). Blocking: call it from a
     * background coroutine.
     */
    fun thumbnail(context: Context, entry: FileEntry, px: Int): Bitmap? {
        if (!covers(entry)) return null
        val key = "${entry.path}|${entry.modifiedMs}|${entry.size}|$px"
        memory.get(key)?.let { return it }
        if (misses.get(key) == true) return null
        gate.acquire()
        try {
            memory.get(key)?.let { return it }
            val app = context.applicationContext
            val file = File(dir(app), digest(key) + ".webp")
            val cached = if (file.isFile) BitmapFactory.decodeFile(file.path) else null
            val made = cached ?: make(app, entry, px)?.also { save(app, file, it) }
            if (made == null) misses.put(key, true) else memory.put(key, made)
            return made
        } finally {
            gate.release()
        }
    }

    private fun make(context: Context, entry: FileEntry, px: Int): Bitmap? = runCatching {
        val kind = FileKinds.of(entry)
        when {
            kind == FileKind.PDF -> FileDetails.pdfPage(context, entry.path, 0, px)?.first
            kind == FileKind.AUDIO -> coverArt(context, entry.path, px)
            kind == FileKind.APK -> FileDetails.app(context, entry.path)?.second?.toBitmap(px / 2, px / 2)
            FileKinds.isTextual(kind) -> textPage(context, entry, px)
            else -> firstPicture(context, entry.path, px)
        }
    }.getOrNull()

    /** A recording's embedded cover, scaled down while decoding. */
    private fun coverArt(context: Context, path: String, px: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, DocFiles.uriOf(context, path))
            r.embeddedPicture?.let { decode(it, px) }
        } finally {
            runCatching { r.release() }
        }
    }

    /**
     * The first lines of a text file on a small page, as a document app's
     * icon shows them: enough to tell a script from a list at a glance.
     */
    private fun textPage(context: Context, entry: FileEntry, px: Int): Bitmap? {
        val text = DocFiles.readText(context, entry.path, TEXT_BYTES) ?: return null
        val lines = text.replace("\r\n", "\n").split('\n').take(TEXT_LINES).map { it.replace("\t", "  ").take(TEXT_COLUMNS) }
        if (lines.all { it.isBlank() }) return null
        val width = px
        val height = (px * 1.25f).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(PAPER)
        val code = FileKinds.of(entry) == FileKind.CODE
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            textSize = width / 15f
            typeface = if (code) Typeface.MONOSPACE else Typeface.DEFAULT
        }
        val margin = width * 0.08f
        val step = paint.textSize * 1.3f
        var y = margin + paint.textSize
        for (line in lines) {
            if (y > height - margin) break
            canvas.drawText(line, margin, y, paint)
            y += step
        }
        return bitmap
    }

    /** The first picture inside a zip, found by walking it from the start; a zip of no pictures gives null. */
    private fun firstPicture(context: Context, path: String, px: Int): Bitmap? {
        DocFiles.input(context, path)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                var seen = 0
                while (seen < ZIP_ENTRIES) {
                    val e = zip.nextEntry ?: return null
                    seen++
                    val name = e.name.lowercase()
                    if (e.isDirectory || name.startsWith("__macosx/") || name.substringAfterLast('/').startsWith(".")) continue
                    if (PICTURES.none { name.endsWith(it) }) continue
                    val bytes = zip.readNBytesCompat(ZIP_PICTURE_MAX) ?: continue
                    decode(bytes, px)?.let { return it }
                }
            }
        }
        return null
    }

    private fun ZipInputStream.readNBytesCompat(max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > max) return null
        }
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray, px: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= px) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun dir(context: Context): File = File(context.cacheDir, "file-thumbs").apply { mkdirs() }

    @Suppress("DEPRECATION")
    private fun save(context: Context, file: File, bitmap: Bitmap) {
        runCatching {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP, WEBP_QUALITY, it) }
        }
        if (++writes % PRUNE_EVERY == 0) prune(dir(context))
    }

    /** Oldest first, until the folder is under its share of the cache. */
    private fun prune(dir: File) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= DISK_BUDGET) break
            total -= f.length()
            f.delete()
        }
    }

    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private const val MEMORY_THUMBS = 96
    private const val MEMORY_MISSES = 512
    private const val PARALLEL = 2
    private const val TEXT_BYTES = 2048
    private const val TEXT_LINES = 18
    private const val TEXT_COLUMNS = 40
    private const val ZIP_ENTRIES = 300
    private const val ZIP_PICTURE_MAX = 8 * 1024 * 1024
    private const val WEBP_QUALITY = 82
    private const val PRUNE_EVERY = 40
    private const val DISK_BUDGET = 24L * 1024 * 1024
    private val PICTURES = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp")
    private val PAPER = Color.rgb(250, 250, 247)
    private val INK = Color.rgb(70, 72, 78)
}
