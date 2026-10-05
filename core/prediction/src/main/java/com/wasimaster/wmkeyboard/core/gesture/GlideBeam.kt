package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.TrieWalker
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Glide decoder: a best-first lattice over the dictionary trie itself.
 *
 * The decoder it replaces scored a flat word list — build each candidate's ideal
 * polyline, resample it, compare. That is linear in the dictionary on every
 * preview event, which caps how large a word list can ship and forces the whole
 * lexicon to be materialized as strings in the IME process. Walking the trie
 * instead shares work across every word with a common prefix, never materializes
 * a word that is not emitted, and takes its candidates from whatever tries the
 * caller hands over — bundled, imported, personal, any script.
 *
 * **The alignment.** The drawn stroke is resampled to
 * [GlideWorkspace.SAMPLE_POINTS] samples spaced equally *by arc length*, which
 * is what makes the whole model work: the drawn distance between sample `i` and
 * sample `j` is exactly `(j - i)` steps, no measuring required. A word explains
 * the stroke by placing each of its letters on a sample, in increasing order,
 * with the first letter on the first sample and the last on the last. Placing
 * letter `m` on sample `j` costs two things:
 *
 *  - **where** — `d²/2σ²` for the distance from sample `j` to letter `m`'s key;
 *  - **how far** — how badly the stroke's own travel since the previous letter,
 *    `(j - i)` steps, disagrees with the distance between the two keys.
 *
 * The second term is what stops a decoder from reading `ho` off a stroke drawn
 * for `hello`: both letters sit exactly where they should, but the finger
 * travelled three times as far as `h`-to-`o` asks for. It is also what makes a
 * detour *between* keys expensive without any notion of curvature: arc length
 * that the key distance cannot account for. A loop drawn *on* a key is the one
 * detour read the other way, as the mark for a doubled letter — see
 * [Tuning.loopExtent].
 *
 * Because a prefix's column depends only on the letters spelled so far, every
 * word sharing that prefix shares the work. A beam state is `(trie node,
 * column)` and advancing one letter is one bounded pass over the column.
 *
 * **Why the bound is admissible.** Both terms are non-negative and the DP takes
 * a minimum over predecessors, so no extension of a prefix can finish below that
 * prefix column's lowest value. Expanding in order of
 * `logWeight + ln(1 + maxSubtree(node)) - shapeWeight * minCol` therefore never
 * discards a word that would have finished in the top K — the same argument
 * `FuzzyBeamSearch` makes for typing, with the alignment floor standing in for
 * the edit cost.
 *
 * **One thing that was tried and did not work.** Weighting each sample by how
 * deliberate the finger looked there — letters sit at velocity minima and
 * curvature maxima, so those samples ought to be better evidence — was expected
 * to be the largest single gain available and measured as a loss at every gain
 * in both directions (top1 .9050 at zero, .8958 at +0.8, .8967 at -1.0). The
 * likely reason is worth knowing before anyone tries it again: corner cutting is
 * the dominant error in a real stroke, so the sample at a pivot is exactly the
 * one systematically displaced *inside* the corner and away from the key it
 * belongs to. Leaning on those samples leans on the error. The arc-length term
 * already carries what the timing would have said about where letters fall.
 *
 * **A second thing that was tried and did not work.** Edge keys were given
 * extra anchor tolerance *inward*, on the reasoning that a finger aiming at `q`
 * cannot land above or left of the keyboard, so the touches an outer key
 * collects are the inward half of an interior key's spread with their centre of
 * mass pushed inward too. The reasoning is sound and the effect is not there:
 * swept from 0 to 0.8 key widths it moved no metric by a single case, and it
 * still moved none against a corpus modified to clamp every sample to the board
 * the way a digitiser does. The reason is the magnitudes. Endpoint slop is
 * σ ≈ 0.3 key widths at the sloppiest graded level and the outermost key
 * centres sit half a key inside the board's edge, so a touch that would have
 * landed off the board is already rare, and one far enough off to change which
 * keys an anchor admits is rarer still. The truncation is real and it is too
 * small to correct for.
 *
 * Scores are in the same log space as the typing beam
 * (`logWeight + ln(1 + frequency) - cost`), so `FuzzyBeamSearch.WalkSource`
 * weights carry over unchanged and a caller can build one source list for both.
 */
class GlideBeam(private val tuning: Tuning = Tuning()) {

    /**
     * The decoder's weights, injectable so the harness can sweep them rather
     * than the author guessing. The defaults are what the sweep settled on; see
     * `GlideTuningSweepTest` for the surface they were picked off.
     */
    data class Tuning(
        /**
         * Sample scatter around the key the finger meant, in key widths. Wider
         * than the tap model's 0.5: a stroke passes *through* a key rather than
         * stopping on it.
         */
        val sigma: Float = 0.42f,
        /**
         * Ceiling on one sample's location cost. Without it a single sample
         * flung off by a dropped touch report outweighs the whole alignment.
         */
        val maxPointCost: Float = 12.0f,
        /**
         * Nats per key width of disagreement between how far the finger
         * travelled between two letters and how far apart their keys are. This
         * is the term that reads `hello` off a `hello` stroke rather than `ho`.
         */
        val gapWeight: Float = 2.0f,
        /** How far, in samples, a placement may sit from where the arc length
         * says it should before the search stops considering it. */
        val gapWindow: Float = 14f,
        /** Nats per unit of alignment cost — the dial that trades shape against
         * the language model. */
        val shapeWeight: Double = 2.5,
        /** Flat charge for a doubled letter, which the stroke's shape cannot show. */
        val repeatCost: Float = 0.1f,
        /**
         * Extra charge for a doubled letter the finger did *not* loop on.
         *
         * A stroke draws `good` and `god` identically — the o is one key, visited
         * once — so shape has nothing to say and frequency decides. The loop is
         * the one mark that says a letter is written twice (see [loopExtent]),
         * and this charges the doubling when that mark is absent, so an
         * unmarked stroke leans to the single spelling.
         *
         * A pause used to waive it too, and that gave a pause two meanings at
         * once: "this letter is in the word" and "this letter is in it twice".
         * A hold on the i of `write` then read as much as a vote for a doubled
         * i as for the i, and a hold on the f of `of` had to be long enough to
         * double it or it did nothing (issue #337). A pause now says only the
         * first thing — see [unclaimedDwell] — and a doubling asks for a loop.
         */
        val unloopedRepeat: Float = 0.4f,
        /**
         * How many times the stroke's own even pace a step has to take for the
         * finger to read as having stopped dead there.
         *
         * The pause reading is relative rather than a count of milliseconds,
         * and has to be: the same hesitation that is a deliberate mark in a
         * quick stroke is how a slow, careful one moves throughout. A step
         * taking `1 + this` times the stroke's mean step scores a full 1, and
         * everything between the mean and that scores in proportion, so
         * lowering this makes a shorter hesitation count for a whole pause and
         * raising it asks for a longer one.
         *
         * Read by [unclaimedDwell], which charges the words that ignore the
         * pause, and by nothing else: a pause no longer bears on doubled
         * letters (see [unloopedRepeat]). Floored where it is used (see
         * [dwellScale]): at zero every step of an evenly drawn stroke is 0/0.
         */
        val dwellFull: Float = 3.0f,
        /**
         * Charge per unit of pause a finished word leaves unexplained.
         *
         * A pause is the mark for "this letter is in the word", and this is
         * the whole of how it is read: as evidence against every word that has
         * no letter there. A finger that stopped on a key was, far more often
         * than not, writing that key, and a candidate whose letters never go
         * near it is explaining the stroke's shape while ignoring its clock
         * (issue #52). It is what separates the words a QWERTY stroke draws
         * along one row — `write`, `wire`, `wrote` — which shape cannot: a
         * hold on the i costs `wrote` and nothing else (issue #337).
         *
         * A pause is claimed by the key it happened on — the nearest one, or
         * one within `DWELL_TIE` of as near, for a stop on the line between
         * two — and never by the key next door. A claim as wide as the pause's
         * reach let the o of `wrote` answer for a hold drawn a little right of
         * the i, which is exactly the hold the charge exists to read. A wiggle,
         * when wiggles are read, files the same kind of event as a full pause
         * weighted by [wiggleWeight]: the moving way to say the same thing.
         * Charged as one event per mark, on the whole word, so it is not a tax
         * on length the way a per-letter "did you slow down here" charge would
         * be — a long word and a short one pay the same for the same ignored
         * mark. Zero switches it off.
         *
         * Swept on the graded corpus, whose only pauses are the doubled-letter
         * hesitations and the slowing into every pivot:
         *
         *     0     .9467 (sloppy .890)      2.0   .9558 (sloppy .907)
         *     1.0   .9508                    4.0   .9550 (sloppy .893)
         *
         * and on the same strokes with the finger resting on a third of the
         * single letters, .9333 at zero to .9433 at 2.0 — still climbing at
         * 4.0, but that is where the plain corpus starts paying it back.
         */
        val unclaimedDwell: Float = 2.0f,
        /**
         * Widest a loop may be, in key widths, for the decoder to read it as a
         * mark on a key; 0 switches loop reading off altogether.
         *
         * Swype's rule: a small circle drawn on a key means the letter twice.
         * Before this the stroke's own geometry made the opposite true. A loop
         * on the `o` of `good` is arc that no key distance explains, so the
         * gap term charged `good` and `god` alike for it, and the doubling
         * still paid [repeatCost] on top: a penalty on every word, evidence of
         * nothing (issue #52).
         *
         * A loop is a stretch of the stroke whose path is at least
         * `LOOP_RATIO` times its extent — a straight pass is 1, any single
         * corner at most 2, a circle π — holding at least [loopMinArc] of
         * path, no wider than this, and enclosing area the way a circle does
         * (`LOOP_ROUNDNESS`), which a zigzag, whose signed area cancels, never
         * does. The width matters: three keys that neighbour one another are
         * a key pitch apart, so a word that visits them in a ring draws a real
         * loop of extent near one — `murderer` goes r-d-e-r — and this has to
         * sit under it. Three things happen to a loop, and each leaves the
         * search bound admissible:
         *
         *  - its arc is collapsed to its chord in [GlideWorkspace.arcAt], so
         *    the travel between the letters on either side reads as it would
         *    without the loop — for `god` and `good` alike;
         *  - a doubled letter on that key has its [repeatCost] and
         *    [unloopedRepeat] waived, a charge dropped rather than a credit
         *    given, so a state's `extra` never falls below its parent's;
         *  - every finished word that does *not* double a letter there pays
         *    [unclaimedLoop] at emit, the way a word that ignores a pause pays
         *    [unclaimedDwell];
         *  - the samples inside it belong to that key: every other key pays
         *    [loopExclusion] more there, so the circle's edge, which passes
         *    over the keys around the one it is drawn on, does not pull their
         *    letters into the word (issue #337). A word that meant one of
         *    them places it on the way in or out, where the stroke really
         *    went.
         *
         * "That key" is one key, the nearest to the loop's centre within
         * [loopRadius]. The loop is the only doubling mark: a pause and a
         * wiggle say a letter is in the word, never that it is in it twice.
         *
         * A credit to the repeat itself is the natural reading and was
         * rejected on the bound: a state popped before its descendant collects
         * the credit carries a bound too low by that much, and the walk stops
         * at the first bound under the floor. Charging the words that ignore
         * the loop orders the candidates identically — on any one stroke the
         * two differ by a constant across every word.
         *
         * Measured on 300 strokes a level, plain corpus against the same
         * strokes with every doubled letter drawn as a circle on its key,
         * top-1 overall and on the words that have a doubled letter:
         *
         *     off        plain .9558 (sloppy .907)   looped .9217 (doubled .750)
         *     0.8        plain .9550 (sloppy .903)   looped .9408 (doubled .852)
         *     1.0        plain .9533 (sloppy .900)   looped .9392 (doubled .860)
         *
         * The plain corpus's sloppy strokes pay the difference: their tremor
         * curls tightly enough to read as a loop one stroke in nine, and no
         * measure of shape tells that curl from a loop the corpus draws with
         * the same tremor on it. Timing does not either, since a loop is drawn
         * at pivot speed. That is the price of reading loops at all, and the
         * ten points on doubled words buy it.
         */
        val loopExtent: Float = 0.8f,
        /** Least arc a window must hold to be a loop rather than tremor, in key widths. */
        val loopMinArc: Float = 1.0f,
        /**
         * How near a key a loop's centre has to sit, in key widths, for the
         * loop to be a mark on that key.
         *
         * Tighter than the pause's own reach (`DWELL_RADIUS`), and for a
         * reason the two readings do not share: a circle drawn through a key
         * centres about a third of a key off it, so the reach has to cover
         * that. Only the nearest key inside it is credited, so a loop is about
         * one key however wide this is set. Every part of the loop model reads
         * the same key — the waiver a doubled letter gets, the [unclaimedLoop]
         * every word that ignores the loop pays, and the samples no other key
         * may be placed on — so one number decides which key a loop is about.
         */
        val loopRadius: Float = 0.6f,
        /**
         * Widest a *wiggle* may be — a window with a loop's arc for its extent
         * but no net turning, the back-and-forth some people rub over a key
         * they want counted — in key widths. Must stay under one key pitch, or
         * a word that goes x-y-x-y across two adjacent keys reads as one. 0
         * switches wiggles off, which is the default: at the sloppy end of
         * the corpus a slow pivot with tremor on it looks the same.
         *
         * A wiggle is a pause drawn in motion, for a finger that would rather
         * not stop, and is read as one: evidence the key is in the word,
         * charged through [unclaimedDwell] against the words without it. It
         * never doubles a letter; that is the loop's job alone (issue #337).
         * Its arc is still collapsed out of the travel and its inside still
         * belongs to its key, the same as a loop's.
         */
        val wiggleExtent: Float = 0f,
        /** A wiggle's worth as a pause, as a share of a full stop on the key, 0 to 1. */
        val wiggleWeight: Float = 0f,
        /**
         * Extra location cost, per sample, for placing any key but a mark's
         * own inside a loop or a wiggle; see [loopExtent]. Zero lets the
         * circle's edge place the letters around it as freely as any stretch
         * of stroke.
         *
         * A charge rather than a wall, and a small one, because the corpus
         * says so. Walling the inside off ([maxPointCost]) cost .9440 ->
         * .9410 on English and .7340 -> .7280 on Probhat's sloppy strokes,
         * all of it on the tremor curls a slow pivot draws, which read as
         * loops and sit where the next letter's approach begins. At 1.5 the
         * same strokes give back .9440 -> .9415 and nothing on Probhat; at
         * 4 it is the wall again.
         */
        val loopExclusion: Float = 1.5f,
        /**
         * Charge per unit of loop a finished word leaves undoubled — the
         * credit for looping, expressed as a charge on every other word so
         * the bound never has to anticipate it (see [loopExtent]). Zero
         * leaves only the waiver, which ties the doubled spelling with the
         * single one on shape and lets frequency decide. Free on strokes
         * with no doubled competitor, since every word then pays alike: the
         * plain corpus reads the same at 0 and 0.5, and the looped one goes
         * .835 to .852 on doubled words.
         */
        val unclaimedLoop: Float = 0.5f,
        /**
         * The most a shape learned from the user's own kept glides may shorten
         * a word's shape distance, in the shape channel's units. A word scores
         * against the nearer of its ideal path and the shapes this user has
         * drawn it as, but never more than this nearer than the ideal: a word
         * they have swiped before gets the benefit of how they draw it, not a
         * free pass over words they have not. Zero switches learned shapes
         * off.
         */
        val learnedShapeGain: Double = 0.35,
        val shapeSeeding: Boolean = false,
        /**
         * How much the whole stroke's *shape* counts, once its size and position
         * are taken out of it.
         *
         * The alignment scores where a stroke went in absolute terms, so it
         * punishes a user whose swipes are systematically small or offset — and
         * the noise sweep says that is the decoder's steepest axis by a distance
         * (top-1 .964 to .508 as strokes shrink, against .940 to .820 for the
         * corner cutting everyone worries about). This term asks a different
         * question of the top few candidates: never mind where it was drawn, was
         * it drawn in that shape? Zero switches it off.
         */
        val shapeChannel: Double = 45.0,
        /**
         * How far the stroke's *first* sample may sit from the word's first
         * key, in key widths.
         *
         * Split from [endRadius] because the two ends of a stroke are not the
         * same event: a touch-down is a deliberate placement, and a lift-off is
         * where a movement happened to stop.
         *
         * The split is expressible rather than load-bearing, and that is the
         * measurement rather than an omission. Swept independently, both sit on
         * a flat plateau — start .9550 from 1.3 to 2.0 and end .9550 from 1.6
         * all the way to 3.0, against .9508 and .9517 at 1.0 — so the one
         * number they replaced was not a compromise between them after all, and
         * there is no asymmetry here to exploit yet. The only thing either says
         * is that a *tight* anchor costs accuracy: whatever an anchor is for, it
         * is not for being strict.
         */
        val startRadius: Float = 1.6f,
        /** How far the stroke's *last* sample may sit from the word's last key,
         * in key widths. See [startRadius]. */
        val endRadius: Float = 1.6f,
        /** How close the stroke must pass to a key for that key's subtree to be
         * worth walking at all, in key widths. */
        val nearRadius: Float = 1.5f,
        /**
         * How many of a dictionary's commonest words a stroke may decode to,
         * or 0 for all of them.
         *
         * A swipe is a much weaker signal than a typed word: it says which keys
         * the finger went near and in what order, and any number of words fit
         * that loosely. The score is `ln(1 + frequency)` against
         * [shapeWeight] × alignment cost, and the logarithm flattens the
         * language model hard — a word a thousand times rarer trails by 6.9
         * nats, which a shape advantage of 2.8 erases. On the 17k list that
         * shipped with the app there is no tail to lose to. On a 1.6M-word
         * corpus there are hundreds of thousands of words nobody writes, each
         * needing only a slightly better-fitting shape to beat the word that
         * was meant, and users read that as the decoder getting worse the more
         * words they give it (issue #28).
         *
         * The cap answers it on the axis the problem is actually on. It bounds
         * the *vocabulary a stroke chooses from* without touching the
         * dictionary: completion, autocorrect and the suggestion strip go on
         * seeing every word, because a typed prefix is evidence enough to pick
         * a rare word out of a million and a swipe is not. On that 1.6M-word
         * list it is worth 8 points of top-1 accuracy at 300k and 14 at 50k,
         * and makes the decode up to three times faster; the measurement is
         * written out on `GlideVocabulary` in :core:settings, which is where
         * the setting picks a value.
         *
         * 0 here, so the decoder on its own is uncapped and the eval harness
         * measures what it always measured. The shipped default lives in the
         * setting.
         *
         * Applied per source, and only where the source has a frequency
         * ranking to apply it to — the personal lexicon and the curated
         * romanized spellings return 0 from
         * [com.wasimaster.wmkeyboard.core.prediction.TrieWalker.frequencyAtRank]
         * and are never capped, so a word the user taught the keyboard stays
         * glidable however rare it is.
         */
        val vocabularyRank: Int = 0,
        /**
         * Nats charged per character a look-ahead candidate carries beyond what
         * the stroke has drawn.
         *
         * A completion competes against readings that explain the whole stroke
         * with nothing left over, and it has a structural advantage over them:
         * fewer letters to place means fewer chances to place one badly, and
         * `ln(1 + frequency)` of the best word under a prefix is an upper bound
         * on any single word's. Without a charge the decoder would answer every
         * two-letter stroke with the commonest long word starting that way.
         *
         * Per character rather than a flat charge because the guess really does
         * get weaker with length: two letters ahead is a near certainty on a
         * word the user writes daily, eight is a bet on their sentence.
         */
        val lookAheadCost: Double = 1.4,
        /**
         * How many prefix states may be expanded into a completion in one
         * decode.
         *
         * Each costs a walk down the trie to find the best word under the
         * prefix, which is cheap but not free, and the search pops best-first —
         * so the states worth asking about come early and a budget spent in
         * order loses nothing that would have won.
         */
        val lookAheadBudget: Int = 48,
        /**
         * Alignment charged for each [GlideJoiners] character the reading stepped
         * over — a hyphen, a dot, an at sign, anything a word is spelled with and
         * a finger cannot draw (issue #230).
         *
         * The skip has to cost *something*. `e-mail` and `email` are the same
         * stroke, and so are `co-op` and `coop`; with the skip free the two
         * readings are separated by frequency alone, and a list that holds the
         * hyphenated spelling at a higher count would take the strip's top slot
         * for a word most people write plain. A charge in the same currency as
         * [repeatCost] — a flat alignment penalty, so ×[shapeWeight] nats —
         * breaks that tie toward the unpunctuated spelling while leaving the
         * punctuated one on the strip, which is the direction a user can fix in
         * one tap either way.
         *
         * Small on purpose: 0.5 nats is about a factor of 1.6 in frequency, so
         * it settles ties and nothing more. It is not a filter, and a word the
         * user added by hand still wins the moment nothing plain competes.
         */
        val joinerCost: Float = 0.2f,
    ) {
        val invTwoSigmaSq: Float get() = 1f / (2f * sigma * sigma)
        val startRadiusSq: Float get() = startRadius * startRadius
        val endRadiusSq: Float get() = endRadius * endRadius
        val nearCost: Float get() = nearRadius * nearRadius * invTwoSigmaSq
        val loopRadiusSq: Float get() = loopRadius * loopRadius

        /** [dwellFull] as the divisor it is used as, floored off zero. */
        val dwellScale: Float get() = if (dwellFull > MIN_DWELL_FULL) dwellFull else MIN_DWELL_FULL

        companion object {
            /**
             * The shipped weights, so a setting that exposes one of them can
             * default to what the decoder does rather than to a number retyped
             * beside it. A settings default that drifts from the decoder's is
             * invisible until someone presses the row's reset.
             */
            val DEFAULT = Tuning()

            /**
             * What the wiggle reading is worth *when someone switches it on*.
             *
             * [wiggleExtent] and [wiggleWeight] ship at zero, which is the
             * reading switched off, so a setting that offers it needs a pair of
             * live numbers to start from and they belong here with the weights
             * rather than retyped beside a slider. These are the middle of the
             * band the sweep covered (`GlideTuningSweepTest`): an extent under
             * one key pitch, so a word crossing two adjacent keys can never
             * read as one, and half a full stop's worth of evidence, since a
             * rub is easier to draw by accident than a stop.
             */
            val WIGGLES_ON = DEFAULT.copy(wiggleExtent = 0.5f, wiggleWeight = 0.5f)

            /** Floor on [dwellScale]: at zero an evenly drawn step is 0/0. */
            private const val MIN_DWELL_FULL = 0.1f
        }
    }

    /**
     * Where a word's keys were visited along a stroke: the key index of each
     * visit (consecutive repeats collapsed) and the resampled stroke position
     * it was placed on, in key widths.
     *
     * [samples] is the resampled index each visit was placed on, `0` until
     * [GlideWorkspace.SAMPLE_POINTS], and [chars] the offset in the word at
     * which each visit's characters begin — together, what maps a place along
     * the stroke back to the letters written there (see `GlideCase.Letters`).
     */
    class Alignment(
        val keys: IntArray,
        val x: FloatArray,
        val y: FloatArray,
        val samples: IntArray = IntArray(keys.size),
        val chars: IntArray = IntArray(keys.size),
    ) {
        val size: Int get() = keys.size
    }

    class Candidate(
        val word: String,
        val score: Double,
        /** Total alignment cost: how badly the stroke had to be explained to
         * read this word off it. A confidence signal, low is good. */
        val shapeCost: Double,
        val tier: FuzzyBeamSearch.Tier,
        /**
         * Characters this word carries beyond what the stroke has drawn, or 0
         * when the stroke spells it out.
         *
         * Non-zero only when [decode] was asked for a look-ahead: the stroke so
         * far is a *prefix* of this word and the rest is a guess about where
         * the finger is going — "dictionary" off a stroke that has reached the
         * `c`. Worth keeping apart from an ordinary reading at every point
         * downstream, because the evidence behind it is different in kind:
         * everything after the prefix rests on the language model alone.
         */
        val ahead: Int = 0,
    )

    /**
     * Decodes [path] (pixel coordinates, keys [keyWidth] wide) against [sources],
     * returning up to [limit] words best first. Empty when the stroke is too
     * short to be a gesture, or when nothing in the tries explains it.
     * [shapes] are the user's own learned shapes for this grid, when they
     * have any, for the shape channel to compare against beside the ideal.
     */
    @Suppress("LongParameterList", "ReturnCount")
    fun decode(
        path: List<GesturePoint>,
        keys: GlideKeyMap,
        keyWidth: Float,
        sources: List<FuzzyBeamSearch.WalkSource>,
        ws: GlideWorkspace,
        limit: Int = 4,
        shapes: GlideShapeSource? = null,
        lookAhead: Int = 0,
    ): List<Candidate> {
        if (path.size < MIN_SAMPLES || keyWidth <= 0f) return emptyList()
        if (keys.keyCount == 0 || sources.isEmpty() || limit <= 0) return emptyList()
        if (!resample(path, keyWidth, ws)) return emptyList()
        ws.prepareKeys(keys.keyCount)
        buildCosts(keys, ws)
        buildDwell(keys, ws)
        if (ws.arcStep <= 0f) return emptyList()
        buildLoops(path, keyWidth, keys, ws)

        val k = maxOf(limit * 2, RESULT_K)
        val results = HashMap<String, Candidate>()
        // Completions are collected apart from the readings and never touch
        // `floor`. The floor is what makes the walk's bound admissible for
        // *words*; a completion scores on a prefix's alignment plus a guess,
        // and letting one raise the floor would prune real words that the
        // guess merely outscored.
        //
        // A stroke that ends on a loop asks for no guesses at all. The loop
        // is a deliberate mark on the last key the finger reached, and a
        // finger that drew it and lifted has said where the word ends: with
        // "finish words early" on, a circle closing "see" was otherwise
        // read as the start of "seer" (issue #337).
        val ahead = if (lookAhead > 0 && !endsOnLoop(ws)) HashMap<String, Candidate>() else null
        // One budget across every source, carried in a box so `searchOne` can
        // spend from it without threading a return value back.
        val budget = intArrayOf(tuning.lookAheadBudget)
        var floor = Double.NEGATIVE_INFINITY

        // Heaviest source first, so its emissions raise the floor before the
        // lighter ones start — the same ordering trick the typing beam uses.
        val ordered = sources.sortedByDescending {
            it.logWeight + ln1p(it.walker.maxSubtree(it.walker.root))
        }
        for (src in ordered) {
            val rootBound = src.logWeight + ln1p(src.walker.maxSubtree(src.walker.root))
            if (rootBound < floor - EPS) continue
            floor = searchOne(src, keys, ws, k, results, floor, ahead, budget)
        }

        val ranked = results.values.sortedWith(
            compareByDescending<Candidate> { it.score }.thenBy { it.word }
        )
        var read = rescoreShape(ranked, keys, ws, shapes).take(limit)

        // Inject or promote learned words whose saved shape matches the drawn stroke,
        // even if trie pruning or anchor mismatch filtered or demoted them in results.
        if (shapes != null && tuning.shapeSeeding && tuning.learnedShapeGain > 0.0) {
            quantise(ws.pathX, ws.pathY, ws.drawnShape8)
            val nearWords = shapes.wordsNear(ws.drawnShape8, radius = 0.35f, limit = limit)
            if (nearWords.isNotEmpty()) {
                val maxRescoredScore = read.maxOfOrNull { it.score } ?: 0.0
                val updatedRead = read.map { candidate ->
                    if (candidate.word in nearWords) {
                        Candidate(candidate.word, maxOf(candidate.score, maxRescoredScore), candidate.shapeCost, candidate.tier, candidate.ahead)
                    } else {
                        candidate
                    }
                }.toMutableList()

                val readWords = read.mapTo(HashSet()) { it.word }
                for (word in nearWords) {
                    if (word !in readWords) {
                        updatedRead.add(Candidate(word, maxRescoredScore, 0.0, FuzzyBeamSearch.Tier.DICTIONARY))
                    }
                }
                read = updatedRead.sortedWith(
                    compareByDescending<Candidate> { it.score }.thenBy { it.word }
                ).take(limit)
            }
        }
        if (ahead.isNullOrEmpty()) return read
        // A word the stroke spelled out is never also offered as a guess at
        // where it was going: one source can read it while another's prefix
        // completes to it, and the merged list would carry it twice.
        val readWords = read.mapTo(HashSet()) { it.word }
        val guesses = ahead.values.filterNot { it.word in readWords }
        // Merged into one ranked list so a caller sees the decoder's whole
        // opinion in score order; `Candidate.ahead` is what tells the two
        // apart, and every caller that cares checks it.
        //
        // The completions go through the shape channel too (issue #167).
        // They used to skip it, and every caller compares a guess's score
        // against the best ordinary reading's — so a reading paid the
        // channel's charge, up to several nats on a real finger, and the
        // guess extending it paid nothing. "thing" drawn to its last letter
        // lost to "things" on that gap alone, and a word the stroke had
        // spelled out could be beaten by any longer word it started.
        val completions = rescoreShape(guesses, keys, ws, shapes)
            .take(lookAhead)
        return (read + completions).sortedWith(
            compareByDescending<Candidate> { it.score }.thenBy { it.word }
        )
    }

    /**
     * [path] as the shape store keeps a stroke: resampled, normalised the way
     * the shape channel normalises the drawn stroke — centred, scaled to unit
     * spread — and quantised to a byte a coordinate. Null when the stroke is
     * too short to be a glide.
     */
    fun sampleShape(path: List<GesturePoint>, keyWidth: Float, ws: GlideWorkspace): ByteArray? {
        if (path.size < MIN_SAMPLES || keyWidth <= 0f) return null
        if (!resample(path, keyWidth, ws)) return null
        val out = ByteArray(2 * GlideWorkspace.SAMPLE_POINTS)
        quantise(ws.pathX, ws.pathY, out)
        return out
    }

    /**
     * Lays [word] over [path] the way the search would have, and says where
     * along the stroke each of its keys landed — the evidence the hand model
     * learns from (issue #52). The same cost model as the walk, one word
     * instead of a lattice, with the argmin kept at every cell so the placement
     * can be read back off the finished column. Null when the word has a
     * character [keys] cannot produce, has fewer than two keys, or cannot reach
     * the stroke's last sample at all.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "NestedBlockDepth")
    fun align(
        word: String,
        path: List<GesturePoint>,
        keys: GlideKeyMap,
        keyWidth: Float,
        ws: GlideWorkspace,
    ): Alignment? {
        if (path.size < MIN_SAMPLES || keyWidth <= 0f || keys.keyCount == 0) return null
        val visits = IntArray(GlideWorkspace.MAX_IDEAL_POINTS)
        val starts = IntArray(GlideWorkspace.MAX_IDEAL_POINTS)
        var count = 0
        var previous = -1
        var at = 0
        while (at < word.length) {
            val codePoint = word.codePointAt(at)
            val start = at
            at += Character.charCount(codePoint)
            val key = keys.keyIndex(codePoint)
            // A joiner is not a visit: the walk stepped over it, so the
            // alignment it is read back against has to step over it too, or
            // every hyphenated word would report "cannot be drawn" and take
            // its shape lesson and its casing with it (#230). The next letter
            // keeps its own offset, so [Alignment.chars] still points past the
            // hyphen and not at it.
            if (key < 0) {
                if (GlideJoiners.isJoiner(codePoint)) continue
                return null
            }
            if (key == previous) continue
            if (count >= GlideWorkspace.MAX_IDEAL_POINTS) return null
            visits[count] = key
            starts[count] = start
            count++
            previous = key
        }
        if (count < 2) return null
        if (!resample(path, keyWidth, ws)) return null
        if (ws.arcStep <= 0f) return null
        ws.prepareKeys(keys.keyCount)
        buildCosts(keys, ws)
        buildLoops(path, keyWidth, keys, ws)

        val n = GlideWorkspace.SAMPLE_POINTS
        val keyCount = keys.keyCount
        val cost = ws.pointCost
        val step = ws.arcStep
        val arc = ws.arcAt
        val back = IntArray(count * n)
        var prev = FloatArray(n)
        var cur = FloatArray(n)
        prev[0] = cost[visits[0]]
        for (j in 1 until n) prev[j] = GlideWorkspace.UNREACHABLE
        for (m in 1 until count) {
            val key = visits[m]
            val span = keys.distance(visits[m - 1], key)
            val expected = span / step
            val lo = maxOf(1, (expected - tuning.gapWindow).toInt())
            val hi = maxOf(lo, (expected + tuning.gapWindow).toInt() + 1) + ws.collapsedSamples
            cur[0] = GlideWorkspace.UNREACHABLE
            for (j in 1 until n) {
                var best = GlideWorkspace.UNREACHABLE
                var from = -1
                val arcJ = arc[j]
                var i = maxOf(0, j - hi)
                val to = j - lo
                while (i <= to) {
                    val before = prev[i]
                    if (before < GlideWorkspace.UNREACHABLE) {
                        val gap = arcJ - arc[i] - span
                        val v = before + tuning.gapWeight * (if (gap < 0f) -gap else gap)
                        if (v < best) {
                            best = v
                            from = i
                        }
                    }
                    i++
                }
                cur[j] = if (from < 0) GlideWorkspace.UNREACHABLE else best + cost[j * keyCount + key]
                back[m * n + j] = from
            }
            val swap = prev
            prev = cur
            cur = swap
        }
        if (prev[n - 1] >= GlideWorkspace.UNREACHABLE) return null

        val x = FloatArray(count)
        val y = FloatArray(count)
        val samples = IntArray(count)
        var j = n - 1
        for (m in count - 1 downTo 0) {
            x[m] = ws.pathX[j]
            y[m] = ws.pathY[j]
            samples[m] = j
            if (m > 0) j = back[m * n + j]
        }
        return Alignment(visits.copyOf(count), x, y, samples, starts.copyOf(count))
    }

    // ---- the walk ----

    // The vocabulary cap adds a subtree prune and an emit gate, which takes the
    // cognitive-complexity count one past the threshold. Splitting the loop to
    // get it back would cost more than it buys: this is one best-first walk over
    // a shared column, and every branch in it is on the hot path.
    @Suppress(
        "LongParameterList", "CyclomaticComplexMethod", "NestedBlockDepth",
        "CognitiveComplexMethod",
    )
    private fun searchOne(
        src: FuzzyBeamSearch.WalkSource,
        keys: GlideKeyMap,
        ws: GlideWorkspace,
        k: Int,
        results: HashMap<String, Candidate>,
        floorIn: Double,
        ahead: HashMap<String, Candidate>?,
        budget: IntArray,
    ): Double {
        var floor = floorIn
        val walker = src.walker
        val n = GlideWorkspace.SAMPLE_POINTS
        val keyCount = keys.keyCount
        // The vocabulary cap as this source's own frequency. A source with no
        // frequency ranking answers 0, which switches every test below off.
        val minFrequency = walker.frequencyAtRank(tuning.vocabularyRank)

        ws.reset()
        ws.push(
            node = walker.root, parent = -1, viaLabel = GlideWorkspace.NO_LABEL,
            lastKey = -1, length = 0, extra = 0f, floorCost = 0f,
            bound = src.logWeight + ln1p(walker.maxSubtree(walker.root)),
            letterCp = 0,
        )

        var pops = 0
        while (ws.heapSize > 0 && pops < MAX_POPS) {
            val s = ws.popBest()
            if (ws.bound[s] < floor - EPS) break
            pops++

            val node = ws.node[s]
            val length = ws.length[s].toInt()
            val extra = ws.extra[s]
            val lastKey = ws.lastKey[s]

            // The prefix's last letter must land on the last sample. For a
            // finished word that is where the finger lifted; for a look-ahead
            // it is where the finger is *now*, which is the same condition and
            // the same anchor.
            // `lastKey` is -1 at the root, which has spelled nothing and can
            // be neither a word nor a prefix worth guessing from.
            val atEnd = lastKey >= 0 && ws.endKey[lastKey] &&
                ws.cols[ws.columnOf(s) + n - 1] < GlideWorkspace.UNREACHABLE
            if (atEnd) {
                val total = ws.cols[ws.columnOf(s) + n - 1]
                // Charged here and not on the way down: only a finished
                // word knows which pauses none of its letters claim. The
                // bound stays admissible since the charge can only lower
                // a score, never raise one.
                val shape = total + extra + unclaimedDwell(s, keys, ws) +
                    unclaimedLoop(s, ws)
                if (length >= MIN_WORD_LENGTH && walker.isWord(node) &&
                    walker.frequency(node) >= minFrequency
                ) {
                    val score = src.logWeight + ln1p(walker.frequency(node)) -
                        tuning.shapeWeight * shape
                    if (score > floor - EPS || results.size < k) {
                        val word = ws.materialize(s)
                        if (!listedNonWord(word, src)) {
                            emit(word, score, shape.toDouble(), src.tier, results)
                            if (results.size >= k) floor = kthBest(results, k, ws)
                        }
                    }
                }
                if (ahead != null && budget[0] > 0 && length >= MIN_LOOKAHEAD_PREFIX) {
                    lookAhead(src, walker, node, s, shape, minFrequency, ws, ahead, budget)
                }
            }
            if (length >= MAX_WORD_LENGTH || length >= n) continue

            val count = walker.childrenInto(node, ws.children)
            // The trie is spelled in UTF-16 units, so a letter outside the BMP
            // is two edges: the high surrogate, then the low. A state that has
            // taken the high half is waiting on the low, and only the pair
            // names a key — every Warang Citi letter shares one high half.
            val pendingHigh = ws.viaLabel[s].takeIf { it.isHighSurrogate() }
            for (i in 0 until count) {
                val label = ws.children.labels[i]
                val child = ws.children.nodes[i]
                val subtree = walker.maxSubtree(child)
                // Every word under here is at most `subtree` frequent, so a
                // subtree below the cap holds nothing the emit gate would keep.
                // Pruning it makes a capped decode cheaper than an uncapped
                // one rather than merely quieter.
                if (subtree < minFrequency) continue

                val codePoint: Int
                if (pendingHigh != null) {
                    // Anything but a low surrogate here is a malformed word,
                    // which no grid can spell.
                    if (!label.isLowSurrogate()) continue
                    codePoint = Character.toCodePoint(pendingHigh, label)
                } else if (label.isHighSurrogate()) {
                    // Half a letter: let the low half decide, and until it does
                    // spend none of the stroke on it.
                    if (!descendSilent(src, subtree, s, child, label, extra, floor, ws)) break
                    continue
                } else {
                    codePoint = label.code
                }

                val key = keys.keyIndex(codePoint)
                // A hyphen, a dot, an at sign: spelled in the word, absent from
                // the grid, and never drawn. Step over it for the price of
                // `joinerCost` rather than losing the word with it (#230). Only
                // between letters — a word cannot be *started* on a character
                // the stroke's first sample has nothing to anchor to — and
                // never twice running, which bounds the detour a malformed
                // entry can send the walk on.
                if (key < 0 && length > 0 && GlideJoiners.isJoiner(codePoint) &&
                    !GlideJoiners.isJoiner(ws.viaLabel[s].code)
                ) {
                    val cost = extra + tuning.joinerCost
                    if (!descendSilent(src, subtree, s, child, label, cost, floor, ws)) break
                    continue
                }
                // A character the grid cannot produce makes its whole subtree
                // unreachable; so does one the stroke never went near.
                if (key < 0 || key >= keyCount || !ws.nearKey[key]) continue
                if (length == 0 && !ws.startKey[key]) continue

                val repeat = length > 0 && codePoint == ws.letterCp[s]

                val minCol: Float
                var childExtra = extra
                if (repeat) {
                    // A doubled letter is one key visited once, so the stroke
                    // holds no evidence of it at all: the alignment carries
                    // over untouched and the repeat pays a flat charge instead.
                    // Without the charge every doubled spelling would shadow
                    // its single one for free. A loop on the key is the
                    // evidence, and waives it; a pause is not (issue #337).
                    minCol = ws.floorCost[s]
                    childExtra += repeatCharge(lastKey, ws)
                } else {
                    minCol = advance(
                        ws,
                        prevColumn = if (length == 0) -1 else ws.columnOf(s),
                        key = key,
                        prevKey = lastKey,
                        keys = keys,
                    )
                    if (minCol >= GlideWorkspace.UNREACHABLE) continue
                }

                val bound = src.logWeight + ln1p(subtree) -
                    tuning.shapeWeight * (minCol + childExtra)
                if (bound < floor - EPS) continue

                val id = ws.push(
                    node = child, parent = s, viaLabel = label, lastKey = key,
                    length = length + 1, extra = childExtra, floorCost = minCol, bound = bound,
                    letterCp = codePoint,
                )
                if (id < 0) break // pool saturated; best-first says the rest are worse
                if (repeat) ws.copyColumn(s, id) else ws.storeScratch(id)
            }
        }
        return floor
    }

    /**
     * Whether [word] is a word list's non-word that the decoder should not
     * offer: an interrupted word, a stray full stop or a stutter (see
     * [GlideJoiners.isNonWord], issue #304). Only for word lists. The personal
     * lexicon holds what this user wrote or added, and #230 was about letting
     * them glide exactly that.
     */
    private fun listedNonWord(word: String, src: FuzzyBeamSearch.WalkSource): Boolean =
        src.tier == FuzzyBeamSearch.Tier.DICTIONARY && GlideJoiners.isNonWord(word)

    /**
     * Descends the edge [label] into [child] without spending any of the stroke:
     * the alignment column, the letter count, the last key and the last letter
     * all carry over untouched, and only [extra] may differ from the parent's.
     *
     * Two edges are walked this way and they are the same case — a character in
     * the word that the stroke has nothing to say about. The high half of a
     * surrogate pair is half a letter and waits for the low half to name a key;
     * a [GlideJoiners] character is a whole character with no key at all, and
     * pays [Tuning.joinerCost] for the privilege.
     *
     * False when the state pool saturated, which best-first says the caller
     * should take as "the rest of these children are worse" and stop.
     */
    @Suppress("LongParameterList")
    private fun descendSilent(
        src: FuzzyBeamSearch.WalkSource,
        subtree: Int,
        s: Int,
        child: Int,
        label: Char,
        extra: Float,
        floor: Double,
        ws: GlideWorkspace,
    ): Boolean {
        val bound = src.logWeight + ln1p(subtree) -
            tuning.shapeWeight * (ws.floorCost[s] + extra)
        if (bound < floor - EPS) return true
        val id = ws.push(
            node = child, parent = s, viaLabel = label, lastKey = ws.lastKey[s],
            length = ws.length[s].toInt(), extra = extra, floorCost = ws.floorCost[s],
            bound = bound, letterCp = ws.letterCp[s],
        )
        if (id < 0) return false
        ws.copyColumn(s, id)
        return true
    }

    /**
     * Offers the commonest word under the prefix at state [s] as a look-ahead
     * candidate — the stroke drawn so far explains that prefix, and the rest of
     * the word is the language model's guess about where the finger is going.
     *
     * The word is found by descending from [node] always into a child whose
     * subtree bound equals this node's, which by construction arrives at a word
     * carrying that bound: the walker's `maxSubtree` is the greatest frequency
     * anywhere beneath, so the branch holding it is the branch that still
     * reports it. That makes the frequency in the score the *actual* word's
     * rather than an upper bound over a subtree, which matters because the two
     * differ by exactly the amount that would make a long shot look certain.
     *
     * A prefix that is already the commonest thing under it offers nothing —
     * the ordinary emit above has that word, drawn rather than guessed.
     */
    @Suppress("LongParameterList")
    private fun lookAhead(
        src: FuzzyBeamSearch.WalkSource,
        walker: TrieWalker,
        node: Int,
        s: Int,
        shape: Float,
        minFrequency: Int,
        ws: GlideWorkspace,
        ahead: HashMap<String, Candidate>,
        budget: IntArray,
    ) {
        val best = walker.maxSubtree(node)
        if (best < minFrequency || best <= walker.frequency(node)) return
        budget[0]--
        val prefix = ws.materialize(s)
        val word = StringBuilder(prefix)
        var at = node
        var steps = 0
        while (steps < MAX_WORD_LENGTH) {
            if (walker.isWord(at) && walker.frequency(at) == best) break
            // Its own buffer: `ws.children` belongs to the walk that called
            // this, and sharing it would make the walk correct only for as
            // long as it happens to refill the buffer after this returns.
            val children = ws.lookAheadChildren
            val count = walker.childrenInto(at, children)
            var next = -1
            var label = '\u0000'
            for (i in 0 until count) {
                if (walker.maxSubtree(children.nodes[i]) == best) {
                    next = children.nodes[i]
                    label = children.labels[i]
                    break
                }
            }
            if (next < 0) return
            word.append(label)
            at = next
            steps++
        }
        if (steps == 0 || word.length <= prefix.length) return
        val spelled = word.toString()
        if (listedNonWord(spelled, src)) return
        val extra = spelled.length - prefix.length
        val score = src.logWeight + ln1p(best) - tuning.shapeWeight * shape -
            tuning.lookAheadCost * extra
        val existing = ahead[spelled]
        if (existing == null || score > existing.score) {
            ahead[spelled] = Candidate(spelled, score, shape.toDouble(), src.tier, extra)
        }
    }

    /**
     * Extends the alignment by one letter sitting on [key], writing the new
     * column into [GlideWorkspace.scratch] and returning its lowest value.
     *
     * [prevColumn] is the parent's column offset, or -1 for the word's first
     * letter, which is anchored to the stroke's first sample: that is where the
     * finger went down, so there is nothing to search over.
     *
     * For every later letter the DP asks, for each sample `j`, which earlier
     * sample `i` the previous letter sat on. Only a band of `i` is worth
     * checking. The stroke's travel between the two samples is `(j - i)` steps
     * of known length, the two keys are a known distance apart, and the two
     * should agree; [GAP_WINDOW] bounds how far they may disagree before the
     * placement is not worth scoring at all. That band is what keeps this a
     * bounded pass rather than the quadratic scan the exact formulation asks
     * for, and it costs nothing real — a placement outside it carries a gap
     * penalty no word recovers from.
     */
    private fun advance(
        ws: GlideWorkspace,
        prevColumn: Int,
        key: Int,
        prevKey: Int,
        keys: GlideKeyMap,
    ): Float {
        val n = GlideWorkspace.SAMPLE_POINTS
        val keyCount = keys.keyCount
        val cost = ws.pointCost
        val out = ws.scratch

        if (prevColumn < 0) {
            out[0] = cost[key]
            for (j in 1 until n) out[j] = GlideWorkspace.UNREACHABLE
            return out[0]
        }

        val prev = ws.cols
        val step = ws.arcStep
        val arc = ws.arcAt
        val span = keys.distance(prevKey, key)
        // Samples the stroke should have travelled between the two letters.
        // A loop's collapsed arc can only make the finger's travel read as
        // less than its sample count says, so it widens the far edge alone.
        val expected = span / step
        val lo = maxOf(1, (expected - tuning.gapWindow).toInt())
        val hi = maxOf(lo, (expected + tuning.gapWindow).toInt() + 1) + ws.collapsedSamples

        out[0] = GlideWorkspace.UNREACHABLE
        var best = GlideWorkspace.UNREACHABLE
        for (j in 1 until n) {
            val from = maxOf(0, j - hi)
            val to = j - lo
            val arcJ = arc[j]
            var bestPrev = GlideWorkspace.UNREACHABLE
            var i = from
            while (i <= to) {
                val at = prev[prevColumn + i]
                if (at < GlideWorkspace.UNREACHABLE) {
                    val travelled = arcJ - arc[i]
                    val gap = travelled - span
                    val v = at + tuning.gapWeight * (if (gap < 0f) -gap else gap)
                    if (v < bestPrev) bestPrev = v
                }
                i++
            }
            val value = if (bestPrev >= GlideWorkspace.UNREACHABLE) {
                GlideWorkspace.UNREACHABLE
            } else {
                bestPrev + cost[j * keyCount + key]
            }
            out[j] = value
            if (value < best) best = value
        }
        return best
    }

    // ---- per-decode preparation ----

    /**
     * Resamples [path] to [GlideWorkspace.SAMPLE_POINTS] equidistant samples in
     * key-width coordinates, interpolating the clock along with the position so
     * timing survives. False when the stroke is too short to be a swipe at all.
     */
    private fun resample(path: List<GesturePoint>, keyWidth: Float, ws: GlideWorkspace): Boolean {
        val n = GlideWorkspace.SAMPLE_POINTS
        var totalPx = 0f
        for (i in 1 until path.size) {
            totalPx += distance(path[i - 1], path[i])
        }
        if (totalPx / keyWidth < MIN_STROKE_LENGTH) return false

        val step = totalPx / (n - 1)
        ws.arcStep = step / keyWidth
        // Travel is read off arcAt rather than off sample indices; identity
        // until buildLoops finds something to collapse.
        val arcStep = ws.arcStep
        for (j in 0 until n) ws.arcAt[j] = j * arcStep
        ws.collapsedSamples = 0
        val first = path.first()
        ws.pathX[0] = first.x / keyWidth
        ws.pathY[0] = first.y / keyWidth
        ws.pathT[0] = first.t

        var out = 1
        var index = 0
        var cx = first.x
        var cy = first.y
        var ct = first.t
        var accumulated = 0f
        while (out < n - 1 && index < path.size - 1) {
            val next = path[index + 1]
            val segment = sqrt((next.x - cx) * (next.x - cx) + (next.y - cy) * (next.y - cy))
            if (accumulated + segment >= step && segment > 0f) {
                val fraction = (step - accumulated) / segment
                cx += fraction * (next.x - cx)
                cy += fraction * (next.y - cy)
                ct += ((next.t - ct) * fraction).toLong()
                ws.pathX[out] = cx / keyWidth
                ws.pathY[out] = cy / keyWidth
                ws.pathT[out] = ct
                out++
                accumulated = 0f
            } else {
                accumulated += segment
                cx = next.x
                cy = next.y
                ct = next.t
                index++
            }
        }
        val last = path.last()
        while (out < n) {
            ws.pathX[out] = last.x / keyWidth
            ws.pathY[out] = last.y / keyWidth
            ws.pathT[out] = last.t
            out++
        }
        return true
    }

    /**
     * Fills the per-sample, per-key cost table and the three key masks the walk
     * prunes with: keys the stroke passed near at all, keys it could have
     * started on, and keys it could have ended on.
     */
    private fun buildCosts(keys: GlideKeyMap, ws: GlideWorkspace) {
        val n = GlideWorkspace.SAMPLE_POINTS
        val keyCount = keys.keyCount
        val cost = ws.pointCost
        val invTwoSigmaSq = tuning.invTwoSigmaSq
        val maxPointCost = tuning.maxPointCost
        val nearCost = tuning.nearCost
        for (i in 0 until n) {
            val base = i * keyCount
            val px = ws.pathX[i]
            val py = ws.pathY[i]
            for (k in 0 until keyCount) {
                val dx = px - keys.keyX[k]
                val dy = py - keys.keyY[k]
                val scaled = (dx * dx + dy * dy) * invTwoSigmaSq
                cost[base + k] = if (scaled > maxPointCost) maxPointCost else scaled
                if (scaled <= nearCost) ws.nearKey[k] = true
            }
        }
        anchorMask(ws.pathX[0], ws.pathY[0], keys, ws.startKey, tuning.startRadiusSq)
        anchorMask(ws.pathX[n - 1], ws.pathY[n - 1], keys, ws.endKey, tuning.endRadiusSq)
    }

    /**
     * Finds where the finger lingered and files each hold as a pause event.
     *
     * Because the path is resampled by arc length, a sample's timestamp gap is
     * the reciprocal of speed with no differentiating required: a step that took
     * much longer than the stroke's even pace is a step the finger was barely
     * moving through.
     *
     * Nothing for a stroke with no clock, which is every synthetic path in a
     * test that does not care about timing — and nothing is the right answer
     * there, since no-evidence and no-pause should charge the same.
     */
    private fun buildDwell(keys: GlideKeyMap, ws: GlideWorkspace) {
        if (tuning.unclaimedDwell <= 0f) return
        val n = GlideWorkspace.SAMPLE_POINTS
        val span = (ws.pathT[n - 1] - ws.pathT[0]).toFloat()
        if (span <= 0f) return
        val evenStep = span / (n - 1)
        val dwellScale = tuning.dwellScale
        var runEnd = -1
        for (j in 1 until n) {
            val step = (ws.pathT[j] - ws.pathT[j - 1]).toFloat()
            val lingering = ((step / evenStep) - 1f) / dwellScale
            if (lingering <= 0f) continue
            val score = if (lingering > 1f) 1f else lingering
            recordPause(j, score, runEnd, ws)
            runEnd = j
        }
        for (p in 0 until ws.pauseCount) {
            val j = ws.pauseAt[p]
            placePause(p, ws.pathX[j], ws.pathY[j], keys, ws)
        }
    }

    /**
     * Puts pause event [p] at ([x], [y]) and works out which keys may claim
     * it: those within `DWELL_RADIUS` of it and no more than `DWELL_TIE`
     * farther than the nearest key. A stop is on one key, so the key next door
     * does not get to answer for it; the tie is for a stop drawn on the line
     * between two, which says nothing about which of them was meant and
     * should favour neither.
     */
    private fun placePause(p: Int, x: Float, y: Float, keys: GlideKeyMap, ws: GlideWorkspace) {
        var nearestSq = Float.MAX_VALUE
        for (k in 0 until keys.keyCount) {
            val dx = x - keys.keyX[k]
            val dy = y - keys.keyY[k]
            val d = dx * dx + dy * dy
            if (d < nearestSq) nearestSq = d
        }
        val reach = minOf(DWELL_RADIUS, sqrt(nearestSq) + DWELL_TIE)
        ws.pauseX[p] = x
        ws.pauseY[p] = y
        ws.pauseReachSq[p] = reach * reach
    }

    /**
     * Files sample [j]'s slowness as a pause event. A true standstill puts no
     * arc on the path, so all of its time lands in the one step that crosses
     * it; slow *travel* smears over several adjacent steps instead, and those
     * are one hold, not several — one event, sitting on whichever sample was
     * slowest, so a crawl through a key is charged once. [runEnd] is the last
     * sample filed, for telling adjacent from separate.
     */
    private fun recordPause(j: Int, score: Float, runEnd: Int, ws: GlideWorkspace) {
        val count = ws.pauseCount
        if (count > 0 && runEnd == j - 1) {
            if (score > ws.pauseScore[count - 1]) {
                ws.pauseScore[count - 1] = score
                ws.pauseAt[count - 1] = j
            }
            return
        }
        ws.pauseAt[count] = j
        ws.pauseScore[count] = score
        ws.pauseCount = count + 1
    }

    /**
     * The pauses along the stroke that none of state [s]'s letters sit on,
     * summed by how still the finger was at each, times [Tuning.unclaimedDwell].
     *
     * A pause is claimed by a key within the reach [placePause] gave it: the
     * key it happened on, and never its neighbour. The word's keys are read
     * straight off the parent chain, so this costs a walk of the word per pause
     * and allocates nothing.
     */
    private fun unclaimedDwell(s: Int, keys: GlideKeyMap, ws: GlideWorkspace): Float {
        val weight = tuning.unclaimedDwell
        if (weight <= 0f || ws.pauseCount == 0) return 0f
        var charge = 0f
        for (p in 0 until ws.pauseCount) {
            val px = ws.pauseX[p]
            val py = ws.pauseY[p]
            val reachSq = ws.pauseReachSq[p]
            var claimed = false
            var cur = s
            while (cur >= 0 && !claimed) {
                val key = ws.lastKey[cur]
                if (key >= 0) {
                    val dx = px - keys.keyX[key]
                    val dy = py - keys.keyY[key]
                    claimed = dx * dx + dy * dy <= reachSq
                }
                cur = ws.parent[cur]
            }
            if (!claimed) charge += ws.pauseScore[p]
        }
        return weight * charge
    }

    // ---- loops ----

    /**
     * Finds the places the finger went round on itself or rubbed back and
     * forth and files each as an event: the samples it covers, where it sat,
     * the key it is on, and how much of a doubled-letter mark it is — a loop
     * all of one, a wiggle none, since a wiggle files a pause instead. Then
     * leaves each event's inside to its key and collapses the events' arc out
     * of [GlideWorkspace.arcAt]. See [Tuning.loopExtent] for the reading and
     * [Tuning.wiggleExtent] for the wiggle's. Nothing happens at zero extent,
     * and a stroke with no loops leaves the workspace as it found it.
     *
     * The search runs on a fine resample of the stroke rather than on the
     * alignment's [GlideWorkspace.SAMPLE_POINTS]: on a long word those sit
     * half a key apart, a loop is three of them, and no three points make a
     * circle. The first start that passes is usually a step or two early, on
     * the way in, so once a window is found every later start inside it is
     * tried too and the roundest wins — the loop itself rather than the loop
     * with its approach.
     */
    private fun buildLoops(path: List<GesturePoint>, keyWidth: Float, keys: GlideKeyMap, ws: GlideWorkspace) {
        ws.loopCount = 0
        val reach = maxOf(tuning.loopExtent, tuning.wiggleExtent)
        if (reach <= 0f) return
        resampleFine(path, keyWidth, ws)
        val n = ws.fineCount
        var a = 0
        while (a < n - 2) {
            var window = roundestWindow(a, reach, ws)
            if (window == NO_WINDOW) {
                a++
                continue
            }
            var from = a
            var other = a + 1
            while (other < windowEnd(window) - 1) {
                val later = roundestWindow(other, reach, ws)
                if (later != NO_WINDOW && windowRoundness(later) > windowRoundness(window)) {
                    window = later
                    from = other
                }
                other++
            }
            val b = windowEnd(window)
            val extent = sqrt(extentSq(from, b, ws))
            val loop = windowRoundness(window) >= LOOP_ROUNDNESS && extent <= tuning.loopExtent
            val wiggle = !loop && tuning.wiggleExtent > 0f && extent <= tuning.wiggleExtent
            if (!loop && !wiggle) {
                a++
                continue
            }
            val e = recordLoop(from, b, if (loop) 1f else 0f, keys, ws)
            if (wiggle) recordWiggle(e, keys, ws)
            a = b + 1
        }
        if (ws.loopCount > 0) {
            excludeNeighbours(keys, ws)
            collapseArc(ws)
        }
    }

    /**
     * Whether the stroke's last doubling loop ends within [END_LOOP_REACH] of
     * where the finger lifted, measured along the collapsed arc: a loop drawn
     * as the stroke's last act, with at most the flick of the lift after it.
     */
    private fun endsOnLoop(ws: GlideWorkspace): Boolean {
        val last = GlideWorkspace.SAMPLE_POINTS - 1
        for (e in ws.loopCount - 1 downTo 0) {
            if (ws.loopScore[e] <= 0f || ws.loopKey[e] < 0) continue
            return ws.arcAt[last] - ws.arcAt[ws.loopTo[e]] <= END_LOOP_REACH
        }
        return false
    }

    /**
     * Files the wiggle recorded as loop event [e] as a pause on its key,
     * worth [Tuning.wiggleWeight] of a full stop: a rub is how a finger that
     * would rather not stop says the key is in the word. The loop event it
     * also is carries no doubling weight and is kept only so its arc is
     * collapsed and its inside left to its key.
     */
    private fun recordWiggle(e: Int, keys: GlideKeyMap, ws: GlideWorkspace) {
        val p = ws.pauseCount
        if (tuning.unclaimedDwell <= 0f || tuning.wiggleWeight <= 0f) return
        if (p >= GlideWorkspace.SAMPLE_POINTS) return
        ws.pauseAt[p] = (ws.loopFrom[e] + ws.loopTo[e]) / 2
        ws.pauseScore[p] = tuning.wiggleWeight
        placePause(p, ws.loopX[e], ws.loopY[e], keys, ws)
        ws.pauseCount = p + 1
    }

    /**
     * Leaves the samples strictly inside every mark to the mark's own key:
     * every other key pays [Tuning.loopExclusion] more there, up to
     * [Tuning.maxPointCost].
     *
     * A loop drawn on the o of `good` sweeps over the i, the p, the k and the
     * l on its way round, and before this the alignment was free to place any
     * of their letters on those samples — so the circle that meant "the o
     * twice" pulled in words that went through the keys around it (issue
     * #337). The first and last samples of the window stay open to every key:
     * that is where the stroke arrives from the letter before and leaves for
     * the one after. A mark on no key excludes nothing.
     */
    private fun excludeNeighbours(keys: GlideKeyMap, ws: GlideWorkspace) {
        val charge = tuning.loopExclusion
        if (charge <= 0f) return
        val keyCount = keys.keyCount
        val cost = ws.pointCost
        val cap = tuning.maxPointCost
        for (e in 0 until ws.loopCount) {
            val own = ws.loopKey[e]
            if (own < 0) continue
            for (j in ws.loopFrom[e] + 1 until ws.loopTo[e]) {
                val base = j * keyCount
                for (k in 0 until keyCount) {
                    if (k != own) cost[base + k] = minOf(cap, cost[base + k] + charge)
                }
            }
        }
    }

    /**
     * Resamples [path] into [GlideWorkspace.fineX] and [GlideWorkspace.fineY]
     * at [FINE_SPACING] key widths a point — coarser only when a stroke would
     * need more than [GlideWorkspace.MAX_FINE_POINTS] of them. Position only:
     * the loop search has no use for the clock.
     */
    private fun resampleFine(path: List<GesturePoint>, keyWidth: Float, ws: GlideWorkspace) {
        var totalPx = 0f
        for (i in 1 until path.size) totalPx += distance(path[i - 1], path[i])
        val total = totalPx / keyWidth
        val spacing = maxOf(FINE_SPACING, total / (GlideWorkspace.MAX_FINE_POINTS - 1))
        val count = (total / spacing).toInt() + 1
        ws.prepareFine(count)
        ws.fineStep = spacing
        val stepPx = spacing * keyWidth
        val first = path.first()
        ws.fineX[0] = first.x / keyWidth
        ws.fineY[0] = first.y / keyWidth
        var out = 1
        var index = 0
        var cx = first.x
        var cy = first.y
        var accumulated = 0f
        while (out < count && index < path.size - 1) {
            val next = path[index + 1]
            val segment = sqrt((next.x - cx) * (next.x - cx) + (next.y - cy) * (next.y - cy))
            if (accumulated + segment >= stepPx && segment > 0f) {
                val fraction = (stepPx - accumulated) / segment
                cx += fraction * (next.x - cx)
                cy += fraction * (next.y - cy)
                ws.fineX[out] = cx / keyWidth
                ws.fineY[out] = cy / keyWidth
                out++
                accumulated = 0f
            } else {
                accumulated += segment
                cx = next.x
                cy = next.y
                index++
            }
        }
        ws.fineCount = out
    }

    /**
     * Of the windows of fine points starting at [a] that stay within [reach]
     * of themselves and hold at least [Tuning.loopMinArc] of path and
     * `LOOP_RATIO` times their own extent, the roundest — its end and its
     * roundness packed into one long, or [NO_WINDOW].
     *
     * Roundness is the isoperimetric quotient `4πA / P²` of the polygon the
     * window's points make when closed back to the start: 1 for a circle,
     * nothing for a path that goes out and comes back along itself, and
     * nothing for a zigzag, whose signed area cancels. Turning was the
     * obvious measure and the wrong one: a loop drawn on a key that sits at
     * a reversal — the `o` of `good` on QWERTY — has the corner's own turn
     * cancelling half of the circle's, and a loop entered on a cusp cancels
     * all of it. Area does not care which way round the finger went.
     *
     * Path length is the sum of the chords, not the point count times the
     * spacing: a sharp corner between two points has less chord than
     * spacing, and counting the spacing turned every hairpin into a loop.
     * The extent is the window's widest pairwise distance, which only grows
     * as the window does, so the scan stops the moment it passes [reach].
     */
    private fun roundestWindow(a: Int, reach: Float, ws: GlideWorkspace): Long {
        val n = ws.fineCount
        val xs = ws.fineX
        val ys = ws.fineY
        val ax = xs[a]
        val ay = ys[a]
        val reachSq = reach * reach
        var extentSq = 0f
        var length = 0f
        var twiceArea = 0f
        var bestEnd = -1
        var bestRoundness = 0f
        var b = a + 1
        while (b < n && b - a <= MAX_WINDOW_POINTS) {
            val bx = xs[b]
            val by = ys[b]
            val sx = bx - xs[b - 1]
            val sy = by - ys[b - 1]
            length += sqrt(sx * sx + sy * sy)
            twiceArea += xs[b - 1] * by - bx * ys[b - 1]
            var i = a
            while (i < b) {
                val dx = xs[i] - bx
                val dy = ys[i] - by
                val d = dx * dx + dy * dy
                if (d > extentSq) extentSq = d
                i++
            }
            if (extentSq > reachSq) break
            if (length >= tuning.loopMinArc && extentSq >= MIN_LOOP_EXTENT_SQ &&
                length * length >= LOOP_RATIO * LOOP_RATIO * extentSq
            ) {
                val closed = twiceArea + (bx * ay - ax * by)
                val dx = bx - ax
                val dy = by - ay
                val perimeter = length + sqrt(dx * dx + dy * dy)
                val roundness = TWO_PI * (if (closed < 0f) -closed else closed) / (perimeter * perimeter)
                if (roundness > bestRoundness) {
                    bestRoundness = roundness
                    bestEnd = b
                }
            }
            b++
        }
        return if (bestEnd < 0) NO_WINDOW else (bestEnd.toLong() shl Int.SIZE_BITS) or
            (bestRoundness.toRawBits().toLong() and 0xffffffffL)
    }

    private fun windowEnd(window: Long): Int = (window shr Int.SIZE_BITS).toInt()

    private fun windowRoundness(window: Long): Float = Float.fromBits(window.toInt())

    /** Squared widest pairwise distance among fine points `[a, b]`. */
    private fun extentSq(a: Int, b: Int, ws: GlideWorkspace): Float {
        var extent = 0f
        for (j in a + 1..b) {
            for (i in a until j) {
                val dx = ws.fineX[i] - ws.fineX[j]
                val dy = ws.fineY[i] - ws.fineY[j]
                val d = dx * dx + dy * dy
                if (d > extent) extent = d
            }
        }
        return extent
    }

    /**
     * Files the fine window `[from, to]` as a loop event of strength [score]:
     * its centroid, the alignment samples it covers, how much of its path its
     * chord is — what the collapse leaves of it — and its key, the one nearest
     * the centroid within [Tuning.loopRadius], or -1. Only that key is
     * credited: a loop is a mark on one key, and crediting every key in reach
     * let a circle drawn a little off the f of `off` double the d of `odd`
     * (issue #337). Returns the event's index.
     */
    private fun recordLoop(from: Int, to: Int, score: Float, keys: GlideKeyMap, ws: GlideWorkspace): Int {
        val xs = ws.fineX
        val ys = ws.fineY
        var cx = 0f
        var cy = 0f
        var length = 0f
        for (j in from..to) {
            cx += xs[j]
            cy += ys[j]
            if (j > from) {
                val sx = xs[j] - xs[j - 1]
                val sy = ys[j] - ys[j - 1]
                length += sqrt(sx * sx + sy * sy)
            }
        }
        val points = (to - from + 1).toFloat()
        cx /= points
        cy /= points
        val dx = xs[to] - xs[from]
        val dy = ys[to] - ys[from]
        val chord = sqrt(dx * dx + dy * dy)

        // Onto the alignment's samples: the nearest to where the window
        // starts and ends, at least one step apart so there is something to
        // collapse.
        val last = GlideWorkspace.SAMPLE_POINTS - 1
        val toSample = ws.fineStep / ws.arcStep
        var a = minOf(last, (from * toSample + 0.5f).toInt())
        var b = minOf(last, (to * toSample + 0.5f).toInt())
        if (b <= a) {
            if (a < last) b = a + 1 else a = b - 1
        }
        val e = ws.loopCount
        ws.loopFrom[e] = a
        ws.loopTo[e] = b
        ws.loopShrink[e] = if (length > 0f) chord / length else 1f
        ws.loopX[e] = cx
        ws.loopY[e] = cy
        ws.loopScore[e] = score
        ws.loopCount = e + 1
        var own = -1
        var nearestSq = tuning.loopRadiusSq
        for (k in 0 until keys.keyCount) {
            val kx = cx - keys.keyX[k]
            val ky = cy - keys.keyY[k]
            val d = kx * kx + ky * ky
            if (d <= nearestSq) {
                nearestSq = d
                own = k
            }
        }
        ws.loopKey[e] = own
        if (own >= 0 && score > ws.keyLoop[own]) ws.keyLoop[own] = score
        return e
    }

    /**
     * Rebuilds [GlideWorkspace.arcAt] with every loop's arc shrunk to its
     * chord, spread evenly over its samples, and counts the samples' worth of
     * arc that removed. A closed loop's chord is near zero, so it vanishes
     * from every word's travel alike; a loop entered on one side and left on
     * the other still credits the finger with the distance it net-moved.
     */
    private fun collapseArc(ws: GlideWorkspace) {
        val n = GlideWorkspace.SAMPLE_POINTS
        val step = ws.arcStep
        val arc = ws.arcAt
        var removed = 0f
        var e = 0
        arc[0] = 0f
        for (j in 1 until n) {
            if (e < ws.loopCount && j > ws.loopFrom[e] && j <= ws.loopTo[e]) {
                val a = ws.loopFrom[e]
                val shrink = ws.loopShrink[e]
                arc[j] = arc[a] + (j - a) * step * shrink
                if (j == ws.loopTo[e]) {
                    removed += (j - a) * step * (1f - shrink)
                    e++
                }
            } else {
                arc[j] = arc[j - 1] + step
            }
        }
        ws.collapsedSamples = ceil(removed / step).toInt()
    }

    /**
     * What a doubled letter on [key] pays: the flat [Tuning.repeatCost] plus
     * [Tuning.unloopedRepeat], the whole of it waived to the extent the finger
     * looped there. Only a loop waives it: a pause or a wiggle says the key is
     * in the word, not that it is in it twice (issue #337). A charge dropped
     * and never a credit, so `extra` only ever grows along a chain and the
     * bound stays admissible.
     */
    private fun repeatCharge(key: Int, ws: GlideWorkspace): Float =
        (tuning.repeatCost + tuning.unloopedRepeat) * (1f - ws.keyLoop[key])

    /**
     * The loops along the stroke that none of state [s]'s *doubled* letters
     * sit on, summed by strength, times [Tuning.unclaimedLoop]. A loop is
     * claimed only by a doubling on its own key (see [recordLoop]). A repeat is
     * a state spelling the same letter as its parent one character further on;
     * the half-letter state of a surrogate pair carries its parent's letter
     * at the parent's length, which keeps it out.
     */
    private fun unclaimedLoop(s: Int, ws: GlideWorkspace): Float {
        val weight = tuning.unclaimedLoop
        if (weight <= 0f || ws.loopCount == 0) return 0f
        var charge = 0f
        for (e in 0 until ws.loopCount) {
            // A wiggle's event doubles nothing, and a loop on no key is
            // ignored by every word alike, which orders nothing.
            val own = ws.loopKey[e]
            if (ws.loopScore[e] <= 0f || own < 0) continue
            var claimed = false
            var cur = s
            while (cur >= 0 && !claimed) {
                val parent = ws.parent[cur]
                if (parent >= 0 && ws.letterCp[cur] == ws.letterCp[parent] &&
                    ws.length[cur].toInt() == ws.length[parent] + 1
                ) {
                    claimed = ws.lastKey[cur] == own
                }
                cur = parent
            }
            if (!claimed) charge += ws.loopScore[e]
        }
        return weight * charge
    }

    // ---- shape channel ----

    /**
     * Reorders the finished candidates by how well each one's *shape* matches
     * the stroke, with position and size normalised away.
     *
     * A rescore over the handful of words the search already emitted, rather
     * than a term inside it: the normalisation needs a whole candidate to
     * compute, and a per-state version would have no admissible bound and would
     * cost a resample per trie edge. Here it costs a resample per candidate.
     */
    private fun rescoreShape(
        ranked: List<Candidate>,
        keys: GlideKeyMap,
        ws: GlideWorkspace,
        shapes: GlideShapeSource?,
    ): List<Candidate> {
        val weight = tuning.shapeChannel
        // A lone candidate is rescored too, not merely reordered: its score
        // still gets compared, to the completions decoded beside it, and a
        // reading that skipped the charge because it had no rival would be
        // measured against guesses that paid it.
        if (weight <= 0.0 || ranked.isEmpty()) return ranked
        normalise(ws.pathX, ws.pathY, ws.drawnShapeX, ws.drawnShapeY)
        val learned = shapes?.takeIf { tuning.learnedShapeGain > 0.0 }
        if (learned != null) quantise(ws.pathX, ws.pathY, ws.drawnShape8)

        val rescored = ArrayList<Candidate>(ranked.size)
        for (candidate in ranked) {
            // A look-ahead candidate's ideal path runs through letters the
            // finger has not drawn, so comparing the whole shape against the
            // whole word would ask it to account for a stroke that does not
            // exist yet. What the finger *has* drawn is the prefix, and the
            // prefix's ideal path is exactly what an ordinary reading of the
            // same letters would be measured against — so a guess pays what
            // the word it extends pays, and the two compare on the letters
            // ahead alone. The learned shapes are of whole words and have
            // nothing to say about a prefix.
            val distance = if (candidate.ahead > 0) {
                shapeDistance(candidate.word.dropLast(candidate.ahead), keys, ws)
            } else {
                shapeDistance(candidate.word, keys, ws)
                    ?.let { ideal -> learnedDistance(candidate.word, ideal, learned, ws) }
            }

            rescored.add(
                if (distance == null) {
                    candidate
                } else {
                    Candidate(
                        candidate.word,
                        candidate.score - weight * distance,
                        candidate.shapeCost,
                        candidate.tier,
                        candidate.ahead,
                    )
                }
            )
        }
        return rescored.sortedWith(
            compareByDescending<Candidate> { it.score }.thenBy { it.word }
        )
    }

    /**
     * Mean distance between the normalised stroke and [word]'s normalised ideal
     * path, or null when the word cannot be drawn on this grid at all.
     */
    private fun shapeDistance(word: String, keys: GlideKeyMap, ws: GlideWorkspace): Double? {
        var count = 0
        var previous = -1
        var at = 0
        while (at < word.length) {
            val codePoint = word.codePointAt(at)
            at += Character.charCount(codePoint)
            val key = keys.keyIndex(codePoint)
            // A joiner has no point on the ideal path — the same skip the walk
            // made. Answering null instead would let a hyphenated reading out
            // of the shape channel entirely, so it would pay none of the charge
            // its rivals pay and win on that alone (#230).
            if (key < 0) {
                if (GlideJoiners.isJoiner(codePoint)) continue
                return null
            }
            // Consecutive letters on one key are one point of the path: the
            // finger visited it once, however many letters it stood for.
            if (key == previous) continue
            if (count >= GlideWorkspace.MAX_IDEAL_POINTS) return null
            ws.idealX[count] = keys.keyX[key]
            ws.idealY[count] = keys.keyY[key]
            count++
            previous = key
        }
        if (count < 2) return null
        resamplePolyline(ws.idealX, ws.idealY, count, ws.idealShapeX, ws.idealShapeY)
        normalise(ws.idealShapeX, ws.idealShapeY, ws.idealShapeX, ws.idealShapeY)

        var sum = 0.0
        for (j in 0 until GlideWorkspace.SAMPLE_POINTS) {
            val dx = ws.drawnShapeX[j] - ws.idealShapeX[j]
            val dy = ws.drawnShapeY[j] - ws.idealShapeY[j]
            sum += sqrt(dx * dx + dy * dy).toDouble()
        }
        return sum / GlideWorkspace.SAMPLE_POINTS
    }

    /**
     * [ideal] shortened by how this user draws [word], when [shapes] know: the
     * nearer of the ideal path and the word's learned spatial shapes.
     */
    private fun learnedDistance(
        word: String,
        ideal: Double,
        shapes: GlideShapeSource?,
        ws: GlideWorkspace,
    ): Double {
        shapes ?: return ideal
        val own = shapes.minDistance(word, ws.drawnShape8)
        if (own < 0f) return ideal
        return maxOf(minOf(ideal, own.toDouble()), ideal - tuning.learnedShapeGain)
    }

    /** A normalised path as the shape store keeps one: [GlideShapeStore.QUANT] to the unit, clamped to a byte. */
    private fun quantise(xs: FloatArray, ys: FloatArray, out: ByteArray) {
        for (j in 0 until GlideWorkspace.SAMPLE_POINTS) {
            out[2 * j] = (xs[j] * GlideShapeStore.QUANT).toInt().coerceIn(-SHAPE_BYTE_LIMIT, SHAPE_BYTE_LIMIT).toByte()
            out[2 * j + 1] = (ys[j] * GlideShapeStore.QUANT).toInt().coerceIn(-SHAPE_BYTE_LIMIT, SHAPE_BYTE_LIMIT).toByte()
        }
    }

    /**
     * Centres a path on its own centroid and scales it to unit spread, so that
     * comparing two paths compares their shapes and nothing else. In and out may
     * be the same arrays.
     */
    private fun normalise(xs: FloatArray, ys: FloatArray, outX: FloatArray, outY: FloatArray) {
        val n = GlideWorkspace.SAMPLE_POINTS
        var cx = 0f
        var cy = 0f
        for (j in 0 until n) {
            cx += xs[j]
            cy += ys[j]
        }
        cx /= n
        cy /= n
        var spread = 0f
        for (j in 0 until n) {
            val dx = xs[j] - cx
            val dy = ys[j] - cy
            spread += dx * dx + dy * dy
        }
        val scale = sqrt(spread / n).takeIf { it > MIN_SHAPE_SPREAD } ?: 1f
        for (j in 0 until n) {
            outX[j] = (xs[j] - cx) / scale
            outY[j] = (ys[j] - cy) / scale
        }
    }

    /** Resamples a [count]-point polyline to [GlideWorkspace.SAMPLE_POINTS] by arc length. */
    private fun resamplePolyline(
        xs: FloatArray,
        ys: FloatArray,
        count: Int,
        outX: FloatArray,
        outY: FloatArray,
    ) {
        val n = GlideWorkspace.SAMPLE_POINTS
        var total = 0f
        for (i in 1 until count) {
            val dx = xs[i] - xs[i - 1]
            val dy = ys[i] - ys[i - 1]
            total += sqrt(dx * dx + dy * dy)
        }
        if (total <= 0f) {
            for (j in 0 until n) {
                outX[j] = xs[0]
                outY[j] = ys[0]
            }
            return
        }
        val step = total / (n - 1)
        outX[0] = xs[0]
        outY[0] = ys[0]
        var out = 1
        var index = 0
        var cx = xs[0]
        var cy = ys[0]
        var accumulated = 0f
        while (out < n - 1 && index < count - 1) {
            val nx = xs[index + 1]
            val ny = ys[index + 1]
            val segment = sqrt((nx - cx) * (nx - cx) + (ny - cy) * (ny - cy))
            if (accumulated + segment >= step && segment > 0f) {
                val fraction = (step - accumulated) / segment
                cx += fraction * (nx - cx)
                cy += fraction * (ny - cy)
                outX[out] = cx
                outY[out] = cy
                out++
                accumulated = 0f
            } else {
                accumulated += segment
                cx = nx
                cy = ny
                index++
            }
        }
        while (out < n) {
            outX[out] = xs[count - 1]
            outY[out] = ys[count - 1]
            out++
        }
    }

    /** Which keys the anchor at ([x], [y]) could have meant, within [radiusSq]. */
    private fun anchorMask(
        x: Float,
        y: Float,
        keys: GlideKeyMap,
        out: BooleanArray,
        radiusSq: Float,
    ) {
        for (k in 0 until keys.keyCount) {
            val dx = x - keys.keyX[k]
            val dy = y - keys.keyY[k]
            out[k] = dx * dx + dy * dy <= radiusSq
        }
    }

    // ---- results ----

    private fun emit(
        word: String,
        score: Double,
        shapeCost: Double,
        tier: FuzzyBeamSearch.Tier,
        results: HashMap<String, Candidate>,
    ) {
        val existing = results[word]
        if (existing == null || score > existing.score) {
            results[word] = Candidate(word, score, shapeCost, tier)
        }
    }

    /** The [k]th best score in [results], sorted in the workspace's scratch rather than a fresh array per emit. */
    private fun kthBest(results: HashMap<String, Candidate>, k: Int, ws: GlideWorkspace): Double {
        val size = results.size
        if (size < k) return Double.NEGATIVE_INFINITY
        var scores = ws.scoreScratch
        if (scores.size < size) {
            scores = DoubleArray(size)
            ws.scoreScratch = scores
        }
        var i = 0
        for (c in results.values) scores[i++] = c.score
        java.util.Arrays.sort(scores, 0, size)
        return scores[size - k]
    }

    private fun distance(a: GesturePoint, b: GesturePoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }

    companion object {

        /** Strokes shorter than this are taps, in key widths. */
        private const val MIN_STROKE_LENGTH = 0.5f

        private const val MIN_SAMPLES = 3
        private const val MIN_WORD_LENGTH = 2

        /**
         * Letters a stroke must have spelled before the decoder will guess what
         * it is going to spell next.
         *
         * Three, because two is not evidence. A stroke that has reached its
         * second letter has been through one direction change at most, and
         * every long word starting with those two letters fits it about as
         * well — guessing there is guessing from frequency alone, dressed up as
         * a reading of a gesture.
         */
        private const val MIN_LOOKAHEAD_PREFIX = 3
        private const val MAX_WORD_LENGTH = 24

        /** Below this spread a path is a dot, and dividing by its size is noise. */
        private const val MIN_SHAPE_SPREAD = 1e-4f

        private const val SHAPE_BYTE_LIMIT = 127

        /** How near a key a pause has to happen to count as a pause on it, in key widths. */
        private const val DWELL_RADIUS = 0.8f

        /**
         * How much farther than the nearest key another key may sit from a
         * pause and still claim it, in key widths: enough for a stop drawn on
         * the line between two keys to belong to both, not enough for the key
         * next door to answer for a stop a little off centre.
         */
        private const val DWELL_TIE = 0.25f

        /**
         * Arc over extent a window needs to be a loop: a straight pass is 1,
         * any single corner at most 2 — exactly 2 for a full reversal — and a
         * circle π. Halfway between the corner and the circle.
         */
        private const val LOOP_RATIO = 2.5f

        /**
         * Least roundness — `4πA / P²` of the closed window, 1 for a circle —
         * a loop must show. A loop drawn as an egg or a teardrop still clears
         * this; a corner, a reversal and a zigzag are nowhere near it.
         */
        private const val LOOP_ROUNDNESS = 0.55f

        /** Point spacing of the loop search's resample, in key widths. */
        private const val FINE_SPACING = 0.08f

        /** Longest window the loop search extends, in fine points: five key widths of path. */
        private const val MAX_WINDOW_POINTS = 64

        private const val TWO_PI = 2f * Math.PI.toFloat()

        /** [roundestWindow]'s answer when no window starting at a sample is a loop. */
        private const val NO_WINDOW = -1L

        /** Below this extent a window is tremor, whatever its path: a loop a finger means is wider than a third of a key. */
        private const val MIN_LOOP_EXTENT_SQ = 0.3f * 0.3f

        /**
         * Most stroke a loop may have after it, in key widths, and still be
         * the stroke's closing mark: the lift's own flick, not a move on to
         * another key, which is a key width away.
         */
        private const val END_LOOP_REACH = 0.5f

        /** Runaway backstop; floor pruning ends healthy walks far earlier. */
        const val MAX_POPS = 2000

        /** Rank depth kept internally, so reranking has something to reorder. */
        const val RESULT_K = 8

        private const val EPS = 1e-9

        private fun ln1p(v: Int): Double = ln(1.0 + v)
    }
}
