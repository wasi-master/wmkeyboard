package com.wasimaster.wmkeyboard.core.media

import android.graphics.Bitmap

/**
 * JNI surface of libwebp's animation encoder (libwmwebp.so, built from
 * native/webp-jni), wrapped by [AnimatedWebpEncoder]. Shipped in every build's
 * base APK (the `base` list of native/gates.json), so there is no gate to
 * fetch; [available] is false only when the library failed to load, and then
 * callers fall back to [AnimatedWebpWriter].
 *
 * A handle is one encoder. Not safe for two threads at once.
 */
internal object WebpAnimNative {
    const val LIBRARY = "wmwebp"

    /** Loads the library the first time it is read. */
    val available: Boolean by lazy {
        try {
            System.loadLibrary(LIBRARY)
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * A new encoder for a [width] by [height] canvas, or 0 when it could not
     * be made. A negative [kmin] or [kmax] keeps libwebp's own key-frame
     * spacing.
     */
    external fun nativeNew(
        width: Int,
        height: Int,
        loopCount: Int,
        minimizeSize: Boolean,
        allowMixed: Boolean,
        kmin: Int,
        kmax: Int,
    ): Long

    /** Adds [bitmap], ARGB_8888 and the canvas's size, shown from [timestampMs]. */
    external fun nativeAddBitmap(
        handle: Long,
        bitmap: Bitmap,
        timestampMs: Int,
        quality: Float,
        lossless: Boolean,
        method: Int,
    ): Boolean

    /** Adds unpremultiplied ARGB ints, row by row, as `Bitmap.getPixels` fills them. */
    external fun nativeAddArgb(
        handle: Long,
        argb: IntArray,
        timestampMs: Int,
        quality: Float,
        lossless: Boolean,
        method: Int,
    ): Boolean

    /** Ends the last frame at [endTimestampMs] and returns the file, or null. Once per handle. */
    external fun nativeAssemble(handle: Long, endTimestampMs: Int): ByteArray?

    external fun nativeDelete(handle: Long)
}
