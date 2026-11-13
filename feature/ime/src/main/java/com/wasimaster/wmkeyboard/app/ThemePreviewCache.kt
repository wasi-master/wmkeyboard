package com.wasimaster.wmkeyboard.app

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.wasimaster.wmkeyboard.core.perf.DeviceMemory
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.core.theme.WallpaperBackdrop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * The pictures behind the theme cards (see [ThemePreview] with `cached`), the
 * same idea as [LayoutPreviewCache] for the layout cards.
 *
 * A theme card is a small keyboard drawn from the theme: its photo decoded,
 * a glass theme's backdrop baked blurred and a node of glass per key, a
 * skin's art, a moving board caught as one frame, a texture, the stickers.
 * For the plain themes that is cheap; for the complex ones it is a lot of
 * work per card, and the gallery and the keyboard's theme panel draw dozens
 * of cards, and draw them again every time they scroll back into view or
 * the panel opens. On a slow phone that work was the lag.
 *
 * So each card is drawn once, captured as a picture, and every later showing
 * draws the picture. Kept in memory for the life of the process and as a WebP
 * under the cache directory, so the next visit (the next launch included)
 * opens on pictures. The key ([keyOf]) is a digest of everything the card is
 * drawn from: the whole theme, the files it names (by size and time, so a
 * replaced photo at the same path is a new picture), the wallpaper for a
 * glass theme that samples it, the card's size and the build. A change to
 * any of them is a new key and a fresh drawing, never a stale picture.
 */
internal object ThemePreviewCache {

    /** Bumped when the key or the drawing changes, to orphan older files. */
    private const val FORMAT = 1
    private const val DIR = "theme-previews"

    /**
     * Pictures kept on disk. A card is some 20 to 40 KB as lossy WebP, so
     * this is a few megabytes: every theme of a large collection, at the
     * gallery's width and the keyboard panel's.
     */
    private const val DISK_MAX_FILES = 300
    private const val DISK_MAX_BYTES = 10L shl 20

    /** A photo theme's card is a photo; lossless would be five times the size for nothing visible. */
    private const val WEBP_QUALITY = 88

    private val memory = object : LruCache<String, ImageBitmap>(memoryBudget()) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    /**
     * The key each card slot was last worked out for, and the theme it was
     * worked out from: a card scrolling back into view with the same theme
     * finds its picture without hashing anything.
     */
    private val keys = HashMap<String, Pair<ThemeSpec, String>>()

    /** The last picture key of each theme and orientation, whatever its size or version. */
    private val latest = HashMap<String, String>()

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appStamp: Long = -1L

    /**
     * A twentieth of the heap, at most 24 MB: a gallery card is some 0.3 MB
     * as pixels, and most pictures are hardware bitmaps that do not sit on
     * the heap at all.
     */
    private fun memoryBudget(): Int =
        // Half on a low-memory phone or television (see DeviceMemory).
        DeviceMemory.cacheBudget(20, min = 2L shl 20, max = 24L shl 20)

    private fun slot(theme: ThemeSpec, image: String?, landscape: Boolean, widthPx: Int) =
        "${theme.id}\u0000${image.orEmpty()}\u0000$landscape\u0000$widthPx"

    private fun themeSlot(theme: ThemeSpec, image: String?, landscape: Boolean) =
        "${theme.id}\u0000${image.orEmpty()}\u0000$landscape"

    /**
     * The picture for [theme] as it stands at [widthPx], if this process has
     * one and worked its key out from this very theme. Main thread, no hashing.
     */
    @Synchronized
    fun known(theme: ThemeSpec, image: String?, landscape: Boolean, widthPx: Int): ImageBitmap? {
        val (from, key) = keys[slot(theme, image, landscape, widthPx)] ?: return null
        if (from !== theme && from != theme) return null
        return memory.get(key)
    }

    /**
     * The last picture of [theme]'s card, whatever it was drawn from: what a
     * card shows while the picture for an edited theme is drawn, rather
     * than a blank.
     */
    @Synchronized
    fun stale(theme: ThemeSpec, image: String?, landscape: Boolean): ImageBitmap? =
        latest[themeSlot(theme, image, landscape)]?.let { memory.get(it) }

    @Synchronized
    fun noteKey(theme: ThemeSpec, image: String?, landscape: Boolean, widthPx: Int, key: String) {
        keys[slot(theme, image, landscape, widthPx)] = theme to key
    }

    fun get(key: String): ImageBitmap? = memory.get(key)

    @Synchronized
    fun put(theme: ThemeSpec, image: String?, landscape: Boolean, key: String, picture: ImageBitmap) {
        memory.put(key, picture)
        latest[themeSlot(theme, image, landscape)] = key
    }

    /** Lets the memory half go; the disk half brings the cards back. */
    @Synchronized
    fun trim(level: Int) {
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            memory.evictAll()
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            memory.trimToSize(memory.maxSize() / 2)
        }
    }

    /** The picture saved for [key] on a past visit, decoded off the main thread; null if there is none. */
    suspend fun load(context: Context, key: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val file = File(dir(context), "$key.webp")
        if (!file.isFile) return@withContext null
        val options = BitmapFactory.Options().apply {
            // Straight to the GPU: the card only ever draws it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) inPreferredConfig = Bitmap.Config.HARDWARE
        }
        val bitmap = runCatching { BitmapFactory.decodeFile(file.path, options) }.getOrNull()
        if (bitmap == null) {
            file.delete()
            return@withContext null
        }
        // Touched, so the trim keeps the pictures still in use.
        file.setLastModified(System.currentTimeMillis())
        bitmap.asImageBitmap()
    }

    /** Saves [picture] for the next visit, in the background. */
    fun save(context: Context, key: String, picture: ImageBitmap) {
        val app = context.applicationContext
        io.launch {
            runCatching {
                val dir = dir(app).apply { mkdirs() }
                val source = picture.asAndroidBitmap()
                // A hardware bitmap has no pixels on the CPU side to encode.
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    source.config == Bitmap.Config.HARDWARE
                ) {
                    source.copy(Bitmap.Config.ARGB_8888, false) ?: return@launch
                } else {
                    source
                }
                val temp = File(dir, "$key.tmp")
                temp.outputStream().use { out ->
                    @Suppress("DEPRECATION")
                    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Bitmap.CompressFormat.WEBP_LOSSY
                    } else {
                        Bitmap.CompressFormat.WEBP
                    }
                    bitmap.compress(format, WEBP_QUALITY, out)
                }
                if (bitmap !== source) bitmap.recycle()
                temp.renameTo(File(dir, "$key.webp"))
                trim(dir)
            }
        }
    }

    /** Keeps the folder to the pictures shown most recently, within both limits. */
    private fun trim(dir: File) {
        val files = dir.listFiles() ?: return
        var bytes = 0L
        files.sortedByDescending { it.lastModified() }.forEachIndexed { index, file ->
            bytes += file.length()
            if (index >= DISK_MAX_FILES || bytes > DISK_MAX_BYTES) file.delete()
        }
    }

    private fun dir(context: Context) = File(context.cacheDir, DIR)

    /**
     * The key of [theme]'s card drawn [widthPx] wide at [density]: a digest of
     * everything the card is drawn from. Off the main thread; it stats the
     * files the theme names.
     */
    fun keyOf(
        context: Context,
        theme: ThemeSpec,
        image: String?,
        landscape: Boolean,
        widthPx: Int,
        density: Float,
    ): String {
        val text = theme.toString()
        val parts = ArrayList<String>()
        parts += FORMAT.toString()
        parts += appStamp(context).toString()
        parts += widthPx.toString()
        parts += density.toString()
        parts += landscape.toString()
        parts += image.orEmpty()
        parts += text
        // Every file the theme or the override names, by size and time: a
        // photo replaced in place keeps its path but not its picture.
        for (path in (FilePaths.findAll(text).map { it.value } + listOfNotNull(image)).distinct()) {
            val file = File(path)
            parts += "$path:${file.length()}:${file.lastModified()}"
        }
        // A glass theme may sample the wallpaper, or its colours where the
        // pixels cannot be read.
        if (theme.frost != null) parts += WallpaperBackdrop.stamp(context).toString()
        return sha256(parts.joinToString("\u0000"))
    }

    /**
     * When the app was installed or updated: a new build may draw the same
     * theme differently, so its pictures start over.
     */
    private fun appStamp(context: Context): Long {
        if (appStamp == -1L) {
            appStamp = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            }.getOrDefault(0L)
        }
        return appStamp
    }

    /** An absolute path in a theme's text: its photos, textures, stickers and skin art. */
    private val FilePaths = Regex("""/(?:data|storage|sdcard|mnt)/[^\s,()\]\[]+""")
}

private fun sha256(text: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
    return buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xff
            append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0f])
        }
    }
}

private const val HEX_DIGITS = "0123456789abcdef"
