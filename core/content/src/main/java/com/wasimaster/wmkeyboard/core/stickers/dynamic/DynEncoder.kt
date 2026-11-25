package com.wasimaster.wmkeyboard.core.stickers.dynamic

import android.graphics.Bitmap
import android.os.Build
import com.wasimaster.wmkeyboard.core.media.AnimatedWebpWriter
import com.wasimaster.wmkeyboard.core.media.MediaMime
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Turns a [DynRenderer.Prepared] into the file that is sent.
 *
 * Stickers go out as WebP on the 512 px square WhatsApp asks for: a still
 * under 100 KB, an animation under 500 KB, which is what lets them travel as
 * `image/webp.wasticker` and show as stickers rather than photos. Memes with
 * a photo in them go out as plain images (PNG, or JPEG when large), since
 * they are pictures rather than stickers.
 *
 * Animated WebP goes through [animatedEncoder]. The default wraps still WebP
 * frames (every frame whole, so it runs at a lower frame rate to stay under
 * budget); the app swaps in libwebp's animation encoder, which stores only
 * what changes between frames, when that library is loaded.
 */
object DynEncoder {

    const val STICKER_SIZE = 512

    /** WhatsApp's limits for a sticker. */
    const val STILL_BUDGET = 100 * 1024
    const val ANIMATED_BUDGET = 500 * 1024

    /** Ten seconds is the longest animated sticker apps take. */
    private const val MAX_FRAMES_FULL = 150
    private const val MAX_FRAMES_WRAPPED = 75

    private val STILL_LADDER = intArrayOf(92, 82, 70, 55, 40)
    private val ANIMATED_LADDER = intArrayOf(80, 62, 45, 30)

    /** Takes frames and returns the animated file. */
    interface FrameSink {
        fun add(frame: Bitmap, durationMs: Int): Boolean
        fun finish(): ByteArray?
    }

    /** Makes a [FrameSink] for a [size]-square animation at a WebP [quality]. */
    fun interface AnimatedEncoderFactory {
        fun create(size: Int, quality: Int): FrameSink
    }

    /** The encoder; the frame rate it can afford is [animatedFps]. Swapped by the app at start-up. */
    @Volatile
    var animatedEncoder: AnimatedEncoderFactory = AnimatedEncoderFactory { size, quality -> WrappedSink(size, quality) }

    /** Frames a second the current encoder is given: fewer for the whole-frame fallback. */
    @Volatile
    var animatedFps: Float = WRAPPED_FPS

    @Volatile
    var animatedMaxFrames: Int = MAX_FRAMES_WRAPPED

    /** Marks [animatedEncoder] as the libwebp one, which can take the full rate. */
    fun useFullEncoder(factory: AnimatedEncoderFactory) {
        animatedEncoder = factory
        animatedFps = FULL_FPS
        animatedMaxFrames = MAX_FRAMES_FULL
    }

    data class Output(val file: File, val mime: String, val animated: Boolean)

    /**
     * Writes [prepared] as a sticker to [target] (the extension is set
     * here): animated when it moves and [animated] allows, else the poster
     * frame. Null when encoding failed.
     */
    fun sticker(prepared: DynRenderer.Prepared, target: File, animated: Boolean): Output? {
        if (animated && prepared.animated) {
            encodeAnimated(prepared)?.let { bytes ->
                val file = File(target.parentFile, target.nameWithoutExtension + ".webp")
                file.writeBytes(bytes)
                return Output(file, MediaMime.WEBP, animated = true)
            }
        }
        val frame = prepared.render(prepared.posterProgress, STICKER_SIZE)
        try {
            val bytes = webp(frame, STILL_BUDGET) ?: return null
            val file = File(target.parentFile, target.nameWithoutExtension + ".webp")
            file.writeBytes(bytes)
            return Output(file, MediaMime.WEBP, animated = false)
        } finally {
            frame.recycle()
        }
    }

    /**
     * Writes [prepared] as an image (a meme with a photo in it): its poster
     * frame at [size], PNG unless that is big, then JPEG.
     */
    fun image(prepared: DynRenderer.Prepared, target: File, size: Int = MEME_SIZE): Output? {
        val frame = prepared.render(prepared.posterProgress, size)
        try {
            val png = ByteArrayOutputStream().also { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            if (png.size <= PNG_LIMIT) {
                val file = File(target.parentFile, target.nameWithoutExtension + ".png")
                file.writeBytes(png)
                return Output(file, MediaMime.PNG, animated = false)
            }
            val flat = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(flat).apply {
                drawColor(android.graphics.Color.WHITE)
                drawBitmap(frame, 0f, 0f, null)
            }
            val jpeg = ByteArrayOutputStream().also { flat.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
            flat.recycle()
            val file = File(target.parentFile, target.nameWithoutExtension + ".jpg")
            file.writeBytes(jpeg)
            return Output(file, MediaMime.JPEG, animated = false)
        } finally {
            frame.recycle()
        }
    }

    /** A still WebP of [bitmap] under [budget], stepping the quality down. */
    fun webp(bitmap: Bitmap, budget: Int): ByteArray? {
        var last: ByteArray? = null
        for (quality in STILL_LADDER) {
            last = encodeWebp(bitmap, quality) ?: return last
            if (last.size <= budget) return last
        }
        return last
    }

    private fun encodeAnimated(prepared: DynRenderer.Prepared): ByteArray? {
        var best: ByteArray? = null
        for (quality in ANIMATED_LADDER) {
            val sink = runCatching { animatedEncoder.create(STICKER_SIZE, quality) }.getOrNull() ?: return best
            var failed = false
            val count = prepared.forEachFrame(STICKER_SIZE, animatedFps, animatedMaxFrames) { frame, durationMs ->
                if (!failed && !sink.add(frame, durationMs)) failed = true
            }
            val bytes = if (failed || count < 2) null else sink.finish()
            if (bytes == null) return best
            best = bytes
            if (bytes.size <= ANIMATED_BUDGET) return bytes
        }
        return best?.takeIf { it.size <= ANIMATED_BUDGET * 2 }
    }

    private fun encodeWebp(bitmap: Bitmap, quality: Int): ByteArray? {
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        val out = ByteArrayOutputStream()
        return if (bitmap.compress(format, quality, out)) out.toByteArray() else null
    }

    /** The whole-frame fallback: each frame a still WebP, wrapped into one animation. */
    private class WrappedSink(private val size: Int, private val quality: Int) : FrameSink {
        private val writer = AnimatedWebpWriter(size, size)

        override fun add(frame: Bitmap, durationMs: Int): Boolean {
            val still = encodeWebp(frame, quality) ?: return false
            return writer.addFrame(still, durationMs)
        }

        override fun finish(): ByteArray? = if (writer.frameCount >= 2) writer.build() else null
    }

    private const val WRAPPED_FPS = 12f
    private const val FULL_FPS = 25f
    private const val MEME_SIZE = 1024
    private const val PNG_LIMIT = 900 * 1024
    private const val JPEG_QUALITY = 88
}
