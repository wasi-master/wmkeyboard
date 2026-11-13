package com.wasimaster.wmkeyboard.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApostrophesTest {

    @Test
    fun fixesCommonContractions() {
        assertEquals("aren't", Apostrophes.fix("arent"))
        assertEquals("isn't", Apostrophes.fix("isnt"))
        assertEquals("don't", Apostrophes.fix("dont"))
        assertEquals("won't", Apostrophes.fix("wont"))
        assertEquals("can't", Apostrophes.fix("cant"))
        assertEquals("y'all", Apostrophes.fix("yall"))
        assertEquals("o'clock", Apostrophes.fix("oclock"))
        assertEquals("ma'am", Apostrophes.fix("maam"))
    }

    @Test
    fun capitalizesI() {
        assertEquals("I'm", Apostrophes.fix("im"))
        assertEquals("I've", Apostrophes.fix("ive"))
        assertEquals("I", Apostrophes.fix("i"))
        // Already correct: nothing to do.
        assertNull(Apostrophes.fix("I"))
    }

    /**
     * The word lists are lowercase throughout, so a correctly typed "i'm" is a
     * known word that arrives without its capital and has nowhere else to get
     * one — the lone "i" above has exactly the same problem.
     */
    @Test
    fun capitalizesITypedWithItsApostrophe() {
        assertEquals("I'm", Apostrophes.fix("i'm"))
        assertEquals("I've", Apostrophes.fix("i've"))
        assertEquals("I'll", Apostrophes.fix("i'll"))
        assertEquals("I'd", Apostrophes.fix("i'd"))
        // Already correct, in either casing convention.
        assertNull(Apostrophes.fix("I'm"))
        assertNull(Apostrophes.fix("I'D"))
    }

    @Test
    fun preservesTypedCase() {
        assertEquals("Don't", Apostrophes.fix("Dont"))
        assertEquals("AREN'T", Apostrophes.fix("ARENT"))
        assertEquals("I'M", Apostrophes.fix("IM"))
        assertEquals("They're", Apostrophes.fix("Theyre"))
    }

    @Test
    fun leavesRealWordsAlone() {
        // Apostrophe-less forms that are real words must never be touched.
        for (word in listOf("its", "were", "well", "id", "hell",
                "shell", "wed", "shed", "lets", "sons", "whore", "hers")) {
            assertNull(word, Apostrophes.fix(word))
        }
        assertNull(Apostrophes.fix("hello"))
        assertNull(Apostrophes.fix(""))
    }

    @Test
    fun illIsRepairedDespiteBeingAWord() {
        // Typed for I'll far more often than for ill; backspace undoes it (#482).
        assertEquals("I'll", Apostrophes.fix("ill"))
        assertEquals("I'll", Apostrophes.fix("Ill"))
        assertNull(Apostrophes.offer("ill"))
    }

    /**
     * The same words [leavesRealWordsAlone] protects, once the user has drawn the
     * apostrophe through the glide key: the stroke said which word was meant, so
     * the guess the automatic fix refuses to make is no longer a guess.
     */
    @Test
    fun declaredApostropheReachesTheAmbiguousForms() {
        assertEquals("it's", Apostrophes.fixExplicit("its"))
        assertEquals("we're", Apostrophes.fixExplicit("were"))
        assertEquals("we'll", Apostrophes.fixExplicit("well"))
        assertEquals("I'll", Apostrophes.fixExplicit("ill"))
        assertEquals("I'd", Apostrophes.fixExplicit("id"))
        assertEquals("let's", Apostrophes.fixExplicit("lets"))
        assertEquals("she'd", Apostrophes.fixExplicit("shed"))
        assertEquals("Let's", Apostrophes.fixExplicit("Lets"))
    }

    /**
     * A declared apostrophe also reaches the ordinary table, so the feature works
     * with "Fix missing apostrophes" switched off — that setting is about
     * guessing, and this stroke is not a guess.
     */
    @Test
    fun declaredApostropheAlsoFixesTheUnambiguousForms() {
        assertEquals("don't", Apostrophes.fixExplicit("dont"))
        assertEquals("I'm", Apostrophes.fixExplicit("im"))
    }

    /** No table entry, no rewrite: a drawn apostrophe never invents a spelling. */
    @Test
    fun declaredApostropheLeavesUnknownWordsAlone() {
        assertNull(Apostrophes.fixExplicit("hello"))
        assertNull(Apostrophes.fixExplicit("developers"))
        assertNull(Apostrophes.fixExplicit("it's"))
        assertNull(Apostrophes.fixExplicit(""))
    }

    /** Gap list item 43: the two Latin abbreviations get their stops; their capitals are names. */
    @Test
    fun latinAbbreviationsGetTheirStops() {
        assertEquals("i.e.", Apostrophes.fix("ie"))
        assertEquals("e.g.", Apostrophes.fix("eg"))
        assertEquals("E.g.", Apostrophes.fix("Eg"))
        assertNull(Apostrophes.fix("IE"))
        assertNull(Apostrophes.fix("EG"))
        assertNull(Apostrophes.fix("i.e."))
    }

    @Test
    fun fixedWordsRoundTripToNull() {
        // Words already carrying their apostrophe are not in the table.
        assertNull(Apostrophes.fix("aren't"))
        assertNull(Apostrophes.fix("I'm"))
    }
}
