package com.wasimaster.wmkeyboard.core.todo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class TodoRepeatTest {

    private val zone = ZoneOffset.UTC
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour
    private val daily = TodoRepeat(1, TodoRepeatUnit.DAY)
    private fun task(due: Long?, repeat: TodoRepeat? = null, snoozed: Long? = null, done: Boolean = false) =
        TodoTask(id = 1, text = "t", due = due, repeat = repeat, snoozedUntil = snoozed, done = done)

    @Test
    fun `a month on from the 31st is the next month's last day`() {
        val jan31 = java.time.LocalDate.of(2026, 1, 31).atStartOfDay(zone).toInstant().toEpochMilli()
        val feb28 = java.time.LocalDate.of(2026, 2, 28).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(feb28, TodoDue.step(jan31, TodoRepeat(1, TodoRepeatUnit.MONTH), zone))
    }

    @Test
    fun `an ignored repeating task keeps reminding`() {
        val t = task(due = day, repeat = daily)
        assertEquals(day, TodoDue.remindAfter(t, 0, zone))
        assertEquals(2 * day, TodoDue.remindAfter(t, day, zone))
        assertEquals(4 * day, TodoDue.remindAfter(t, 3 * day + 1, zone))
    }

    @Test
    fun `a plain task reminds once`() {
        val t = task(due = day)
        assertEquals(day, TodoDue.remindAfter(t, 0, zone))
        assertNull(TodoDue.remindAfter(t, day, zone))
    }

    @Test
    fun `a snooze holds the reminder and the chip`() {
        val t = task(due = day, snoozed = day + hour)
        assertEquals(day + hour, TodoDue.remindAfter(t, day, zone))
        assertFalse(TodoDue.isDue(t, day + 1, zone))
        assertTrue(TodoDue.isDue(t, day + hour, zone))
    }

    @Test
    fun `ticking a repeating task moves it on, even ahead of time`() {
        val store = TodoStore(null)
        val added = store.add(task(due = 10 * day, repeat = daily))!!
        store.setDone(added.id, true, now = day)
        val after = store.item(added.id)!!
        assertFalse(after.done)
        assertEquals(11 * day, after.due)
    }

    @Test
    fun `ticking a repeating task opens its steps again`() {
        val store = TodoStore(null)
        val added = store.add(task(due = day, repeat = daily))!!
        val step = store.add(TodoTask(id = 2, text = "s", parentId = added.id))!!
        store.setDone(step.id, true, now = 1)
        store.setDone(added.id, true, now = 2)
        assertFalse(store.item(step.id)!!.done)
    }
}
