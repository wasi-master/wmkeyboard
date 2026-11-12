package com.wasimaster.wmkeyboard.core.snippets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GboardDictionaryTest {

    // As Gboard's Export writes it: shortcut, word, locale, tab-separated.
    private val export = "# Gboard Dictionary version:1\n" +
        "omw\ton my way\ten-US\n" +
        "\tWasimaster\ten-US\n" +
        "\tNew York City\t\n" +
        "\\alpha\tα\t\n" +
        "\\alpha\tΑ\t\n" +
        "->\t→\t\n"

    private fun zipped(text: String): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(GboardDictionary.ENTRY_NAME))
            zip.write(text.toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()

    @Test
    fun `entries split into words and shortcuts`() {
        val contents = GboardDictionary.read(export)!!
        assertEquals(6, contents.entries.size)
        assertEquals("omw", contents.entries[0].shortcut)
        assertEquals("on my way", contents.entries[0].word)
        assertEquals("en-US", contents.entries[0].locale)
        // A phrase with no shortcut is offered as its words.
        assertEquals(listOf("Wasimaster", "New", "York", "City"), contents.words)
        assertEquals(4, contents.shortcuts.size)
    }

    @Test
    fun `each word keeps the locale it was filed under`() {
        // #930: the personal dictionary files them by language.
        val contents = GboardDictionary.read(export)!!
        assertEquals(
            listOf("Wasimaster" to "en-US", "New" to "", "York" to "", "City" to ""),
            contents.localizedWords,
        )
    }

    @Test
    fun `shortcuts become snippets that ask first`() {
        val converted = GboardDictionary.toSnippets(GboardDictionary.read(export)!!)
        assertEquals(3, converted.snippets.size)
        val omw = converted.snippets[0]
        assertEquals("omw", omw.trigger)
        assertEquals("on my way", omw.text)
        assertTrue(omw.confirm)
        // One shortcut, two words: one snippet with both offered.
        val alpha = converted.snippets[1]
        assertEquals("\\alpha", alpha.trigger)
        assertEquals("α", alpha.text)
        assertEquals(listOf("Α"), alpha.alternates)
        assertEquals(MultiExpand.CHIPS_ONLY, alpha.multiExpand)
        // `->` fires on its last symbol, so it is kept.
        assertEquals("->", converted.snippets[2].trigger)
        assertTrue(converted.notes.isEmpty())
    }

    @Test
    fun `only the header makes a file a gboard dictionary`() {
        assertNull(GboardDictionary.read("omw\ton my way\ten-US\n"))
        assertTrue(GboardDictionary.looksLikeGboard("\uFEFF# Gboard Dictionary version:1\n"))
        assertFalse(GboardDictionary.looksLikeGboard("# Some other dictionary\n"))
    }

    @Test
    fun `the zip gboard exports is read`() {
        val contents = GboardDictionary.readZip(zipped(export).inputStream())
        assertNotNull(contents)
        assertEquals(6, contents!!.entries.size)
        assertTrue(GboardDictionary.looksLikeGboardZip(listOf("dictionary.txt")))
        assertFalse(GboardDictionary.looksLikeGboardZip(listOf("package.yml", "_manifest.yml")))
    }

    @Test
    fun `the snippet reader takes the shortcuts and counts the words`() {
        val text = SnippetPayload.readText(export, "dictionary.txt")!!
        assertEquals(SnippetPayload.Source.GBOARD, text.source)
        assertEquals(3, text.snippets.size)
        assertEquals(GboardDictionary.FOLDER_NAME, text.suggestedName)
        // The words have nowhere to go here, and a note says where they do.
        assertEquals(1, text.notes.size)
        val zip = SnippetPayload.read(zipped(export), "PersonalDictionary.zip")!!
        assertEquals(SnippetPayload.Source.GBOARD, zip.source)
        assertFalse(zip.isEspanso)
    }

    // #1254: Gboard's Japanese keyboard files a word under its kana reading.
    private val japanese = "# Gboard Dictionary version:1\n" +
        "よろ\tよろしくお願いします\tja-JP\n" +
        "おつ\tお疲れ様です\t\n" +
        "メール\tメールアドレス\tja-JP\n" +
        "めあど\tme@example.com\tja-JP\n" +
        "よろ\tよろしく\ten-US\n" +
        "omw\ton my way\ten-US\n"

    @Test
    fun `a kana shortcut is a japanese reading and not a snippet`() {
        val contents = GboardDictionary.read(japanese)!!
        assertEquals(listOf("よろ", "おつ", "メール"), contents.readings.map { it.shortcut })
        // Not a word a Japanese list can hold, or filed under English: those
        // stay snippets, so nothing in the file is lost.
        assertEquals(listOf("めあど", "よろ", "omw"), contents.shortcuts.map { it.shortcut })
        val snippets = GboardDictionary.toSnippets(contents).snippets
        assertFalse(snippets.any { it.text == "よろしくお願いします" })
        assertEquals(3, snippets.size)
    }

    @Test
    fun `readings are written as a japanese word list, without repeats`() {
        val contents = GboardDictionary.read(japanese)!!
        val lines = GboardDictionary.readingList(contents)
        assertEquals(
            listOf("よろ\tよろしくお願いします", "おつ\tお疲れ様です", "メール\tメールアドレス"),
            lines,
        )
        // Importing the same file again adds nothing.
        assertTrue(GboardDictionary.readingList(contents, lines).isEmpty())
    }
}
