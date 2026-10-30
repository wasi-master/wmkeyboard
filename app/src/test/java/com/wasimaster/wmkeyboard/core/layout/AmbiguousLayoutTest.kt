package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.prediction.KeyProximity
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.script.ScriptId
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two shipped boards that put more than one letter on a key: T9 and
 * Compact QWERTY (discussion #103).
 *
 * What they need that an ordinary layout does not is [Key.letters], and what
 * everything downstream needs from *them* is that the field and the key agree:
 * the anchor a key commits has to be the first letter of its set, or the buffer
 * and the decode would be describing different keystrokes.
 */
class AmbiguousLayoutTest {

    private val ambiguous = listOf(BuiltInLayouts.T9, BuiltInLayouts.COMPACT)

    private fun letterRows(spec: LayoutSpec) = spec.layer(LayoutLayer.LETTERS)!!.rows

    private fun textKeys(spec: LayoutSpec) =
        letterRows(spec).flatten().filter { it.action == KeyAction.Text }

    @Test
    fun bothBoardsAreAmbiguousAndNothingElseIs() {
        for (spec in ambiguous) {
            assertTrue("${spec.name} should be ambiguous", textKeys(spec).any { it.isAmbiguous() })
        }
        for (spec in BuiltInLayouts.all - ambiguous.toSet()) {
            val guessy = textKeys(spec).filter { it.isAmbiguous() }
            assertEquals("${spec.name} has ambiguous keys", emptyList<Key>(), guessy)
        }
    }

    @Test
    fun theCommittedCharacterIsTheFirstLetterOfTheKeysSet() {
        // The load-bearing invariant. The buffer holds what the key commits and
        // the key set describes position by position what it might have been;
        // an anchor that was not in its own set would make every reading of
        // that keystroke an edit away from what was typed.
        for (spec in ambiguous) {
            for (key in textKeys(spec)) {
                val set = key.letterSet()
                if (set.isEmpty()) continue
                assertEquals(
                    "${spec.name}: ${key.label} commits the wrong anchor",
                    set.first().toString(),
                    key.output ?: key.label,
                )
            }
        }
    }

    @Test
    fun betweenThemTheKeysSpellTheAlphabet() {
        for (spec in ambiguous) {
            val letters = textKeys(spec).flatMap { it.letterSet().toList() }
            assertEquals(
                "${spec.name} covers a-z exactly once",
                ('a'..'z').toList(),
                letters.sorted(),
            )
        }
    }

    @Test
    fun theKeypadFollowsTheStandardKeyGrouping() {
        // ITU E.161, which every phone since the mid-nineties has drawn, and
        // which is what a T9 user's fingers already know.
        val groups = textKeys(BuiltInLayouts.T9).map { it.letterSet() }.filter { it.length > 1 }
        assertEquals(
            listOf("abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz"),
            groups,
        )
    }

    @Test
    fun theKeypadDrawsItsDigitsAsHints() {
        // The first long-press alternate is a key's corner hint, so putting the
        // digit there is what makes the grid read as a keypad — and holding a
        // key types it, the way it always did.
        val digits = textKeys(BuiltInLayouts.T9)
            .filter { it.isAmbiguous() }
            .map { it.longPress.first() }
        assertEquals(listOf("2", "3", "4", "5", "6", "7", "8", "9"), digits)
    }

    @Test
    fun everyLetterIsReachableOnItsOwnFromALongPress() {
        // The escape hatch, and the thing that makes an unknown word typable at
        // all on a board that otherwise guesses: every letter of every key is
        // in that key's popup, so it can be spelled outright.
        for (spec in ambiguous) {
            for (key in textKeys(spec).filter { it.isAmbiguous() }) {
                for (letter in key.letterSet()) {
                    assertTrue(
                        "${spec.name}: ${key.label} cannot spell '$letter'",
                        letter.toString() in key.longPress,
                    )
                }
            }
        }
    }

    @Test
    fun everyRowIsTheSameWidth() {
        // A row narrower than the grid is centred with side gaps and a wider
        // one is not, so a mismatched row on a five-column board is far more
        // visible than the same mistake on a ten-column one.
        for (spec in ambiguous) {
            val widths = letterRows(spec).map { row -> row.sumOf { it.width.toDouble() } }
            assertEquals("${spec.name} row widths", 1, widths.distinct().size)
        }
    }

    @Test
    fun proximityIsBetweenKeysNotBetweenLetters() {
        // Derived, not authored: the anchors are single characters, so the
        // existing derivation reads the grid correctly and the neighbours it
        // finds are the physical ones. On the keypad, `a`'s key really does sit
        // beside `d`'s and above `g`'s.
        val pad = KeyProximity.forLayout(BuiltInLayouts.T9)
        assertTrue(pad.areAdjacent('a', 'd'))
        assertTrue(pad.areAdjacent('a', 'g'))
        assertFalse(pad.areAdjacent('a', 'w'))
        // A letter that shares a key with the anchor is not "adjacent" to it —
        // it is the same key, which the decode handles as a free match rather
        // than as a slip.
        assertFalse(pad.areAdjacent('a', 'b'))
    }

    @Test
    fun neitherBoardAsksForTheTabletExpansion() {
        // Widening a keypad with Tab, caps lock and a mirrored shift would
        // make it neither a keypad nor a keyboard.
        for (spec in ambiguous) assertFalse(spec.name, spec.tabletExpand)
    }

    /**
     * [withLetters] is the only place a letter set is written, by the layout
     * editor's field and by the repair pass alike, so the anchor rule the tests
     * above check on the shipped boards is the same rule a user's own board
     * gets. Before it existed the rule was a doc comment and the only author who
     * could break it was the one least able to see it: someone hand-writing JSON
     * with no symptom to search for (discussion #103).
     */
    @Test
    fun writingALetterSetAlwaysAnchorsTheKeyToItsFirstLetter() {
        val key = Key("QW", output = "z").withLetters("qw")
        assertEquals("qw", key.letters)
        assertEquals("q", key.output)
        assertTrue(key.isAmbiguous())
    }

    @Test
    fun writingALetterSetCleansCaseRepeatsAndNonLetters() {
        val key = Key("QW").withLetters("Q w 2 q")
        assertEquals("qw", key.letters)
        assertEquals("q", key.output)
    }

    @Test
    fun aLetterSetWithNoLettersInItClearsTheFieldAndLeavesTheOutput() {
        val key = Key("1", output = "1").withLetters("123")
        assertNull(key.letters)
        assertEquals("1", key.output)
        assertFalse(key.isAmbiguous())
    }

    /**
     * One letter is a legal set and the docs say so, but it still has to anchor:
     * a set of `q` on a key that types `z` is the same disagreement as a set of
     * `qw` on one, and rarer, so less likely to be caught by hand.
     */
    @Test
    fun aSetOfOneLetterStillDecidesWhatTheKeyTypes() {
        val key = Key("L", output = "z").withLetters("l")
        assertEquals("l", key.letters)
        assertEquals("l", key.output)
        assertFalse("one letter is not ambiguous", key.isAmbiguous())
    }

    /** Turkish dotted capital I lowercases to two characters; a set is one per key. */
    @Test
    fun aLetterThatLowercasesToTwoCharactersStaysOneMemberOfTheSet() {
        val key = Key("İJ").withLetters("\u0130j")
        assertEquals(2, key.letters?.length)
        assertEquals(key.letters?.take(1), key.output)
    }

    @Test
    fun theShippedBoardsAreAlreadyWhatWritingTheirSetsWouldProduce() {
        for (spec in ambiguous) {
            for (key in textKeys(spec)) {
                val set = key.letters ?: continue
                assertEquals(
                    "${spec.name}: ${key.label} is not what withLetters would write",
                    key,
                    key.withLetters(set),
                )
            }
        }
    }

    @Test
    fun bothAreEnglishLanguageLayoutsAndNeitherIsSecondary() {
        for (spec in ambiguous) {
            assertEquals("en", spec.langId)
            assertFalse("${spec.name} is secondary", spec.secondary)
            assertFalse("${spec.name} starts enabled", spec.id in BuiltInLayouts.defaultEnabledIds)
        }
    }

    /**
     * The per-language keypads (issue #332), shipped as assets: the English
     * grid with each language's own letter groups. Read off disk the way
     * `AssetLayoutsTest` reads every asset, since the loader wants a device.
     */
    private val languageKeypads: List<LayoutSpec> =
        File("src/main/assets/layouts")
            .listFiles { f -> f.name.endsWith("_t9.${LayoutFile.FILE_EXTENSION}") && !f.name.startsWith("sam_") }
            .orEmpty()
            .sortedBy { it.name }
            .map { LayoutFile.decode(it.readText())!!.layout }
            // The pinyin pad types digits for a composer; it has no letter sets.
            .filterNot { it.id == AssetLayouts.ZH_PINYIN_T9_ID }

    @Test
    fun theLanguageKeypadsShip() {
        assertEquals(444, languageKeypads.size)
    }

    @Test
    fun everyLanguageKeypadKeepsTheEnglishOnesShape() {
        // Same grid as builtin_t9, so a T9 typist switching language finds
        // every key where it was: eight letter keys, the digits 2-9 on them in
        // keypad order, one width throughout, and no tablet widening.
        val englishShape = letterRows(BuiltInLayouts.T9).map { row -> row.map { it.width } }
        for (spec in languageKeypads) {
            // Keys 2-9 by their digit hint. Every one carries letters except
            // Armenian's 9, which ETSI ES 202 130 keeps for the script's
            // punctuation, as Armenian phones did.
            val keys = textKeys(spec).filter { it.longPress.firstOrNull() in KEYPAD_DIGITS }
            assertEquals("${spec.id} digit hints", KEYPAD_DIGITS, keys.map { it.longPress.first() })
            assertTrue(
                "${spec.id} has a digit key with no letters",
                keys.count { it.isAmbiguous() } >= if (spec.langId in ARMENIAN) 7 else 8,
            )
            assertEquals(
                "${spec.id} grid shape",
                englishShape,
                letterRows(spec).map { row -> row.map { it.width } },
            )
            assertFalse("${spec.id} asks for tablet expansion", spec.tabletExpand)
        }
    }

    @Test
    fun everyLanguageKeypadKeepsTheAnchorAndSpellingRules() {
        for (spec in languageKeypads) {
            for (key in textKeys(spec).filter { it.isAmbiguous() }) {
                val set = key.letterSet()
                assertEquals("${spec.id}: ${key.label} anchor", set.first().toString(), key.output)
                assertEquals("${spec.id}: ${key.label} set", key, key.withLetters(set))
                for (letter in set) {
                    assertTrue(
                        "${spec.id}: ${key.label} cannot spell '$letter'",
                        letter.toString() in key.longPress,
                    )
                }
            }
            val letters = textKeys(spec).flatMap { it.letterSet().toList() }
            assertEquals("${spec.id} puts a letter on two keys", letters.distinct(), letters)
        }
    }

    @Test
    fun aLatinKeypadAddsItsLettersToTheKeypadNotAroundIt() {
        // The ITU groups stay the first letters of every key, so the anchor a
        // tap commits and the keys a word's shape runs through are the English
        // keypad's; what a language adds (ä, ł, ñ) rides on its base letter's key.
        val itu = listOf("abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz")
        for (spec in languageKeypads.filter { it.script().id == ScriptId.LATIN }) {
            val sets = textKeys(spec).filter { it.isAmbiguous() }.map { it.letterSet() }
            for ((set, group) in sets.zip(itu)) {
                // ETSI orders a key by alphabet, so Vietnamese's 2 reads a ă â b c:
                // the key keeps its ITU letters and anchors on the first of them.
                assertTrue("${spec.id}: $set is missing some of $group", group.all { it in set })
                assertEquals("${spec.id}: $set anchor", group.first(), set.first())
            }
        }
    }

    @Test
    fun everyLanguageKeypadIsOfferedByItsLanguage() {
        // An asset no language lists is shipped and unreachable, which is what
        // happened to builtin_t9 for two days (a699c1ef).
        for (spec in languageKeypads) {
            assertTrue(
                "${spec.id} is not listed under ${spec.langId}",
                spec.id in LanguageRegistry.byId(spec.langId).layoutIds,
            )
        }
    }

    /**
     * The keypads of scripts written with combining marks (#332) put the
     * script's signs on 1 -- candrabindu, anusvara, visarga, virama -- the way
     * the phones of the Indian market did (ETSI ES 202 130 Annex A), so 1 is a
     * letter key there and the sentence's punctuation takes the fourth column.
     */
    @Test
    fun anIndicKeypadCarriesItsSignsOnOne() {
        val hindi = languageKeypads.first { it.langId == "hi" }
        val rows = letterRows(hindi)
        val one = rows[0][0]
        assertEquals("1", one.longPress.first())
        for (sign in listOf('ँ', 'ं', 'ः', '्')) {
            assertTrue("hi: 1 does not carry $sign", sign in one.letterSet())
        }
        // The vowels and their signs share a key: अ with ा on 2, ए with े on 3.
        assertTrue('अ' in rows[0][1].letterSet() && 'ा' in rows[0][1].letterSet())
        assertTrue('ए' in rows[0][2].letterSet() && 'े' in rows[0][2].letterSet())
        val stop = rows[1][3]
        assertEquals("।", stop.output)
        assertEquals(KeyRole.Period, stop.role)
    }

    /** A combining mark is part of a written word, so a letter set keeps it. */
    @Test
    fun aLetterSetKeepsCombiningMarks() {
        val key = Key("ं").withLetters("ँं्1 ")
        assertEquals("ँं्", key.letters)
        assertEquals("ँ", key.output)
        assertTrue(key.isAmbiguous())
    }

    private companion object {
        val KEYPAD_DIGITS = listOf("2", "3", "4", "5", "6", "7", "8", "9")
        val ARMENIAN = setOf("hy", "hyw")
    }
}
