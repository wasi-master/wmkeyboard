package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.notes.Note
import com.wasimaster.wmkeyboard.core.notes.NoteSort
import com.wasimaster.wmkeyboard.core.notes.NoteStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class NotesLogicTest {

    private fun caret(text: String, at: Int = text.length, anchor: Int = at) = CaretText(text, at, anchor)

    @Test
    fun `visible lists the shelf, narrows to pinned, searches, and hides locked notes on a locked device`() {
        val store = NoteStore(null)
        val a = store.add("alpha milk", now = 1)
        val b = store.add("beta", now = 2)
        val secret = store.add("milk secret", locked = true, now = 3)
        val archived = store.add("gamma milk", now = 4)
        store.setArchived(archived.id, true)
        store.setPinned(b.id, true)
        val trashed = store.add("milk trashed", now = 5)
        store.trash(trashed.id)

        assertEquals(listOf(b.id, secret.id, a.id), NotesLogic.visible(store, NotesFilter.All, "", NoteSort.UPDATED, deviceLocked = false).map { it.id })
        assertEquals(listOf(b.id, a.id), NotesLogic.visible(store, NotesFilter.All, "", NoteSort.UPDATED, deviceLocked = true).map { it.id })
        assertEquals(listOf(b.id), NotesLogic.visible(store, NotesFilter.Pinned, "", NoteSort.UPDATED, deviceLocked = false).map { it.id })
        // A locked body is never searched; the archive and the trash stay out of a search of All.
        assertEquals(listOf(a.id), NotesLogic.visible(store, NotesFilter.All, "milk", NoteSort.UPDATED, deviceLocked = false).map { it.id })
        assertEquals(listOf(archived.id), NotesLogic.visible(store, NotesFilter.Archived, "milk", NoteSort.UPDATED, deviceLocked = false).map { it.id })
        assertEquals(listOf(trashed.id), NotesLogic.visible(store, NotesFilter.Trash, "", NoteSort.UPDATED, deviceLocked = false).map { it.id })
        assertEquals(NotesCounts(pinned = 1, archived = 1, trash = 1), NotesLogic.counts(store, deviceLocked = false))
    }

    @Test
    fun `enter carries a list on, counts numbers up, and ends a list on an empty item`() {
        assertEquals("- a\n- ", NotesLogic.enter(caret("- a")).text)
        assertEquals("- [x] a\n- [ ] ", NotesLogic.enter(caret("- [x] a")).text)
        assertEquals("2. b\n3. ", NotesLogic.enter(caret("2. b")).text)
        assertEquals("  * a\n  * ", NotesLogic.enter(caret("  * a")).text)
        val ended = NotesLogic.enter(caret("- a\n- "))
        assertEquals("- a\n\n", ended.text)
        assertEquals(ended.text.length, ended.at)
        // Plain prose, and a caret inside the marker, are a plain line break.
        assertEquals("hello\n", NotesLogic.enter(caret("hello")).text)
        assertEquals("\n- a", NotesLogic.enter(caret("- a", at = 0)).text)
        // A caret mid-line splits the item and starts the next one with the rest.
        val split = NotesLogic.enter(caret("- abcd", at = 4))
        assertEquals("- ab\n- cd", split.text)
        assertEquals(7, split.at)
    }

    @Test
    fun `inline markdown wraps a selection, unwraps one already wrapped, and opens a pair at the caret`() {
        val wrapped = NotesLogic.applyMarkdown(caret("say hello now", at = 9, anchor = 4), NoteMarkdownAction.BOLD)
        assertEquals("say **hello** now", wrapped.text)
        assertEquals("**hello**", wrapped.selectedText)
        val unwrapped = NotesLogic.applyMarkdown(wrapped, NoteMarkdownAction.BOLD)
        assertEquals("say hello now", unwrapped.text)
        assertEquals("hello", unwrapped.selectedText)
        val pair = NotesLogic.applyMarkdown(caret("ab", at = 1), NoteMarkdownAction.CODE)
        assertEquals("a``b", pair.text)
        assertEquals(2, pair.at)
        // A selection whose markers sit just outside it is unwrapped too.
        val outside = NotesLogic.applyMarkdown(caret("_x_", at = 2, anchor = 1), NoteMarkdownAction.ITALIC)
        assertEquals("x", outside.text)
    }

    @Test
    fun `line markdown toggles a prefix on every line the selection touches`() {
        val bullets = NotesLogic.applyMarkdown(caret("a\nb\nc", at = 5, anchor = 0), NoteMarkdownAction.BULLET)
        assertEquals("- a\n- b\n- c", bullets.text)
        assertEquals("- a\n- b\n- c", bullets.selectedText)
        assertEquals("a\nb\nc", NotesLogic.applyMarkdown(bullets, NoteMarkdownAction.BULLET).text)
        assertEquals("- [ ] a", NotesLogic.applyMarkdown(caret("- a"), NoteMarkdownAction.TASK).text)
        assertEquals("a", NotesLogic.applyMarkdown(caret("- [x] a"), NoteMarkdownAction.TASK).text)
        val numbered = NotesLogic.applyMarkdown(caret("x\ny", at = 3, anchor = 0), NoteMarkdownAction.NUMBERED)
        assertEquals("1. x\n2. y", numbered.text)
        assertEquals("x\ny", NotesLogic.applyMarkdown(numbered, NoteMarkdownAction.NUMBERED).text)
        // A caret alone works on its line and stays on it.
        val heading = NotesLogic.applyMarkdown(caret("one\ntwo", at = 5), NoteMarkdownAction.HEADING)
        assertEquals("one\n# two", heading.text)
        assertEquals(7, heading.at)
        assertEquals("> q", NotesLogic.applyMarkdown(caret("q"), NoteMarkdownAction.QUOTE).text)
    }

    @Test
    fun `a conflict adopts the store while the draft is clean and keeps the draft once it is dirty`() {
        val original = Note(1, "old", createdAt = 1, updatedAt = 10)
        val clean = NoteEdit(1, isNew = false, original = original)
        val newer = original.copy(body = "from the app", updatedAt = 20)
        val adopted = NotesLogic.resolveConflict(clean, newer)
        assertEquals("from the app", adopted.bodyDraft)
        assertEquals(newer, adopted.original)

        val dirty = clean.copy(bodyDraft = "typed here")
        val kept = NotesLogic.resolveConflict(dirty, newer)
        assertEquals("typed here", kept.bodyDraft)
        assertEquals("old", kept.original.body)
        assertEquals(20, kept.original.updatedAt)

        assertSame(clean, NotesLogic.resolveConflict(clean, original))
        assertSame(dirty, NotesLogic.resolveConflict(dirty, null))
    }

    @Test
    fun `lineStartOffset walks the newlines and stops at the end`() {
        val body = "ab\ncd\n\nef"
        assertEquals(0, NotesLogic.lineStartOffset(body, 0))
        assertEquals(3, NotesLogic.lineStartOffset(body, 1))
        assertEquals(6, NotesLogic.lineStartOffset(body, 2))
        assertEquals(7, NotesLogic.lineStartOffset(body, 3))
        assertEquals(body.length, NotesLogic.lineStartOffset(body, 9))
    }

    @Test
    fun `passages come from the listed notes in order, never from a locked one, and stop at the limit`() {
        val store = NoteStore(null)
        val quotes = store.add("Intro.\n\nTo be or not to be.\n\nThe end.", title = "Quotes", now = 1)
        val diary = store.add("be quiet", now = 2, locked = true)
        val other = store.add("let it be", now = 3)
        val items = listOf(other, diary, quotes).map { store.item(it.id)!! }

        val hits = NotesLogic.passages(items, "be")
        assertEquals(listOf(other.id, quotes.id), hits.map { it.noteId })
        assertEquals("To be or not to be.", hits[1].text)
        assertEquals("be", hits[1].text.substring(hits[1].matchStart, hits[1].matchEnd))
        assertEquals("Quotes", hits[1].title)
        assertEquals(1, NotesLogic.passages(items, "be", limit = 1).size)
        assertEquals(emptyList<NotePassageHit>(), NotesLogic.passages(items, "b"))
    }
}
