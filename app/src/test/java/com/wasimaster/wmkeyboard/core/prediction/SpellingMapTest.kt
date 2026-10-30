package com.wasimaster.wmkeyboard.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpellingMapTest {

    private fun map(text: String) =
        SpellingMap.load(text.byteInputStream(Charsets.UTF_8))

    @Test fun mapsLoanword() {
        val m = map("keyboard\tকিবোর্ড\nchair\tচেয়ার\n")
        assertEquals(listOf("কিবোর্ড"), m.lookup("keyboard"))
        assertEquals(listOf("চেয়ার"), m.lookup("chair"))
        assertTrue(m.contains("keyboard"))
    }

    @Test fun lookupIsCaseInsensitiveAndTrimmed() {
        val m = map("chair\tচেয়ার\n")
        assertEquals(listOf("চেয়ার"), m.lookup("Chair"))
        assertEquals(listOf("চেয়ার"), m.lookup("  CHAIR "))
    }

    @Test fun duplicateKeysAccumulateInOrder() {
        val m = map("ticket\tটিকেট\nticket\tটিকিট\nticket\tটিকেট\n")
        assertEquals(listOf("টিকেট", "টিকিট"), m.lookup("ticket"))
    }

    @Test fun commentsBlanksAndMalformedSkipped() {
        val m = map(
            """
            # comment

            keyboard	কিবোর্ড
            noTabHere
            	leadingTabEmptyKey
            table
            """.trimIndent(),
        )
        assertEquals(1, m.size)
        assertEquals(listOf("কিবোর্ড"), m.lookup("keyboard"))
        assertFalse(m.contains("table"))
        assertFalse(m.contains("notabhere"))
    }

    @Test fun unmappedReturnsEmpty() {
        assertTrue(SpellingMap.EMPTY.lookup("anything").isEmpty())
        assertTrue(map("chair\tচেয়ার").lookup("mouse").isEmpty())
    }

    @Test fun severalAssetsMergeWithTheFirstLeading() {
        // How the app loads it: the curated loanword list, then the generated
        // romanized one. Both contribute, and a spelling in both keeps the
        // curated form in front.
        val m = SpellingMap.load(
            "keyboard\tকিবোর্ড\npic\tপিক\n".byteInputStream(Charsets.UTF_8),
            "tmr\tতোমার\npic\tছবি\n".byteInputStream(Charsets.UTF_8),
        )
        assertEquals(listOf("কিবোর্ড"), m.lookup("keyboard"))
        assertEquals(listOf("তোমার"), m.lookup("tmr"))
        assertEquals(listOf("পিক", "ছবি"), m.lookup("pic"))
    }

    @Test fun theHindiAssetsAreWellFormed() {
        for (name in listOf("en_hi.tsv", "hi_rom.tsv")) {
            val lines = java.io.File("src/main/assets/dictionaries/$name")
                .readLines()
                .filterNot { it.isBlank() || it.startsWith("#") }
            assertTrue("$name looks empty", lines.size > 100)
            for (line in lines) {
                val parts = line.split("\t")
                assertEquals("malformed line in $name: $line", 2, parts.size)
                assertTrue("non-ascii key in $name: $line", parts[0].all { it in 'a'..'z' })
                assertTrue("empty form in $name: $line", parts[1].isNotBlank())
                assertFalse(
                    "latin in hindi column of $name: $line",
                    parts[1].any { it in 'a'..'z' || it in 'A'..'Z' },
                )
                // Devanagari's nukta letters go the other way from Bengali's:
                // NFC *decomposes* them, the word lists carry the pair, and a
                // precomposed one here would never equal the dictionary's word.
                assertFalse("precomposed nukta in $name: $line", parts[1].any { it in 'क़'..'य़' })
            }
        }
        // Every scheme's assets exist: a missing one is a crash on the first
        // keystroke of that layout, not a quiet fallback.
        for (scheme in PhoneticSchemes.all) {
            for (asset in scheme.spellingAssets) {
                assertTrue("missing $asset", java.io.File("src/main/assets/$asset").isFile)
            }
        }
        assertEquals(
            setOf("bn", "hi", "ur", "mr", "gu", "pa", "or", "ta", "te", "kn", "ml", "as", "fa", "ar"),
            SpellingMap.LANGUAGES,
        )
    }

    @Test fun thePhoneticAssetsAreWellFormed() {
        // Every scheme past Bengali and Hindi, whose assets have tests of their
        // own: a form has to be spelled the way that language's word lists
        // spell it, or it can never equal a word in them.
        for (scheme in PhoneticSchemes.all.filter { it.languageId != "bn" && it.languageId != "hi" }) {
            val id = scheme.languageId
            for (asset in scheme.spellingAssets) {
                val lines = java.io.File("src/main/assets/$asset")
                    .readLines()
                    .filterNot { it.isBlank() || it.startsWith("#") }
                assertTrue("$asset looks empty", lines.size > 80)
                for (line in lines) {
                    val parts = line.split("\t")
                    assertEquals("malformed line in $asset: $line", 2, parts.size)
                    val (key, form) = parts
                    // Arabizi spells letters with digits and an apostrophe.
                    val keyOk = if (id == "ar") key.all { it in 'a'..'z' || it in '0'..'9' || it == '\'' } else key.all { it in 'a'..'z' }
                    assertTrue("bad key in $asset: $line", keyOk)
                    assertTrue("empty form in $asset: $line", form.isNotBlank())
                    assertFalse("latin in $asset: $line", form.any { it in 'a'..'z' || it in 'A'..'Z' })
                    // One word: a space would be committed as a phrase.
                    assertFalse("space in $asset: $line", form.contains(' '))
                    // Every letter the language's own: Urdu and Persian never
                    // write Arabic's ي ك, Arabic never Urdu's or Persian's ی ک ہ,
                    // and an Indic form stays in its script's block. Assamese
                    // writes ক'ত with the apostrophe the word list uses.
                    assertTrue("foreign letter in $asset: $line", form.all { scheme.isNative(it) || it in ALLOWED })
                    when (id) {
                        "ur", "fa" -> assertFalse("Arabic letter in $asset: $line", form.any { it == '\u064A' || it == '\u0643' })
                        "ar" -> assertFalse("Urdu/Persian letter in $asset: $line", form.any { it == '\u06CC' || it == '\u06A9' || it == '\u06C1' })
                    }
                }
            }
        }
    }

    @Test fun theShippedAssetIsWellFormed() {
        // Guards the generated file: a stray Latin letter in the Bengali column
        // means a spelling was echoed back instead of translated, and it would
        // be committed verbatim ahead of every other suggestion.
        val lines = java.io.File("src/main/assets/dictionaries/bn_rom.tsv")
            .readLines()
            .filterNot { it.isBlank() || it.startsWith("#") }
        assertTrue("asset looks empty", lines.size > 10_000)
        for (line in lines) {
            val parts = line.split("\t")
            assertEquals("malformed line: $line", 2, parts.size)
            val spelling = parts[0]
            val bengali = parts[1]
            assertTrue("non-ascii key: $line", spelling.all { it in 'a'..'z' })
            assertTrue("empty form: $line", bengali.isNotBlank())
            assertFalse(
                "latin in bengali column: $line",
                bengali.any { it in 'a'..'z' || it in 'A'..'Z' },
            )
            // Nukta letters must be the precomposed code points the rest of the
            // codebase compares against; the decomposed pair never matches.
            assertFalse("decomposed nukta: $line", bengali.contains('\u09BC'))
        }
    }

    @Test fun anEnglishLookalikeDoesNotTakeABanglaWordsKeys() {
        // Issue #486: the generated list had `fire` as the English word, so
        // Avro wrote the loanword where its own rules spell a far commoner
        // Bangla word. The keys of such a word belong to the rules.
        val keys = listOf("dictionaries/en_bn.tsv", "dictionaries/bn_rom.tsv")
            .flatMap { java.io.File("src/main/assets/$it").readLines() }
            .filterNot { it.isBlank() || it.startsWith("#") }
            .mapTo(HashSet()) { it.substringBefore('\t') }
        val rules = mapOf(
            "fire" to "\u09AB\u09BF\u09B0\u09C7",
            "nice" to "\u09A8\u09BF\u099A\u09C7",
            "make" to "\u09AE\u09BE\u0995\u09C7",
            "here" to "\u09B9\u09C7\u09B0\u09C7",
            "uni" to "\u0989\u09A8\u09BF",
        )
        for ((spelling, word) in rules) {
            assertFalse("$spelling is listed", spelling in keys)
            assertEquals(word, com.wasimaster.wmkeyboard.core.transliteration.AvroPhonetic.transliterate(spelling))
        }
    }

    @Test fun theMinedSpellingsCarryNoPlaceholdersOrLookalikes() {
        // BanglaTLit anonymized its commenters' names, so `imran` was annotated
        // ইউজার. Mining kept that out; a re-run with a looser filter must not
        // put it back, or typing a name would write "user".
        val lines = java.io.File("src/main/assets/dictionaries/bn_rom.tsv")
            .readLines()
            .filterNot { it.isBlank() || it.startsWith("#") }
            .map { it.split("\t") }
        for ((spelling, form) in lines) {
            if (spelling == "user") continue
            assertFalse("placeholder: $spelling", form in setOf("\u0987\u0989\u099C\u09BE\u09B0", "\u0987\u0989\u09B8\u09BE\u09B0"))
        }
        // A curated first reading the forum's habits must not displace.
        assertEquals("\u09AB\u09C7\u09B8\u09AC\u09C1\u0995", lines.first { it[0] == "fb" }[1])
        // And what the mining was for.
        assertEquals("\u09AA\u09CD\u09B2\u09BF\u099C", lines.first { it[0] == "plz" }[1])
    }

    private companion object {
        /** ZWNJ (Persian می‌), the apostrophe (Assamese ক'ত) and the hyphen (ৱাই-ফাই). */
        val ALLOWED = setOf('\u200C', '\'', '-')
    }
}
