package com.wasimaster.wmkeyboard.core.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StatFs
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * What the Properties card and the preview read out of a file itself:
 * a picture's size and camera, a recording's length, an app package's name
 * and version, a PDF's pages. Each call blocks and wants a background thread;
 * a file that does not answer gives nulls, never an exception.
 */
object FileDetails {

    /** The facts there are; any of them null when the file has none or would not say. */
    data class Facts(
        val width: Int? = null,
        val height: Int? = null,
        val durationMs: Long? = null,
        val camera: String? = null,
        val exposure: String? = null,
        val takenMs: Long? = null,
        val artist: String? = null,
        val album: String? = null,
        val title: String? = null,
        val appLabel: String? = null,
        val appPackage: String? = null,
        val appVersion: String? = null,
        val appMinSdk: Int? = null,
        val pages: Int? = null,
    )

    /** Free and total bytes of the volume holding [path], or null for a document or a path Android will not measure. */
    fun space(path: String): Pair<Long, Long>? = runCatching {
        val stat = StatFs(path)
        stat.availableBytes to stat.totalBytes
    }.getOrNull()

    fun facts(context: Context, path: String, kind: FileKind): Facts = when (kind) {
        FileKind.IMAGE -> image(context, path)
        FileKind.VIDEO, FileKind.AUDIO -> media(context, path)
        FileKind.APK -> app(context, path)?.first ?: Facts()
        FileKind.PDF -> Facts(pages = pdfPages(context, path))
        else -> Facts()
    }

    private fun image(context: Context, path: String): Facts {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { DocFiles.input(context, path)?.use { BitmapFactory.decodeStream(it, null, bounds) } }
        val exif = runCatching { DocFiles.input(context, path)?.use { ExifInterface(it) } }.getOrNull()
        val make = exif?.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
        val model = exif?.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
        val camera = when {
            model.isEmpty() -> make.ifEmpty { null }
            make.isEmpty() || model.startsWith(make, ignoreCase = true) -> model
            else -> "$make $model"
        }
        val f = exif?.getAttribute(ExifInterface.TAG_F_NUMBER)?.toDoubleOrNull()
        val time = exif?.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()
        val iso = exif?.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
        val exposure = listOfNotNull(
            f?.let { "f/" + trim(it) },
            time?.let { if (it < 1) "1/" + Math.round(1 / it) + " s" else trim(it) + " s" },
            iso?.let { "ISO $it" },
        ).joinToString(" · ").ifEmpty { null }
        // EXIF's own orientation turns the sizes round for a portrait shot.
        val turned = exif?.rotationDegrees?.let { it == 90 || it == 270 } == true
        val w = bounds.outWidth.takeIf { it > 0 }
        val h = bounds.outHeight.takeIf { it > 0 }
        return Facts(
            width = if (turned) h else w,
            height = if (turned) w else h,
            camera = camera,
            exposure = exposure,
            takenMs = exif?.dateTimeOriginal ?: exif?.dateTime,
        )
    }

    private fun trim(value: Double): String =
        if (value == Math.floor(value)) value.toLong().toString() else "%.1f".format(value)

    private fun media(context: Context, path: String): Facts = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, DocFiles.uriOf(context, path))
            Facts(
                width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull(),
                height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull(),
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
            )
        } finally {
            r.release()
        }
    }.getOrDefault(Facts())

    /**
     * An app package's name, id, version and icon, read the way the installer
     * reads it. Only a file on the phone: Android reads a package by path.
     */
    fun app(context: Context, path: String): Pair<Facts, Drawable?>? {
        if (DocFiles.isDoc(path)) return null
        return runCatching {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val info = pm.getPackageArchiveInfo(path, 0) ?: return null
            val app = info.applicationInfo ?: return null
            app.sourceDir = path
            app.publicSourceDir = path
            val version = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            Facts(
                appLabel = pm.getApplicationLabel(app).toString(),
                appPackage = info.packageName,
                appVersion = listOfNotNull(info.versionName, "($version)").joinToString(" "),
                appMinSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) app.minSdkVersion else null,
            ) to runCatching { pm.getApplicationIcon(app) }.getOrNull()
        }.getOrNull()
    }

    private fun pdfDescriptor(context: Context, path: String): ParcelFileDescriptor? = runCatching {
        if (DocFiles.isDoc(path)) {
            context.contentResolver.openFileDescriptor(android.net.Uri.parse(path), "r")
        } else {
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }.getOrNull()

    fun pdfPages(context: Context, path: String): Int? = runCatching {
        pdfDescriptor(context, path)?.use { fd -> PdfRenderer(fd).use { it.pageCount } }
    }.getOrNull()

    /**
     * Page [index] of a PDF drawn [width] pixels wide on white, and how many
     * pages there are. Null when the PDF is locked or broken.
     */
    fun pdfPage(context: Context, path: String, index: Int, width: Int): Pair<Bitmap, Int>? = runCatching {
        pdfDescriptor(context, path)?.use { fd ->
            PdfRenderer(fd).use { pdf ->
                val count = pdf.pageCount
                if (count == 0) return@use null
                pdf.openPage(index.coerceIn(0, count - 1)).use { page ->
                    val height = (width.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap to count
                }
            }
        }
    }.getOrNull()
}
