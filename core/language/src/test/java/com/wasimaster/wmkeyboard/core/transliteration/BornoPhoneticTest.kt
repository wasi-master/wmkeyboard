package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Test

class BornoPhoneticTest {

    private fun assertReads(expected: String, typed: String) =
        assertEquals(typed, expected, BornoPhonetic.transliterate(typed))

    @Test
    fun `vowels stand alone at a word's start and sign a consonant after one`() {
        assertReads("আমি", "ami")
        assertReads("কি", "ki")
        assertReads("ঐ", "OI")
        assertReads("কৌ", "kOU")
        assertReads("ঈদ", "eed")
        assertReads("ঋ", "rri")
    }

    @Test
    fun `the inherent vowel writes nothing after a consonant`() {
        assertReads("কলম", "kolom")
    }

    @Test
    fun `keys where Borno parts from Avro`() {
        assertReads("চা", "ca")
        assertReads("ছা", "cha")
        assertReads("য", "z")
        assertReads("ক্ষ", "kkh")
        assertReads("জ্ঞান", "ggan")
        assertReads("ঞ", "NG")
        assertReads("ঙ", "Ng")
        assertReads("ং", "ng")
        assertReads("কাঁ", "kaA")
        assertReads("কাঁ", "kaxx")
    }

    @Test
    fun `capitals fold only where the map doesn't name them`() {
        assertReads("ক", "K")
        assertReads("খ", "KH")
        assertReads("ট", "T")
        assertReads("ত", "t")
        assertReads("ষ", "Sh")
        assertReads("শ", "S")
    }

    @Test
    fun `ৎ has three spellings`() {
        assertReads("উৎ", "uX")
        assertReads("উৎ", "uTH")
        assertReads("উৎ", "ut``")
    }

    @Test
    fun `known conjuncts join and other neighbours stay apart`() {
        assertReads("বন্ধু", "bondhu")
        assertReads("ক্ত", "kt")
        assertReads("খক", "khk")
    }

    @Test
    fun `phalas and the reph`() {
        assertReads("প্র", "pr")
        assertReads("ব্য", "bz")
        assertReads("স্ব", "sw")
        assertReads("কর্ম", "korm")
    }

    @Test
    fun `the hasant and its joiners are typed outright`() {
        assertReads("খ্ক", "kh,,k")
        assertReads("ক্‌ত", "k,.t")
        assertReads("ক্‍ত", "k,..t")
    }

    @Test
    fun `a long-press sign stays in the word as it is`() {
        assertReads("উৎ", "uৎ")
        assertReads("কাঁ", "kaঁ")
    }

    @Test
    fun `what no table knows stays`() {
        assertReads("ক1", "k1")
    }
}
