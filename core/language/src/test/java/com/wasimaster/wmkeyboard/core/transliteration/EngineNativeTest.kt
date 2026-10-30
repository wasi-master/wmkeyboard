package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One call through every bridge entry point. The engines' own behaviour is
 * tested in the Rust crate (`cargo test` in native/wm-engine, 290 cases
 * mirrored from the Kotlin suites this package used to carry); what this
 * checks is the bridge: that the library loads on this JVM, that strings
 * cross intact in both directions, that the enums' ordinals line up, and
 * that arrays and nulls come back as the facades expect.
 */
class EngineNativeTest {

    @Test fun phoneticEngines() {
        assertEquals("আমি ভালো আছি", AvroPhonetic.transliterate("ami valO achi"))
        assertEquals("कैसे हो", HindiPhonetic.transliterate("kaise ho"))
        assertEquals("करना", HindiPhonetic.variants("karna").first())
        assertTrue("कर्ना" in HindiPhonetic.variants("karna"))
        assertEquals("آپ کیسے ہو?", UrduPhonetic.transliterate("aap kaise ho?"))
        assertTrue("کتاب" in UrduPhonetic.variants("kitab"))
        assertEquals("قلبي", ArabicPhonetic.transliterate("2albi"))
        assertEquals(8, ArabicPhonetic.variants("sa3a").size)
        assertEquals("خوبه", PersianPhonetic.transliterate("khoobe"))
        assertEquals(2, PersianPhonetic.variants("ketab").size)
        assertTrue('ৰ' in AssamesePhonetic.transliterate("tumar"))
        assertEquals("রব", AssamesePhonetic.toBengali("ৰৱ"))
    }

    @Test fun indicEngineAndScripts() {
        val marathi = IndicPhonetic(IndicProfiles.forLanguage("mr")!!)
        assertEquals("करणार", marathi.transliterate("karnar"))
        assertEquals(3, marathi.variants("ratra").size)
        val tamil = IndicPhonetic(IndicProfiles.forLanguage("ta")!!)
        assertEquals("நான்", tamil.transliterate("naan"))
        assertEquals(8, IndicProfiles.all.size)
        assertEquals("कमल", IndicPhonetic(IndicProfiles.forLanguage("mai")!!).transliterate("kamal"))
        assertEquals("కమల", IndicPhonetic(IndicProfiles.forLanguage("lmn-Telu")!!).transliterate("kamal"))
        for (profile in IndicProfiles.minor) {
            val out = IndicPhonetic(profile).transliterate("kamal")
            assertTrue("${profile.languageId}: $out", out.all(profile.script::isNative))
        }
        for ((id, letters) in MinorPhonetic.languages) {
            val out = MinorPhonetic.transliterate(id, "salam")
            assertTrue("$id: $out", out.isNotEmpty() && out.any { it.code in letters })
        }
        assertEquals("anta", IndicProfiles.romanPrep("ta")("andha"))
        assertEquals("karna", IndicProfiles.romanPrep("mr")("karna"))
        assertEquals("कत", IndicProfiles.devanagariPrep("ml")("गद"))
        assertTrue(IndicProfiles.rerankFlatList("ta"))
        assertFalse(IndicProfiles.rerankFlatList("kn"))
        assertEquals("ਪੱਕਾ", IndicScript.GURMUKHI.fromDevanagari("पक्का"))
        assertEquals("पक्का", IndicScript.GURMUKHI.toDevanagari("ਪੱਕਾ"))
        assertEquals("അവൻ", IndicScript.MALAYALAM.fromDevanagari("अवन्"))
        assertTrue(IndicScript.TAMIL.isNative('க'))
        assertFalse(IndicScript.DEVANAGARI.isNative('।'))
    }

    @Test fun khipro() {
        assertEquals("আমি", Khipro.convert("ami"))
        assertEquals("", Khipro.convert(""))
        assertEquals("১২৩", Khipro.convert("123", Khipro.Variant.DESKTOP))
        assertEquals("123", Khipro.convert("123", Khipro.Variant.TOUCHSCREEN))
        assertEquals("ন্ত্র্য", Khipro.convert("ntrz///", Khipro.Variant.DARK_TOUCHSCREEN))
        assertEquals(Khipro.Variant.DARK_DESKTOP, Khipro.Variant.of(dark = true, desktop = true))
        Khipro.warm(Khipro.Variant.DESKTOP)
        KhiproRomanizer.warm()
        assertEquals(listOf("ekhn", "ekhon"), KhiproRomanizer.spellings("এখন"))
        assertEquals("ekhn", KhiproRomanizer.romanize("এখন"))
        assertTrue(KhiproRomanizer.spellings("চাঁদ").all { '/' in it })
        // Run-time specs (#909): the bundled copy's revision, a check that
        // refuses what is not a spec, and an install and a reset.
        assertTrue(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ""").matches(EngineNative.khiproBundledRevision()))
        assertTrue(EngineNative.khiproCheck("<html>404</html>", "input\texpected\n", "").isNotEmpty())
        val dark = java.io.File("../../native/wm-engine/data/khipro/bn-khipro-dark.mim").readText()
        assertEquals("", EngineNative.khiproInstall(Khipro.Variant.DARK_DESKTOP.ordinal, dark))
        assertEquals("আমি", Khipro.convert("ami", Khipro.Variant.DARK_DESKTOP))
        EngineNative.khiproReset(Khipro.Variant.DARK_DESKTOP.ordinal)
        assertTrue(EngineNative.khiproInstall(Khipro.Variant.DARK_DESKTOP.ordinal, "(map)").isNotEmpty())
    }

    @Test fun bijoyAnsi() {
        assertEquals("Avgvi †mvbvi evsjv", BijoyAnsi.convert("আমার সোনার বাংলা"))
        assertEquals("‡K", BijoyAnsi.convert("কে", BijoyAnsi.Version.V2, before = "xyzv"))
        assertEquals("hello", BijoyAnsi.convert("hello"))
        assertTrue(BijoyAnsi.needsConversion("।"))
        assertFalse(BijoyAnsi.needsConversion("hello"))
        assertEquals(BijoyAnsi.Version.V2, BijoyAnsi.Version.of(9))
    }

    @Test fun zawgyi() {
        val zawgyi = "\u1021\u103B\u1015\u100A\u1039\u103B\u1015\u100A\u1039\u1006\u102D\u102F\u1004\u1039\u101B\u102C"
        val unicode = "\u1021\u1015\u103C\u100A\u103A\u1015\u103C\u100A\u103A\u1006\u102D\u102F\u1004\u103A\u101B\u102C"
        assertEquals(unicode, Zawgyi.toUnicode(zawgyi))
        assertEquals(zawgyi, Zawgyi.toZawgyi(unicode))
        assertEquals(unicode, Zawgyi.convertEitherWay(zawgyi))
        assertTrue(Zawgyi.probability(zawgyi) > 0.99)
        assertTrue(Zawgyi.probability(unicode) < 0.01)
        assertEquals(Double.NEGATIVE_INFINITY, Zawgyi.probability("hello"), 0.0)
        assertTrue(Zawgyi.isConfidentlyZawgyi(zawgyi))
        assertFalse(Zawgyi.isConfidentlyZawgyi(unicode))
        assertEquals("hello", Zawgyi.toUnicode("hello"))
    }

    @Test fun bengaliGraphemes() {
        assertEquals(0, BengaliGraphemes.clusterDeleteLength(""))
        assertEquals(1, BengaliGraphemes.clusterDeleteLength("hello"))
        assertEquals(2, BengaliGraphemes.clusterDeleteLength("hi😀"))
        assertEquals(6, BengaliGraphemes.clusterDeleteLength("স্ত্রী"))
        assertEquals(3, BengaliGraphemes.clusterDeleteLength("a".repeat(100) + "ক্ষ"))
        assertEquals(BengaliGraphemes.VowelKeyForm.INDEPENDENT, BengaliGraphemes.vowelFormAfter(null))
        assertEquals(BengaliGraphemes.VowelKeyForm.KAR, BengaliGraphemes.vowelFormAfter('ক'))
        assertEquals(BengaliGraphemes.VowelKeyForm.GLIDE, BengaliGraphemes.vowelFormAfter('া'))
        assertEquals("য়া", BengaliGraphemes.vowelKeyText('া', BengaliGraphemes.VowelKeyForm.GLIDE))
        assertEquals("আ", BengaliGraphemes.vowelKeyText('া', BengaliGraphemes.VowelKeyForm.INDEPENDENT))
        assertNull(BengaliGraphemes.vowelKeyText('ক', BengaliGraphemes.VowelKeyForm.GLIDE))
        assertTrue(BengaliGraphemes.isBengali('ক'))
    }

    @Test fun romanizers() {
        assertEquals("ami 2ta baje asi.", BengaliRomanizer.romanize("আমি ২টা বাজে আসি।"))
        assertEquals("dhonnobad", BengaliRomanizer.romanizeWord("ধন্যবাদ"))
        assertEquals("পড়া", BengaliRomanizer.normalize("পড়া"))
        assertEquals("main theek hoon. tum kaise ho? 2024 OK", DevanagariRomanizer.romanize("मैं ठीक हूँ। तुम कैसे हो? २०२४ OK"))
        assertEquals("karna", DevanagariRomanizer.romanizeWord("करना"))
        assertEquals("ज़", DevanagariRomanizer.normalize("ज़"))
        assertTrue(DevanagariRomanizer.isDevanagari('क'))
        assertEquals("aap kise hin", UrduRomanizer.romanize("آپ کیسے ہیں"))
        assertEquals("bacha", UrduRomanizer.romanizeWord("بچہ"))
        assertEquals("کیا", UrduRomanizer.normalize("كيا"))
        assertTrue(UrduRomanizer.isUrdu('ک'))
        assertFalse(UrduRomanizer.isUrdu('۵'))
    }
}
