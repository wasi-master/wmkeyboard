package com.wasimaster.wmkeyboard.core.voice.vosk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Reading what Vosk writes. Every answer is a small JSON object, `{"text":
 * "..."}` for a finished utterance and `{"partial": "..."}` for one under
 * way, with the words in lower case and no marks. Pure, so the JVM tests
 * reach it.
 */
object VoskText {

    private val json = Json { ignoreUnknownKeys = true }

    /** The [key] field of Vosk's [raw] JSON, tidied; "" for anything unreadable. */
    fun field(raw: String, key: String): String {
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return ""
        val value = (obj[key] as? JsonPrimitive)?.contentOrNull ?: return ""
        return tidy(value)
    }

    /** A finished utterance's words. */
    fun text(raw: String): String = field(raw, "text")

    /** The words of the utterance under way. */
    fun partial(raw: String): String = field(raw, "partial")

    /**
     * Vosk's Chinese and Japanese models put a space between words, which
     * those languages do not write; a space between two such characters goes.
     * Runs of spaces become one, and the ends are trimmed.
     */
    fun tidy(text: String): String {
        val squeezed = text.trim().replace(SPACES, " ")
        if (squeezed.none(::closedScript)) return squeezed
        val out = StringBuilder(squeezed.length)
        for (i in squeezed.indices) {
            val c = squeezed[i]
            if (c == ' ' && i > 0 && i + 1 < squeezed.length &&
                closedScript(squeezed[i - 1]) && closedScript(squeezed[i + 1])
            ) {
                continue
            }
            out.append(c)
        }
        return out.toString()
    }

    /** [done] utterances and the one under way, as one line of words. */
    fun join(done: List<String>, current: String): String =
        (done + current).filter { it.isNotEmpty() }.joinToString(" ").let(::tidy)

    private val SPACES = Regex("\\s+")

    /** Han, kana, and the CJK marks between them: written without spaces. */
    private fun closedScript(c: Char): Boolean {
        val block = Character.UnicodeBlock.of(c) ?: return false
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS ||
            block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
    }
}
