package com.wasimaster.wmkeyboard.core.docscan

import android.graphics.Bitmap

/**
 * What [ThemeCopier] read off a picture: the theme as JSON a ThemeCodec decodes,
 * with the sizes it measured under `_copy`, and the pictures the theme needs,
 * named by the ThemeSpec field each one fills.
 */
class CopiedThemeImage(val json: String, val images: Map<String, Bitmap>)

/**
 * Reads a keyboard theme off a screenshot of a keyboard; see "Theme copier" in
 * native/docscan-jni/README.md. Each call needs [DocScanNative.available]. Takes
 * a few hundred milliseconds, so not on the main thread.
 */
object ThemeCopier {
    val available: Boolean get() = DocScanNative.available

    /** The theme in [picture], for a screen [widthDp] wide, or null when it shows no keyboard. */
    fun copy(picture: Bitmap, widthDp: Float): CopiedThemeImage? {
        if (!available) return null
        val argb = if (picture.config == Bitmap.Config.ARGB_8888) picture else picture.copy(Bitmap.Config.ARGB_8888, false)
        val found = runCatching { DocScanNative.nativeCopyTheme(argb, widthDp) }.getOrNull() ?: return null
        val json = found.firstOrNull() as? String ?: return null
        val images = buildMap {
            for (i in 1 until found.size - 1 step 2) {
                val name = found[i] as? String ?: continue
                val pixels = found[i + 1] as? IntArray ?: continue
                put(name, Bitmap.createBitmap(pixels, 2, pixels[0], pixels[0], pixels[1], Bitmap.Config.ARGB_8888))
            }
        }
        return CopiedThemeImage(json, images)
    }
}
