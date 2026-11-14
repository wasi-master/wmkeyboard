package com.wasimaster.wmkeyboard.core.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ClockLogicTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val ny = TimeZone.getTimeZone("America/New_York")
    private val kolkata = TimeZone.getTimeZone("Asia/Kolkata")
    private val london = TimeZone.getTimeZone("Europe/London")

    private fun at(zone: TimeZone, y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(y, mo - 1, d, h, mi, 0)
        }.timeInMillis

    // ---- AlarmMath -------------------------------------------------------

    @Test
    fun oneShotRingsTodayWhenStillAhead() {
        val now = at(utc, 2026, 10, 14, 6, 0)
        val alarm = Alarm(id = 1, hour = 7, minute = 30)
        assertEquals(at(utc, 2026, 10, 14, 7, 30), AlarmMath.nextFire(alarm, now, utc))
    }

    @Test
    fun oneShotRingsTomorrowWhenPassed() {
        val now = at(utc, 2026, 10, 14, 8, 0)
        val alarm = Alarm(id = 1, hour = 7, minute = 30)
        assertEquals(at(utc, 2026, 10, 15, 7, 30), AlarmMath.nextFire(alarm, now, utc))
    }

    @Test
    fun repeatingSkipsDaysNotInTheSet() {
        // 2026-10-16 is a Friday; weekdays-only must jump to Monday the 19th.
        val now = at(utc, 2026, 10, 16, 9, 0)
        val alarm = Alarm(id = 1, hour = 7, minute = 0, days = Weekdays.WORKDAYS)
        assertEquals(at(utc, 2026, 10, 19, 7, 0), AlarmMath.nextFire(alarm, now, utc))
    }

    @Test
    fun disabledAlarmNeverRings() {
        assertNull(AlarmMath.nextFire(Alarm(id = 1, hour = 7, minute = 0, enabled = false), 0, utc))
    }

    @Test
    fun snoozeWinsOverSchedule() {
        val now = at(utc, 2026, 10, 14, 7, 1)
        val alarm = Alarm(id = 1, hour = 7, minute = 0, days = Weekdays.ALL, snoozedUntil = now + 9 * 60_000)
        assertEquals(now + 9 * 60_000, AlarmMath.nextFire(alarm, now, utc))
    }

    @Test
    fun skipNextJumpsOneOccurrence() {
        val now = at(utc, 2026, 10, 14, 6, 0)
        val alarm = Alarm(id = 1, hour = 7, minute = 0, days = Weekdays.ALL)
        val skipped = AlarmMath.skipNext(alarm, now, utc)
        assertTrue(AlarmMath.isSkipping(skipped, now))
        assertEquals(at(utc, 2026, 10, 15, 7, 0), AlarmMath.nextFire(skipped, now, utc))
    }

    @Test
    fun springForwardGapRingsAfterTheJump() {
        // 2026-03-08 02:30 does not exist in New York.
        val now = at(ny, 2026, 3, 8, 0, 0)
        val alarm = Alarm(id = 1, hour = 2, minute = 30)
        val fire = AlarmMath.nextFire(alarm, now, ny)
        assertNotNull(fire)
        val cal = Calendar.getInstance(ny).apply { timeInMillis = fire!! }
        assertEquals(8, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(3, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun dueAtCatchesALateWakeUp() {
        val fire = at(utc, 2026, 10, 14, 7, 0)
        val alarms = listOf(Alarm(id = 1, hour = 7, minute = 0), Alarm(id = 2, hour = 7, minute = 5))
        val due = AlarmMath.dueAt(alarms, fire + 40_000, utc)
        assertEquals(listOf(1L), due.map { it.id })
    }

    @Test
    fun nextOfAllPicksTheEarliest() {
        val now = at(utc, 2026, 10, 14, 6, 0)
        val alarms = listOf(Alarm(id = 1, hour = 9, minute = 0), Alarm(id = 2, hour = 6, minute = 30))
        assertEquals(2L, AlarmMath.nextOfAll(alarms, now, utc)?.first?.id)
    }

    // ---- ClockActions ----------------------------------------------------

    @Test
    fun firedOneShotTurnsOffAndDismissLogs() {
        val fire = at(utc, 2026, 10, 14, 7, 0)
        val (alarm, d0) = ClockActions.addAlarm(ClockData(), Alarm(id = 0, hour = 7, minute = 0, label = "Gym"))
        val (d1, started) = ClockActions.fireAlarms(d0, fire, utc)
        assertEquals(1, started.size)
        assertFalse(d1.alarm(alarm.id)!!.enabled)
        val d2 = ClockActions.dismiss(d1, started.single(), fire + 30_000)
        assertTrue(d2.ringing.isEmpty())
        assertEquals(HistoryKind.ALARM, d2.history.first().kind)
        assertEquals("Gym", d2.history.first().label)
    }

    @Test
    fun snoozeReArmsTheAlarm() {
        val fire = at(utc, 2026, 10, 14, 7, 0)
        val (alarm, d0) = ClockActions.addAlarm(ClockData(), Alarm(id = 0, hour = 7, minute = 0))
        val (d1, started) = ClockActions.fireAlarms(d0, fire, utc)
        val d2 = ClockActions.snooze(d1, started.single(), fire, 10)
        val a = d2.alarm(alarm.id)!!
        assertTrue(a.enabled)
        assertEquals(fire + 10 * 60_000, AlarmMath.nextFire(a, fire, utc))
        assertEquals(HistoryOutcome.SNOOZED, d2.history.first().outcome)
    }

    @Test
    fun anUnansweredAlarmSnoozesItselfUntilItsSnoozesRunOut() {
        val fire = at(utc, 2026, 10, 14, 7, 0)
        val (alarm, d0) = ClockActions.addAlarm(ClockData(), Alarm(id = 0, hour = 7, minute = 0))
        val (d1, rang) = ClockActions.fireAlarms(d0, fire, utc)
        val d2 = ClockActions.unanswered(d1, rang.single(), fire + 600_000, autoSnoozes = 1, snoozeMinutes = 10)
        assertEquals(HistoryOutcome.SNOOZED, d2.history.first().outcome)
        assertEquals(1, d2.alarm(alarm.id)!!.snoozeCount)
        // The snooze has been spent: the next one nobody answers is missed.
        val again = Ringing(RingKind.ALARM, alarm.id, fire + 1_200_000, "")
        val d3 = ClockActions.unanswered(d2.copy(ringing = listOf(again)), again, fire + 1_800_000, autoSnoozes = 1, snoozeMinutes = 10)
        assertEquals(HistoryOutcome.MISSED, d3.history.first().outcome)
        assertFalse(d3.alarm(alarm.id)!!.enabled)
    }

    @Test
    fun anAlarmSetToSnoozeUntilAnsweredIsNeverMissed() {
        val fire = at(utc, 2026, 10, 14, 7, 0)
        val (alarm, d0) = ClockActions.addAlarm(ClockData(), Alarm(id = 0, hour = 7, minute = 0))
        var (d, rang) = ClockActions.fireAlarms(d0, fire, utc)
        var ring = rang.single()
        repeat(20) { i ->
            val now = fire + (i + 1) * 1_200_000L
            d = ClockActions.unanswered(d, ring, now, ClockConfig.SNOOZE_UNTIL_ANSWERED, snoozeMinutes = 10)
            assertEquals(HistoryOutcome.SNOOZED, d.history.first().outcome)
            ring = Ringing(RingKind.ALARM, alarm.id, now + 600_000, "")
            d = d.copy(ringing = listOf(ring))
        }
        assertTrue(d.alarm(alarm.id)!!.enabled)
    }

    @Test
    fun endingASnoozeSettlesTheAlarmAndSkippingTheComingRingKeepsTheRest() {
        val fire = at(utc, 2026, 10, 14, 7, 0)
        val (alarm, d0) = ClockActions.addAlarm(ClockData(), Alarm(id = 0, hour = 7, minute = 0, days = Weekdays.ALL))
        val (d1, rang) = ClockActions.fireAlarms(d0, fire, utc)
        val snoozed = ClockActions.snooze(d1, rang.single(), fire, 10)
        val ended = ClockActions.endSnooze(snoozed, alarm.id).alarm(alarm.id)!!
        assertEquals(0L, ended.snoozedUntil)
        assertTrue(ended.enabled)
        // Skipped, tomorrow's 7:00 is passed over for the day after.
        val skipped = ClockActions.skipComing(ClockActions.endSnooze(snoozed, alarm.id), alarm.id, fire + 3_600_000, utc)
        assertEquals(fire + 2 * 86_400_000L, AlarmMath.nextFire(skipped.alarm(alarm.id)!!, fire + 3_600_000, utc))
        // A one-shot is turned off instead.
        val (once, o0) = ClockActions.addAlarm(ClockData(), Alarm(id = 0, hour = 9, minute = 0))
        assertFalse(ClockActions.skipComing(o0, once.id, fire, utc).alarm(once.id)!!.enabled)
    }

    @Test
    fun timerPauseResumeKeepsTheRemainder() {
        val (t, d0) = ClockActions.startTimer(ClockData(), "", 60_000, now = 1_000)
        val d1 = ClockActions.pauseTimer(d0, t.id, now = 21_000)
        assertEquals(40_000, d1.timer(t.id)!!.remainingAt(99_999))
        val d2 = ClockActions.resumeTimer(d1, t.id, now = 100_000)
        assertEquals(140_000, d2.timer(t.id)!!.endsAt)
    }

    @Test
    fun pomodoroFocusHandsOverToABreak() {
        val plan = PomodoroPlan(focusMinutes = 25, shortBreakMinutes = 5, longBreakMinutes = 15, longEvery = 4)
        val d0 = ClockActions.startPomodoro(ClockData(), plan, "", now = 0)
        val end = d0.timers.single().endsAt
        val (d1, rang) = ClockActions.fireTimers(d0, end, plan)
        assertEquals(1, rang.size)
        val next = d1.timers.single()
        assertEquals(PomodoroPhase.SHORT_BREAK, next.pomodoro?.phase)
        assertEquals(5 * 60_000L, next.totalMs)
        assertEquals(HistoryKind.POMODORO, d1.history.first().kind)
    }

    @Test
    fun everyFourthFocusEarnsALongBreak() {
        val plan = PomodoroPlan(longEvery = 4)
        val run = plan.next(PomodoroRun(PomodoroPhase.FOCUS, 3))
        assertEquals(PomodoroPhase.LONG_BREAK, run.phase)
        assertEquals(4, run.completed)
        assertEquals(PomodoroPhase.FOCUS, plan.next(run).phase)
    }

    @Test
    fun stopwatchLapsAndResetLogsTheRun() {
        var d = ClockActions.stopwatchStart(ClockData(), now = 0)
        d = ClockActions.stopwatchLap(d, now = 10_000)
        d = ClockActions.stopwatchLap(d, now = 25_000)
        assertEquals(listOf(10_000L, 15_000L), d.stopwatch.lapLengths)
        d = ClockActions.stopwatchPause(d, now = 30_000)
        assertEquals(30_000, d.stopwatch.elapsedAt(999_999))
        d = ClockActions.stopwatchReset(d, now = 40_000)
        assertTrue(d.stopwatch.isReset)
        assertEquals(HistoryKind.STOPWATCH, d.history.first().kind)
        assertEquals(30_000, d.history.first().durationMs)
    }

    @Test
    fun historyIsCapped() {
        var d = ClockData()
        repeat(ClockActions.HISTORY_CAP + 20) {
            d = ClockActions.log(d, HistoryKind.TIMER, it.toLong(), "", 1, HistoryOutcome.DONE)
        }
        assertEquals(ClockActions.HISTORY_CAP, d.history.size)
        assertEquals((ClockActions.HISTORY_CAP + 19).toLong(), d.history.first().at)
    }

    // ---- Formats ---------------------------------------------------------

    @Test
    fun isoCarriesTheOffset() {
        val t = at(kolkata, 2026, 10, 14, 14, 5)
        assertEquals("2026-10-14T14:05:00+05:30", ClockFormats.iso(t, kolkata))
        assertEquals("2026-10-14T08:35:00Z", ClockFormats.iso(t, utc))
    }

    @Test
    fun discordStylesAndPickedMoments() {
        val now = at(london, 2026, 10, 14, 22, 0)
        // 22:00 in London is already the 15th in Kolkata, so tomorrow there
        // is the 16th, and 12:00 then is 06:30 UTC.
        val noon = ClockFormats.atLocal(now, kolkata, 1, 12, 0)
        assertEquals(at(utc, 2026, 10, 16, 6, 30), noon)
        assertEquals("<t:${noon / 1000}:R>", ClockFormats.discord(noon, DiscordStyle.RELATIVE))
        assertEquals(listOf('t', 'T', 'd', 'D', 'f', 'F', 'R', 's', 'S'), DiscordStyle.entries.map { it.code })
        assertEquals(DiscordStyle.NUMERIC_SECONDS, DiscordStyle.of("S"))
        assertEquals(
            "<t:${noon / 1000}:d>",
            ClockFormats.format(InsertFormat.DISCORD, noon, kolkata, true, "", { "" }, discordStyle = DiscordStyle.SHORT_DATE),
        )
    }

    @Test
    fun dayShiftAndDelta() {
        val t = at(london, 2026, 10, 14, 22, 0)
        assertEquals(1, ClockFormats.dayShift(t, kolkata, london))
        assertEquals(270, ClockFormats.offsetDelta(t, kolkata, london))
        assertEquals("+4:30", ClockFormats.deltaLabel(270))
        assertEquals("−5", ClockFormats.deltaLabel(-300))
    }

    @Test
    fun stopwatchAndCountdownReadouts() {
        assertEquals("01:02.34", ClockFormats.stopwatch(62_345))
        assertEquals("1:00:00.00", ClockFormats.stopwatch(3_600_000))
        assertEquals("0:01", ClockFormats.countdown(1))
        assertEquals("1:00:00", ClockFormats.countdown(3_600_000))
    }

    @Test
    fun offsetParsing() {
        assertEquals(330, ZoneCatalog.parseOffset("+5:30"))
        assertEquals(-180, ZoneCatalog.parseOffset("utc-3"))
        assertEquals(480, ZoneCatalog.parseOffset("GMT +08:00"))
        assertNull(ZoneCatalog.parseOffset("dhaka"))
        assertEquals("UTC+5:30", utcOffsetLabel(330))
    }

    @Test
    fun zoneSearchMatchesWordStartsAndAccents() {
        val catalog = ZoneCatalog(object : ZoneNames {
            override fun city(id: String): String? = if (id == "America/Sao_Paulo") "São Paulo" else null
            override fun region(id: String): String? = null
        })
        assertEquals("America/New_York", catalog.search("new yo").first().id)
        assertEquals("America/Sao_Paulo", catalog.search("sao pa").first().id)
        assertTrue(catalog.search("ork").none { it.id == "America/New_York" })
        assertTrue(catalog.search("+5:30").any { it.id == "Asia/Kolkata" })
        assertFalse(ZoneCatalog.isPlace("Etc/GMT+6"))
        assertFalse(ZoneCatalog.isPlace("US/Pacific"))
    }

    @Test
    fun citiesWithoutAZoneOfTheirOwnAreFoundByName() {
        val catalog = ZoneCatalog(object : ZoneNames {
            override fun city(id: String): String? = null
            override fun region(id: String): String? = null
        })
        val beijing = catalog.search("beijing").first()
        assertEquals("Asia/Shanghai", beijing.id)
        assertEquals("Beijing", beijing.toWorldZone().label)
        assertEquals("Bengaluru", catalog.search("bangalore").first().city)
        // The zone's own city still leads its own name, and keeps no label.
        val shanghai = catalog.search("shanghai").first()
        assertEquals("Shanghai", shanghai.city)
        assertEquals("", shanghai.toWorldZone().label)
        assertTrue(catalog.search("shanghai").none { it.extra })
        assertEquals(catalog.entries.size, catalog.entries.map { it.key }.toSet().size)
    }

    @Test
    fun jerusalemFollowsThePhonesRegion() {
        assertEquals("PS", ContestedRegions.regionCode("Asia/Jerusalem", "IL", "BD"))
        assertEquals("PS", ContestedRegions.regionCode("Asia/Jerusalem", "IL", "ie"))
        assertEquals("IL", ContestedRegions.regionCode("Asia/Jerusalem", "IL", "IL"))
        assertEquals("IL", ContestedRegions.regionCode("Asia/Jerusalem", "IL", null))
        assertEquals("IN", ContestedRegions.regionCode("Asia/Kolkata", "IN", "BD"))
        // Tel Aviv shares the zone but keeps Israel's name.
        val catalog = ZoneCatalog(object : ZoneNames {
            override fun city(id: String): String? = null
            override fun region(id: String): String? = if (id == "Asia/Jerusalem") "Palestine" else null
            override fun country(code: String): String? = if (code == "IL") "Israel" else null
        })
        assertEquals("Israel", catalog.search("tel aviv").first().region)
        assertEquals("Palestine", catalog.search("jerusalem").first().region)
    }

    // ---- Planner and dates ----------------------------------------------

    @Test
    fun plannerFindsTheLondonNewYorkOverlap() {
        val day = at(london, 2026, 10, 14, 12, 0)
        val plan = MeetingPlanner.plan(day, london, listOf(london, ny), workStart = 9 * 60, workEnd = 17 * 60)
        // London 14:00-17:00 is New York 09:00-12:00.
        val firstOverlap = plan.overlap.first()
        assertEquals(14 * 60, MeetingPlanner.minuteOfDay(plan.slots[firstOverlap], london))
        assertEquals(6, plan.overlap.size)
        assertEquals(firstOverlap, plan.best)
    }

    @Test
    fun nightShiftWindowsWrap() {
        assertTrue(MeetingPlanner.within(23 * 60, 22 * 60, 6 * 60))
        assertTrue(MeetingPlanner.within(60, 22 * 60, 6 * 60))
        assertFalse(MeetingPlanner.within(12 * 60, 22 * 60, 6 * 60))
    }

    @Test
    fun epochDaysRoundTrip() {
        val d = LocalDay(2024, 2, 29)
        assertEquals(d, LocalDay.ofEpochDay(d.epochDay))
        assertEquals(0L, LocalDay(1970, 1, 1).epochDay)
        assertEquals(3, LocalDay(1970, 1, 1).isoDayOfWeek)
    }

    @Test
    fun monthsClampToTheMonthLength() {
        assertEquals(LocalDay(2026, 2, 28), LocalDay(2026, 1, 31).plusMonths(1))
        assertEquals(LocalDay(2024, 2, 29), LocalDay(2024, 1, 31).plusMonths(1))
    }

    @Test
    fun distanceBetweenDates() {
        val d = DateMath.between(LocalDay(2026, 1, 15), LocalDay(2027, 3, 10))
        assertEquals(1, d.years)
        assertEquals(1, d.months)
        assertEquals(23, d.restDays)
        assertEquals(419L, d.days)
        assertEquals(-419L, DateMath.between(LocalDay(2027, 3, 10), LocalDay(2026, 1, 15)).days)
    }

    @Test
    fun workdaysSkipWeekends() {
        // Mon 2026-10-12 to Mon 2026-10-19: five workdays.
        assertEquals(5L, DateMath.workdays(LocalDay(2026, 10, 12), LocalDay(2026, 10, 19)))
        assertEquals(LocalDay(2026, 10, 19), DateMath.addWorkdays(LocalDay(2026, 10, 16), 1))
    }

    @Test
    fun isoWeekNumbers() {
        assertEquals(53, LocalDay(2026, 12, 31).isoWeek)
        assertEquals(1, LocalDay(2024, 12, 30).isoWeek)
        assertEquals(42, LocalDay(2026, 10, 14).isoWeek)
    }
}
