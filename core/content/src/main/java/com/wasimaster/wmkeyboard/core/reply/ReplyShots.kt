package com.wasimaster.wmkeyboard.core.reply

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import java.io.File

/**
 * Screenshots as context for the AI reply tool (#234).
 *
 * The keyboard cannot read the conversation above it, and it cannot take a
 * picture of the screen either — screen capture needs `MediaProjection`, whose
 * consent dialog is an activity, and an input method that starts an activity
 * loses the field it was typing into. What it *can* do is notice the one the
 * user takes with the hardware keys, which is why this reads MediaStore rather
 * than capturing anything.
 */
object ReplyShots {

    /** One screenshot on the device. */
    data class Shot(
        val uri: Uri,
        /** Absolute path where there is one; blank on stores that hide it. */
        val path: String,
        val mimeType: String,
        /** Seconds since the epoch, as MediaStore keeps it. */
        val addedAt: Long,
        val name: String,
    )

    /** An encoded picture on its way to a model. */
    data class Encoded(val mimeType: String, val base64: String, val bytes: Int)

    /**
     * Longest edge a screenshot is scaled to before it is sent.
     *
     * A modern phone screenshot is 1080x2400 and several megabytes once it is
     * base64; models charge for every pixel and some refuse outright above a
     * few megapixels. 1280 keeps chat text comfortably legible — the thing the
     * model has to read — at roughly a tenth of the bytes.
     */
    const val MAX_EDGE = 1280

    /** JPEG quality for the scaled copy. Chat bubbles are flat colour and survive it. */
    private const val QUALITY = 80

    /** Hard ceiling on one encoded picture, after scaling. */
    const val MAX_BYTES = 4 * 1024 * 1024

    /**
     * The newest screenshots, newest first.
     *
     * Empty when the images permission has not been granted — the caller checks
     * that, because the message to show about it belongs to the keyboard rather
     * than here.
     */
    fun recent(context: Context, limit: Int = 12): List<Shot> {
        val projection = buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.Images.Media.DATE_ADDED)
            add(MediaStore.Images.Media.MIME_TYPE)
            add(MediaStore.Images.Media.DISPLAY_NAME)
            add(MediaStore.Images.Media.DATA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.Images.Media.RELATIVE_PATH)
        }.toTypedArray()
        val cursor = runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )
        }.getOrNull() ?: return emptyList()
        val shots = ArrayList<Shot>(limit)
        cursor.use { c ->
            val idCol = c.getColumnIndex(MediaStore.Images.Media._ID)
            val dateCol = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
            val mimeCol = c.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
            val nameCol = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
            val dataCol = c.getColumnIndex(MediaStore.Images.Media.DATA)
            val relCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                c.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
            } else {
                -1
            }
            // Walked rather than filtered in SQL: the column that says
            // "screenshot" is a different one on different Android versions,
            // and a wrong WHERE clause returns nothing with no way to tell.
            // The sort is newest-first, so the walk stops as soon as the list
            // is full.
            while (c.moveToNext() && shots.size < limit) {
                val name = nameCol.takeIf { it >= 0 }?.let { c.getString(it) }.orEmpty()
                val path = dataCol.takeIf { it >= 0 }?.let { c.getString(it) }.orEmpty()
                val relative = relCol.takeIf { it >= 0 }?.let { c.getString(it) }.orEmpty()
                if (!isScreenshot(name, path, relative)) continue
                val id = idCol.takeIf { it >= 0 }?.let { c.getLong(it) } ?: continue
                shots.add(
                    Shot(
                        uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                        path = path,
                        mimeType = mimeCol.takeIf { it >= 0 }?.let { c.getString(it) } ?: "image/png",
                        addedAt = dateCol.takeIf { it >= 0 }?.let { c.getLong(it) } ?: 0L,
                        name = name,
                    ),
                )
            }
        }
        return shots
    }

    /** The newest screenshot taken in the last [withinSeconds], or null. */
    fun newest(context: Context, withinSeconds: Long = 120): Shot? {
        val shot = recent(context, limit = 1).firstOrNull() ?: return null
        val now = System.currentTimeMillis() / 1000
        return shot.takeIf { now - it.addedAt <= withinSeconds }
    }

    /**
     * Whether a row is a screenshot rather than a photo.
     *
     * Three signals because no one of them is present everywhere: the folder
     * (`Pictures/Screenshots`, `DCIM/Screenshots`), the file name, and the
     * legacy `DATA` path. OEMs translate the folder name on localised devices,
     * which is why the file name is checked too — and why a false negative is
     * the failure mode rather than a false positive: the user can still add a
     * picture by hand from the clipboard.
     */
    internal fun isScreenshot(name: String, path: String, relative: String): Boolean {
        val hay = (name + '\u0000' + path + '\u0000' + relative).lowercase()
        return "screenshot" in hay || "screen_shot" in hay || "screen-shot" in hay
    }

    /**
     * [uri] read, scaled to [MAX_EDGE] and base64-encoded, or null when it
     * cannot be read or stays too big after scaling.
     */
    fun encode(context: Context, uri: Uri): Encoded? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sample = sampleSize(bounds.outWidth, bounds.outHeight)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = context.contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return@runCatching null
        encodeBitmap(decoded)
    }.getOrNull()

    /** The same for a file the keyboard already holds (a clipboard image). */
    fun encode(file: File): Encoded? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return@runCatching null
        encodeBitmap(decoded)
    }.getOrNull()

    /** [uri] decoded and scaled, for the on-device reader. */
    fun bitmap(context: Context, uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()

    /** The same for a file. */
    fun bitmap(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
        BitmapFactory.decodeFile(file.absolutePath, opts)
    }.getOrNull()

    /**
     * [uri] scaled and compressed like [encode], as raw JPEG bytes: what the
     * on-device model takes, which wants the picture itself rather than a
     * base64 string meant for a JSON body.
     */
    fun jpeg(context: Context, uri: Uri): ByteArray? = encode(context, uri)
        ?.let { runCatching { Base64.decode(it.base64, Base64.NO_WRAP) }.getOrNull() }

    /**
     * [jpeg] of the part of the screenshot above [bottomCut], a fraction of
     * its height: the keyboard, when it was on screen, is cut off, so a model
     * asked to write the chat out does not read the keys.
     */
    fun jpegAbove(context: Context, uri: Uri, bottomCut: Float): ByteArray? = runCatching {
        val bitmap = bitmap(context, uri) ?: return null
        val height = (bitmap.height * bottomCut.coerceIn(MIN_KEPT, 1f)).toInt().coerceIn(1, bitmap.height)
        val kept = if (height < bitmap.height) {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, height).also { bitmap.recycle() }
        } else {
            bitmap
        }
        encodeBitmap(kept)?.let { Base64.decode(it.base64, Base64.NO_WRAP) }
    }.getOrNull()

    /** The least of a screenshot [jpegAbove] keeps, whatever the cut says. */
    private const val MIN_KEPT = 0.2f

    /**
     * The system's own "allow this app to delete…" request for gallery
     * entries the keyboard did not create, or null where there is none.
     *
     * From Android 11 that consent dialog is the only way to remove somebody
     * else's media, and it has to be started from an activity — the reply
     * tool's tidy-up hands this to a trampoline after the draft is already in
     * the field, so losing the field for a moment costs nothing. Only
     * `content://media/` rows qualify: a photo-picker URI is a read grant,
     * not something MediaStore will delete.
     */
    fun deleteRequest(context: Context, uris: List<Uri>): android.app.PendingIntent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val media = uris.filter { it.authority == MediaStore.AUTHORITY }
        if (media.isEmpty()) return null
        return runCatching { MediaStore.createDeleteRequest(context.contentResolver, media) }.getOrNull()
    }

    private fun encodeBitmap(bitmap: Bitmap): Encoded? {
        val scaled = scale(bitmap)
        val out = java.io.ByteArrayOutputStream()
        @Suppress("DEPRECATION")
        scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        val bytes = out.toByteArray()
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        return Encoded("image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP), bytes.size)
    }

    private fun scale(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_EDGE || longest == 0) return bitmap
        val ratio = MAX_EDGE.toFloat() / longest
        val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    /**
     * A power-of-two decode divisor that gets the picture near [MAX_EDGE]
     * before it is allocated. Decoding a 12-megapixel screenshot at full size
     * only to scale it is how an input method gets killed for memory.
     */
    internal fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= MAX_EDGE) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    /**
     * Removes a screenshot the reply used, as far as the platform allows.
     *
     * Honest about what it can do. The keyboard's own copy under `filesDir` is
     * its own file and always goes. The gallery entry is the user's, and from
     * Android 11 an app may only delete media it created — anything else needs
     * a system consent dialog, which is an activity, which an input method
     * cannot show without losing the field. So the delete is attempted and its
     * failure is reported rather than hidden: the caller says "removed from the
     * keyboard" when it comes back false.
     *
     * @return true when the gallery entry itself was removed.
     */
    fun delete(context: Context, uri: Uri?, copy: File?): Boolean {
        copy?.let { runCatching { it.delete() } }
        val target = uri ?: return false
        return runCatching { context.contentResolver.delete(target, null, null) > 0 }.getOrDefault(false)
    }
}
