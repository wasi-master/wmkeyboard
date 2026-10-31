package com.wasimaster.wmkeyboard.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SwiftKey's learned words, as the file its OneDrive sync keeps them in. */
class SwiftKeyVocabularyTest {

    private fun found(name: String): CustomDictionaries.ImportCandidate {
        val file = CustomDictionaries.ImportFile(name, "cooperate\nkolkata\nmanglish\n".toByteArray())
        val inspection = CustomDictionaries.inspect(listOf(file)) as CustomDictionaries.Inspection.Found
        return inspection.dictionaries.single()
    }

    @Test
    fun theFileIsKnownByItsName() {
        assertTrue(CustomDictionaries.isSwiftKeyVocabulary("vocabulary.txt"))
        assertTrue(CustomDictionaries.isSwiftKeyVocabulary("Vocabulary (1).txt"))
        assertFalse(CustomDictionaries.isSwiftKeyVocabulary("my vocabulary.txt"))
        assertFalse(CustomDictionaries.isSwiftKeyVocabulary("vocabulary.json"))
    }

    @Test
    fun itsListSaysWhatItHolds() {
        val list = found("vocabulary.txt")
        assertEquals("SwiftKey learned words.txt", list.name)
        assertEquals("SwiftKey learned words", list.description)
        assertEquals(3, list.words.size)
    }

    @Test
    fun anyOtherListKeepsItsName() {
        assertEquals("slang.txt", found("slang.txt").name)
    }
}
