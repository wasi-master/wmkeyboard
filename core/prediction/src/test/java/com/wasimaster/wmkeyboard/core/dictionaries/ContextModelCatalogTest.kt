package com.wasimaster.wmkeyboard.core.dictionaries

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextModelCatalogTest {

    @Test
    fun everyEntryPinsExactSizeAndSha256() {
        for (entry in ContextModelCatalog.entries) {
            assertTrue("${entry.fileName}: bytes", entry.bytes > 0L)
            assertEquals("${entry.fileName}: approxBytes", entry.bytes, entry.approxBytes)
            assertEquals("${entry.fileName}: sha256 length", 64, entry.sha256.length)
            assertTrue(
                "${entry.fileName}: sha256 lowercase hex",
                entry.sha256.matches(Regex("[0-9a-f]{64}")),
            )
        }
    }

    @Test
    fun germanIsTheStandardEntry() {
        val entry = ContextModelCatalog.forLanguage("de")
        assertNotNull(entry)
        assertEquals("de_context", entry!!.stem)
        assertEquals("de_context.wmlm", entry.fileName)
        assertEquals(entry, ContextModelCatalog.byFileName("de_context.wmlm"))
        assertEquals(entry, ContextModelCatalog.entry("de", ContextModelVariant.STANDARD))
    }

    @Test
    fun englishVariantsAreStandardFirst() {
        val variants = ContextModelCatalog.variantsFor("en")
        assertEquals(listOf(ContextModelVariant.STANDARD, ContextModelVariant.LARGE), variants.map { it.variant })
        assertEquals("en_context.wmlm", variants.first().fileName)
    }

    @Test
    fun languageAndVariantPairsAreUnique() {
        val pairs = ContextModelCatalog.entries.map { it.languageId to it.variant }
        assertEquals(pairs.size, pairs.toSet().size)
    }

    @Test
    fun integrityAcceptsPinnedBytesAndRejectsMismatches() {
        val file = File.createTempFile("context-model", ".bin")
        try {
            file.writeBytes("abc".toByteArray(Charsets.UTF_8))
            val good = ContextModelEntry(
                languageId = "test",
                repoCode = "test",
                stem = "test",
                bytes = 3,
                sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            )
            assertTrue(ContextModelIntegrity.matches(file, good))

            val wrongSize = ContextModelEntry(
                languageId = "test",
                repoCode = "test",
                stem = "test",
                bytes = 4,
                sha256 = good.sha256,
            )
            assertFalse(ContextModelIntegrity.matches(file, wrongSize))

            val wrongDigest = ContextModelEntry(
                languageId = "test",
                repoCode = "test",
                stem = "test",
                bytes = 3,
                sha256 = "0".repeat(64),
            )
            assertFalse(ContextModelIntegrity.matches(file, wrongDigest))
        } finally {
            file.delete()
        }
    }
}
