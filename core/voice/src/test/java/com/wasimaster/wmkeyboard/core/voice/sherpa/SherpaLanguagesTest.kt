package com.wasimaster.wmkeyboard.core.voice.sherpa

import com.wasimaster.wmkeyboard.core.voice.executorch.EtCatalog
import com.wasimaster.wmkeyboard.core.voice.executorch.EtEngine
import com.wasimaster.wmkeyboard.core.voice.executorch.EtStore
import com.wasimaster.wmkeyboard.core.voice.executorch.SherpaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline Sherpa's languages beyond its Zipformers: Nemotron's per-stream
 * language, the catalog's offline families, which model a language gets, and
 * the text an offline model hands back.
 */
class SherpaLanguagesTest {

    private val nemotron = EtCatalog.byId("sherpa-nemotron")!!

    @Test
    fun `nemotron is told the locale of a single language`() {
        assertEquals("ja-JP", SherpaLanguages.tagFor(nemotron, listOf("ja")))
        assertEquals("nb-NO", SherpaLanguages.tagFor(nemotron, listOf("nb")))
        assertEquals("en-US", SherpaLanguages.tagFor(nemotron, listOf("en_US")))
    }

    @Test
    fun `a keyboard region nemotron has a locale for gets that locale`() {
        assertEquals("en-GB", SherpaLanguages.tagFor(nemotron, listOf("en_GB")))
        assertEquals("pt-PT", SherpaLanguages.tagFor(nemotron, listOf("pt_PT")))
        assertEquals("pt-BR", SherpaLanguages.tagFor(nemotron, listOf("pt")))
        // No en-AU prompt: the language's own.
        assertEquals("en-US", SherpaLanguages.tagFor(nemotron, listOf("en_AU")))
    }

    @Test
    fun `several languages, or one it does not know, leave nemotron to tell`() {
        assertEquals(SherpaLanguages.AUTO, SherpaLanguages.tagFor(nemotron, listOf("en", "de")))
        assertEquals(SherpaLanguages.AUTO, SherpaLanguages.tagFor(nemotron, emptyList()))
        assertEquals(SherpaLanguages.AUTO, SherpaLanguages.tagFor(nemotron, listOf("bn")))
    }

    @Test
    fun `a model with no language tags is told nothing`() {
        assertNull(SherpaLanguages.tagFor(EtCatalog.byId("sherpa-dolphin-small")!!, listOf("ja")))
        assertNull(SherpaLanguages.tagFor(EtCatalog.byId("sherpa-am-addis")!!, listOf("am")))
        assertEquals("yue", SherpaLanguages.tagFor(EtCatalog.byId("sherpa-sense-voice")!!, listOf("yue")))
    }

    @Test
    fun `sherpa takes the languages its new models bring`() {
        for (code in listOf("am", "fa", "uz", "yue", "my", "km", "tr", "pl", "hi", "ur")) {
            assertTrue(code, EtEngine.SHERPA.supports(code))
        }
        // Not until their exports are pinned.
        assertFalse(EtEngine.SHERPA.supports("ti"))
    }

    @Test
    fun `every sherpa model carries Silero, and every offline one names its files`() {
        for (model in EtCatalog.models.filter { it.engine == EtEngine.SHERPA && !it.punctuation }) {
            assertTrue(model.id, model.files.any { it.name == "silero_vad.onnx" })
            assertTrue(model.id, model.files.any { it.name == "tokens.txt" })
            when (model.sherpaKind) {
                SherpaKind.STREAMING -> {
                    // The engine takes the first file named encoder*.
                    assertTrue(model.id, model.files.first { it.name.startsWith("encoder") }.name.endsWith(".onnx"))
                }
                SherpaKind.MOONSHINE -> {
                    assertTrue(model.id, model.files.any { it.name == "encoder_model.ort" })
                    assertTrue(model.id, model.files.any { it.name == "decoder_model_merged.ort" })
                    assertTrue(model.id, model.nonCommercial)
                }
                else -> assertTrue(model.id, model.files.any { it.name.startsWith("model") })
            }
        }
    }

    @Test
    fun `ids and folders are unique, and every hash is a SHA-256`() {
        val models = EtCatalog.models
        assertEquals(models.size, models.map { it.id }.toSet().size)
        for ((_, ofEngine) in models.groupBy { it.engine }) {
            assertEquals(ofEngine.size, ofEngine.map { it.dirName }.toSet().size)
        }
        for (model in models) {
            for (file in model.files) assertTrue("${model.id} ${file.name}", file.sha256.matches(Regex("[0-9a-f]{64}")))
            assertEquals(model.id, model.files.size, model.files.map { it.name }.toSet().size)
        }
    }

    @Test
    fun `a fine-tune for a language is used for it over a larger general model`() {
        val farsi = EtCatalog.byId("sherpa-fa-nemotron")!!
        val dolphin = EtCatalog.byId("sherpa-dolphin-small")!!
        val both = listOf(dolphin, farsi)
        assertEquals(farsi, EtStore.pick(both, listOf("fa")))
        // Japanese is not Farsi's: Dolphin keeps it.
        assertEquals(dolphin, EtStore.pick(both, listOf("ja")))
        // With no language asked for, the general model.
        assertEquals(dolphin, EtStore.pick(both, emptyList()))
        assertTrue(EtStore.inUse(farsi, both))
        assertTrue(EtStore.inUse(dolphin, both))
    }

    @Test
    fun `the larger of two general models still wins`() {
        val nemotron = EtCatalog.byId("sherpa-nemotron")!!
        val small = EtCatalog.byId("sherpa-en-small")!!
        assertEquals(nemotron, EtStore.pick(listOf(small, nemotron), listOf("en")))
        assertFalse(EtStore.inUse(small, listOf(small, nemotron)))
    }

    @Test
    fun `omnilingual 1B is used over 300M once both are on the phone`() {
        val big = EtCatalog.byId("omnilingual-1b")!!
        val small = EtCatalog.byId("omnilingual-300m")!!
        assertEquals(big, EtStore.pick(listOf(small, big), listOf("ti")))
    }

    @Test
    fun `an offline phrase loses tags and stray spaces`() {
        assertEquals("你好世界", SherpaPhraseStream.clean("<|zh|><|NEUTRAL|><|Speech|><|withitn|>你好世界"))
        assertEquals("hello there", SherpaPhraseStream.clean("  hello   there <0.00> "))
    }

    @Test
    fun `no space goes between phrases of a script written without them`() {
        assertTrue(SherpaPhraseStream.unspaced('你'))
        assertTrue(SherpaPhraseStream.unspaced('。'))
        assertTrue(SherpaPhraseStream.unspaced('ក'))
        assertFalse(SherpaPhraseStream.unspaced('ሰ'))
        assertFalse(SherpaPhraseStream.unspaced('a'))
    }
}
