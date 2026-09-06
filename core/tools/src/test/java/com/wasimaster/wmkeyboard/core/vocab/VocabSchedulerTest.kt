package com.wasimaster.wmkeyboard.core.vocab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabSchedulerTest {

    @Test
    fun `leitner climbs boxes and learns at the top`() {
        var state = WordProgress()
        state = LeitnerScheduler.next(state, ReviewGrade.GOOD, nowDay = 100)
        assertEquals(1, state.box)
        assertEquals(103, state.dueDay)
        state = LeitnerScheduler.next(state, ReviewGrade.EASY, nowDay = 103)
        assertEquals(3, state.box)
        assertEquals(103 + 14, state.dueDay)
        state = LeitnerScheduler.next(state, ReviewGrade.GOOD, nowDay = 117)
        assertEquals(LeitnerScheduler.TOP, state.box)
        assertFalse(state.learnt)
        state = LeitnerScheduler.next(state, ReviewGrade.GOOD, nowDay = 147)
        assertTrue(state.learnt)
    }

    @Test
    fun `leitner again drops to the first box and counts a lapse`() {
        val state = LeitnerScheduler.next(WordProgress(box = 3), ReviewGrade.AGAIN, nowDay = 10)
        assertEquals(0, state.box)
        assertEquals(1, state.lapses)
        assertEquals(11, state.dueDay)
        val hard = LeitnerScheduler.next(WordProgress(box = 3), ReviewGrade.HARD, nowDay = 10)
        assertEquals(3, hard.box)
    }

    @Test
    fun `sm2 canonical sequence`() {
        var state = WordProgress()
        state = Sm2Scheduler.next(state, ReviewGrade.EASY, nowDay = 0)
        assertEquals(1, state.intervalDays)
        assertEquals(2.6, state.ease, 1e-9)
        state = Sm2Scheduler.next(state, ReviewGrade.EASY, nowDay = 1)
        assertEquals(6, state.intervalDays)
        assertEquals(2.7, state.ease, 1e-9)
        state = Sm2Scheduler.next(state, ReviewGrade.GOOD, nowDay = 7)
        assertEquals(Math.round(6 * 2.7).toInt(), state.intervalDays)
        assertEquals(2.7, state.ease, 1e-9)
        assertEquals(7 + state.intervalDays, state.dueDay)
    }

    @Test
    fun `sm2 again resets reps and floors ease`() {
        var state = WordProgress(ease = 1.35, reps = 5, intervalDays = 40)
        state = Sm2Scheduler.next(state, ReviewGrade.AGAIN, nowDay = 50)
        assertEquals(0, state.reps)
        assertEquals(1, state.intervalDays)
        assertEquals(1, state.lapses)
        assertEquals(Sm2Scheduler.MIN_EASE, state.ease, 1e-9)
        assertFalse(state.learnt)
    }

    @Test
    fun `sm2 learns past two months`() {
        var state = WordProgress(ease = 2.5, reps = 3, intervalDays = 30)
        state = Sm2Scheduler.next(state, ReviewGrade.GOOD, nowDay = 0)
        assertTrue(state.intervalDays >= Sm2Scheduler.LEARNT_INTERVAL_DAYS)
        assertTrue(state.learnt)
    }

    @Test
    fun `word of the day is deterministic and skips exclusions`() {
        val words = listOf("a", "b", "c", "d", "e")
        val first = WordOfDay.pick(20_700, words)
        assertEquals(first, WordOfDay.pick(20_700, words))
        val without = WordOfDay.pick(20_700, words, exclude = setOf(first!!))
        assertTrue(without != null && without != first)
        assertEquals(null, WordOfDay.pick(1, emptyList()))
        assertEquals(null, WordOfDay.pick(1, words, exclude = words.toSet()))
    }
}
