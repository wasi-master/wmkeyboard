package com.wasimaster.wmkeyboard.spell

import com.wasimaster.wmkeyboard.core.grammar.GrammarFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HarperSpellCheckerServiceTest {

    @Test fun replaceUsesTheSuggestionVerbatim() {
        assertEquals(
            "they're",
            spellReplacementFor("their", GrammarFix(kind = "replace", text = "they're")),
        )
    }

    @Test fun insertAfterKeepsTheFlaggedSpan() {
        // The framework swaps the whole span, so an insertion has to carry
        // the original text along or the span's words would vanish.
        assertEquals(
            "cat,",
            spellReplacementFor("cat", GrammarFix(kind = "insertAfter", text = ",")),
        )
    }

    @Test fun removalsAreDroppedRatherThanShownEmpty() {
        assertNull(spellReplacementFor("very", GrammarFix(kind = "remove")))
    }

    @Test fun unknownKindsAndMissingTextAreDropped() {
        assertNull(spellReplacementFor("x", GrammarFix(kind = "somethingNew", text = "y")))
        assertNull(spellReplacementFor("x", GrammarFix(kind = "replace", text = null)))
        assertNull(spellReplacementFor("x", GrammarFix(kind = "insertAfter", text = null)))
    }

    @Test fun aListHasNoOpinionOnAScriptItDoesNotHold() {
        val english = scriptsOf(listOf("the", "of", "and"))
        assertFalse(isInScripts("привет", english))
        assertTrue(isInScripts("helo", english))
        assertTrue(isInScripts("café", english))
    }

    @Test fun aMixedWordIsStillChecked() {
        assertTrue(isInScripts("iPhoneы", scriptsOf(listOf("я", "не", "что"))))
    }

    @Test fun aListWrittenInSeveralScriptsCoversEachOfThem() {
        val serbian = scriptsOf(listOf("да", "ne"))
        assertTrue(isInScripts("здраво", serbian))
        assertTrue(isInScripts("zdravo", serbian))
    }

    @Test fun anUnsampledListRulesNothingOut() {
        assertTrue(isInScripts("привет", scriptsOf(emptyList())))
    }
}
