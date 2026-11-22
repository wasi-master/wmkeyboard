package com.wasimaster.wmkeyboard.core.emoji

import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * Which of the two text-art families an entry belongs to. Merged into one tab
 * by default; the split setting gives each its own tab again.
 */
@Serializable
enum class KaomojiKind {
    /** A Japanese-style face built from CJK and box-drawing glyphs: `(╯°□°）╯︵ ┻━┻`. */
    KAOMOJI,

    /** A Western ASCII emoticon: `:-)`, `<3`. */
    EMOTICON,
    ;

    companion object {
        /**
         * The family [text] most likely belongs to: printable ASCII alone reads
         * as an emoticon, anything reaching past it as a kaomoji. Used for
         * entries the user adds or imports without saying which.
         */
        fun of(text: String): KaomojiKind =
            if (text.isNotEmpty() && text.all { it in ' '..'~' }) EMOTICON else KAOMOJI
    }
}

/**
 * One face in the user's kaomoji library. [tags] are the tabs it shows under;
 * [uses] and [lastUsedAt] feed the Recent tab, [favourite] the starred one.
 */
@Serializable
data class Kaomoji(
    val id: Long,
    val text: String,
    val tags: List<String> = emptyList(),
    val kind: KaomojiKind = KaomojiKind.KAOMOJI,
    val favourite: Boolean = false,
    val uses: Int = 0,
    val lastUsedAt: Long = 0L,
) {
    fun hasTag(name: String): Boolean = tags.any { it.equals(name, ignoreCase = true) }
}

/**
 * A read-only view of the library, what the keyboard draws from. Plain data,
 * so the panel can hold one per emission and filter it without a lock.
 *
 * [items] are in the user's order and [tags] in the tag strip's order; the
 * Recent view alone sorts by time instead.
 */
data class KaomojiLibrary(
    val items: List<Kaomoji>,
    val tags: List<String>,
    val hiddenTags: Set<String> = emptySet(),
) {
    /** Items of [kind], or all of them when null. */
    fun items(kind: KaomojiKind?): List<Kaomoji> = if (kind == null) items else items.filter { it.kind == kind }

    /**
     * The entries the tag strip's [view] holds: one of the [VIEW_ALL],
     * [VIEW_FAVOURITES] and [VIEW_RECENT] sentinels, or a tag's name.
     */
    fun view(view: String, kind: KaomojiKind?): List<Kaomoji> = when (view) {
        VIEW_ALL -> items(kind)
        VIEW_FAVOURITES -> favourites(kind)
        VIEW_RECENT -> recents(kind)
        else -> items(kind).filter { it.hasTag(view) }
    }

    fun favourites(kind: KaomojiKind?): List<Kaomoji> = items(kind).filter { it.favourite }

    /** Used entries, the latest first. */
    fun recents(kind: KaomojiKind?, limit: Int = RECENT_LIMIT): List<Kaomoji> =
        items(kind).filter { it.lastUsedAt > 0 }.sortedByDescending { it.lastUsedAt }.take(limit)

    /**
     * The tags the strip shows for [kind]: not hidden, and carried by at least
     * one entry of that kind, so the split Emoticons tab does not list a tab
     * of kaomoji-only moods that would open empty.
     */
    fun visibleTags(kind: KaomojiKind?): List<String> {
        val used = HashSet<String>()
        for (item in items(kind)) for (tag in item.tags) used.add(tag.lowercase(Locale.ROOT))
        return tags.filter { it.lowercase(Locale.ROOT) in used && !isHidden(it) }
    }

    fun isHidden(tag: String): Boolean = hiddenTags.any { it.equals(tag, ignoreCase = true) }

    /**
     * Entries matching [query] in their text or a tag, ignoring case. A tag
     * hit that starts the tag ranks ahead of one inside it, and both ahead of
     * a match in the face itself, which is the rarer thing to search by.
     * Last come the faces [stemmed] accepts: a tag holding another form of
     * the query's words ("laughing" finds the faces tagged laugh).
     */
    fun search(query: String, kind: KaomojiKind?, stemmed: ((Kaomoji) -> Boolean)? = null): List<Kaomoji> {
        val needle = query.trim().lowercase(Locale.ROOT)
        if (needle.isEmpty()) return items(kind)
        val ranked = ArrayList<Pair<Int, Kaomoji>>()
        for (item in items(kind)) {
            val tags = item.tags.map { it.lowercase(Locale.ROOT) }
            val rank = when {
                tags.any { it == needle } -> 0
                tags.any { it.startsWith(needle) } -> 1
                tags.any { it.contains(needle) } -> 2
                item.text.lowercase(Locale.ROOT).contains(needle) -> 3
                stemmed?.invoke(item) == true -> 4
                else -> continue
            }
            ranked.add(rank to item)
        }
        // Stable: within a rank the user's own order holds.
        return ranked.sortedBy { it.first }.map { it.second }
    }

    /**
     * Faces to offer in the suggestion strip for a typed [word]: entries
     * tagged exactly that word, favourites and the most used first. Nothing
     * for words shorter than [MIN_SUGGEST_LENGTH], which would match half the
     * moods on every keystroke.
     */
    fun suggestionsFor(word: String, limit: Int = 3): List<Kaomoji> {
        val needle = word.trim().lowercase(Locale.ROOT)
        if (needle.length < MIN_SUGGEST_LENGTH) return emptyList()
        return items
            .filter { item -> item.tags.any { it.lowercase(Locale.ROOT) == needle } }
            .sortedWith(compareByDescending<Kaomoji> { it.favourite }.thenByDescending { it.uses })
            .take(limit)
    }

    companion object {
        // Control characters: a cleaned tag name can never hold one.
        const val VIEW_ALL = "\u0001all"
        const val VIEW_FAVOURITES = "\u0001favourites"
        const val VIEW_RECENT = "\u0001recent"

        const val RECENT_LIMIT = 48
        const val MIN_SUGGEST_LENGTH = 3

        val EMPTY = KaomojiLibrary(emptyList(), emptyList())

        /**
         * Whether a suggestion-strip chip is a face rather than an emoji, so
         * the strip draws it as text. Three or more UTF-16 units with no
         * surrogate pair, variation selector or keycap mark: every emoji the
         * catalog has is shorter or carries one of those.
         */
        fun isTextArt(text: String): Boolean =
            text.length >= 3 && text.none { it.isSurrogate() || it == '️' || it == '⃣' || it == '‍' }

        /**
         * The compiled-in catalog as a library: every [TextArt] group becomes a
         * tag, moods the two families share (Happy, Sad, Love…) become one tag,
         * and a face filed under two moods is one entry with both. Ids are
         * small and fixed, so the seed is the same on every phone.
         */
        fun builtIn(): KaomojiLibrary {
            val byText = LinkedHashMap<String, Kaomoji>()
            val tags = ArrayList<String>()
            var nextId = 1L
            fun addGroups(groups: List<TextArtGroup>, kind: KaomojiKind) {
                for (group in groups) {
                    if (tags.none { it.equals(group.name, ignoreCase = true) }) tags.add(group.name)
                    for (text in group.items) {
                        val existing = byText[text]
                        byText[text] = if (existing == null) {
                            Kaomoji(id = nextId++, text = text, tags = listOf(group.name), kind = kind)
                        } else if (existing.hasTag(group.name)) {
                            existing
                        } else {
                            existing.copy(tags = existing.tags + group.name)
                        }
                    }
                }
            }
            addGroups(TextArt.kaomoji, KaomojiKind.KAOMOJI)
            addGroups(TextArt.emoticons, KaomojiKind.EMOTICON)
            return KaomojiLibrary(byText.values.toList(), tags)
        }
    }
}
