package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.layout.FlickDirection
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.settings.GlidePauseLetters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen's half of picking letters mid-glide (#1519): which letters a
 * pause offers, which one the finger is on, and the walk out to a pick cut
 * from the stroke the decoder reads.
 */
class GlideLetterPickTest {

    @Test
    fun `flick letters are offered where they point, letters only`() {
        val key = Key(
            "u",
            flick = mapOf(
                FlickDirection.UP_RIGHT to "ü",
                FlickDirection.DOWN to "€",
                FlickDirection.LEFT to "U",
                FlickDirection.DOWN_RIGHT to "7",
            ),
        )
        val letters = pauseLettersOf(key, GlidePauseLetters.FLICKS)
        assertEquals(listOf(PauseLetter("ü", 'ü'.code, FlickDirection.UP_RIGHT)), letters)
    }

    @Test
    fun `long-press letters fill the cells above the finger first`() {
        val key = Key("a", longPress = listOf("à", "@", "á", "ä", "a"))
        val letters = pauseLettersOf(key, GlidePauseLetters.ALTERNATES)
        assertEquals(listOf("à", "á", "ä"), letters.map { it.text })
        assertEquals(
            listOf(FlickDirection.UP, FlickDirection.UP_LEFT, FlickDirection.UP_RIGHT),
            letters.map { it.direction },
        )
    }

    @Test
    fun `a key that types no letter offers nothing`() {
        val comma = Key(",", flick = mapOf(FlickDirection.UP to "é"))
        assertTrue(pauseLettersOf(comma, GlidePauseLetters.FLICKS).isEmpty())
        assertTrue(pauseLettersOf(Key("u"), GlidePauseLetters.FLICKS).isEmpty())
        assertTrue(pauseLettersOf(null, GlidePauseLetters.ALTERNATES).isEmpty())
    }

    @Test
    fun `the cell under the finger picks its letter`() {
        val laid = listOf(FlickDirection.UP_RIGHT, FlickDirection.DOWN)
        assertEquals(PAD_CHOOSING, pauseLetterAt(laid, 0f, 0f, 100f, 100f))
        assertEquals(0, pauseLetterAt(laid, 90f, -95f, 100f, 100f))
        assertEquals(1, pauseLetterAt(laid, 0f, 100f, 100f, 100f))
        // An empty cell is still choosing; past the pad's edge is leaving it.
        assertEquals(PAD_CHOOSING, pauseLetterAt(laid, -100f, 0f, 100f, 100f))
        assertEquals(PAD_LEFT, pauseLetterAt(laid, 160f, 0f, 100f, 100f))
        assertEquals(PAD_LEFT, pauseLetterAt(laid, 0f, -151f, 100f, 100f))
    }

    // Along to the key at (100, 0), a pause there, the pick up at (100, -90),
    // a straight walk on to (300, -90) and a turn down: the next letters.
    private val stroke = listOf(
        GesturePoint(0f, 0f, 0),
        GesturePoint(50f, 0f, 100),
        GesturePoint(100f, 0f, 200),
        GesturePoint(100f, 0f, 700),
        GesturePoint(100f, -90f, 2_000),
        GesturePoint(150f, -90f, 2_050),
        GesturePoint(200f, -90f, 2_100),
        GesturePoint(250f, -90f, 2_150),
        GesturePoint(300f, -90f, 2_200),
        GesturePoint(300f, 0f, 2_250),
        GesturePoint(300f, 50f, 2_300),
    )
    private val pick = GesturePoint(100f, -90f)

    @Test
    fun `the walk out to a letter is cut up to its first bend`() {
        val (out, _) = cutPickExits(stroke, intArrayOf(4), listOf(pick), emptyList(), 35f)
        assertEquals(7, out.size)
        // The pause stays, and so does the turn toward the next letter.
        assertEquals(stroke[3], out[3])
        assertEquals(300f, out[4].x)
        assertEquals(-90f, out[4].y)
        // The time spent choosing is gone.
        assertTrue(out[4].t < stroke[8].t)
        assertTrue(out[4].t > out[3].t)
    }

    @Test
    fun `a shift crossing inside a cut lands where the cut ends`() {
        val (_, cuts) = cutPickExits(stroke, intArrayOf(4), listOf(pick), listOf(2, 6, 9), 35f)
        assertEquals(listOf(2, 4, 5), cuts)
    }

    @Test
    fun `a pick with nothing drawn after it cuts nothing`() {
        val drawn = stroke.take(4)
        val (out, _) = cutPickExits(drawn, intArrayOf(4), listOf(pick), emptyList(), 35f)
        assertEquals(drawn, out)
    }
}
