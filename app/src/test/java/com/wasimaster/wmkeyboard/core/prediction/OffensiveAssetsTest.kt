package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-language offensive-word assets behind "Block offensive words".
 *
 * The setting defaults to on, so an empty or malformed list here is a filter
 * that reads as protecting the user and does not. It used to be one English
 * file of 66 words for all 352 languages; these assert the shape of what
 * replaced it, because the files are generated and a bad regeneration would
 * otherwise be invisible until someone was offered a slur.
 */
class OffensiveAssetsTest {

    private val dir = listOf(
        File("src/main/assets/dictionaries/offensive"),
        File("app/src/main/assets/dictionaries/offensive"),
    ).first { it.isDirectory }

    private fun words(file: File): List<String> =
        file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    @Test
    fun `every language file parses to words`() {
        val files = dir.listFiles { f -> f.extension == "txt" }.orEmpty()
        assertTrue("no offensive lists found in $dir", files.size > 50)
        for (file in files) {
            assertTrue("${file.name} is empty", words(file).isNotEmpty())
        }
    }

    /**
     * The lookup lowercases the candidate before asking, so an entry carrying a
     * capital could never match and would be a word the filter silently lets
     * through.
     */
    @Test
    fun `every entry is lowercase`() {
        for (file in dir.listFiles { f -> f.extension == "txt" }.orEmpty()) {
            for (word in words(file)) {
                assertEquals("${file.name}: $word", word.lowercase(), word)
            }
        }
    }

    /**
     * Letters only. The source lists also carry leetspeak and symbol
     * obfuscations, and a suggestion comes off a word trie whose keys are
     * letters, so those entries can never match anything and are dropped at
     * generation time rather than shipped and searched.
     */
    @Test
    fun `every entry is spelled in letters`() {
        for (file in dir.listFiles { f -> f.extension == "txt" }.orEmpty()) {
            for (word in words(file)) {
                // By code point, not by Char: a supplementary-plane CJK
                // character is two Chars and neither surrogate half is a
                // letter on its own.
                assertTrue(
                    "${file.name}: $word is not spelled in letters",
                    word.codePoints().allMatch {
                        Character.isLetter(it) || it == '\''.code || it == '-'.code
                    },
                )
            }
        }
    }

    /** Every file has to name a language the app actually knows. */
    @Test
    fun `every file names a registered language`() {
        for (file in dir.listFiles { f -> f.extension == "txt" }.orEmpty()) {
            val langId = file.nameWithoutExtension
            assertTrue(
                "$langId is not in the language registry",
                LanguageRegistry.all.any { it.id == langId },
            )
        }
    }

    /**
     * English is the one with a baseline to regress against: the file it
     * replaced held 66 words, which was the whole filter for every language.
     */
    @Test
    fun `english is no longer a token list`() {
        val english = words(File(dir, "en.txt"))
        assertTrue("English list is only ${english.size} words", english.size > 5_000)
    }
}
