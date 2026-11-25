package com.wasimaster.wmkeyboard.core.media.skottie

import android.graphics.Bitmap
import java.io.Closeable

/**
 * One Lottie animation loaded into Skottie ([SkottieNative]). Made with
 * [load], which returns null when the library is not here yet or the JSON
 * does not parse. Calls are serialised; [close] frees the native animation
 * and is safe to call twice, after which every render fails.
 */
class SkottieAnimation private constructor(private var handle: Long) : Closeable {

    /** The animation's own canvas, in pixels. */
    val width: Int = SkottieNative.width(handle)
    val height: Int = SkottieNative.height(handle)
    val fps: Float = SkottieNative.fps(handle)

    /** Seconds. */
    val duration: Float = SkottieNative.duration(handle)

    /** Whole frames in [duration] at [fps], at least 1. */
    val frameCount: Int get() = (duration * fps).toInt().coerceAtLeast(1)

    /** See [SkottieNative.render]. */
    @Synchronized
    fun render(frameTimeSeconds: Float, bitmap: Bitmap): Boolean =
        handle != 0L && SkottieNative.render(handle, frameTimeSeconds, bitmap)

    /** See [SkottieNative.renderPixels]. */
    @Synchronized
    fun renderPixels(frameTimeSeconds: Float, width: Int, height: Int, argb: IntArray): Boolean =
        handle != 0L && SkottieNative.renderPixels(handle, frameTimeSeconds, width, height, argb)

    @Synchronized
    override fun close() {
        if (handle != 0L) SkottieNative.release(handle)
        handle = 0
    }

    companion object {
        /**
         * [json] with its text already replaced; see [SkottieNative.load] for
         * [resourceDir] and the fonts. [fontFamilies] may be shorter than
         * [fontPaths]: the rest go by their own family names.
         */
        fun load(
            json: String,
            resourceDir: String? = null,
            fontPaths: List<String> = emptyList(),
            fontFamilies: List<String> = emptyList(),
        ): SkottieAnimation? {
            if (!SkottieNative.available) return null
            val names = Array(fontPaths.size) { fontFamilies.getOrNull(it).orEmpty() }
            val handle = SkottieNative.load(json, resourceDir, fontPaths.toTypedArray(), names)
            return if (handle == 0L) null else SkottieAnimation(handle)
        }
    }
}
