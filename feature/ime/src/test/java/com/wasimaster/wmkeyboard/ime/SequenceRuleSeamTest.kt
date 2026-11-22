package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.layout.CompiledSequenceRules
import com.wasimaster.wmkeyboard.core.layout.SequenceRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a typing rule's edit (#1493) reaches the buffer or the field, and what its Backspace undo does. */
class SequenceRuleSeamTest {

    private val pinyin = CompiledSequenceRules.of(
        listOf(
            CompiledSequenceRules.compile(
                mapOf("plain" to listOf("a"), "tone1" to listOf("ā")),
                listOf(SequenceRule("{plain}1", "{tone1:plain}", undo = true)),
            ),
        ),
    )!!

    @Test
    fun `an edit inside the word being typed rewrites the buffer`() {
        val edit = pinyin.edit("ma", "1")!!
        assertEquals(SequenceRuleSeam.RewriteBuffer(keep = 1, text = "ā"), SequenceRuleSeam.plan(edit, composingLength = 2))
    }

    @Test
    fun `an edit reaching past the buffer commits it first`() {
        val swap = CompiledSequenceRules.of(listOf(CompiledSequenceRules.compile(emptyMap(), listOf(SequenceRule("ab", "X")))))!!
        val edit = swap.edit("a", "b")!!
        // The a is committed text and the buffer is empty: an edit of the field.
        assertEquals(SequenceRuleSeam.EditField(commitFirst = false, delete = 1, text = "X"), SequenceRuleSeam.plan(edit, 0))
        // A buffer shorter than the delete is committed first.
        val long = CompiledSequenceRules.of(listOf(CompiledSequenceRules.compile(emptyMap(), listOf(SequenceRule("xab", "Y")))))!!
        assertEquals(
            SequenceRuleSeam.EditField(commitFirst = true, delete = 2, text = "Y"),
            SequenceRuleSeam.plan(long.edit("xa", "b")!!, composingLength = 1),
        )
    }

    @Test
    fun `a spelling buffer or a caret inside the buffer is not read`() {
        assertTrue(SequenceRuleSeam.bufferReadable(0, transliterating = true, readingCaret = -1))
        assertFalse(SequenceRuleSeam.bufferReadable(3, transliterating = true, readingCaret = -1))
        assertTrue(SequenceRuleSeam.bufferReadable(3, transliterating = false, readingCaret = -1))
        assertFalse(SequenceRuleSeam.bufferReadable(3, transliterating = false, readingCaret = 1))
    }

    @Test
    fun `backspace puts back what the rule took only while it is still there`() {
        val edit = pinyin.edit("ma", "1")!!
        assertEquals("a1", edit.restore)
        assertEquals(1 to "a1", SequenceRuleSeam.undo("mā", edit.text, edit.restore!!))
        assertNull(SequenceRuleSeam.undo("mā ", edit.text, edit.restore!!))
        assertNull(SequenceRuleSeam.undo(null, edit.text, edit.restore!!))
    }

    @Test
    fun `a rewind puts back the text the edit took out`() {
        val edit = pinyin.edit("ma", "1")!!
        // A multitap step winds back to the text before the key: the a, not a1.
        assertEquals(1 to "a", SequenceRuleSeam.undo("mā", edit.text, edit.deleted))
    }
}
