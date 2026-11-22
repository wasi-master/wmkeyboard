package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [DatePhrases] against the platform's own ICU (`android.icu`, which
 * Robolectric runs for real at the sdk `robolectric.properties` pins), read
 * through [DateSuggest] the way the date chip reads it.
 *
 * Expectations stick to words CLDR has said the same way for years, so a
 * newer Android's ICU does not turn them red; the one exception is pinned
 * on purpose: Hindi's कल means both yesterday and tomorrow, and stays quiet.
 */
@RunWith(RobolectricTestRunner::class)
class DatePhrasesTest {

    /** Monday, 10 August 2026, as in DateSuggestTest. */
    private val today = CalendarSystems.gregorianToJdn(2026, 8, 10)
    private val aug14 = CalendarSystems.gregorianToJdn(2026, 8, 14)

    private fun find(language: String, text: String) = DateSuggest.find(text, today, listOf(language))

    private fun days(language: String, text: String): Long? = find(language, text)?.let { it.jdn - today }

    @Test
    fun `German relative days, weekdays, counts and dates`() {
        assertEquals(1L, days("de", "wir sehen uns morgen"))
        assertEquals(2L, days("de", "bis übermorgen"))
        assertEquals(4L, days("de", "nächsten Freitag"))
        assertEquals(3L, days("de", "in 3 Tagen"))
        assertEquals(aug14, find("de", "am 14. August")?.jdn)
        assertEquals("14. August", find("de", "am 14. August")?.phrase)
    }

    @Test
    fun `German morning, backward and habitual phrases stay quiet`() {
        assertNull(find("de", "Guten Morgen"))
        assertNull(find("de", "heute Morgen"))
        assertNull(find("de", "letzten Freitag"))
        assertNull(find("de", "jeden Freitag"))
        assertNull(find("de", "gestern"))
    }

    @Test
    fun `matching ignores case and accents`() {
        assertEquals(2L, days("de", "UBERMORGEN"))
        assertEquals(2L, days("fr", "apres-demain"))
        assertEquals(1L, days("es", "hasta manana"))
    }

    @Test
    fun `French`() {
        assertEquals(1L, days("fr", "à demain"))
        assertEquals(4L, days("fr", "vendredi prochain"))
        assertEquals(3L, days("fr", "dans 3 jours"))
        assertEquals(7L, days("fr", "la semaine prochaine"))
        assertEquals(aug14, find("fr", "le 14 août")?.jdn)
    }

    @Test
    fun `Spanish, and its morning`() {
        assertEquals(1L, days("es", "nos vemos mañana"))
        assertEquals(2L, days("es", "pasado mañana"))
        assertEquals(3L, days("es", "dentro de 3 días"))
        assertEquals(aug14, find("es", "el 14 de agosto")?.jdn)
        assertNull(find("es", "por la mañana"))
        assertNull(find("es", "los viernes"))
    }

    @Test
    fun `Russian counts in every plural form`() {
        assertEquals(1L, days("ru", "до завтра"))
        assertEquals(2L, days("ru", "послезавтра"))
        assertEquals(3L, days("ru", "через 3 дня"))
        assertEquals(5L, days("ru", "через 5 дней"))
        assertEquals(21L, days("ru", "через 21 день"))
        assertEquals(aug14, find("ru", "14 августа")?.jdn)
        assertNull(find("ru", "вчера"))
    }

    @Test
    fun `Hindi, where कल points both ways`() {
        assertNull(find("hi", "कल"))
        assertEquals(4L, days("hi", "शुक्रवार"))
        assertEquals(aug14, find("hi", "14 अगस्त")?.jdn)
    }

    @Test
    fun `Bengali from ICU and the hand entries it lacks`() {
        assertEquals(1L, days("bn", "আগামীকাল"))
        // ICU says "আগামী পরশু"; the bare পরশু is a hand entry.
        assertEquals(2L, days("bn", "পরশু"))
        assertEquals(4L, days("bn", "শুক্রবার"))
        assertEquals(4L, days("bn", "আগামী শুক্রবার"))
        assertEquals(aug14, find("bn", "১৪ আগস্ট")?.jdn)
        // Ordinal endings and both spellings of January are hand entries too.
        assertEquals(CalendarSystems.gregorianToJdn(2026, 11, 20), find("bn", "২০শে নভেম্বর")?.jdn)
        assertEquals(CalendarSystems.gregorianToJdn(2027, 1, 1), find("bn", "১লা জানুয়ারী")?.jdn)
        assertNull(find("bn", "গত শুক্রবার"))
    }

    @Test
    fun `Bengali is read on an English keyboard by its script`() {
        assertEquals(1L, DateSuggest.find("কাল নয়, আগামীকাল", today, listOf("en"))?.let { it.jdn - today })
    }

    @Test
    fun `Japanese runs on without spaces, and next Friday is next week's`() {
        assertEquals(1L, days("ja", "また明日"))
        assertEquals(2L, days("ja", "明後日"))
        assertEquals(4L, days("ja", "金曜日"))
        // 来週の金曜日 is Friday of next week, not the soonest Friday.
        assertEquals(11L, days("ja", "来週の金曜日"))
        assertEquals(aug14, find("ja", "8月14日")?.jdn)
    }

    @Test
    fun `Arabic, marks and digits of either kind`() {
        // غدًا as ICU writes it, with its tanween; غدا as it is typed.
        assertEquals(1L, days("ar", "غدًا"))
        assertEquals(1L, days("ar", "غدا"))
        assertEquals(2L, days("ar", "بعد الغد"))
        assertEquals(4L, days("ar", "الجمعة"))
        assertEquals(aug14, find("ar", "14 أغسطس")?.jdn)
        assertEquals(aug14, find("ar", "١٤ أغسطس")?.jdn)
    }

    @Test
    fun `English hand rules stay first, and ICU adds counts`() {
        assertEquals("Tue 11 Aug", find("en", "see you tomorrow")?.annotation)
        assertEquals(3L, days("en", "back in 3 days"))
        assertNull(find("en", "every friday"))
        assertNull(find("en", "last friday"))
    }

    @Test
    fun `the chip speaks the phrase's language`() {
        val hit = find("de", "nächsten Freitag")!!
        assertTrue(hit.display, hit.display.contains("14"))
        assertTrue(hit.annotation.isNotBlank())
        // Not the English hand wording.
        assertTrue(hit.display, !hit.display.startsWith("Fri"))
    }

    @Test
    fun `findAll reads a whole note, today included`() {
        val text = "Abgabe nächsten Freitag 14:30, Treffen am 14. August, heute nichts"
        val found = DatePhrases.findAll(text, today, listOf("de"))
        assertEquals(listOf("nächsten Freitag", "14. August", "heute"), found.map { text.substring(it.start, it.end) })
        assertEquals(listOf(aug14, aug14, today), found.map { it.jdn })
    }
}
