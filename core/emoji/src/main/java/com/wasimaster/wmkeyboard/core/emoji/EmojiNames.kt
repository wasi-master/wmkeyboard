package com.wasimaster.wmkeyboard.core.emoji

/**
 * Emoji → Unicode short name lookup over a loaded [EmojiCatalog].
 *
 * The catalog is a flat list built once at startup, so the index is cached
 * against its identity: the first call after a (re)load pays for the map,
 * every later lookup is a hash hit. Only the long-press popup asks, so this
 * never runs per grid cell.
 */
object EmojiNames {

    /**
     * The catalog [index] was built from, weakly: a catalog reloaded after a
     * keyword pack download is a new list, and the old one, about 1 MB with
     * its keywords, should not stay alive here until the next long press.
     */
    private var source: java.lang.ref.WeakReference<List<EmojiEntry>>? = null
    private var index: Map<String, String> = emptyMap()

    /**
     * Drops the name table, about 150 KB; the next long press builds it
     * again from the catalog, a hash map of 1,923 names.
     */
    @Synchronized
    fun release() {
        source = null
        index = emptyMap()
    }

    private val TONES = 0x1F3FB..0x1F3FF
    private const val VS16 = '️'

    /**
     * Display name for [emoji], falling back to [base] (the palette entry a
     * skin-toned or gendered variant was picked from) and then to the
     * tone/selector-free form, which is how the catalog keys most entries.
     *
     * [localised] is the name table for the language being typed, when a
     * keyword pack supplies one; it is consulted first so an emoji reads in
     * that language and falls back to the catalog's Unicode name rather than
     * to some other installed language's.
     */
    fun of(
        catalog: List<EmojiEntry>,
        emoji: String,
        base: String = emoji,
        localised: Map<String, String> = emptyMap(),
    ): String? {
        val map = indexFor(catalog)
        return lookup(localised, emoji, base) ?: lookup(map, emoji, base)
    }

    private fun lookup(map: Map<String, String>, emoji: String, base: String): String? {
        if (map.isEmpty()) return null
        return map[emoji]
            ?: map[strip(emoji)]
            ?: map[base]
            ?: map[strip(base)]
    }

    @Synchronized
    private fun indexFor(catalog: List<EmojiEntry>): Map<String, String> {
        if (source?.get() !== catalog) {
            source = java.lang.ref.WeakReference(catalog)
            index = buildMap {
                for (entry in catalog) {
                    val name = entry.name ?: continue
                    put(entry.emoji, name)
                    // Same name under the selector-free key so a variant that
                    // dropped its FE0F still resolves.
                    putIfAbsent(strip(entry.emoji), name)
                }
            }
        }
        return index
    }

    /** Drops skin-tone modifiers and the FE0F presentation selector. */
    private fun strip(emoji: String): String = buildString {
        var i = 0
        while (i < emoji.length) {
            val cp = emoji.codePointAt(i)
            val size = Character.charCount(cp)
            if (cp !in TONES && cp != VS16.code) appendCodePoint(cp)
            i += size
        }
    }
}
