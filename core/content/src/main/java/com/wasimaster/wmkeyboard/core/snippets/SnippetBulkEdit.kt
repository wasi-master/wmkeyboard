package com.wasimaster.wmkeyboard.core.snippets

/**
 * Options written to many snippets at once: every snippet of a folder, from
 * the folder's screen (#1122). Each field is null to leave every snippet's own
 * value alone, so one pass can change one thing and keep the rest.
 *
 * The rules are the snippet editor's own, so a snippet changed here is one
 * the editor would have saved: capitals and an early expansion belong to a
 * snippet typed by a word, not to one matched by a pattern, and a snippet
 * that asks first has nothing to expand early.
 */
data class SnippetBulkEdit(
    val propagateCase: Boolean? = null,
    val uppercaseStyle: UppercaseStyle? = null,
    val confirm: Boolean? = null,
    val immediate: Boolean? = null,
) {
    /** Nothing to change. */
    val isEmpty: Boolean
        get() = propagateCase == null && uppercaseStyle == null && confirm == null && immediate == null

    /** [snippet] with these options written in, where they apply to it. */
    fun applyTo(snippet: Snippet): Snippet {
        val word = snippet.triggerPattern.isNullOrBlank()
        var out = snippet
        confirm?.let { out = out.copy(confirm = it) }
        if (word) {
            propagateCase?.let { out = out.copy(propagateCase = it) }
            immediate?.let { out = out.copy(immediate = it, insideWords = it && out.insideWords) }
        }
        uppercaseStyle?.let { out = out.copy(uppercaseStyle = it) }
        // Asking first and expanding on the last letter cannot both hold.
        if (out.confirm && out.immediate) out = out.copy(immediate = false, insideWords = false)
        return out
    }
}
