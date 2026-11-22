package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.KeyCenter
import java.text.Normalizer
import kotlin.math.roundToInt

/**
 * A letter picked off a key in the middle of a glide (#1519): the key was
 * paused on, its extra letters came up around the finger, and the finger slid
 * onto [codePoint]. The word the stroke is read as has to contain it.
 *
 * [base] is the letter the key itself types, lowercased; [x], [y] the centre
 * the grid gives that letter, which is where the picked letter is put on the
 * grid for this stroke. [segmentStart] is the clock of the first sample of the
 * word segment it was picked in, which is how a stroke chained across the
 * spacebar knows which of its words the letter belongs to: a segment's first
 * sample is never trimmed or re-timed. [t] is when the pause that offered it
 * ended, a time inside that segment.
 */
internal data class GlidePin(
    val codePoint: Int,
    val base: Int,
    val x: Float,
    val y: Float,
    val segmentStart: Long,
    val t: Long,
)

/**
 * A stroke's grid with its picked letters on it, and the picks themselves.
 *
 * Rides to the service as the stroke's ordinary key list: the glide callbacks
 * already carry one, and `ServiceKeyboardContent` has no room for another
 * parameter. Anything that only reads keys sees a grid with one more letter
 * on it, at the centre of the key it was picked from; [GlidePins.on] is how
 * the decode finds the picks again.
 *
 * Equality is a list's: two of these are the same grid when their keys are,
 * which is what the service's key-map cache asks.
 */
internal class PinnedKeys(
    /** The grid as the stroke began, before any pick. */
    val base: List<KeyCenter>,
    val pins: List<GlidePin>,
    grid: List<KeyCenter>,
) : List<KeyCenter> by grid {
    override fun equals(other: Any?): Boolean =
        other is List<*> && other.size == size && indices.all { this[it] == other[it] }

    override fun hashCode(): Int = fold(1) { acc, key -> 31 * acc + key.hashCode() }
}

/** The pure half of picking letters mid-glide (#1519); the screen and the service share it. */
internal object GlidePins {

    /**
     * [base] with [pins] on it: each picked letter at its key's centre, and no
     * longer anywhere else. A letter the layout also has on a key of its own
     * would otherwise keep that key, since the decoder's grid files a letter
     * under the first key that names it, and the word would have to be drawn
     * through the other key to spell the pick.
     */
    fun pinned(base: List<KeyCenter>, pins: List<GlidePin>): PinnedKeys {
        val picked = pins.mapTo(HashSet()) { it.codePoint }
        val grid = ArrayList<KeyCenter>(base.size + pins.size)
        base.filterTo(grid) { Character.toLowerCase(it.codePoint) !in picked }
        val placed = HashSet<Int>()
        for (pin in pins) {
            if (placed.add(pin.codePoint)) grid.add(KeyCenter(pin.codePoint, pin.x, pin.y))
        }
        return PinnedKeys(base, pins.toList(), grid)
    }

    /**
     * The picks that belong to the word segment [points]: picked in it, by its
     * first sample's clock, or failing that (a decode of the segment with its
     * walk from the spacebar cut off) picked between its first and last
     * samples. Empty for a grid nobody picked on.
     */
    fun on(keys: List<KeyCenter>, points: List<GesturePoint>): List<GlidePin> {
        val pins = (keys as? PinnedKeys)?.pins ?: return emptyList()
        val first = points.firstOrNull() ?: return emptyList()
        val last = points.last()
        return pins.filter { it.segmentStart == first.t || it.t in first.t..last.t }
    }

    /** Whether [word] has every one of [pins]' letters, in the order they were picked. */
    fun spells(word: String, pins: List<GlidePin>): Boolean {
        val folded = fold(word)
        var at = 0
        for (pin in pins) {
            val found = indexOfCodePoint(folded, pin.codePoint, at)
            if (found < 0) return false
            at = found + Character.charCount(pin.codePoint)
        }
        return true
    }

    /**
     * The word to type when no reading has the picked letters: [literal], the
     * letters the stroke marked (`GlideLiteral`), with each pick put in for
     * the first of its key's own letter after the previous pick — the pause
     * that offered the pick marks that key, so it is nearly always there. A
     * pick whose key the literal never marked goes in at [fractions]' share
     * of the way along it, the share of the stroke drawn before the pick.
     * With no literal at all, the picks alone.
     */
    fun splice(literal: String?, pins: List<GlidePin>, fractions: FloatArray): String {
        val letters = ArrayList<Int>()
        literal?.let { fold(it) }?.codePoints()?.forEach { letters.add(it) }
        var at = 0
        pins.forEachIndexed { index, pin ->
            var found = -1
            for (i in at until letters.size) {
                if (letters[i] == pin.base || letters[i] == pin.codePoint) {
                    found = i
                    break
                }
            }
            if (found >= 0) {
                letters[found] = pin.codePoint
                at = found + 1
            } else {
                val share = fractions.getOrElse(index) { 1f }.coerceIn(0f, 1f)
                val place = (share * letters.size).roundToInt().coerceIn(at, letters.size)
                letters.add(place, pin.codePoint)
                at = place + 1
            }
        }
        val out = StringBuilder()
        for (cp in letters) out.appendCodePoint(cp)
        return out.toString()
    }

    /**
     * How far along [points] each of [pins] was picked, as a share of the
     * stroke's time: what [splice] places a pick by when the literal has no
     * letter of its key.
     */
    fun fractions(points: List<GesturePoint>, pins: List<GlidePin>): FloatArray {
        val first = points.firstOrNull()?.t ?: return FloatArray(pins.size) { 1f }
        val span = (points.last().t - first).toFloat()
        return FloatArray(pins.size) { i ->
            if (span <= 0f) 1f else ((pins[i].t - first) / span).coerceIn(0f, 1f)
        }
    }

    /**
     * The letter [text] puts in a word, as a lowercase code point, or null
     * when it is not one letter: a symbol, a digit, a word, a letter with a
     * mark that has no precomposed form. Composed first, so an "ä" a layout
     * spells as `a` and a combining diaeresis still counts.
     */
    fun letterOf(text: String): Int? {
        val composed = Normalizer.normalize(text, Normalizer.Form.NFC)
        if (composed.isEmpty()) return null
        val cp = composed.codePointAt(0)
        if (Character.charCount(cp) != composed.length) return null
        if (!Character.isLetter(cp)) return null
        return Character.toLowerCase(cp)
    }

    private fun fold(word: String): String = Normalizer.normalize(word.lowercase(), Normalizer.Form.NFC)

    private fun indexOfCodePoint(text: String, cp: Int, from: Int): Int {
        var i = from
        while (i < text.length) {
            val here = text.codePointAt(i)
            if (here == cp) return i
            i += Character.charCount(here)
        }
        return -1
    }
}
