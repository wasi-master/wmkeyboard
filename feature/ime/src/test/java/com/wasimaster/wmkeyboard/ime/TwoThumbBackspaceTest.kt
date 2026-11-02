package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.ime.TwoThumbKit.allUpPause
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.delete
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.drain
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.keyboard
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.plantDictionary
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.settings
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.swipe
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.tap
import com.wasimaster.wmkeyboard.ime.ui.GlideVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Backspace on a two-thumb word that is still open (#732), or was just typed (#858). */
@RunWith(RobolectricTestRunner::class)
class TwoThumbBackspaceTest {

    @Test
    fun `control - one backspace after an ordinary glide takes the word and its space`() {
        val (service, editor) = keyboard(settings(twoThumb = false))
        swipe(service, GlideVerdict.Word("hello"))
        drain(service)
        assertEquals("fixture: the glide types its word and space", "hello ", editor.text.toString())
        service.onKey(delete)
        drain(service)
        assertEquals("", editor.text.toString())
    }

    @Test
    fun `backspace before the first reading lands removes that swipe, not field text`() {
        val (service, editor) = keyboard(settings(), initial = "hi ")
        swipe(service)
        assertEquals("fixture: the decode is still in flight", "hi ", editor.text.toString())
        service.onKey(delete)
        drain(service)
        assertEquals("hi ", editor.text.toString())
    }

    @Test
    fun `backspace before a new word's first reading leaves the word before it alone`() {
        val (service, editor) = keyboard(settings(allUp = true))
        swipe(service)
        drain(service)
        allUpPause(service)
        val before = editor.text.toString()
        assertEquals("fixture: the pause typed the first word and its space", "qy ", before)
        swipe(service)
        assertEquals("fixture: the second decode is still in flight", before, editor.text.toString())
        service.onKey(delete)
        drain(service)
        assertEquals(before, editor.text.toString())
    }

    @Test
    fun `with backspace-undoes-swipe off, backspace takes one letter of the open word`() {
        val (service, editor) = keyboard(settings(undo = false))
        swipe(service)
        drain(service)
        val before = editor.text.toString()
        assertTrue("fixture: a reading of more than one letter: '$before'", before.length > 1)
        service.onKey(delete)
        drain(service)
        assertEquals(before.dropLast(1), editor.text.toString())
    }

    @Test
    fun `with it off, backspace before the first reading lands takes one letter of that reading`() {
        val (service, editor) = keyboard(settings(undo = false))
        swipe(service)
        assertEquals("fixture: the decode is still in flight", "", editor.text.toString())
        service.onKey(delete)
        drain(service)
        assertEquals("q", editor.text.toString())
    }

    /** #858: a typed word of two swipes loses only the second, and stays open. */
    @Test
    fun `backspace after the pause typed a two-swipe word takes back its last swipe`() {
        val (service, editor) = keyboard(settings(allUp = true))
        swipe(service)
        drain(service)
        swipe(service)
        drain(service)
        allUpPause(service)
        assertEquals("fixture: the pause typed both swipes as one word", "qyqy ", editor.text.toString())
        service.onKey(delete)
        drain(service)
        assertEquals("qy", editor.text.toString())
        // Still open: the next swipe joins it rather than starting a word.
        swipe(service)
        drain(service)
        assertEquals("qyqy", editor.text.toString())
    }

    /**
     * #895: a tapped letter that takes the pause's space back reopens the
     * word as its swipes, so Backspace past the letter takes back a swipe.
     */
    @Test
    fun `a tapped letter after the pause keeps the word's swipes for backspace`() {
        val (service, editor) = keyboard(settings(allUp = true, retractMs = RETRACT_MS))
        plantDictionary(service, "qyqy" to 300, "qyqya" to 200)
        swipe(service)
        drain(service)
        val oneSwipe = editor.text.toString()
        swipe(service)
        drain(service)
        allUpPause(service)
        assertEquals("fixture: the pause typed both swipes as one word", "qyqy ", editor.text.toString())
        tap(service, "a")
        drain(service)
        assertEquals("fixture: the letter took the space back", "qyqya", editor.text.toString())
        service.onKey(delete)
        drain(service)
        assertEquals("qyqy", editor.text.toString())
        service.onKey(delete)
        drain(service)
        assertEquals("the second swipe goes, not a letter", oneSwipe, editor.text.toString())
    }

    /**
     * #895: Backspace on a swipe that carried a reopened word on is an undo,
     * so the word stays open without its space.
     */
    @Test
    fun `backspace on a continuing swipe leaves the reopened word open`() {
        val (service, editor) = keyboard(settings(allUp = true, retractMs = RETRACT_MS))
        plantDictionary(service, "qyqy" to 300, "qyqyqy" to 200)
        swipe(service)
        drain(service)
        val oneSwipe = editor.text.toString()
        swipe(service)
        drain(service)
        allUpPause(service)
        assertEquals("fixture: the pause typed both swipes as one word", "qyqy ", editor.text.toString())
        swipe(service)
        drain(service)
        assertEquals("fixture: the swipe took the space back", "qyqyqy", editor.text.toString())
        service.onKey(delete)
        drain(service)
        assertEquals("qyqy", editor.text.toString())
        service.onKey(delete)
        drain(service)
        assertEquals(oneSwipe, editor.text.toString())
    }

    private companion object {
        const val RETRACT_MS = 2000
    }
}
