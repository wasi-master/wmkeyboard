package com.wasimaster.wmkeyboard.core.shell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A screenshot without the system's consent dialog, through `screencap -p` as
 * the shell user (Shizuku) or root. An input method that starts the
 * MediaProjection prompt loses the field it was typing into; this keeps it.
 *
 * Runs only when the user presses a tool's capture button, behind
 * [ShellFeatures.capturesScreen]. The PNG streams straight back to the app
 * ([ShellAccess.execBytes]), so nothing is left in `/data/local/tmp`; the copy
 * written here lives in the app's cache and the caller deletes it when done.
 * Windows marked secure (FLAG_SECURE) come out black, as in any screenshot.
 */
object ScreenCapture {

    private const val DIR = "screen-capture"

    /** Old captures a crash or a closed panel left behind are cleared after this long. */
    private const val STALE_MS = 60 * 60 * 1000L

    /**
     * Captures the screen into a PNG in the cache, cut off at [cropBottomPx]
     * (screen pixels; the keyboard's top edge) when it is above the bottom,
     * so the keyboard does not photograph itself. Null when no shell door is
     * open or the capture failed.
     */
    suspend fun capture(context: Context, cropBottomPx: Int? = null): File? {
        val bytes = ShellAccess.execBytes(context, "screencap -p") ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, DIR).apply { mkdirs() }
                val now = System.currentTimeMillis()
                dir.listFiles()?.filter { now - it.lastModified() > STALE_MS }?.forEach { it.delete() }
                val file = File(dir, "screen-$now.png")
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
                val cut = cropBottomPx?.takeIf { it in 1 until bitmap.height }
                if (cut == null) {
                    file.writeBytes(bytes)
                } else {
                    val cropped = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, cut)
                    file.outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    if (cropped !== bitmap) cropped.recycle()
                }
                bitmap.recycle()
                file
            }.getOrNull()
        }
    }
}
