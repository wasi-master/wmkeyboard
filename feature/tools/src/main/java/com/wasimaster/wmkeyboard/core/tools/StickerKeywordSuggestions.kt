package com.wasimaster.wmkeyboard.core.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Keyword suggestions for the user's own stickers (#1158): the prompt that
 * asks a model for them, and the reading of its answer into a short,
 * clean list the user ticks from. Nothing here is saved; the review sheet
 * keeps only what the user picks.
 */
object StickerKeywordSuggestions {

    /** How many suggestions one sticker gets at most: the issue's "ten or so". */
    const val MAX_SUGGESTIONS = 10

    /** A keyword longer than this is a sentence, not something anyone types to find a sticker. */
    private const val MAX_LENGTH = 40

    /** What the model is told about one sticker. */
    data class Request(
        val packName: String,
        val title: String,
        val existing: List<String>,
        /** The languages the user types in, by name, the first being the main one. */
        val languages: List<String>,
        /** Whether the sticker's picture goes along. Without it the model works from the words alone. */
        val withPicture: Boolean,
        /** What the user added for this run, such as "these are for my family chat". Blank for nothing. */
        val instruction: String = "",
    )

    fun systemPrompt(request: Request): String = buildString {
        append("You suggest search keywords for stickers in a phone keyboard. ")
        append("A keyword is what someone types when they want to send this sticker: ")
        append("the feeling, reaction or situation it fits, a word for what it shows, or a single emoji. ")
        append("Prefer short everyday words and phrases people really type in chats, one to three words each. ")
        append("Give up to ").append(MAX_SUGGESTIONS).append(" keywords, including two or three emoji. ")
        val languages = request.languages.filter { it.isNotBlank() }.distinct()
        if (languages.size > 1) {
            append("The user types in ").append(languages.joinToString(", "))
            append(", so spread the keywords over these languages, written as each language is written. ")
        } else if (languages.size == 1) {
            append("Write the keywords in ").append(languages.single()).append(". ")
        }
        append("Answer with only a JSON array of strings, nothing before or after it.")
    }

    fun userPrompt(request: Request): String = buildString {
        if (request.packName.isNotBlank()) append("Sticker pack: ").append(request.packName.trim()).append('\n')
        if (request.title.isNotBlank()) append("Sticker title: ").append(request.title.trim()).append('\n')
        if (request.existing.isNotEmpty()) {
            append("It already has these keywords; do not repeat them: ")
            append(request.existing.joinToString(", ")).append('\n')
        }
        if (request.withPicture) {
            append("The sticker is the attached picture.")
        } else {
            append("There is no picture: suggest from the pack name and the title alone.")
        }
        request.instruction.trim().takeIf { it.isNotEmpty() }?.let {
            append("\n\nThe user adds: ").append(it)
        }
    }

    /**
     * The suggestions in [answer]: its JSON array when it has one, else its
     * lines or commas, with list markers and quotes taken off. Reasoning is
     * dropped, repeats and the sticker's [existing] keywords are left out
     * whatever their case, and at most [MAX_SUGGESTIONS] are kept.
     */
    fun parse(answer: String, existing: List<String> = emptyList()): List<String> {
        val text = AiThinking.stripped(answer).trim()
        val raw = jsonArray(text) ?: text
            .split('\n', ',', '،', '、', '，')
            .map { it.trim() }
        val seen = existing.mapTo(HashSet()) { it.trim().lowercase() }
        return raw.asSequence()
            .map(::clean)
            .filter { it.isNotEmpty() && it.length <= MAX_LENGTH }
            .filter { seen.add(it.lowercase()) }
            .take(MAX_SUGGESTIONS)
            .toList()
    }

    private fun jsonArray(text: String): List<String>? {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        val array = runCatching { Json.parseToJsonElement(text.substring(start, end + 1)) as? JsonArray }.getOrNull()
            ?: return null
        return array.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    }

    private val LIST_MARKER = Regex("""^(?:[-*•]|\d+[.)])\s*""")

    private fun clean(item: String): String =
        item.trim()
            .replace(LIST_MARKER, "")
            .trim('"', '\'', '`', '“', '”', '[', ']', ' ')
            .replace(Regex("""\s+"""), " ")

    /**
     * The sticker's keywords after the review: the ones it had, then the
     * suggestions the user [chosen], then the user's [own], each once.
     */
    fun merged(existing: List<String>, chosen: List<String>, own: List<String>): List<String> {
        val seen = HashSet<String>()
        return (existing + chosen + own).map { it.trim() }.filter { it.isNotEmpty() && seen.add(it.lowercase()) }
    }
}
