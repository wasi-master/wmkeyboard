package com.wasimaster.wmkeyboard.cjk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictStore
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictionaries
import com.wasimaster.wmkeyboard.core.input.composer.ConversionDictionary
import com.wasimaster.wmkeyboard.core.input.composer.JapaneseComposer
import com.wasimaster.wmkeyboard.core.input.composer.Kana
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-End Instrumented tests for Japanese Romaji conversion (Phase 5).
 */
@RunWith(AndroidJUnit4::class)
class CjkJapaneseRomajiE2ETest {

    @Before
    @After
    fun resetGlobals() {
        CjkDictionaries.japanese = ConversionDictionary.EMPTY
    }

    /**
     * Test P5.1: Romaji candidates for `nihon` MUST include Kanji 日本 when the
     * real 41 MB `ja_kana` pack is loaded — the case that once OOM'd.
     *
     * Skipped rather than failed when the pack is not on the device. The previous
     * version substituted an EMPTY dictionary and then asserted 日本 was in it,
     * so a device that had simply never downloaded the pack reported a failure
     * that said nothing about the code — and reinstalling the app during a test
     * run wipes app files, which is enough to trigger it.
     */
    @Test
    fun testP5_1_JapaneseRomajiKanjiCandidatesMustBePresent() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val filesDir = appContext.filesDir
        val jaFile = CjkDictStore.downloadedFileFor(filesDir, "ja_kana")
        assumeTrue(
            "ja_kana pack not downloaded on this device — download it in Settings to run this test",
            jaFile != null && jaFile.isFile,
        )

        // The point of the test: the real pack has to parse without running out
        // of memory, and still convert.
        CjkDictionaries.japanese = jaFile!!.bufferedReader().useLines { ConversionDictionary.parse(it) }
        assertFalse("ja_kana pack loaded but produced an empty table", CjkDictionaries.japanese.isEmpty)

        val candidates = JapaneseComposer.candidates("nihon")

        // Specification assertion: Japanese candidates MUST include Kanji 日本
        assertTrue("Japanese romaji candidates MUST contain Kanji 日本 when dictionary is loaded", candidates.contains("日本"))
    }

    @Test
    fun testP5_3_ConsumedRomajiLengthMath() {
        CjkDictionaries.japanese = ConversionDictionary.parse(
            sequenceOf(
                "に\t日\t50",
                "にほん\t日本\t100"
            )
        )

        val consumedWhole = JapaneseComposer.consumedFor("nihon", "日本")
        assertEquals(5, consumedWhole)

        val consumedPrefix = JapaneseComposer.consumedFor("nihon", "日")
        assertEquals(2, consumedPrefix)

        val remainingRomaji = "nihon".substring(consumedPrefix)
        assertEquals("hon", remainingRomaji)
        assertEquals("ほん", JapaneseComposer.composeBuffer(remainingRomaji))
    }

    @Test
    fun testP5_4_SyllabicN() {
        assertEquals("こんにちわ", Kana.toHiragana("konnnichiwa"))
        assertEquals("こんにちは", Kana.toHiragana("konnnichiha"))
        assertEquals("しんぶん", Kana.toHiragana("shinbun"))
        assertEquals("おんな", Kana.toHiragana("onnna"))
    }

    @Test
    fun testP5_5_SokuonDoubleConsonant() {
        assertEquals("けっこん", Kana.toHiragana("kekkon"))
        assertEquals("がっこう", Kana.toHiragana("gakkou"))
    }

    @Test
    fun testP5_6_YoonCluster() {
        assertEquals("きょう", Kana.toHiragana("kyou"))
        assertEquals("きゃく", Kana.toHiragana("kyaku"))
        assertEquals("しゃしん", Kana.toHiragana("shashin"))
    }

    @Test
    fun testP5_7_HalfWidthKatakanaAndDakuten() {
        assertEquals("ﾆﾎﾝ", Kana.toHalfWidthKatakana("ニホン"))
        assertEquals("ｶﾞ", Kana.toHalfWidthKatakana("ガ"))
        assertEquals("ﾊﾟ", Kana.toHalfWidthKatakana("パ"))
        assertEquals("ｳﾞ", Kana.toHalfWidthKatakana("ヴ"))
    }

    @Test
    fun testP5_8_SpaceKeyOneStepCommit() {
        val candidates = JapaneseComposer.candidates("kyou")
        assertTrue(candidates.isNotEmpty())

        val topCandidate = candidates[0]
        val consumed = JapaneseComposer.consumedFor("kyou", topCandidate)
        assertEquals(4, consumed)
    }

    @Test
    fun testP5_9_NoPackGracefulFallback() {
        CjkDictionaries.japanese = ConversionDictionary.EMPTY

        val candidates = JapaneseComposer.candidates("nihon")
        assertTrue(candidates.contains("にほん"))
        assertTrue(candidates.contains("ニホン"))
        assertTrue(candidates.contains("ﾆﾎﾝ"))
    }
}
