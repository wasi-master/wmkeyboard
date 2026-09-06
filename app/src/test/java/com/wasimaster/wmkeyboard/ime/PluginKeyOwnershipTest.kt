package com.wasimaster.wmkeyboard.ime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A plugin's own text box is one of several places that take the keys away from
 * the user's field. Every one of them has to be handled the same way in a
 * scattered set of conditions — the delete keys, the forward-delete guards, the
 * numeric-pad override, and the decision to draw the key rows at all.
 *
 * Those lists are hand-maintained, and a plugin box added to some but not others
 * is exactly what shipped: the panel collapsed to make room for keys that were
 * never drawn, so a focused box had nothing to type into it, and a backspace
 * swipe word-deleted the app's own text behind the panel.
 *
 * There is no way to assert this from state alone — the conditions live inside
 * private methods and composables that need an Android runtime. So this reads
 * the sources and holds the lists together: wherever the clipboard search is
 * named as owning the keys, the plugin box must be named too.
 */
class PluginKeyOwnershipTest {

    private fun source(path: String): String {
        val file = File(path)
        assertTrue("source not found at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private val serviceSource: String by lazy {
        source("../feature/ime/src/main/java/com/wasimaster/wmkeyboard/ime/WMKeyboardService.kt")
    }

    private val screenSource: String by lazy {
        source("../feature/ime/src/main/java/com/wasimaster/wmkeyboard/ime/ui/KeyboardScreen.kt")
    }

    /**
     * The multi-clause boolean expressions that name the clipboard search among
     * several other buffers. Single-clause guards are left alone: those are the
     * dispatch branches that route a keystroke to one particular buffer, and a
     * plugin box has its own branch right beside them.
     *
     * Collected by line rather than by syntax, because the same list is written
     * four different ways — two `if (...)` heads, and two `return` chains. An
     * earlier net matched only `if (` heads, so extracting one of those guards
     * into a `return` expression quietly took it out of the net rather than
     * failing: two of the four sites went unchecked.
     *
     * An expression is the run of lines around the clipboard term that are
     * joined by a trailing operator. Negated mentions are skipped: those say
     * "no buffer owns the keys" and carry their own membership rules, so the
     * calculator's absence from one is not the drift this is looking for.
     */
    private fun ownerConditions(text: String): List<String> = buildList {
        val lines = text.lines()
        fun continues(line: String) = line.trimEnd().endsWith("||") || line.trimEnd().endsWith("&&")
        for ((index, line) in lines.withIndex()) {
            if (!line.contains("clipboardSearchActive")) continue
            if (line.contains("!state.clipboardSearchActive")) continue
            var first = index
            while (first > 0 && continues(lines[first - 1])) first--
            var last = index
            while (last + 1 < lines.size && continues(lines[last])) last++
            val expression = lines.subList(first, last + 1).joinToString("\n")
            if (expression.contains("||")) add(expression)
        }
    }

    @Test
    fun `the service treats a plugin box as owning the keys wherever the clipboard search does`() {
        val conditions = ownerConditions(serviceSource)
        assertTrue("no keystroke-owner conditions found in the service", conditions.size >= 3)
        val missing = conditions.filterNot { it.contains("pluginTypingActive") }
        assertEquals(
            "service conditions that hand the keys to the clipboard search but not to a plugin box",
            emptyList<String>(),
            missing,
        )
    }

    @Test
    fun `the numeric pad steps aside for a plugin box`() {
        val body = screenSource
            .substringAfter("private fun numericPadActive(")
            .substringBefore("\n\n")
        assertTrue("numericPadActive not found", body.contains("clipboardSearchActive"))
        assertTrue(
            "a numeric field would give a plugin box a digits-only pad",
            body.contains("pluginTypingActive"),
        )
    }

    /**
     * The hardware gate is a bare `return` expression, not an `if (` head, so
     * the owner-condition net above never sees it — which is exactly how the
     * AI custom-instruction box shipped typing its physical keys into the
     * app's field. Held to the full list by name instead.
     */
    @Test
    fun `physical keys reach every buffer the soft keys do`() {
        val body = serviceSource
            .substringAfter("private fun hardwareIntercepts(")
            .substringBefore("\n    }")
        assertTrue("hardwareIntercepts not found", body.contains("composingMode"))
        val owners = listOf(
            "emojiSearchActive", "dictionarySearchActive", "clipboardSearchActive",
            "typingTestActive", "pluginTypingActive", "aiCustomInputActive",
            "calcTypingActive", "converterTypingActive",
        )
        assertEquals(
            "buffers the soft keys feed but a physical keyboard cannot reach",
            emptyList<String>(),
            owners.filterNot { body.contains(it) },
        )
    }

    /**
     * The calculator/converter buffers joined the same scattered owner lists,
     * and the same drift is possible: a delete guard that forgets them edits
     * the field behind the panel.
     */
    @Test
    fun `the service treats the calculator buffers as owning the keys too`() {
        val conditions = ownerConditions(serviceSource)
        assertTrue("no keystroke-owner conditions found in the service", conditions.size >= 3)
        val missing = conditions.filterNot {
            it.contains("calcTypingActive") && it.contains("converterTypingActive")
        }
        assertEquals(
            "service conditions that hand the keys to the clipboard search but not to the calculator",
            emptyList<String>(),
            missing,
        )
    }

    @Test
    fun `the key rows are drawn while a plugin box has the keys`() {
        assertTrue(
            "no KeyRows are drawn for pluginTypingActive, so a focused plugin box " +
                "would have nothing on screen to type into it",
            Regex("""if \(state\.pluginTypingActive\) \{\s*KeyRows\(""")
                .containsMatchIn(screenSource),
        )
    }
}
