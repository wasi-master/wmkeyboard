package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.snippets.SnippetAsk

/**
 * The fill-in row while a snippet with `{ask:Name}` fields waits for its
 * answers: which field is being asked for and what has been typed so far.
 *
 * Held by [ToolTextEntries.snippetAsk]; while it is there the keys type into
 * it ([CaptureTarget.SNIPPET_ASK]) and nothing reaches the app's field. The
 * text waiting to go in, and where, is the service's: the row only says what
 * it is asking.
 */
data class SnippetAskEntry(
    /** Every field the snippet asks for, each once, in order. */
    val fields: List<SnippetAsk>,
    /** The field being asked for now, an index into [fields]. */
    val index: Int = 0,
    /** The answers already given, one per field before [index]. */
    val answers: List<String> = emptyList(),
    /** What has been typed for the current field. Starts as its default. */
    val text: String = fields.getOrNull(index)?.default.orEmpty(),
) {
    /** The field being asked for now. */
    val field: SnippetAsk get() = fields[index.coerceIn(0, fields.lastIndex)]

    /** True when Enter on this field inserts the snippet rather than moving on. */
    val last: Boolean get() = index >= fields.lastIndex

    /** The next field, with [text] kept as this one's answer; null past the last. */
    fun next(): SnippetAskEntry? {
        if (last) return null
        val answered = answers + text
        return SnippetAskEntry(fields, index + 1, answered)
    }

    /** Every answer, this field's included, by field name. */
    fun answerMap(): Map<String, String> {
        val all = answers + text
        return fields.mapIndexedNotNull { i, field -> all.getOrNull(i)?.let { field.name to it } }.toMap()
    }

    companion object {
        /** Longest answer one field takes. */
        const val MAX_ANSWER = 500
    }
}
