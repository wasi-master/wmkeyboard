package com.wasimaster.wmkeyboard.core.voice.parakeet

import com.wasimaster.wmkeyboard.core.voice.VoiceStream

/**
 * Parakeet heard as the user speaks (#1168). Parakeet is not a streaming
 * model: its encoder reads a whole clip. So the words so far come from
 * transcribing the clip as it stands, again and again while it grows, and the
 * finished phrase from one last pass over all of it, which is exactly the
 * transcript the engine gave when it only ran at the end.
 *
 * The passes pace themselves. One runs once [minStepSamples] of new audio have
 * come in, and never sooner than [paceFactor] times as long as the last pass
 * took, so a slow phone spends at most about a third of its time on the
 * words so far, and the clip being recorded never waits for them. A pass
 * covers the whole clip, so it costs more as the phrase gets longer, and the
 * pace slows with it.
 *
 * Not thread-safe: one thread (the ExecuTorch one Parakeet runs on) feeds,
 * reads and finishes.
 *
 * A clip that reaches [maxSamples] is cut at a pause when [cutAt] can find
 * one (#1173, Parakeet Ultra's own voice-activity head): the part before the
 * cut is transcribed once and kept, and the audio after it starts the next
 * part, so dictation goes on past the model's 50 seconds. Without [cutAt],
 * audio past [maxSamples] is dropped, as it always was.
 *
 * @param maxSamples the longest clip [transcribe] takes.
 * @param transcribe the clip's first n samples as text: [ParakeetEngine]'s
 *   decoding, or a fake in a test.
 * @param now a millisecond clock, for the pacing.
 * @param cutAt the sample to cut a full clip's first n samples at, or null
 *   when there is no good place.
 */
class ParakeetStream internal constructor(
    private val maxSamples: Int,
    private val transcribe: (pcm: FloatArray, count: Int) -> String,
    private val now: () -> Long = System::currentTimeMillis,
    private val minStepSamples: Int = MIN_STEP_SAMPLES,
    private val paceFactor: Int = PACE_FACTOR,
    private val cutAt: ((pcm: FloatArray, count: Int) -> Int?)? = null,
) : VoiceStream {

    private var buffer = FloatArray(INITIAL_SAMPLES)
    private var size = 0

    /** Samples the last pass covered. */
    private var decodedSize = 0

    /** How long the last pass took, in milliseconds. */
    private var lastPassMs = 0L

    private var heard = ""

    /** What the parts already cut off came to, joined. */
    private var settled = ""

    override fun feed(chunk: FloatArray, count: Int) {
        var from = 0
        var left = minOf(count, chunk.size)
        while (left > 0) {
            if (size >= maxSamples && !cut()) return
            val n = minOf(left, maxSamples - size)
            ensure(size + n)
            System.arraycopy(chunk, from, buffer, size, n)
            size += n
            from += n
            left -= n
            if (size - decodedSize >= stepSamples()) pass()
        }
    }

    override fun text(): String = join(settled, heard)

    override fun finish(): String {
        if (size > decodedSize || (size > 0 && heard.isEmpty())) pass()
        return join(settled, heard)
    }

    /**
     * Cuts the full clip at [cutAt]'s pause: the part before it transcribed
     * and kept, the rest moved to the front. False when there is no cutter,
     * or it found nowhere to cut.
     */
    private fun cut(): Boolean {
        val at = cutAt?.invoke(buffer, size)?.takeIf { it in 1 until size } ?: return false
        settled = join(settled, transcribe(buffer, at))
        System.arraycopy(buffer, at, buffer, 0, size - at)
        size -= at
        decodedSize = 0
        heard = ""
        return true
    }

    private fun join(before: String, after: String): String = when {
        before.isEmpty() -> after
        after.isEmpty() -> before
        else -> "$before $after"
    }

    /**
     * New audio needed before the next pass: at least [minStepSamples], and as
     * much as plays in [paceFactor] times the last pass, so passes take up at
     * most about 1 / [paceFactor] of the time.
     */
    internal fun stepSamples(): Int {
        val paced = lastPassMs * paceFactor * SAMPLES_PER_MS
        return maxOf(minStepSamples.toLong(), paced).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun pass() {
        val started = now()
        heard = transcribe(buffer, size)
        lastPassMs = (now() - started).coerceAtLeast(0)
        decodedSize = size
    }

    private fun ensure(capacity: Int) {
        if (capacity <= buffer.size) return
        var grown = buffer.size
        while (grown < capacity) grown *= 2
        buffer = buffer.copyOf(minOf(grown, maxSamples))
    }

    companion object {
        private const val SAMPLES_PER_MS = 16L

        /** 0.6 s: a word or two, so the line under the microphone moves as words arrive. */
        const val MIN_STEP_SAMPLES = 9_600

        /** Passes take at most about a third of the time. */
        const val PACE_FACTOR = 3

        /** 4 s, doubled as the phrase grows. */
        private const val INITIAL_SAMPLES = 64_000
    }
}
