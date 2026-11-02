package com.wasimaster.wmkeyboard.core.gesture.eval

/**
 * Committed glide-decoder quality floors. A change that drops any of these past
 * its tolerance fails the build; a change that raises them should raise the
 * numbers here in the same commit, so the file reads as the decoder's history.
 *
 * Per-noise floors sit alongside the overall ones on purpose. An overall
 * average hides the trade every shape decoder is tempted to make — buying
 * accuracy on tidy swipes by giving it up on messy ones, which is backwards,
 * because a tidy swipe was never the hard case.
 *
 * Per-*language* floors sit alongside both for the same kind of reason. A
 * decoder tuned on English can pay for its gains in a script nobody was
 * measuring, and until 2026-08-07 nobody was measuring one: glide only ran in
 * English, so the harness only knew English.
 *
 * Measured against the corpus in [SwipeCorpus] at seed 42 over the shipped word
 * lists. Both are inputs to the number: changing either invalidates it.
 */
object GestureEvalBaseline {

    /** One language's floors. Every field is asserted. */
    class Floors(
        val top1: Double,
        val top3: Double,
        val mrr: Double,
        val clean: Double,
        val light: Double,
        val typical: Double,
        val sloppy: Double,
    )

    /**
     * English on QWERTY, over the bundled 17k list.
     *
     * 2026-08-07, the `GlideBeam` trie lattice replacing the SHARK²-lite
     * `GestureDecoder`. Against the harness as it stood that day, on an
     * idealised key grid:
     *
     *     overall  .8710 -> .9295      clean    .9640 -> .9720   (+0.8pt)
     *     top3     .9655 -> .9825      light    .9380 -> .9540   (+1.6pt)
     *     mrr      .9196 -> .9565      typical  .8760 -> .9300   (+5.4pt)
     *                                  sloppy   .7060 -> .8620  (+15.6pt)
     *
     * Two things moved those numbers afterwards. The harness stopped carrying a
     * hand-written QWERTY table and started deriving geometry from the shipped
     * [com.wasimaster.wmkeyboard.core.layout.LayoutSpec], staggering the bottom
     * row by a shift key's width rather than by a guess — a different and real
     * keyboard, worth .9295 -> .9265 with the decoder untouched. Then the shape
     * channel and dwell-gated doubling landed: .9265 -> .9460, and clean strokes
     * from .9620 to .9860.
     *
     * 2026-09-08, unclaimed pauses charged against the word (issue #52):
     * .9460 -> .9515, with every language and every noise level up except
     * Avro's sloppy strokes, which gave back .8840 -> .8820. The corpus only
     * pauses on doubled letters and slows into pivots, so this is the charge's
     * cost side measured; the sweep on strokes that pause on single letters is
     * where its gain shows, and it is written up on `GlideBeam.Tuning`.
     *
     * 2026-09-08, a loop on a key read as a doubled letter (issue #52):
     * .9515 -> .9480, all of it on sloppy strokes, .9040 -> .8900. That
     * corpus's tremor curls tightly enough to read as a loop on one stroke
     * in nine, and nothing about a curl's shape or speed tells it from the
     * loop the corpus draws with the same tremor on it. The gain is on the
     * strokes that loop, which this corpus never does: with every doubled
     * letter drawn as a circle, top-1 on the doubled words goes .750 -> .852.
     * The measurement is on `GlideBeam.Tuning.loopExtent`.
     *
     * 2026-09-24, the loop made the only doubling mark (issue #337): a pause
     * and a wiggle now say a letter is in the word and never that it is in
     * it twice, a pause is claimed only by the key it happened on, and the
     * inside of a loop is charged to every key but its own. .9480 -> .9415,
     * clean .9860 -> .9820, and every language gives back about as much.
     * This one is the corpus disagreeing with the user rather than the
     * decoder getting worse: [SwipeCorpus] draws every doubled letter with a
     * hesitation on it, which is exactly the "a pause means twice" reading
     * the issue asked to have removed, and the reporter, drawing real strokes
     * on a device, found the same change a clear gain: the same-row words
     * `write`, `wire` and `wrote` come apart. Of the loss, about half is
     * that reading going, and the rest the loop's inside charge on the
     * tremor curls a sloppy stroke draws; see `GlideBeam.Tuning.loopExclusion`
     * for why that charge is small. The nearest-key pause claim measured as
     * nothing either way on this corpus, which pauses only on doubled letters
     * and pivots. Every floor below was re-measured the same day.
     *
     * 2026-10-22, a doubling no loop marks charged far less (issue #886):
     * `GlideBeam.Tuning.repeatCost` 0.1 -> 0.05 and `unloopedRepeat` 0.4 ->
     * 0.1, the shape channel comparing ideal paths with the stroke's loops
     * cut out, and the stroke's first and last letters free to reach past
     * the gap band. .9415 -> .9485, sloppy .8820 -> .9080, clean .9820 ->
     * .9800, top-3 .9890 -> .9880; every language gains on sloppy strokes and
     * gives back a few tenths on clean and typical ones. The corpus hesitates
     * on every doubled letter, which no longer means anything, so the change
     * is measured where it was meant: real swipe.futo.org strokes, written up
     * on `GlideBeam.Tuning.unloopedRepeat`. Every floor below was
     * re-measured the same day.
     *
     * 2026-11-03, the tuning fitted on real strokes (d0020afce, 2026-10-23),
     * measured here only now: .9485 -> .8845, clean .9800 -> .9480, sloppy
     * .9080 -> .8260. The fit moved weight from the shape to the language model
     * because real thumbs trace the keys far less faithfully than [SwipeCorpus]
     * does, and it raised top-1 on real strokes .7982 -> .8734, which
     * `GlideRealSwipeFloorTest` now holds. So this corpus reads lower on a better
     * decoder. Every language gave up ground here, and Bengali most: .7925 ->
     * .6650. Nobody has real Bengali strokes to say whether that one is the same
     * trade, so it is the number to watch.
     */
    val ENGLISH = Floors(
        top1 = 0.8845,
        top3 = 0.9725,
        mrr = 0.9293,
        clean = 0.9480,
        light = 0.9160,
        typical = 0.8480,
        sloppy = 0.8260,
    )

    /**
     * Bengali on Probhat, over the bundled 20k list — the case the whole
     * multi-character-per-key design exists for, and the one that would catch it
     * breaking.
     *
     * These sit ~19pt below English and are expected to. Probhat asks the
     * decoder a harder question at every step: a key carries a consonant and its
     * aspirated twin, so ক and খ are the *same stroke* and nothing but the
     * language model can separate them, and Bengali's vowel signs and hasanta
     * put more characters into an average word than English needs for the same
     * meaning. The [CYRILLIC] control is what says this gap is Bengali's
     * ambiguity rather than a defect in the decoder — it scores above English on
     * a grid with more keys and no per-language machinery at all.
     *
     * The lever that would move these most is not the decoder. It is frequency:
     * these numbers ride on the bundled 20k list, which carries real counts,
     * while the 451k downloadable Bengali list ships every word at frequency 1.
     * On that list a stroke that fits ক and খ equally has nothing left to
     * consult, and top-1 would fall a long way below what is measured here.
     *
     * 2026-10-13, re-measured after the word list itself changed, not the
     * decoder: a19072cf7 added 120 colloquial chat verb forms (বানাইছি,
     * করতিছে, খুঁজতেছে) at frequencies 269 to 617, so the corpus, which draws
     * by frequency, now draws them often, and every list word is a rival to
     * every stroke. .7955 -> .7845
     * top-1 (words 20645 -> 20765), clean .8220 -> .8040, sloppy .7340 ->
     * .7060, typical up .7760 -> .7900. English measured unchanged to the
     * fourth decimal the same run, which is what says the decoder did not
     * move.
     */
    val BENGALI = Floors(
        top1 = 0.6650,
        top3 = 0.7815,
        mrr = 0.7263,
        clean = 0.7300,
        light = 0.6800,
        typical = 0.6540,
        sloppy = 0.5960,
    )

    /**
     * The no-special-machinery control: a twelve-column non-Latin grid with a
     * synthetic lexicon (see `GestureEvalTest.cyrillicControl`). Nothing about
     * it is Bengali or English specific, so a regression that shows up only here
     * is a regression in the decoder itself rather than in either language's
     * arrangements.
     */
    val CYRILLIC = Floors(
        top1 = 0.9055,
        top3 = 0.9825,
        mrr = 0.9440,
        clean = 0.9620,
        light = 0.9280,
        typical = 0.8960,
        sloppy = 0.8360,
    )

    /**
     * Avro: a QWERTY grid, a romanized stroke, a Bengali answer.
     *
     * Two different things have to go right: the stroke has to decode to the
     * right romanized spelling, and that spelling has to stand for the right
     * Bengali. Only the first is the decoder's problem.
     *
     * It nonetheless scores ~17pt above Bengali on a fixed layout, and the
     * reason is worth keeping in mind before anyone tries to close that gap the
     * other way round: a romanization is written in an alphabet where no two
     * letters share a key, so the shapes are unambiguous in a way Probhat's ক/খ
     * never are. Avro is also the layout most Bengali typists actually use.
     */
    val AVRO = Floors(
        top1 = 0.8835,
        top3 = 0.9690,
        mrr = 0.9268,
        clean = 0.9500,
        light = 0.9320,
        typical = 0.8820,
        sloppy = 0.7700,
    )

    /** Run-to-run drift is nil (the corpus is seeded), so this is small. */
    const val TOLERANCE = 0.01
}
