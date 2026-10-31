package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.settings.NumberGrouping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NumberPredictionTest {

    private fun predict(
        composing: String,
        hints: List<String?>,
        group: Boolean = true,
        grouping: NumberGrouping = NumberGrouping.WESTERN,
        locale: String = "en",
    ) = NumberPrediction.predict(composing, hints, group, grouping, locale)

    @Test
    fun theHintsSpellTheNumber() {
        assertEquals("123", predict("qwe", listOf("1", "2", "3")))
        // Shift changes the letters, not the keys.
        assertEquals("123", predict("QWE", listOf("1", "2", "3")))
    }

    @Test
    fun aKeyWithoutADigitHintBreaksTheRun() {
        // "wa" on QWERTY: a carries no digit.
        assertNull(predict("wa", listOf("2", null)))
        // An accent hint is not a digit either.
        assertNull(predict("we", listOf("2", "é")))
        // Nor is a two-character hint.
        assertNull(predict("we", listOf("2", "10")))
    }

    @Test
    fun aKeysDigitCountsWhereverItsHoldListsIt() {
        assertEquals("1", NumberPrediction.digitHint(listOf("à", "á", "1")))
        assertEquals("4", NumberPrediction.digitHint(listOf("4", "ŕ")))
        assertNull(NumberPrediction.digitHint(listOf("é", "10")))
        assertNull(NumberPrediction.digitHint(emptyList()))
    }

    @Test
    fun aTypedDigitStandsForItself() {
        // A 3 typed from the popup lands as a synthetic key with no hint.
        assertEquals("123", predict("qw3", listOf("1", "2", null)))
    }

    @Test
    fun oneLetterIsNotOffered() {
        assertNull(predict("i", listOf("8")))
        assertEquals("88", predict("ii", listOf("8", "8")))
    }

    @Test
    fun aFrameOutOfStepIsNotTrusted() {
        assertNull(predict("qwe", listOf("1", "2")))
        assertNull(predict("qwe", listOf("1", "2", "3", "4")))
    }

    @Test
    fun longRunsGroupLikeTheNumberChip() {
        val hints = listOf("1", "2", "3", "4", "5", "6", "7")
        assertEquals("1,234,567", predict("qwertyu", hints))
        assertEquals("12,34,567", predict("qwertyu", hints, grouping = NumberGrouping.SOUTH_ASIAN))
        assertEquals("12,34,567", predict("qwertyu", hints, grouping = NumberGrouping.AUTO, locale = "bn"))
        // The chip's own rules: under five digits, or a leading zero, stay as typed.
        assertEquals("1234", predict("qwer", listOf("1", "2", "3", "4")))
        assertEquals("01234", predict("pqwer", listOf("0", "1", "2", "3", "4")))
        // Sixteen digits is a card number, not a quantity.
        val sixteen = List(16) { "1" }
        assertEquals("1".repeat(16), predict("q".repeat(16), sixteen))
    }

    @Test
    fun groupingFollowsTheNumberChipSwitch() {
        val hints = listOf("1", "2", "3", "4", "5", "6", "7")
        assertEquals("1234567", predict("qwertyu", hints, group = false))
    }

    @Test
    fun nonAsciiDigitsAreOfferedAsTyped() {
        // Bengali digit hints spell a Bengali number; the grouper is ASCII-only.
        val hints = listOf("১", "২", "৩", "৪", "৫", "৬")
        assertEquals("১২৩৪৫৬", predict("qwerty", hints))
    }

    @Test
    fun theNumberTakesTheLastVisibleSlot() {
        val strip = listOf("we", "were", "went", "west")
        assertEquals(listOf("we", "were", "23", "went", "west"), NumberPrediction.place(strip, "23", 3))
        // A short strip gets it at the end; an empty one gets only it.
        assertEquals(listOf("we", "23"), NumberPrediction.place(listOf("we"), "23", 3))
        assertEquals(listOf("23"), NumberPrediction.place(emptyList(), "23", 3))
        // A one-slot strip still puts it first, since first is the only slot.
        assertEquals(listOf("23", "we"), NumberPrediction.place(listOf("we"), "23", 1))
        // Nothing to place, or already there: the strip is untouched.
        assertEquals(strip, NumberPrediction.place(strip, null, 3))
        assertEquals(listOf("23", "we"), NumberPrediction.place(listOf("23", "we"), "23", 3))
    }
}
