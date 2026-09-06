package com.wasimaster.wmkeyboard.core.prediction

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * How hard the keyboard holds on to a correction the user undid.
 *
 * The levels differ in one thing only: how much of an undo survives, and for
 * how long. None of them changes what an undo *does* in the moment, which is
 * always to put the typed word back.
 *
 * [OFF] forgets pair by pair the instant the word scrolls away; the global
 * revert rate behind the adaptive gate still counts, because that is a
 * separate setting. [LIGHT] handicaps an undone pair heavily but never retires
 * it. [NORMAL] is the shipped balance: a deliberate undo holds for the run of
 * typing that earned it, two undos retire the pair, and a retired pair is
 * eventually offered once more rather than vanishing. [STRICT] takes the first
 * undo as final, however it was read.
 */
enum class UndoMemory { OFF, LIGHT, NORMAL, STRICT }

/**
 * What autocorrect has learned about its own mistakes: per-pair revert
 * penalties and a global fired/reverted ratio that adapts the confidence gate.
 *
 * Deliberately its own file (`learning/correction_stats.json`), not part of
 * the [UserLexicon] snapshot: a rejection must persist even when "learn from
 * typing" is off or incognito is on — undoing a correction is the user
 * telling the keyboard it was wrong, which is not optional telemetry — and
 * the lexicon file gets rewritten wholesale by the settings app.
 *
 * Storage follows the personal-store contract: nullable file (direct boot →
 * memory only), dirty-flag save on dismissal, [reload] for external edits.
 */
class CorrectionStats(private val storageFile: File?) {

    /**
     * [PROBATION] is a blocked pair let out to be *asked about* once more. It
     * never applies itself; it only reaches the offer chip, so the user finds
     * out the keyboard has a fix rather than meeting a silence that never ends.
     */
    enum class Penalty { NONE, PENALIZED, PROBATION, BLOCKED }

    /**
     * [count] is how many times this exact correction was rejected. [kept] is
     * progress towards forgiving one of those: a pair the user has since let
     * stand [KEEPS_TO_FORGIVE] times loses a rejection, because a single bad
     * fix in a hurry should not sentence the pair for the six months the
     * expiry clock takes.
     */
    @Serializable
    private data class PairStat(val count: Int, val gen: Long, val kept: Int = 0)

    @Serializable
    private data class Snapshot(
        val pairs: Map<String, PairStat> = emptyMap(),
        val fired: Int = 0,
        val reverted: Int = 0,
        val generation: Long = 0L,
    )

    private val pairs = HashMap<String, PairStat>()
    private var fired = 0
    private var reverted = 0
    private var generation = 0L
    private var dirty = false

    /**
     * Pairs reverted in this process, against the [tick] each was reverted at:
     * the very next space must not re-correct, regardless of persisted counts.
     */
    private val sessionRejected = HashMap<String, Int>()

    /**
     * Verdicts delivered since the process started. Monotonic, unlike [fired],
     * which halves; it exists only to age [sessionRejected] out.
     */
    private var tick = 0

    /**
     * How hard undos are remembered, from the user's setting. Mirrored here
     * rather than passed to every call because it is read on the typing path.
     */
    @Volatile
    var memory: UndoMemory = UndoMemory.NORMAL

    private val json = Json { ignoreUnknownKeys = true }

    init {
        load()
    }

    /**
     * One correction reached its verdict.
     *
     * Deliberately not counted when the correction *fires*, which is what this
     * used to do. At that moment the user has not seen it, so a correction they
     * went back and fixed by hand counted as a success and quietly told the
     * adaptive gate that autocorrect was doing better than it was. A verdict
     * arrives once the text has settled around the correction — see
     * [CorrectionWatch] — or immediately when the user backspaces it away.
     */
    private fun bumpFired() {
        fired++
        tick++
        // Halving counters: an exponential moving window with no timestamps.
        if (fired >= WINDOW) {
            fired /= 2
            reverted /= 2
        }
        dirty = true
    }

    /**
     * The user undid the correction of [typed] into [corrected].
     *
     * [deliberate] separates the two ways this is reached, which used to carry
     * identical weight and should not. A backspace inside the revert window is
     * the user saying no to this exact fix with the fix still on the screen. A
     * settle verdict (see [CorrectionWatch]) is a far weaker reading: all it
     * knows is that the typed spelling is standing where the fix was, and that
     * is just as easily the user going back to correct a typo by hand, never
     * having noticed it was already corrected, and reproducing it. Only a
     * deliberate undo earns the in-process block.
     */
    @Synchronized
    fun recordRevert(typed: String, corrected: String, deliberate: Boolean = true) {
        reverted++
        bumpFired()
        if (memory == UndoMemory.OFF) return
        val key = key(typed, corrected)
        if (deliberate || memory == UndoMemory.STRICT) sessionRejected[key] = tick
        val existing = pairs[key]
        // The accept progress goes with it: a pair being rejected again is not
        // most of the way to being forgiven.
        val bumped = (existing?.count ?: 0) + 1
        val count = when (memory) {
            // Light never reaches the persisted block: an undone pair fights
            // with a handicap for good, and that is the whole of the memory.
            UndoMemory.LIGHT -> 1
            // Strict takes the first undo as the last word on the pair.
            UndoMemory.STRICT -> maxOf(bumped, BLOCK_AT)
            UndoMemory.OFF, UndoMemory.NORMAL -> bumped
        }
        pairs[key] = PairStat(count, generation)
        if (pairs.size > MAX_PAIRS) {
            pairs.remove(pairs.entries.minByOrNull { it.value.gen }?.key)
        }
        dirty = true
    }

    /**
     * Whether the in-process block on [key] still stands.
     *
     * The block keeps one promise: the space right after an undo must not put
     * the correction straight back. That promise is about the next few words,
     * not about the days an IME process can stay alive, so it expires after
     * [SESSION_BLOCK_SPAN] further verdicts and hands the pair over to the
     * persisted counts. It used to last for the life of the process, which
     * meant one misread undo silently retired a correction until the keyboard
     * was killed.
     */
    private fun sessionBlocked(key: String): Boolean {
        val stamp = sessionRejected[key] ?: return false
        if (tick - stamp < SESSION_BLOCK_SPAN) return true
        sessionRejected.remove(key)
        return false
    }

    /**
     * The user left the field. In-process blocks are scoped to the run of
     * typing that earned them; anything that should outlive it is in the
     * persisted counts by now.
     */
    @Synchronized
    fun endFieldSession() {
        sessionRejected.clear()
    }

    /**
     * The user let the correction of [typed] into [corrected] stand.
     *
     * Counts towards the global ratio, and towards forgiving this pair if it
     * carries a rejection. Only pairs already on record are touched: a
     * correction nobody ever objected to needs no entry, and giving every
     * accepted correction a slot would evict the rejections this store exists
     * to remember.
     */
    @Synchronized
    fun recordKept(typed: String, corrected: String) {
        bumpFired()
        if (memory == UndoMemory.OFF) return
        val key = key(typed, corrected)
        // A pair under an in-process block stays blocked while that block
        // lasts, whatever the user does: the block is the promise that the
        // very next space will not re-correct.
        if (sessionBlocked(key)) return
        val existing = pairs[key] ?: return
        val kept = existing.kept + 1
        when {
            // Not enough accepts yet to buy back a rejection.
            kept < KEEPS_TO_FORGIVE -> pairs[key] = existing.copy(kept = kept, gen = generation)
            // Its last rejection is forgiven, so the pair is ordinary again.
            existing.count <= 1 -> pairs.remove(key)
            else -> pairs[key] = PairStat(existing.count - 1, generation, kept = 0)
        }
    }

    /** How strongly this exact correction is disfavored. A different
     * correction of the same typed word is untouched — one bad fix must not
     * take every other fix down with it. */
    @Synchronized
    fun penalty(typed: String, corrected: String): Penalty {
        if (memory == UndoMemory.OFF) return Penalty.NONE
        val key = key(typed, corrected)
        if (sessionBlocked(key)) return Penalty.BLOCKED
        val stat = pairs[key] ?: return Penalty.NONE
        return when {
            stat.count < BLOCK_AT -> Penalty.PENALIZED
            // A retired pair that has sat quiet this long is let out to be
            // asked about once. Without it a block is both invisible and
            // permanent: the word stays wrong and nothing ever says why.
            memory != UndoMemory.STRICT &&
                generation - stat.gen > REOFFER_GENERATIONS -> Penalty.PROBATION
            else -> Penalty.BLOCKED
        }
    }

    /**
     * Multiplier on the user's confidence setting, from the recent
     * revert rate: a keyboard being corrected-then-undone often should
     * demand more confidence before forcing anything; a clean record may
     * loosen very slightly. 1.0 until there is a real sample.
     */
    @Synchronized
    fun confidenceMultiplier(): Double {
        if (fired < MIN_SAMPLE) return 1.0
        val revertRate = reverted.toDouble() / fired
        return (1.0 + RATE_GAIN * (revertRate - TARGET_RATE))
            .coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
    }

    @Synchronized
    fun save() {
        val file = storageFile ?: return
        if (!dirty) return
        generation++
        expireStalePairs()
        val snapshot = Snapshot(
            pairs = pairs.toMap(),
            fired = fired,
            reverted = reverted,
            generation = generation,
        )
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(snapshot))
        }.onSuccess { dirty = false }
    }

    @Synchronized
    fun reload() {
        pairs.clear()
        fired = 0
        reverted = 0
        load()
        dirty = false
    }

    @Synchronized
    fun clear() {
        pairs.clear()
        sessionRejected.clear()
        fired = 0
        reverted = 0
        dirty = storageFile?.delete() == false
    }

    /** Penalties age out: one revert from months of sessions ago should not
     * dampen a correction forever. Each pair untouched for
     * [EXPIRE_GENERATIONS] saves loses one count (and is refreshed, so the
     * next decrement is another full period away). */
    private fun expireStalePairs() {
        val stale = pairs.filterValues { generation - it.gen > EXPIRE_GENERATIONS }
        for ((key, stat) in stale) {
            if (stat.count <= 1) {
                pairs.remove(key)
            } else {
                pairs[key] = PairStat(stat.count - 1, generation)
            }
        }
    }

    private fun load() {
        val file = storageFile ?: return
        if (!file.exists()) return
        runCatching {
            val snapshot = json.decodeFromString<Snapshot>(file.readText())
            pairs.putAll(snapshot.pairs)
            fired = snapshot.fired
            reverted = snapshot.reverted
            generation = snapshot.generation
        }
    }

    private companion object {
        /** NUL separator, built rather than written literally. */
        val SEPARATOR: Char = 0.toChar()

        fun key(typed: String, corrected: String): String =
            typed.lowercase() + SEPARATOR + corrected.lowercase()

        /** Second persisted revert of the same pair blocks it outright. */
        const val BLOCK_AT = 2

        /**
         * Accepted uses of a rejected pair that buy back one rejection.
         *
         * Three rather than one, because letting a correction stand is much
         * weaker evidence than reaching for backspace to undo one: people
         * often simply do not notice. It only reaches BLOCKED pairs through
         * [expireStalePairs], since a blocked pair never fires and so can
         * never be accepted.
         */
        const val KEEPS_TO_FORGIVE = 3
        const val MAX_PAIRS = 500
        const val EXPIRE_GENERATIONS = 180L

        /**
         * Verdicts an in-process block survives. Long enough to cover the
         * retype the user is in the middle of and the rest of that sentence,
         * short enough that it is not a life sentence.
         */
        const val SESSION_BLOCK_SPAN = 20

        /**
         * Saves a retired pair sits quiet before it may be offered again.
         * Well inside [EXPIRE_GENERATIONS], so a pair reaches the offer chip
         * long before it would have decayed back on its own.
         */
        const val REOFFER_GENERATIONS = 40L

        /** Halving window for the fired/reverted counters. */
        const val WINDOW = 200
        const val MIN_SAMPLE = 20
        const val TARGET_RATE = 0.03
        const val RATE_GAIN = 20.0
        const val MIN_MULTIPLIER = 0.85
        const val MAX_MULTIPLIER = 2.5
    }
}
