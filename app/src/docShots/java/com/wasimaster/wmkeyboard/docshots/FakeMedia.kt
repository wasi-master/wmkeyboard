package com.wasimaster.wmkeyboard.docshots

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * A photo library for the gallery tool's pictures: MediaStore's authority
 * answered by a provider that lists a dozen made-up photos and two videos,
 * and draws each one (sky, sun, hills) when its thumbnail is asked for.
 * Nothing here is anyone's photo.
 */
internal fun Seed.photoLibrary() {
    val app = context.applicationContext as android.app.Application
    shadowOf(app).grantPermissions(
        android.Manifest.permission.READ_MEDIA_IMAGES,
        android.Manifest.permission.READ_MEDIA_VIDEO,
        android.Manifest.permission.READ_EXTERNAL_STORAGE,
    )
    Robolectric.setupContentProvider(FakeMediaProvider::class.java, MediaStore.AUTHORITY)
}

private class FakeMedia(
    val id: Long,
    val name: String,
    val bucket: String,
    val daysAgo: Int,
    val video: Boolean = false,
)

private val LIBRARY = listOf(
    FakeMedia(101, "IMG_20261115_071204.jpg", "Camera", 1),
    FakeMedia(102, "IMG_20261115_071230.jpg", "Camera", 1),
    FakeMedia(103, "VID_20261114_181502.mp4", "Camera", 2, video = true),
    FakeMedia(104, "IMG_20261114_181455.jpg", "Camera", 2),
    FakeMedia(105, "IMG_20261112_123010.jpg", "Camera", 4),
    FakeMedia(106, "Screenshot_20261111.png", "Screenshots", 5),
    FakeMedia(107, "IMG_20261109_170344.jpg", "Camera", 7),
    FakeMedia(108, "IMG_20261105_064120.jpg", "Camera", 11),
    FakeMedia(109, "VID_20261102_200011.mp4", "Camera", 14, video = true),
    FakeMedia(110, "IMG_20261028_155902.jpg", "Camera", 19),
    FakeMedia(111, "IMG_20261020_090017.jpg", "Camera", 27),
    FakeMedia(112, "IMG_20261012_174433.jpg", "Camera", 35),
    FakeMedia(113, "IMG_20261003_081120.jpg", "Camera", 44),
    FakeMedia(114, "IMG_20260921_191506.jpg", "Camera", 56),
)

class FakeMediaProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, queryArgs: Bundle?, cancellationSignal: CancellationSignal?): Cursor =
        rows(uri, projection)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        rows(uri, projection)

    private fun rows(uri: Uri, projection: Array<out String>?): Cursor {
        val columns = projection?.takeIf { it.isNotEmpty() }?.map { it }?.toTypedArray()
            ?: arrayOf(MediaStore.Files.FileColumns._ID)
        val cursor = MatrixCursor(columns)
        val one = uri.lastPathSegment?.toLongOrNull()
        val now = System.currentTimeMillis()
        for (m in LIBRARY) {
            if (one != null && one != m.id) continue
            val taken = now - m.daysAgo * 86_400_000L
            cursor.addRow(
                columns.map<String, Any?> { c ->
                    when (c) {
                        "_id" -> m.id
                        "media_type" -> if (m.video) 3 else 1
                        "mime_type" -> if (m.video) "video/mp4" else if (m.name.endsWith(".png")) "image/png" else "image/jpeg"
                        "_display_name" -> m.name
                        "width" -> 1600
                        "height" -> 1200
                        "_size" -> 2_400_000L
                        "date_added", "date_modified" -> taken / 1000
                        "datetaken" -> taken
                        "orientation" -> 0
                        "duration" -> if (m.video) 21_000L else null
                        "bucket_id" -> m.bucket.hashCode().toString()
                        "bucket_display_name" -> m.bucket
                        "relative_path" -> if (m.bucket == "Camera") "DCIM/Camera/" else "Pictures/Screenshots/"
                        "is_favorite" -> if (m.id == 104L) 1 else 0
                        "_data" -> "/storage/emulated/0/DCIM/${m.name}"
                        else -> null
                    }
                }.toTypedArray(),
            )
        }
        return cursor
    }

    override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?, signal: CancellationSignal?): AssetFileDescriptor =
        AssetFileDescriptor(openFile(uri, "r"), 0, AssetFileDescriptor.UNKNOWN_LENGTH)

    override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?): AssetFileDescriptor =
        openTypedAssetFile(uri, mimeTypeFilter, opts, null)

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val id = uri.lastPathSegment?.toLongOrNull() ?: 101L
        val file = File(context!!.cacheDir, "fake-media-$id.jpg")
        if (!file.exists()) file.outputStream().use { draw(id).compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/jpeg"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    /** A landscape: a sky from [id]'s hue, a sun, two rows of hills. */
    private fun draw(id: Long): Bitmap {
        val w = 400
        val h = 300
        val hue = (id * 47 % 360).toFloat()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val sky = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.95f)),
                Color.HSVToColor(floatArrayOf((hue + 40) % 360, 0.35f, 1f)),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), sky)
        c.drawCircle(80f + (id % 5) * 50f, 90f, 34f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 236, 170) })
        fun hills(base: Float, amp: Float, color: Int, phase: Float) {
            val p = Path().apply {
                moveTo(0f, h.toFloat())
                var x = 0f
                while (x <= w) {
                    lineTo(x, base + amp * kotlin.math.sin((x / w * 6.28f) + phase))
                    x += 8f
                }
                lineTo(w.toFloat(), h.toFloat())
                close()
            }
            c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        }
        hills(190f, 22f, Color.HSVToColor(floatArrayOf((hue + 120) % 360, 0.45f, 0.55f)), id.toFloat())
        hills(235f, 16f, Color.HSVToColor(floatArrayOf((hue + 140) % 360, 0.55f, 0.35f)), id * 2f)
        return bmp
    }
}
