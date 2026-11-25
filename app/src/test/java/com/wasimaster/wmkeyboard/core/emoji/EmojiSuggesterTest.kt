package com.wasimaster.wmkeyboard.core.emoji

import java.io.File
import java.io.FileInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class EmojiSuggesterTest {

    companion object {
        private lateinit var suggester: EmojiSuggester

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val asset = File("src/main/assets/emoji/catalog.tsv")
            val triggers = EmojiTriggers.load(
                FileInputStream(File("src/main/assets/emoji/triggers.tsv")),
            )
            val shortcodes = EmojiShortcodes.load(
                FileInputStream(File("src/main/assets/emoji/shortcodes.tsv")),
            )
            suggester = EmojiSuggester(
                EmojiCatalog.load(FileInputStream(asset)),
                triggers,
                shortcodes,
            )
        }
    }

    @Test fun birthdaySuggestsThePartySet() {
        assertEquals(listOf("🎂", "🎉", "🥳", "🎁"), suggester.suggest("birthday"))
    }

    /** The emoji panel's Suggested tab (#1554): every word of the text, the last one first. */
    @Test fun textGivesEachWordsEmojiLastWordFirst() {
        val found = suggester.suggestForText("happy birthday, want coffee")
        assertEquals("☕", found.first())
        assertTrue("🎂" in found)
        assertTrue(found.indexOf("☕") < found.indexOf("🎂"))
        assertEquals(found.size, found.toSet().size)
    }

    @Test fun textReadsOnlyItsLastWords() {
        val found = suggester.suggestForText("coffee " + "x ".repeat(20) + "pizza", maxWords = 5)
        assertTrue("🍕" in found)
        assertTrue("☕" !in found)
    }

    @Test fun textWithNoWordsGivesNothing() {
        assertTrue(suggester.suggestForText("  ...  ").isEmpty())
    }

    @Test fun coffeeSuggestsTheCup() {
        assertEquals("☕", suggester.suggest("coffee").first())
    }

    @Test fun bangladeshSuggestsTheFlag() {
        assertTrue("🇧🇩" in suggester.suggest("bangladesh"))
        assertTrue("🇧🇩" in suggester.suggest("Bangladesh"))
        assertTrue("🇧🇩" in suggester.suggest("বাংলাদেশ"))
    }

    @Test fun bengaliBirthdaySuggestsCake() {
        assertEquals(listOf("🎂", "🎉", "🥳", "🎁"), suggester.suggest("জন্মদিন"))
    }

    @Test fun exactKeywordHitsComeFromTheCatalog() {
        assertTrue("🍕" in suggester.suggest("pizza"))
        // Every "cat" hit within the default limit is a real cat emoji
        // (the cat faces outrank 🐱 in catalog order).
        assertTrue("🐱" in suggester.suggest("cat", limit = 10))
    }

    /** One Han character or kana is a word on its own, where one Latin letter never is (#633). */
    @Test fun aSingleHanCharacterIsAKeyword() {
        val packed = EmojiSuggester(
            listOf(
                EmojiEntry("😄", "smileys", listOf("笑")),
                EmojiEntry("🅰️", "symbols", listOf("a")),
            ),
        )
        assertEquals(listOf("😄"), packed.suggest("笑"))
        assertTrue(packed.suggest("a").isEmpty())
    }

    @Test fun simplePluralsStillMatch() {
        assertTrue(suggester.suggest("cakes").isNotEmpty())
    }

    @Test fun noMatchMeansNoSuggestions() {
        assertTrue(suggester.suggest("qzxqzx").isEmpty())
        assertTrue(suggester.suggest("a").isEmpty())
    }

    /** Words gemoji names that the curated table and CLDR keywords both miss. */
    @Test fun gemojiTriggersFillTheGaps() {
        assertTrue("🎉" in suggester.suggest("hooray"))
        assertTrue("☕" in suggester.suggest("espresso"))
        assertTrue("🥳" in suggester.suggest("celebrate"))
        assertTrue("🚀" in suggester.suggest("rocket"))
    }

    /** ...and words only the curated table has keep working. */
    @Test fun curatedTriggersStillWin() {
        assertEquals("😔", suggester.suggest("sorry").first())
        assertEquals("💼", suggester.suggest("work").first())
        assertEquals("😴", suggester.suggest("sleep").first())
    }

    /**
     * Two-letter shortcodes are flag codes and enclosed letters; firing them
     * at anyone typing "it" or "us" is exactly the wrong-emoji case.
     */
    @Test fun shortTwoLetterShortcodesNeverTrigger() {
        assertTrue("🇮🇹" !in suggester.suggest("it"))
        assertTrue("🇺🇸" !in suggester.suggest("us"))
        assertTrue("🇩🇪" !in suggester.suggest("de"))
    }

    /**
     * gemoji tags "chef" on both 👨‍🍳 and 👩‍🍳; offering the pair would spend
     * two of four slots on one concept, so the table names the base instead.
     */
    @Test fun gemojiTriggersCollapseGenderVariants() {
        // The base leads; the CLDR keyword layer may still add its own hits
        // after it ("chef" is also a keyword on the chef's knife).
        assertEquals("🧑‍🍳", suggester.suggest("chef").first())
        assertEquals("🧑‍⚕️", suggester.suggest("doctor").first())
        assertEquals("👮", suggester.suggest("cop").first())
        for (word in listOf("chef", "doctor", "cop", "farmer", "singer")) {
            val offered = suggester.suggest(word)
            assertEquals(
                "$word offers gendered duplicates: $offered",
                offered.size,
                offered.distinct().size,
            )
            assertTrue(
                "$word offers a gendered variant: $offered",
                offered.none { "‍♂" in it || "‍♀" in it },
            )
        }
    }

    @Test fun genderVariantsDoNotFloodResults() {
        // "man" has dozens of gendered-variant entries; those collapse under
        // their base so the plain word yields at most `limit` results.
        assertTrue(suggester.suggest("man").size <= 4)
    }
}
