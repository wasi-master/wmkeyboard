package com.wasimaster.wmkeyboard.core.stickers.dynamic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import com.wasimaster.wmkeyboard.core.media.AnimatedWebpEncoder
import com.wasimaster.wmkeyboard.core.media.AnimatedWebpWriter
import com.wasimaster.wmkeyboard.core.media.GifEncoder
import com.wasimaster.wmkeyboard.core.media.MediaMime
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.exp
import kotlin.math.pow

/**
 * Turns a [DynRenderer.Prepared] into the file that is sent.
 *
 * Stickers go out on the 512 px square WhatsApp asks for. A still is a WebP
 * under 100 KB. A moving one is made as the field's [Format] says: for
 * WhatsApp an animated WebP under 500 KB, which is what lets it travel as
 * `image/webp.wasticker` and show as a sticker rather than a photo; for an
 * app that only plays GIFs, a GIF, with no size to meet. Memes with
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

    /** An animated WebP for a field with no limit of its own still has to travel. */
    const val LOOSE_BUDGET = 1024 * 1024

    /** What [sticker]'s progress counts up to. */
    const val PROGRESS_TOTAL = 1000

    private val STILL_LADDER = intArrayOf(92, 82, 70, 55, 40)

    private val STEPS = listOf(
        Step(25f, 80), Step(25f, 66), Step(20f, 60), Step(16f, 55),
        Step(12f, 50), Step(12f, 42), Step(10f, 34), Step(8f, 26),
    )
    /** What the first pass tries at once, best first: a quality, and every how many frames it takes. */
    private class Candidate(val quality: Int, val every: Int = 1)

    private val FIRST_PASS = listOf(Candidate(80), Candidate(60), Candidate(50, every = 2))

    /** Passes after the first, one encoder each, when none of the first pass's fit. */
    private const val LATER_PASSES = 2

    /** The first pass's share of the progress bar, and each later pass's share of what is left. */
    private const val FIRST_SHARE = 0.8f
    private const val LATER_SHARE = 0.7f

    /** Threads the first pass's encoders run on, one each. */
    private val encoders by lazy {
        Executors.newFixedThreadPool(FIRST_PASS.size) { task -> Thread(task, "dynsticker-encode").apply { isDaemon = true } }
    }

    /** Aim this far under the budget, since the estimate is rough. */
    private const val SAFETY = 0.85f
    private const val FRAME_EXPONENT = 0.8f
    private const val QUALITY_SLOPE = 0.02f

    /** Takes frames and returns the animated file. */
    interface FrameSink {
        fun add(frame: Bitmap, durationMs: Int): Boolean

        /** Keeps the last frame added on screen [durationMs] longer; false when this sink cannot. */
        fun hold(durationMs: Int): Boolean = false

        fun finish(): ByteArray?
    }

    /** Makes a [FrameSink] for a [size]-square animation at a WebP [quality]. */
    fun interface AnimatedEncoderFactory {
        fun create(size: Int, quality: Int): FrameSink
    }

    /**
     * The encoder: libwebp's animation encoder when its library is loaded,
     * else the whole-frame fallback. The frame rate it can afford is
     * [animatedFps].
     */
    @Volatile
    var animatedEncoder: AnimatedEncoderFactory = if (AnimatedWebpEncoder.available) {
        AnimatedEncoderFactory { size, quality -> LibwebpSink(size, quality) }
    } else {
        AnimatedEncoderFactory { size, quality -> WrappedSink(size, quality) }
    }

    /** Frames a second the current encoder is given: fewer for the whole-frame fallback. */
    @Volatile
    var animatedFps: Float = if (AnimatedWebpEncoder.available) FULL_FPS else WRAPPED_FPS

    @Volatile
    var animatedMaxFrames: Int = if (AnimatedWebpEncoder.available) MAX_FRAMES_FULL else MAX_FRAMES_WRAPPED

    /** Marks [animatedEncoder] as the libwebp one, which can take the full rate. */
    fun useFullEncoder(factory: AnimatedEncoderFactory) {
        animatedEncoder = factory
        animatedFps = FULL_FPS
        animatedMaxFrames = MAX_FRAMES_FULL
    }

    data class Output(val file: File, val mime: String, val animated: Boolean)

    /**
     * What a moving sticker is written as, which depends on the field it is
     * going to (the keyboard works that out from what the field takes).
     * A still one is a still WebP whichever this is.
     */
    enum class Format(val budget: Int) {
        /** WhatsApp's sticker type: animated WebP, under its 500 KB. */
        WEBP_STICKER(ANIMATED_BUDGET),

        /** Plain animated WebP, for a field that takes WebP but no GIF. */
        WEBP(LOOSE_BUDGET),

        /**
         * A GIF, for a field that takes one and not WhatsApp's type. Telegram
         * and Messenger take `image/webp` and show one frame of an animated
         * one; a GIF moves in both. Made straight from the frames in one pass,
         * with no size to aim for: those apps take big GIFs.
         */
        GIF(0),
    }

    /**
     * Writes [prepared] as a sticker to [target] (the extension is set
     * here): animated when it moves and [animated] allows, as [format] says,
     * else the poster frame. Null when encoding failed. [onProgress] hears
     * how far it has got out of [PROGRESS_TOTAL], only ever forwards, even
     * when a WebP is made again smaller.
     */
    fun sticker(
        prepared: DynRenderer.Prepared,
        target: File,
        animated: Boolean,
        format: Format = Format.WEBP_STICKER,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Output? {
        if (animated && prepared.animated) {
            var reported = 0
            val progress: (Float) -> Unit = { fraction ->
                val done = (fraction.coerceIn(0f, 1f) * PROGRESS_TOTAL).toInt()
                if (done > reported) {
                    reported = done
                    onProgress(done, PROGRESS_TOTAL)
                }
            }
            if (format == Format.GIF) {
                encodeGif(prepared, progress)?.let { bytes ->
                    val file = File(target.parentFile, target.nameWithoutExtension + ".gif")
                    write(file, bytes)
                    return Output(file, MediaMime.GIF, animated = true)
                }
            }
            encodeAnimated(prepared, if (format == Format.GIF) Format.WEBP.budget else format.budget, progress)?.let { bytes ->
                val file = File(target.parentFile, target.nameWithoutExtension + ".webp")
                write(file, bytes)
                return Output(file, MediaMime.WEBP, animated = true)
            }
        }
        val frame = prepared.render(prepared.posterProgress, STICKER_SIZE)
        try {
            val bytes = webp(frame, STILL_BUDGET) ?: return null
            val file = File(target.parentFile, target.nameWithoutExtension + ".webp")
            write(file, bytes)
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
                write(file, png)
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
            write(file, jpeg)
            return Output(file, MediaMime.JPEG, animated = false)
        } finally {
            frame.recycle()
        }
    }

    /**
     * Writes [bytes] to [file] by way of a temporary file, so a file found
     * under [file]'s name (the controller sends one again without remaking
     * it) is always a whole one.
     */
    private fun write(file: File, bytes: ByteArray) {
        val part = File(file.parentFile, file.name + ".part")
        part.writeBytes(bytes)
        if (!part.renameTo(file)) {
            part.delete()
            file.writeBytes(bytes)
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

    /**
     * The animated WebP, under [budget] when it can be.
     *
     * The first pass draws each frame once and hands it to several encoders
     * at once, each on a thread of its own ([FIRST_PASS]: a good quality, a
     * lower one, and a lower one at half the frame rate), so trying three
     * ways costs about the time of one. The first of them that fits is the
     * answer, which it nearly always is. Only when none does is there a
     * second pass: the smallest one's size says roughly what a lower rate
     * and quality will weigh ([estimate]), and that pass goes straight to the
     * first step expected to fit. Each pass fills its own share of the
     * progress, so the bar keeps going forwards.
     */
    private fun encodeAnimated(prepared: DynRenderer.Prepared, budget: Int, onProgress: (Float) -> Unit): ByteArray? {
        val fps = animatedFps
        val results = pass(prepared, fps, FIRST_PASS) { onProgress(it * FIRST_SHARE) }
        results.firstOrNull { it != null && it.size <= budget }?.let { return it }
        val smallest = FIRST_PASS.indices.filter { results[it] != null }.minByOrNull { results[it]!!.size } ?: return null
        var best = results[smallest]!!
        var measured = Step(fps / FIRST_PASS[smallest].every, FIRST_PASS[smallest].quality)
        val steps = STEPS.filter { it.fps <= fps }
        var start = FIRST_SHARE
        for (attempt in 0 until LATER_PASSES) {
            val frames = prepared.frameCount(measured.fps, animatedMaxFrames)
            val next = steps.firstOrNull { step ->
                step.fps <= measured.fps && step.quality <= measured.quality && step != measured &&
                    estimate(best.size, measured, frames, step, prepared.frameCount(step.fps, animatedMaxFrames)) <= budget * SAFETY
            } ?: steps.lastOrNull()?.takeIf { it != measured } ?: break
            val end = if (attempt == LATER_PASSES - 1) 1f else start + (1f - start) * LATER_SHARE
            val from = start
            val bytes = pass(prepared, next.fps, listOf(Candidate(next.quality))) { onProgress(from + (end - from) * it) }.single() ?: break
            if (bytes.size < best.size) best = bytes
            if (bytes.size <= budget) return bytes
            measured = next
            start = end
        }
        return best.takeIf { it.size <= budget * 2 }
    }

    /**
     * One drawing of [prepared]'s frames at [fps], fed to one encoder per
     * [candidates] entry, in parallel; each gets every [Candidate.every]th
     * frame, held for the frames it skips. The results in the same order,
     * null where one failed. [onProgress] goes 0 to 1 over the frames.
     */
    private fun pass(
        prepared: DynRenderer.Prepared,
        fps: Float,
        candidates: List<Candidate>,
        onProgress: (Float) -> Unit,
    ): List<ByteArray?> {
        val sinks = candidates.map { runCatching { animatedEncoder.create(STICKER_SIZE, it.quality) }.getOrNull() }
        val failed = BooleanArray(sinks.size) { sinks[it] == null }
        val total = prepared.frameCount(fps, animatedMaxFrames).coerceAtLeast(1)
        var index = 0
        // Started on the still: a chat that shows an animated sticker's
        // first frame before it plays (or instead of playing it) shows the
        // finished sticker, not the empty frame before anything pops in.
        // The loop itself is the same, only entered at another point.
        // The frame is copied out for the encoders, so the next one is drawn
        // while they work on this one; they are waited on only before the
        // copy is overwritten, a frame later.
        val staged = Bitmap.createBitmap(STICKER_SIZE, STICKER_SIZE, Bitmap.Config.ARGB_8888)
        val copy = Canvas(staged)
        val replace = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        var pending: List<Future<*>> = emptyList()
        val count = try {
            prepared.forEachFrame(STICKER_SIZE, fps, animatedMaxFrames, startAt = prepared.posterProgress) { frame, durationMs ->
                for (job in pending) job.get()
                copy.drawBitmap(frame, 0f, 0f, replace)
                val jobs = ArrayList<Future<*>>(sinks.size)
                for (i in sinks.indices) {
                    val sink = sinks[i] ?: continue
                    if (failed[i]) continue
                    if (index % candidates[i].every == 0) {
                        // Each encoder copies the frame during its call.
                        jobs += encoders.submit(Runnable { if (!sink.add(staged, durationMs)) failed[i] = true })
                    } else if (!sink.hold(durationMs)) {
                        failed[i] = true
                    }
                }
                pending = jobs
                index++
                onProgress(index / total.toFloat())
            }
        } finally {
            for (job in pending) runCatching { job.get() }
            staged.recycle()
        }
        return sinks.mapIndexed { i, sink ->
            val bytes = sink?.finish()
            bytes.takeIf { !failed[i] && count >= 2 }
        }
    }

    /** An animated GIF of [prepared] in one pass, or null when it cannot be made (no native encoder). */
    private fun encodeGif(prepared: DynRenderer.Prepared, onProgress: (Float) -> Unit): ByteArray? = runCatching {
        val out = ByteArrayOutputStream()
        val encoder = GifEncoder(out, STICKER_SIZE, STICKER_SIZE)
        val pixels = IntArray(STICKER_SIZE * STICKER_SIZE)
        val fps = minOf(animatedFps, FULL_FPS)
        val total = prepared.frameCount(fps, MAX_FRAMES_FULL).coerceAtLeast(1)
        var done = 0
        val count = prepared.forEachFrame(STICKER_SIZE, fps, MAX_FRAMES_FULL, startAt = prepared.posterProgress) { frame, durationMs ->
            frame.getPixels(pixels, 0, STICKER_SIZE, 0, 0, STICKER_SIZE, STICKER_SIZE)
            encoder.addFrame(pixels, durationMs)
            onProgress(++done / total.toFloat())
        }
        encoder.finish()
        out.toByteArray().takeIf { count >= 2 }
    }.getOrNull()

    /**
     * Roughly what a pass at [next] ([nextFrames] frames) weighs, from one at
     * [measured] that came to [size]. Fewer frames save a little less than
     * their share (each stored change is bigger), and a WebP's size falls
     * about exponentially with its quality.
     */
    private fun estimate(size: Int, measured: Step, frames: Int, next: Step, nextFrames: Int): Float {
        val frameShare = (nextFrames.toFloat() / frames.coerceAtLeast(1)).pow(FRAME_EXPONENT)
        val qualityShare = exp(QUALITY_SLOPE * (next.quality - measured.quality))
        return size * frameShare * qualityShare
    }

    /** A rate and quality an animated WebP is tried at, from the best down. */
    private data class Step(val fps: Float, val quality: Int)

    private fun encodeWebp(bitmap: Bitmap, quality: Int): ByteArray? {
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        val out = ByteArrayOutputStream()
        return if (bitmap.compress(format, quality, out)) out.toByteArray() else null
    }

    /** libwebp's animation encoder: each frame stored as what changed since the one before. */
    private class LibwebpSink(size: Int, quality: Int) : FrameSink {
        // minimizeSize tries every way of storing each frame, and key frames
        // encode each frame twice: several times slower for a few per cent,
        // and the user is waiting on the send. A lighter method than
        // libwebp's own default does the same: the first pass tries three
        // qualities at once, which covers the few per cent it costs.
        private val encoder = AnimatedWebpEncoder(
            size,
            size,
            quality = quality.toFloat(),
            minimizeSize = false,
            method = SEND_METHOD,
            keyFrames = false,
        )
        private var timestamp = 0
        private var frames = 0

        override fun add(frame: Bitmap, durationMs: Int): Boolean {
            if (!encoder.addFrame(frame, timestamp)) return false
            timestamp += durationMs
            frames++
            return true
        }

        // A frame lasts until the next one's timestamp, so holding is moving the clock on.
        override fun hold(durationMs: Int): Boolean {
            if (frames == 0) return false
            timestamp += durationMs
            return true
        }

        override fun finish(): ByteArray? = encoder.use { if (frames >= 2) it.finish(timestamp) else null }
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

    /** libwebp's method for a sent sticker: 2 of 0 (fast) to 6 (small); its own default is 4. */
    private const val SEND_METHOD = 2

    private const val WRAPPED_FPS = 12f
    private const val FULL_FPS = 25f
    private const val MEME_SIZE = 1024
    private const val PNG_LIMIT = 900 * 1024
    private const val JPEG_QUALITY = 88
}
