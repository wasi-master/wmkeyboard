package com.wasimaster.wmkeyboard.core.vocab

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VocabProgressTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `review save reload`() {
        val file = File(temp.root, "vocab/progress.json")
        val progress = VocabProgress(file)
        progress.review("abhor", ReviewGrade.GOOD, nowDay = 100, scheme = VocabScheduler.LEITNER)
        progress.markLearnt("alacrity", learnt = true, nowDay = 100)
        progress.save()
        assertTrue(file.isFile)

        val reloaded = VocabProgress(file)
        assertEquals(1, reloaded.stateOf("abhor").box)
        assertEquals(103, reloaded.stateOf("abhor").dueDay)
        assertTrue(reloaded.isLearnt("alacrity"))
        assertTrue(reloaded.isSeen("abhor"))
        assertFalse(reloaded.isSeen("nobody"))
    }

    @Test
    fun `memory only store works without a file`() {
        val progress = VocabProgress(null)
        progress.review("abhor", ReviewGrade.EASY, nowDay = 5, scheme = VocabScheduler.SM2)
        progress.save()
        assertEquals(1, progress.stateOf("abhor").reps)
    }

    @Test
    fun `due words are ordered and filtered`() {
        val progress = VocabProgress(null)
        progress.review("late", ReviewGrade.GOOD, nowDay = 1, scheme = VocabScheduler.LEITNER)   // due 4
        progress.review("soon", ReviewGrade.AGAIN, nowDay = 5, scheme = VocabScheduler.LEITNER)  // due 6
        progress.review("far", ReviewGrade.EASY, nowDay = 5, scheme = VocabScheduler.LEITNER)    // due 12
        progress.markLearnt("done", learnt = true, nowDay = 5)
        assertEquals(listOf("late", "soon"), progress.dueWords(day = 6))
        assertEquals(listOf("soon"), progress.dueWords(day = 6, among = listOf("soon", "far", "new")))
        assertEquals(listOf("new"), progress.unseen(listOf("late", "new", "done")))
        val stats = progress.stats(day = 6)
        assertEquals(4, stats.seen)
        assertEquals(1, stats.learnt)
        assertEquals(2, stats.due)
        assertEquals(0, stats.reviewedToday)
    }

    @Test
    fun `history is capped`() {
        val progress = VocabProgress(null)
        repeat(VocabProgress.MAX_HISTORY + 5) {
            progress.review("w", ReviewGrade.GOOD, nowDay = it, scheme = VocabScheduler.SM2)
        }
        assertEquals(VocabProgress.MAX_HISTORY, progress.stateOf("w").history.size)
    }

    @Test
    fun `word of the day is pinned for the day`() {
        val file = File(temp.root, "p.json")
        val progress = VocabProgress(file)
        val candidates = listOf("a", "b", "c", "d")
        val picked = progress.wordOfTheDay(day = 42, candidates = candidates)!!
        assertEquals(picked, progress.wordOfTheDay(day = 42, candidates = candidates - picked + picked))
        assertEquals(picked, progress.pinnedWordOfTheDay(42))
        progress.save()
        assertEquals(picked, VocabProgress(file).pinnedWordOfTheDay(42))
        assertNull(progress.wordOfTheDay(day = 43, candidates = emptyList()))
    }

    @Test
    fun `reloadIfChanged notices the other process`() {
        val file = File(temp.root, "p.json")
        val a = VocabProgress(file)
        val b = VocabProgress(file)
        a.markLearnt("x", learnt = true, nowDay = 1)
        a.save()
        assertFalse(b.isLearnt("x"))
        // Force a visibly different mtime/length.
        file.setLastModified(file.lastModified() + 5000)
        assertTrue(b.reloadIfChanged())
        assertTrue(b.isLearnt("x"))
        assertFalse(b.reloadIfChanged())
    }

    @Test
    fun `unlearning resets scheduling`() {
        val progress = VocabProgress(null)
        progress.markLearnt("x", learnt = true, nowDay = 3)
        assertEquals(LeitnerScheduler.TOP, progress.stateOf("x").box)
        progress.markLearnt("x", learnt = false, nowDay = 4)
        assertEquals(0, progress.stateOf("x").box)
        assertEquals(4, progress.stateOf("x").dueDay)
        progress.reset("x")
        assertFalse(progress.isSeen("x"))
    }
}
