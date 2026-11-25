package com.wasimaster.wmkeyboard.core.media.skottie

import android.graphics.Bitmap
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import kotlinx.coroutines.flow.StateFlow

/**
 * JNI surface of Skia's Skottie (libwmskottie.so, built from
 * native/skottie-jni), the Lottie player that draws imported sticker
 * templates exactly: their text layers use Skottie's own auto-fit extensions
 * (resize to fit, minimum and maximum size, maximum lines, vertical
 * alignment), which Lottie (airbnb), the default renderer, ignores.
 * Optional at runtime: GitHub and F-Droid builds fetch it the first time a
 * template asks for it, Play carries it in the on-demand `:feature:skottie`
 * split, and until then [available] is false. [SkottieAnimation] wraps a
 * handle.
 *
 * Text is shaped with HarfBuzz and broken into lines by Unicode's rules, so
 * Bengali and Arabic render as they should. Typefaces come from the font
 * files passed to [load] first, then from the phone's own fonts, and the
 * passed files also take part in fallback for characters the template's font
 * lacks.
 *
 * A handle is one animation, not safe for two threads at once.
 */
object SkottieNative {
    const val LIBRARY = "wmskottie"

    private val library = GatedLibrary({ FeatureModules.skottie }) {
        runCatching { FeatureModules.loadLibrary(LIBRARY) }.isSuccess
    }

    /** Loads the library the first time it is read with the module present. */
    val available: Boolean get() = library.available

    /** Whether this build has the library, counting a module not fetched yet. Loads nothing then. */
    val shipped: Boolean get() = library.shipped

    val moduleState: StateFlow<ModuleState> get() = FeatureModules.skottie.state

    fun requestModule() = FeatureModules.skottie.requestInstall()

    /**
     * Parses a Lottie animation, its text already written in. [resourceDir]
     * is where its image assets' `u`/`p` paths are read from (data: URIs need
     * none). [fontPaths] are font files, each registered under the name at
     * the same index of [fontFamilies] (the template's `fName` or `fFamily`;
     * empty for the file's own family name). 0 when the JSON does not parse.
     */
    external fun load(
        json: String,
        resourceDir: String?,
        fontPaths: Array<String>,
        fontFamilies: Array<String>,
    ): Long

    /** The animation's own canvas, in pixels, rounded up. */
    external fun width(handle: Long): Int

    external fun height(handle: Long): Int

    external fun fps(handle: Long): Float

    /** Seconds. */
    external fun duration(handle: Long): Float

    /**
     * Draws the frame at [frameTimeSeconds] into [bitmap] (ARGB_8888, mutable),
     * cleared to transparent first, the animation scaled to fit its size.
     */
    external fun render(handle: Long, frameTimeSeconds: Float, bitmap: Bitmap): Boolean

    /**
     * The same into [argb], [width] by [height] unpremultiplied ARGB ints, what
     * `Bitmap.setPixels` takes. For tests on a desktop JVM, which has no Bitmap.
     */
    external fun renderPixels(handle: Long, frameTimeSeconds: Float, width: Int, height: Int, argb: IntArray): Boolean

    external fun release(handle: Long)
}
