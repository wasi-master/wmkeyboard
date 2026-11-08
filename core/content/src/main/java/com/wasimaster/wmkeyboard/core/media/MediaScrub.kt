package com.wasimaster.wmkeyboard.core.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.wasimaster.wmkeyboard.core.media.gallery.MediaPrep
import java.io.ByteArrayInputStream
import java.io.File
import java.security.SecureRandom

/**
 * Pictures and videos with their identifying metadata taken out (#1179), for
 * the clipboard's Remove photo and video metadata and the gallery's Remove
 * the camera details.
 *
 * A JPEG, PNG, WebP or GIF loses its metadata blocks byte for byte
 * ([MetadataStrip]), so the picture is not re-compressed; only the EXIF
 * orientation is written back, so it still arrives upright. Any other
 * picture (HEIF, AVIF, BMP) is decoded upright and written again as a JPEG,
 * or a PNG when it has transparency, which carries nothing but pixels. A
 * video's streams go into a fresh MP4 or WebM container ([MediaPrep.remux]),
 * without being re-encoded: the location, the creation date, the phone's
 * make and model and every other tag stay behind with the original.
 *
 * Every function blocks and belongs on a background thread.
 */
object MediaScrub {

    /** A cleaned copy and the type it now is. */
    data class Clean(val file: File, val mime: String)

    private val random = SecureRandom()

    /** Where the cleaned copies made for one paste go; `MediaPrep.sweepSent` deletes them after a day. */
    fun outputDir(context: Context): File = File(File(context.cacheDir, "media"), "clean").apply { mkdirs() }

    /**
     * Cleans the picture in [file] in place. Returns the type it is now
     * ([mime] unless it had to be written again as a JPEG or PNG), or null
     * when it could not be cleaned and was left as it was. [reencode] false
     * refuses the decode-and-write route, for a caller that has to keep the
     * file's format.
     */
    fun image(context: Context, file: File, mime: String, reencode: Boolean = true): String? = runCatching {
        val bytes = file.readBytes()
        val stripped = MetadataStrip.strip(bytes)
        if (stripped != null) {
            val orientation = orientationOf(bytes)
            replace(file) { it.writeBytes(stripped) }
            if (orientation != ExifInterface.ORIENTATION_NORMAL && orientation != ExifInterface.ORIENTATION_UNDEFINED) {
                // The one tag kept: without it a photo taken sideways arrives sideways.
                runCatching {
                    ExifInterface(file).apply {
                        setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                        saveAttributes()
                    }
                }
            }
            return@runCatching MetadataStrip.sniff(stripped)?.mime ?: mime
        }
        if (!reencode) return@runCatching null
        val bitmap = MediaPrep.decode(context, Uri.fromFile(file), MediaPrep.MAX_DECODE_EDGE) ?: return@runCatching null
        val alpha = bitmap.hasAlpha()
        try {
            replace(file) { tmp ->
                tmp.outputStream().use { out ->
                    bitmap.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, REENCODE_QUALITY, out)
                }
            }
        } finally {
            bitmap.recycle()
        }
        if (alpha) MediaMime.PNG else MediaMime.JPEG
    }.getOrNull()

    /** A cleaned copy of the picture in [source], which is left alone; null when it cannot be cleaned. */
    fun imageCopy(context: Context, source: File, mime: String): Clean? = runCatching {
        val dir = outputDir(context)
        val tmp = File(dir, "tmp_${token()}")
        source.inputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        finish(context, tmp, mime)
    }.getOrNull()

    /**
     * A cleaned copy of the picture or video at [uri] (a file another app
     * owns, kept in the clipboard by reference). Null when it is neither,
     * when it cannot be read any more, or when it cannot be cleaned: a video
     * in a container Android cannot write (MKV, AVI) is one.
     */
    fun copyOf(context: Context, uri: Uri, mime: String): Clean? = runCatching {
        when {
            mime.startsWith("video/") -> MediaPrep.remuxClean(context, uri, mime, outputDir(context))
                ?.let { Clean(it.file, it.mime) }
            mime.startsWith("image/") -> {
                val tmp = File(outputDir(context), "tmp_${token()}")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                } ?: return@runCatching null
                finish(context, tmp, mime)
            }
            else -> null
        }
    }.getOrNull()

    /** Whether [copyOf] has anything to do for a file of this type. */
    fun isMedia(mime: String): Boolean = mime.startsWith("image/") || mime.startsWith("video/")

    private fun finish(context: Context, tmp: File, mime: String): Clean? {
        val cleaned = image(context, tmp, mime)
        if (cleaned == null) {
            tmp.delete()
            return null
        }
        val named = File(tmp.parentFile, "IMG_${token()}.${MediaPrep.extensionFor(cleaned)}")
        if (!tmp.renameTo(named)) return Clean(tmp, cleaned)
        return Clean(named, cleaned)
    }

    private fun orientationOf(bytes: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /** Writes through a file beside [file] and moves it over, so a failed write leaves the original whole. */
    private inline fun replace(file: File, write: (File) -> Unit) {
        val tmp = File(file.parentFile, "${file.name}.clean")
        try {
            write(tmp)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
            }
        } finally {
            tmp.delete()
        }
    }

    private fun token(): String {
        val bytes = ByteArray(5)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private const val REENCODE_QUALITY = 95
}
