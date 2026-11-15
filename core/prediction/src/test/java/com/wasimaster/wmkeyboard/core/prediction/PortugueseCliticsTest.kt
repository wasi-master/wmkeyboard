package com.wasimaster.wmkeyboard.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortugueseCliticsTest {

    private val words = setOf(
        "cortar", "cortas", "corta", "vender", "partir", "pôr", "fazemos", "fiz", "cortam", "cortou", "deu", "disse",
    )
    private val isWord: (String) -> Boolean = { it in words }

    private fun read(typed: String) = PortugueseClitics.readings(typed, isWord).map { it.word }

    @Test fun anInfinitiveDropsItsRAndTakesTheAccent() {
        assertTrue("cortá-lo" in read("cortá-lo"))
        assertTrue("cortá-lo" in read("corta-lo"))
        assertTrue("vendê-la" in read("vende-la"))
        assertTrue("parti-los" in read("parti-los"))
        assertTrue("pô-lo" in read("po-lo"))
    }

    @Test fun anSOrZFormDropsIt() {
        assertTrue("fazemo-lo" in read("fazemo-lo"))
        assertTrue("fi-lo" in read("fi-lo"))
        assertTrue("corta-lo" in read("corta-lo"))
    }

    @Test fun thePronounBegunOrNotTypedYet() {
        assertEquals(listOf("cortá-lo", "cortá-los"), read("cortá-lo").sorted())
        assertTrue(read("cortá-l").containsAll(listOf("cortá-lo", "cortá-la", "cortá-los", "cortá-las")))
        assertTrue(read("cortar-").containsAll(listOf("cortar-se", "cortar-me")))
        assertTrue("cortar-se" in read("cortar-s"))
    }

    @Test fun withNoHyphenTyped() {
        assertTrue("cortá-lo" in read("cortalo"))
        assertTrue("cortar-se" in read("cortarse"))
        assertTrue(read("cortar").isEmpty())
    }

    @Test fun nasalAndVowelEndings() {
        assertTrue("cortam-no" in read("cortam-no"))
        assertTrue("cortou-o" in read("cortou-o"))
        assertFalse("cortar-o" in read("cortar-o"))
        assertTrue("disse-lhe" in read("disse-lhe"))
    }

    @Test fun whatIsSpelledRightIsKnown() {
        assertTrue(PortugueseClitics.isEnclitic("cortá-lo", isWord))
        assertTrue(PortugueseClitics.isEnclitic("cortar-se", isWord))
        assertTrue(PortugueseClitics.isEnclitic("deu-me", isWord))
        assertFalse(PortugueseClitics.isEnclitic("cortar-lo", isWord))
        assertFalse(PortugueseClitics.isEnclitic("guarda-chuva", isWord))
        assertFalse(PortugueseClitics.isEnclitic("cortá-l", isWord))
    }
}
