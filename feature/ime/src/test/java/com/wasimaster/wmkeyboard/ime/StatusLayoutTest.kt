package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatusLayoutTest {

    private val alarm = StatusItem(StatusKind.ALARM, "Gym", at = 1_000, ref = 7)
    private val birthday = StatusItem(StatusKind.SPECIAL_DAY, "Anna birthday", at = 0, ref = 3, fromNote = true)
    private val timer = StatusItem(StatusKind.TIMER, at = 5_000, ref = 9)

    @Test
    fun theFirstAlarmOrDayIsTheChipAndTheRestAreIcons() {
        val ui = StatusLayout.arrange(listOf(alarm, birthday, timer), closed = emptySet(), chips = true, stripBusy = false)
        assertEquals(alarm, ui.chip)
        assertEquals(listOf(birthday, timer), ui.icons)
    }

    @Test
    fun aClosedChipBecomesAnIconAndTheNextTakesTheStrip() {
        val ui = StatusLayout.arrange(listOf(alarm, birthday, timer), closed = setOf(alarm.key), chips = true, stripBusy = false)
        assertEquals(birthday, ui.chip)
        assertEquals(listOf(alarm, timer), ui.icons)
    }

    @Test
    fun noChipWhileADueTaskHasTheStripOrChipsAreOff() {
        assertNull(StatusLayout.arrange(listOf(alarm, timer), emptySet(), chips = true, stripBusy = true).chip)
        val off = StatusLayout.arrange(listOf(alarm, timer), emptySet(), chips = false, stripBusy = false)
        assertNull(off.chip)
        assertEquals(listOf(alarm, timer), off.icons)
    }

    @Test
    fun aTimerIsNeverAChip() {
        val ui = StatusLayout.arrange(listOf(timer), emptySet(), chips = true, stripBusy = false)
        assertNull(ui.chip)
        assertEquals(listOf(timer), ui.icons)
    }

    @Test
    fun theNextChangeIsTheSoonestOfWindowRingEndAndMidnight() {
        val now = 0L
        val window = 30 * 60_000L
        val upcoming = 2 * 60 * 60_000L
        val next = StatusLayout.nextChange(listOf(timer), upcoming, window, now, midnight = 10 * 60 * 60_000L, horizonMs = 6 * 60 * 60_000L)
        assertEquals(timer.at, next)
        val noTimer = StatusLayout.nextChange(emptyList(), upcoming, window, now, midnight = 10 * 60 * 60_000L, horizonMs = 6 * 60 * 60_000L)
        assertEquals(upcoming - window, noTimer)
    }

    @Test
    fun nothingWithinTheHorizonSchedulesNothing() {
        assertNull(StatusLayout.nextChange(emptyList(), null, 0, now = 0, midnight = 10 * 60 * 60_000L, horizonMs = 60_000L))
    }
}
