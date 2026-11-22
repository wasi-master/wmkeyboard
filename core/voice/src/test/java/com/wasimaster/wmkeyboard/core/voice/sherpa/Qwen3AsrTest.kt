package com.wasimaster.wmkeyboard.core.voice.sherpa

import com.wasimaster.wmkeyboard.core.voice.executorch.EtCatalog
import com.wasimaster.wmkeyboard.core.voice.executorch.EtCustomModels
import com.wasimaster.wmkeyboard.core.voice.executorch.EtEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Qwen3AsrTest {

    @Test
    fun `one language is named to the model, several or an unknown one are not`() {
        assertEquals("English", Qwen3Languages.promptName(listOf("en_GB")))
        assertEquals("Filipino", Qwen3Languages.promptName(listOf("tl")))
        assertEquals("Cantonese", Qwen3Languages.promptName(listOf("yue")))
        // Two keyboards for one language are still one language.
        assertEquals("German", Qwen3Languages.promptName(listOf("de", "de_CH")))
        assertNull(Qwen3Languages.promptName(listOf("en", "hi")))
        assertNull(Qwen3Languages.promptName(listOf("bn")))
        assertNull(Qwen3Languages.promptName(emptyList()))
    }

    @Test
    fun `the engine covers the thirty languages and runs on ONNX Runtime as it speaks`() {
        assertEquals(30, Qwen3Languages.codes.size)
        assertTrue(EtEngine.QWEN3.onnx)
        assertTrue(EtEngine.QWEN3.live)
        assertTrue(EtEngine.QWEN3.takesCustomModels)
        assertTrue(EtEngine.QWEN3.supports("zh_TW"))
        assertFalse(EtEngine.QWEN3.supports("bn"))
        assertEquals(EtEngine.QWEN3, EtEngine.byId("qwen3"))
    }

    @Test
    fun `the catalog model has every file sherpa-onnx opens, flattened into one folder`() {
        val model = EtCatalog.forEngine(EtEngine.QWEN3).single()
        val names = model.files.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertEquals(EtCustomModels.QWEN3_TOKENIZER.toSet() + "silero_vad.onnx", names.filterNot { it.endsWith(".int8.onnx") || it == "conv_frontend.onnx" }.toSet())
        assertTrue(model.writesPunctuation)
        assertTrue(model.files.all { it.sha256.length == 64 })
    }

    @Test
    fun `hotwords lose commas, repeats and the list past its cap`() {
        assertEquals("Wasimaster,WM Keyboard", Qwen3AsrEngine.hotwords(listOf(" Wasimaster ", "WM Keyboard", "Wasimaster", "")))
        assertEquals("a b", Qwen3AsrEngine.hotwords(listOf("a,b")))
        assertNull(Qwen3AsrEngine.hotwords(emptyList()))
        assertEquals(24, Qwen3AsrEngine.hotwords((1..40).map { "w$it" })!!.split(',').size)
    }

    @Test
    fun `a phrase may write about ten tokens a second, never past the cap`() {
        assertEquals(24, Qwen3AsrEngine.maxTokensFor(0))
        assertEquals(124, Qwen3AsrEngine.maxTokensFor(160_000))
        assertEquals(256, Qwen3AsrEngine.maxTokensFor(16_000 * 60))
    }

    @Test
    fun `a Qwen3-ASR export keeps one of each graph, int8 first, its weights and the tokenizer`() {
        val names = listOf(
            "conv_frontend.onnx", "encoder.onnx", "encoder.int8.onnx", "decoder.int8.onnx", "decoder.int8.onnx.data",
            "decoder.onnx", "vocab.json", "merges.txt", "tokenizer_config.json", "README.md", "ar1.wav",
        )
        assertTrue(EtCustomModels.isQwen3(names))
        assertEquals(
            listOf(
                "conv_frontend.onnx", "encoder.int8.onnx", "decoder.int8.onnx", "decoder.int8.onnx.data",
                "vocab.json", "merges.txt", "tokenizer_config.json",
            ),
            EtCustomModels.qwen3Parts(names),
        )
    }

    @Test
    fun `a Qwen3-ASR export without its tokenizer is refused`() {
        assertNull(EtCustomModels.qwen3Parts(listOf("conv_frontend.onnx", "encoder.onnx", "decoder.onnx", "vocab.json")))
        assertFalse(EtCustomModels.isQwen3(listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")))
    }
}
