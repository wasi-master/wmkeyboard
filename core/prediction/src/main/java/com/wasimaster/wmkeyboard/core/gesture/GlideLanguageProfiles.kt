package com.wasimaster.wmkeyboard.core.gesture

/**
 * The glide decoder's weights by language (issue #966).
 *
 * [GlideBeam.Tuning]'s defaults were fitted on English swipes and ship
 * unchanged for every language, and two of them are a property of the
 * language and its word list rather than of the hand: how much a word's
 * frequency counts against the stroke's shape ([GlideBeam.Tuning.freqWeight]),
 * and how dearly a detour between two keys is charged
 * ([GlideBeam.Tuning.gapWeight]). German's useful words are rare in any list
 * (its compounds sit at a median rank of 20,714 in a 150k list, against 369
 * for the English words people swipe), so a frequency weight fitted on
 * English outvotes the shape for German. Measured on 2,588 real German
 * swipes (FUTO swipe.futo.org, Wikipedia sentences) and 10,908 more
 * (HeliBoard's gesture data, rare words), success per swipe:
 *
 *     shipped                     71.41 %   21.95 %
 *     freqWeight 0.5              73.69 %   30.46 %
 *     freqWeight 0.5, gap 2.0     74.27 %   32.51 %
 *
 * and the same profile on 2,129 English strokes costs 81.49 % -> 80.41 %.
 * No one value wins both, which is what makes it a language's setting and
 * not a new default. The profile sits on top of whatever the user has moved
 * in Advanced tuning: it touches only the weights no setting reaches,
 * so the radii and the marks stay theirs.
 *
 * The third weight is [GlideBeam.Tuning.joinCost], the compound reading
 * (issue #966 §5.2): a German list misses 36 % of the 15-letter-plus words
 * people swipe, and the decoder reads those as two or three listed words
 * in a row, each join charged this much, every part at least
 * [GlideBeam.Tuning.joinMinPart] letters, and each part joined past
 * charged [GlideBeam.Tuning.joinPartPenalty] on its own frequency. Off
 * (infinite) everywhere but German. On the same 2,588 swipes:
 *
 *     freqWeight 0.5, gap 2.0                  74.15 %
 *     + joinCost 3.0, no guard                 70.63 %
 *     + joinCost 8.0, 4 letters, penalty 0.1   74.61 %
 *
 * Start with German; the compounding languages (Finnish, Dutch, Swedish,
 * Norwegian) are the next candidates, one harness run each
 * (`native/wm-engine/examples/glide_beam_eval`, `COLS` set to the layout's
 * columns). A language with no row here decodes with the defaults, and the
 * English output is unchanged to the bit.
 */
object GlideLanguageProfiles {

    /**
     * The context model's weight on a stroke, for a language without its own:
     * fitted on 2,294 German swipes against a corpus bigram (#966), the cap where
     * 2 to 3 nats was asked for.
     */
    const val DEFAULT_LM_BLEND = 0.7
    const val DEFAULT_LM_BLEND_CAP = 3.0

    /** The weights a profile moves; everything else stays as the caller tuned it. */
    class Profile(
        val freqWeight: Double,
        val gapWeight: Float,
        val joinCost: Double,
        val joinMinPart: Int,
        val joinPartPenalty: Double,
        /** Decoder nats per nat the context model prefers a stroke's candidate over the first it can read. */
        val lmBlend: Double = DEFAULT_LM_BLEND,
        /** The most [lmBlend] moves a candidate either way. */
        val lmBlendCap: Double = DEFAULT_LM_BLEND_CAP,
    ) {
        /** [tuning] with this profile's weights on it, or [tuning] itself when it already carries them. */
        fun applyTo(tuning: GlideBeam.Tuning): GlideBeam.Tuning =
            if (tuning.freqWeight == freqWeight && tuning.gapWeight == gapWeight &&
                tuning.joinCost == joinCost && tuning.joinMinPart == joinMinPart &&
                tuning.joinPartPenalty == joinPartPenalty
            ) {
                tuning
            } else {
                tuning.copy(
                    freqWeight = freqWeight, gapWeight = gapWeight, joinCost = joinCost,
                    joinMinPart = joinMinPart, joinPartPenalty = joinPartPenalty,
                )
            }
    }

    /** The shipped weights, which every language without a row of its own decodes with. */
    val DEFAULT = Profile(
        GlideBeam.Tuning.DEFAULT.freqWeight,
        GlideBeam.Tuning.DEFAULT.gapWeight,
        GlideBeam.Tuning.DEFAULT.joinCost,
        GlideBeam.Tuning.DEFAULT.joinMinPart,
        GlideBeam.Tuning.DEFAULT.joinPartPenalty,
    )

    /**
     * German: shape over frequency, since its targets are rare in every
     * list (see the class note), and compounds read from their parts of
     * four letters or more, a common part paying for its join. Measured on
     * FUTO's German strokes only; HeliBoard's were not re-run. The context
     * blend is the one #1016 measured with unlisted candidates spelled: 76.04 %
     * to 77.40 % top-1 on the 150k list.
     */
    val GERMAN = Profile(
        freqWeight = 0.5,
        gapWeight = 2.0f,
        joinCost = 8.0,
        joinMinPart = 4,
        joinPartPenalty = 0.1,
        lmBlend = 0.5,
        lmBlendCap = 5.0,
    )

    private val BY_LANGUAGE: Map<String, Profile> = mapOf(
        "de" to GERMAN,
    )

    /**
     * The profile for [languageId], a registry id such as `de` or `de-DE`;
     * [DEFAULT] for a language with none, and for a blank or null id.
     */
    fun forLanguage(languageId: String?): Profile = BY_LANGUAGE[base(languageId)] ?: DEFAULT

    /** [tuning] as the decoder should run it for [languageId]. */
    fun tune(tuning: GlideBeam.Tuning, languageId: String?): GlideBeam.Tuning =
        forLanguage(languageId).applyTo(tuning)

    /** The language subtag of a registry id: `de-DE` and `de-Runr` are German spelled two ways. */
    private fun base(languageId: String?): String =
        languageId.orEmpty().substringBefore('-').substringBefore('_').lowercase()
}
