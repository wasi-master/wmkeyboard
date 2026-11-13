package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.engine.NativeHandles
import java.io.ByteArrayOutputStream
import java.util.WeakHashMap

/**
 * [FuzzyBeamSearch.search] in Rust (`wm-engine`'s `fuzzy::search_units`),
 * when every trie it walks has a Rust twin: a [MappedTrie] is one already,
 * the user lexicon's [LexiconTrie] is a copy of one, a [PackedTrie] gets a
 * copy of its bytes the first time it is walked, a plain [Trie] an exact
 * copy each time it has changed, and an empty trie is skipped, since it can
 * contribute nothing. Any other walker keeps the search in Kotlin.
 *
 * One crossing per search; the proximity and habit models cross once, by
 * handle ([KeyProximity.nativeHandle], [EditHabits.nativeHandle]).
 */
internal object NativeFuzzySearch {

    /** [handleOf]'s answer for a trie with nothing in it. */
    const val SKIP = -1L

    const val KIND_DICTIONARY = 0
    const val KIND_LEXICON = 1
    const val KIND_TRIE = 2

    private val packedTwins = WeakHashMap<PackedTrie, Long>()

    /**
     * Runs the search natively, or returns null when a source has no twin
     * and the caller should walk in Kotlin.
     */
    @Suppress("LongParameterList", "ReturnCount")
    fun search(
        sources: List<FuzzyBeamSearch.WalkSource>,
        typed: String,
        proximity: KeyProximity,
        limit: Int,
        maxEdits: Int,
        touch: FuzzyBeamSearch.TouchScoring?,
        habits: EditHabits,
        keys: KeySets?,
    ): List<FuzzyBeamSearch.ScoredCandidate>? {
        val handles = LongArray(sources.size)
        val kinds = IntArray(sources.size)
        val weights = DoubleArray(sources.size)
        val floors = DoubleArray(sources.size)
        val perNats = DoubleArray(sources.size)
        val tiers = IntArray(sources.size)
        var count = 0
        val scratch = ChildBuffer()
        for (src in sources) {
            val walker = src.walker
            val handle = handleOf(walker, scratch)
            if (handle == SKIP) continue
            if (handle == 0L) return null
            handles[count] = handle
            kinds[count] = kindOf(walker)
            // The walk ranks on the list's ranking scale ([RankScale]).
            weights[count] = src.rankWeight
            floors[count] = src.scale.floor
            perNats[count] = src.scale.perNat
            tiers[count] = if (src.tier == FuzzyBeamSearch.Tier.USER) 1 else 0
            count++
        }
        val k = maxOf(limit * 2, FuzzyBeamSearch.AUTOCORRECT_K)
        val doubles = DoubleArray(k * 4)
        val ints = IntArray(k * 5)
        val model = touch?.model
        val points = touch?.points?.let { taps ->
            FloatArray(taps.size * 2) { i ->
                val tap = taps[i / 2]
                when {
                    tap == null -> Float.NaN
                    i % 2 == 0 -> tap.x
                    else -> tap.y
                }
            }
        }
        val words = PredictionNative.fuzzySearch(
            handles.copyOf(count), kinds.copyOf(count), weights.copyOf(count),
            floors.copyOf(count), perNats.copyOf(count), tiers.copyOf(count),
            typed, proximity.nativeHandle, limit, maxEdits, habits.nativeHandle,
            model?.keyOrder, model?.centerPairs, model?.sigmaValue ?: KeyTouchModel.SIGMA,
            model?.slipRadius ?: 0.0, points,
            keys?.rawSets, keys?.rawSpellings, doubles, ints,
        )
        return List(words.size) { i ->
            FuzzyBeamSearch.ScoredCandidate(
                word = words[i],
                score = doubles[i * 4],
                editCost = doubles[i * 4 + 1],
                edits = ints[i * 5],
                completedChars = ints[i * 5 + 1],
                tier = if (ints[i * 5 + 2] == 1) FuzzyBeamSearch.Tier.USER else FuzzyBeamSearch.Tier.DICTIONARY,
                dictScore = doubles[i * 4 + 2],
                userScore = doubles[i * 4 + 3],
                accents = ints[i * 5 + 3],
                slips = ints[i * 5 + 4],
            )
        }
    }

    /**
     * The Rust trie [walker] reads, 0 when it has none, [SKIP] when it is
     * empty; [kindOf] says which registry the handle is in.
     */
    fun handleOf(walker: TrieWalker, scratch: ChildBuffer): Long = when (walker) {
        is LexiconTrie -> if (walker.isEmpty) SKIP else walker.nativeHandle
        is MappedTrie -> walker.nativeHandle
        is Trie.NodeWalker -> if (walker.trie.version == 0L) SKIP else trieTwin(walker.trie)
        is PackedTrie -> packedTwin(walker)
        else -> if (walker.childrenInto(walker.root, scratch) == 0 && !walker.isWord(walker.root)) SKIP else 0L
    }

    fun kindOf(walker: TrieWalker): Int = when (walker) {
        is LexiconTrie -> KIND_LEXICON
        is Trie.NodeWalker -> KIND_TRIE
        else -> KIND_DICTIONARY
    }

    /** A [Trie]'s Rust copy and the [Trie.version] it was made at; closed once replaced or the trie is gone. */
    private class TrieTwin(val version: Long, val handle: Long)

    private val trieTwins = WeakHashMap<Trie, TrieTwin>()

    /**
     * A Rust copy of [trie], rebuilt whenever the trie has changed since the
     * last one. Only tests walk a plain [Trie], some of them one they keep
     * adding to, so this trades a rebuild per change for having no second
     * walk to keep. The copy is `wm-engine`'s `UserTrie`, the [Trie] port:
     * exact counts (a `.wmdict` rounds them), children in label order, and
     * the same subtree maxima, since a [Trie]'s counts only rise.
     */
    private fun trieTwin(trie: Trie): Long = synchronized(trieTwins) {
        trieTwins[trie]?.takeIf { it.version == trie.version }?.let { return it.handle }
        val entries = ArrayList<Pair<String, Int>>()
        val walker = trie.walkers().single()
        collect(walker, walker.root, StringBuilder(), entries)
        val words = Array(entries.size) { entries[it].first }
        val frequencies = IntArray(entries.size) { entries[it].second }
        val twin = TrieTwin(trie.version, PredictionNative.trieFromEntries(words, frequencies))
        NativeHandles.track(twin, twin.handle, PredictionNative::trieClose)
        trieTwins[trie] = twin
        twin.handle
    }

    private fun collect(walker: TrieWalker, node: Int, prefix: StringBuilder, out: MutableList<Pair<String, Int>>) {
        if (walker.isWord(node)) out.add(prefix.toString() to walker.frequency(node))
        val children = ChildBuffer()
        val count = walker.childrenInto(node, children)
        for (i in 0 until count) {
            prefix.append(children.labels[i])
            collect(walker, children.nodes[i], prefix, out)
            prefix.setLength(prefix.length - 1)
        }
    }

    /** A Rust copy of [trie], written once by the Kotlin codec and released with the trie. */
    private fun packedTwin(trie: PackedTrie): Long = synchronized(packedTwins) {
        packedTwins.getOrPut(trie) {
            val bytes = ByteArrayOutputStream().also { PackedTrieCodec.write(trie, it) }.toByteArray()
            PredictionNative.dictFromBytes(bytes).also {
                NativeHandles.track(trie, it, PredictionNative::dictClose)
            }
        }
    }
}
