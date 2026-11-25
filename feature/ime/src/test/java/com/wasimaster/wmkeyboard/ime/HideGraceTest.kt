package com.wasimaster.wmkeyboard.ime

import android.view.inputmethod.InputMethodManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Messenger's edit: an app hide held for the show it sends next. */
class HideGraceTest {

    private var scheduled: Pair<Long, Runnable>? = null
    private var hides = 0
    private val grace = HideGrace(
        schedule = { delayMs, block -> scheduled = delayMs to block },
        unschedule = { block -> if (scheduled?.second === block) scheduled = null },
        hide = { hides++ },
    )

    private fun fire() {
        val block = scheduled?.second ?: error("nothing scheduled")
        scheduled = null
        block.run()
    }

    @Test
    fun `a show inside the hold keeps the keyboard up`() {
        val results = mutableListOf<Int>()
        grace.hold(MESSENGER) { results += it }
        assertTrue(grace.holding)
        assertEquals(HideGrace.HOLD_MS, scheduled?.first)

        grace.shown()

        assertFalse(grace.holding)
        assertEquals(null, scheduled)
        assertEquals(0, hides)
        assertEquals(listOf(InputMethodManager.RESULT_UNCHANGED_SHOWN), results)
    }

    @Test
    fun `no show and the hide goes ahead late`() {
        val results = mutableListOf<Int>()
        grace.hold(MESSENGER) { results += it }

        fire()

        assertEquals(1, hides)
        assertFalse(grace.holding)
        assertEquals(listOf(InputMethodManager.RESULT_HIDDEN), results)
    }

    @Test
    fun `the app's next field waits longer for its show`() {
        grace.hold(MESSENGER, null)
        grace.fieldStarted(MESSENGER)
        assertEquals(HideGrace.AFTER_START_MS, scheduled?.first)

        grace.shown()
        assertEquals(0, hides)
    }

    @Test
    fun `another app's field does not stretch the hold`() {
        grace.hold(MESSENGER, null)
        grace.fieldStarted("com.example.other")
        assertEquals(HideGrace.HOLD_MS, scheduled?.first)
    }

    @Test
    fun `a second hide joins the held one`() {
        val results = mutableListOf<Int>()
        grace.hold(MESSENGER) { results += 1 }
        grace.hold(MESSENGER) { results += 2 }

        fire()

        assertEquals(1, hides)
        assertEquals(listOf(1, 2), results)
    }

    @Test
    fun `a hide that went through anyway settles the held one without another`() {
        val results = mutableListOf<Int>()
        grace.hold(MESSENGER) { results += it }

        grace.hiddenAnyway()

        assertEquals(0, hides)
        assertEquals(null, scheduled)
        assertEquals(listOf(InputMethodManager.RESULT_HIDDEN), results)
    }

    @Test
    fun `a show with nothing held does nothing`() {
        grace.shown()
        grace.hiddenAnyway()
        assertEquals(0, hides)
        assertEquals(null, scheduled)
    }

    @Test
    fun `only Messenger, and only up to Android 15`() {
        assertTrue(HideGrace.applies(MESSENGER, sdk = 35))
        assertTrue(HideGrace.applies(MESSENGER, sdk = 30))
        assertFalse(HideGrace.applies(MESSENGER, sdk = 36))
        assertFalse(HideGrace.applies("com.whatsapp", sdk = 35))
        assertFalse(HideGrace.applies(null, sdk = 35))
    }

    private companion object {
        const val MESSENGER = "com.facebook.orca"
    }
}
