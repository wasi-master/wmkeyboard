package com.wasimaster.wmkeyboard.core.settings.sync

import java.security.MessageDigest
import kotlinx.serialization.json.JsonElement

/**
 * One entry as sync knows it: its value, when it last changed, and who
 * changed it. A deleted entry keeps its stamp and loses its value, so the
 * deletion itself can win over an older edit elsewhere.
 */
data class Stamped(
    val value: JsonElement?,
    /** Milliseconds, or a later tick of the same clock; see [SyncMerge.clock]. */
    val t: Long,
    /** The installation id that made this change. Breaks ties. */
    val by: String,
) {
    val deleted: Boolean get() = value == null
}

/** What this phone last agreed on, per entry: a hash of the value, not the value. */
data class Remembered(val hash: String?, val t: Long, val by: String)

/** Section id → entry key → value. */
typealias SyncTable = Map<String, Map<String, Stamped>>

/**
 * Last change wins, entry by entry, across every device's file.
 *
 * Pure, so the whole behaviour is testable without a phone. The runner reads
 * the files, calls [merge], applies what it returns and writes the result.
 *
 * How a local change is noticed: this phone remembers a hash of every entry
 * as of the last pass ([Remembered]). An entry whose hash moved since was
 * changed here, and gets a fresh stamp; one that disappeared was deleted here,
 * and becomes a tombstone. Nothing has to hook every place a setting is
 * written, which in this app is hundreds of places.
 */
object SyncMerge {

    /** Tombstones older than this are dropped: every device has seen them by then. */
    const val TOMBSTONE_TTL_MS = 90L * 24 * 60 * 60 * 1000

    data class Result(
        /** Every entry, winners only: what this phone's file should say. */
        val merged: SyncTable,
        /** What to write on this phone, per section: key → new value, or null to delete. */
        val changes: Map<String, Map<String, JsonElement?>>,
        /** What to remember for next time. */
        val remembered: Map<String, Map<String, Remembered>>,
    )

    fun hash(value: JsonElement?): String? {
        if (value == null) return null
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toString().toByteArray())
        return digest.take(HASH_BYTES).joinToString("") { "%02x".format(it) }
    }

    private const val HASH_BYTES = 10

    /**
     * A stamp for a change made now, and later than anything already seen.
     * Wall-clock alone is not enough: a phone whose clock runs slow would
     * otherwise lose every edit to a phone that edited earlier.
     */
    fun clock(nowMs: Long, seen: Long): Long = maxOf(nowMs, seen + 1)

    /**
     * @param local each synced section's entries on this phone right now.
     * @param remembered what the last pass agreed on; empty on the first.
     * @param remotes the other devices' files.
     * @param firstSync true on this phone's first pass. Its existing entries
     *   then count as the oldest there are, so joining an existing set of
     *   devices takes their settings rather than overwriting them with this
     *   phone's. Anything only this phone has still spreads to the others.
     *   A section with nothing remembered is treated the same way, however
     *   old the phone's sync is: it was just ticked, and what the other
     *   devices changed while it was off is newer than anything here.
     * @param rejoining whether an entry this phone left out of its last pass
     *   is back in this one: a setting the user stopped keeping on this
     *   device, or a key once keys sync. It joins the way a section does,
     *   taking what the other devices have rather than pushing this phone's
     *   copy over theirs.
     * @param joinAlone joins on a first pass even with nothing from the
     *   others yet. For the copy in a private space or work profile, linked
     *   to the one in the main profile (#1017): its first pass can run before
     *   the main copy's file has come across, and what it has must still lose
     *   to the main copy's then. The main copy founds over such a file, since
     *   a file that only ever joined holds nothing agreed to join.
     * @param owned whether an entry is one only this phone writes, such as
     *   its own typing counts. Another device's copy of it can only be an
     *   old one (a restored backup carries one), so this phone's value wins
     *   and is stamped again to say so. A deletion still wins: that is the
     *   user clearing it from another device.
     */
    fun merge(
        local: Map<String, Map<String, JsonElement>>,
        remembered: Map<String, Map<String, Remembered>>,
        remotes: List<SyncTable>,
        me: String,
        nowMs: Long,
        firstSync: Boolean,
        joinAlone: Boolean = false,
        rejoining: (section: String, key: String) -> Boolean = { _, _ -> false },
        owned: (section: String, key: String) -> Boolean = { _, _ -> false },
    ): Result {
        val seen = maxOf(
            remotes.maxOfOrNull { table -> table.values.maxOfOrNull { s -> s.values.maxOfOrNull { it.t } ?: 0L } ?: 0L } ?: 0L,
            remembered.values.maxOfOrNull { s -> s.values.maxOfOrNull { it.t } ?: 0L } ?: 0L,
        )
        val stamp = clock(nowMs, seen)

        val merged = LinkedHashMap<String, Map<String, Stamped>>()
        val changes = LinkedHashMap<String, Map<String, JsonElement?>>()
        val nextRemembered = LinkedHashMap<String, Map<String, Remembered>>()

        for ((section, current) in local) {
            val before = remembered[section].orEmpty()
            // Joining something the others already hold: this phone's entries
            // are older than anything there, and lose even a tie, which an
            // empty author does against every real installation id. With
            // nothing there yet, this phone is the one the others join, and
            // its entries are stamped for real. Entries another phone only
            // joined with are not something to join: two copies that both
            // waited for the other would never settle on either.
            val othersHaveIt = remotes.any { remote -> remote[section]?.values?.any { it.by != JOINER } == true }
            val joining = (firstSync || section !in remembered) && (othersHaveIt || joinAlone)
            val mine = LinkedHashMap<String, Stamped>()
            for ((key, value) in current) {
                val prior = before[key]
                val h = hash(value)
                mine[key] = when {
                    prior == null && joining -> Stamped(value, 0L, JOINER)
                    prior == null && rejoining(section, key) &&
                        remotes.any { it[section]?.containsKey(key) == true } -> Stamped(value, 0L, JOINER)
                    prior == null -> Stamped(value, stamp, me)
                    prior.hash != h -> Stamped(value, stamp, me)
                    else -> Stamped(value, prior.t, prior.by)
                }
            }
            for ((key, prior) in before) {
                if (key in current) continue
                // Gone here since the last pass: deleted here, unless it was
                // already a tombstone, which keeps its own stamp.
                mine[key] = if (prior.hash == null) Stamped(null, prior.t, prior.by) else Stamped(null, stamp, me)
            }

            val winners = LinkedHashMap<String, Stamped>(mine)
            for (remote in remotes) {
                for ((key, theirs) in remote[section].orEmpty()) {
                    val ours = winners[key]
                    if (ours == null || beats(theirs, ours)) winners[key] = theirs
                }
            }
            for ((key, ours) in mine) {
                if (ours.deleted || !owned(section, key)) continue
                val won = winners.getValue(key)
                if (!won.deleted && hash(won.value) != hash(ours.value)) winners[key] = Stamped(ours.value, stamp, me)
            }
            val kept = winners.filterValues { !it.deleted || nowMs - it.t < TOMBSTONE_TTL_MS }
            merged[section] = kept

            val sectionChanges = LinkedHashMap<String, JsonElement?>()
            for ((key, winner) in kept) {
                val have = current[key]
                if (winner.deleted) {
                    if (have != null) sectionChanges[key] = null
                } else if (have == null || hash(have) != hash(winner.value)) {
                    sectionChanges[key] = winner.value
                }
            }
            if (sectionChanges.isNotEmpty()) changes[section] = sectionChanges
            nextRemembered[section] = kept.mapValues { (_, s) -> Remembered(hash(s.value), s.t, s.by) }
        }
        return Result(merged, changes, nextRemembered)
    }

    /** The author of an entry a joining phone brought: sorts below every installation id. */
    private const val JOINER = ""

    /** Later stamp wins; on a tie the larger installation id, the same answer on every device. */
    private fun beats(a: Stamped, b: Stamped): Boolean =
        a.t > b.t || (a.t == b.t && a.by > b.by)
}
