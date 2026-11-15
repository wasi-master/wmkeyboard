package com.wasimaster.wmkeyboard.core.tools.offlinedict

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DictCcTest {

    private val sample = """
        # DE-EN vocabulary database	compiled by dict.cc
        # Date and time	2026-11-16 10:00
        # License	THIS FILE IS NOT TO BE SHARED

        Haus {n}	house	noun	[archi.]
        Haus {n}	home	noun
        Gift {n}	poison	noun	[chem.]
        abhauen [ugs.]	to beat it [coll.]	verb
        (sich) waschen	to wash (oneself)	verb
        Hausaufgabe {f}	homework	noun	[school]
    """.trimIndent()

    private fun converted(bytes: ByteArray): WmDictFile {
        val files = Files.createTempDirectory("dictcc").toFile()
        val made = DictCc.convert(ByteArrayInputStream(bytes), files)
        assertEquals("dictcc_de_en", made.id)
        assertEquals("de", made.langId)
        return WmDictFile.open(made.file)!!
    }

    @Test
    fun `a list becomes a pack both ways round, notes kept in the meaning`() {
        converted(sample.toByteArray()).use { dict ->
            val haus = OfflineDictionaries.lookup(dict, "haus", englishRules = false).single()
            assertEquals("Haus", haus.word)
            val meanings = haus.meanings.flatMap { m -> m.definitions.map { it.text } }
            assertEquals(listOf("home", "house"), meanings.sorted())
            assertEquals("noun", haus.meanings.first().partOfSpeech)
            // English side: "to beat it [coll.]" is found as "beat it", its German with the register note.
            val beat = OfflineDictionaries.lookup(dict, "beat it", englishRules = false).single()
            assertEquals("abhauen [ugs.]", beat.meanings.single().definitions.single().text)
            // Optional words in parentheses are not part of the key.
            assertTrue(OfflineDictionaries.lookup(dict, "waschen", englishRules = false).isNotEmpty())
            assertTrue(OfflineDictionaries.lookup(dict, "poison", englishRules = false).isNotEmpty())
            assertEquals("de", dict.header["lang"])
        }
    }

    @Test
    fun `a zipped list reads the same, and a file that is no list is refused`() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("readme.html"))
                z.write("x".toByteArray())
                z.putNextEntry(ZipEntry("dict.cc-de-en.txt"))
                z.write(sample.toByteArray())
            }
        }.toByteArray()
        converted(zip).use { dict -> assertTrue(OfflineDictionaries.lookup(dict, "hausaufgabe", englishRules = false).isNotEmpty()) }
        try {
            DictCc.convert(ByteArrayInputStream("hello\tworld\n".repeat(80).toByteArray()), Files.createTempDirectory("x").toFile())
            fail("not a list")
        } catch (_: DictCc.NotDictCcException) {
        }
    }

    @Test
    fun `headwords lose their notes and an English verb its to`() {
        assertEquals("Haus", DictCc.headword("Haus {n}"))
        assertEquals("abhauen", DictCc.headword("abhauen [ugs.]"))
        assertEquals("beat it", DictCc.headword("to beat it [coll.]"))
        assertEquals("UNO", DictCc.headword("UNO <Abk.>"))
        assertEquals("de" to "en", DictCc.pair("# DE-EN vocabulary database"))
    }
}
