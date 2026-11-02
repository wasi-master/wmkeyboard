package com.wasimaster.wmkeyboard.ime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A plugin's own text box is one of a dozen places that take the keys away from
 * the user's field. Every one of them has to be handled the same way in a
 * scattered set of conditions — the delete keys, the forward-delete guards, the
 * hardware intercept, the numeric-pad override, and the decision to draw the
 * key rows at all.
 *
 * Those lists used to be hand-maintained, and a buffer added to some but not
 * others is exactly what shipped, repeatedly: the plugin panel collapsed to
 * make room for keys that were never drawn; a physical keyboard typed the AI
 * instruction into the app's field; a space pressed in the clipboard search
 * went into the text behind the panel.
 *
 * Issue #161 replaced the lists with one ladder — `KeyboardUiState.captureTarget`,
 * over the `CaptureTarget` enum, which the compiler makes exhaustive. So this
 * test's job changed: instead of holding a dozen copies in step, it holds the
 * copies to *one*. It reads the sources and fails if any of those sites grows
 * its own list of buffer flags again.
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

    private val stateSource: String by lazy {
        source("../feature/ime/src/main/java/com/wasimaster/wmkeyboard/ime/KeyboardState.kt")
    }

    /** Every buffer flag the ladder has to name, in any order. */
    private val ownerFlags = listOf(
        "typingTestActive", "aiCustomInputActive", "pluginTypingActive",
        "findReplaceTypingActive", "learnEditActive", "calcTypingActive",
        "converterTypingActive", "wordSpellActive", "emojiSearchActive",
        "mediaSearchActive", "dictionarySearchActive", "clipboardSearchActive",
        "clipEditActive",
    )

    /**
     * The multi-clause boolean expressions that name a buffer flag alongside
     * others. One of these outside the ladder is a second list, and a second
     * list is the drift this whole test exists for.
     *
     * An expression is the run of lines around the flag that are joined by a
     * trailing operator. Collected by line rather than by syntax, because the
     * lists were written four different ways — two `if (...)` heads and two
     * `return` chains — and an earlier net that matched only `if (` heads let
     * two of the four sites go unchecked.
     */
    private fun ownerConditions(text: String): List<String> = buildList {
        val lines = text.lines()
        fun continues(line: String) = line.trimEnd().endsWith("||") || line.trimEnd().endsWith("&&")
        for ((index, line) in lines.withIndex()) {
            if (ownerFlags.none { line.contains(it) }) continue
            var first = index
            while (first > 0 && continues(lines[first - 1])) first--
            var last = index
            while (last + 1 < lines.size && continues(lines[last])) last++
            val expression = lines.subList(first, last + 1).joinToString("\n")
            // Two flags or more in one expression is a list; one flag joined to
            // something else (a panel check, a strokes check) is a branch.
            if (ownerFlags.count { expression.contains(it) } >= 2) add(expression)
        }
    }

    @Test
    fun `the ladder names every buffer`() {
        val ladder = stateSource
            .substringAfter("fun captureTarget(): CaptureTarget?")
            .substringBefore("\n    }")
        assertEquals(
            "buffers the capture ladder does not name, so their keys reach the app behind the keyboard",
            emptyList<String>(),
            ownerFlags.filterNot { ladder.contains(it) },
        )
    }

    @Test
    fun `the service keeps no owner list of its own`() {
        assertEquals(
            "the service grew a second list of keyboard-owned buffers; ask captureTarget() instead",
            emptyList<String>(),
            ownerConditions(serviceSource),
        )
    }

    @Test
    fun `the keyboard screen keeps no owner list of its own`() {
        assertEquals(
            "KeyboardScreen grew a second list of keyboard-owned buffers; ask captureTarget() instead",
            emptyList<String>(),
            ownerConditions(screenSource),
        )
    }

    @Test
    fun `the numeric pad asks the ladder`() {
        val body = screenSource
            .substringAfter("private fun numericPadActive(")
            .substringBefore("\n\n")
        assertTrue(
            "a numeric field would give a keyboard-owned box a digits-only pad",
            body.contains("captureTarget()"),
        )
    }

    /**
     * The hardware gate is a bare `return` expression, not an `if (` head, so
     * the owner-condition net above would never have seen it — which is exactly
     * how the AI custom-instruction box shipped typing its physical keys into
     * the app's field. Held by name instead.
     */
    @Test
    fun `physical keys reach every buffer the soft keys do`() {
        val body = serviceSource
            .substringAfter("private fun hardwareIntercepts(")
            .substringBefore("\n    }")
        assertTrue("hardwareIntercepts not found", body.contains("composingMode"))
        assertTrue(
            "a physical keyboard cannot reach the keyboard's own fields",
            body.contains("captureTarget()"),
        )
    }

    @Test
    fun `the key rows are drawn while a keyboard-owned box has the keys`() {
        assertTrue(
            "no KeyRows are drawn for a capture target, so a focused box " +
                "would have nothing on screen to type into it",
            Regex("""fun keyRowsUnderPanelRaised\(state: KeyboardUiState\): Boolean = when \(state\.panelFieldTarget\(\)\)""")
                .containsMatchIn(screenSource),
        )
    }
}
