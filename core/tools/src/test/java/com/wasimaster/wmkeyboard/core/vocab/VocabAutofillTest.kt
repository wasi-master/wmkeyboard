package com.wasimaster.wmkeyboard.core.vocab

import com.wasimaster.wmkeyboard.core.tools.DictDefinition
import com.wasimaster.wmkeyboard.core.tools.DictEntry
import com.wasimaster.wmkeyboard.core.tools.DictMeaning
import com.wasimaster.wmkeyboard.core.tools.DictionaryClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabAutofillTest {

    private val quixotic = DictEntry(
        word = "quixotic",
        phonetic = "/kwɪkˈsɒtɪk/",
        audioUrl = "https://x/quixotic-us.mp3",
        meanings = listOf(
            DictMeaning(
                partOfSpeech = "Adjective",
                definitions = listOf(
                    DictDefinition("Exceedingly idealistic.", "His quixotic quest", listOf("idealistic")),
                    DictDefinition("Impulsive.", null, emptyList()),
                    DictDefinition("Three.", null, emptyList()),
                    DictDefinition("Four is one too many.", null, emptyList()),
                ),
                synonyms = listOf("romantic"),
                antonyms = listOf("practical"),
            ),
        ),
    )

    @Test
    fun `dictionary entries fold into a record`() {
        val word = VocabAutofill.fromDictionary(listOf(quixotic), "quixotic")!!
        assertEquals(listOf("adjective"), word.pos)
        assertEquals("/kwɪkˈsɒtɪk/", word.ipaFor(VocabAccent.US))
        assertEquals("https://x/quixotic-us.mp3", word.audioFor(VocabAccent.UK))
        assertEquals(3, word.senses.size)
        assertEquals("His quixotic quest", word.senses[0].example)
        assertEquals(listOf("romantic", "idealistic"), word.synonyms)
        assertEquals(listOf("practical"), word.antonyms)
        assertNull(VocabAutofill.fromDictionary(emptyList(), "x"))
    }

    @Test
    fun `resolve prefers the index then the network`() = runBlocking {
        val index = VocabIndex.build(listOf(VocabPack(VocabPackMeta(id = "p"), listOf(VocabWord("abhor")))))
        val fromIndex = VocabAutofill.resolve(index, " Abhor ", allowOnline = false) { error("no network") }
        assertTrue(fromIndex is VocabAutofill.Result.Found && !fromIndex.fromOnline)

        assertEquals(VocabAutofill.Result.NeedsOnline, VocabAutofill.resolve(index, "quixotic", allowOnline = false) { emptyList() })

        val online = VocabAutofill.resolve(index, "quixotic", allowOnline = true) { listOf(quixotic) }
        assertTrue(online is VocabAutofill.Result.Found && online.fromOnline)

        val missing = VocabAutofill.resolve(index, "zzz", allowOnline = true) { throw DictionaryClient.NotFoundException("zzz") }
        assertEquals(VocabAutofill.Result.NotFound, missing)

        val failed = VocabAutofill.resolve(index, "zzz", allowOnline = true) { throw IllegalStateException("down") }
        assertEquals(VocabAutofill.Result.Failed, failed)
    }

    @Test
    fun `word lists parse one per line`() {
        val text = "Abhor\nalacrity, \n\n  laconic # comment\nabhor\n123\n"
        assertEquals(listOf("abhor", "alacrity", "laconic"), VocabAutofill.parseWordList(text))
    }
}
