package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [LipiDetect] and [LipiScheme]: what a text is read as, where it can go, and the names native/lipi-jni takes. */
class LipiDetectTest {

    @Test
    fun scriptsComeFromTheirBlocks() {
        assertEquals(LipiScheme.DEVANAGARI, LipiDetect.script("संस्कृतम्"))
        assertEquals(LipiScheme.BENGALI, LipiDetect.script("বাংলা"))
        assertEquals(LipiScheme.TAMIL, LipiDetect.script("தமிழ்"))
        assertEquals(LipiScheme.KANNADA, LipiDetect.script("ಕನ್ನಡ"))
        assertEquals(LipiScheme.SINHALA, LipiDetect.script("සිංහල"))
        assertEquals(LipiScheme.THAI, LipiDetect.script("ไทย"))
    }

    /** Grantha and Brahmi are past U+FFFF: two chars each, read as one code point. */
    @Test
    fun supplementaryScriptsAreReadByCodePoint() {
        val grantha = String(Character.toChars(0x11338)) + String(Character.toChars(0x1134D))
        assertEquals(LipiScheme.GRANTHA, LipiDetect.script(grantha))
        assertEquals(LipiScheme.BRAHMI, LipiDetect.script(String(Character.toChars(0x11013))))
    }

    /** Assamese shares Bengali's block; ৰ and ৱ are its own. */
    @Test
    fun assameseIsToldApartByItsRa() {
        assertEquals(LipiScheme.ASSAMESE, LipiDetect.script("অসমীয়া ৰ"))
        assertEquals(LipiScheme.BENGALI, LipiDetect.script("অসমীয়া"))
    }

    @Test
    fun theMostLettersWin() {
        // Devanagari with an English word and digits in it is Devanagari.
        assertEquals(LipiScheme.DEVANAGARI, LipiDetect.script("hello नमस्ते 123"))
        // Three Tamil letters outweigh two Devanagari ones.
        assertEquals(LipiScheme.TAMIL, LipiDetect.script("का தமி"))
        assertNull(LipiDetect.script("plain English, 42"))
        assertNull(LipiDetect.script(""))
    }

    @Test
    fun marksSayIastOrIso() {
        assertEquals(LipiScheme.IAST, LipiDetect.romanisation("saṃskṛtam"))
        assertEquals(LipiScheme.IAST, LipiDetect.romanisation("kṛṣṇa"))
        // ISO 15919: ṁ, and r with a ring below.
        assertEquals(LipiScheme.ISO_15919, LipiDetect.romanisation("saṁskr̥tam"))
        assertEquals(LipiScheme.ISO_15919, LipiDetect.romanisation("kṛṣṇa dēva"))
    }

    @Test
    fun itransSaysSoWithCaretsAndTildes() {
        assertEquals(LipiScheme.ITRANS, LipiDetect.romanisation("kR^iShNa"))
        assertEquals(LipiScheme.ITRANS, LipiDetect.romanisation("sa~Ngama"))
        assertEquals(LipiScheme.ITRANS, LipiDetect.romanisation("RRiShi"))
    }

    /** Plain letters, and the vowels other languages mark too, say nothing. */
    @Test
    fun otherLanguagesAreNotRomanisations() {
        assertNull(LipiDetect.romanisation("saMskRtam"))
        assertNull(LipiDetect.romanisation("hello world"))
        // Pinyin, Latvian, Polish, Spanish.
        assertNull(LipiDetect.romanisation("mā má nǐ hăo"))
        assertNull(LipiDetect.romanisation("Rīga ābols"))
        assertNull(LipiDetect.romanisation("świat żółty"))
        assertNull(LipiDetect.romanisation("mañana"))
    }

    @Test
    fun sourceFallsBackToTheSettingOnlyForLetters() {
        assertEquals(LipiScheme.HARVARD_KYOTO, LipiDetect.source("saMskRtam", LipiScheme.HARVARD_KYOTO))
        assertEquals(LipiScheme.IAST, LipiDetect.source("saṃskṛtam", LipiScheme.HARVARD_KYOTO))
        assertEquals(LipiScheme.DEVANAGARI, LipiDetect.source("राम", LipiScheme.HARVARD_KYOTO))
        assertNull(LipiDetect.source("12, 34!", LipiScheme.HARVARD_KYOTO))
        assertNull(LipiDetect.source("plain", null))
    }

    @Test
    fun targetsLeadWithTheKeyboardsScriptAndNeverTheSource() {
        val fromIast = LipiScheme.targets(LipiScheme.IAST, "kn")
        assertEquals(LipiScheme.KANNADA, fromIast.first())
        assertFalse(LipiScheme.IAST in fromIast)
        val fromDevanagari = LipiScheme.targets(LipiScheme.DEVANAGARI, "hi")
        // Hindi's own script is the source, so the romanisations lead.
        assertEquals(LipiScheme.IAST, fromDevanagari.first())
        assertFalse(LipiScheme.DEVANAGARI in fromDevanagari)
        assertEquals(LipiScheme.entries.size - 1, fromDevanagari.size)
        assertEquals(fromDevanagari.size, fromDevanagari.distinct().size)
    }

    @Test
    fun romanisationsAreTheFirstEight() {
        assertEquals(8, LipiScheme.entries.count { it.roman })
        assertTrue(LipiScheme.entries.filter { it.roman }.all { it.block == null })
        assertTrue(LipiScheme.asciiInputs.all { it.roman })
        assertEquals(LipiScheme.HARVARD_KYOTO, LipiScheme.of("HarvardKyoto"))
        assertNull(LipiScheme.of("harvardkyoto"))
    }

    /** No two scripts claim one code point, or detection would depend on enum order. */
    @Test
    fun blocksDoNotOverlap() {
        val blocks = LipiScheme.entries.mapNotNull { it.block }
        for (i in blocks.indices) for (j in i + 1 until blocks.size) {
            assertFalse("${blocks[i]} ${blocks[j]}", blocks[i].first <= blocks[j].last && blocks[j].first <= blocks[i].last)
        }
    }

    /** native/lipi-jni's test lists the names this enum passes; the two lists are one contract. */
    @Test
    fun nativeNamesMatchTheRustCrate() {
        val lib = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "native/lipi-jni/src/lib.rs") }
            .first { it.exists() }
            .readText()
        val listed = Regex("\"([A-Za-z0-9]+)\"").findAll(lib.substringAfter("const KOTLIN_NAMES").substringBefore("];"))
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(LipiScheme.entries.map { it.native }.toSet(), listed)
    }

    /** Without the library nothing converts, and a same-scheme convert is the text itself. */
    @Test
    fun convertWithoutTheLibrary() {
        assertEquals("abc", Lipi.convert("abc", LipiScheme.IAST, LipiScheme.IAST))
        assertNull(Lipi.convert("abc", LipiScheme.IAST, LipiScheme.DEVANAGARI))
    }
}
