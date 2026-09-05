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
        // A transliterator with no engine for its script degrades, never crashes.
        assertSame(NoComposer, composerFor(devanagari, ComposerType.TRANSLITERATE))
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
