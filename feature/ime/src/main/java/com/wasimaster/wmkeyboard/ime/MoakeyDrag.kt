package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.layout.FlickDirection
import kotlin.math.hypot

/**
 * One drag on a 모아키 (Moakey) consonant key, read the way Samsung's table reads
 * it. The first leg picks one of six vowels, and each change of direction after
 * that turns it into the next: right then back is ㅐ, forward again ㅑ, back again
 * ㅒ. Up, down and up again runs ㅗ ㅚ ㅛ, and down, up and down runs ㅜ ㅟ ㅠ.
 *
 * Fed the finger's offset from the down. Every [slopPx] of travel is one leg, so a
 * wobble shorter than the flick distance is not a bend. A leg the current vowel has
 * no turn for only moves the point the next leg is measured from, which is what
 * lets a straight drag run on as far as it likes.
 */
internal class MoakeyDrag(private val slopPx: Float) {

    /** The direction of the first leg, the arm the cross keeps lit through the bends. */
    var first: FlickDirection? = null
        private set

    /** The vowel so far, or null while the press is still a tap. */
    var vowel: Char? = null
        private set

    /** Whether a bend has changed the vowel from the first leg's. */
    var bent: Boolean = false
        private set

    private var fromX = 0f
    private var fromY = 0f

    fun move(dx: Float, dy: Float) {
        val current = vowel
        if (hypot(dx - fromX, dy - fromY) < slopPx) return
        val leg = resolveFlickDirection(dx - fromX, dy - fromY, slopPx) { true } ?: return
        fromX = dx
        fromY = dy
        if (current == null) {
            first = leg
            vowel = FIRST_LEG.getValue(leg)
        } else {
            TURNS[current]?.get(leg)?.let {
                vowel = it
                bent = true
            }
        }
    }

    private companion object {
        val FIRST_LEG: Map<FlickDirection, Char> = mapOf(
            FlickDirection.RIGHT to 'ㅏ',
            FlickDirection.LEFT to 'ㅓ',
            FlickDirection.UP to 'ㅗ',
            FlickDirection.DOWN to 'ㅜ',
            FlickDirection.UP_LEFT to 'ㅣ',
            FlickDirection.UP_RIGHT to 'ㅣ',
            FlickDirection.DOWN_LEFT to 'ㅡ',
            FlickDirection.DOWN_RIGHT to 'ㅡ',
        )

        private val LEFTISH = listOf(FlickDirection.LEFT, FlickDirection.UP_LEFT, FlickDirection.DOWN_LEFT)
        private val RIGHTISH = listOf(FlickDirection.RIGHT, FlickDirection.UP_RIGHT, FlickDirection.DOWN_RIGHT)
        private val UPISH = listOf(FlickDirection.UP, FlickDirection.UP_LEFT, FlickDirection.UP_RIGHT)
        private val DOWNISH = listOf(FlickDirection.DOWN, FlickDirection.DOWN_LEFT, FlickDirection.DOWN_RIGHT)

        private fun turns(vararg legs: Pair<List<FlickDirection>, Char>): Map<FlickDirection, Char> =
            buildMap { for ((directions, vowel) in legs) for (d in directions) put(d, vowel) }

        /** Samsung's bend table, with its sixteen sectors read as the eight arms. */
        val TURNS: Map<Char, Map<FlickDirection, Char>> = mapOf(
            'ㅏ' to turns(LEFTISH + FlickDirection.UP + FlickDirection.DOWN to 'ㅐ'),
            'ㅐ' to turns(RIGHTISH to 'ㅑ'),
            'ㅑ' to turns(LEFTISH to 'ㅒ'),
            'ㅓ' to turns(RIGHTISH + FlickDirection.UP + FlickDirection.DOWN to 'ㅔ'),
            'ㅔ' to turns(LEFTISH to 'ㅕ'),
            'ㅕ' to turns(RIGHTISH to 'ㅖ'),
            'ㅗ' to turns(listOf(FlickDirection.RIGHT) to 'ㅘ', DOWNISH to 'ㅚ'),
            'ㅚ' to turns(listOf(FlickDirection.RIGHT) to 'ㅘ', listOf(FlickDirection.LEFT) to 'ㅕ', UPISH to 'ㅛ'),
            'ㅘ' to turns(listOf(FlickDirection.LEFT, FlickDirection.DOWN, FlickDirection.DOWN_LEFT) to 'ㅙ', listOf(FlickDirection.UP, FlickDirection.UP_RIGHT) to 'ㅛ'),
            'ㅙ' to turns(listOf(FlickDirection.UP, FlickDirection.UP_RIGHT) to 'ㅛ'),
            'ㅜ' to turns(listOf(FlickDirection.LEFT) to 'ㅝ', UPISH to 'ㅟ'),
            'ㅟ' to turns(listOf(FlickDirection.LEFT) to 'ㅝ', DOWNISH to 'ㅠ'),
            'ㅝ' to turns(RIGHTISH + FlickDirection.UP to 'ㅞ'),
            'ㅡ' to turns(UPISH to 'ㅢ'),
            'ㅢ' to turns(
                listOf(FlickDirection.LEFT, FlickDirection.DOWN_LEFT) to 'ㅕ',
                listOf(FlickDirection.RIGHT) to 'ㅑ',
                listOf(FlickDirection.DOWN, FlickDirection.DOWN_RIGHT) to 'ㅠ',
            ),
        )
    }
}
