package com.wasimaster.wmkeyboard.core.media

import android.graphics.Bitmap
import java.io.Closeable

/**
 * An animated WebP encoded by libwebp's own animation encoder
 * (`WebPAnimEncoder`, in libwmwebp from native/webp-jni).
 *
 * Unlike [AnimatedWebpWriter], which wraps whole still frames, this stores
 * each frame as the rectangle that changed since the one before, blended over
 * it where that is smaller, so a sticker whose text moves over a still
 * picture costs little more than the picture. That is what keeps an animated
 * sticker under WhatsApp's 500 KB.
 *
 * Frames are whole pictures of the canvas, added in order, each with the time
 * it starts showing; [finish] gives the time the last one stops. A frame's
 * pixels are copied during the call, so the caller may draw the next frame
 * into the same bitmap at once. Calls are serialised; [close] frees the
 * native encoder and is safe to call twice.
 *
 * When the library is missing ([available] false) every call fails quietly:
 * [addFrame] returns false and [finish] null, and the caller falls back to
 * [AnimatedWebpWriter].
 *
 * @param quality 0 to 100: the lossy quality, or the effort of a [lossless] encode.
 * @param loopCount 0 for for ever.
 * @param minimizeSize try every way of storing each frame and keep the
 *   smallest. Slower; also turns off key-frame insertion.
 * @param method libwebp's speed/size trade-off for each frame, 0 (fast) to 6 (small).
 * @param keyFrames let libwebp store a frame whole where that is smaller than
 *   storing what changed. Every frame is then encoded both ways; without it,
 *   once. A looping sticker never seeks, so it loses nothing but a few bytes.
 */
class AnimatedWebpEncoder(
    private val width: Int,
    private val height: Int,
    private val quality: Float = 75f,
    private val lossless: Boolean = false,
    loopCount: Int = 0,
    minimizeSize: Boolean = true,
    private val method: Int = METHOD,
    keyFrames: Boolean = true,
) : Closeable {

    companion object {
        /** Whether libwmwebp is loaded. Loads it the first time it is read. */
        val available: Boolean get() = WebpAnimNative.available

        /** libwebp's speed/size trade-off, 0 (fast) to 6 (small); 4 is its own default. */
        private const val METHOD = 4

        /** Negative: libwebp's own key-frame spacing. */
        private const val LIBRARY_DEFAULT = -1

        /** A key-frame spacing of 0: libwebp inserts none. */
        private const val NO_KEY_FRAMES = 0
    }

    private var handle: Long = 0
    private var finished = false

    init {
        require(width > 0 && height > 0) { "canvas $width x $height" }
        if (available) {
            handle = WebpAnimNative.nativeNew(
                width,
                height,
                loopCount,
                minimizeSize,
                false,
                if (keyFrames) LIBRARY_DEFAULT else NO_KEY_FRAMES,
                if (keyFrames) LIBRARY_DEFAULT else NO_KEY_FRAMES,
            )
        }
    }

    /**
     * Adds [bitmap], which must be the canvas's size, shown from [timestampMs].
     * Any config is taken; one other than ARGB_8888 (a hardware bitmap, say)
     * is copied first. False, adding nothing, when it cannot be added.
     */
    @Synchronized
    fun addFrame(bitmap: Bitmap, timestampMs: Int): Boolean {
        if (!open() || bitmap.width != width || bitmap.height != height) return false
        if (bitmap.config == Bitmap.Config.ARGB_8888) {
            return WebpAnimNative.nativeAddBitmap(handle, bitmap, timestampMs, quality, lossless, method)
        }
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return false
        return try {
            WebpAnimNative.nativeAddBitmap(handle, copy, timestampMs, quality, lossless, method)
        } finally {
            copy.recycle()
        }
    }

    /**
     * Adds [argb], width times height unpremultiplied ARGB ints row by row (what
     * `Bitmap.getPixels` fills), shown from [timestampMs].
     */
    @Synchronized
    fun addFrame(argb: IntArray, timestampMs: Int): Boolean {
        if (!open() || argb.size != width * height) return false
        return WebpAnimNative.nativeAddArgb(handle, argb, timestampMs, quality, lossless, method)
    }

    /**
     * The finished file, its last frame showing until [endTimestampMs]; null
     * when nothing was added or the encoder failed. Once: after it, every add
     * fails and a second call returns null.
     */
    @Synchronized
    fun finish(endTimestampMs: Int): ByteArray? {
        if (!open()) return null
        finished = true
        return WebpAnimNative.nativeAssemble(handle, endTimestampMs)
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) WebpAnimNative.nativeDelete(handle)
        handle = 0
    }

    private fun open(): Boolean = handle != 0L && !finished
}
