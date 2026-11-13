package com.wasimaster.wmkeyboard.core.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A theme's own rule sheet, compiled onto its key colours and single-key styles. */
class KeyRuleSheetTest {

    private val base = ThemeSpec(
        id = "t",
        name = "T",
        keyBackground = 0xFF303338,
        keyOverrides = mapOf("ENTER" to KeyOverride(border = 0xFF00FF00, glow = 0xFF0000FF)),
    )

    private fun compile(sheet: String) = KeyRuleSheet.compile(base.copy(ruleSheet = sheet))

    @Test
    fun noSheetIsTheSameTheme() {
        assertSame(base, base.withRuleSheet())
        val blank = base.copy(ruleSheet = "  ")
        assertSame(blank, blank.withRuleSheet())
    }

    @Test
    fun aCodeNamesItsKeyAndVariablesResolve() {
        val result = compile(
            """
            {
              "@defines": { "--hot": "#FF3355" },
              "key[code=10]": { "background": "var(--hot)", "shape": "circle()" },
              "key[code=10]:pressed": { "background": "#AA2233" }
            }
            """.trimIndent(),
        )
        val enter = result.theme.keyOverrides.getValue("ENTER")
        assertEquals(0xFFFF3355L, enter.background)
        assertEquals(KeyShapeKind.CIRCLE.name, enter.shape)
        assertEquals(0xFFAA2233L, enter.pressedBackground)
        // What the theme's own style for the key said, and the sheet did not, stays.
        assertEquals(0xFF00FF00L, enter.border)
        assertEquals(0xFF0000FFL, enter.glow)
        assertEquals(2, result.applied)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun outputsRangesAndIdsAllNameKeys() {
        val result = compile(
            """
            {
              "key[output=`a`]": { "foreground": "#111111" },
              "key[code=48..50]": { "foreground": "#222222" },
              "key[id=`EDIT_UNDO`]": { "background": "#333333" },
              "key[id=`Q`]": { "font-weight": "bold", "font-size": "33sp" }
            }
            """.trimIndent(),
        )
        val overrides = result.theme.keyOverrides
        assertEquals(0xFF111111L, overrides.getValue("a").text)
        for (digit in listOf("0", "1", "2")) assertEquals(0xFF222222L, overrides.getValue(digit).text)
        assertEquals(0xFF333333L, overrides.getValue("EDIT_UNDO").background)
        // A single character is a letter, styled by its lowercase label.
        assertEquals(true, overrides.getValue("q").bold)
        assertEquals(1.5f, overrides.getValue("q").labelScale!!, 0.001f)
    }

    @Test
    fun classesSetTheThemesOwnColours() {
        val result = compile(
            """
            {
              "key": { "background": "#101010", "foreground": "#EEEEEE" },
              "key[class=`modifier`]": { "background": "#202020" },
              "key[class=`enter`]": { "background": "#303030" },
              "key[class=`space`]": { "background": "#404040" },
              "key:pressed": { "background": "#505050" },
              "key-hint": { "foreground": "#606060" }
            }
            """.trimIndent(),
        )
        val theme = result.theme
        assertEquals(0xFF101010L, theme.keyBackground)
        assertEquals(0xFFEEEEEEL, theme.keyText)
        assertEquals(0xFF202020L, theme.modifierKeyBackground)
        assertEquals(0xFF303030L, theme.enterKeyBackground)
        assertEquals(0xFF404040L, theme.keyOverrides.getValue("SPACE").background)
        assertEquals(0xFF505050L, theme.pressedKeyBackground)
        assertEquals(0xFF606060L, theme.hintText)
        assertEquals(6, result.applied)
    }

    @Test
    fun selectorListsShareOneBody() {
        val result = compile("""{ "key[output=`a`], key[output=`e`]": { "foreground": "#123456" } }""")
        assertEquals(0xFF123456L, result.theme.keyOverrides.getValue("a").text)
        assertEquals(0xFF123456L, result.theme.keyOverrides.getValue("e").text)
        // A comma inside the brackets is a list of codes, not of selectors.
        val codes = compile("""{ "key[code=97,101]": { "foreground": "#654321" } }""")
        assertEquals(0xFF654321L, codes.theme.keyOverrides.getValue("e").text)
    }

    @Test
    fun laterRulesWin() {
        val result = compile(
            """
            {
              "key[code=10]": { "background": "#111111" },
              "key[output=`action/enter`]": { "background": "#222222" }
            }
            """.trimIndent(),
        )
        assertEquals(0xFF222222L, result.theme.keyOverrides.getValue("ENTER").background)
    }

    @Test
    fun whatCannotLandIsNamedRuleByRule() {
        val result = compile(
            """
            {
              "smartbar": { "background": "#000000" },
              "key[code=10]:focus": { "background": "#000000" },
              "key[mode=`numeric`]": { "background": "#000000" },
              "key[code=-999]": { "background": "#000000" },
              "key-hint[code=10]": { "background": "#000000" },
              "sparkle": { "background": "#000000" }
            }
            """.trimIndent(),
        )
        val kinds = result.issues.map { it.kind }.toSet()
        assertEquals(
            setOf(
                RuleSheetIssue.Kind.ELEMENT,
                RuleSheetIssue.Kind.STATE,
                RuleSheetIssue.Kind.ATTRIBUTE,
                RuleSheetIssue.Kind.KEY,
                RuleSheetIssue.Kind.PROPERTY,
            ),
            kinds,
        )
        assertTrue(result.issues.any { it.kind == RuleSheetIssue.Kind.STATE && it.rule == "key[code=10]:focus" })
        assertTrue(result.issues.any { it.kind == RuleSheetIssue.Kind.ELEMENT && it.rule == "sparkle" })
        // Quoted as written, not under the parser's own name for it.
        assertTrue(result.issues.any { it.kind == RuleSheetIssue.Kind.ELEMENT && it.rule == "smartbar" })
        assertEquals(0, result.applied)
        // Nothing landed, so the theme's colours are untouched.
        assertEquals(base.keyBackground, result.theme.keyBackground)
    }

    @Test
    fun brokenTextIsUnreadableAndChangesNothing() {
        val result = compile("{ \"key\": ")
        assertEquals(listOf(RuleSheetIssue.Kind.UNREADABLE), result.issues.map { it.kind })
        assertEquals(base.copy(ruleSheet = "{ \"key\": "), result.theme)
    }

    @Test
    fun selectorListsSplitOutsideBracketsOnly() {
        assertNull(KeyRuleSheet.expandSelectorLists("not json"))
        val text = """{"key[code=1,2]":{"background":"#000"}}"""
        assertSame(text, KeyRuleSheet.expandSelectorLists(text))
    }
}
