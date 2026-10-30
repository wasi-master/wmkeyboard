package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoakeyDragTest {

    private val slop = 20f

    /** Walks a drag through [points], in small steps as a finger would report them. */
    private fun drag(vararg points: Pair<Float, Float>): MoakeyDrag {
        val out = MoakeyDrag(slop)
        var x = 0f
        var y = 0f
        for ((toX, toY) in points) {
            repeat(STEPS) { step ->
                val t = (step + 1f) / STEPS
                out.move(x + (toX - x) * t, y + (toY - y) * t)
            }
            x = toX
            y = toY
        }
        return out
    }

    @Test
    fun `a short wobble is still a tap`() {
        assertNull(drag(10f to 0f, 0f to 5f).vowel)
    }

    @Test
    fun `a first leg picks one of the six vowels`() {
        assertEquals('ㅏ', drag(40f to 0f).vowel)
        assertEquals('ㅓ', drag(-40f to 0f).vowel)
        assertEquals('ㅗ', drag(0f to -40f).vowel)
        assertEquals('ㅜ', drag(0f to 40f).vowel)
        assertEquals('ㅣ', drag(30f to -30f).vowel)
        assertEquals('ㅣ', drag(-30f to -30f).vowel)
        assertEquals('ㅡ', drag(30f to 30f).vowel)
        assertEquals('ㅡ', drag(-30f to 30f).vowel)
    }

    @Test
    fun `a straight drag runs on without bending`() {
        val long = drag(200f to 0f)
        assertEquals('ㅏ', long.vowel)
        assertFalse(long.bent)
    }

    @Test
    fun `back and forth walks the y-forms`() {
        assertEquals('ㅐ', drag(40f to 0f, 0f to 0f).vowel)
        assertEquals('ㅑ', drag(40f to 0f, 0f to 0f, 40f to 0f).vowel)
        assertEquals('ㅒ', drag(40f to 0f, 0f to 0f, 40f to 0f, 0f to 0f).vowel)
        assertEquals('ㅔ', drag(-40f to 0f, 0f to 0f).vowel)
        assertEquals('ㅕ', drag(-40f to 0f, 0f to 0f, -40f to 0f).vowel)
        assertEquals('ㅖ', drag(-40f to 0f, 0f to 0f, -40f to 0f, 0f to 0f).vowel)
        assertEquals('ㅛ', drag(0f to -40f, 0f to 0f, 0f to -40f).vowel)
        assertEquals('ㅠ', drag(0f to 40f, 0f to 0f, 0f to 40f).vowel)
    }

    @Test
    fun `a corner makes the compound vowels`() {
        assertEquals('ㅘ', drag(0f to -40f, 40f to -40f).vowel)
        assertEquals('ㅙ', drag(0f to -40f, 40f to -40f, 0f to -40f).vowel)
        assertEquals('ㅝ', drag(0f to 40f, -40f to 40f).vowel)
        assertEquals('ㅞ', drag(0f to 40f, -40f to 40f, 0f to 40f).vowel)
        assertEquals('ㅢ', drag(30f to 30f, 30f to -10f).vowel)
    }

    @Test
    fun `the first leg stays the arm through the bends`() {
        val bend = drag(40f to 0f, 0f to 0f)
        assertTrue(bend.bent)
        assertEquals(com.wasimaster.wmkeyboard.core.layout.FlickDirection.RIGHT, bend.first)
    }

    private companion object {
        const val STEPS = 8
    }
}
