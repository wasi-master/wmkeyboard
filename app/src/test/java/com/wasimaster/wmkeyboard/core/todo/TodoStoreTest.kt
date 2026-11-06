package com.wasimaster.wmkeyboard.core.todo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TodoStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val t0 = 1_700_000_000_000L

    @Test
    fun `remove copies keeps the oldest and remembers the copies the task app holds`() {
        val store = TodoStore(null)
        val first = store.add(TodoTask(id = 0, text = "Buy milk", uid = "a", createdAt = t0), now = t0)!!
        store.add(TodoTask(id = 0, text = "Step", parentId = first.id, createdAt = t0), now = t0)
        val copy = store.add(TodoTask(id = 0, text = "Buy milk", uid = "b", createdAt = t0 + 1), now = t0 + 1)!!
        store.add(TodoTask(id = 0, text = "Step", parentId = copy.id, uid = "c", createdAt = t0 + 1), now = t0 + 1)
        store.add(TodoTask(id = 0, text = "Buy milk", done = true, createdAt = t0 + 2), now = t0 + 2)

        val gone = store.removeCopies()

        assertEquals(setOf("b", "c"), gone.mapNotNull { it.uid }.toSet())
        assertEquals(setOf("b", "c"), store.pendingDeletes())
        assertEquals(3, store.size())
        assertEquals(listOf("Step"), store.subtasks(first.id).map { it.text })
        assertTrue(store.removeCopies().isEmpty())
    }

    @Test
    fun `add check and delete round trip`() {
        val store = TodoStore(null)
        val task = store.add("Buy milk", now = t0)!!
        assertEquals("Buy milk", task.text)
        assertEquals(t0, task.createdAt)
        assertEquals(1, store.openCount())

        val ticked = store.setDone(task.id, true, now = t0 + 1)
        assertEquals(listOf(task.id), ticked.map { it.id })
        assertTrue(store.item(task.id)!!.done)
        assertEquals(t0 + 1, store.item(task.id)!!.completedAt)
        assertEquals(0, store.openCount())

        store.setDone(task.id, false, now = t0 + 2)
        assertNull(store.item(task.id)!!.completedAt)

        val gone = store.delete(task.id)
        assertEquals(listOf(task.id), gone.map { it.id })
        assertNull(store.item(task.id))
    }

    @Test
    fun `blank text is refused and text is one trimmed line`() {
        val store = TodoStore(null)
        assertNull(store.add("   ", now = t0))
        val task = store.add("  two\nlines  ", now = t0)!!
        assertEquals("two lines", task.text)
        assertNull(store.setText(task.id, " "))
        assertEquals("two lines", store.item(task.id)!!.text)
    }

    @Test
    fun `steps go one level deep`() {
        val store = TodoStore(null)
        val parent = store.add("Trip", now = t0)!!
        val step = store.add("Pack", parentId = parent.id, now = t0 + 1)!!
        val nested = store.add("Socks", parentId = step.id, now = t0 + 2)!!
        assertEquals(parent.id, step.parentId)
        // A step asked for under a step lands under the top task.
        assertEquals(parent.id, nested.parentId)
        assertEquals(listOf("Pack", "Socks"), store.subtasks(parent.id).map { it.text })
        assertEquals(listOf(parent.id), store.topLevel().map { it.id })
        assertNull(store.add("Orphan", parentId = 12345L, now = t0))
    }

    @Test
    fun `ticking a task ticks its open steps and clearing a step clears the task`() {
        val store = TodoStore(null)
        val parent = store.add("Trip", now = t0)!!
        val a = store.add("Pack", parentId = parent.id, now = t0 + 1)!!
        val b = store.add("Book", parentId = parent.id, now = t0 + 2)!!
        store.setDone(a.id, true, now = t0 + 3)
        assertEquals(TodoProgress(1, 2), store.progress(parent.id))

        store.setDone(parent.id, true, now = t0 + 4)
        assertTrue(store.item(b.id)!!.done)
        assertEquals(TodoProgress(2, 2), store.progress(parent.id))
        // The step ticked earlier keeps its own time.
        assertEquals(t0 + 3, store.item(a.id)!!.completedAt)

        store.setDone(b.id, false, now = t0 + 5)
        assertFalse(store.item(parent.id)!!.done)
        assertTrue(store.item(a.id)!!.done)
    }

    @Test
    fun `deleting a task takes its steps and restore puts them back`() {
        val store = TodoStore(null)
        val parent = store.add("Trip", now = t0)!!
        store.add("Pack", parentId = parent.id, now = t0 + 1)!!
        val other = store.add("Milk", now = t0 + 2)!!
        val gone = store.delete(parent.id)
        assertEquals(2, gone.size)
        assertEquals(listOf(other.id), store.all().map { it.id })

        store.restore(gone)
        assertEquals(3, store.size())
        assertEquals(1, store.subtasks(parent.id).size)
        // A second restore of the same tasks adds nothing.
        store.restore(gone)
        assertEquals(3, store.size())
    }

    @Test
    fun `clear done takes finished tasks and finished steps`() {
        val store = TodoStore(null)
        val open = store.add("Open", now = t0)!!
        val openStep = store.add("Open step", parentId = open.id, now = t0 + 1)!!
        val doneStep = store.add("Done step", parentId = open.id, now = t0 + 2)!!
        val finished = store.add("Finished", now = t0 + 3)!!
        store.add("Its step", parentId = finished.id, now = t0 + 4)
        store.setDone(doneStep.id, true, now = t0 + 5)
        store.setDone(finished.id, true, now = t0 + 6)

        val gone = store.clearDone()
        assertEquals(3, gone.size)
        assertEquals(setOf(open.id, openStep.id), store.all().map { it.id }.toSet())
    }

    @Test
    fun `list order is open first, soonest due, then newest, finished last`() {
        val store = TodoStore(null)
        val old = store.add("Old", now = t0)!!
        val new = store.add("New", now = t0 + 10)!!
        val later = store.add("Due later", due = t0 + 5_000, now = t0 + 1)!!
        val soon = store.add("Due soon", due = t0 + 1_000, now = t0 + 2)!!
        val done1 = store.add("Done first", now = t0 + 3)!!
        val done2 = store.add("Done second", now = t0 + 4)!!
        store.setDone(done1.id, true, now = t0 + 20)
        store.setDone(done2.id, true, now = t0 + 30)

        assertEquals(
            listOf(soon.id, later.id, new.id, old.id, done2.id, done1.id),
            store.topLevel().map { it.id },
        )
        assertEquals(listOf(soon.id, later.id, new.id, old.id), store.topLevel(hideDone = true).map { it.id })
    }

    @Test
    fun `a delete of a linked task is remembered for the task app until restored`() {
        val store = TodoStore(null)
        val task = store.add(TodoTask(id = 0, text = "Linked", uid = "uid-1"), now = t0)!!
        val gone = store.delete(task.id)
        assertEquals(setOf("uid-1"), store.pendingDeletes())
        store.restore(gone)
        assertTrue(store.pendingDeletes().isEmpty())
        // Clear all is this device's list only, never a delete in the task app.
        store.clearAll()
        assertTrue(store.pendingDeletes().isEmpty())
    }

    @Test
    fun `saves and reloads through the file`() {
        val file = File(tmp.root, TodoStore.FILE_PATH)
        val store = TodoStore(file)
        val parent = store.add("Trip", due = t0 + 100, dueAllDay = true, now = t0)!!
        store.add("Pack", parentId = parent.id, now = t0 + 1)
        store.setDone(parent.id, true, now = t0 + 2)
        store.save()

        val again = TodoStore(file)
        assertEquals(2, again.size())
        val read = again.item(parent.id)
        assertNotNull(read)
        assertTrue(read!!.done)
        assertTrue(read.dueAllDay)
        assertEquals(t0 + 100, read.due)
        assertEquals(1, again.subtasks(parent.id).size)
        // A new id after a reload never collides with a stored one.
        val next = again.add("Next", now = t0)!!
        assertTrue(again.all().count { it.id == next.id } == 1)
    }

    @Test
    fun `reloadIfChanged sees the other process's write and not its own`() {
        val file = File(tmp.root, TodoStore.FILE_PATH)
        val mine = TodoStore(file)
        mine.add("Mine", now = t0)
        mine.save()
        assertFalse(mine.reloadIfChanged())

        val theirs = TodoStore(file)
        theirs.add("Theirs", now = t0 + 1)
        theirs.save()
        file.setLastModified(file.lastModified() + 2_000)
        assertTrue(mine.reloadIfChanged())
        assertEquals(2, mine.size())
    }

    @Test
    fun `a step whose parent is gone loads as a top-level task`() {
        val file = File(tmp.root, TodoStore.FILE_PATH)
        file.parentFile!!.mkdirs()
        file.writeText(
            """{"version":1,"tasks":[{"id":5,"text":"Lost step","parentId":4,"createdAt":1,"updatedAt":1}]}""",
        )
        val store = TodoStore(file)
        assertNull(store.item(5)!!.parentId)
        assertEquals(listOf(5L), store.topLevel().map { it.id })
    }

    @Test
    fun `overdue counts an all-day task from the next day`() {
        val day = TodoTask.DAY_MS
        val timed = TodoTask(id = 1, text = "a", due = t0)
        val allDay = TodoTask(id = 2, text = "b", due = t0, dueAllDay = true)
        assertTrue(timed.overdue(t0 + 1))
        assertFalse(allDay.overdue(t0 + 1))
        assertTrue(allDay.overdue(t0 + day))
        assertFalse(timed.copy(done = true).overdue(t0 + day))
    }
}
