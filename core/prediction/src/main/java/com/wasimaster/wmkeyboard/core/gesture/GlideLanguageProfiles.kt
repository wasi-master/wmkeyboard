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
 * German, Finnish, Estonian and Hungarian have measured rows; Dutch, Swedish
 * and Norwegian are the next candidates, one harness run each
 * (`native/wm-engine/examples/glide_beam_eval`, `COLS` set to the layout's
 * columns). A language with no row here decodes with the defaults, and the
 * English output is unchanged to the bit.
 *
 * The glide context blend is opt-in as well
 * ([Profile.glideLmBlend]): the default is zero, German keeps its measured
 * 0.5/5.0 with pieces on both list sizes, and English uses 0.7/3.0 with
 * pieces on the small bundled list and 0.5/5.0 on listed candidates only
 * above [GLIDE_LM_BLEND_LIST_LIMIT]. A language without a measured row keeps
 * the decoder order.
 */
object GlideLanguageProfiles {

    /**
     * The context model's weight on a stroke, for a language without its own:
     * fitted on 2,294 German swipes against a corpus bigram (#966), the cap where
     * 2 to 3 nats was asked for.
     */
    const val DEFAULT_LM_BLEND = 0.7
    const val DEFAULT_LM_BLEND_CAP = 3.0

    /**
     * A word list longer than this counts as large for the glide's context
     * blend. The English model reads positive on the 17k bundled list with
     * pieces, and positive on the 160k AOSP list **only** when it scores
     * listed candidates alone; with pieces on the large list it reads
     * negative. The two list sizes are not interchangeable.
     */
    const val GLIDE_LM_BLEND_LIST_LIMIT = 50_000

    /** The weights a profile moves; everything else stays as the caller tuned it. */
    class Profile(
        val freqWeight: Double,
        /**
         * [freqWeight] for a stroke the context model can read alongside it.
         * A model-aware decode has a second signal for the words frequency was
         * outvoting, so German can lean a little further on shape. Inherits
         * [freqWeight] for a language with no measured difference.
         */
        val freqWeightWithContext: Double = freqWeight,
        val gapWeight: Float,
        val joinCost: Double,
        val joinMinPart: Int,
        val joinPartPenalty: Double,
        /** Decoder nats per nat the context model prefers a stroke's candidate over the first it can read. */
        val lmBlend: Double = DEFAULT_LM_BLEND,
        /** The most [lmBlend] moves a candidate either way. */
        val lmBlendCap: Double = DEFAULT_LM_BLEND_CAP,
        /**
         * [lmBlend] for the glide path only: typing's strip is a different
         * evidence problem. Zero keeps the glide blend off, which is the safe
         * default until a language and a list size have been measured.
         */
        val glideLmBlend: Double = 0.0,
        val glideLmBlendCap: Double = lmBlendCap,
        /**
         * The same two for a list above [GLIDE_LM_BLEND_LIST_LIMIT]. English
         * reads positive on the small bundled list and negative on the large
         * AOSP and counted lists, so its large value is zero.
         */
        val glideLmBlendLargeList: Double = 0.0,
        val glideLmBlendCapLargeList: Double = glideLmBlendCap,
        /**
         * Whether the glide blend spells an unlisted candidate through its
         * pieces. German's compounds need it; an English large list gains
         * more from scoring its listed candidates alone (0.8074 against
         * 0.8055 top-1, with no piece pass at all).
         */
        val glideLmSpellOov: Boolean = true,
        val glideLmSpellOovLargeList: Boolean = false,
    ) {
        /** [tuning] with this profile's weights on it, or [tuning] itself when it already carries them. */
        fun applyTo(tuning: GlideBeam.Tuning, hasContext: Boolean = false): GlideBeam.Tuning {
            val weight = if (hasContext) freqWeightWithContext else freqWeight
            return if (tuning.freqWeight == weight && tuning.gapWeight == gapWeight &&
                tuning.joinCost == joinCost && tuning.joinMinPart == joinMinPart &&
                tuning.joinPartPenalty == joinPartPenalty
            ) {
                tuning
            } else {
                tuning.copy(
                    freqWeight = weight, gapWeight = gapWeight, joinCost = joinCost,
                    joinMinPart = joinMinPart, joinPartPenalty = joinPartPenalty,
                )
            }
        }
    }

    /** The shipped weights, which every language without a row of its own decodes with. */
    val DEFAULT = Profile(
        freqWeight = GlideBeam.Tuning.DEFAULT.freqWeight,
        gapWeight = GlideBeam.Tuning.DEFAULT.gapWeight,
        joinCost = GlideBeam.Tuning.DEFAULT.joinCost,
        joinMinPart = GlideBeam.Tuning.DEFAULT.joinMinPart,
        joinPartPenalty = GlideBeam.Tuning.DEFAULT.joinPartPenalty,
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
        // 0.5 above was fitted on a counted list, and German downloads AOSP's,
        // whose top words sit further apart (#1051). On that list, with the
        // shipped join guard and the published G6P 90k, the same 2,588 swipes:
        // without a context model 0.75 reads 81.34 % top-1 and 0.5 gives
        // 80.76 %; the 0.5/5.0 glide blend takes 0.5 to 82.88 % against
        // 81.68 % at 0.75, so with a model the stroke reads 0.5 while
        // no-model users keep 0.75. `das` reads `das` 32 times of 33 rather
        // than 30.
        freqWeight = 0.75,
        freqWeightWithContext = 0.5,
        gapWeight = 2.0f,
        joinCost = 8.0,
        joinMinPart = 4,
        joinPartPenalty = 0.1,
        lmBlend = 0.5,
        lmBlendCap = 5.0,
        glideLmBlend = 0.5,
        glideLmBlendLargeList = 0.5,
        glideLmSpellOovLargeList = true,
    )

    /**
     * Finnish: the first compounding row measured on real HeliBoard strokes
     * rather than copied from German. On 1,000 single-finger a-z swipes drawn
     * from HeliBoard's Finnish gesture data (seed 20261109, 12 canvases) over
     * the app-preferred `fi_aosp` list, the German-style 0.75/2.0 pair lifts
     * top-1 from 72.80 % to 76.90 % and top-4 from 91.30 % to 95.10 % (paired
     * +4.10 / +3.80 pp, CI [+2.70, +5.60] / [+2.70, +5.00]). The join guard
     * moved 0 of 1,000 outcomes, and the app resolves joins through a separate
     * downloaded compound lexicon this slice did not exercise, so the shipped
     * row leaves joins off. No Finnish context model ships either, so
     * [freqWeightWithContext] inherits 0.75.
     */
    val FINNISH = Profile(
        freqWeight = 0.75,
        gapWeight = 2.0f,
        joinCost = GlideBeam.Tuning.DEFAULT.joinCost,
        joinMinPart = GlideBeam.Tuning.DEFAULT.joinMinPart,
        joinPartPenalty = GlideBeam.Tuning.DEFAULT.joinPartPenalty,
    )

    /**
     * Estonian: measured on HeliBoard's Estonian strokes, full alphabet rather
     * than the a-z-only Finnish slice. The eligible set is small: 281
     * single-finger strokes, seed 20261109, two geometries, over `et_full`
     * capped to its 300,000 most frequent words. The 0.75/2.0 pair reads
     * 66.55 / 81.14 against 57.30 / 72.95 at the default (+9.25 / +8.19 pp;
     * paired 27/1, p = 2.16e-07, cluster CI [+2.00, +10.82] /
     * [+8.00, +8.23]). Joins stay off and no Estonian model ships.
     */
    val ESTONIAN = Profile(
        freqWeight = 0.75,
        gapWeight = 2.0f,
        joinCost = GlideBeam.Tuning.DEFAULT.joinCost,
        joinMinPart = GlideBeam.Tuning.DEFAULT.joinMinPart,
        joinPartPenalty = GlideBeam.Tuning.DEFAULT.joinPartPenalty,
    )

    /**
     * Hungarian: measured on HeliBoard's Hungarian strokes, full alphabet.
     * 436 single-finger strokes, seed 20261109, seven geometries, over
     * `hu_full` capped to its 300,000 most frequent words. The 0.75/2.0 pair
     * reads 66.97 / 85.09 against 62.39 / 81.42 at the default (+4.59 /
     * +3.67 pp; paired 26/6, p = 5.35e-4, cluster CI [+3.19, +8.11] /
     * [+1.37, +8.86]). Joins stay off and no Hungarian model ships.
     */
    val HUNGARIAN = Profile(
        freqWeight = 0.75,
        gapWeight = 2.0f,
        joinCost = GlideBeam.Tuning.DEFAULT.joinCost,
        joinMinPart = GlideBeam.Tuning.DEFAULT.joinMinPart,
        joinPartPenalty = GlideBeam.Tuning.DEFAULT.joinPartPenalty,
    )

    /**
     * English: the decoder defaults are its fit. The small bundled list uses
     * 0.7/3.0 with pieces: +4.9 top-1 on the original 2,129/1,824 hard slice,
     * +3.25 on the full 26,523 public set. The large list uses 0.5/5.0 on
     * listed candidates only: 80.74 / 88.50 against 80.55 / 88.50 without it
     * (paired +0.20 pp, CI [-0.05, +0.44], p = 0.121), which is non-negative
     * but not a proven gain, at no piece pass. Spelling unlisted candidates
     * on the large list reads 79.57 / 87.83.
     */
    val ENGLISH = Profile(
        freqWeight = GlideBeam.Tuning.DEFAULT.freqWeight,
        gapWeight = GlideBeam.Tuning.DEFAULT.gapWeight,
        joinCost = GlideBeam.Tuning.DEFAULT.joinCost,
        joinMinPart = GlideBeam.Tuning.DEFAULT.joinMinPart,
        joinPartPenalty = GlideBeam.Tuning.DEFAULT.joinPartPenalty,
        lmBlend = DEFAULT_LM_BLEND,
        lmBlendCap = DEFAULT_LM_BLEND_CAP,
        glideLmBlend = 0.7,
        glideLmBlendCap = 3.0,
        glideLmBlendLargeList = 0.5,
        glideLmBlendCapLargeList = 5.0,
        glideLmSpellOovLargeList = false,
    )

    private val BY_LANGUAGE: Map<String, Profile> = mapOf(
        "de" to GERMAN,
        "en" to ENGLISH,
        "et" to ESTONIAN,
        "fi" to FINNISH,
        "hu" to HUNGARIAN,
    )

    /**
     * The profile for [languageId], a registry id such as `de` or `de-DE`;
     * [DEFAULT] for a language with none, and for a blank or null id.
     */
    fun forLanguage(languageId: String?): Profile = BY_LANGUAGE[base(languageId)] ?: DEFAULT

    /**
     * [tuning] as the decoder should run it for [languageId]. With other
     * languages' lists [mixed] in, the shipped weights: the profiles were
     * measured on one list alone, and halving frequency lets rare English
     * words take German strokes (#1051). [hasContext] picks the profile's
     * [Profile.freqWeightWithContext] where a context model reads the stroke.
     */
    fun tune(
        tuning: GlideBeam.Tuning,
        languageId: String?,
        mixed: Boolean = false,
        hasContext: Boolean = false,
    ): GlideBeam.Tuning =
        (if (mixed) DEFAULT else forLanguage(languageId)).applyTo(tuning, hasContext)

    /** The language subtag of a registry id: `de-DE` and `de-Runr` are German spelled two ways. */
    private fun base(languageId: String?): String =
        languageId.orEmpty().substringBefore('-').substringBefore('_').lowercase()
}
