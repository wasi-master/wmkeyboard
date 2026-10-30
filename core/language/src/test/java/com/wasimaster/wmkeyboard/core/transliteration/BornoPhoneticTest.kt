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
        assertReads("করম", "korm")
        assertReads("কর্ম", "korrm")
        assertReads("রর", "rr")
        assertReads("ক্য", "ky")
        assertReads("কয়", "kY")
        assertReads("র\u200D্য", "rz")
        assertReads("প্র্য", "prz")
        assertReads("প্রত", "prt")
    }

    @Test
    fun `n is the nasal of the row it joins`() {
        assertReads("অঙ্ক", "onk")
        assertReads("অঙ্খ", "onkh")
        assertReads("অঞ্চ", "onc")
        assertReads("অঞ্জ", "onj")
        assertReads("অন্ত", "ont")
    }

    @Test
    fun `ng before a vowel is ঙ্গ, and alone is ং`() {
        assertReads("রাঙ্গা", "ranga")
        assertReads("বাংলা", "bangla")
        assertReads("রং", "rong")
        assertReads("সঙ্ঘ", "songh")
    }

    @Test
    fun `a cluster joins once, but for two that are written with three`() {
        assertReads("স্কুল", "skul")
        assertReads("স্ক্ল", "skl")
        assertReads("স্প্ল", "spl")
        assertReads("ন্তক", "ntk")
    }

    @Test
    fun `keys that read as more than a letter`() {
        assertReads("এক্স", "x")
        assertReads("বক্স", "box")
        assertReads("ক", "q")
        assertReads("ওয়া", "wa")
        assertReads("ওয়ে", "we")
        assertReads("ও", "w")
        assertReads("কাও", "kao")
    }

    @Test
    fun `a backtick forces a vowel's sign, and two write one`() {
        assertReads("া", "a`")
        assertReads("কত", "k`t")
        assertReads("`", "``")
    }

    @Test
    fun `digits and the taka are Bangla`() {
        assertReads("১২", "12")
        assertReads("১.৫", "1.5")
        assertReads("৳", "$")
        assertReads("$", "$$")
    }

    @Test
    fun `the nukta sits on the consonant`() {
        assertReads("ক়", "k/")
        assertReads("ক়", "k./")
    }

    @Test
    fun `backslashes keep Latin`() {
        assertReads("facebook", "facebook\\\\")
        assertReads("facebook-এর", "facebook\\\\-er")
        assertReads("কি\\", "ki\\")
        assertReads("ki\\", "ki\\\\\\")
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
        assertReads("ক@", "k@")
    }
}
