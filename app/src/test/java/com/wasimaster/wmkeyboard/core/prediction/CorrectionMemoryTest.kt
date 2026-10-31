package com.wasimaster.wmkeyboard.core.prediction

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CorrectionMemoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val layout = "qwerty"

    private fun memory(file: File? = null) = CorrectionMemory(file)

    @Test
    fun aFixSetByHandHoldsItsCountAndTravelsWithTheFile() {
        val file = File(temp.root, "learned.json")
        val m = memory(file)
        m.setPair(" Recieve ", "Receive", 5)
        m.save()
        val back = memory(file).fixFor("recieve")
        assertEquals("receive", back?.fixed)
        assertEquals(5, back?.count)
        assertTrue(back?.byHand == true)
    }

    @Test
    fun oneFixOfASpellingGoesAlone() {
        val m = memory()
        m.teach("abc", "abd", layout, CorrectionMemory.Kind.PAIR_ONLY)
        m.teach("abc", "abe", layout, CorrectionMemory.Kind.PAIR_ONLY)
        m.removePair("abc", "abd")
        assertEquals(listOf("abe"), m.pairs().map { it.second.fixed })
    }

    @Test
    fun aSlipMovesOnOneLayoutOnly() {
        val m = memory()
        m.teach("t3st", "test", layout, CorrectionMemory.Kind.PAIR_AND_HABITS)
        m.teach("t3st", "test", "azerty", CorrectionMemory.Kind.PAIR_AND_HABITS)
        val habit = m.habitSummary(layout).single()
        m.setHabit(layout, habit.key, 9)
        assertEquals(9, m.habitSummary(layout).single().count)
        assertEquals(1, m.habitSummary("azerty").single().count)
        m.setHabit(layout, habit.key, 0)
        assertEquals(listOf("azerty"), m.habitLayouts())
    }

    @Test
    fun everySlipKindNamesItsOwnStoreKey() {
        val m = memory()
        m.teach("t3st", "test", layout, CorrectionMemory.Kind.PAIR_AND_HABITS)
        m.teach("teh", "the", layout, CorrectionMemory.Kind.PAIR_AND_HABITS)
        m.teach("tst", "test", layout, CorrectionMemory.Kind.PAIR_AND_HABITS)
        m.teach("tesst", "test", layout, CorrectionMemory.Kind.PAIR_AND_HABITS)
        m.teach("ambtomake", "am tomake", layout, CorrectionMemory.Kind.PAIR_AND_HABITS)
        m.teach("amtomake", "am tomake", layout, CorrectionMemory.Kind.PAIR_AND_HABITS)
        val all = m.habitSummary(layout)
        assertEquals(CorrectionMemory.HabitKind.entries.toSet(), all.map { it.kind }.toSet())
        for (habit in all) m.setHabit(layout, habit.key, 0)
        assertTrue(m.habitSummary(layout).isEmpty())
    }

    @Test
    fun onlyAWordAutocorrectWouldReadCanBeSetByHand() {
        assertTrue(CorrectionMemory.isPairValid("teh", "the"))
        assertTrue(CorrectionMemory.isPairValid("wfh", "working from"))
        assertFalse(CorrectionMemory.isPairValid("ab", "abc"))
        assertFalse(CorrectionMemory.isPairValid("the", "THE"))
        assertFalse(CorrectionMemory.isPairValid("two words", "two"))
        assertFalse(CorrectionMemory.isPairValid("teh", " "))
        assertFalse(CorrectionMemory.isPairValid("teh", "one two three"))
    }

    @Test
    fun aFixIsCountedPerTeaching() {
        val m = memory()
        m.teach("teh", "the", layout, CorrectionMemory.Kind.PAIR_AND_HABITS)
        assertEquals(1, m.fixFor("teh")?.count)
        m.teach("Teh", "The", layout, CorrectionMemory.Kind.PAIR_AND_HABITS)
        val taught = m.fixFor("TEH")
        assertEquals("the", taught?.fixed)
        assertEquals(2, taught?.count)
        assertEquals(1, m.pairCount())
    }

    @Test
    fun competingFixesNeedADominantOne() {
        val m = memory()
        repeat(2) { m.teach("teh", "the", layout, CorrectionMemory.Kind.PAIR_ONLY) }
        repeat(2) { m.teach("teh", "ten", layout, CorrectionMemory.Kind.PAIR_ONLY) }
        // A spelling the user means two things by is not a fix anyone can make.
        assertNull(m.fixFor("teh"))
        repeat(2) { m.teach("teh", "the", layout, CorrectionMemory.Kind.PAIR_ONLY) }
        assertEquals("the", m.fixFor("teh")?.fixed)
    }

    @Test
    fun unteachingShrinksAndThenForgets() {
        val m = memory()
        repeat(2) { m.teach("teh", "the", layout, CorrectionMemory.Kind.PAIR_ONLY) }
        m.unteach("teh", "the")
        assertEquals(1, m.fixFor("teh")?.count)
        m.unteach("teh", "the")
        assertNull(m.fixFor("teh"))
        assertEquals(0, m.pairCount())
    }

    @Test
    fun forgetDropsEveryFixOfTheSpelling() {
        val m = memory()
        m.teach("teh", "the", layout, CorrectionMemory.Kind.PAIR_ONLY)
        m.teach("teh", "ten", layout, CorrectionMemory.Kind.PAIR_ONLY)
        m.forget("teh")
        assertNull(m.fixFor("teh"))
        assertTrue(m.pairs().isEmpty())
    }

    @Test
    fun habitsSayNothingUntilWarm() {
        val m = memory()
        repeat(EditHabits.WARMUP - 1) { m.teach("as3", "ase", layout, CorrectionMemory.Kind.PAIR_AND_HABITS) }
        assertTrue(m.habitsFor(layout).isEmpty)
        m.teach("as3", "ase", layout, CorrectionMemory.Kind.PAIR_AND_HABITS)
        val habits = m.habitsFor(layout)
        assertTrue(habits.substitution('3', 'e') > 0.0)
        assertEquals(0.0, habits.substitution('e', '3'), 0.0)
        assertEquals(0.0, habits.substitution('q', 'w'), 0.0)
    }

    @Test
    fun habitsAreKeptPerLayout() {
        val m = memory()
        repeat(EditHabits.WARMUP) { m.teach("as3", "ase", "qwerty", CorrectionMemory.Kind.PAIR_AND_HABITS) }
        assertTrue(m.habitsFor("azerty").isEmpty)
        assertFalse(m.habitsFor("qwerty").isEmpty)
    }

    @Test
    fun aPairOnlyTeachingMovesNoCost() {
        val m = memory()
        repeat(EditHabits.WARMUP * 2) { m.teach("form", "from", layout, CorrectionMemory.Kind.PAIR_ONLY) }
        assertTrue(m.habitsFor(layout).isEmpty)
        assertTrue(m.habitSummary(layout).isEmpty())
        assertEquals("from", m.fixFor("form")?.fixed)
    }

    @Test
    fun aFatFingeredSpaceIsASpaceSlipHabit() {
        val m = memory()
        repeat(EditHabits.WARMUP) { m.teach("thisbis", "this is", layout, CorrectionMemory.Kind.PAIR_AND_HABITS) }
        assertTrue(m.habitsFor(layout).spaceSlip('b') > 0.0)
        val habit = m.habitSummary(layout).single()
        assertEquals(CorrectionMemory.HabitKind.SPACE_SLIP, habit.kind)
        assertEquals('b', habit.from)
        assertEquals(EditHabits.WARMUP, habit.count)
    }

    @Test
    fun theSummaryNamesEveryKindOfSlip() {
        val m = memory()
        val kind = CorrectionMemory.Kind.PAIR_AND_HABITS
        m.teach("teh", "the", layout, kind)
        m.teach("aple", "apple", layout, kind)
        m.teach("helllo", "hello", layout, kind)
        m.teach("as3", "ase", layout, kind)
        m.teach("thisis", "this is", layout, kind)
        val kinds = m.habitSummary(layout).map { it.kind }.toSet()
        assertEquals(
            setOf(
                CorrectionMemory.HabitKind.SWAP, CorrectionMemory.HabitKind.MISSING,
                CorrectionMemory.HabitKind.STRAY, CorrectionMemory.HabitKind.SUBSTITUTION,
                CorrectionMemory.HabitKind.MISSED_SPACE,
            ),
            kinds,
        )
    }

    @Test
    fun habitCountersHalvePastTheWindow() {
        val m = memory()
        repeat(CorrectionMemory.HABIT_WINDOW) { m.teach("as3", "ase", layout, CorrectionMemory.Kind.PAIR_AND_HABITS) }
        val count = m.habitSummary(layout).single().count
        assertTrue("halved: $count", count < CorrectionMemory.HABIT_WINDOW)
        assertTrue(count > 0)
    }

    @Test
    fun everythingSurvivesARestart() {
        val file = File(temp.root, "learned_corrections.json")
        val first = memory(file)
        repeat(2) { first.teach("teh", "the", layout, CorrectionMemory.Kind.PAIR_AND_HABITS) }
        repeat(EditHabits.WARMUP) { first.teach("as3", "ase", layout, CorrectionMemory.Kind.PAIR_AND_HABITS) }
        first.save()
        val second = memory(file)
        assertEquals(2, second.fixFor("teh")?.count)
        assertTrue(second.habitsFor(layout).substitution('3', 'e') > 0.0)
        assertEquals(2, second.pairCount())
    }

    @Test
    fun aPairNobodyFixesForMonthsFades() {
        val file = File(temp.root, "learned_corrections.json")
        val m = memory(file)
        m.teach("teh", "the", layout, CorrectionMemory.Kind.PAIR_ONLY)
        m.save()
        // Each save is one generation; something has to change for one to happen.
        repeat(CorrectionMemory.EXPIRE_GENERATIONS.toInt() + 1) { i ->
            m.teach("filler$i", "fillers$i", layout, CorrectionMemory.Kind.PAIR_ONLY)
            m.save()
        }
        assertNull(m.fixFor("teh"))
    }

    @Test
    fun clearDeletesTheFile() {
        val file = File(temp.root, "learned_corrections.json")
        val m = memory(file)
        m.teach("teh", "the", layout, CorrectionMemory.Kind.PAIR_ONLY)
        m.save()
        assertTrue(file.exists())
        m.clear()
        assertFalse(file.exists())
        assertNull(m.fixFor("teh"))
    }

    @Test
    fun versionMovesOnEveryChange() {
        val m = memory()
        val before = m.version
        m.teach("teh", "the", layout, CorrectionMemory.Kind.PAIR_ONLY)
        assertTrue(m.version > before)
        val afterTeach = m.version
        m.forget("teh")
        assertTrue(m.version > afterTeach)
    }

    @Test
    fun acceptsOnlySlipSizedFixesIntoKnownWords() {
        val known = setOf("the", "this", "is", "hello", "receive", "from", "form")
        val knows: (String) -> Boolean = { it in known }
        assertTrue(CorrectionMemory.accepts("teh", "the", knows))
        assertTrue(CorrectionMemory.accepts("Teh", "The", knows))
        assertTrue(CorrectionMemory.accepts("recieve", "receive", knows))
        assertTrue(CorrectionMemory.accepts("thisbis", "this is", knows))
        assertTrue(CorrectionMemory.accepts("form", "from", knows))
        // The revised word has to be known — a typo fixed into another typo teaches nothing.
        assertFalse(CorrectionMemory.accepts("teh", "tje", knows))
        // ...and both halves of a split.
        assertFalse(CorrectionMemory.accepts("thisbis", "this is") { it == "this" })
        // A word committed early and then finished is not a fix.
        assertFalse(CorrectionMemory.accepts("hel", "hello", knows))
        // Case alone is the case memory's business.
        assertFalse(CorrectionMemory.accepts("the", "The", knows))
        // Too short for autocorrect, too short for this.
        assertFalse(CorrectionMemory.accepts("te", "the", knows))
        // A rewrite, not a slip.
        assertFalse(CorrectionMemory.accepts("abcdefg", "receive", knows))
        assertNotNull(CorrectionMemory.accepts("hello", "hello", knows))
        assertFalse(CorrectionMemory.accepts("hello", "hello", knows))
    }
}
