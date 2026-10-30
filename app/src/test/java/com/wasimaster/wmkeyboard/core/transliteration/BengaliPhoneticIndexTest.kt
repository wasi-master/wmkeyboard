package com.wasimaster.wmkeyboard.core.transliteration

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sibling ranking, which decides what a loose romanized spelling comes
 * back as. Frequencies here are the real ones from the bundled Bengali list,
 * since the whole point of these cases is that the commoner word is the wrong
 * answer and something else has to outweigh it.
 */
class BengaliPhoneticIndexTest {

    private val index = BengaliPhoneticIndex(
        listOf(
            // Aspiration pairs — the plain member is the commoner word.
            "তাকে" to 4850, "থাকে" to 1750,
            "তাকা" to 568, "থাকা" to 1582, "টাকা" to 1900,
            "গুম" to 422, "ঘুম" to 80,
            "কবর" to 900, "খবর" to 640,
            // Aspiration where the h was left out: ঠিক must still win on being
            // the far commoner word.
            "টিক" to 229, "ঠিক" to 565,
            // A bare word against itself plus the emphatic ও.
            "কথা" to 286, "কথাও" to 993,
            "আগে" to 530, "আগেও" to 1216,
            // ভাল/ভালো are one word spelled two ways, not two words.
            "ভাল" to 1900, "ভালো" to 6500,
            // The confusable folds the index was built for.
            "আছি" to 6900, "আসি" to 2300,
            "চলে" to 2100, "ছলে" to 40,
            // খণ্ড-ত: nobody reaches for its key mid-word, so it has to be
            // findable from the plain t — without dragging ordinary ত-final
            // words along with it.
            "হঠাৎ" to 1252, "হঠাত" to 40, "ভারত" to 3900,
        )
    )

    private fun top(input: String) = index.lookup(input).firstOrNull()

    @Test fun typedAspirationIsNotOverriddenByACommonerPlainWord() {
        // থাকে is 2.8x rarer than তাকে; the h is what asks for it.
        assertEquals("থাকে", top("thake"))
        assertEquals("তাকে", top("take"))
        assertEquals("ঘুম", top("ghum"))
        assertEquals("গুম", top("gum"))
        assertEquals("খবর", top("khobor"))
        assertEquals("কবর", top("kobor"))
    }

    @Test fun omittedAspirationStillYieldsToFrequency() {
        // Leaving the h out is ordinary casual spelling, so it only handicaps
        // the aspirated word rather than ruling it out.
        assertEquals("ঠিক", top("tik"))
        assertEquals("ঠিক", top("thik"))
        // টাকা is commoner than both তাকা and থাকা, and nothing was typed to
        // argue against it.
        assertEquals("টাকা", top("taka"))
    }

    @Test fun aFinalOIsPartOfTheWord() {
        // কথাও is "also"; it is not what "kotha" asked for.
        assertEquals("কথা", top("kotha"))
        assertEquals("কথাও", top("kothao"))
        assertEquals("আগে", top("age"))
        assertEquals("আগেও", top("ageo"))
    }

    @Test fun spellingVariantsStillFollowFrequency() {
        // ভাল/ভালো differ only by the o-kar, so the commoner spelling wins
        // whichever way it was typed.
        assertEquals("ভালো", top("valo"))
        assertEquals("ভাল", top("val"))
    }

    @Test fun chAndPhAreNotAspirationClaims() {
        // "ch" is how চ gets written by anyone used to English, so it must not
        // be read as asking for the aspirated ছ.
        assertEquals("চলে", top("chole"))
        assertEquals("আছি", top("achi"))
        assertEquals("আছি", top("asi"))
    }

    @Test fun khandaTaIsReachableFromAPlainT() {
        assertEquals("হঠাৎ", top("hotat"))
        assertEquals("হঠাৎ", top("hoThat"))
        // ...and a word that really does end in ত keeps its ত.
        assertEquals("ভারত", top("bharot"))
    }

    @Test fun qReadsAsK() {
        assertEquals(BengaliPhoneticIndex.foldRoman("kobor"), BengaliPhoneticIndex.foldRoman("qobor"))
        assertEquals("কবর", top("qobor"))
        // "qq" is ঁ, which folds away rather than doubling a ক.
        assertEquals(BengaliPhoneticIndex.foldRoman("cad"), BengaliPhoneticIndex.foldRoman("caqqd"))
    }

    @Test fun aFlatListTakesTheBundledRanking() {
        // The downloaded Bangla list says 1 for every word, in trie order, so
        // a rare sibling came ahead of the far commoner সরাসরি on the shared key.
        val downloaded = listOf("\u099B\u09A1\u09BC\u09BE\u099B\u09A1\u09BC\u09BF" to 1, "সরাসরি" to 1, "স্বরলিপি" to 1)
        val bundled = listOf("সরাসরি" to 1252, "\u099B\u09DC\u09BE\u099B\u09DC\u09BF" to 40)
        val merged = BengaliPhoneticIndex.withBundledRanking(downloaded) { bundled }
        assertEquals("সরাসরি", BengaliPhoneticIndex(merged).lookup("sorasori").first())
        // The download keeps its own words, and one spelled with a decomposed
        // nukta is still recognised as the bundled word, not listed twice.
        assertEquals(setOf("সরাসরি", "\u099B\u09DC\u09BE\u099B\u09DC\u09BF", "স্বরলিপি"), merged.map { it.first.replace("\u09A1\u09BC", "\u09DC") }.toSet())
        assertEquals(3, merged.size)
    }

    @Test fun aRankedListLeadsWithItsCountsAndTheBundledListLiftsChatWords() {
        // The bundled list has থেকেও above থেকে; the corpus's 594,781 against
        // 7,135 decides that. A chat word the corpus never saw (2) is lifted by
        // its bundled frequency, ×4, above the list's other unseen words.
        val ranked = listOf("থেকে" to 594_781, "থেকেও" to 7_135, "বানাইছি" to 2, "বানাইসি" to 2)
        val bundled = listOf("থেকেও" to 1311, "থেকে" to 400, "বানাইছি" to 900, "করছি" to 700)
        val merged = BengaliPhoneticIndex.withBundledRanking(ranked) { bundled }.toMap()
        assertEquals(
            mapOf("থেকেও" to 12_379, "থেকে" to 596_381, "বানাইছি" to 3_602, "করছি" to 2_802, "বানাইসি" to 2),
            merged,
        )
        assertEquals("থেকে", BengaliPhoneticIndex(merged.toList()).lookup("theke").first())
    }

    @Test fun aRankedListKeepsTheBundledSpelling() {
        // bn_full spells the nukta decomposed, the bundled list and Avro
        // precomposed: one word, in the bundled spelling, at the summed count.
        val ranked = listOf("\u099B\u09A1\u09BC\u09BE\u099B\u09A1\u09BC\u09BF" to 500, "সরাসরি" to 9_000)
        val bundled = listOf("\u099B\u09DC\u09BE\u099B\u09DC\u09BF" to 40)
        val merged = BengaliPhoneticIndex.withBundledRanking(ranked) { bundled }
        assertEquals(listOf("\u099B\u09DC\u09BE\u099B\u09DC\u09BF" to 660, "সরাসরি" to 9_000), merged)
    }

    /**
     * The published Bangla table (#716) has the bundled list's ranking baked
     * in, so editing `dictionaries-src/bn.txt` leaves every installed table
     * ranking by the old list. Bump [BengaliPhoneticIndex.REVISION], rerun the
     * data repo's `scripts/build_phonetic_indexes.sh` for bn, and update the
     * digest below together.
     */
    @Test fun theBundledListIsTheOneThePublishedTableRanksBy() {
        val list = listOf(File("dictionaries-src/bn.txt"), File("app/dictionaries-src/bn.txt")).first { it.isFile }
        val digest = MessageDigest.getInstance("SHA-256").digest(list.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(2, BengaliPhoneticIndex.REVISION)
        assertEquals("145c1f751f8244ed9f87b41a3be815c4a3a188ca3fe5ba6f78742690b31d6df9", digest)
    }

    @Test fun unknownInputHasNoSiblings() {
        assertEquals(null, top("zzzq"))
    }

    /**
     * Issue #516: "vodor" is ভোঁদড় on desktop Avro's list. A typed o between
     * consonants folds as the inherent vowel, so the strip's lookup cannot see
     * a written ো there; the loose lookup reads it both ways, plain fold first.
     */
    @Test fun looseLookupReadsAMedialOAsOKar() {
        val words = BengaliPhoneticIndex(
            listOf("বদর" to 50, "ভোঁদড়" to 1, "দোকান" to 30, "ভোট" to 40, "বট" to 60),
        )
        assertEquals(false, "ভোঁদড়" in words.lookup("vodor"))
        assertEquals(listOf("বদর", "ভোঁদড়"), words.lookupLoose("vodor"))
        assertEquals(listOf("দোকান"), words.lookupLoose("dokan"))
        assertEquals(listOf("বট", "ভোট"), words.lookupLoose("vot"))
    }
}
