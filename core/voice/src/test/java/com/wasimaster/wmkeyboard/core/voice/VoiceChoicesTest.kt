package com.wasimaster.wmkeyboard.core.voice

import com.wasimaster.wmkeyboard.core.voice.executorch.EtCatalog
import com.wasimaster.wmkeyboard.core.voice.executorch.EtEngine
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceChoicesTest {

    private val multilingual = WhisperCatalog.models.first { it.langCodes.isEmpty() }
    private val englishOnly = WhisperCatalog.models.first { it.fixedLang == "en" }
    private val parakeet = EtCatalog.forEngine(EtEngine.PARAKEET).first()
    private val moonshine = EtCatalog.forEngine(EtEngine.MOONSHINE).first()
    private val sherpaBangla = EtCatalog.byId("sherpa-bn")!!
    private val sherpaEnglish = EtCatalog.byId("sherpa-en")!!

    private fun choices(languageId: String, whistle: Boolean = true, server: Boolean = true) =
        VoiceChoices.forLanguage(
            languageId = languageId,
            whistleReady = whistle,
            whisperModels = listOf(multilingual, englishOnly),
            etModels = listOf(parakeet, moonshine, sherpaBangla, sherpaEnglish),
            serverConfigured = server,
        )

    @Test fun englishGetsEveryEngineInOrder() {
        val engines = choices("en").map { it.engine }.distinct()
        assertEquals(listOf("system", "whistle", "whisper", "parakeet", "moonshine", "sherpa", "server"), engines)
        assertTrue(choices("en").any { it.key == "sherpa:sherpa-en" })
        assertTrue(choices("en").any { it.modelId == englishOnly.id })
    }

    @Test fun onlyWhatServesTheLanguageIsListed() {
        val bangla = choices("bn")
        assertEquals(listOf("system", "whisper:${multilingual.id}", "sherpa:sherpa-bn", "server"), bangla.map { it.key })
        assertFalse(bangla.any { it.modelId == englishOnly.id })
    }

    @Test fun noServerAddressNoServer() {
        assertFalse(choices("de", server = false).any { it.engine == "server" })
        assertFalse(choices("de", whistle = false).any { it.engine == "whistle" })
        assertTrue(choices("de").any { it.engine == "parakeet" })
    }

    @Test fun sherpaOffersOnlyTheModelForTheLanguage() {
        assertFalse(choices("en").any { it.modelId == sherpaBangla.id })
        assertFalse(choices("bn").any { it.modelId == sherpaEnglish.id })
        assertTrue(choices("en_GB").any { it.modelId == sherpaEnglish.id })
    }

    @Test fun punctuationIsNeverADictationChoice() {
        val punct = EtCatalog.sherpaPunctuation
        assertFalse(punct.supports("en"))
        val withPunct = VoiceChoices.forLanguage("en", false, emptyList(), listOf(punct), false)
        assertEquals(listOf("system"), withPunct.map { it.key })
    }

    @Test fun everySherpaModelCarriesSileroAndAWholeModel() {
        for (model in EtCatalog.forEngine(EtEngine.SHERPA).filterNot { it.punctuation }) {
            val names = model.files.map { it.name }
            assertTrue(model.id, "silero_vad.onnx" in names && "tokens.txt" in names)
            for (part in listOf("encoder", "decoder")) {
                assertEquals(model.id + part, 1, names.count { it.startsWith(part) })
            }
            assertEquals(model.id, if ("paraformer" in model.id) 0 else 1, names.count { it.startsWith("joiner") })
            assertTrue(model.id, model.files.all { it.sha256.length == 64 })
        }
    }

    @Test fun omnilingualReachesLanguagesNoOtherEngineDoes() {
        val omni = EtCatalog.forEngine(EtEngine.OMNILINGUAL).single()
        val yoruba = VoiceChoices.forLanguage("yo", false, emptyList(), listOf(parakeet, omni), false)
        assertEquals(listOf("system", "omnilingual:${omni.id}"), yoruba.map { it.key })
        for (language in listOf("en_GB", "hi", "bn", "sw", "ta", "ar", "fa", "zh", "nus-Latn", "ckb")) {
            assertTrue(language, omni.supports(language))
        }
        // Not in its list, or written by it in another script than the keyboard's.
        for (language in listOf("gd", "sa", "ber", "crh", "mni", "tcy")) {
            assertFalse(language, omni.supports(language))
        }
        val names = omni.files.map { it.name }
        assertEquals(listOf("tokens.txt", "silero_vad.onnx", "model.int8.onnx"), names)
        assertTrue(omni.files.all { it.sha256.length == 64 })
    }
}
