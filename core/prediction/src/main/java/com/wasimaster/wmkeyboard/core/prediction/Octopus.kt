package com.wasimaster.wmkeyboard.core.prediction

/**
 * Where a floating word came from, which is the only thing that separates the
 * three otherwise identical cases: a word that continues what is typed, a word
 * that fixes it, and a word for a buffer that is still empty.
 *
 * Read by the overlay to pick the two-tone colours, and by the settings so a
 * user who wants completions but not guesses can say so.
 */
enum class OctopusKind { COMPLETION, CORRECTION, NEXT_WORD }

/**
 * One prediction, floating over one key: discussion #102's octopus, the
 * BlackBerry Z10's "In-Letter" prediction.
 *
 * The promise the whole feature makes is that [keyCodePoint] is *the key you
 * would press next* to reach [word] — so the word above `l` while "hel" is
 * typed is "hello", and flicking up on `l` is the same gesture as pressing it,
 * only finished. [typedChars] is how much of [word] the buffer already stands
 * for, which is both the two-tone split point and the proof of that promise:
 * `word[typedChars]` is the character [keyCodePoint] produces, except for a Han
 * word on a conversion layout, whose key continues its reading instead
 * ([assignConversionOctopus]).
 *
 * Keyed by code point rather than Char so a letter outside the BMP is one key
 * and not two surrogate halves — the bug [SuggestionEngine.nextLetterWeights]
 * has, and the reason this does not reuse it.
 */
data class OctopusWord(
    /** Anchor code point of the key to press next. */
    val keyCodePoint: Int,
    /** The whole word, cased as it will commit. */
    val word: String,
    /** How many leading characters of [word] the buffer already stands for. */
    val typedChars: Int,
    val kind: OctopusKind,
    /** 0 is the board's best word; drives density trimming and emphasis. */
    val rank: Int,
    /**
     * Which step of its key's stack this word is on: 0 sits nearest the key,
     * 1 is drawn one band further from it, and so on (#136). Always 0 while a
     * key may carry one word, which is the default.
     */
    val tier: Int = 0,
)

/**
 * A candidate before it has a key: what the engine produced, plus enough to
 * rank it against the others.
 */
data class OctopusCandidate(
    val word: String,
    val score: Double,
    val kind: OctopusKind,
    /** Edit distance from the typed buffer; 0 for a pure completion. */
    val edits: Int = 0,
)

/**
 * The first index at which [candidate] stops agreeing with [typed] — which is
 * the position of the key that continues it, and the count of characters the
 * user has already contributed.
 *
 * One rule covers every shape the strip can hand us:
 *
 * | typed    | candidate | divergence | key |
 * |----------|-----------|------------|-----|
 * | `hel`    | `hello`   | 3          | `l` |
 * | `helli`  | `hello`   | 4          | `o` |
 * | `helo`   | `hello`   | 3          | `l` |
 * | `helllo` | `hello`   | 4          | `o` |
 * | (empty)  | `hello`   | 0          | `h` |
 *
 * On an ambiguous board ([keys] non-null) the buffer holds the *anchor* letter
 * each key commits, not what the user spelled, so plain character equality
 * would report divergence at 0 for nearly every word and hang every candidate
 * off its own first letter. [KeySets.accepts] is what turns the literal `hel`
 * of a `ghi`/`def`/`jkl` board back into agreement.
 */
fun octopusDivergence(typed: String, candidate: String, keys: KeySets? = null): Int {
    val shared = minOf(typed.length, candidate.length)
    var i = 0
    while (i < shared) {
        val ch = candidate[i]
        val agrees = typed[i].equals(ch, ignoreCase = true) ||
            keys?.accepts(i, ch.lowercaseChar()) == true
        if (!agrees) break
        i++
    }
    return i
}

/**
 * Hangs each candidate off the key that continues it, keeps the best ones, and
 * hands back at most [limit] words with at most [perKey] per key.
 *
 * [perKey] above one lets a key carry a stack (#136): the best claim takes
 * tier 0, the next tier 1, and so on, in rank order. A word still goes to the
 * key that continues it and nowhere else — the stack only says how many claims
 * a key honours before the rest are dropped.
 *
 * [keyOf] maps a code point to the anchor code point of the key that produces
 * it, or -1 when this board cannot produce it in one press — a long-press-only
 * glyph, a character off the current layer, or the emoji that next-word
 * prediction can return. Those are dropped silently rather than relocated: a
 * word floating above a key that would not type it is a lie about the
 * affordance, and the whole feature rests on that not being one.
 *
 * [scoreSpread] is how far below the board's best a candidate may score and
 * still float, in the engine's log-space units. Without it the board always
 * paints exactly [limit] words — including two rubbish ones — and never goes
 * quiet, which is the difference between a Z10 and a slot machine.
 */
fun assignOctopus(
    typed: String,
    candidates: List<OctopusCandidate>,
    keys: KeySets?,
    keyOf: (Int) -> Int,
    limit: Int,
    scoreSpread: Double,
    perKey: Int = 1,
): List<OctopusWord> {
    if (limit <= 0 || candidates.isEmpty()) return emptyList()
    val stack = perKey.coerceAtLeast(1)
    // Deterministic: score, then word. Mirrors the ordering `suggest` itself
    // uses, so the same buffer always paints the same board and a tie is never
    // decided by hash iteration order.
    val ranked = candidates.sortedWith(
        compareByDescending<OctopusCandidate> { it.score }.thenBy { it.word }
    )
    val floor = ranked.first().score - scoreSpread
    // How many claims each key has honoured so far; the count is the next
    // word's tier.
    val claimedKeys = HashMap<Int, Int>()
    val claimedWords = HashSet<String>()
    val out = ArrayList<OctopusWord>(minOf(limit, ranked.size))
    for (candidate in ranked) {
        if (out.size >= limit || candidate.score < floor) break
        val at = octopusDivergence(typed, candidate.word, keys)
        // A word the buffer already spells out, or a prefix of it, has no next
        // key: -1 stands for "nowhere to float this", the same as a character
        // this board cannot type in one press.
        val key = if (at >= candidate.word.length) {
            -1
        } else {
            keyOf(candidate.word.codePointAt(at))
        }
        // At most [perKey] words per key and one key per word, best claim
        // first. A candidate whose key is full is dropped, never moved to its
        // second choice: moving it would break the one promise the feature
        // makes. Both claims are staked together so a word rejected as a
        // duplicate does not hold its key hostage against the candidate
        // behind it.
        val tier = claimedKeys[key] ?: 0
        val fresh = key >= 0 &&
            candidate.word.lowercase() !in claimedWords &&
            tier < stack
        if (fresh) {
            claimedKeys[key] = tier + 1
            claimedWords.add(candidate.word.lowercase())
            out.add(
                OctopusWord(
                    keyCodePoint = key,
                    word = candidate.word,
                    typedChars = at,
                    kind = candidate.kind,
                    rank = out.size,
                    tier = tier,
                )
            )
        }
    }
    return out
}

/**
 * The board for a conversion layout: each of [candidates], best first, over the
 * key [nextLetter] says continues its reading, then [others] (the strip's English
 * words, already assigned) on the keys with room (#1028). A Han word has none of
 * its characters typed, so its [OctopusWord.typedChars] is 0 whatever the reading
 * has reached. The same rules as [assignOctopus]: a word whose key is full is
 * dropped rather than moved, and [limit] and [perKey] hold across both lists.
 */
fun assignConversionOctopus(
    candidates: List<String>,
    nextLetter: (Int) -> Char?,
    others: List<OctopusWord>,
    keyOf: (Int) -> Int,
    limit: Int,
    perKey: Int,
    kind: OctopusKind = OctopusKind.COMPLETION,
): List<OctopusWord> {
    if (limit <= 0) return emptyList()
    val stack = perKey.coerceAtLeast(1)
    val claimed = HashMap<Int, Int>()
    val out = ArrayList<OctopusWord>(limit)
    fun claim(key: Int, word: String, typedChars: Int, kind: OctopusKind) {
        val tier = claimed[key] ?: 0
        if (key < 0 || tier >= stack || out.size >= limit) return
        claimed[key] = tier + 1
        out.add(OctopusWord(key, word, typedChars, kind, rank = out.size, tier = tier))
    }
    candidates.forEachIndexed { i, word ->
        nextLetter(i)?.let { claim(keyOf(it.code), word, 0, kind) }
    }
    others.forEach { claim(it.keyCodePoint, it.word, it.typedChars, it.kind) }
    return out
}

/**
 * The best word under each key that could extend [prefix], keyed by the code
 * point of that key — the dense-mode fan-out, which fills the keys the ranked
 * candidates left empty.
 *
 * A public seam over [TrieCompleter], which is internal: the prediction tests
 * live in `:app` and cannot see this module's internals.
 */
fun octopusTrieFan(walker: TrieWalker, prefix: String): Map<Int, Suggestion> =
    TrieCompleter.bestPerNextCodePoint(walker, prefix)
