package com.wasimaster.wmkeyboard.core.snippets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Folder-wide edits of snippet options (#1122). */
class SnippetBulkEditTest {

    private fun word(id: Long, folder: Long, immediate: Boolean = false) = Snippet(
        id = id, label = "s$id", text = "text$id", trigger = "t$id", folderId = folder, immediate = immediate,
    )

    @Test
    fun `an empty edit changes nothing`() {
        val s = word(1, 1)
        assertTrue(SnippetBulkEdit().isEmpty)
        assertEquals(s, SnippetBulkEdit().applyTo(s))
    }

    @Test
    fun `a null option leaves each snippet's own value`() {
        val s = word(1, 1).copy(confirm = true)
        val out = SnippetBulkEdit(propagateCase = true).applyTo(s)
        assertTrue(out.propagateCase)
        assertTrue(out.confirm)
    }

    @Test
    fun `a pattern snippet takes no capitals and no early expansion`() {
        val pattern = Snippet(id = 1, label = "p", text = "x", triggerPattern = "(\\d+)kg")
        val out = SnippetBulkEdit(propagateCase = true, immediate = true, confirm = true).applyTo(pattern)
        assertFalse(out.propagateCase)
        assertFalse(out.immediate)
        assertTrue(out.confirm)
    }

    @Test
    fun `asking first turns off the early expansion, as the editor does`() {
        val out = SnippetBulkEdit(confirm = true).applyTo(word(1, 1, immediate = true).copy(insideWords = true))
        assertFalse(out.immediate)
        assertFalse(out.insideWords)
    }

    @Test
    fun `the store edits one folder only and counts what changed`() {
        val store = SnippetStore(null)
        val folder = store.addFolder("Fixes")
        val a = store.add(word(0, folder.id))
        val b = store.add(word(0, folder.id).copy(propagateCase = true))
        val other = store.add(word(0, 0))
        val changed = store.editFolder(folder.id, SnippetBulkEdit(propagateCase = true))
        assertEquals(1, changed)
        val byId = store.items().associateBy { it.id }
        assertTrue(byId.getValue(a.id).propagateCase)
        assertTrue(byId.getValue(b.id).propagateCase)
        assertFalse(byId.getValue(other.id).propagateCase)
    }
}
