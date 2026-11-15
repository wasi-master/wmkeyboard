package com.wasimaster.wmkeyboard.core.transliteration

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Varnam walk over the shipped tables: a spelling read through every
 * letter its patterns may stand for, kept only while a listed word starts
 * that way, answered by the list's commonest whole words.
 */
class VarnamSchemeTest {

    private val dir = File("../../app/src/main/assets/varnam")

    private fun scheme(lang: String) = File(dir, "$lang.tsv").inputStream().use(VarnamScheme::read)

    /** A word list in [String.compareTo] order, ranked by its counts. */
    private class Listed(counts: Map<String, Int>) : SortedWords {
        private val words = counts.keys.sorted()
        private val ranks = words.map { -counts.getValue(it).toLong() }
        override val size: Int get() = words.size
        override fun length(i: Int) = words[i].length
        override fun charAt(i: Int, at: Int) = words[i][at]
        override fun word(i: Int) = words[i]
        override fun rank(i: Int) = ranks[i]
    }

    // Counts from the downloadable Malayalam list.
    private val malayalam = Listed(
        mapOf(
            "അമ്മ" to 857, "അമ്മാ" to 63, "വെള്ളം" to 529, "വെള്ളി" to 40,
            "പാത്രം" to 61, "പത്രം" to 60, "എന്താണ്" to 2477, "എന്താണു" to 77,
            "പറഞ്ഞു" to 2459, "മലയാളം" to 86, "കുട്ടികൾ" to 107, "കുറ്റികൾ" to 2,
        ),
    )

    @Test fun everyShippedTableReads() {
        for (lang in VarnamSchemes.languages) {
            val s = scheme(lang)
            assertTrue(lang, s.tokenize("ka") != null)
        }
    }

    @Test fun theListDecidesBetweenTheLettersAPatternMayBe() {
        val ml = scheme("ml")
        // ll is ല്ല or ള്ള, th is ത or ത്ത or ഥ: only the listed words survive.
        assertEquals("വെള്ളം", ml.words("vellam", malayalam, 3).first())
        assertEquals(listOf("പാത്രം", "പത്രം"), ml.words("pathram", malayalam, 3))
        assertEquals(listOf("കുട്ടികൾ", "കുറ്റികൾ"), ml.words("kuttikal", malayalam, 3))
        assertEquals(listOf("അമ്മ", "അമ്മാ"), ml.words("amma", malayalam, 3))
        assertEquals("എന്താണ്", ml.words("enthaanu", malayalam, 3).first())
        assertEquals("പറഞ്ഞു", ml.words("paranju", malayalam, 3).first())
    }

    @Test fun nothingWhenTheListHasNoSuchWord() {
        val ml = scheme("ml")
        assertEquals(emptyList<String>(), ml.words("keralam", malayalam, 3))
        // A letter no pattern starts with stops the walk.
        assertEquals(emptyList<String>(), ml.words("amma?", malayalam, 3))
        assertEquals(emptyList<String>(), ml.words("", malayalam, 3))
    }

    @Test fun prefixesAreFoundInTheSortedList() {
        assertTrue(VarnamScheme.hasPrefix(malayalam, "വെള്ള"))
        assertTrue(!VarnamScheme.hasPrefix(malayalam, "വെള്ളു"))
    }
}
