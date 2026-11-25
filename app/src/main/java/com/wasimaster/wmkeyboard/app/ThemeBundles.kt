package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.net.Uri
import com.wasimaster.wmkeyboard.core.theme.ThemeCodec
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.core.theme.withExtractedImages
import com.wasimaster.wmkeyboard.core.theme.withFreshIds

/**
 * A zip of theme files, as Export selected writes it ([PackBundle]), read for
 * the theme screen's import and for one opened from a file manager or shared
 * from a chat app.
 *
 * Read a theme at a time: each one's pictures go to files as it is read, so
 * what is kept is the theme without them. Saving is the caller's, in one
 * write for the whole zip (`replaceCustomThemes`): a write per theme rewrote
 * the whole settings store and re-encoded the growing theme library each
 * time, and a zip of 193 themes did that 193 times over.
 */
internal object ThemeBundles {

    /** Whether [uri] is a zip of theme files, by its first entry. Blocking. */
    fun isBundle(context: Context, uri: Uri): Boolean = PackBundle.isBundle(context, uri, ThemeCodec.FILE_EXTENSION)

    /**
     * Every theme in the zip at [uri], with fresh ids from [idStem] and its
     * pictures already in the theme images folder. [onRead] gets the count so
     * far, for a progress line. An entry that is not a theme is passed over.
     * Blocking: call it off the main thread.
     */
    fun read(context: Context, uri: Uri, idStem: String, onRead: (Int) -> Unit = {}): List<ThemeSpec> {
        val dir = themeImagesDir(context)
        val themes = ArrayList<ThemeSpec>()
        runCatching {
            PackBundle.forEachPack(context, uri, ThemeCodec.FILE_EXTENSION) { _, input ->
                val parsed = input?.use { it.readBytes().decodeToString() }?.let(ThemeCodec::decode)
                if (parsed != null) {
                    themes += parsed.withFreshIds("${idStem}_z${themes.size}").withExtractedImages(dir)
                    onRead(themes.size)
                }
            }
        }
        return themes
    }
}
