package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Test

/** The glide picks the profile's context frequency weight when a model is open (#1051). */
class SuggestionEngineGlideContextProfileTest {

    private fun engine() = SuggestionEngine(Trie(), BengaliPhoneticIndex(emptyList()), UserLexicon(null))

    @Test
    fun `german reads 0_75 without a model and 0_5 with one`() {
        val engine = engine()
        engine.primaryLanguageId = "de"
        assertEquals(0.75, engine.glideTuningInForce.freqWeight, 0.0)
        engine.retuneGlideForContext(true)
        assertEquals(0.5, engine.glideTuningInForce.freqWeight, 0.0)
        engine.retuneGlideForContext(false)
        assertEquals(0.75, engine.glideTuningInForce.freqWeight, 0.0)
    }

    @Test
    fun `english keeps its frequency weight with a context model`() {
        val engine = engine()
        engine.primaryLanguageId = "en"
        engine.retuneGlideForContext(true)
        assertEquals(1.0, engine.glideTuningInForce.freqWeight, 0.0)
    }

    @Test
    fun `mixed languages keep the shipped frequency weight with a context model`() {
        val engine = engine()
        engine.primaryLanguageId = "de"
        engine.englishAsSecondary = true
        engine.retuneGlideForContext(true)
        assertEquals(1.0, engine.glideTuningInForce.freqWeight, 0.0)
    }
}
