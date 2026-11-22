package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.settings.AiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiVisionTest {

    private fun config(provider: AiProvider, model: String, url: String = "") =
        AiClient.Config(provider, "", model, url)

    @Test
    fun `names of seeing families say so`() {
        for (model in listOf("gpt-4o-mini", "qwen/qwen2.5-vl-72b-instruct", "llama-3.2-11b-vision-preview", "meta-llama/llama-4-scout", "pixtral-12b", "deepseek-vl2", "minicpm-v")) {
            assertEquals(model, true, AiVision.fromName(model))
        }
    }

    @Test
    fun `names of text-only families say so`() {
        for (model in listOf("deepseek-chat", "deepseek/deepseek-r1", "gpt-3.5-turbo", "o3-mini", "openai/gpt-oss-120b", "qwen2.5-coder:7b", "llama3.1:8b", "llama-3.3-70b-versatile")) {
            assertEquals(model, false, AiVision.fromName(model))
        }
    }

    @Test
    fun `a name that says neither is unknown`() {
        assertNull(AiVision.fromName("mistral-large-latest"))
        assertNull(AiVision.fromName(""))
    }

    @Test
    fun `a provider with nowhere to put a picture never sees`() {
        assertEquals(false, AiVision.knows(config(AiProvider.DEEPSEEK, "deepseek-chat")))
        assertEquals(false, AiVision.knows(config(AiProvider.BRAVE, "")))
    }

    @Test
    fun `an unknown model is still sent the picture`() {
        assertTrue(AiVision.sees(config(AiProvider.OPENAI_COMPATIBLE, "some-model", "https://api.example.com/v1")))
        assertFalse(AiVision.sees(config(AiProvider.OPENAI_COMPATIBLE, "deepseek-chat", "https://api.example.com/v1")))
    }

    @Test
    fun `a refusal teaches the model is blind`() {
        val blind = config(AiProvider.OPENAI_COMPATIBLE, "plain-model", "https://gateway.example/v1")
        assertNull(AiVision.knows(blind))
        AiVision.markBlind(blind)
        assertEquals(false, AiVision.knows(blind))
    }

    @Test
    fun `only a picture refusal counts as one`() {
        assertTrue(AiVision.refusedPicture(ToolHttpException(0, 400, "This model does not support image input")))
        assertTrue(AiVision.refusedPicture(ToolHttpException(0, 422, "image_url is not supported")))
        assertFalse(AiVision.refusedPicture(ToolHttpException(0, 401, "Invalid image key")))
        assertFalse(AiVision.refusedPicture(ToolHttpException(0, 400, "max_tokens too large")))
        assertFalse(AiVision.refusedPicture(java.io.IOException("image")))
    }

    @Test
    fun `OpenRouter's list is read by input modalities`() {
        val body = """{"data":[
            {"id":"a/sees","architecture":{"input_modalities":["text","image"]}},
            {"id":"b/blind","architecture":{"input_modalities":["text"]}},
            {"id":"c/none"}
        ]}"""
        assertEquals(mapOf("a/sees" to true, "b/blind" to false), AiVision.parseOpenRouter(body))
    }

    @Test
    fun `Ollama's show lists vision`() {
        assertEquals(true, AiVision.parseOllamaShow("""{"capabilities":["completion","vision"]}"""))
        assertEquals(false, AiVision.parseOllamaShow("""{"capabilities":["completion","tools"]}"""))
        assertNull(AiVision.parseOllamaShow("""{"modelfile":"..."}"""))
    }

    @Test
    fun `LM Studio's list calls a seeing model vlm`() {
        val body = """{"data":[{"id":"qwen2.5-vl-7b","type":"vlm"},{"id":"llama-3.1-8b","type":"llm"}]}"""
        assertEquals(true, AiVision.parseLmStudio(body, "qwen2.5-vl-7b"))
        assertEquals(false, AiVision.parseLmStudio(body, "llama-3.1-8b"))
        assertNull(AiVision.parseLmStudio(body, "missing"))
    }
}
