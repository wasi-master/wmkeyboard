package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.ime.TwoThumbKit.delete
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.drain
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.keyboard
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.plantDictionary
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.settings
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.swipe
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.tap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Backspace on an open two-thumb word goes back to the reading it showed, not a
 * fresh decode of the shorter gestures (#1013). The word list changes behind the
 * word, so a fresh decode could only come out differently.
 */
@RunWith(RobolectricTestRunner::class)
class TwoThumbRestoredReadingTest {

    @Test
    fun `a backspace brings back the reading shown before the last gesture`() {
        val (service, editor) = keyboard(settings())
        plantDictionary(service, "qwerty" to 500)
        swipe(service)
        drain(service)
        assertEquals("fixture: the dictionary reads the stroke", "qwerty", editor.text.toString())
        tap(service, "s")
        drain(service)

        plantDictionary(service, "qwery" to 900)
        service.onKey(delete)
        drain(service)

        assertEquals("qwerty", editor.text.toString())
    }

    @Test
    fun `the remembered readings go when the composing text is replaced whole`() {
        val (service, _) = keyboard(settings())
        plantDictionary(service, "qwerty" to 500)
        swipe(service)
        drain(service)
        val readings = WMKeyboardService::class.java.getDeclaredField("duetReadings")
            .apply { isAccessible = true }
            .get(service) as List<*>
        assertTrue("fixture: the reading is remembered", readings.isNotEmpty())

        service.onSuggestionTapped("hello")
        drain(service)

        assertTrue(readings.isEmpty())
    }
}
