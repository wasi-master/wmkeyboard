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
 * @param maxSamples the longest clip [transcribe] takes; audio past it is dropped.
 * @param transcribe the clip's first n samples as text: [ParakeetEngine]'s
 *   decoding, or a fake in a test.
 * @param now a millisecond clock, for the pacing.
 */
class ParakeetStream internal constructor(
    private val maxSamples: Int,
    private val transcribe: (pcm: FloatArray, count: Int) -> String,
    private val now: () -> Long = System::currentTimeMillis,
    private val minStepSamples: Int = MIN_STEP_SAMPLES,
    private val paceFactor: Int = PACE_FACTOR,
) : VoiceStream {

    private var buffer = FloatArray(INITIAL_SAMPLES)
    private var size = 0

    /** Samples the last pass covered. */
    private var decodedSize = 0

    /** How long the last pass took, in milliseconds. */
    private var lastPassMs = 0L

    private var heard = ""

    override fun feed(chunk: FloatArray, count: Int) {
        val n = minOf(count, chunk.size, maxSamples - size)
        if (n <= 0) return
        ensure(size + n)
        System.arraycopy(chunk, 0, buffer, size, n)
        size += n
        if (size - decodedSize >= stepSamples()) pass()
    }

    override fun text(): String = heard

    override fun finish(): String {
        if (size > decodedSize || (size > 0 && heard.isEmpty())) pass()
        return heard
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
