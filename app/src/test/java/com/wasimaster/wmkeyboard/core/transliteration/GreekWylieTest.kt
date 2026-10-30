package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GreekWylieTest {

    @Test
    fun `the rules read digraphs, the closing sigma and capitals`() {
        assertEquals("καλημερα", GreekPhonetic.transliterate("kalhmera"))
        assertEquals("θελω", GreekPhonetic.transliterate("8elw"))
        assertEquals("Φιλος", GreekPhonetic.transliterate("Filos"))
        assertEquals("ανθρωπος", GreekPhonetic.transliterate("anthrwpos"))
        assertEquals("123", GreekPhonetic.transliterate("123"))
    }

    @Test
    fun `every spelling of a sound finds the word, commonest first`() {
        val index = GreekPhoneticIndex(
            listOf("καλημέρα" to 900, "θέλω" to 800, "ξέρω" to 700, "όχι" to 600, "αυτό" to 500, "μπράβο" to 400, "ευχαριστώ" to 300, "ώρα" to 200, "όρα" to 100),
        )
        for (typed in listOf("kalimera", "kalhmera", "kalymera")) assertEquals("καλημέρα", index.lookup(typed).first())
        for (typed in listOf("thelw", "8elw", "thelo")) assertEquals("θέλω", index.lookup(typed).first())
        for (typed in listOf("kserw", "3erw", "xerw")) assertEquals("ξέρω", index.lookup(typed).first())
        for (typed in listOf("oxi", "ohi", "ochi")) assertEquals("όχι", index.lookup(typed).first())
        for (typed in listOf("auto", "afto")) assertEquals("αυτό", index.lookup(typed).first())
        for (typed in listOf("mpravo", "bravo")) assertEquals("μπράβο", index.lookup(typed).first())
        for (typed in listOf("efxaristw", "eyxaristw", "euxaristo")) assertEquals("ευχαριστώ", index.lookup(typed).first())
        assertEquals(listOf("ώρα", "όρα"), index.lookup("wra"))
        assertTrue(index.lookup("zzz").isEmpty())
    }

    @Test
    fun `a word with a letter outside plain Greek does not break the table`() {
        val index = GreekPhoneticIndex(listOf("θάʹνε" to 5, "θέλω" to 3))
        assertFalse(index.isEmpty)
        assertEquals("θέλω", index.lookup("thelw").first())
    }

    @Test
    fun `Wylie matches pyewts`() {
        val cases = mapOf(
            "bod skad" to "བོད་སྐད", "bkra shis bde legs" to "བཀྲ་ཤིས་བདེ་ལེགས", "rgyal po" to "རྒྱལ་པོ",
            "'phags pa" to "འཕགས་པ", "bsgrubs" to "བསྒྲུབས", "g.yag" to "གཡག", "gya" to "གྱ", "oM" to "ཨོཾ",
            "hU~M" to "ཧཱུྃ", "pad+ma" to "པདྨ", "rdo rje/" to "རྡོ་རྗེ།", "e'o" to "ཨེའོ", "ka'i" to "ཀའི",
            "Ta" to "ཊ", "kaH" to "ཀཿ", "dbyangs" to "དབྱངས", "brgyad" to "བརྒྱད", "01" to "༠༡", "bod/ /skad" to "བོད། །སྐད",
        )
        for ((wylie, tibetan) in cases) assertEquals(wylie, tibetan, WylieTibetan.transliterate(wylie))
    }
}
