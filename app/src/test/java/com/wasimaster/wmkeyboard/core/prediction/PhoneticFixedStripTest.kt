package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.AvroPhonetic
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A phonetic layout's strip set to keep its first two chips still: the buffer
 * as typed, then the rules' reading of it, then the chosen suggestions.
 */
class PhoneticFixedStripTest {

    private val english = Trie().apply {
        insert("the", 10000)
        insert("to", 9800)
        insert("am", 4750)
        insert("amazing", 800)
        insert("ask", 3000)
        insert("asia", 700)
        insert("aside", 600)
        insert("hello", 830)
        insert("help", 900)
        insert("helpful", 500)
        insert("helper", 300)
    }

    private val bengali = BengaliPhoneticIndex(
        listOf(
            "আমি" to 9000,
            "আছি" to 6900,
            "তো" to 5200,
            "আসি" to 2300,
            "হ্যালো" to 1900,
            "আম" to 1566,
        ),
    )

    private val spellings = SpellingMap.load(
        "hello\tহ্যালো\n".byteInputStream(Charsets.UTF_8),
        "to\tতো\n".byteInputStream(Charsets.UTF_8),
        loanwordStreams = 1,
    )

    private fun engine(
        source: PhoneticStripSource?,
        mixing: Boolean = false,
        autoEnglish: Boolean = false,
    ) = SuggestionEngine(english, bengali, UserLexicon(null), spellings).apply {
        primaryLanguageId = "bn"
        englishSources = false
        englishAsSecondary = mixing
        phoneticAutoEnglish = autoEnglish
        phoneticFixedStrip = source
    }

    private fun SuggestionEngine.avro(buffer: String, slots: Int = 3) =
        suggest(buffer, previousWord = null, phoneticLanguage = "bn", phoneticSlots = slots)

    private fun isLatin(word: String) = word.all { it in 'a'..'z' || it in 'A'..'Z' }

    @Test fun theFirstTwoChipsAreTheBufferAndItsReading() {
        for (source in PhoneticStripSource.entries) {
            for (mixing in listOf(false, true)) {
                val e = engine(source, mixing = mixing)
                for (buffer in listOf("asi", "ami", "hello", "wasi", "to")) {
                    val strip = e.avro(buffer)
                    assertEquals("$source $buffer", buffer, strip[0])
                    assertEquals("$source $buffer", AvroPhonetic.transliterate(buffer), strip[1])
                }
            }
        }
    }

    @Test fun offLeavesTheOrdinaryStrip() {
        val plain = SuggestionEngine(english, bengali, UserLexicon(null), spellings)
        val off = engine(source = null)
        for (buffer in listOf("asi", "hello", "to")) {
            assertEquals(buffer, plain.avro(buffer), off.avro(buffer))
        }
    }

    @Test fun nativeFillsTheRestWithTheLanguagesOwnWords() {
        val strip = engine(PhoneticStripSource.NATIVE).avro("asi", slots = 5)
        // আসি is the reading itself, so it is not offered twice.
        assertEquals(listOf("asi", "আসি", "আছি"), strip.take(3))
        assertTrue(strip.drop(2).none(::isLatin))
    }

    @Test fun englishFillsTheRestWithEnglishEvenWithoutEnglishAsASecondary() {
        val strip = engine(PhoneticStripSource.ENGLISH, mixing = false).avro("as", slots = 5)
        assertEquals(listOf("as", "আস"), strip.take(2))
        assertTrue(strip.drop(2).isNotEmpty())
        assertTrue(strip.drop(2).all(::isLatin))
        assertEquals("ask", strip[2])
    }

    @Test fun englishNotationSpellsNoEnglish() {
        // `,,` is Avro's hasant, not anything English spells.
        val strip = engine(PhoneticStripSource.ENGLISH).avro("k,,", slots = 5)
        assertEquals(2, strip.size)
    }

    @Test fun smartLeadsWithTheLanguageTheWordReadsAs() {
        val e = engine(PhoneticStripSource.SMART)
        // A Bangla word: its siblings first.
        assertFalse(isLatin(e.avro("asi")[2]))
        // An English word only English has: English first.
        val help = e.avro("help", slots = 5)
        assertTrue(help.toString(), isLatin(help[2]))
    }

    @Test fun smartOffersTheOtherLanguageOnAWiderStrip() {
        val strip = engine(PhoneticStripSource.SMART).avro("asi", slots = 5)
        assertFalse(isLatin(strip[2]))
        assertTrue(strip.toString(), strip.subList(3, 5).any(::isLatin))
    }

    @Test fun theCommitDoesNotFollowTheStrip() {
        for (source in PhoneticStripSource.entries) {
            val e = engine(source)
            // The strip leads with the Latin buffer; a space still writes Bangla.
            assertEquals("asi", e.avro("asi").first())
            assertEquals("আছি", e.phoneticCommit("bn", "asi")!!.output)
        }
    }
}
