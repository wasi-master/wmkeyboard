package com.wasimaster.wmkeyboard.core.tools.calendar

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/** WM Calendar's bridge JSON read and written as `BRIDGES.md` in WM Calendar describes it. */
class WmCalendarWireTest {

    private var saved: TimeZone? = null
    private val dhaka = TimeZone.getTimeZone("Asia/Dhaka")

    @Before fun zone() {
        saved = TimeZone.getDefault()
        TimeZone.setDefault(dhaka)
    }

    @After fun back() {
        TimeZone.setDefault(saved)
    }

    private val day = EventDate(2026, 12, 3)
    private fun at(minute: Int, date: EventDate = day) = EventTimes.millis(date, minute, dhaka)

    private val instances = """
        [
          {"item": 11, "master": 10, "occurrence": "2026-12-03T10:00", "calendar": 2, "title": "Standup", "location": "Room 4",
           "color": -16776961, "start": "2026-12-03T10:00", "end": "2026-12-03T10:30", "allDay": false, "task": false,
           "done": false, "recurring": true, "readOnly": false},
          {"item": 12, "master": 12, "occurrence": "2026-12-03T00:00", "calendar": 2, "title": "", "location": "",
           "color": 0, "start": "2026-12-03T00:00", "end": "2026-12-04T00:00", "allDay": true, "task": false, "recurring": false},
          {"item": 13, "master": 13, "calendar": 3, "title": "Pay rent", "start": "2026-12-03T09:00", "end": "2026-12-03T09:00", "allDay": false, "task": true},
          {"item": 14, "master": 14, "calendar": 2, "title": "Tomorrow", "start": "2026-12-04T09:00", "end": "2026-12-04T10:00", "allDay": false}
        ]
    """.trimIndent()

    @Test fun instancesBecomeTheDaysEventsWithoutTasks() {
        val events = WmCalendarWire.events(instances, at(0), at(0, day.plusDays(1)), "(No title)")
        assertEquals(listOf(11L, 12L), events.map { it.id })
        val standup = events[0]
        assertEquals(at(10 * 60), standup.begin)
        assertEquals(at(10 * 60 + 30), standup.end)
        assertEquals("Room 4", standup.location)
        assertEquals(-16776961, standup.color)
        assertEquals(2L, standup.calendarId)
        assertTrue(standup.recurring)
        // Changed in WM Calendar, never in place here.
        assertFalse(standup.canModify)
    }

    @Test fun anAllDayEventIsHeldAtUtcMidnightsAsThePhonesProviderHoldsOne() {
        val allDay = WmCalendarWire.events(instances, at(0), at(0, day.plusDays(1)), "(No title)").single { it.allDay }
        assertEquals(EventTimes.millis(day, 0, EventDraft.UTC), allDay.begin)
        assertEquals(EventTimes.millis(day.plusDays(1), 0, EventDraft.UTC), allDay.end)
        assertEquals("(No title)", allDay.title)
        assertNull(allDay.location)
    }

    @Test fun theMonthDotsCountWmCalendarEventsToo() {
        val events = WmCalendarWire.events(instances, at(0, EventDate(2026, 12, 1)), at(0, EventDate(2027, 1, 1)), "")
        val days = CalendarDays.busyDays(events, at(0, EventDate(2026, 12, 1)), at(0, EventDate(2027, 1, 1)), 31)
        assertEquals(setOf(3, 4), days)
    }

    @Test fun calendarsKeepTheWritableShownOnesDefaultFirst() {
        val body = """[{"id":1,"title":"Work","color":5,"writable":true,"visible":true,"default":false},
            {"id":2,"title":"Home","color":6,"writable":true,"visible":true,"default":true},
            {"id":3,"title":"Holidays","writable":false,"visible":true,"default":false},
            {"id":4,"title":"Hidden","writable":true,"visible":false,"default":false}]"""
        val list = WmCalendarWire.calendars(body)
        assertEquals(listOf(2L, 1L), list.map { it.id })
        assertTrue(list[0].primary)
        assertEquals("Home", list[0].name)
    }

    @Test fun aTimedDraftIsWrittenInThePhonesWallTime() {
        val d = EventDraft(calendarId = 2, title = "Lunch", location = "Café", startDate = day, startMinute = 12 * 60, reminders = listOf(10, 10))
            .withLength(90)
        val json = WmCalendarWire.draft(d)
        assertTrue(json, "\"start\":\"2026-12-03T12:00\"" in json)
        assertTrue(json, "\"end\":\"2026-12-03T13:30\"" in json)
        assertTrue(json, "\"allDay\":false" in json)
        assertTrue(json, "\"calendar\":2" in json)
        assertTrue(json, "\"reminders\":[10]" in json)
        assertTrue(json, "\"location\":\"Café\"" in json)
        assertFalse(json, "notes" in json)
    }

    @Test fun aDraftInAnotherZoneIsMovedIntoThePhonesZone() {
        // 09:00 in London is 15:00 in Dhaka in December.
        val d = EventDraft(title = "Call", startDate = day, startMinute = 9 * 60, zoneId = "Europe/London").withLength(60)
        val json = WmCalendarWire.draft(d)
        assertTrue(json, "\"start\":\"2026-12-03T15:00\"" in json)
        assertFalse(json, "calendar" in json)
    }

    @Test fun anAllDayDraftEndsTheDayAfterItsLast() {
        val d = EventDraft(title = "Trip", allDay = true, startDate = day, endDate = day.plusDays(2), recurrence = Recurrence.yearly())
        val json = WmCalendarWire.draft(d)
        assertTrue(json, "\"start\":\"2026-12-03T00:00\"" in json)
        assertTrue(json, "\"end\":\"2026-12-06T00:00\"" in json)
        assertTrue(json, "\"allDay\":true" in json)
        assertTrue(json, "\"rrule\":\"FREQ=YEARLY" in json)
    }

    @Test fun parseReadsQuickAddsAnswer() {
        val p = WmCalendarWire.parsed("""{"title":"Dentist","start":"2026-12-03T15:00","end":"2026-12-03T16:00","allDay":false,"location":"Main St","reminders":[30],"language":"en"}""")!!
        assertEquals("Dentist", p.title)
        assertEquals(at(15 * 60), p.start)
        assertEquals(at(16 * 60), p.end)
        assertEquals("Main St", p.location)
        assertEquals(listOf(30), p.reminders)
        assertNull(p.rrule)
        assertNull(WmCalendarWire.parsed("not json"))
    }

    @Test fun busyKeepsTheSpansInsideTheWindow() {
        val body = """[{"start":"2026-12-03T10:00","end":"2026-12-03T11:00"},{"start":"2026-12-05T10:00","end":"2026-12-05T11:00"},{"start":"bad","end":"x"}]"""
        assertEquals(listOf(BusySpan(at(10 * 60), at(11 * 60))), WmCalendarWire.busy(body, at(0), at(0, day.plusDays(1))))
    }

    @Test fun savedIdAndBadAnswers() {
        assertEquals(42L, WmCalendarWire.savedId("""{"id":42}"""))
        assertNull(WmCalendarWire.savedId("null"))
        assertTrue(WmCalendarWire.events("{}", 0, Long.MAX_VALUE, "").isEmpty())
        assertNull(WmCalendarWire.millis("tomorrow", false))
        assertEquals(at(10 * 60), WmCalendarWire.millis("2026-12-03T10:00:00", false))
    }
}
