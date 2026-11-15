package com.wasimaster.wmkeyboard.core.emoji

import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Tracks recently and frequently used emojis, user favourites, and the
 * preferred skin-tone/gender variant per emoji — persisted as JSON on
 * device.
 *
 * Favourites are pinned: [recents] and [frequents] always list them first
 * (in the order they were favourited), whether or not they were ever used.
 */
class EmojiUsage(private val storageFile: File?) {

    @Serializable
    private data class Snapshot(
        val recents: List<String> = emptyList(),
        val counts: Map<String, Int> = emptyMap(),
        val favourites: List<String> = emptyList(),
        /** Palette-grid base emoji → the variant the user last picked for it. */
        val variantPrefs: Map<String, String> = emptyMap(),
    )

    private val recents = ArrayDeque<String>()
    private val counts = HashMap<String, Int>()
    private val favourites = ArrayList<String>()
    private val variantPrefs = HashMap<String, String>()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * How many recents to keep. Settable because 32 is one panel row on some
     * phones and four on a tablet, so the same number is a short list for one
     * user and a wall for another. Trims immediately when lowered, so the
     * change is visible on the next panel open rather than after 32 more
     * emoji.
     */
    @get:Synchronized
    var maxRecents: Int = MAX_RECENTS
        set(value) {
            val clamped = value.coerceIn(MIN_RECENTS, MAX_RECENTS_CEILING)
            if (clamped == field) return
            field = clamped
            synchronized(this) {
                while (recents.size > clamped) {
                    recents.removeLast()
                    dirty = true
                }
            }
        }

    companion object {
        const val MAX_RECENTS = 32
        const val MIN_RECENTS = 8
        const val MAX_RECENTS_CEILING = 96
        private const val MAX_FAVOURITES = 64

        /** What a favourite counts as in [rankedByUse]: a handful of uses. */
        private const val FAVOURITE_USES = 5
    }

    /**
     * Whether anything has been used, pinned or reordered since the file last
     * matched memory. [save] runs on the main thread on every keyboard
     * dismissal; without this it re-encoded and rewrote the whole history even
     * when no emoji had been touched, which is nearly every dismissal. See
     * [com.wasimaster.wmkeyboard.core.prediction.UserLexicon] for the same flag
     * and the reason the write itself stays synchronous.
     */
    private var dirty = false
    private val snapshotFile = storageFile?.let(::SnapshotFile)

    init {
        load()
    }

    /**
     * Re-reads the file, dropping whatever is held in memory. For when the
     * settings app has rewritten it — a restored backup, say — and this copy
     * would otherwise save over it at the end of the next field.
     */
    @Synchronized
    fun reload() {
        snapshotFile?.supersede()
        recents.clear()
        counts.clear()
        favourites.clear()
        variantPrefs.clear()
        load()
        // Memory matches the file again, and saving would undo the rewrite
        // this reload exists to pick up.
        dirty = false
    }

    private fun load() {
        storageFile?.takeIf { it.exists() }?.let { file ->
            runCatching {
                val snapshot = json.decodeFromString<Snapshot>(file.readText())
                recents.addAll(snapshot.recents.take(maxRecents))
                counts.putAll(snapshot.counts)
                favourites.addAll(snapshot.favourites.distinct().take(MAX_FAVOURITES))
                variantPrefs.putAll(snapshot.variantPrefs)
            }
        }
    }

    @Synchronized
    fun record(emoji: String) {
        recents.remove(emoji)
        recents.addFirst(emoji)
        while (recents.size > maxRecents) recents.removeLast()
        counts.merge(emoji, 1, Int::plus)
        dirty = true
    }

    /** Favourites first, then the most recently used non-favourites. */
    @Synchronized
    fun recents(limit: Int = maxRecents): List<String> =
        pinned(recents.asSequence(), limit)

    /** Favourites first, then the most frequently used non-favourites. */
    @Synchronized
    fun frequents(limit: Int = maxRecents): List<String> =
        pinned(
            counts.entries.asSequence()
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { it.key },
            limit,
        )

    /**
     * [candidates] with the ones you use most brought forward, the rest in
     * the order given: the strip's emoji for a word ranked by your own picks
     * rather than by the keyword list alone. A favourite counts as used often;
     * a skin-tone variant counts for its base when it is the one you prefer.
     * Stable, so two you never used keep the keyword list's order.
     */
    @Synchronized
    fun rankedByUse(candidates: List<String>): List<String> {
        if (candidates.size < 2 || (counts.isEmpty() && favourites.isEmpty())) return candidates
        fun uses(emoji: String): Int {
            val own = counts[emoji] ?: 0
            val variant = variantPrefs[emoji]?.let { counts[it] } ?: 0
            val favourite = if (emoji in favourites) FAVOURITE_USES else 0
            return maxOf(own + variant, favourite)
        }
        return candidates.withIndex()
            .sortedWith(compareByDescending<IndexedValue<String>> { uses(it.value) }.thenBy { it.index })
            .map { it.value }
    }

    private fun pinned(used: Sequence<String>, limit: Int): List<String> =
        (favourites.asSequence() + used.filter { it !in favourites })
            .distinct()
            .take(limit)
            .toList()

    @Synchronized
    fun favourites(): List<String> = favourites.toList()

    @Synchronized
    fun isFavourite(emoji: String): Boolean = emoji in favourites

    /** Toggles favourite status; returns true when [emoji] is now a favourite. */
    @Synchronized
    fun toggleFavourite(emoji: String): Boolean {
        dirty = true
        return if (favourites.remove(emoji)) {
            false
        } else {
            favourites.add(emoji)
            while (favourites.size > MAX_FAVOURITES) favourites.removeAt(0)
            true
        }
    }

    /**
     * Rewrites the favourites order to [order]. Only current favourites
     * survive (deduped); any favourite absent from [order] is appended at the
     * tail, so a stale or partial order can never silently drop a pinned
     * emoji.
     */
    @Synchronized
    fun reorderFavourites(order: List<String>) {
        val current = favourites.toHashSet()
        val next = order.asSequence().filter { it in current }.distinct().toMutableList()
        for (fav in favourites) if (fav !in next) next.add(fav)
        favourites.clear()
        favourites.addAll(next)
        dirty = true
    }

    @Synchronized
    fun preferredVariant(base: String): String? = variantPrefs[base]

    /** Remembers the user's variant pick; passing the base itself resets it. */
    @Synchronized
    fun setPreferredVariant(base: String, variant: String) {
        if (variant == base) variantPrefs.remove(base) else variantPrefs[base] = variant
        dirty = true
    }

    @Synchronized
    fun variantPrefs(): Map<String, String> = HashMap(variantPrefs)

    fun save() {
        val file = snapshotFile ?: return
        val (ticket, snapshot) = synchronized(this) {
            if (!dirty) return
            dirty = false
            file.ticket() to Snapshot(recents.toList(), counts.toMap(), favourites.toList(), variantPrefs.toMap())
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

    /** Clears only the recents list, keeping counts, favourites and prefs. */
    @Synchronized
    fun clearRecents() {
        recents.clear()
        dirty = true
    }

    /**
     * Drops [emoji] from history entirely — recents, usage counts, and (if
     * pinned) favourites, since favourites head every history listing and
     * would keep the emoji visible after a "remove". Variant prefs stay.
     */
    @Synchronized
    fun removeFromHistory(emoji: String) {
        recents.remove(emoji)
        counts.remove(emoji)
        favourites.remove(emoji)
        dirty = true
    }

    @Synchronized
    fun clear() {
        recents.clear()
        counts.clear()
        favourites.clear()
        variantPrefs.clear()
        // The delete is the write; a later save must not recreate the file.
        // A delete that FAILED leaves the wiped history on disk, so stay dirty
        // and let the next save overwrite it with the empty snapshot.
        dirty = snapshotFile?.delete() == false
    }
}
