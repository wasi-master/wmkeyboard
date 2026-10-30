package com.wasimaster.wmkeyboard.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BengaliSuffixes.join] recognises a known stem plus a Bengali ending and puts
 * the two readings together, and declines when the stem is not a word.
 *
 * The stem reading and the ending's transliteration are both supplied here, so
 * the test pins the join logic — which endings are seen, where the stem is cut,
 * and the guard against joining onto nonsense — without the decode behind it.
 */
class BengaliSuffixesTest {

    /** A stem dictionary; anything outside it is "not a word". */
    private val stems = mapOf(
        "chele" to "ছেলে",
        "bondhu" to "বন্ধু",
        "boi" to "বই",
    )

    /** Endings as the rules would transliterate them, so the seam is checked. */
    private val endings = mapOf(
        "gulo" to "গুলো",
        "gulor" to "গুলোর",
        "gula" to "গুলা",
        "guli" to "গুলি",
        "der" to "দের",
        "derke" to "দেরকে",
        "khana" to "খানা",
    )

    private fun join(roman: String): List<String> =
        BengaliSuffixes.join(roman, stems::get) { endings[it] ?: it }

    @Test
    fun joinsAKnownStemAndEnding() {
        assertEquals(listOf("ছেলেগুলো"), join("chelegulo"))
        assertEquals(listOf("বন্ধুদের"), join("bondhuder"))
        assertEquals(listOf("বইখানা"), join("boikhana"))
    }

    @Test
    fun longerEndingWins() {
        // Ends in গুলোর, not a গুলো stem of "chelegulor".
        assertEquals(listOf("ছেলেগুলোর"), join("chelegulor"))
        assertEquals(listOf("বন্ধুদেরকে"), join("bondhuderke"))
    }

    @Test
    fun declinesWhenTheStemIsNotAWord() {
        // "xyz" is in no dictionary, so there is nothing to put an ending on.
        assertTrue(join("xyzgulo").isEmpty())
    }

    @Test
    fun declinesAWordThatIsNotStemPlusEnding() {
        assertTrue(join("chele").isEmpty())
        assertTrue(join("bondhu").isEmpty())
    }

    @Test
    fun declinesWhenNothingIsLeftBeforeTheEnding() {
        // The ending with too short a stem in front of it is not a plural.
        assertTrue(join("gulo").isEmpty())
        assertTrue(join("der").isEmpty())
    }
}
