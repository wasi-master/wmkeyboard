package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.dictionaries.NgramPackCatalog
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NgramPackCatalogTest {

    @Test
    fun everyEntryTargetsARegisteredLanguage() {
        // The blunder this pins: bn_rom is its own language in the registry,
        // not a mode of "bn" — a pack keyed to a language id that does not
        // exist (or the wrong one) silently never downloads for anyone.
        for (entry in NgramPackCatalog.entries) {
            // byId falls back to GENERIC for unknown ids; the id echo is the
            // real registration check.
            assertEquals(
                "unknown language id ${entry.languageId}",
                entry.languageId,
                LanguageRegistry.byId(entry.languageId).id,
            )
        }
    }

    @Test
    fun urlsPointAtTheDataRepo() {
        for (entry in NgramPackCatalog.entries) {
            assertTrue(entry.bigramUrl().startsWith("https://raw.githubusercontent.com/wasi-master/wmkeyboard-data/"))
            assertTrue(entry.bigramUrl().endsWith(".txt.gz"))
            assertTrue(entry.trigramUrl().endsWith(".txt.gz"))
        }
    }

    @Test
    fun romanizedBengaliIsItsOwnPackNotAvros() {
        assertTrue(NgramPackCatalog.forLanguage("bn_rom") != null)
        // Script-Bengali has a pack of its own, and the blunder to pin is it
        // being pointed at the romanized files: those keys are Latin and could
        // never match a word typed in Bengali script.
        val bengali = NgramPackCatalog.forLanguage("bn")
        assertNotNull(bengali)
        assertEquals("bn_bigrams", bengali!!.bigramStem)
        assertEquals("bn_trigrams", bengali.trigramStem)
    }

    @Test
    fun aRebuiltPackStillAnswersToItsOldFileNames() {
        // An older language pack carries the lists under the names they had
        // before the rebuild, and an import has to recognise them.
        val rebuilt = NgramPackCatalog.entries.first { it.isRebuilt }
        val oldBigrams = rebuilt.bigramFileName.replace("_v2", "")
        val oldTrigrams = rebuilt.trigramFileName.replace("_v2", "")
        assertEquals(false, NgramPackCatalog.byFileName(oldBigrams)!!.second)
        assertEquals(true, NgramPackCatalog.byFileName(oldTrigrams)!!.second)
        assertEquals(rebuilt.repoCode, NgramPackCatalog.byFileName(oldBigrams)!!.first.repoCode)
    }

    @Test
    fun everyEntryHasItsOwnFiles() {
        // Two languages sharing a stem means one of them is reading the
        // other's pairs; bn and bn_rom share the repo directory, so the stems
        // are the only thing keeping them apart.
        // Another code for the same language (azj-Latn for az) names the very
        // same pack on purpose; it is two different packs that must not meet.
        val packs = NgramPackCatalog.entries.map { Triple(it.repoCode, it.bigramStem, it.trigramStem) }.distinct()
        val stems = packs.flatMap { listOf(it.second, it.third) }
        assertEquals(stems.size, stems.distinct().size)
    }
}
