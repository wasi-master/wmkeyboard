package com.wasimaster.wmkeyboard.core.selection

/**
 * Enter on a list line in any app (#694): the next line starts with the next
 * marker, the way the Notes editor already does it (`NotesLogic.enter` in the
 * IME). `- `, `* `, `+ ` and `• ` carry on as they are, `1. ` and `1) ` count
 * on, a Markdown task box (`- [x] `) carries on as an empty one, and so does
 * a `☐ ` box.
 *
 * Enter on an item with nothing in it ends the list instead: the marker goes
 * and no line break is typed, which is how every editor lets you out of one.
 *
 * Numbers stop at three digits so a line that opens with a year (`2024. was
 * long`) is not read as item two thousand and twenty-four.
 */
object ListContinuation {

    sealed interface Step {
        /** Type the line break, then [marker]. */
        data class Continue(val marker: String) : Step

        /** Delete the [length] characters before the caret, the empty item's marker, and type nothing else. */
        data class End(val length: Int) : Step
    }

    // Built on first use: each compiled Regex holds native ICU sets for the
    // life of the process, about 17 KB across this file's patterns.
    private val ITEM by lazy { Regex("""^([ \t]*)(?:([-*+•])([ \t]+\[[ xX]])?|(\d{1,3})([.)])|([☐☑☒]))([ \t]+)""") }

    /**
     * What Enter does with the caret after [lineBefore] and in front of
     * [lineAfter], both cut at the line's own breaks. Null when the line is not
     * a list item, or the caret sits inside the marker: a plain line break.
     */
    fun onEnter(lineBefore: String, lineAfter: String): Step? {
        val match = ITEM.find(lineBefore) ?: return null
        val marker = match.value
        if (lineBefore.substring(marker.length).isBlank() && lineAfter.isBlank()) {
            return Step.End(lineBefore.length)
        }
        val g = match.groupValues
        val indent = g[1]
        val gap = g[7]
        val next = when {
            g[2].isNotEmpty() && g[3].isNotEmpty() -> "${g[2]} [ ]"
            g[2].isNotEmpty() -> g[2]
            g[4].isNotEmpty() -> "${g[4].toInt() + 1}${g[5]}"
            else -> "☐"
        }
        return Step.Continue(indent + next + gap)
    }
}
