package com.wasimaster.wmkeyboard.core.gesture

/**
 * [GlideBeam]'s sizes: how finely a stroke is resampled, how large a learned
 * shape is, how many states a decode may hold.
 *
 * The pooled state store itself, the alignment columns and the heap, moved
 * to Rust with the decoder (`wm-engine`'s `glide::workspace`, one per decoding
 * thread), and the Kotlin decoder that used this class kept it in the test
 * sources as the reference. An instance carries nothing. Callers that pool
 * one per thread can keep passing it, and [GlideBeam] ignores it.
 */
class GlideWorkspace {

    companion object {
        /**
         * Path samples the drawn stroke is resampled to. The alignment is
         * O(SAMPLE_POINTS) per trie edge and a column is 4 bytes a sample, so
         * this is the decoder's one real size dial. This is the most a
         * stroke gets; most get [SHORT_SAMPLES].
         *
         * 48 for everything left a long word two and a half samples a key:
         * with a finger's rounded corners, "internationalization" decoded
         * from 5 of 30 noisy strokes, the walk spending its budget on short
         * readings before a twenty-letter one fit. At 64 it decodes from 26.
         * 64 for everything also cost about 40% more CPU on every stroke,
         * so only a stroke of [LONG_STROKE] key widths or more gets it.
         *
         * 64 was also the most any stroke got, and a 26-letter compound
         * crosses the board twice as far as a 13-letter one (issue #966):
         * past [LONG_STROKE] a stroke now earns [SAMPLES_PER_LETTER] more
         * samples for every [LETTER_ARC] it runs on, up to this. See
         * [samplesFor].
         */
        const val SAMPLE_POINTS = 96

        /** Samples a stroke shorter than [LONG_STROKE] is resampled to. */
        const val SHORT_SAMPLES = 48

        /** Stroke length, in key widths, from which a stroke gets [LONG_SAMPLES] samples. */
        const val LONG_STROKE = 40f

        /** Samples a stroke of exactly [LONG_STROKE] gets. */
        const val LONG_SAMPLES = 64

        /**
         * A letter's worth of stroke, in key widths: the mean key-to-key
         * step over the English list, drawn centre to centre, is 2.95.
         */
        const val LETTER_ARC = 3f

        /** Samples each [LETTER_ARC] past [LONG_STROKE] adds. */
        const val SAMPLES_PER_LETTER = 2

        /**
         * Letters a glide spells at most: `wm-engine`'s `MAX_WORD_LENGTH`.
         * 24 until issue #966, which left a 26-letter compound unreadable.
         */
        const val MAX_WORD_LENGTH = 32

        /**
         * How many samples a stroke of [arc] key widths is resampled to:
         * [SHORT_SAMPLES] under [LONG_STROKE], [LONG_SAMPLES] at it, and
         * [SAMPLES_PER_LETTER] more for every [LETTER_ARC] beyond, up to
         * [SAMPLE_POINTS] (issue #966 §5.3). The letter count is read off
         * the arc because the stroke is resampled before any word is known.
         * Every stroke under [LONG_STROKE] is sampled exactly as before.
         * `wm-engine`'s `glide::workspace::samples_for`, to the bit; the
         * decoder runs there, and this is what [GlideBeam.Alignment.sampleCount]
         * comes back as.
         */
        fun samplesFor(arc: Float): Int =
            if (arc < LONG_STROKE) {
                SHORT_SAMPLES
            } else {
                val letters = ((arc - LONG_STROKE) / LETTER_ARC).toInt()
                minOf(SAMPLE_POINTS, LONG_SAMPLES + SAMPLES_PER_LETTER * letters)
            }

        /**
         * Samples a *shape* has, apart from the alignment's: the learned
         * shapes are saved at this size, and a stored shape of another size
         * is skipped, so changing it would forget every shape a user has
         * taught. The shape channel resamples the stroke down to it.
         */
        const val SHAPE_POINTS = 48

        /**
         * Frontier cap, worth up to ~4.5 MB of columns once the pool has
         * grown into it on the longest stroke (MAX_STATES × 96 samples ×
         * 4 bytes); the vectors grow with the pool, so only a stroke that
         * needs them pays. Best-first order means the states that matter are
         * expanded first, so hitting this is a backstop rather than a bound
         * anyone should reach.
         *
         * It was 3072, and on a long word it was reached every time: a German
         * stroke of fifteen letters or more saturated the pool on every one of
         * 300 synthetic strokes, and the word drawn was never pushed (issue
         * #714). At 8192 none of them do. At the later 4,000-pop budget the
         * real German swipes reached 8192 again: 12,288 lifted top-1 from
         * 75.81 to 76.31 and top-64 coverage from 87.13 % to 87.75 %; 16,384
         * gained nothing more on the same set.
         *
         * This is the final lift's frontier; a mid-swipe preview frame runs
         * with 8,192 states (see `SuggestionEngine.GLIDE_PREVIEW_STATES`).
         */
        const val MAX_STATES = 12288

        /**
         * Longest key sequence a candidate's ideal path can have: a word of
         * [MAX_WORD_LENGTH] letters visits at most that many keys, plus the
         * two anchor leads the shape channel adds. [NativeGlide.align]'s
         * out buffer is sized by it, so it matches `wm-engine`'s.
         */
        const val MAX_IDEAL_POINTS = 34

        /** Stands in for "unreachable" in a column without overflowing on add. */
        const val UNREACHABLE = Float.MAX_VALUE / 4f

        /** Sentinel for the root, which consumed no trie edge. */
        val NO_LABEL: Char = 0.toChar()
    }
}
