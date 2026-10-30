package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.engine.NativeEngine

/**
 * JNI surface of prediction's Rust half, in libwm_engine_jni
 * (`native/wm-engine-jni/src/dict.rs` and the modules after it). Call it only
 * once [NativeEngine.loaded] is true; the classes that do ([MappedTrie],
 * [MappedNgramPack]) hold the handles and wrap them in the types the rest of
 * prediction uses.
 *
 * Handles are `long`s, 0 for "not opened". A call with a closed handle
 * throws [IllegalStateException]; a panic in Rust throws [RuntimeException].
 * Strings cross as UTF-16, unpaired surrogates included.
 */
internal object PredictionNative {
    init {
        NativeEngine.requireLoaded()
    }

    // .wmdict: a mapped dictionary.
    external fun dictOpen(path: String): Long
    external fun dictClose(handle: Long)
    external fun dictFrequencyOf(handle: Long, word: String): Int

    /** Completions best first; each one's frequency lands in [frequencies] at its index. */
    external fun dictComplete(handle: Long, prefix: String, limit: Int, frequencies: IntArray): Array<String>

    /** `[Array<String>, IntArray]`: every word and its frequency, depth first. */
    external fun dictEntries(handle: Long): Array<Any>

    /** A handle on a copy of `.wmdict` bytes written in memory ([PackedTrieCodec]), or 0. */
    external fun dictFromBytes(bytes: ByteArray): Long

    // .wmng: a mapped n-gram pack.
    external fun ngramOpen(path: String): Long
    external fun ngramClose(handle: Long)
    external fun ngramBigramCount(handle: Long, first: String, second: String): Int
    external fun ngramTrigramCount(handle: Long, first: String, second: String, third: String): Int
    external fun ngramNextWords(handle: Long, first: String, limit: Int): Array<String>
    external fun ngramNextWordsAfter(handle: Long, first: String, second: String, limit: Int): Array<String>
    external fun ngramWord(handle: Long, id: Int): String?
    external fun ngramIdOf(handle: Long, word: String): Int

    // The fuzzy correction walk (FuzzyBeamSearch) and the models it reads.
    external fun proximityOpen(rows: Array<String>, carried: CharArray): Long
    external fun proximityClose(handle: Long)

    @Suppress("LongParameterList")
    external fun habitsOpen(
        subKeys: IntArray,
        subValues: DoubleArray,
        insertKeys: CharArray,
        insertValues: DoubleArray,
        deleteKeys: CharArray,
        deleteValues: DoubleArray,
    ): Long

    external fun habitsClose(handle: Long)

    /**
     * One walk over the tries behind [dicts]: each a dictionary handle when
     * its [kinds] entry is 0, a user lexicon ([lexNew]) when 1. Tiers are 0
     * (dictionary) or 1 (user); [habits] is 0 for none. The touch frame is absent when
     * [touchKeys] is null: [touchCenters] holds an x, y per key and
     * [touchPoints] an x, y per typed character, NaN where unknown;
     * [slipRadius] is how far a tap reaches for a slip, 0 for none.
     * For result `i`, [doublesOut] holds score, editCost, dictScore and
     * userScore at `4i`, and [intsOut] edits, completedChars, tier, accents
     * and slips at `5i`; they must hold four and five per word of
     * `max(limit * 2, 8)`.
     */
    @Suppress("LongParameterList")
    external fun fuzzySearch(
        dicts: LongArray,
        kinds: IntArray,
        weights: DoubleArray,
        tiers: IntArray,
        typed: String,
        proximity: Long,
        limit: Int,
        maxEdits: Int,
        habits: Long,
        touchKeys: CharArray?,
        touchCenters: FloatArray?,
        sigma: Double,
        slipRadius: Double,
        touchPoints: FloatArray?,
        keySets: Array<String>?,
        keySpellings: Array<Array<String>?>?,
        doublesOut: DoubleArray,
        intsOut: IntArray,
    ): Array<String>

    // The user lexicon (UserLexicon). Words arrive folded (WordKey); the
    // n-gram kinds are 0 bigram, 1 trigram, 2 one-skip, 3 two-skip.
    external fun lexNew(): Long
    external fun lexClose(handle: Long)

    /** Reads a saved file into the store, folding and judging its words itself; false for a file that is not a snapshot. */
    external fun lexLoad(handle: Long, json: String): Boolean
    external fun lexResetForReload(handle: Long)
    external fun lexFinishReload(handle: Long)

    @Suppress("LongParameterList")
    external fun lexLearnWord(handle: Long, key: String, vote: String?, count: Int, lang: String, listedInLowerCase: Boolean): Boolean

    @Suppress("LongParameterList")
    external fun lexAddWord(handle: Long, key: String, caseEvidence: Boolean, surface: String?, boost: Int, pinCase: Boolean)
    external fun lexLanguageOf(handle: Long, key: String): String?
    /** `lang` empty files the word under every language (#930). */
    external fun lexSetLanguage(handle: Long, key: String, lang: String): Boolean
    external fun lexIsLanguagePinned(handle: Long, key: String): Boolean
    external fun lexDisplayOf(handle: Long, key: String): String?
    external fun lexIsAddedByHand(handle: Long, key: String): Boolean

    /** [Long.MIN_VALUE] for a word the store does not have. */
    external fun lexAddedGeneration(handle: Long, key: String): Long
    external fun lexRename(handle: Long, oldKey: String, newKey: String, surface: String, surfaceFolds: Boolean): Boolean
    external fun lexSetCount(handle: Long, key: String, count: Int): Boolean
    external fun lexIsCasePinned(handle: Long, key: String): Boolean
    external fun lexPinCase(handle: Long, key: String, pinned: Boolean): Boolean
    external fun lexLearnNgram(handle: Long, kind: Int, a: String, b: String, c: String)
    external fun lexNgramCount(handle: Long, kind: Int, a: String, b: String, c: String): Int

    /** Followers best first: kind 0 after [a], 1 after [a] [b], 2 one word after [a], 3 two words after. */
    external fun lexFollowers(handle: Long, kind: Int, a: String, b: String, limit: Int): Array<String>

    /** `[Array<String>, IntArray]`. */
    external fun lexFollowerCounts(handle: Long, key: String): Array<Any>
    external fun lexComplete(handle: Long, prefix: String, limit: Int, frequencies: IntArray): Array<String>
    external fun lexContains(handle: Long, key: String): Boolean
    external fun lexIsEstablished(handle: Long, key: String, minCount: Int): Boolean
    external fun lexFrequencyOf(handle: Long, key: String): Int
    external fun lexWordCount(handle: Long): Int

    /** `[Array<String>, IntArray]`, in key order. */
    external fun lexAllWords(handle: Long): Array<Any>
    external fun lexForgetAll(handle: Long, keys: Array<String>)
    external fun lexClear(handle: Long, deleted: Boolean)

    /** The file's text, or null when nothing changed since the last save. */
    external fun lexPrepareSave(handle: Long): String?
    external fun lexMarkUnsaved(handle: Long)
    external fun lexMutationCount(handle: Long): Long
    external fun lexTrieVersion(handle: Long): Long

    /** `[childStart, labels, targets, frequency, maxSubtree, isWord, longArrayOf(version)]`. */
    external fun lexExportTrie(handle: Long): Array<Any>

    /**
     * The context reranker over [candidates] (keys, as the stores file them):
     * their new order as indices, or null when none had any evidence.
     * [seeds] holds the bundled count of `prev candidate`, [recent] whether
     * the conversation holds it; [skip] is the word two back when the
     * previous word is one no store knows.
     */
    @Suppress("LongParameterList")
    external fun rerank(
        lexicon: Long,
        packs: LongArray,
        prev: String,
        prev2: String?,
        prev3: String?,
        skip: String?,
        candidates: Array<String>,
        seeds: IntArray,
        recent: BooleanArray,
        lm: FloatArray?,
        /** The model's rerank weight; 0 or less takes the shipped one. */
        lmWeight: Double,
    ): IntArray?

    /** The shipped weight of the context model's rank-unit rerank term, `rerank.rs`'s `LM_WEIGHT`: off, the score-space blend carries it. */
    const val LM_WEIGHT = 0.0

    // The neural context model (lm.rs), behind [ContextModel]: a mapped
    // `.wmlm` and a session on it that keeps the context last read.
    external fun lmOpen(path: String): Long
    external fun lmClose(handle: Long)

    /** `[name, languages joined by commas, vocabulary size, context length]`. */
    external fun lmInfo(handle: Long): Array<String>
    external fun lmKnows(handle: Long, word: String): Boolean
    external fun lmSessionOpen(model: Long): Long
    external fun lmSessionClose(handle: Long)

    /** The words before the caret, oldest first; register 0 neutral, 1 casual, 2 formal; field 0 other, 1 chat, 2 email, 3 search. */
    external fun lmSetContext(session: Long, register: Int, field: Int, lang: Int, words: Array<String>)

    /** ln P(word next) per word, NaN where the model has no opinion. */
    external fun lmScores(session: Long, words: Array<String>): FloatArray

    /** [lmScores], spelling a word the vocabulary lacks through its pieces. For a few words at a time. */
    external fun lmScoresSpelled(session: Long, words: Array<String>): FloatArray

    /**
     * The learned gate's view of the typed spelling: `[ln P(typed) if listed,
     * rank of typed among next words, entropy of the distribution in nats,
     * ln P(typed) spelled in pieces]`; the first two NaN when the vocabulary
     * does not list the word, the last NaN when it does.
     */
    external fun lmGateView(session: Long, typed: String): FloatArray

    /** `[Array<String> words, FloatArray logprobs]`: the likeliest next words, best first. */
    external fun lmTop(session: Long, k: Int, minLogprob: Float): Array<Any>

    /** `[Array<String> words, FloatArray(1) joint logprob]`: a greedy continuation; the context is put back after. */
    external fun lmGenerate(session: Long, n: Int, minProb: Float): Array<Any>

    /**
     * For each of [candidates] standing where the word before [followers]
     * stands, after [context]: ln P(candidate | context) plus the
     * log-probability of each follower in turn with the candidate in place,
     * NaN where the model cannot read a word. The session's context is put
     * back after.
     */
    external fun lmRevise(session: Long, register: Int, field: Int, lang: Int, context: Array<String>, candidates: Array<String>, followers: Array<String>): FloatArray

    // The stores autocorrect learns from: CorrectionMemory (cm*) and
    // CorrectionStats (cs*). Spellings arrive folded.
    external fun cmNew(): Long
    external fun cmClose(handle: Long)
    external fun cmLoad(handle: Long, json: String)
    external fun cmResetForReload(handle: Long)
    external fun cmFinishReload(handle: Long)
    external fun cmTeach(handle: Long, typed: String, fixed: String, layout: String, habits: Boolean)
    external fun cmUnteach(handle: Long, typed: String, fixed: String)

    /** `[String fixed, IntArray(count)]`, or null. */
    external fun cmFixFor(handle: Long, typed: String): Array<Any>?
    external fun cmForget(handle: Long, typed: String)

    /** `[Array<String> typed, Array<String> fixed, IntArray counts]`, most used first. */
    external fun cmPairs(handle: Long): Array<Any>
    external fun cmPairCount(handle: Long): Int
    external fun cmVersion(handle: Long): Int

    /** `[subKeys, subValues, insertKeys, insertValues, deleteKeys, deleteValues, spaceKeys, spaceValues]`. */
    external fun cmHabits(handle: Long, layout: String): Array<Any>

    /** `[Array<String> keys, IntArray counts]`, one layout's or (null) every layout's summed. */
    external fun cmHabitCounts(handle: Long, layout: String?): Array<Any>
    external fun cmClear(handle: Long, deleted: Boolean)
    external fun cmPrepareSave(handle: Long): String?
    external fun cmMarkUnsaved(handle: Long)

    external fun csNew(): Long
    external fun csClose(handle: Long)
    external fun csLoad(handle: Long, json: String)
    external fun csReload(handle: Long, json: String)
    external fun csSetMemory(handle: Long, ordinal: Int)
    external fun csRecordRevert(handle: Long, key: String, deliberate: Boolean)
    external fun csEndFieldSession(handle: Long)
    external fun csRecordKept(handle: Long, key: String)

    /** The [CorrectionStats.Penalty] ordinal. */
    external fun csPenalty(handle: Long, key: String): Int
    external fun csConfidenceMultiplier(handle: Long): Double
    external fun csClear(handle: Long, deleted: Boolean)
    external fun csPrepareSave(handle: Long): String?
    external fun csMarkUnsaved(handle: Long)

    // The glide decoder (`glide.rs`), behind GlideBeam.

    external fun glideKeyMapOpen(keyX: FloatArray, keyY: FloatArray, codePoints: IntArray, keyOf: IntArray): Long
    external fun glideKeyMapClose(handle: Long)

    /**
     * One decode, or its walk when [phase] is 1: `[words, doubles, ints]`
     * with each word's score and shape cost in `doubles` and its tier
     * (1 for user) and look-ahead in `ints`. Phase 1 returns every word the
     * shape channel will score, readings first, with the reading count in
     * the last int, for [glideFinish] on the same thread. Null when the
     * stroke is no decode at all. [openHead] and [openTail] count the
     * leading and trailing samples that are a walk from or to the spacebar
     * (#968), 0 for a stroke that began and ended on keys.
     */
    @Suppress("LongParameterList")
    external fun glideDecode(
        tuning: DoubleArray,
        xs: FloatArray,
        ys: FloatArray,
        ts: LongArray,
        keyMap: Long,
        keyWidth: Float,
        dicts: LongArray,
        kinds: IntArray,
        weights: DoubleArray,
        tiers: IntArray,
        minFrequencies: IntArray,
        limit: Int,
        lookAhead: Int,
        phase: Int,
        openHead: Int,
        openTail: Int,
    ): Array<Any>?

    /** The last phase-1 [glideDecode] on this thread, finished with [shapes] per word and the seeding's [seeds]. */
    external fun glideFinish(words: Array<String>, shapes: Array<Array<ByteArray>>, seeds: Array<String>?): Array<Any>

    /** `keys + samples + chars + [sampleCount]`, the visits' x then y in [floatsOut]; null when [word] cannot be laid. */
    @Suppress("LongParameterList")
    external fun glideAlign(
        tuning: DoubleArray,
        word: String,
        xs: FloatArray,
        ys: FloatArray,
        ts: LongArray,
        keyMap: Long,
        keyWidth: Float,
        floatsOut: FloatArray,
    ): IntArray?

    external fun glideSampleShape(xs: FloatArray, ys: FloatArray, ts: LongArray, keyWidth: Float): ByteArray?

    /**
     * `CtcGlideBeam.decode`: the result shaped as [glideDecode]'s, phases
     * included; [scoring] is lengthNorm, lengthBonus, freqWeight, pruneNorm,
     * pruneBonus, lookAheadCost, loopWeight, shapeWeight. The stroke ([tuning],
     * the path, [keyMap], [keyWidth]) is where the loops and learned shapes are
     * read; an empty [tuning] or a [keyMap] of 0 decodes the emissions alone.
     */
    @Suppress("LongParameterList")
    external fun ctcDecode(
        logProbs: FloatArray,
        steps: Int,
        dicts: LongArray,
        kinds: IntArray,
        weights: DoubleArray,
        tiers: IntArray,
        minFrequencies: IntArray,
        limit: Int,
        width: Int,
        scoring: DoubleArray,
        tuning: DoubleArray,
        xs: FloatArray,
        ys: FloatArray,
        ts: LongArray,
        keyMap: Long,
        keyWidth: Float,
        lookAhead: Int,
        phase: Int,
    ): Array<Any>

    /** The last phase-1 [ctcDecode] on this thread, finished with [shapes] per word. */
    external fun ctcFinish(words: Array<String>, shapes: Array<Array<ByteArray>>): Array<Any>

    /** A plain `Trie`'s exact copy, for the walks (`sources.rs`); kind 2 in their source lists. */
    external fun trieFromEntries(words: Array<String>, frequencies: IntArray): Long
    external fun trieClose(handle: Long)

    /** `DuetBeam.decode`: tokens as kinds (0 tap, 1 swipe) and point counts over the flat points; the result shaped as [glideDecode]'s. */
    @Suppress("LongParameterList")
    external fun duetDecode(
        tuning: DoubleArray,
        duet: DoubleArray,
        tokenKinds: IntArray,
        tokenSizes: IntArray,
        xs: FloatArray,
        ys: FloatArray,
        ts: LongArray,
        keyMap: Long,
        keyWidth: Float,
        dicts: LongArray,
        kinds: IntArray,
        weights: DoubleArray,
        tiers: IntArray,
        minFrequencies: IntArray,
        limit: Int,
        partial: Boolean,
    ): Array<Any>

    // Words as Unicode sees them (words.rs): the Rust twins of [WordKey.of],
    // [WordKey.surface] and [WordContext.isLearnableWord], and the word
    // segmenter behind [WordSegments].
    external fun wordKey(word: String): String
    external fun wordSurface(word: String): String
    external fun isLearnableWord(word: String): Boolean

    /** The words of [text] as `[start0, end0, start1, end1, ...]`, UTF-16 offsets. */
    external fun segmentWords(text: String): IntArray
}
