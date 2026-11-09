package com.wasimaster.wmkeyboard.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smart editing under Python's rules, which read `libwm_python`'s lexer for
 * what is a string or a comment. JSON's are pinned, unchanged, by [CodeEditorTest].
 */
class CodeSmartRulesTest {

    /** One step of Python indentation, as the editor adds it: four, per PEP 8. */
    private val indent = PythonSmartRules.indent

    private fun type(text: String, caret: Int, character: Char, rules: CodeSmartRules = PythonSmartRules): TextFieldValue {
        val old = TextFieldValue(text, TextRange(caret))
        val typed = TextFieldValue(text.substring(0, caret) + character + text.substring(caret), TextRange(caret + 1))
        return smartEdit(old, typed, rules)
    }

    private fun backspace(text: String, caret: Int): TextFieldValue {
        val old = TextFieldValue(text, TextRange(caret))
        return smartEdit(old, TextFieldValue(text.removeRange(caret - 1, caret), TextRange(caret - 1)), PythonSmartRules)
    }

    @Test
    fun `a round bracket closes`() {
        val result = type("f", 1, '(')
        assertEquals("f()", result.text)
        assertEquals(2, result.selection.end)
    }

    @Test
    fun `a single quote closes and then steps over`() {
        val opened = type("x = ", 4, '\'')
        assertEquals("x = ''", opened.text)
        assertEquals(5, opened.selection.end)
        val closed = type("x = ''", 5, '\'')
        assertEquals("x = ''", closed.text)
        assertEquals(6, closed.selection.end)
    }

    @Test
    fun `backspace over an empty round pair takes both halves`() {
        assertEquals("f", backspace("f()", 2).text)
    }

    @Test
    fun `nothing closes inside a comment`() {
        assertEquals("# note {", type("# note ", 7, '{').text)
        assertEquals("x = 1  # (", type("x = 1  # ", 9, '(').text)
    }

    @Test
    fun `nothing closes inside a triple-quoted string`() {
        assertEquals("s = \"\"\" (", type("s = \"\"\" ", 8, '(').text)
    }

    @Test
    fun `nothing closes inside a quoted string`() {
        assertEquals("s = \"ab[", type("s = \"ab", 7, '[').text)
    }

    @Test
    fun `typing the quote that ends a string adds no second one`() {
        assertEquals("s = \"ab\"", type("s = \"ab", 7, '"').text)
    }

    @Test
    fun `a newline after a colon indents`() {
        val result = type("if x:", 5, '\n')
        assertEquals("if x:\n$indent", result.text)
        assertEquals(6 + indent.length, result.selection.end)
    }

    @Test
    fun `a newline after a function header indents from where it stands`() {
        assertEquals("def render():\n$indent", type("def render():", 13, '\n').text)
        assertEquals("    def f(a):\n    $indent", type("    def f(a):", 13, '\n').text)
        assertEquals("if x:  # why\n$indent", type("if x:  # why", 12, '\n').text)
    }

    @Test
    fun `a colon that does not end the code does not indent`() {
        assertEquals("d = {1: 2}\n", type("d = {1: 2}", 10, '\n').text)
        assertEquals("x = 1  # note:\n", type("x = 1  # note:", 14, '\n').text)
        assertEquals("    s = \"a:\"\n    ", type("    s = \"a:\"", 12, '\n').text)
    }

    @Test
    fun `a newline between round brackets opens them up`() {
        val result = type("f()", 2, '\n')
        assertEquals("f(\n$indent\n)", result.text)
        assertEquals(3 + indent.length, result.selection.end)
    }

    @Test
    fun `the default rules are still JSON's`() {
        assertEquals("(", type("", 0, '(', CodeSmartRules.Json).text)
        val old = TextFieldValue("", TextRange(0))
        assertEquals("{}", smartEdit(old, TextFieldValue("{", TextRange(1))).text)
    }

    @Test
    fun `inside a string or comment, judged from the text before the caret`() {
        assertFalse(pythonInert("x = 1", 5))
        assertFalse(pythonInert("x", 0))
        assertTrue(pythonInert("# c", 3))
        assertTrue(pythonInert("x = 1  # c", 10))
        assertTrue(pythonInert("s = 'ab", 7))
        assertFalse(pythonInert("s = 'ab'", 8))
        assertTrue(pythonInert("s = \"\"\" x", 9))
        assertFalse(pythonInert("s = \"\"\"a\"\"\" y", 13))
    }

    @Test
    fun `which lines open a block`() {
        for (line in listOf("if x:", "for i in range(3):", "else:", "def render():", "    while True:", "try:", "if x:  # why")) {
            assertTrue(line, pythonOpensBlock(line))
        }
        for (line in listOf("x = f()", "d = {1: 2}", "# if x:", "", "return x", "s = 'a:'", "f = lambda x: x")) {
            assertFalse(line, pythonOpensBlock(line))
        }
    }
}
