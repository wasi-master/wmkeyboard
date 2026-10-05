package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What the user did with the words a glide gave them, remembered as a nudge
 * on the next decode (issue #52): a word picked off the strip *over* the one
 * the stroke was read as, and a word backspaced the moment it landed.
 *
 * The decoder cannot tell `not` from `nor` on shape, and a user who writes
 * both has no rank adjustment that helps — lifting one sinks the other. What
 * separates them is what the user did last time the stroke came up, and that
 * is a fact about the *pair*: choosing `nor` over `not` says nothing about
 * `nor` against `now`. So the store keeps pairs, `(rejected, chosen)`, with a
 * strength that grows two a pick and is undone by a pick the other way, and
 * single words with a strength that grows one an immediate undo. Both fade
 * with use rather than time: a row loses a unit every [DECAY_INTERVAL]
 * observations it goes unrefreshed, so a habit the user has stopped
 * correcting stops being corrected for.
 *
 * Applied in nats on the decoder's own scores, before the context rerank and
 * beside the user's rank adjustments, and bounded: a word can be lifted at
 * most [MAX_LIFT_NATS] — one close-call margin, so three consistent picks flip
 * a close call and the ambiguity picker stops asking about it — and sunk at
 * most [MAX_DROP_NATS], so a clear reading is never overturned by a habit.
 *
 * Nothing here is text. Words are stored as the first eight bytes of a
 * salted SHA-256, the salt is rotated when the store is cleared, and a row
 * carries a strength and a logical age — no stroke, no coordinates, no
 * timestamp. The file is a device-local record of the decoder's mistakes,
 * not a vocabulary, which is why it does not travel in a backup.
 *
 * The same shape as [WordRanks]: mutations on the keyboard's thread under
 * the lock, an immutable [View] published whole for the decode thread.
 */
class GlideOutcomes(private val storageFile: File?) {

    internal data class PairKey(val rejected: String, val chosen: String)

    private class Evidence(val strength: Int, val epoch: Long)

    @Serializable
    private data class PairRow(val r: String, val c: String, val s: Int, val e: Long)

    @Serializable
    private data class UndoRow(val w: String, val s: Int, val e: Long)

    @Serializable
    private data class Snapshot(
        val version: Int = VERSION,
        val epoch: Long = 0L,
        val pairs: List<PairRow> = emptyList(),
        val undone: List<UndoRow> = emptyList(),
    )

    /**
     * The store as the decoder reads it: every row's strength as it stands
     * now. Replaced whole on each change.
     */
    class View internal constructor(
        private val pairs: Map<PairKey, Int>,
        private val undone: Map<String, Int>,
    ) {
        val isEmpty: Boolean get() = pairs.isEmpty() && undone.isEmpty()

        /**
         * One nat shift per candidate in [words], index-aligned, or null when
         * no row here concerns any of them — the common case, and the one the
         * decode path should pay nothing for.
         *
         * A pair only speaks when both its words are in the pool: the user
         * chose one *over* the other, and that says nothing about either
         * against a third word.
         */
        fun adjustments(words: List<String>): DoubleArray? {
            if (isEmpty || words.isEmpty()) return null
            val keys = Array(words.size) { keyOf(words[it]) }
            val out = DoubleArray(words.size)
            var any = false
            for (i in words.indices) {
                val keyI = keys[i] ?: continue
                var lift = 0.0
                var drop = (undone[keyI] ?: 0) * UNDONE_NATS
                for (j in words.indices) {
                    if (j == i) continue
                    val keyJ = keys[j] ?: continue
                    pairs[PairKey(keyJ, keyI)]?.let { lift += it * CHOSEN_NATS }
                    pairs[PairKey(keyI, keyJ)]?.let { drop += it * REJECTED_NATS }
                }
                val delta = (lift - drop).coerceIn(-MAX_DROP_NATS, MAX_LIFT_NATS)
                if (delta != 0.0) {
                    out[i] = delta
                    any = true
                }
            }
            return if (any) out else null
        }

        internal companion object {
            val EMPTY = View(emptyMap(), emptyMap())
        }
    }

    private val pairs = LinkedHashMap<PairKey, Evidence>()
    private val undone = LinkedHashMap<String, Evidence>()
    private var epoch = 0L
    private val json = Json { ignoreUnknownKeys = true }
    private var dirty = false
    private val snapshotFile = storageFile?.let(::SnapshotFile)

    /**
     * Whether the decoder should read the store at all — the "learn my swipe
     * style" setting. Off leaves what was learned on disk and applies none of
     * it, so switching back on picks up where it left off.
     */
    @Volatile
    var applied: Boolean = true

    @Volatile
    private var published: View = View.EMPTY

    init {
        load()
    }

    /** Lock-free view for the decode thread; [View.EMPTY] while [applied] is off. */
    fun view(): View = if (applied) published else View.EMPTY

    /**
     * The user took [chosen] off the strip in place of [rejected], the word
     * the stroke was read as. Strengthens the pair, weakens the reverse pair
     * by the same amount — a later pick the other way is counter-evidence,
     * not a second habit — and repays one undo strike against [chosen].
     * False when either is not a word this store keeps, or they are the same.
     */
    @Synchronized
    fun observeAlternative(rejected: String, chosen: String): Boolean {
        val r = keyOf(rejected) ?: return false
        val c = keyOf(chosen) ?: return false
        if (r == c) return false
        epoch++
        val key = PairKey(r, c)
        pairs[key] = Evidence(minOf(effective(pairs[key]) + ALTERNATIVE_STEP, STRENGTH_CAP), epoch)
        weaken(pairs, PairKey(c, r), ALTERNATIVE_STEP)
        weaken(undone, c, 1)
        settle()
        return true
    }

    /**
     * The user backspaced [word] the moment a glide committed it. Weaker than
     * naming an alternative on purpose: an undo says the reading was wrong
     * without saying what was right.
     */
    @Synchronized
    fun observeImmediateUndo(word: String): Boolean {
        val w = keyOf(word) ?: return false
        epoch++
        undone[w] = Evidence(minOf(effective(undone[w]) + UNDO_STEP, STRENGTH_CAP), epoch)
        settle()
        return true
    }

    @Synchronized
    fun isEmpty(): Boolean = pairs.isEmpty() && undone.isEmpty()

    fun save() {
        val file = snapshotFile ?: return
        val (ticket, snapshot) = synchronized(this) {
            if (!dirty) return
            dirty = false
            file.ticket() to Snapshot(
                version = VERSION,
                epoch = epoch,
                pairs = pairs.map { (k, e) -> PairRow(k.rejected, k.chosen, e.strength, e.epoch) },
                undone = undone.map { (w, e) -> UndoRow(w, e.strength, e.epoch) },
            )
        }
        // Encoded and written outside the lock, so the store stays usable while
        // the file goes to disk. A failed write makes the store dirty again, so
        // the next save retries rather than assuming it landed.
        if (!file.write(ticket) { json.encodeToString(snapshot) }) markUnsaved()
    }

    @Synchronized
    private fun markUnsaved() {
        dirty = true
    }

    /** Re-reads the file after the settings app deleted or replaced it. */
    @Synchronized
    fun reload() {
        snapshotFile?.supersede()
        pairs.clear()
        undone.clear()
        epoch = 0L
        load()
        dirty = false
    }

    /**
     * Forgets everything.
     */
    @Synchronized
    fun clear() {
        pairs.clear()
        undone.clear()
        epoch = 0L
        publish()
        // The delete is the write; stay dirty only if it failed, so the next
        // save overwrites the stale file with the empty snapshot.
        dirty = snapshotFile?.delete() == false
    }

    /**
     * The key [word] is filed under, or null when it is not a word this
     * store keeps. Public for tests.
     */
    fun fingerprint(word: String): Long? = keyOf(word)?.hashCode()?.toLong()

    private fun effective(evidence: Evidence?): Int {
        evidence ?: return 0
        val age = (epoch - evidence.epoch).coerceAtLeast(0L)
        val lost = (age / DECAY_INTERVAL).coerceAtMost(STRENGTH_CAP.toLong()).toInt()
        return (evidence.strength - lost).coerceAtLeast(0)
    }

    private fun <K> weaken(rows: MutableMap<K, Evidence>, key: K, by: Int) {
        val evidence = rows[key] ?: return
        val left = effective(evidence) - by
        if (left > 0) rows[key] = Evidence(left, epoch) else rows.remove(key)
    }

    private fun settle() {
        prune()
        trim(pairs, MAX_PAIRS)
        trim(undone, MAX_UNDONE)
        publish()
        dirty = true
    }

    private fun prune() {
        pairs.entries.removeAll { effective(it.value) <= 0 }
        undone.entries.removeAll { effective(it.value) <= 0 }
    }

    private fun <K> trim(rows: LinkedHashMap<K, Evidence>, capacity: Int) {
        while (rows.size > capacity) {
            val weakest = rows.entries.minWithOrNull(
                compareBy<Map.Entry<K, Evidence>>({ effective(it.value) }, { it.value.epoch }),
            ) ?: return
            rows.remove(weakest.key)
        }
    }

    private fun publish() {
        published = if (pairs.isEmpty() && undone.isEmpty()) {
            View.EMPTY
        } else {
            View(
                pairs.entries.associate { it.key to effective(it.value) },
                undone.entries.associate { it.key to effective(it.value) },
            )
        }
    }

    private fun load() {
        val file = storageFile ?: return
        if (!file.exists()) return
        runCatching {
            val snapshot = json.decodeFromString<Snapshot>(file.readText())
            if (snapshot.version != VERSION || snapshot.epoch < 0L) return@runCatching
            epoch = snapshot.epoch
            for (row in snapshot.pairs) {
                if (row.r == row.c || row.e < 0L || row.e > epoch) continue
                val key = PairKey(row.r, row.c)
                val strength = row.s.coerceIn(1, STRENGTH_CAP)
                val current = pairs[key]
                if (current == null || current.strength < strength) pairs[key] = Evidence(strength, row.e)
            }
            for (row in snapshot.undone) {
                if (row.e < 0L || row.e > epoch) continue
                val strength = row.s.coerceIn(1, STRENGTH_CAP)
                val current = undone[row.w]
                if (current == null || current.strength < strength) undone[row.w] = Evidence(strength, row.e)
            }
            prune()
            trim(pairs, MAX_PAIRS)
            trim(undone, MAX_UNDONE)
        }
        publish()
    }

    companion object {
        private const val VERSION = 2

        /** Strength a strip pick adds to `(rejected, chosen)`. */
        const val ALTERNATIVE_STEP = 2

        /** Strength an immediate undo adds to the undone word; weaker on purpose. */
        const val UNDO_STEP = 1

        const val STRENGTH_CAP = 8

        /** Observations a row goes unrefreshed before it loses one unit of strength. */
        const val DECAY_INTERVAL = 64L

        const val MAX_PAIRS = 256
        const val MAX_UNDONE = 128

        /** Nats per unit of strength on the word the user chose, when the word it beat is in the pool. */
        const val CHOSEN_NATS = 0.35

        /** Nats per unit on the word the user passed over, when the word they took is in the pool. */
        const val REJECTED_NATS = 0.04

        /** Nats per unit on a word the user backspaced straight away. */
        const val UNDONE_NATS = 0.10

        /**
         * The most a word is ever lifted.
         */
        const val MAX_LIFT_NATS = 1.2

        /** The most a word is ever sunk. */
        const val MAX_DROP_NATS = 0.4

        private const val MIN_WORD_LENGTH = 2
        private const val MAX_WORD_LENGTH = UserLexicon.MAX_WORD_LENGTH

        private const val ZWNJ = 0x200C
        private const val ZWJ = 0x200D

        private fun keyOf(word: String): String? {
            val folded = WordKey.of(word)
            val out = StringBuilder(folded.length)
            var letters = 0
            var at = 0
            while (at < folded.length) {
                val cp = folded.codePointAt(at)
                at += Character.charCount(cp)
                if (cp == '\''.code || cp == '’'.code) continue
                val type = Character.getType(cp)
                val wordly = Character.isLetter(cp) ||
                    type == Character.NON_SPACING_MARK.toInt() ||
                    type == Character.COMBINING_SPACING_MARK.toInt() ||
                    cp == ZWNJ || cp == ZWJ
                if (!wordly) return null
                out.appendCodePoint(cp)
                letters++
            }
            return if (letters in MIN_WORD_LENGTH..MAX_WORD_LENGTH) out.toString() else null
        }
    }
}
