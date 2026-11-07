package com.wasimaster.wmkeyboard.app

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [CodeEditorState]'s prepared edits, and what survives a rotation. */
class CodeEditorStateTest {

    private val scope = SaverScope { true }

    @Test
    fun `a prepared edit is one step of history`() {
        val state = CodeEditorState(TextFieldValue("x = 1"))
        state.applyEdit(toggleLineComment(state.text, state.value.selection, "#"))
        assertEquals("# x = 1", state.text)
        assertTrue(state.canUndo)
        state.undo()
        assertEquals("x = 1", state.text)
        assertFalse(state.canUndo)
    }

    @Test
    fun `an edit that changes nothing records nothing`() {
        val state = CodeEditorState(TextFieldValue("abc"))
        state.applyEdit(CodeTextEdit(TextRange(0), "", TextRange(0)))
        assertFalse(state.canUndo)
    }

    @Test
    fun `selecting clamps to the document and records nothing`() {
        val state = CodeEditorState(TextFieldValue("abc"))
        state.select(TextRange(2, 99))
        assertEquals(TextRange(2, 3), state.value.selection)
        state.select(TextRange(50, 99))
        assertEquals(TextRange(3, 3), state.value.selection)
        assertFalse(state.canUndo)
    }

    @Test
    fun `typing follows the rules the state was made with`() {
        val state = CodeEditorState(TextFieldValue("f", TextRange(1)), PythonSmartRules)
        state.edit(TextFieldValue("f(", TextRange(2)))
        assertEquals("f()", state.text)
    }

    @Test
    fun `a saved state keeps its rules and, when asked, its history`() {
        val saver = CodeEditorState.saver(PythonSmartRules, keepHistory = true)
        val state = CodeEditorState(TextFieldValue("a", TextRange(1)), PythonSmartRules)
        state.applyEdit(CodeTextEdit(TextRange(1), "b", TextRange(2)))
        state.applyEdit(CodeTextEdit(TextRange(2), "c", TextRange(3)))

        val restored = saver.restore(with(saver) { scope.save(state) }!!)!!

        assertEquals("abc", restored.text)
        assertEquals(TextRange(3), restored.value.selection)
        restored.undo()
        assertEquals("ab", restored.text)
        restored.undo()
        assertEquals("a", restored.text)
        assertFalse(restored.canUndo)
        restored.moveTo(1)
        restored.edit(TextFieldValue("a(", TextRange(2)))
        assertEquals("a()", restored.text)
    }

    @Test
    fun `the plain saver keeps no history`() {
        val state = CodeEditorState(TextFieldValue("a", TextRange(1)))
        state.applyEdit(CodeTextEdit(TextRange(1), "b", TextRange(2)))
        val saver = CodeEditorState.Saver
        val restored = saver.restore(with(saver) { scope.save(state) }!!)!!
        assertEquals("ab", restored.text)
        assertFalse(restored.canUndo)
    }

    @Test
    fun `saved history stops at its size cap`() {
        val big = "x".repeat(20_000)
        val state = CodeEditorState(TextFieldValue(big, TextRange(0)))
        state.applyEdit(CodeTextEdit(TextRange(0), "y", TextRange(1)))
        state.applyEdit(CodeTextEdit(TextRange(0), "z", TextRange(1)))
        val saver = CodeEditorState.saver(CodeSmartRules.Json, keepHistory = true)

        val restored = saver.restore(with(saver) { scope.save(state) }!!)!!

        restored.undo()
        assertEquals("y$big", restored.text)
        assertFalse(restored.canUndo)
    }
}
