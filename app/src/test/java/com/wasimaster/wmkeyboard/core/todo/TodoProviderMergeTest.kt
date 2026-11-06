package com.wasimaster.wmkeyboard.core.todo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoProviderMergeTest {

    private val now = 1_700_000_000_000L
    private var nextId = 100L
    private val fresh: () -> Long = { nextId++ }

    private fun task(id: Long, text: String, uid: String? = null, updated: Long = 1, parent: Long? = null, done: Boolean = false) =
        TodoTask(id = id, text = text, uid = uid, createdAt = 1, updatedAt = updated, parentId = parent, done = done)

    private fun row(rowId: Long, uid: String, title: String, version: Long = 1, parent: Long? = null, done: Boolean = false, modified: Long = 0) =
        RemoteTask(rowId = rowId, uid = uid, title = title, done = done, version = version, parentRowId = parent, modified = modified)

    @Test
    fun `a first sync pushes local tasks parents first and pulls remote ones`() {
        val local = listOf(task(2, "Step", parent = 1), task(1, "Top"))
        val remote = listOf(row(10, "r1", "From the server"), row(11, "r2", "Its step", parent = 10))
        val plan = TodoProviderMerge.plan(local, emptySet(), remote, TodoSyncState(), fresh, now)

        assertEquals(listOf(1L, 2L), plan.inserts)
        assertEquals(2, plan.pulled)
        val pulledTop = plan.local.single { it.uid == "r1" }
        val pulledStep = plan.local.single { it.uid == "r2" }
        assertEquals("From the server", pulledTop.text)
        assertEquals(pulledTop.id, pulledStep.parentId)
        assertTrue(plan.updates.isEmpty())
        assertTrue(plan.deletes.isEmpty())
    }

    @Test
    fun `a remote edit comes in and a local edit goes out`() {
        val state = TodoSyncState(seen = mapOf("a" to 1L, "b" to 1L), pushed = mapOf("a" to 5L, "b" to 5L))
        val local = listOf(task(1, "A here", uid = "a", updated = 5), task(2, "B edited here", uid = "b", updated = 9))
        val remote = listOf(row(10, "a", "A edited there", version = 2), row(11, "b", "B", version = 1))
        val plan = TodoProviderMerge.plan(local, emptySet(), remote, state, fresh, now)

        assertEquals("A edited there", plan.local.single { it.id == 1L }.text)
        assertEquals(mapOf(11L to 2L), plan.updates)
    }

    @Test
    fun `when both sides changed the later edit wins`() {
        val state = TodoSyncState(seen = mapOf("a" to 1L), pushed = mapOf("a" to 5L))
        val local = listOf(task(1, "Here", uid = "a", updated = 50))
        val newer = TodoProviderMerge.plan(local, emptySet(), listOf(row(10, "a", "There", version = 2, modified = 60)), state, fresh, now)
        assertEquals("There", newer.local.single().text)

        val older = TodoProviderMerge.plan(local, emptySet(), listOf(row(10, "a", "There", version = 2, modified = 40)), state, fresh, now)
        assertEquals("Here", older.local.single().text)
        assertEquals(mapOf(10L to 1L), older.updates)
    }

    @Test
    fun `a delete here deletes the row and a delete there deletes the task`() {
        val state = TodoSyncState(seen = mapOf("gone-here" to 1L, "gone-there" to 1L, "kept" to 1L))
        val local = listOf(
            task(1, "Gone there", uid = "gone-there"),
            task(2, "Its step", parent = 1),
            task(3, "Kept", uid = "kept"),
        )
        val remote = listOf(row(10, "gone-here", "Deleted here"), row(12, "kept", "Kept"))
        val plan = TodoProviderMerge.plan(local, setOf("gone-here"), remote, state, fresh, now)

        assertEquals(listOf(10L), plan.deletes)
        assertEquals(listOf(3L), plan.local.map { it.id })
        assertEquals(2, plan.removed)
    }

    @Test
    fun `a missing task is never read as a delete of the row`() {
        // "Delete all" here, or a fresh install: the rows come back rather than going.
        val state = TodoSyncState(seen = mapOf("a" to 1L))
        val plan = TodoProviderMerge.plan(emptyList(), emptySet(), listOf(row(10, "a", "Still there")), state, fresh, now)
        assertTrue(plan.deletes.isEmpty())
        assertEquals("Still there", plan.local.single().text)
    }

    @Test
    fun `a linked task the task app has never seen is left alone`() {
        // It came from another phone through sync, before DAVx5 brought it here.
        val local = listOf(task(1, "From the other phone", uid = "elsewhere"))
        val plan = TodoProviderMerge.plan(local, emptySet(), emptyList(), TodoSyncState(), fresh, now)
        assertEquals(1, plan.local.size)
        assertTrue(plan.inserts.isEmpty())
        assertEquals(0, plan.removed)
    }

    @Test
    fun `a task the provider kept no UID for takes its row instead of going in again`() {
        // #1164: the row went in without a UID, DAVx5 named it, and it comes back.
        val local = listOf(task(1, "Buy milk"), task(2, "Call mum", done = true))
        val remote = listOf(row(10, "named-by-davx5", "Buy milk"), row(11, "other", "Call mum", done = true))
        val plan = TodoProviderMerge.plan(local, emptySet(), remote, TodoSyncState(), fresh, now)

        assertTrue(plan.inserts.isEmpty())
        assertEquals(2, plan.local.size)
        assertEquals("named-by-davx5", plan.local.single { it.id == 1L }.uid)
        assertEquals("other", plan.local.single { it.id == 2L }.uid)
    }

    @Test
    fun `a task whose UID the provider never showed takes a row with its words`() {
        val local = listOf(task(1, "Buy milk", uid = "set-here"))
        val plan = TodoProviderMerge.plan(local, emptySet(), listOf(row(10, "set-there", "Buy milk")), TodoSyncState(), fresh, now)
        assertEquals(listOf("set-there"), plan.local.map { it.uid })
    }

    @Test
    fun `a row with other words or another tick still comes in as a task`() {
        val local = listOf(task(1, "Buy milk"))
        val remote = listOf(row(10, "a", "Buy bread"), row(11, "b", "Buy milk", done = true))
        val plan = TodoProviderMerge.plan(local, emptySet(), remote, TodoSyncState(), fresh, now)
        assertEquals(listOf(1L), plan.inserts)
        assertEquals(3, plan.local.size)
    }

    @Test
    fun `a step of a step hangs off the top task`() {
        val remote = listOf(
            row(10, "top", "Top"),
            row(11, "mid", "Mid", parent = 10),
            row(12, "deep", "Deep", parent = 11),
        )
        val plan = TodoProviderMerge.plan(emptyList(), emptySet(), remote, TodoSyncState(), fresh, now)
        val top = plan.local.single { it.uid == "top" }
        assertNull(top.parentId)
        assertEquals(top.id, plan.local.single { it.uid == "mid" }.parentId)
        assertEquals(top.id, plan.local.single { it.uid == "deep" }.parentId)
    }

    @Test
    fun `the state after records versions and local stamps`() {
        val state = TodoProviderMerge.stateAfter(
            "org.dmfs.tasks",
            7,
            listOf(row(10, "a", "A", version = 4)),
            listOf(task(1, "A", uid = "a", updated = 9), task(2, "Unlinked")),
            now,
        )
        assertTrue(state.isFor("org.dmfs.tasks", 7))
        assertEquals(mapOf("a" to 4L), state.seen)
        assertEquals(mapOf("a" to 9L), state.pushed)
        assertEquals(now, state.lastSyncAt)
    }
}
