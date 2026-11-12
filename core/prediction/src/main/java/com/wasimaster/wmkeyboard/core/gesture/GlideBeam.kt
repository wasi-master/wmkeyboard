package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch

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
 * a minimum over predecessors, so no extension of a prefix can finish below the
 * lowest of its column plus the cheapest any key sequence could explain the rest
 * of the stroke from there ([buildRemain]). Expanding in order of
 * `logWeight + ln(1 + maxSubtree(node)) - shapeWeight * thatFloor` therefore
 * never discards a word that would have finished in the top K — the same
 * argument `FuzzyBeamSearch` makes for typing, with the alignment floor standing
 * in for the edit cost.
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
 *
 * The decoder runs in Rust (`wm-engine`'s `glide`, through [NativeGlide]):
 * this class holds its weights and its result types and hands each call
 * across. The Kotlin decoder it replaced lives on in the test sources
 * (`reference/KotlinGlideBeam`) as the reference `NativeGlideParityTest`
 * holds the Rust one to, bit for bit.
 */
class GlideBeam(private val tuning: Tuning = Tuning()) {

    private val nativeTuning: DoubleArray by lazy { NativeGlide.tuningArray(tuning) }

    /**
     * The decoder's weights, injectable so the harness can sweep them rather
     * than the author guessing.
     *
     * The defaults are fitted on real strokes (2026-10-22): coordinate descent
     * (`native/bench/futo-swipe/fit_glide.py` over `wm-engine`'s
     * `glide_beam_eval`) on 35,928 strokes from 8,701 sessions of FUTO's
     * swipe.futo.org train split, then checked on the 38,103 test-split
     * strokes the fit never saw, over the bundled en.txt:
     *
     *     top-1  .7982 -> .8734      short doubled words  .712 -> .721
     *     top-4  .9358 -> .9598      six letters or more  .862 -> .860
     *
     * and over AOSP's 160k en_US list .8165 -> .8683. Strokes redrawn with a
     * loop on the doubled key go .815 -> .834, with a stray loop .798 ->
     * .863. Most of it is the language model counting for more: the shape
     * weights were set on synthetic strokes (`SwipeCorpus`), which trace the
     * keys far more faithfully than a thumb does, and on real ones the shape
     * term was outvoting word frequency. `GlideRealSwipeFloorTest` holds a
     * 2,000-stroke slice of the test split to a floor.
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
        val maxPointCost: Float = 18.0f,
        /**
         * Nats per key width of disagreement between how far the finger
         * travelled between two letters and how far apart their keys are. This
         * is the term that reads `hello` off a `hello` stroke rather than `ho`.
         */
        val gapWeight: Float = 1.33f,
        /** How far, in samples, a placement may sit from where the arc length
         * says it should before the search stops considering it. */
        val gapWindow: Float = 32f,
        /** Nats per unit of alignment cost — the dial that trades shape against
         * the language model. 2.5 until the real-swipe fit (see [Tuning]). */
        val shapeWeight: Double = 0.5,
        /**
         * Nats per nat of `ln(1 + frequency)`: how much a word's frequency
         * counts against the stroke's shape. 1.0 is the unweighted language
         * model the decoder was fitted with, and every bound in the walk
         * carries the weight too, so pruning stays sound at any value.
         *
         * No setting reaches it, and no single value is right: it is a
         * property of the language and its word list (issue #966). German's
         * useful words are rare in every list (its compounds sit at a
         * median rank of 20,714 in a 150k list, against 369 for the English
         * words people swipe), so at 1.0 frequency outvotes shape there.
         * Swept on 2,588 real German swipes (FUTO, Wikipedia text), 10,908
         * more (HeliBoard, rare words) and 2,129 English, success per
         * swipe over a 150k list:
         *
         *     1.0    71.41   21.95   81.49       0.5    73.69   30.46   80.41
         *     0.8    72.84   25.60   80.98       0.4    73.38   31.94   79.66
         *     0.6    73.49   28.80   80.55       0.25   71.48   32.90     -
         *
         * Monotonically worse for English, better for German: the signature
         * of a language's setting. [GlideLanguageProfiles] holds the value
         * per language and the engine applies it on top of the user's
         * tuning; the default here stays the English fit.
         */
        val freqWeight: Double = 1.0,
        /**
         * Flat charge for a doubled letter, which the stroke's shape cannot show.
         *
         * 0.1 until 2026-10-22, lowered with [unloopedRepeat] (#886); the
         * measurement is written up there. 0.8 since the real-swipe fit the
         * same day, against a [shapeWeight] cut to a fifth: an unlooped
         * doubling costs 0.45 nats where it cost 0.375.
         */
        val repeatCost: Float = 0.8f,
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
         *
         * Kept small (#886). The language model's term is `ln(1 + f)` over a
         * list whose frequencies are already logarithms, so the most common
         * doubled words stand only a little above the rare single spellings
         * the same stroke draws: at 0.4, with [repeatCost] 0.1 and
         * [shapeWeight] 2.5, the charge was worth 1.25 there and `all` lost to
         * `al`, `been` to `ben` and `ill` to whatever passed near it. On 8,000
         * real swipe.futo.org strokes over the bundled list, short doubled
         * words read right .20 -> .64 (0.05 and 0.1), strokes looped on the
         * doubled key .78 -> .81 and the whole set .7741 -> .7768; over the
         * AOSP list .36 -> .49 on the short doubles and .6931 -> .6923 on the
         * whole set. A stray loop on a word with no doubled letter costs .4 to
         * .55 points, which is the price of a loop being cheaper to claim.
         */
        val unloopedRepeat: Float = 0.1f,
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
        val dwellFull: Float = 2.0f,
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
         *
         * 2.0 again since #1250. The real-swipe fit had taken it to 0.9
         * while cutting [shapeWeight] to a fifth, and the charge is in
         * alignment units, so a full pause went from 5 nats to 0.45: under
         * the half nat that separates `for` from `four`, the same stroke but
         * for the u. The fit could not have known. Nobody in swipe.futo.org's
         * strokes pauses to mark a letter, so every pause it saw was a
         * hesitation, and a weight for a deliberate mark cannot be fitted on
         * strokes that carry none. On 2,000 strokes of the test split
         * (`glide_beam_eval`) and on strokes drawn with a 150 ms hold on
         * the letter that tells two words apart (`glide_cases`: `four`,
         * `its`, `three`, `odd`):
         *
         *     0      .8740    hold reads right  -          4.0    .8590   4 of 4
         *     0.9    .8740    1 of 4                       10.0   .8125   4 of 4
         *     2.0    .8725    4 of 4
         *
         * The cost above 2.0 is all on the least accurate strokes, whose
         * hesitations are a finger looking for the next key: the most
         * accurate quarter reads .932 at 0.9 and .928 at 10. So how much a
         * pause counts is the hand's to choose, and a setting reaches it
         * (`GestureSettings.pauseStrength`).
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
         *
         * 4.0 since #1250, for the reason [unclaimedDwell] moved: at 1.1
         * against the fitted [shapeWeight] a circle was worth half a nat, and
         * `too` led `to` by 0.21 on a stroke looped on the o, `off` led `of`
         * by 0.24, `three` led `the` by 0.29, leads the context model's say
         * (up to 3 nats) overturned at will. At 4.0 the same strokes lead by
         * 1.7, 1.7 and 1.0 (`glide_cases`), and real strokes, which carry
         * stray curls and no deliberate loops, read the same: .8740 at 1.1,
         * .8745 at 4.0 and at 8.0 on 2,000 of the test split. A setting
         * reaches it (`GestureSettings.loopStrength`).
         */
        val unclaimedLoop: Float = 4.0f,
        /**
         * The most a shape learned from the user's own kept glides may shorten
         * a word's shape distance, in the shape channel's units. A word scores
         * against the nearer of its ideal path and the shapes this user has
         * drawn it as, but never more than this nearer than the ideal: a word
         * they have swiped before gets the benefit of how they draw it, not a
         * free pass over words they have not. Zero switches learned shapes
         * off. Measured 2026-10-13 (`GlideShapeLearningTest`): .10 to .50
         * all read the same, so the cap does not bind on the corpus and stays
         * at the conservative end.
         */
        val learnedShapeGain: Double = 0.15,
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
        val shapeChannel: Double = 6.0,
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
         *
         * Above the shipped value the radius widens the score as well as the
         * gate (issue #805). As a gate alone it admitted words that could not
         * win: the first letter still paid its location cost on the first
         * sample, capped only from 2.06 key widths out, and the lead-in from
         * the touch-down to the key was travel the gap term charged, so a word
         * starting 2.5 keys off trailed one starting under the finger by about
         * 40 nats against a dictionary's whole frequency spread of about 14.
         * The excess over [SLACK_BASE] is now [startSlack]: that much of the
         * distance to the first key is free on the first sample, that much of
         * the lead-in is free as gap, the shape channel sees the lead-in on
         * the word's own path, and the key is walkable even if the stroke
         * never passes within [nearRadius] of it. At or under the base all
         * four are zero.
         */
        val startRadius: Float = 1.6f,
        /**
         * How far the stroke's *last* sample may sit from the word's last key,
         * in key widths. See [startRadius].
         *
         * 2.0 since the real-swipe fit (see [Tuning]), so 0.4 key widths of
         * [endSlack] ship switched on: real lifts stop short of the last key
         * often enough that forgiving it was worth 1.7 points of top-1 on the
         * fit's own strokes. The start stayed at 1.6.
         */
        val endRadius: Float = 2.0f,
        /** How close the stroke must pass to a key for that key's subtree to be
         * worth walking at all, in key widths. 1.5 until the real-swipe fit. */
        val nearRadius: Float = 1.9f,
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
         *
         * 0.28 since #1250; 1.4 until then. The charge is in nats, outside
         * [shapeWeight], and it competes with how much worse the stroke's
         * whole readings fit than the prefix does, which is inside it. The
         * real-swipe fit cut [shapeWeight] to a fifth and left this alone,
         * so a guess paid five times what it used to against the readings
         * it has to beat, and "Finish long words early" all but stopped
         * offering. Real strokes of six letters or more cut at a share of
         * their length, the tier's margin applied as the keyboard applies
         * it (`glide_ahead_eval`, 611 strokes; the word shown, another
         * guess shown, and a guess typed over a stroke drawn to its end):
         *
         *                        60 % drawn    80 % drawn    drawn whole
         *     before the fit     .133  .432    .267  .291    .031
         *     1.4,  margin 1     .034  .147    .213  .116    .002
         *     0.28, margin 1     .103  .309    .273  .208    .011
         *     0.28, margin 2     .072  .213    .236  .157    .005
         *
         * A fifth of the old charge, the ratio the shape weight moved by,
         * brings the right word back as often as before the fit with fewer
         * wrong ones beside it. The margins are `GlideLookAhead`'s.
         */
        val lookAheadCost: Double = 0.28,
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
        /**
         * Nats charged for each join of a compound read as two or three
         * listed words in a row (issue #966 §5.2), and the switch: infinite,
         * the default, reads no compounds and the decode is the plain one
         * to the bit.
         *
         * A list cannot hold the compounds a language builds as it goes.
         * On real German swipes 36 % of the words of 15 letters or more
         * were missing from a 150k list, and adding generated compounds to
         * the list was a net loss (under 2 % of them were words anyone
         * writes); an oracle that held just the missing ones read 4.9 more
         * swipes in a hundred. So the decoder reads them where the stroke
         * is decoded instead: at a listed word of three letters or more
         * with stroke still ahead of it, the walk may go on from the top of
         * the trie as a second part, on the same alignment, as if the list
         * held the joined spelling. The joined word scores on the rarest of
         * its parts, this much the poorer per join, so a listed compound
         * beats its own joining and a common short word plus a stray one
         * does not beat a word the list holds whole. The bound carries
         * both, so the walk's pruning stays exact.
         *
         * No setting reaches it: [GlideLanguageProfiles] sets it per
         * language, German first; `glide_beam_eval` takes `join_cost=` for
         * the sweep.
         */
        val joinCost: Double = Double.POSITIVE_INFINITY,
        /**
         * Letters every part of a joined compound must have, the first and
         * the last alike ([joinCost]). Three by default; German's four
         * keeps `hast` + `alt` from reading as a word.
         */
        val joinMinPart: Int = 3,
        /**
         * Nats each part a join goes on from pays per `ln(1 + frequency)`
         * of its own (issue #966): the commoner the part, the dearer the
         * join, so two common short words do not outbid a listed word.
         * The last part, which no join follows, pays nothing. With the
         * bare charge, German lost 3.5 swipes in a hundred to such splits;
         * at `8 / 4 / 0.1` the joining gains half a point over none.
         */
        val joinPartPenalty: Double = 0.0,
        /**
         * Runaway pop backstop for one decode, not a language weight: see
         * [GlideBeam.MAX_POPS]. A mid-swipe preview frame decodes under a
         * smaller budget (2,000 pops / 8,192 states) than the final lift
         * (4,000 / 12,288), so the same weights can be reused for both.
         */
        val maxPops: Int = MAX_POPS,
        /**
         * Frontier-state cap for one decode, not a language weight: see
         * [GlideWorkspace.MAX_STATES]. Previews use 8,192; the lift 12,288.
         */
        val maxStates: Int = GlideWorkspace.MAX_STATES,
    ) {
        val invTwoSigmaSq: Float get() = 1f / (2f * sigma * sigma)
        val startRadiusSq: Float get() = startRadius * startRadius
        val endRadiusSq: Float get() = endRadius * endRadius
        val nearCost: Float get() = nearRadius * nearRadius * invTwoSigmaSq
        val loopRadiusSq: Float get() = loopRadius * loopRadius

        /**
         * How much of [startRadius] lies past [SLACK_BASE], in key widths:
         * the distance from the touch-down to a word's first key that the
         * score forgives. See [anchorSlack].
         */
        val startSlack: Float get() = (startRadius - SLACK_BASE).coerceAtLeast(0f)

        /** [startSlack] for [endRadius] and the lift-off. */
        val endSlack: Float get() = (endRadius - SLACK_BASE).coerceAtLeast(0f)

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
             * The anchor radius past which the score forgives the distance
             * as well as the gate admitting it (issue #805). Fixed rather
             * than the default, which it was until the default end radius
             * moved past it: measured from the default, a radius shipped at
             * 2.0 would have forgiven nothing.
             */
            const val SLACK_BASE = 1.6f

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
     * [sampleCount], and [chars] the offset in the word at
     * which each visit's characters begin — together, what maps a place along
     * the stroke back to the letters written there (see `GlideCase.Letters`).
     */
    class Alignment(
        val keys: IntArray,
        val x: FloatArray,
        val y: FloatArray,
        val samples: IntArray = IntArray(keys.size),
        val chars: IntArray = IntArray(keys.size),
        /** Samples the stroke was resampled to; see [GlideWorkspace.samples]. */
        val sampleCount: Int = GlideWorkspace.SHORT_SAMPLES,
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
     * [openHead] and [openTail] are how many of the path's first and last
     * samples are a walk from or to the spacebar (#968, a chained stroke):
     * the first letter may stand anywhere on the head, the last anywhere on
     * the tail, and the samples outside the word cost nothing. 0 and 0 for
     * a stroke that began and ended on keys.
     */
    @Suppress("LongParameterList", "ReturnCount", "UNUSED_PARAMETER")
    fun decode(
        path: List<GesturePoint>,
        keys: GlideKeyMap,
        keyWidth: Float,
        sources: List<FuzzyBeamSearch.WalkSource>,
        ws: GlideWorkspace,
        limit: Int = 4,
        shapes: GlideShapeSource? = null,
        lookAhead: Int = 0,
        openHead: Int = 0,
        openTail: Int = 0,
    ): List<Candidate> {
        if (path.size < MIN_SAMPLES || keyWidth <= 0f) return emptyList()
        if (keys.keyCount == 0 || sources.isEmpty() || limit <= 0) return emptyList()
        return NativeGlide.decode(
            tuning, path, keys, keyWidth, sources, limit, shapes, lookAhead, openHead, openTail,
        ) ?: error(NO_TWIN)
    }

    /**
     * [path] as the shape store keeps a stroke: resampled, normalised the way
     * the shape channel normalises the drawn stroke — centred, scaled to unit
     * spread — and quantised to a byte a coordinate. Null when the stroke is
     * too short to be a glide.
     */
    @Suppress("UNUSED_PARAMETER")
    fun sampleShape(path: List<GesturePoint>, keyWidth: Float, ws: GlideWorkspace): ByteArray? {
        if (path.size < MIN_SAMPLES || keyWidth <= 0f) return null
        return NativeGlide.sampleShape(path, keyWidth)
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
    @Suppress("UNUSED_PARAMETER")
    fun align(
        word: String,
        path: List<GesturePoint>,
        keys: GlideKeyMap,
        keyWidth: Float,
        ws: GlideWorkspace,
    ): Alignment? {
        if (path.size < MIN_SAMPLES || keyWidth <= 0f || keys.keyCount == 0) return null
        return NativeGlide.align(nativeTuning, word, path, keys, keyWidth)
    }

    companion object {

        private const val MIN_SAMPLES = 3

        /**
         * Runaway backstop; floor pruning ends healthy walks far earlier.
         *
         * It was 2000. On 2,588 German swipes with the full list and the
         * 8,192-state frontier, 4,000 pops read 75.81 top-1 against 75.46;
         * with the 12,288-state frontier it read 76.31. The extra pops only
         * run where the walk would otherwise have stopped.
         *
         * This is the final lift's budget; a mid-swipe preview frame runs
         * 2,000 pops with the 8,192-state frontier (see
         * `SuggestionEngine.GLIDE_PREVIEW_POPS`), since a preview is redrawn
         * every few frames and the lift alone has to be exact.
         */
        const val MAX_POPS = 4000

        /** Rank depth kept internally, so reranking has something to reorder. */
        const val RESULT_K = 8

        private const val NO_TWIN =
            "a glide source with no Rust twin: every TrieWalker the keyboard builds has one"
    }
}
