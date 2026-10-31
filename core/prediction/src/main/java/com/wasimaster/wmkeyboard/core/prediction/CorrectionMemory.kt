package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.engine.NativeHandles
import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import java.io.File

/**
 * What the user's own fixes have taught autocorrect.
 *
 * [CorrectionStats] remembers the corrections the keyboard made and the user
 * undid. This is its mirror image: the corrections the *user* made — a word
 * typed, left standing, then repaired by hand — which autocorrect either
 * declined or got wrong. Two things are kept from each:
 *
 * * **The pair.** `teh → the`, counted. A pair fixed twice is a fix the user
 *   wants made for them; one fixed once is worth asking about. The engine
 *   consults [fixFor] before it decides anything else about the word.
 * * **The slip.** The pair aligned letter by letter ([EditOps]) and each
 *   difference counted as a habit of this hand on this layout: `3` for `e`,
 *   `b` for space, a doubled letter missed. [habitsFor] turns those counts
 *   into the [EditHabits] the fuzzy walk prices with, so a habit learned on
 *   one word helps on every word it recurs in.
 *
 * Habits are kept per layout because a slip is a property of where the keys
 * sit under the hand, and only taught by [Kind.PAIR_AND_HABITS]: a real word
 * swapped for another real word ("form" for "from") is a choice, not a finger
 * landing badly, and must not move any cost.
 *
 * Everything decays. Pairs untouched for [EXPIRE_GENERATIONS] saves lose a
 * count; a layout's habit counters halve when they pass [HABIT_WINDOW], so a
 * hand that changes is followed. Storage follows the personal-store contract
 * (`learning/learned_corrections.json`): nullable file for a locked device,
 * dirty-flag save on dismissal, [reload] for edits the settings app made.
 *
 * The store, its rules and its file format are Rust (`wm-engine`'s
 * `correction_memory`, which WMK Desktop runs too, through [PredictionNative]'s
 * `cm*` calls); this class folds the spellings ([WordKey]), keeps the file,
 * and turns the counts into [EditHabits] and the viewer's [Habit] list.
 */
class CorrectionMemory(private val storageFile: File?) {

    /** What a fix teaches: the pair alone, or the pair and the slip behind it. */
    enum class Kind { PAIR_ONLY, PAIR_AND_HABITS }

    /**
     * A fix the user has made for one typed spelling, with how often.
     * [byHand] is one set on the viewer: it does not age out.
     */
    class Taught(val fixed: String, val count: Int, val byHand: Boolean = false)

    /** One slip this hand makes, for the viewer. */
    enum class HabitKind { SUBSTITUTION, MISSING, STRAY, SWAP, SPACE_SLIP, MISSED_SPACE }

    class Habit(val kind: HabitKind, val from: Char?, val to: Char?, val count: Int) {
        /** Its key in the store, what [setHabit] takes. */
        val key: String
            get() = when (kind) {
                HabitKind.SUBSTITUTION -> "$KEY_SUB:$from$to"
                HabitKind.MISSING -> "$KEY_INS:$to"
                HabitKind.STRAY -> "$KEY_DEL:$from"
                HabitKind.SWAP -> "$KEY_SWAP:$from$to"
                HabitKind.SPACE_SLIP -> "$KEY_SPACE:$from"
                HabitKind.MISSED_SPACE -> KEY_NOSPACE
            }
    }

    private val native: Long = PredictionNative.cmNew().also {
        NativeHandles.track(this, it, PredictionNative::cmClose)
    }

    private val snapshotFile = storageFile?.let(::SnapshotFile)

    /** Bumped on every change; the engine's caches key on it. */
    val version: Int get() = PredictionNative.cmVersion(native)

    init {
        load()
    }

    /**
     * The user turned [original] into [revised] by hand.
     *
     * Both are folded through [WordKey], so the pair is spelling-only; a
     * revised form may hold one space (a fat-fingered space bar put back).
     * Callers gate with [accepts] first; this only records.
     */
    fun teach(original: String, revised: String, layoutId: String, kind: Kind) {
        PredictionNative.cmTeach(
            native, WordKey.of(original), WordKey.of(revised),
            layoutId.takeIf { it.isNotBlank() }.orEmpty(), kind == Kind.PAIR_AND_HABITS,
        )
    }

    /**
     * The user undid a taught fix: one count off, and the pair is gone once
     * it has none. The penalty in [CorrectionStats] stops it firing at once;
     * this is what keeps the viewer honest about it.
     */
    fun unteach(typed: String, fixed: String) {
        PredictionNative.cmUnteach(native, WordKey.of(typed), WordKey.of(fixed))
    }

    /**
     * The fix the user has taught for [typed], or null. When the same
     * spelling has been fixed different ways, only a fix that clearly
     * dominates the others ([DOMINANCE]) is named.
     */
    fun fixFor(typed: String): Taught? =
        PredictionNative.cmFixFor(native, WordKey.of(typed))?.let { Taught(it[0] as String, (it[1] as IntArray)[0]) }

    /** Forgets everything taught about [typed]. */
    fun forget(typed: String) {
        PredictionNative.cmForget(native, WordKey.of(typed))
    }

    /**
     * Sets [typed] → [fixed] by hand, at exactly [count], from the viewer. The
     * caller checks the spellings with [isPairValid]. A pair set here is kept
     * until the user removes it; typing can still add to it, or undo it.
     */
    fun setPair(typed: String, fixed: String, count: Int) {
        PredictionNative.cmSetPair(native, WordKey.of(typed.trim()), WordKey.of(fixed.trim()), count)
    }

    /** Forgets one fix of [typed], and leaves any other fix of that spelling. */
    fun removePair(typed: String, fixed: String) {
        PredictionNative.cmRemovePair(native, WordKey.of(typed), WordKey.of(fixed))
    }

    /** Sets one slip of [layoutId] to [count]; zero or less forgets it. */
    fun setHabit(layoutId: String, key: String, count: Int) {
        PredictionNative.cmSetHabit(native, layoutId, key, count)
    }

    /** The layouts with slips of their own, for a viewer that lists them apart. */
    fun habitLayouts(): List<String> = PredictionNative.cmHabitLayouts(native).toList()

    /** Every taught pair, most-used first, for the viewer. */
    fun pairs(): List<Pair<String, Taught>> {
        val parts = PredictionNative.cmPairs(native)
        @Suppress("UNCHECKED_CAST")
        val typed = parts[0] as Array<String>
        @Suppress("UNCHECKED_CAST")
        val fixed = parts[1] as Array<String>
        val counts = parts[2] as IntArray
        val byHand = parts[3] as IntArray
        return List(typed.size) { typed[it] to Taught(fixed[it], counts[it], byHand[it] != 0) }
    }

    fun pairCount(): Int = PredictionNative.cmPairCount(native)

    /**
     * The shrink model for [layoutId], or [EditHabits.NONE] until that layout
     * has taught [EditHabits.WARMUP] slips.
     */
    @Synchronized
    fun habitsFor(layoutId: String): EditHabits {
        // The keyboard asks on every settings emission. [version] moves on
        // every change, so the last answer stands until it does, and comes
        // back as the same instance, which the engine's setter skips.
        val version = version
        habitsMemo?.let { if (it.layoutId == layoutId && it.version == version) return it.habits }
        return buildHabits(layoutId).also { habitsMemo = HabitsMemo(layoutId, version, it) }
    }

    private class HabitsMemo(val layoutId: String, val version: Int, val habits: EditHabits)

    private var habitsMemo: HabitsMemo? = null

    private fun buildHabits(layoutId: String): EditHabits {
        val parts = PredictionNative.cmHabits(native, layoutId)
        val subKeys = parts[0] as IntArray
        val subValues = parts[1] as DoubleArray
        val insertKeys = parts[2] as CharArray
        val insertValues = parts[3] as DoubleArray
        val deleteKeys = parts[4] as CharArray
        val deleteValues = parts[5] as DoubleArray
        val spaceKeys = parts[6] as CharArray
        val spaceValues = parts[7] as DoubleArray
        if (subKeys.isEmpty() && insertKeys.isEmpty() && deleteKeys.isEmpty() && spaceKeys.isEmpty()) {
            return EditHabits.NONE
        }
        return EditHabits.of(
            subKeys.indices.associate { subKeys[it] to subValues[it] },
            insertKeys.indices.associate { insertKeys[it] to insertValues[it] },
            deleteKeys.indices.associate { deleteKeys[it] to deleteValues[it] },
            spaceKeys.indices.associate { spaceKeys[it] to spaceValues[it] },
        )
    }

    /** This hand's slips on [layoutId], most frequent first, for the viewer. */
    fun habitSummary(layoutId: String): List<Habit> = summarise(PredictionNative.cmHabitCounts(native, layoutId))

    /** This hand's slips across every layout, summed, for a viewer with no layout in mind. */
    fun habitSummary(): List<Habit> = summarise(PredictionNative.cmHabitCounts(native, null))

    private fun summarise(parts: Array<Any>): List<Habit> {
        @Suppress("UNCHECKED_CAST")
        val keys = parts[0] as Array<String>
        val counts = parts[1] as IntArray
        return keys.indices.mapNotNull { habitOf(keys[it], counts[it]) }
            .sortedWith(
                compareByDescending<Habit> { it.count }
                    .thenBy { it.kind.ordinal }
                    .thenBy { it.from?.code ?: -1 }
                    .thenBy { it.to?.code ?: -1 },
            )
    }

    fun save() {
        val file = snapshotFile ?: return
        val (ticket, json) = synchronized(this) {
            val json = PredictionNative.cmPrepareSave(native) ?: return
            file.ticket() to json
        }
        // Written outside the lock; a failed write makes the store dirty
        // again, so the next save retries.
        if (!file.write(ticket) { json }) PredictionNative.cmMarkUnsaved(native)
    }

    @Synchronized
    fun reload() {
        snapshotFile?.supersede()
        PredictionNative.cmResetForReload(native)
        load()
        PredictionNative.cmFinishReload(native)
    }

    @Synchronized
    fun clear() {
        // The delete is the write; stay dirty only if it failed, so the next
        // save overwrites the stale file with the empty snapshot.
        val failed = snapshotFile?.delete() == false
        PredictionNative.cmClear(native, !failed)
    }

    private fun load() {
        val file = storageFile ?: return
        if (!file.exists()) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        PredictionNative.cmLoad(native, text)
    }

    companion object {
        /**
         * Whether a fix of [original] into [revised] is one worth learning.
         *
         * The original has to be a word-sized single token; the revised form
         * a word the keyboard knows ([known]), or two known halves — a space
         * bar hit on the bottom row put back. The distance between them must
         * be a slip's worth, not a rewrite's, and the revised form must not
         * simply continue the original: "hel" becoming "hello" is a word
         * committed early, which teaches nothing about how it was spelled.
         * Case is folded first, so a capital put back is not a fix either —
         * the case memory owns that.
         */
        fun accepts(original: String, revised: String, known: (String) -> Boolean): Boolean {
            val o = WordKey.of(original.trim())
            val r = WordKey.of(revised.trim())
            if (o.length < MIN_TYPED_LENGTH || o.length > MAX_WORD_LENGTH || r.isEmpty()) return false
            if (o == r || o.any { it.isWhitespace() }) return false
            val parts = r.split(' ')
            if (parts.size > 2 || parts.any { it.length < MIN_HALF_LENGTH || !known(it) }) return false
            if (r.startsWith(o)) return false
            val limit = if (o.length >= LONG_WORD_LENGTH) MAX_EDITS_LONG else MAX_EDITS
            return EditOps.distance(o, r) <= limit
        }

        /**
         * Whether the user may set [typed] → [fixed] by hand. Unlike [accepts]
         * this does not ask whether the fix is a known word or a slip's
         * distance away, since the user is saying so: only that autocorrect
         * would ever read the typed word, and that the fix is one word, or two
         * for a space bar put back.
         */
        fun isPairValid(typed: String, fixed: String): Boolean {
            val t = WordKey.of(typed.trim())
            val f = WordKey.of(fixed.trim())
            if (t.length !in MIN_TYPED_LENGTH..MAX_WORD_LENGTH || t.any { it.isWhitespace() }) return false
            if (f.isEmpty() || f == t) return false
            val parts = f.split(' ')
            return parts.size <= 2 && parts.all { it.isNotEmpty() && it.length <= MAX_WORD_LENGTH }
        }

        private fun habitOf(key: String, count: Int): Habit? {
            if (key == KEY_NOSPACE) return Habit(HabitKind.MISSED_SPACE, null, null, count)
            val colon = key.indexOf(':')
            if (colon < 0) return null
            val arg = key.substring(colon + 1)
            return when (key.substring(0, colon)) {
                KEY_SUB -> arg.takeIf { it.length == 2 }?.let { Habit(HabitKind.SUBSTITUTION, it[0], it[1], count) }
                KEY_INS -> arg.singleOrNull()?.let { Habit(HabitKind.MISSING, null, it, count) }
                KEY_DEL -> arg.singleOrNull()?.let { Habit(HabitKind.STRAY, it, null, count) }
                KEY_SWAP -> arg.takeIf { it.length == 2 }?.let { Habit(HabitKind.SWAP, it[0], it[1], count) }
                KEY_SPACE -> arg.singleOrNull()?.let { Habit(HabitKind.SPACE_SLIP, it, null, count) }
                else -> null
            }
        }

        private const val KEY_SUB = "sub"
        private const val KEY_INS = "ins"
        private const val KEY_DEL = "del"
        private const val KEY_SWAP = "swap"
        private const val KEY_SPACE = "space"
        private const val KEY_NOSPACE = "nospace"

        /** Fixes of the same spelling that make it a fix the user wants made. */
        const val APPLY_AT = 2

        /** ...when the spelling is itself a word ("form"): the bar is higher,
         * and the engine asks the bigram context as well. */
        const val APPLY_KNOWN_AT = 3

        /** A fix has to outnumber the next most common fix of the same
         * spelling by this factor before it is the fix. */
        const val DOMINANCE = 2

        const val MAX_FIXES_PER_TYPED = 3
        const val MAX_PAIRS = 1000
        const val MAX_COUNT = 100
        const val EXPIRE_GENERATIONS = 180L

        /** Habit counters halve past this many sightings on one layout. */
        const val HABIT_WINDOW = 400

        /** Autocorrect never touches shorter words; nor does this. */
        const val MIN_TYPED_LENGTH = 3
        const val MAX_WORD_LENGTH = 32
        const val MIN_HALF_LENGTH = 2
        const val MAX_EDITS = 2
        const val LONG_WORD_LENGTH = 8
        const val MAX_EDITS_LONG = 3
    }
}
