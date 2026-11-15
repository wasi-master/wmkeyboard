package com.wasimaster.wmkeyboard.core.input.composer

import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.composerType
import com.wasimaster.wmkeyboard.core.layout.script
import com.wasimaster.wmkeyboard.core.script.ComposerType
import com.wasimaster.wmkeyboard.core.script.ScriptDef
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.script.ScriptRegistry
import com.wasimaster.wmkeyboard.core.transliteration.AvroPhonetic
import com.wasimaster.wmkeyboard.core.transliteration.BengaliGraphemes
import com.wasimaster.wmkeyboard.core.transliteration.HindiPhonetic
import com.wasimaster.wmkeyboard.core.transliteration.Khipro
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerTest {

    private val bengali = ScriptRegistry[ScriptId.BENGALI]
    private val latin = ScriptRegistry[ScriptId.LATIN]
    private val devanagari = ScriptDef(
        id = ScriptId.DEVANAGARI,
        composer = ComposerType.INDIC_CLUSTER,
        unicodeRange = 0x0900..0x097F,
    )

    @Test
    fun `factory maps each composer type to the right implementation`() {
        assertSame(NoComposer, composerFor(latin, ComposerType.NONE))
        assertSame(NoComposer, composerFor(latin, ComposerType.DEAD_KEY))
        assertSame(BengaliTransliterateComposer, composerFor(bengali, ComposerType.TRANSLITERATE))
        assertTrue(composerFor(bengali, ComposerType.INDIC_CLUSTER) is IndicClusterComposer)
        assertTrue(composerFor(devanagari, ComposerType.INDIC_CLUSTER) is IndicClusterComposer)
        assertSame(HindiTransliterateComposer, composerFor(devanagari, ComposerType.TRANSLITERATE))
        assertSame(
            UrduTransliterateComposer,
            composerFor(ScriptRegistry[ScriptId.ARABIC], ComposerType.TRANSLITERATE),
        )
        // A transliterator with no engine for its script degrades, never crashes.
        assertSame(NoComposer, composerFor(latin, ComposerType.TRANSLITERATE))
        assertSame(KhiproComposer.CLASSIC, composerFor(bengali, ComposerType.KHIPRO))
        assertSame(KhiproComposer.DARK, composerFor(bengali, ComposerType.KHIPRO_DARK))
        assertSame(NoComposer, composerFor(latin, ComposerType.KHIPRO))
        assertSame(NoComposer, composerFor(latin, ComposerType.KHIPRO_DARK))
    }

    @Test
    fun `the Urdu phonetic layout types through the Urdu composer`() {
        val spec = BuiltInLayouts.URDU_PHONETIC
        assertSame(UrduTransliterateComposer, composerFor(spec.script(), spec.composerType()))
        // Its buffer is roman, so a word half-typed on a Latin keyboard carries
        // into it when the language is switched mid-word (#522).
        assertTrue(UrduTransliterateComposer.isRomanBuffer)
        assertEquals("ur", UrduTransliterateComposer.phoneticLanguage)
        assertEquals("کتب", UrduTransliterateComposer.composeBuffer("kitab"))
        // The preview is diffed from the common prefix, because a key can take
        // the letter before it away: the ا of "ka" when "kar" is typed.
        assertEquals("ا", UrduTransliterateComposer.keyPreview("k", "a", false))
        assertEquals("ر", UrduTransliterateComposer.keyPreview("ka", "r", false))
        assertNull(UrduTransliterateComposer.keyPreview("ka", "1", false))
    }

    @Test
    fun `a phonetic layout types through its own language's rules, not its script's`() {
        // Assamese shares the Bengali script with Avro, Marathi Devanagari with
        // Hindi phonetic, Persian and Arabic the Arabic script with Urdu: the
        // layout's language picks the rules.
        val expected = mapOf(
            BuiltInLayouts.ASSAMESE_PHONETIC to "as",
            BuiltInLayouts.MARATHI_PHONETIC to "mr",
            BuiltInLayouts.GUJARATI_PHONETIC to "gu",
            BuiltInLayouts.PUNJABI_PHONETIC to "pa",
            BuiltInLayouts.ODIA_PHONETIC to "or",
            BuiltInLayouts.TAMIL_PHONETIC to "ta",
            BuiltInLayouts.TELUGU_PHONETIC to "te",
            BuiltInLayouts.KANNADA_PHONETIC to "kn",
            BuiltInLayouts.MALAYALAM_PHONETIC to "ml",
            BuiltInLayouts.NEPALI_PHONETIC to "ne",
            BuiltInLayouts.SANSKRIT_PHONETIC to "sa",
            BuiltInLayouts.PERSIAN_PHONETIC to "fa",
            BuiltInLayouts.ARABIC_PHONETIC to "ar",
            BuiltInLayouts.AVRO to "bn",
            BuiltInLayouts.HINDI_PHONETIC to "hi",
            BuiltInLayouts.URDU_PHONETIC to "ur",
        )
        for ((spec, language) in expected) {
            val composer = spec.resolvedComposer()
            assertEquals(spec.id, language, composer.phoneticLanguage)
            assertTrue(spec.id, composer.isTransliterating)
            // A word half-typed on a Latin keyboard can carry into every one (#522).
            assertTrue(spec.id, composer.isRomanBuffer)
        }
        // Without a language a phonetic layout falls back to its script's first.
        assertSame(BengaliTransliterateComposer, composerFor(bengali, ComposerType.TRANSLITERATE))
        // And the same instance every time, which the service compares by.
        assertSame(
            BuiltInLayouts.TAMIL_PHONETIC.resolvedComposer(),
            BuiltInLayouts.TAMIL_PHONETIC.resolvedComposer(),
        )
    }

    @Test
    fun `Arabizi digits belong to the word`() {
        val arabic = BuiltInLayouts.ARABIC_PHONETIC.resolvedComposer()
        assertTrue(arabic.bufferDigits)
        assertTrue(arabic.digitsStartBuffer)
        // A number on its own is still a number, and not a word to learn.
        assertEquals("2024", arabic.composeBuffer("2024"))
        assertFalse(arabic.isPlausibleWord("2024"))
        // The other phonetic layouts keep digits out of the buffer.
        assertFalse(BuiltInLayouts.PERSIAN_PHONETIC.resolvedComposer().bufferDigits)
        assertFalse(BuiltInLayouts.TAMIL_PHONETIC.resolvedComposer().bufferDigits)
    }

    @Test
    fun `the Khipro layout types through the Khipro composer`() {
        val spec = BuiltInLayouts.KHIPRO
        assertSame(KhiproComposer.CLASSIC, composerFor(spec.script(), spec.composerType()))
        val composer = KhiproComposer.CLASSIC
        assertTrue(composer.isTransliterating)
        // Exact, not phonetic: no Avro ranking pass, only completion.
        assertNull(composer.phoneticLanguage)
        assertEquals("bn", composer.completionLanguage)
    }

    @Test
    fun `Khipro buffers its modifier keys, and a comma only inside a word`() {
        val composer = KhiproComposer.CLASSIC
        composer.variant = Khipro.Variant.TOUCHSCREEN
        for (c in "/;?\\।") assertTrue("$c", composer.buffersChar(c, ""))
        assertFalse(composer.buffersChar(',', ""))
        assertTrue(composer.buffersChar(',', "j"))
        assertFalse(composer.buffersChar('.', "ami"))
        assertFalse(composer.bufferDigits)
    }

    @Test
    fun `a word typed on a hardware keyboard reads against the desktop spec`() {
        val composer = KhiproComposer.CLASSIC
        try {
            composer.variant = Khipro.Variant.DESKTOP
            assertTrue(composer.buffersChar('.', "ami"))
            assertTrue(composer.bufferDigits && composer.digitsStartBuffer)
            assertEquals("কথা।", composer.composeBuffer("kotha."))
            assertEquals("১২", composer.composeBuffer("12"))
        } finally {
            composer.variant = Khipro.Variant.TOUCHSCREEN
        }
        assertEquals("কথা.", composer.composeBuffer("kotha."))
    }

    @Test
    fun `the Khipro Dark layout joins consonants only on the slash`() {
        val spec = BuiltInLayouts.KHIPRO_DARK
        val composer = composerFor(spec.script(), spec.composerType())
        assertSame(KhiproComposer.DARK, composer)
        assertEquals("লাগবে", composer.composeBuffer("lagbe"))
        assertEquals("ন্ত্র্য", composer.composeBuffer("ntrz///"))
        val dark = KhiproComposer.DARK
        try {
            dark.startWord(hardwareKeyboard = true)
            assertEquals(Khipro.Variant.DARK_DESKTOP, dark.variant)
            assertEquals("১২", dark.composeBuffer("12"))
        } finally {
            dark.startWord(hardwareKeyboard = false)
        }
        assertEquals(Khipro.Variant.DARK_TOUCHSCREEN, dark.variant)
    }

    @Test
    fun `Khipro key hints diff the spec's own output`() {
        val composer = KhiproComposer.CLASSIC
        // h after k rewrites ক to খ: the key's contribution is the whole new letter.
        assertEquals("খ", composer.keyPreview("k", "h", wholeCluster = false))
        assertEquals("কা", composer.keyPreview("k", "a", wholeCluster = true))
        // The slicer after a vowel writes the chandrabindu.
        assertEquals("ঁ", composer.keyPreview("ca", "/", wholeCluster = false))
    }

    @Test
    fun `Khipro learns whole Bengali words only`() {
        val composer = KhiproComposer.CLASSIC
        assertTrue(composer.isPlausibleWord(composer.composeBuffer("ami")))
        assertFalse(composer.isPlausibleWord(","))
        assertFalse(composer.isPlausibleWord(composer.composeBuffer("?hello")))
        assertFalse(composer.isPlausibleWord(composer.composeBuffer("kqq")))
    }

    @Test
    fun `NoComposer is a plain 1-to-1 append`() {
        assertFalse(NoComposer.isTransliterating)
        assertFalse(NoComposer.isClusterShaping)
        assertEquals("abc", NoComposer.composeBuffer("abc"))
        assertEquals("x", NoComposer.contextualForm("x", 'a'))
        assertEquals(1, NoComposer.deleteLength("ab"))
        assertEquals(2, NoComposer.deleteLength("a😀")) // surrogate pair
        assertEquals(0, NoComposer.deleteLength(""))
    }

    @Test
    fun `Avro composer defers to AvroPhonetic and stays a live input method`() {
        val composer = BengaliTransliterateComposer
        assertTrue(composer.isTransliterating)
        for (word in listOf("ami", "bhalo", "achi", "kemon")) {
            assertEquals(AvroPhonetic.transliterate(word), composer.composeBuffer(word))
        }
    }

    @Test
    fun `Avro key preview reads the key against the buffer in both modes`() {
        val composer = BengaliTransliterateComposer
        // Word start: both modes agree, because there is no cluster to join.
        assertEquals("ক", composer.keyPreview("", "k", wholeCluster = false))
        assertEquals("ক", composer.keyPreview("", "k", wholeCluster = true))
        // After a consonant the modes part: ADDED shows what the key writes,
        // CLUSTER the conjunct it leaves.
        assertEquals("্ক", composer.keyPreview("k", "k", wholeCluster = false))
        assertEquals("ক্ক", composer.keyPreview("k", "k", wholeCluster = true))
        // A vowel after a consonant is the same split: the bare kar, or the
        // consonant wearing it.
        assertEquals("া", composer.keyPreview("k", "a", wholeCluster = false))
        assertEquals("কা", composer.keyPreview("k", "a", wholeCluster = true))
        // Case is a different letter to Avro, and the preview follows it. `T`
        // is one of the capitals Avro actually spells with (ট against ত); a
        // capital it does not use reads as its own lowercase, and the preview
        // has to agree with that too rather than inventing a letter.
        assertEquals("ত", composer.keyPreview("", "t", wholeCluster = false))
        assertEquals("ট", composer.keyPreview("", "T", wholeCluster = false))
        assertEquals("ক", composer.keyPreview("", "K", wholeCluster = false))
        // The inherent vowel writes no glyph of its own after a consonant, so
        // there is nothing to draw; the caller drops an empty answer.
        assertEquals("", composer.keyPreview("k", "o", wholeCluster = false))
        // Keys the buffer never sees have no honest preview.
        assertNull(composer.keyPreview("k", "5", wholeCluster = false))
        assertNull(composer.keyPreview("k", ".", wholeCluster = true))
        assertNull(composer.keyPreview("k", "", wholeCluster = false))
        // Every preview agrees with the transliterator it is predicting.
        for (buffer in listOf("", "k", "am", "bhal", "kOr")) {
            for (key in listOf("a", "k", "i", "T", "s")) {
                val was = AvroPhonetic.transliterate(buffer)
                val whole = AvroPhonetic.transliterate(buffer + key)
                val added = composer.keyPreview(buffer, key, wholeCluster = false)!!
                // The ADDED preview must cover the WHOLE of what the keypress
                // changes: strip it off the new output and what is left has to
                // be text the field already held. This is the promise the hint
                // makes, and it is why the preview is diffed rather than
                // assumed to be the last character.
                assertTrue(
                    "ADDED preview must cover every change '$key' makes to '$buffer'",
                    was.startsWith(whole.dropLast(added.length)),
                )
                // The CLUSTER preview must be a real grapheme cluster of the
                // output, not an arbitrary tail — that is the whole reason to
                // prefer it: every hint is a shape that appears in the word.
                val cluster = composer.keyPreview(buffer, key, wholeCluster = true)!!
                assertEquals(
                    "CLUSTER preview must be one whole cluster of '$whole'",
                    BengaliGraphemes.clusterDeleteLength(whole),
                    cluster.length,
                )
                assertTrue("CLUSTER preview must be a tail of '$whole'", whole.endsWith(cluster))
            }
        }
    }

    @Test
    fun `Bengali cluster composer matches BengaliGraphemes for deletion and vowel form`() {
        val composer = IndicClusterComposer(bengali)
        assertTrue(composer.isClusterShaping)
        for (text in listOf("জ্ঞ", "কজ্ঞ", "ক্ষ", "বাংলা", "")) {
            assertEquals(
                "deletion must match the tested Bengali path for '$text'",
                BengaliGraphemes.clusterDeleteLength(text),
                composer.deleteLength(text),
            )
        }
        // Vowel key া: kar after a consonant, independent at a word start.
        assertEquals("া", composer.contextualForm("া", 'ক'))
        assertEquals("আ", composer.contextualForm("া", null))
    }

    @Test
    fun `the Korean built-in resolves end to end to the Hangul composer`() {
        val spec = BuiltInLayouts.KOREAN
        assertEquals(ScriptId.HANGUL, spec.script().id)
        assertSame(HangulComposer, composerFor(spec.script(), spec.composerType()))
    }

    @Test
    fun `the Hindi built-in inherits the Devanagari cluster composer from its script`() {
        val spec = BuiltInLayouts.HINDI
        assertEquals(ScriptId.DEVANAGARI, spec.script().id)
        // No per-layout composer override — it inherits INDIC_CLUSTER from the
        // registered Devanagari ScriptDef.
        assertEquals(ComposerType.INDIC_CLUSTER, spec.composerType())
        val composer = composerFor(spec.script(), spec.composerType())
        assertTrue(composer is IndicClusterComposer)
        assertTrue(composer.isClusterShaping)
        // क + ि (consonant + spacing vowel sign) deletes as one cluster.
        assertEquals(2, composer.deleteLength("कि"))
    }

    @Test
    fun `the Hindi phonetic built-in overrides the script with the transliterator`() {
        val spec = BuiltInLayouts.HINDI_PHONETIC
        assertEquals(ScriptId.DEVANAGARI, spec.script().id)
        assertEquals(ComposerType.TRANSLITERATE, spec.composerType())
        val composer = composerFor(spec.script(), spec.composerType())
        assertSame(HindiTransliterateComposer, composer)
        assertTrue(composer.isTransliterating)
        assertFalse(composer.isClusterShaping)
        // What routes its commit and strip through the Hindi dictionary path.
        assertEquals("hi", composer.phoneticLanguage)
        assertEquals("bn", BengaliTransliterateComposer.phoneticLanguage)
        assertNull(NoComposer.phoneticLanguage)
        for (word in listOf("kaise", "namaste", "karna")) {
            assertEquals(HindiPhonetic.transliterate(word), composer.composeBuffer(word))
        }
        // Committed text is Devanagari, so a conjunct still deletes as one unit.
        assertEquals(3, composer.deleteLength("क्ष"))
    }

    @Test
    fun `Hindi key preview is the diff of two transliterations`() {
        val composer = HindiTransliterateComposer
        assertEquals("क", composer.keyPreview("", "k", wholeCluster = false))
        // A closing "a" is long, and the hint says so before the key is pressed.
        assertEquals("ा", composer.keyPreview("k", "a", wholeCluster = false))
        assertEquals("का", composer.keyPreview("k", "a", wholeCluster = true))
        // A cluster that closes the word joins: the key adds the virama too.
        assertEquals("्न", composer.keyPreview("kar", "n", wholeCluster = false))
        assertEquals("र्न", composer.keyPreview("kar", "n", wholeCluster = true))
        assertNull(composer.keyPreview("k", "1", wholeCluster = false))
    }

    @Test
    fun `a Hindi buffer committed half typed is not a word to learn`() {
        assertTrue(HindiTransliterateComposer.isPlausibleWord("करना"))
        assertFalse(HindiTransliterateComposer.isPlausibleWord("कर्"))
        assertFalse(HindiTransliterateComposer.isPlausibleWord(""))
    }

    @Test
    fun `generic Indic deletion removes a conjunct cluster as one unit`() {
        val composer = IndicClusterComposer(devanagari)
        // क् ष  (ka + virama + ssa) is one cluster क्ष.
        assertEquals(3, composer.deleteLength("क्ष"))
        // Consonant + spacing vowel sign कि deletes together.
        assertEquals(2, composer.deleteLength("कि"))
        // Preceded by another syllable: only the last cluster goes.
        assertEquals(3, composer.deleteLength("न" + "क्ष"))
        // A non-Devanagari ending falls back to one char.
        assertEquals(1, composer.deleteLength("ab"))
    }
}
