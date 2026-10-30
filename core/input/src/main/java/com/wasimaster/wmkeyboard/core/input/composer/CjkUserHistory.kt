package com.wasimaster.wmkeyboard.core.input.composer

import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the user actually picked, per reading, so the same reading offers it
 * first next time.
 *
 * This is the one thing every shipping CJK IME does that this one did not: a
 * conversion commit taught the keyboard nothing, so the eightieth-ranked
 * character you use daily stayed eightieth forever.
 *
 * **Deliberately not `UserLexicon`.** That store drops any key shorter than two
 * characters, which is the single most common CJK commit; it is keyed by surface
 * word where conversion ranking is keyed by reading; and its contents are fed to
 * the Latin suggestion ranker and the gesture decoder, where Hanzi is pollution.
 * Its file is also rewritten whole, and mixing CJK churn into it would multiply
 * that cost for a user who types both.
 *
 * ### Namespaces are reading spaces, not languages
 *
 * `zh` alone has six ways of spelling a reading — pinyin letters, T9 digits,
 * bopomofo, Cangjie letters, Quick letters and stroke digits. A pinyin `ni` and a
 * Cangjie `ni` have nothing to do with each other, so a per-language split would
 * have them overwrite one another. Keying on the reading space instead gives the
 * zh/ja/yue separation for free and the intra-Chinese one as well.
 */
class CjkUserHistory(private val storageFile: File?) {

    @Serializable
    private data class Snapshot(
        val version: Int = 1,
        /** namespace → reading → word → times chosen. */
        val picks: Map<String, Map<String, Map<String, Int>>> = emptyMap(),
        /** namespace → words never to offer, from the candidate menu (#580). */
        val blocked: Map<String, List<String>> = emptyMap(),
        /** namespace → word → steps moved down (negative) by the candidate menu (#580). */
        val bias: Map<String, Map<String, Int>> = emptyMap(),
        /** namespace → previous word → word → times written straight after it. */
        val pairs: Map<String, Map<String, Map<String, Int>>> = emptyMap(),
        /** Picks and pairs learned so far, for [AGE_EVERY]. */
        val tick: Long = 0,
    )

    private val picks = HashMap<String, HashMap<String, HashMap<String, Int>>>()
    private val blocked = HashMap<String, MutableSet<String>>()
    private val bias = HashMap<String, HashMap<String, Int>>()
    private val pairs = HashMap<String, HashMap<String, HashMap<String, Int>>>()

    /**
     * Learning events so far. Every [AGE_EVERY] of them, whatever was not
     * used in the meantime is halved ([age]), so a word picked a hundred
     * times last year stops outranking the one picked twice this week.
     */
    private var tick = 0L

    /** namespace → readings picked or paired since the last [age]: spared by it. */
    private val touched = HashMap<String, HashSet<String>>()

    /** [pairs] as [followersOf] reads them, rebuilt on change and read without the lock. */
    @Volatile
    private var pairSnapshot: Map<String, Map<String, Map<String, Int>>> = emptyMap()

    /**
     * [blocked] and [bias] per namespace, rebuilt on every change so a ranking
     * reads them without the lock.
     */
    @Volatile
    private var adjustments: Map<String, WordAdjust> = emptyMap()

    /** Changes since this store was made, so [userData] knows when it is stale. */
    private var revision = 0L

    /** namespace → the [revision] its decoder snapshot was made at, and the snapshot. */
    private val snapshots = HashMap<String, Pair<Long, Lattice.UserData?>>()

    /** Whether anything has changed since the last [save]. */
    @Volatile
    var dirty: Boolean = false
        private set
    private val snapshotFile = storageFile?.let(::SnapshotFile)

    init {
        load()
    }

    /** Records that [word] was chosen for [reading]. */
    @Synchronized
    fun learn(namespace: String, reading: String, word: String) {
        if (reading.isEmpty() || word.isEmpty()) return
        val byReading = picks.getOrPut(namespace) { HashMap() }
        val counts = byReading.getOrPut(reading) { HashMap() }
        counts[word] = (counts[word] ?: 0) + 1
        touched.getOrPut(namespace) { HashSet() }.add(reading)
        ticked()
        revision++
        dirty = true
        CjkDictionaries.invalidate()
    }

    /**
     * Records that [word] was written straight after [previous] — a pick
     * following the last one, or a word taken from the strip after a commit.
     */
    @Synchronized
    fun learnPair(namespace: String, previous: String, word: String) {
        if (previous.isEmpty() || word.isEmpty()) return
        val byPrevious = pairs.getOrPut(namespace) { HashMap() }
        val counts = byPrevious.getOrPut(previous) { HashMap() }
        counts[word] = (counts[word] ?: 0) + 1
        touched.getOrPut(namespace) { HashSet() }.add(PAIR_MARK + previous)
        ticked()
        rebuildPairs()
        revision++
        dirty = true
        CjkDictionaries.invalidate()
    }

    /** What the user has written after [previous], most often first. */
    fun followersOf(namespace: String, previous: String): List<Pair<String, Int>> =
        pairSnapshot[namespace]?.get(previous)?.entries?.sortedByDescending { it.value }?.map { it.key to it.value }.orEmpty()

    /** Previous word → word → times, for the decoder; empty when nothing was learned. */
    fun pairsFor(namespace: String): Map<String, Map<String, Int>> = pairSnapshot[namespace].orEmpty()

    /**
     * [namespace]'s history as the decoder reads it, or null when there is
     * none. Made once per change: a decode runs in Rust and cannot ask this
     * store about each of the thousands of words it scores.
     */
    @Synchronized
    fun userData(namespace: String): Lattice.UserData? {
        snapshots[namespace]?.let { (made, data) -> if (made == revision) return data }
        val data = Lattice.UserData.of(picks[namespace], adjustments[namespace], pairSnapshot[namespace])
        snapshots[namespace] = revision to data
        return data
    }

    private fun ticked() {
        tick++
        if (tick % AGE_EVERY == 0L) age()
    }

    /**
     * Halves every count nothing has touched since the last time, and drops
     * the ones that reach zero. A pick made once and never again is gone
     * after one quiet period; one made every day never fades.
     */
    private fun age() {
        for ((namespace, byReading) in picks) {
            val spared = touched[namespace].orEmpty()
            halve(byReading) { it in spared }
        }
        for ((namespace, byPrevious) in pairs) {
            val spared = touched[namespace].orEmpty()
            halve(byPrevious) { PAIR_MARK + it in spared }
        }
        touched.clear()
        rebuildPairs()
    }

    private fun halve(table: HashMap<String, HashMap<String, Int>>, spared: (String) -> Boolean) {
        val emptied = ArrayList<String>()
        for ((key, counts) in table) {
            if (spared(key)) continue
            val it = counts.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                val half = e.value / 2
                if (half <= 0) it.remove() else e.setValue(half)
            }
            if (counts.isEmpty()) emptied.add(key)
        }
        emptied.forEach(table::remove)
    }

    private fun rebuildPairs() {
        pairSnapshot = pairs.mapValues { (_, byPrevious) -> byPrevious.mapValues { (_, words) -> HashMap(words) } }
    }

    /** How many times [word] has been chosen for [reading]. */
    @Synchronized
    fun countFor(namespace: String, reading: String, word: String): Int =
        picks[namespace]?.get(reading)?.get(word) ?: 0

    /** What the candidate menu has said about words in [namespace], or null when nothing. */
    fun adjustFor(namespace: String): WordAdjust? = adjustments[namespace]

    /**
     * Never offer [word] in [namespace] again. Its picks go with it, or the
     * history would keep a count for a word it no longer shows.
     */
    @Synchronized
    fun block(namespace: String, vararg word: String) {
        val words = word.filter { it.isNotEmpty() }.toSet()
        if (words.isEmpty()) return
        blocked.getOrPut(namespace) { HashSet() }.addAll(words)
        forgetPicks(namespace, words)
        changed()
    }

    /**
     * One step lower for [word] in [namespace]: below the words it ties with,
     * and a cost on every path built through it. Its picks go too, since a
     * pick is what put it first. Bounded at [MAX_STEPS].
     */
    @Synchronized
    fun demote(namespace: String, vararg word: String) {
        val words = word.filter { it.isNotEmpty() }.toSet()
        if (words.isEmpty()) return
        val steps = bias.getOrPut(namespace) { HashMap() }
        for (w in words) steps[w] = ((steps[w] ?: 0) - 1).coerceAtLeast(-MAX_STEPS)
        forgetPicks(namespace, words)
        changed()
    }

    /**
     * One step higher for [word] read [reading]: whatever demotion it had is
     * taken back, and it counts as picked once more, which is what puts a
     * candidate first for its reading.
     */
    @Synchronized
    fun promote(namespace: String, reading: String, vararg word: String) {
        val words = word.filter { it.isNotEmpty() }.toSet()
        if (words.isEmpty() || reading.isEmpty()) return
        bias[namespace]?.let { steps -> words.forEach { steps.remove(it) } }
        val counts = picks.getOrPut(namespace) { HashMap() }.getOrPut(reading) { HashMap() }
        val shown = word.first { it.isNotEmpty() }
        counts[shown] = (counts[shown] ?: 0) + 1
        changed()
    }

    private fun forgetPicks(namespace: String, words: Set<String>) {
        picks[namespace]?.let { byReading ->
            val empty = ArrayList<String>()
            for ((reading, counts) in byReading) {
                counts.keys.removeAll(words)
                if (counts.isEmpty()) empty.add(reading)
            }
            empty.forEach(byReading::remove)
        }
        // A word taken back is taken back after everything too, the way
        // libime's "forget" drops every sentence that contains it.
        pairs[namespace]?.let { byPrevious ->
            words.forEach(byPrevious::remove)
            val empty = ArrayList<String>()
            for ((previous, counts) in byPrevious) {
                counts.keys.removeAll(words)
                if (counts.isEmpty()) empty.add(previous)
            }
            empty.forEach(byPrevious::remove)
        }
        rebuildPairs()
    }

    private fun changed() {
        revision++
        dirty = true
        rebuildAdjustments()
        CjkDictionaries.invalidate()
    }

    private fun rebuildAdjustments() {
        val out = HashMap<String, WordAdjust>()
        for (ns in blocked.keys + bias.keys) {
            val a = WordAdjust(blocked[ns].orEmpty().toSet(), bias[ns].orEmpty().filterValues { it != 0 })
            if (!a.isEmpty) out[ns] = a
        }
        adjustments = out
    }

    /** True when nothing has ever been learned for [namespace]. */
    @Synchronized
    fun isEmptyFor(namespace: String): Boolean = picks[namespace].isNullOrEmpty()

    @Synchronized
    fun clear() {
        if (picks.isEmpty() && blocked.isEmpty() && bias.isEmpty() && pairs.isEmpty()) return
        picks.clear()
        blocked.clear()
        bias.clear()
        pairs.clear()
        touched.clear()
        rebuildPairs()
        changed()
    }

    @Synchronized
    fun clear(namespace: String) {
        val had = picks.remove(namespace) != null
        val hadBlocked = blocked.remove(namespace) != null
        val hadBias = bias.remove(namespace) != null
        val hadPairs = pairs.remove(namespace) != null
        if (!had && !hadBlocked && !hadBias && !hadPairs) return
        rebuildPairs()
        changed()
    }

    fun save() {
        val file = snapshotFile ?: return
        val (ticket, snapshot) = synchronized(this) {
            if (!dirty) return
            dirty = false
            file.ticket() to Snapshot(
                picks = picks.mapValues { (_, readings) ->
                    readings.mapValues { (_, words) -> words.toMap() }
                },
                blocked = blocked.mapValues { (_, words) -> words.sorted() },
                bias = bias.mapValues { (_, steps) -> steps.toMap() },
                pairs = pairs.mapValues { (_, byPrevious) ->
                    byPrevious.mapValues { (_, words) -> words.toMap() }
                },
                tick = tick,
            )
        }
        // Encoded and written outside the lock, so the store stays usable while
        // the file goes to disk. A failed write makes the store dirty again, so
        // the next save retries rather than assuming it landed.
        if (!file.write(ticket) { JSON.encodeToString(snapshot) }) markUnsaved()
    }

    @Synchronized
    private fun markUnsaved() {
        dirty = true
    }

    @Synchronized
    private fun load() {
        val file = storageFile ?: return
        if (!file.exists()) return
        runCatching {
            val snapshot = JSON.decodeFromString<Snapshot>(file.readText())
            for ((namespace, byReading) in snapshot.picks) {
                val target = picks.getOrPut(namespace) { HashMap() }
                for ((reading, counts) in byReading) target[reading] = HashMap(counts)
            }
            for ((namespace, words) in snapshot.blocked) blocked[namespace] = words.toHashSet()
            for ((namespace, steps) in snapshot.bias) bias[namespace] = HashMap(steps)
            for ((namespace, byPrevious) in snapshot.pairs) {
                val target = pairs.getOrPut(namespace) { HashMap() }
                for ((previous, counts) in byPrevious) target[previous] = HashMap(counts)
            }
            tick = snapshot.tick
            rebuildAdjustments()
                rebuildPairs()
        }
        dirty = false
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** How far down the candidate menu can push a word, in steps. */
        private const val MAX_STEPS = 5

        /**
         * Learning events between two [age] passes. A Chinese user commits a
         * few hundred times a day, so this is about a week or two of typing.
         */
        const val AGE_EVERY = 3000L

        /** Marks a pair's previous word in [touched], apart from a reading. */
        private const val PAIR_MARK = "\u0001"
    }
}

/**
 * The process-wide learned-pick store, mirroring [CjkDictionaries]: the composers
 * stay parameter-less singletons and the service swaps the real store in once it
 * has a files directory. Null until then, and null is a working state — nothing
 * is learned and nothing is re-ranked.
 */
object CjkLearning {

    @Volatile
    var store: CjkUserHistory? = null

    /**
     * Whether picks may be recorded at all. The service mirrors the same gate the
     * Latin lexicon uses (learn-from-typing off, incognito, or a field that
     * forbids typing intelligence), so both stores go quiet together.
     */
    @Volatile
    var enabled: Boolean = true

    fun learn(namespace: String, reading: String, word: String) {
        if (!enabled) return
        store?.learn(namespace, reading, word)
    }

    /** Records [word] written straight after [previous]; gated like [learn]. */
    fun learnPair(namespace: String, previous: String, word: String) {
        if (!enabled) return
        store?.learnPair(namespace, previous, word)
    }

    /** What the decoder reads from the store for [namespace]; null when nothing. */
    fun userData(namespace: String): Lattice.UserData? = store?.userData(namespace)

    /** What the candidate menu has said about [namespace]'s words; null when nothing. */
    fun adjustFor(namespace: String): WordAdjust? = store?.adjustFor(namespace)

    /**
     * The held candidate's menu (#580). [shown] is the candidate as the strip
     * drew it and [source] what the decoder found (they differ under
     * Traditional output); both are recorded, so the block holds whichever
     * the next ranking compares against. Not gated on [enabled]: this is the
     * user telling the keyboard something, not the keyboard watching them.
     */
    fun adjust(namespace: String, reading: String, shown: String, source: String, adjustment: CandidateAdjustment) {
        val history = store ?: return
        when (adjustment) {
            CandidateAdjustment.PROMOTE -> history.promote(namespace, reading, shown, source)
            CandidateAdjustment.DEMOTE -> history.demote(namespace, shown, source)
            CandidateAdjustment.BLOCK -> history.block(namespace, shown, source)
            // Not an answer about the word: the service commits one of its
            // characters instead, and nothing is learned from that.
            CandidateAdjustment.FIRST_CHARACTER, CandidateAdjustment.LAST_CHARACTER -> Unit
        }
    }

    /**
     * [items] with previously-chosen candidates first, most-chosen first among
     * them, and everything else left in exactly the order it arrived.
     *
     * A *stable partition* rather than a score adjustment on purpose. The pack's
     * frequency column has no documented scale, so any arithmetic blending it
     * with a pick count is a guess that behaves differently per pack; sorting
     * keeps the decoder's ranking intact underneath and makes the promise the
     * user actually expects — the character you chose for this reading is the one
     * offered next time — exact rather than probable. Clearing the history
     * restores the original order precisely.
     */
    fun <T> rank(
        namespace: String,
        items: List<T>,
        text: (T) -> String,
        reading: (T) -> String,
    ): List<T> = rank(namespace, items, text, reading, text, null)

    /**
     * [rank], with the candidate menu's answers applied (#580): a blocked
     * candidate is dropped, and a demoted one goes after everything it would
     * otherwise tie with. [source] is the decoder's own text for an item.
     *
     * With [group], the reordering stays inside each group, and the groups keep
     * the order they arrived in. Pinyin groups by how much of the buffer a
     * candidate covers, so a character picked for `wo` once rises among the
     * single characters for `wbxh` instead of above every reading of all four
     * letters — which is what put 我 ahead of 我不喜欢 in the issue.
     */
    fun <T> rank(
        namespace: String,
        items: List<T>,
        text: (T) -> String,
        reading: (T) -> String,
        source: (T) -> String,
        group: ((T) -> Int)?,
    ): List<T> {
        val history = store ?: return items
        if (items.isEmpty()) return items
        val adjust = history.adjustFor(namespace)
        val kept = if (adjust == null || adjust.blocked.isEmpty()) {
            items
        } else {
            items.filterNot { text(it) in adjust.blocked || source(it) in adjust.blocked }
        }
        if (kept.size < 2) return kept
        val learned = !history.isEmptyFor(namespace)
        val bias = adjust?.bias.orEmpty()
        if (!learned && bias.isEmpty()) return kept
        var moved = false
        val counts = IntArray(kept.size)
        val lowered = BooleanArray(kept.size)
        for ((i, item) in kept.withIndex()) {
            if (learned) counts[i] = history.countFor(namespace, reading(item), text(item))
            lowered[i] = (bias[source(item)] ?: bias[text(item)] ?: 0) < 0
            if (counts[i] > 0 || lowered[i]) moved = true
        }
        if (!moved) return kept
        val groupOrder = HashMap<Int, Int>()
        val groupOf = IntArray(kept.size) { i ->
            val key = group?.invoke(kept[i]) ?: 0
            groupOrder.getOrPut(key) { groupOrder.size }
        }
        // Stable, so everything nothing was said about keeps the decoder's order.
        return kept.indices.sortedWith(
            compareBy<Int>({ groupOf[it] }, { -counts[it] }, { if (lowered[it]) 1 else 0 }),
        ).map { kept[it] }
    }
}

/** What the held candidate's menu asks for (#580). */
enum class CandidateAdjustment {
    /** Show it earlier: counted as picked, any demotion taken back. */
    PROMOTE,

    /** Show it later: one step down, and its picks forgotten. */
    DEMOTE,

    /** Never show it again in this reading space. */
    BLOCK,

    /**
     * Type only its first character (以词定字): 选 out of 选择 when the
     * character alone is hard to find under its own reading. The whole word's
     * reading is consumed, as fcitx's `[` does.
     */
    FIRST_CHARACTER,

    /** Type only its last character: 择 out of 选择, fcitx's `]`. */
    LAST_CHARACTER,
}
