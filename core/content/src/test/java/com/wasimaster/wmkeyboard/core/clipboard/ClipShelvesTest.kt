package com.wasimaster.wmkeyboard.core.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Named clipboards (#1542) in the store: moving, keeping, clearing and deleting. */
class ClipShelvesTest {

    private fun store() = ClipboardStore(storageFile = null, expiryMillis = 1_000L, maxItems = 2)

    @Test
    fun `a moved clip leaves the main clipboard and goes to the top of the other`() {
        val store = store()
        val a = store.add("alpha", now = 10)!!
        val b = store.add("beta", now = 20)!!
        assertEquals(2, store.moveToShelf(listOf(a.id, b.id), "work", now = 100))
        val moved = store.items(now = 100).filter { it.shelf == "work" }
        // Their order is kept: beta was newer, and still is.
        assertEquals(listOf("beta", "alpha"), moved.map { it.text })
        assertTrue(moved.all { it.timestamp in 99..100 })
    }

    @Test
    fun `a named clipboard's clips never expire and do not count towards the cap`() {
        val store = store()
        val kept = store.add("keep me", now = 0)!!
        store.moveToShelf(listOf(kept.id), "archive", now = 0)
        store.add("one", now = 5_000)
        store.add("two", now = 5_001)
        store.add("three", now = 5_002)
        val items = store.items(now = 5_500)
        assertTrue(items.any { it.text == "keep me" })
        // The cap of two still holds for the main clipboard.
        assertEquals(2, items.count { it.shelf.isEmpty() })
        assertNull(items.first { it.text == "keep me" }.expiresAt(1_000L, 0L))
    }

    @Test
    fun `a copy of text kept in a named clipboard lands in the main one`() {
        val store = store()
        val a = store.add("same", now = 10)!!
        store.moveToShelf(listOf(a.id), "work", now = 20)
        val again = store.add("same", now = 30)!!
        assertEquals("", again.shelf)
        assertEquals(2, store.items(now = 30).count { it.text == "same" })
    }

    @Test
    fun `clearing one clipboard leaves the others`() {
        val store = store()
        val a = store.add("alpha", now = 10)!!
        store.add("beta", now = 20)
        store.moveToShelf(listOf(a.id), "work", now = 30)
        store.clearUnpinned { it.shelf == "work" }
        val left = store.items(now = 30)
        assertEquals(listOf("beta"), left.map { it.text })
    }

    @Test
    fun `a deleted clipboard takes its clips with it`() {
        val store = store()
        val a = store.add("alpha", now = 10)!!
        store.add("beta", now = 20)
        store.moveToShelf(listOf(a.id), "work", now = 30)
        assertEquals(1, store.dropShelves(setOf("work")).size)
        assertFalse(store.items(now = 30).any { it.text == "alpha" })
        assertTrue(store.dropShelves(setOf("")).isEmpty())
    }

    @Test
    fun `a clip from an unknown clipboard shows in the main one`() {
        val clip = ClipItem(id = 1, text = "x", timestamp = 0, shelf = "gone")
        assertEquals("", clip.shelfAmong(setOf("work")))
        assertEquals("gone", clip.shelfAmong(setOf("gone")))
    }
}
