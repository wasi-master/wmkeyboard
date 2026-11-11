package com.wasimaster.wmkeyboard.core.tools

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventWeatherRulesTest {

    private val zone = TimeZone.getTimeZone("Asia/Dhaka")
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun local(y: Int, m: Int, d: Int, h: Int = 0): Long = Calendar.getInstance(zone).apply {
        clear()
        set(y, m - 1, d, h, 0, 0)
    }.timeInMillis

    private fun utcMidnight(y: Int, m: Int, d: Int): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(y, m - 1, d, 0, 0, 0)
    }.timeInMillis

    @Test
    fun `a single place is searched as it is`() {
        assertEquals(listOf("Dhaka"), EventWeatherRules.placeQueries("Dhaka"))
        assertEquals(listOf("Dhaka"), EventWeatherRules.placeQueries("  Dhaka  "))
    }

    @Test
    fun `the town of an address comes first and the numbers go`() {
        assertEquals(
            listOf("London", "Oxford Street", "Café Nero"),
            EventWeatherRules.placeQueries("Café Nero, 12 Oxford Street, London W1, UK"),
        )
        assertEquals(listOf("Dhaka", "Bangladesh"), EventWeatherRules.placeQueries("Dhaka, Bangladesh"))
    }

    @Test
    fun `state codes and postcodes are not searched`() {
        assertEquals(
            listOf("Springfield", "Main St"),
            EventWeatherRules.placeQueries("123 Main St, Springfield, IL 62701, USA"),
        )
    }

    @Test
    fun `calls and links name no place`() {
        assertEquals(emptyList<String>(), EventWeatherRules.placeQueries("https://meet.google.com/abc-defg-hij"))
        assertEquals(emptyList<String>(), EventWeatherRules.placeQueries("Zoom"))
        assertEquals(emptyList<String>(), EventWeatherRules.placeQueries("Microsoft Teams Meeting"))
        assertEquals(emptyList<String>(), EventWeatherRules.placeQueries(null))
        assertEquals(emptyList<String>(), EventWeatherRules.placeQueries("Room 4"))
    }

    @Test
    fun `a timed event is forecast at its start, or now once started`() {
        val now = local(2026, 11, 12, 10)
        assertEquals(local(2026, 11, 14, 15), EventWeatherRules.targetMillis(local(2026, 11, 14, 15), local(2026, 11, 14, 16), false, now, zone))
        assertEquals(now, EventWeatherRules.targetMillis(now - hour, now + hour, false, now, zone))
    }

    @Test
    fun `an event that is over has no forecast`() {
        val now = local(2026, 11, 12, 10)
        assertNull(EventWeatherRules.targetMillis(now - 2 * hour, now - hour, false, now, zone))
        assertNull(EventWeatherRules.targetMillis(utcMidnight(2026, 11, 11), utcMidnight(2026, 11, 12), true, now, zone))
    }

    @Test
    fun `an all-day event is forecast at midday of its first day to come`() {
        val now = local(2026, 11, 12, 10)
        assertEquals(local(2026, 11, 20, 12), EventWeatherRules.targetMillis(utcMidnight(2026, 11, 20), utcMidnight(2026, 11, 21), true, now, zone))
        // Started yesterday, still on today.
        assertEquals(local(2026, 11, 12, 12), EventWeatherRules.targetMillis(utcMidnight(2026, 11, 11), utcMidnight(2026, 11, 14), true, now, zone))
    }

    @Test
    fun `nothing past the forecast's last day`() {
        val now = local(2026, 11, 12, 10)
        val lastHour = local(2026, 11, 12) + 16 * day - hour
        assertEquals(lastHour, EventWeatherRules.targetMillis(lastHour, lastHour + hour, false, now, zone))
        assertNull(EventWeatherRules.targetMillis(lastHour + hour, lastHour + 2 * hour, false, now, zone))
    }
}
