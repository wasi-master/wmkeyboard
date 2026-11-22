package com.wasimaster.wmkeyboard.core.notes

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoteStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val t0 = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `add update trash restore round trip`() {
        val store = NoteStore(null)
        val note = store.add("Buy milk", now = t0)
        assertEquals(1, store.items().size)
        assertEquals(t0, note.createdAt)
        assertEquals(t0, note.updatedAt)

        val edited = store.update(note.copy(body = "Buy milk and eggs"), now = t0 + 1)!!
        assertEquals(note.id, edited.id)
        assertEquals(t0, edited.createdAt)
        assertEquals(t0 + 1, edited.updatedAt)

        store.trash(note.id, now = t0 + 2)
        assertTrue(store.items().isEmpty())
        // Listing the trash purges by age against the clock it is given.
        assertEquals(1, store.items(NoteFilter(NoteShelf.TRASH), now = t0 + 3).size)
        store.restore(note.id)
        assertEquals("Buy milk and eggs", store.items().single().body)
        assertNull(store.items().single().deletedAt)
    }

    @Test
    fun `a flag flip does not move updatedAt but a content edit does`() {
        val store = NoteStore(null)
        val note = store.add("text", now = t0)
        store.setPinned(note.id, true)
        store.setArchived(note.id, true)
        assertEquals(t0, store.item(note.id)!!.updatedAt)
        store.update(store.item(note.id)!!, now = t0 + 5)
        assertEquals(t0, store.item(note.id)!!.updatedAt)
        store.update(store.item(note.id)!!.copy(title = "A title"), now = t0 + 6)
        assertEquals(t0 + 6, store.item(note.id)!!.updatedAt)
    }

    @Test
    fun `update keeps the stored trash stamp and the stored id`() {
        val store = NoteStore(null)
        val note = store.add("text", now = t0)
        store.trash(note.id, now = t0 + 1)
        val edited = store.update(note.copy(body = "changed", deletedAt = null), now = t0 + 2)!!
        assertEquals(t0 + 1, edited.deletedAt)
        assertNull(store.update(Note(id = 999, body = "nobody")))
    }

    @Test
    fun `saves and reloads through the file with no part file left behind`() {
        val file = File(tmp.root, "notes/notes.json")
        val store = NoteStore(file)
        store.add("hello", title = "Hi", tags = listOf("Work"), now = t0)
        store.save()
        assertTrue(file.exists())
        assertFalse(File(file.parentFile, file.name + ".part").exists())

        val again = NoteStore(file)
        val note = again.items().single()
        assertEquals("hello", note.body)
        assertEquals("Hi", note.title)
        assertEquals(listOf("Work"), note.tags)
        assertEquals(listOf("Work"), again.tags().map { it.name })
    }

    @Test
    fun `the trash is purged by age on reload and when listed`() {
        val file = File(tmp.root, "notes.json")
        val store = NoteStore(file)
        val old = store.add("old", now = t0)
        val young = store.add("young", now = t0)
        store.trash(old.id, now = t0)
        store.trash(young.id, now = t0 + 29 * day)
        assertEquals(2, store.items(NoteFilter(NoteShelf.TRASH), now = t0 + 29 * day + 1).size)
        val listed = store.items(NoteFilter(NoteShelf.TRASH), now = t0 + 31 * day)
        assertEquals(listOf(young.id), listed.map { it.id })
        store.trashRetentionMillis = 0
        assertEquals(0, store.purgeTrash(now = t0 + 400 * day))
        assertEquals(1, store.items(NoteFilter(NoteShelf.TRASH), now = t0 + 400 * day).size)
    }

    @Test
    fun `restore clears the stamp so a purge leaves the note alone`() {
        val store = NoteStore(null)
        val note = store.add("keep", now = t0)
        store.trash(note.id, now = t0)
        store.restore(note.id)
        assertEquals(0, store.purgeTrash(now = t0 + 365 * day))
        assertEquals(1, store.items().size)
    }

    @Test
    fun `tags are cleaned, deduped ignoring case, capped and recased to the list`() {
        val store = NoteStore(null)
        store.addTag("Work", colour = 6)
        val note = store.add("x", tags = listOf(" #work ", "WORK", "school, stuff", "", "a".repeat(60)) + (1..20).map { "t$it" })
        assertEquals("Work", note.tags[0])
        assertEquals("school stuff", note.tags[1])
        assertEquals("a".repeat(40), note.tags[2])
        assertEquals(NoteStore.MAX_TAGS_PER_NOTE, note.tags.size)
        // Every tag on the note exists in the list now, the suggested-chip rule.
        assertTrue(note.tags.all { store.tag(it) != null })
        assertEquals(6, store.tag("work")!!.colour)
    }

    @Test
    fun `addTag returns the existing tag and refuses blanks`() {
        val store = NoteStore(null)
        val first = store.addTag("Ideas", colour = 3)!!
        assertEquals(first, store.addTag("ideas", colour = 5))
        assertEquals(1, store.tags().size)
        assertNull(store.addTag("  #  "))
        assertNull(store.addTag(","))
    }

    @Test
    fun `renameTag recases, merges on a collision and rewrites every note`() {
        val store = NoteStore(null)
        store.addTag("work", colour = 6)
        store.addTag("Job", colour = 2)
        val a = store.add("a", tags = listOf("work"))
        val b = store.add("b", tags = listOf("job", "work"))
        val trashed = store.add("c", tags = listOf("work"))
        store.trash(trashed.id)

        assertTrue(store.renameTag("work", "Work"))
        assertEquals(listOf("Work"), store.item(a.id)!!.tags)
        assertEquals("Work", store.tag("work")!!.name)

        assertTrue(store.renameTag("Work", "job"))
        assertEquals(listOf("Job"), store.tags().map { it.name })
        assertEquals(2, store.tag("Job")!!.colour)
        assertEquals(listOf("Job"), store.item(a.id)!!.tags)
        assertEquals(listOf("Job"), store.item(b.id)!!.tags)
        assertEquals(listOf("Job"), store.item(trashed.id)!!.tags)
        assertFalse(store.renameTag("nothing", "x"))
        assertFalse(store.renameTag("Job", "   "))
    }

    @Test
    fun `removeTag strips the trash too and setTagColour clamps`() {
        val store = NoteStore(null)
        val a = store.add("a", tags = listOf("Gone"))
        val t = store.add("t", tags = listOf("Gone", "Kept"))
        store.trash(t.id)
        assertEquals(2, store.removeTag("gone"))
        assertTrue(store.item(a.id)!!.tags.isEmpty())
        assertEquals(listOf("Kept"), store.item(t.id)!!.tags)
        assertEquals(0, store.removeTag("gone"))
        store.setTagColour("Kept", 99)
        assertEquals(0, store.tag("Kept")!!.colour)
        store.setTagColour("Kept", 4)
        assertEquals(4, store.tag("Kept")!!.colour)
    }

    @Test
    fun `locked notes hide on request and their bodies are not searched`() {
        val store = NoteStore(null)
        store.add("secret plan", title = "Diary", locked = true)
        store.add("public plan")
        assertEquals(1, store.items(NoteFilter(includeLocked = false)).size)
        assertEquals(1, store.search("plan").size)
        assertEquals(2, store.search("plan", searchLockedBodies = true).size)
        // The title and the tags of a locked note still match; its body never names it.
        assertEquals(1, store.search("diary").size)
        assertEquals("", Note(1, "first line\nmore", locked = true).displayTitle())
        assertEquals("Mine", Note(1, "first line", title = "Mine", locked = true).displayTitle())
    }

    @Test
    fun `body and title are capped without splitting a surrogate pair`() {
        val store = NoteStore(null)
        val body = "a".repeat(NoteStore.MAX_BODY_CHARS - 1) + "😀" + "tail"
        val note = store.add(body, title = "t".repeat(300))
        assertEquals(NoteStore.MAX_BODY_CHARS - 1, note.body.length)
        assertEquals(NoteStore.MAX_TITLE_LENGTH, note.title.length)
    }

    @Test
    fun `carriage returns and a BOM are normalised on the way in`() {
        val store = NoteStore(null)
        val note = store.add("﻿one\r\ntwo\rthree", title = "a\nb")
        assertEquals("one\ntwo\nthree", note.body)
        assertEquals("a b", note.title)
    }

    @Test
    fun `two adds at the same instant get distinct ids and reload continues past them`() {
        val file = File(tmp.root, "n.json")
        val store = NoteStore(file)
        val a = store.add("a", now = t0)
        val b = store.add("b", now = t0)
        assertNotEquals(a.id, b.id)
        store.save()
        val again = NoteStore(file)
        val c = again.add("c", now = t0)
        assertTrue(c.id > maxOf(a.id, b.id))
    }

    @Test
    fun `displayTitle comes from the title, a heading, a task line or nothing`() {
        assertEquals("Mine", Note(1, "# Not this", title = "Mine").displayTitle())
        assertEquals("Shopping", Note(1, "# Shopping\n- milk").displayTitle())
        assertEquals("milk", Note(1, "\n- [ ] **milk**\n- eggs").displayTitle())
        assertEquals("", Note(1, "  \n\n").displayTitle())
        assertEquals("- milk", Note(1, "# Shopping\n- milk").previewBody())
        assertEquals("a".repeat(80), Note(1, "a".repeat(100)).displayTitle())
    }

    @Test
    fun `previews skip the title line, trim, and copy only what a row can show`() {
        assertEquals("", Note(1, "only a title line").previewBody())
        assertEquals("", Note(1, " \n \n").previewBody())
        assertEquals("body", Note(1, "\r\n# Head\r\n\n  body \n\n").previewBody())
        assertEquals("first\nsecond", Note(1, "\nfirst\nsecond\n", title = "Typed").previewBody())
        assertEquals("abc", Note(1, "head\nabcdef").previewBody(limit = 3))
        // A surrogate pair is not split at the cut.
        assertEquals("a", Note(1, "head\na😀b").previewBody(limit = 2))

        // One line hundreds of kilobytes long, as a pasted file leaves it (#1466).
        val huge = Note(1, "https://example.com/file\n" + "x".repeat(300_000))
        assertEquals(3 * Note.PREVIEW_CHARS_PER_LINE, huge.preview(lines = 3).length)
        assertEquals("one\ntwo\nthree", Note(1, "head\none\ntwo\nthree\nfour\nfive").preview(lines = 2))
    }

    @Test
    fun `lists are pinned first then by the chosen sort, the trash newest-deleted first`() {
        val store = NoteStore(null)
        val old = store.add("old", title = "Zed", now = t0)
        val mid = store.add("mid", title = "Alpha", now = t0 + 1)
        val new = store.add("new", title = "Mid", now = t0 + 2)
        store.setPinned(old.id, true)
        assertEquals(listOf(old.id, new.id, mid.id), store.items(sort = NoteSort.UPDATED).map { it.id })
        assertEquals(listOf(old.id, mid.id, new.id), store.items(sort = NoteSort.TITLE).map { it.id })
        assertEquals(listOf(old.id, new.id, mid.id), store.items(sort = NoteSort.CREATED).map { it.id })
        store.trash(mid.id, now = t0 + 10)
        store.trash(old.id, now = t0 + 20)
        assertEquals(listOf(old.id, mid.id), store.items(NoteFilter(NoteShelf.TRASH), now = t0 + 21).map { it.id })
    }

    @Test
    fun `shelves and a tag filter select what they say`() {
        val store = NoteStore(null)
        val a = store.add("a", tags = listOf("Work"))
        val b = store.add("b", tags = listOf("Home"))
        store.setArchived(b.id, true)
        assertEquals(listOf(a.id), store.items().map { it.id })
        assertEquals(listOf(b.id), store.items(NoteFilter(NoteShelf.ARCHIVED)).map { it.id })
        assertEquals(listOf(a.id), store.items(NoteFilter(tag = "work")).map { it.id })
        assertEquals(mapOf("Work" to 1, "Home" to 0), store.tagCounts())
        assertEquals(1, store.count(NoteFilter(NoteShelf.ARCHIVED)))
    }

    @Test
    fun `unknown keys load and an unknown tag colour falls back to the accent`() {
        val file = File(tmp.root, "n.json")
        file.writeText(
            """{"version":1,"future":true,"notes":[{"id":5,"body":"b","tags":["WORK"],"createdAt":1,"updatedAt":1,"extra":"x"}],""" +
                """"tags":[{"name":"Work","colour":42}]}""",
        )
        val store = NoteStore(file)
        assertEquals(listOf("Work"), store.items().single().tags)
        assertEquals(0, store.tag("Work")!!.colour)
    }

    @Test
    fun `reloadIfChanged picks up another store's save and ignores its own`() {
        val file = File(tmp.root, "n.json")
        val mine = NoteStore(file)
        mine.add("mine")
        mine.save()
        assertFalse(mine.reloadIfChanged())

        val theirs = NoteStore(file)
        theirs.add("theirs")
        theirs.save()
        // Two writes inside one modification-time tick are indistinguishable;
        // push the stamp so the test does not depend on the file system's clock.
        file.setLastModified(file.lastModified() + 5000)
        assertTrue(mine.reloadIfChanged())
        assertEquals(2, mine.items().size)
        assertFalse(mine.reloadIfChanged())
    }

    @Test
    fun `setTask rewrites one line and bumps updatedAt, a stale index is refused`() {
        val store = NoteStore(null)
        val note = store.add("- [ ] milk\n- [x] eggs\nprose", now = t0)
        val ticked = store.setTask(note.id, 0, checked = true, now = t0 + 1)!!
        assertEquals("- [x] milk\n- [x] eggs\nprose", ticked.body)
        assertEquals(t0 + 1, ticked.updatedAt)
        assertNull(store.setTask(note.id, 2, checked = true))
        assertNull(store.setTask(note.id, 9, checked = true))
    }

    @Test
    fun `append adds a blank line between, or nothing to an empty body`() {
        val store = NoteStore(null)
        val note = store.add("", now = t0)
        assertEquals("first", store.append(note.id, "first", now = t0 + 1)!!.body)
        assertEquals("first\n\nsecond", store.append(note.id, "second", now = t0 + 2)!!.body)
        assertEquals(note.id, store.lastEdited()!!.id)
    }

    @Test
    fun `emptyTrash and deleteForever remove for good`() {
        val store = NoteStore(null)
        val a = store.add("a")
        val b = store.add("b")
        store.trash(a.id)
        assertEquals(1, store.emptyTrash())
        assertNull(store.item(a.id))
        assertTrue(store.deleteForever(b.id))
        assertFalse(store.deleteForever(b.id))
        assertNotNull(store.add("c"))
    }
}
