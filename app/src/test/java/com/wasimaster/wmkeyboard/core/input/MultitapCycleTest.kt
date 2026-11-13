package com.wasimaster.wmkeyboard.core.input

import com.wasimaster.wmkeyboard.core.layout.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The run bookkeeping behind [Key.multitap] (discussion #372). */
class MultitapCycleTest {

    private val giyeok = Key("ㄱㅋ", output = "ㄱ", multitap = listOf("ㅋ", "ㄲ"))
    private val nieun = Key("ㄴㄹ", output = "ㄴ", multitap = listOf("ㄹ"))

    @Test fun `taps of one key walk its cycle and wrap back to the key`() {
        val cycle = MultitapCycle()
        assertEquals(MultitapCycle.Tap("ㄱ", null), cycle.press(giyeok, 0))
        assertEquals(MultitapCycle.Tap("ㅋ", "ㄱ"), cycle.press(giyeok, 100))
        assertEquals(MultitapCycle.Tap("ㄲ", "ㅋ"), cycle.press(giyeok, 200))
        assertEquals(MultitapCycle.Tap("ㄱ", "ㄲ"), cycle.press(giyeok, 300))
    }

    @Test fun `a pause longer than the timeout starts a new letter`() {
        val cycle = MultitapCycle(timeoutMs = 1_000)
        cycle.press(giyeok, 0)
        assertTrue(cycle.isLive(1_000))
        assertFalse(cycle.isLive(1_001))
        assertEquals(MultitapCycle.Tap("ㄱ", null), cycle.press(giyeok, 1_001))
    }

    @Test fun `a timeout changed between taps applies to the next one`() {
        val cycle = MultitapCycle()
        cycle.press(giyeok, 0)
        cycle.timeoutMs = 2_000
        assertEquals(MultitapCycle.Tap("ㅋ", "ㄱ"), cycle.press(giyeok, 1_500))
        cycle.timeoutMs = 400
        assertEquals(MultitapCycle.Tap("ㄱ", null), cycle.press(giyeok, 2_000))
    }

    @Test fun `a different key starts its own run`() {
        val cycle = MultitapCycle()
        cycle.press(giyeok, 0)
        assertEquals(MultitapCycle.Tap("ㄴ", null), cycle.press(nieun, 10))
        assertEquals(MultitapCycle.Tap("ㄹ", "ㄴ"), cycle.press(nieun, 20))
    }

    @Test fun `ending the run makes the next tap fresh`() {
        val cycle = MultitapCycle()
        cycle.press(giyeok, 0)
        cycle.end()
        assertFalse(cycle.isLive(1))
        assertNull(cycle.press(giyeok, 2).replaces)
    }

    @Test fun `a key with no cycle never opens a run`() {
        val cycle = MultitapCycle()
        cycle.press(giyeok, 0)
        assertEquals(MultitapCycle.Tap("a", null), cycle.press(Key("a"), 10))
        assertFalse(cycle.isLive(11))
    }

    @Test fun `restart begins again at the key`() {
        val cycle = MultitapCycle()
        cycle.press(giyeok, 0)
        cycle.press(giyeok, 10)
        assertEquals(MultitapCycle.Tap("ㄱ", null), cycle.restart(giyeok, 20))
        assertEquals(MultitapCycle.Tap("ㅋ", "ㄱ"), cycle.press(giyeok, 30))
    }

    /** A Keyman key presses a different key per step, so it reads the step, not the text. */
    @Test fun `the step says where the run is in the cycle`() {
        val cycle = MultitapCycle()
        cycle.press(giyeok, 0)
        assertEquals(0, cycle.step)
        cycle.press(giyeok, 10)
        assertEquals(1, cycle.step)
        cycle.press(giyeok, 20)
        assertEquals(2, cycle.step)
        cycle.press(giyeok, 30)
        assertEquals(0, cycle.step)
        cycle.press(nieun, 40)
        assertEquals(0, cycle.step)
    }
}
