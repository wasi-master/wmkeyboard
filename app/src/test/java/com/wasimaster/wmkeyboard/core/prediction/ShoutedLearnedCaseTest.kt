package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Test

/** #1433: a remembered all-caps spelling of an everyday word is not every glide's spelling. */
class ShoutedLearnedCaseTest {

    private fun engine(lexicon: UserLexicon) = SuggestionEngine(
        PackedTrie.of(listOf("for" to 100, "the" to 100)),
        BengaliPhoneticIndex(emptyList()),
        lexicon,
    )

    @Test fun anUnpinnedShoutOfAListedWordIsWrittenInSmallLetters() {
        val lexicon = UserLexicon(null)
        lexicon.addWord("FOR", caseEvidence = true)
        lexicon.pinCase("for", false)
        assertEquals("for", engine(lexicon).displayOf("for"))
    }

    @Test fun aShoutTheUserKeptStays() {
        val lexicon = UserLexicon(null)
        lexicon.addWord("FOR", caseEvidence = true, pinCase = true)
        assertEquals("FOR", engine(lexicon).displayOf("for"))
    }

    @Test fun aNameOfTheUsersOwnKeepsItsCapitals() {
        val lexicon = UserLexicon(null)
        lexicon.addWord("NASA", caseEvidence = true)
        lexicon.pinCase("nasa", false)
        assertEquals("NASA", engine(lexicon).displayOf("nasa"))
    }
}
