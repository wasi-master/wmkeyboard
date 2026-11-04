package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A letter typed without its accent (#200). */
class AccentFoldingTest {

    private fun walk(entries: List<Pair<String, Int>>, typed: String) =
        FuzzyBeamSearch().search(
            listOf(
                FuzzyBeamSearch.WalkSource(
                    PackedTrie.of(entries).walkers().single(), 0.0, FuzzyBeamSearch.Tier.DICTIONARY,
                ),
            ),
            typed, KeyProximity.QWERTY, 5, BeamWorkspace(),
        )

    private fun engine(vararg entries: Pair<String, Int>) =
        SuggestionEngine(
            PackedTrie.of(entries.toList()), BengaliPhoneticIndex(emptyList()), UserLexicon(null),
        )

    @Test fun bareTakesTheAccentOffLatinAndGreekLetters() {
        assertEquals('z', Accents.bare('ż'))
        assertEquals('z', Accents.bare('ź'))
        assertEquals('l', Accents.bare('ł'))
        assertEquals('e', Accents.bare('ę'))
        assertEquals('e', Accents.bare('é'))
        assertEquals('o', Accents.bare('ó'))
        assertEquals('a', Accents.bare('ẩ'))
        assertEquals('α', Accents.bare('ά'))
        assertEquals('z', Accents.bare('z'))
    }

    @Test fun lettersOfOtherScriptsStayTheirOwn() {
        // Cyrillic й has its own key; it is not и typed carelessly.
        assertEquals('й', Accents.bare('й'))
        // A Bengali nukta letter is not its base either.
        assertEquals('\u09DF', Accents.bare('\u09DF'))
        // A ligature is a letter, not a letter with a mark.
        assertEquals('æ', Accents.bare('æ'))
    }

    @Test fun everyMissingAccentIsFoundWithoutSpendingAnEdit() {
        // Four letters get one edit, and "zolw" leaves off three accents.
        val got = walk(listOf("żółw" to 896), "zolw").single()
        assertEquals("żółw", got.word)
        assertEquals(0, got.edits)
        assertEquals(3, got.accents)
        assertEquals(0.0, got.editCost, 1e-9)
    }

    @Test fun turkishDotlessIIsAnAccentOfI() {
        assertEquals('i', Accents.bare('ı'))
        // Telegram group: "haklisin" for "haklısın", ı typed as i twice.
        val got = walk(listOf("haklısın" to 2_000), "haklisin").single()
        assertEquals("haklısın", got.word)
        assertEquals(0, got.edits)
        assertEquals(2, got.accents)
        assertEquals("güzel", walk(listOf("güzel" to 2_000), "guzel").single().word)
    }

    @Test fun aLeftOutHalfSpaceIsFoundWithoutSpendingAnEdit() {
        // #406: Persian writes می‌کنم, typed as the letters alone.
        val got = walk(listOf("می\u200Cکنم" to 58_927), "میکنم").single()
        assertEquals("می\u200Cکنم", got.word)
        assertEquals(0, got.edits)
        assertEquals(1, got.accents)
    }

    @Test fun aWordTypedWithoutItsHalfSpaceIsCorrected() {
        val e = engine("می\u200Cکنم" to 58_927, "سگ\u200Cها" to 319, "مکنم" to 40)
        assertEquals("می\u200Cکنم", e.suggest("میکنم", previousWord = null).first())
        assertEquals("می\u200Cکنم", e.shouldAutocorrect("میکنم"))
        assertEquals("سگ\u200Cها", e.shouldAutocorrect("سگها"))
        // Typed with it, the word is already right.
        assertNull(e.shouldAutocorrect("می\u200Cکنم"))
    }

    @Test fun theWordSpelledAsTypedLeadsItsTwinAtEqualFrequency() {
        val got = walk(listOf("cafe" to 100, "café" to 100), "cafe")
        assertEquals(listOf("cafe", "café"), got.map { it.word })
    }

    @Test fun anAccentlessStandInIsSuggestedAndCorrected() {
        // Counts from the Polish word list, which holds both spellings.
        val e = engine("już" to 690_940, "juz" to 12_683, "jest" to 2_730_378)
        assertEquals("już", e.suggest("juz", previousWord = null).first())
        assertEquals("już", e.shouldAutocorrect("juz"))
        assertEquals("Już", e.shouldAutocorrect("Juz"))
    }

    @Test fun anEditedReadingIsNoRivalToTheAccentedOne() {
        // "nie" is commoner than "się" and one slip from "sie"; "ona" is far
        // commoner than "żona" and one deletion from "zona". Neither explains
        // the keys that were pressed.
        val e = engine(
            "nie" to 8_583_207, "się" to 5_144_785, "sie" to 87_050,
            "ona" to 900_000, "żona" to 43_464, "zona" to 726,
            "mój" to 370_480, "moja" to 250_000,
        )
        assertEquals("się", e.shouldAutocorrect("sie"))
        assertEquals("żona", e.shouldAutocorrect("zona"))
        // Not in the list at all, with a completion close behind.
        assertEquals("mój", e.shouldAutocorrect("moj"))
    }

    @Test fun aStandInOutsideTheWalksTopRanksIsStillShadowed() {
        // The twin's inflections fill every rank above the stand-in.
        val inflections = (1..40).map { "mówię${"abcdefghijklmnopqrstuvwxyz"[it % 26]}$it" to 50_000 }
        val e = SuggestionEngine(
            PackedTrie.of(listOf("mówię" to 93_488, "mowie" to 1_094) + inflections),
            BengaliPhoneticIndex(emptyList()), UserLexicon(null),
        )
        assertEquals("mówię", e.shouldAutocorrect("mowie"))
    }

    @Test fun aWordNoListHoldsGetsAllItsAccentsBack() {
        val e = engine("żółw" to 896)
        assertEquals("żółw", e.shouldAutocorrect("zolw"))
    }

    @Test fun twoCommonSpellingsAreBothWords() {
        // Spanish "más" is 15 times as common as "mas", under the ratio: both
        // are words people mean, so the strip offers the accent and the
        // typed word stays.
        val e = engine("más" to 1_503_527, "mas" to 103_445)
        assertTrue("más" in e.suggest("mas", previousWord = null))
        assertNull(e.shouldAutocorrect("mas"))
    }

    @Test fun aDownloadedListIsShadowedToo() {
        // Every language but English and Bengali reaches the engine through
        // customDictionary, the union of its downloaded and imported lists.
        // Exempting that source exempted the whole downloaded Polish list, so
        // `juz` stayed uncorrected on the device while every test passed.
        val e = SuggestionEngine(PackedTrie.EMPTY, BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        e.customDictionary = PackedTrie.of(listOf("już" to 690_940, "juz" to 12_683))
        assertEquals("już", e.shouldAutocorrect("juz"))
    }

    private fun downloaded(vararg entries: Pair<String, Int>) =
        SuggestionEngine(PackedTrie.EMPTY, BengaliPhoneticIndex(emptyList()), UserLexicon(null)).apply {
            customDictionary = PackedTrie.of(entries.map { (word, aosp) -> word to AospScores.listCount(aosp) })
        }

    @Test fun theKeysSpellingAWordButForItsAccentsLeadCommonerEdits() {
        // #215, the pt_BR AOSP list's own 0 to 255 ratings. `chave` is a far
        // slip from `chale` and `caminho` a dropped letter from `caminhao`,
        // both commoner than the word whose keys were pressed.
        val e = downloaded(
            "chalé" to 81, "chave" to 130, "chaves" to 118,
            "caminhão" to 106, "caminho" to 142,
        )
        val chale = e.suggest("chale", previousWord = null)
        assertEquals("chalé", chale.first())
        // The slip is still offered, behind it.
        assertTrue("chave" in chale)
        assertEquals("caminhão", e.suggest("caminhao", previousWord = null).first())
    }

    @Test fun aRareAccentedWordPastTheWalksTopRanksIsStillOffered() {
        // `chule` is a letter from dozens of commoner words before `chulé`.
        val letters = "abcdfghijkmnopqrstuvwxyz"
        val near = letters.map { "chul$it" to 200 } + letters.map { "chu${it}e" to 200 }
        val e = downloaded("chulé" to 53, *near.toTypedArray())
        assertEquals("chulé", e.suggest("chule", previousWord = null).first())
    }

    @Test fun aWordInAndroidsPersonalDictionaryIsNeverShadowed() {
        val e = engine("już" to 690_940, "juz" to 12_683)
        e.systemDictionary = PackedTrie.of(listOf("juz" to 1))
        assertNull(e.shouldAutocorrect("juz"))
    }
}
