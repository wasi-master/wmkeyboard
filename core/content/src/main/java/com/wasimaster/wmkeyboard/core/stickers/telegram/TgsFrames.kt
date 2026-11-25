package com.wasimaster.wmkeyboard.core.stickers.telegram

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import com.airbnb.lottie.AsyncUpdates
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import kotlin.math.ceil

/**
 * The frames of a Telegram animated sticker.
 *
 * A `.tgs` file is a Lottie animation (After Effects exported as JSON),
 * gzipped. Nothing on Android plays one and no other app takes one as a
 * sticker, so it is drawn here frame by frame with Lottie, and [StickerImage]
 * turns the frames into an animated WebP the same way it turns an animated
 * PNG into one.
 *
 * Lottie animations run at 60 frames a second, which is twice what a sticker
 * needs and twice the encoding, so frames are taken at [MAX_FPS] at most.
 *
 * [com.wasimaster.wmkeyboard.core.stickers.StickerImage]
 */
object TgsFrames {

    private const val MAX_FPS = 30f

    /**
     * Telegram's own limit is 64 KB gzipped. Unpacked, the JSON is a few
     * hundred KB; this is far past that and still small enough to hold.
     */
    private const val MAX_JSON_BYTES = 8 * 1024 * 1024
    private const val MS_PER_SECOND = 1000f
    private const val GZIP_MAGIC_0 = 0x1F
    private const val GZIP_MAGIC_1 = 0x8B
    private const val BYTE = 0xFF

    /** True when [bytes] are gzipped, which among sticker formats only a `.tgs` is. */
    fun isTgs(bytes: ByteArray): Boolean =
        bytes.size > 2 && (bytes[0].toInt() and BYTE) == GZIP_MAGIC_0 && (bytes[1].toInt() and BYTE) == GZIP_MAGIC_1

    /** A parsed animation, for [drawable]. Parse it off the main thread. */
    class Animation internal constructor(internal val composition: LottieComposition) {
        /**
         * A drawable that plays the animation over and over. Main thread only:
         * it is driven by the display's frame clock. Stop it (it is an
         * `Animatable`) when the view it is in goes away.
         */
        fun drawable(): Drawable = LottieDrawable().apply {
            // Qualified: a bare `composition` here is the drawable's own, still null.
            setComposition(this@Animation.composition)
            repeatCount = LottieDrawable.INFINITE
            playAnimation()
        }
    }

    /** The animation in [bytes], or null when they are not a `.tgs` Lottie can read. Blocking. */
    fun load(bytes: ByteArray): Animation? = composition(bytes)?.let(::Animation)

    /**
     * Calls [onFrame] with each frame of [bytes], drawn on a transparent
     * [size] by [size] canvas, and how long it shows. The bitmap is reused for
     * the next frame. Returns the number of frames delivered, 0 when the file
     * could not be read.
     *
     * Frames are spaced out further when the animation would otherwise come
     * to more than [maxFrames]. Blocking, and not for the main thread.
     */
    fun forEach(bytes: ByteArray, size: Int, maxFrames: Int, onFrame: (frame: Bitmap, durationMs: Int) -> Unit): Int {
        val composition = composition(bytes) ?: return 0
        return runCatching { render(composition, size, maxFrames, onFrame) }.getOrDefault(0)
    }

    private fun render(composition: LottieComposition, size: Int, maxFrames: Int, onFrame: (Bitmap, Int) -> Unit): Int {
        val fps = composition.frameRate.takeIf { it > 0f } ?: MAX_FPS
        val start = composition.startFrame
        val span = composition.endFrame - start
        if (span <= 0f) return 0
        // Whole source frames per output frame: at least enough to come down
        // to MAX_FPS, more when the animation is too long for maxFrames.
        val step = maxOf(1f, ceil(fps / MAX_FPS), ceil(span / maxFrames))
        val durationMs = (step * MS_PER_SECOND / fps).toInt()

        val drawable = LottieDrawable()
        // Progress is set by hand and drawn straight after; an update on
        // Lottie's own thread would race the draw.
        drawable.asyncUpdates = AsyncUpdates.DISABLED
        drawable.setComposition(composition)
        drawable.setBounds(0, 0, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        var delivered = 0
        try {
            var frame = 0f
            while (frame < span && delivered < maxFrames) {
                drawable.progress = frame / span
                bitmap.eraseColor(Color.TRANSPARENT)
                drawable.draw(canvas)
                onFrame(bitmap, durationMs)
                delivered++
                frame += step
            }
        } finally {
            drawable.clearComposition()
            bitmap.recycle()
        }
        return delivered
    }

    private fun composition(bytes: ByteArray): LottieComposition? {
        if (!isTgs(bytes)) return null
        val json = runCatching { gunzip(bytes) }.getOrNull() ?: return null
        // No cache key: a composition is used once, and Lottie's cache would
        // keep a whole pack of them alive for nothing.
        return runCatching {
            LottieCompositionFactory.fromJsonInputStreamSync(ByteArrayInputStream(json), null).value
        }.getOrNull()
    }

    /** [bytes] unpacked, or null past [MAX_JSON_BYTES]: a small file can unpack to a great deal. */
    private fun gunzip(bytes: ByteArray): ByteArray? {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > MAX_JSON_BYTES) return null
            }
            return out.toByteArray()
        }
    }
}
