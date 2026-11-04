package com.wasimaster.wmkeyboard.core.emoji

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Reads the emoji names in a Clink language pack (`<code>.emoji.json`, from
 * the iOS keyboard Clink's `anti-ltd/clink-language-packs` and its forks):
 *
 * ```json
 * {"version": 1,
 *  "aliases": {"⚖": ["balans", "skaal"]},
 *  "names": {"⚖": "skaal"},
 *  "stopwords": ["die", "en"]}
 * ```
 *
 * `aliases` are the keywords and `names` the display names, which is a pack
 * here exactly. `stopwords` are the words Clink will not match an emoji on;
 * this keyboard's search has its own rule for short words, so they are not read.
 */
object ClinkEmojiNames {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class File(
        val aliases: Map<String, List<String>> = emptyMap(),
        val names: Map<String, String> = emptyMap(),
    )

    /** Null when [text] is not this format; an empty pack when it names nothing. */
    fun decode(text: String): EmojiKeywordPack? {
        if (!text.contains("\"aliases\"") && !text.contains("\"names\"")) return null
        val file = runCatching { json.decodeFromString<File>(text) }.getOrNull() ?: return null
        val keywords = LinkedHashMap<String, List<String>>(file.aliases.size)
        for ((emoji, words) in file.aliases) {
            val key = emoji.trim()
            if (key.isEmpty()) continue
            val kept = words.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
            if (kept.isNotEmpty()) keywords[key] = kept
        }
        val names = LinkedHashMap<String, String>(file.names.size)
        for ((emoji, name) in file.names) {
            val key = emoji.trim()
            val value = name.trim()
            if (key.isNotEmpty() && value.isNotEmpty()) names[key] = value
        }
        return EmojiKeywordPack(keywords, names)
    }
}
