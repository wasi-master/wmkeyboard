package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.engine.NativeHandles
import com.wasimaster.wmkeyboard.core.util.SnapshotFile
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
 *
 * The counts, the forgiveness and expiry rules and the file format are Rust
 * (`wm-engine`'s `correction_stats`, which WMK Desktop runs too, through
 * [PredictionNative]'s `cs*` calls); this class builds the pair keys with
 * Kotlin's lower-casing and keeps the file.
 */
class CorrectionStats(private val storageFile: File?) {

    /**
     * [PROBATION] is a blocked pair let out to be *asked about* once more. It
     * never applies itself; it only reaches the offer chip, so the user finds
     * out the keyboard has a fix rather than meeting a silence that never ends.
     */
    enum class Penalty { NONE, PENALIZED, PROBATION, BLOCKED }

    /** One correction held against autocorrect, for the viewer. */
    class Undone(val typed: String, val fixed: String, val count: Int, val penalty: Penalty, val held: Boolean)

    private val native: Long = PredictionNative.csNew().also {
        NativeHandles.track(this, it, PredictionNative::csClose)
    }

    private val snapshotFile = storageFile?.let(::SnapshotFile)

    /**
     * How hard undos are remembered, from the user's setting. Mirrored into
     * the store rather than passed to every call because it is read on the
     * typing path.
     */
    @Volatile
    var memory: UndoMemory = UndoMemory.NORMAL
        set(value) {
            field = value
            PredictionNative.csSetMemory(native, value.ordinal)
        }

    init {
        load()
    }

    /**
     * The user undid the correction of [typed] into [corrected]. A
     * [deliberate] undo (a backspace inside the revert window) also blocks
     * the pair for the run of typing that earned it; a settle verdict only
     * counts against it.
     */
    fun recordRevert(typed: String, corrected: String, deliberate: Boolean = true) {
        PredictionNative.csRecordRevert(native, key(typed, corrected), deliberate)
    }

    /**
     * The user left the field. In-process blocks are scoped to the run of
     * typing that earned them; anything that should outlive it is in the
     * persisted counts by now.
     */
    fun endFieldSession() {
        PredictionNative.csEndFieldSession(native)
    }

    /**
     * The user let the correction of [typed] into [corrected] stand: counts
     * towards the global ratio, and towards forgiving this pair if it
     * carries a rejection.
     */
    fun recordKept(typed: String, corrected: String) {
        PredictionNative.csRecordKept(native, key(typed, corrected))
    }

    /** How strongly this exact correction is disfavored. A different
     * correction of the same typed word is untouched. */
    fun penalty(typed: String, corrected: String): Penalty =
        Penalty.entries[PredictionNative.csPenalty(native, key(typed, corrected))]

    /**
     * Sets one correction's standing from the viewer: [count] undos against
     * it, [held] to keep it stopped however long it sits quiet. Zero or less
     * forgives it. The revert rate behind [confidenceMultiplier] stays.
     */
    fun setPair(typed: String, corrected: String, count: Int, held: Boolean = false) {
        PredictionNative.csSetPair(native, key(typed, corrected), count, held)
    }

    /** Every correction the user has undone or stopped, for the viewer. */
    fun undone(): List<Undone> {
        val parts = PredictionNative.csPairs(native)
        @Suppress("UNCHECKED_CAST")
        val typed = parts[0] as Array<String>
        @Suppress("UNCHECKED_CAST")
        val fixed = parts[1] as Array<String>
        val counts = parts[2] as IntArray
        val penalties = parts[3] as IntArray
        val held = parts[4] as IntArray
        return List(typed.size) {
            Undone(typed[it], fixed[it], counts[it], Penalty.entries[penalties[it]], held[it] != 0)
        }
    }

    /**
     * Multiplier on the user's confidence setting, from the recent revert
     * rate. 1.0 until there is a real sample.
     */
    fun confidenceMultiplier(): Double = PredictionNative.csConfidenceMultiplier(native)

    fun save() {
        val file = snapshotFile ?: return
        val (ticket, json) = synchronized(this) {
            val json = PredictionNative.csPrepareSave(native) ?: return
            file.ticket() to json
        }
        // Written outside the lock; a failed write makes the store dirty
        // again, so the next save retries.
        if (!file.write(ticket) { json }) PredictionNative.csMarkUnsaved(native)
    }

    @Synchronized
    fun reload() {
        snapshotFile?.supersede()
        PredictionNative.csReload(native, readFile().orEmpty())
    }

    @Synchronized
    fun clear() {
        val failed = snapshotFile?.delete() == false
        PredictionNative.csClear(native, !failed)
    }

    private fun load() {
        readFile()?.let { PredictionNative.csLoad(native, it) }
    }

    private fun readFile(): String? {
        val file = storageFile ?: return null
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull()
    }

    private companion object {
        /** NUL separator, built rather than written literally. */
        val SEPARATOR: Char = 0.toChar()

        fun key(typed: String, corrected: String): String =
            typed.lowercase() + SEPARATOR + corrected.lowercase()
    }
}
