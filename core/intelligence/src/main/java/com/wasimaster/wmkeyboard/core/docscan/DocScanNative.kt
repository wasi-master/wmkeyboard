package com.wasimaster.wmkeyboard.core.docscan

import android.graphics.Bitmap
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import kotlinx.coroutines.flow.StateFlow

/**
 * JNI surface of the built-in document scanner (libwmdocscan.so, built from
 * native/docscan-jni): page detection, perspective flattening and clean-up
 * on OpenCV's core and imgproc, and the theme copier, which shares the library
 * for the OpenCV in it. Optional at runtime: GitHub builds fetch it
 * the first time it is asked for, F-Droid builds it from source, Play
 * carries it in the on-demand `:feature:docscan` split.
 *
 * Every bitmap must be [Bitmap.Config.ARGB_8888] and mutable where it is
 * written. Nothing is kept between calls, so any thread may call.
 */
object DocScanNative {
    const val LIBRARY = "wmdocscan"

    private val library = GatedLibrary({ FeatureModules.docscan }) {
        runCatching { FeatureModules.loadLibrary(LIBRARY) }.isSuccess
    }

    /** Loads the library the first time it is read with the module present. */
    val available: Boolean get() = library.available

    /** Whether this build has the library, counting a module not fetched yet. Loads nothing then. */
    val shipped: Boolean get() = library.shipped

    val moduleState: StateFlow<ModuleState> get() = FeatureModules.docscan.state

    fun requestModule() = FeatureModules.docscan.requestInstall()

    /**
     * The page's corners, clockwise from top left as x, y pairs in [bitmap]'s
     * pixels, then the detector's confidence (0..1); null when it found none.
     */
    external fun nativeDetect(bitmap: Bitmap): FloatArray?

    /** The page inside [corners] (eight floats, as [nativeDetect] gives them) drawn flat over all of [dst]. */
    external fun nativeWarp(src: Bitmap, corners: FloatArray, dst: Bitmap): Boolean

    /** [bitmap] cleaned up in place; [mode] is a [PageFilter.code]. */
    external fun nativeEnhance(bitmap: Bitmap, mode: Int): Boolean

    /**
     * The keyboard theme [bitmap] shows, for a screen [widthDp] wide: its JSON,
     * then for each picture it needs, that picture's name and an IntArray of
     * width, height and ARGB pixels. Null when it shows no key grid.
     */
    external fun nativeCopyTheme(bitmap: Bitmap, widthDp: Float): Array<Any?>?
}
