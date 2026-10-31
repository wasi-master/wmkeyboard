package com.wasimaster.wmkeyboard.core.docscan

import android.graphics.Bitmap

/**
 * What [ThemeCopier] read off a picture: the theme as JSON a ThemeCodec decodes,
 * with the sizes it measured under `_copy`, and the pictures the theme needs,
 * named by the ThemeSpec field each one fills.
 */
class CopiedThemeImage(val json: String, val images: Map<String, Bitmap>)

/** A run of text an OCR engine read, in the picture's pixels. */
class ReadText(val text: String, val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * Reads a keyboard theme off a screenshot of a keyboard; see "Theme copier" in
 * native/docscan-jni/README.md. Each call needs [DocScanNative.available]. Takes
 * a few hundred milliseconds, so not on the main thread.
 */
object ThemeCopier {
    val available: Boolean get() = DocScanNative.available

    /** Where things the size of a label are in [picture], as x, y, width, height. */
    fun labelCandidates(picture: Bitmap): List<IntArray> {
        if (!available) return emptyList()
        val argb = if (picture.config == Bitmap.Config.ARGB_8888) picture else picture.copy(Bitmap.Config.ARGB_8888, false)
        val flat = runCatching { DocScanNative.nativeLabelCandidates(argb) }.getOrNull() ?: return emptyList()
        return (0 until flat.size / 4).map { flat.copyOfRange(it * 4, it * 4 + 4) }
    }

    /**
     * The theme in [pictures], for a screen [widthDp] wide, or null when the first one
     * shows no keyboard. Any more are other layers of the same keyboard, whose labels
     * differ, so a photo behind the keys comes back cleaner. [read] says what an OCR
     * engine found in the first, for a picture too busy to find the keys in any other way.
     */
    fun copy(pictures: List<Bitmap>, widthDp: Float, read: List<ReadText> = emptyList()): CopiedThemeImage? {
        if (!available || pictures.isEmpty()) return null
        val argb = pictures.map { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }
        val texts = read.takeIf { it.isNotEmpty() }?.map { it.text }?.toTypedArray()
        val boxes = read.takeIf { it.isNotEmpty() }?.flatMap { listOf(it.x, it.y, it.width, it.height) }?.toIntArray()
        val found = runCatching { DocScanNative.nativeCopyTheme(argb.toTypedArray(), widthDp, texts, boxes) }.getOrNull() ?: return null
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
