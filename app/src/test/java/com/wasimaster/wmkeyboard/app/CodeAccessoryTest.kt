package com.wasimaster.wmkeyboard.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeAccessoryTest {

    @Test
    fun `a key types over the selection and leaves the caret after it`() {
        val typedOver = typed(TextFieldValue("total = x + 1", TextRange(8, 9)), "count")
        assertEquals("total = count + 1", typedOver.text)
        assertEquals(TextRange(13), typedOver.selection)
        assertEquals("ab==", typed(TextFieldValue("ab", TextRange(2)), "==").text)
    }

    @Test
    fun `the caret moves between lines at the same column, or the end of a shorter line`() {
        val text = "my_long = 1\nx\nmy_other = 2"
        assertEquals(text.indexOf("x") + 1, caretLines(text, 8, 1))
        assertEquals(text.indexOf("my_other") + 8, caretLines(text, 8, 2))
        assertEquals(8, caretLines(text, text.indexOf("my_other") + 8, -2))
        assertEquals(3, caretLines(text, 3, -1))
        assertEquals(text.length, caretLines(text, text.length, 5))
    }

    @Test
    fun `every key has its own label`() {
        assertEquals(PythonAccessoryKeys.size, PythonAccessoryKeys.map { it.label }.toSet().size)
    }

    @Test
    fun `the keys are Python's`() {
        val labels = PythonAccessoryKeys.map { it.label }.toSet()
        for (label in listOf(":", "#", "def", "return", "None", "True", "False", "global")) {
            assertTrue(label, label in labels)
        }
        for (lua in listOf("local", "end", "then", "nil", "--", "..")) {
            assertFalse(lua, lua in labels)
        }
        // A comment key leaves a space for the words. No Tab key: indenting is
        // the editor's Tab command, four columns for Python (bcede9a78).
        assertEquals("# ", PythonAccessoryKeys.single { it.label == "#" }.insert)
        assertFalse("Tab" in labels)
    }
}
