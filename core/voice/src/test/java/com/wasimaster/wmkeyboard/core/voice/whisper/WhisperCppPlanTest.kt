package com.wasimaster.wmkeyboard.core.voice.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperCppPlanTest {

    private fun model(size: WhisperSize, english: Boolean = false) = WhisperModel(
        id = "test",
        displayName = "Whisper test",
        size = size,
        repo = "repo",
        modelFile = "ggml-test.bin",
        vocabFile = "",
        modelBytes = 1,
        vocabBytes = 0,
        langCodes = if (english) setOf("en") else emptySet(),
        selectableLang = !english,
        tier = WhisperTier.STANDARD,
        descriptionRes = 0,
        format = WhisperFormat.GGML,
    )

    @Test
    fun `beam search on the sizes where it is cheap, greedy on the big ones`() {
        assertEquals(5, WhisperCppPlan.beamSize(model(WhisperSize.TINY)))
        assertEquals(5, WhisperCppPlan.beamSize(model(WhisperSize.SMALL)))
        assertEquals(5, WhisperCppPlan.beamSize(model(WhisperSize.TURBO)))
        assertEquals(1, WhisperCppPlan.beamSize(model(WhisperSize.MEDIUM)))
        assertEquals(1, WhisperCppPlan.beamSize(model(WhisperSize.LARGE)))
    }

    @Test
    fun `auto asks for the GPU from Base up, the explicit choices always win`() {
        assertEquals(false, WhisperCppPlan.wantsGpu(model(WhisperSize.TINY), WhisperCompute.AUTO, adreno = true))
        assertEquals(true, WhisperCppPlan.wantsGpu(model(WhisperSize.BASE), WhisperCompute.AUTO, adreno = true))
        assertEquals(true, WhisperCppPlan.wantsGpu(model(WhisperSize.TURBO), WhisperCompute.AUTO, adreno = true))
        assertEquals(true, WhisperCppPlan.wantsGpu(model(WhisperSize.TINY), WhisperCompute.GPU, adreno = true))
        assertEquals(false, WhisperCppPlan.wantsGpu(model(WhisperSize.LARGE), WhisperCompute.CPU, adreno = true))
        // Other GPUs (the Vulkan build) only when GPU is picked, never on Auto.
        assertEquals(false, WhisperCppPlan.wantsGpu(model(WhisperSize.BASE), WhisperCompute.AUTO, adreno = false))
        assertEquals(true, WhisperCppPlan.wantsGpu(model(WhisperSize.BASE), WhisperCompute.GPU, adreno = false))
        // An unknown stored value reads as Auto.
        assertEquals(true, WhisperCppPlan.wantsGpu(model(WhisperSize.SMALL), "npu", adreno = true))
    }

    @Test
    fun `every dictation language is named, in order, in Whisper's spelling`() {
        assertEquals(listOf("bn", "en"), WhisperCppPlan.languages(model(WhisperSize.SMALL), listOf("bn", "en")))
        // Bokmål is Whisper's "no"; a language Whisper lacks is left out.
        assertEquals(listOf("no"), WhisperCppPlan.languages(model(WhisperSize.SMALL), listOf("nb", "zz")))
        assertEquals(listOf("de"), WhisperCppPlan.languages(model(WhisperSize.SMALL), listOf("de", "de")))
    }

    @Test
    fun `an English-only model is told no language`() {
        assertTrue(WhisperCppPlan.languages(model(WhisperSize.BASE, english = true), listOf("en")).isEmpty())
    }

    @Test
    fun `the prompt lists the user's words once each, up to the cap`() {
        assertNull(WhisperCppPlan.prompt(emptyList()))
        assertNull(WhisperCppPlan.prompt(listOf(" ", "")))
        assertEquals("Glossary: Arian, WM Keyboard.", WhisperCppPlan.prompt(listOf(" Arian ", "WM Keyboard", "Arian")))
        val many = (1..100).map { "word$it" }
        val prompt = WhisperCppPlan.prompt(many)!!
        assertTrue(prompt.endsWith("word${WhisperCppPlan.PROMPT_WORDS}."))
        assertTrue(!prompt.contains("word${WhisperCppPlan.PROMPT_WORDS + 1}"))
    }

    @Test
    fun `a word with a control character is not prompted`() {
        assertEquals("Glossary: ok.", WhisperCppPlan.prompt(listOf("bad\u0000word", "ok")))
    }

    @Test
    fun `a model held to one language is told that one, whatever is dictated`() {
        val hinglish = model(WhisperSize.BASE).copy(langCodes = setOf("hi"), decodeAs = "en")
        assertEquals(listOf("en"), WhisperCppPlan.languages(hinglish, listOf("hi", "en")))
        val swedish = model(WhisperSize.SMALL).copy(langCodes = setOf("sv"), decodeAs = "sv")
        assertEquals(listOf("sv"), WhisperCppPlan.languages(swedish, listOf("en")))
    }

    @Test
    fun `a model that takes no prompt gets none`() {
        val words = listOf("Arian")
        assertEquals("Glossary: Arian.", WhisperCppPlan.prompt(model(WhisperSize.BASE), words))
        assertNull(WhisperCppPlan.prompt(model(WhisperSize.BASE).copy(takesPrompt = false), words))
    }
}
