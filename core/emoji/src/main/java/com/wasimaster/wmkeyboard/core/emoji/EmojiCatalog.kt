package com.wasimaster.wmkeyboard.core.emoji

import java.io.InputStream

data class EmojiEntry(
    val emoji: String,
    val category: String,
    val keywords: List<String>,
    /**
     * Base emoji this entry is a gender/role variant of (woman-running →
     * runner, man-health-worker → person-health-worker). Variant entries
     * stay searchable but the palette grid shows only the base; variants
     * live in its long-press popup.
     */
    val parent: String? = null,
    /**
     * Unicode short name ("grinning face"), shown in the long-press popup
     * when emoji descriptions are on. Kept case- and phrase-intact, unlike
     * [keywords] which are lowercased and split for search.
     */
    val name: String? = null,
)

/**
 * Loads the bundled emoji catalog.
 *
 * Format: TSV with
 * `emoji<TAB>category<TAB>en,keywords<TAB>bn,keywords<TAB>parent<TAB>name`.
 * `parent` may be empty. English and Bengali keywords are merged into one
 * list so search is language-agnostic — searching বিড়াল or "cat" hits the
 * same entries.
 */
object EmojiCatalog {

    /**
     * The catalog, with every keyword and category spelled by one shared
     * string however many entries carry it.
     *
     * The keyboard holds the catalog for as long as it runs. Its 1,923 rows
     * carry 24,500 keywords but only 11,100 distinct ones ("face" alone is in
     * hundreds of rows), and each split used to make a string of its own:
     * about 350 KB of duplicates, and 45 KB more for ten category names
     * spelled 1,923 times (estimated from ART object sizes, 2026-11-26). The
     * search and suggestion indexes key on these same strings, so they share
     * them too.
     */
    fun load(stream: InputStream): List<EmojiEntry> {
        val entries = ArrayList<EmojiEntry>()
        val pool = StringPool()
        stream.bufferedReader().useLines { lines ->
            for (line in lines) {
                // "# " opens a comment, a bare "#" does not: the keycap hash
                // emoji (#️⃣) is a data row that starts with one.
                if (line.isBlank() || line.startsWith("# ")) continue
                val parts = line.split('\t')
                if (parts.size < 3) continue
                val keywords = ArrayList<String>()
                for (column in 2..minOf(3, parts.size - 1)) {
                    for (raw in parts[column].split(',')) {
                        val word = raw.trim().lowercase()
                        if (word.isNotEmpty()) keywords.add(pool.of(word))
                    }
                }
                keywords.trimToSize()
                val parent = parts.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() }
                val name = parts.getOrNull(5)?.trim()?.takeIf { it.isNotEmpty() }
                entries.add(EmojiEntry(parts[0].trim(), pool.of(parts[1].trim()), keywords, parent, name))
            }
        }
        entries.trimToSize()
        return entries
    }

    /**
     * Hands out one instance per distinct string: the first one it was
     * given. Only lives through a load or a merge.
     */
    internal class StringPool {
        private val pool = HashMap<String, String>()

        fun of(value: String): String = pool.getOrPut(value) { value }

        /** Seeds the pool with what [catalog] already spells, so additions share those. */
        fun addAll(catalog: List<EmojiEntry>) {
            for (entry in catalog) for (keyword in entry.keywords) pool.putIfAbsent(keyword, keyword)
        }
    }

    /**
     * [emoji] as the catalog is matched by: without U+FE0F, which a paste or
     * an older file may carry or drop where the catalog does the opposite.
     */
    fun matchKey(emoji: String): String = emoji.replace("\uFE0F", "")

    /** The catalog entry for [emoji], ignoring presentation selectors, or null. */
    fun find(catalog: List<EmojiEntry>, emoji: String): EmojiEntry? {
        val key = matchKey(emoji)
        return catalog.firstOrNull { matchKey(it.emoji) == key }
    }

    /**
     * [catalog] with the user's hand-added emoji ([added]) folded in: the way a
     * face from a newer Unicode release reaches the panel before a build ships
     * it.
     *
     * An emoji the catalog lacks is appended, so it lands at the end of its
     * category in the grid (see `EmojiOrder.catalogEmoji`) and after every
     * bundled emoji in search ties. One the catalog already has, which is what
     * a hand-added emoji becomes once an update ships it, only lends its
     * keywords to the bundled entry: the face is not drawn twice, and nothing
     * the user typed for it stops working.
     *
     * Runs before the keyword packs are merged, so a pack generated against a
     * newer Unicode version names the added emoji in its language too.
     * Returns [catalog] itself when there is nothing to add, keeping the
     * identity [EmojiNames] caches against.
     */
    fun withAdded(catalog: List<EmojiEntry>, added: List<EmojiEntry>): List<EmojiEntry> {
        if (added.isEmpty()) return catalog
        val positions = HashMap<String, Int>(catalog.size)
        catalog.forEachIndexed { index, entry -> positions.putIfAbsent(matchKey(entry.emoji), index) }
        val out = catalog.toMutableList()
        val pool = StringPool().apply { addAll(catalog) }
        for (entry in added) {
            val key = matchKey(entry.emoji)
            val at = positions[key]
            val keywords = entry.keywords.map(pool::of)
            if (at == null) {
                positions[key] = out.size
                out.add(entry.copy(keywords = keywords, parent = null))
            } else {
                val existing = out[at]
                out[at] = existing.copy(
                    keywords = (existing.keywords + keywords).distinct(),
                    name = existing.name ?: entry.name,
                )
            }
        }
        return out
    }

    /**
     * One hand-added emoji as a catalog entry. The name is indexed as a
     * keyword, whole and (by [EmojiSearch]) word by word, the way the bundled
     * catalog carries each Unicode name among its English keywords.
     */
    fun addedEntry(emoji: String, category: String, name: String, keywords: List<String>): EmojiEntry {
        val cleanName = name.trim()
        val words = (keywords + cleanName)
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
        return EmojiEntry(emoji.trim(), category, words, parent = null, name = cleanName.ifEmpty { null })
    }
}
