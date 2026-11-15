package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.tools.AiClient.ChatRole
import com.wasimaster.wmkeyboard.core.tools.AiClient.ChatTurn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The chat screen sends whole conversations; each provider spells the message
 * array differently. These pin the request bodies, and that the single-turn
 * wrapper still produces exactly what the keyboard's AI actions always sent.
 */
class AiClientChatBodyTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun config(provider: AiProvider) = AiClient.Config(provider, "k", "test-model", "")

    private val chat = listOf(
        ChatTurn(ChatRole.USER, "What is a monad?"),
        ChatTurn(ChatRole.ASSISTANT, "A monoid in the category of endofunctors."),
        ChatTurn(ChatRole.USER, "In plain words?"),
    )

    @Test
    fun `a temperature goes where each service reads it, and only where it is taken`() {
        fun parse(text: String) = json.parseToJsonElement(text).jsonObject
        val warm = 0.7
        val anthropic = parse(AiClient.anthropicBody(config(AiProvider.ANTHROPIC).copy(temperature = warm), "s", chat, 10))
        assertEquals("0.7", anthropic["temperature"]?.jsonPrimitive?.content)
        val thinking = parse(
            AiClient.anthropicBody(config(AiProvider.ANTHROPIC).copy(temperature = warm, reasoning = true), "s", chat, 10),
        )
        assertNull(thinking["temperature"])
        val compatible = parse(
            AiClient.openAiCompatibleBody(config(AiProvider.OPENAI_COMPATIBLE).copy(temperature = warm), "s", chat, 10),
        )
        assertEquals("0.7", compatible["temperature"]?.jsonPrimitive?.content)
        val o3 = parse(
            AiClient.openAiCompatibleBody(
                AiClient.Config(AiProvider.OPENAI, "k", "o3-mini", "").copy(temperature = warm), "s", chat, 10,
            ),
        )
        assertNull(o3["temperature"])
        val gpt4 = parse(
            AiClient.openAiCompatibleBody(AiClient.Config(AiProvider.OPENAI, "k", "gpt-4.1", "").copy(temperature = warm), "s", chat, 10),
        )
        assertEquals("0.7", gpt4["temperature"]?.jsonPrimitive?.content)
        val gemini = parse(AiClient.geminiBody("s", chat, null, temperature = warm))
        assertEquals("0.7", gemini["generationConfig"]!!.jsonObject["temperature"]?.jsonPrimitive?.content)
        val ollama = parse(AiClient.ollamaBody(config(AiProvider.OLLAMA).copy(temperature = warm), "s", chat, null))
        assertEquals("0.7", ollama["options"]!!.jsonObject["temperature"]?.jsonPrimitive?.content)
        assertNull(parse(AiClient.ollamaBody(config(AiProvider.OLLAMA), "s", chat, null))["options"])
    }

    @Test
    fun `anthropic sends user and assistant roles with system on top`() {
        val body = json.parseToJsonElement(
            AiClient.anthropicBody(config(AiProvider.ANTHROPIC), "sys", chat, 1000),
        ).jsonObject
        assertEquals("sys", body["system"]?.jsonPrimitive?.content)
        val roles = body["messages"]!!.jsonArray.map {
            it.jsonObject["role"]!!.jsonPrimitive.content
        }
        assertEquals(listOf("user", "assistant", "user"), roles)
    }

    @Test
    fun `openai puts the system message first in the array`() {
        val body = json.parseToJsonElement(
            AiClient.openAiCompatibleBody(config(AiProvider.OPENAI), "sys", chat, null),
        ).jsonObject
        val messages = body["messages"]!!.jsonArray
        val roles = messages.map { it.jsonObject["role"]!!.jsonPrimitive.content }
        assertEquals(listOf("system", "user", "assistant", "user"), roles)
        assertEquals("sys", messages.first().jsonObject["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `gemini calls the assistant role model`() {
        val body = json.parseToJsonElement(
            AiClient.geminiBody("sys", chat, null),
        ).jsonObject
        val roles = body["contents"]!!.jsonArray.map {
            it.jsonObject["role"]!!.jsonPrimitive.content
        }
        assertEquals(listOf("user", "model", "user"), roles)
    }

    @Test
    fun `openai gets max_completion_tokens, other OpenAI-shaped servers max_tokens`() {
        val single = listOf(ChatTurn(ChatRole.USER, "hello"))
        fun body(c: AiClient.Config) = json.parseToJsonElement(
            AiClient.openAiCompatibleBody(c, "sys", single, 300),
        ).jsonObject

        val openAi = body(config(AiProvider.OPENAI))
        assertEquals(300, openAi["max_completion_tokens"]?.jsonPrimitive?.content?.toInt())
        assertNull(openAi["max_tokens"])

        val deepSeek = body(config(AiProvider.DEEPSEEK))
        assertEquals(300, deepSeek["max_tokens"]?.jsonPrimitive?.content?.toInt())
        assertNull(deepSeek["max_completion_tokens"])

        val viaOpenAi = config(AiProvider.OPENAI_COMPATIBLE).copy(baseUrl = "https://api.openai.com/v1")
        assertNull(body(viaOpenAi)["max_tokens"])
        val viaOpenRouter = config(AiProvider.OPENAI_COMPATIBLE).copy(baseUrl = "https://openrouter.ai/api/v1")
        assertNull(body(viaOpenRouter)["max_completion_tokens"])
    }

    @Test
    fun `ollama keeps the system message in the array`() {
        val body = json.parseToJsonElement(
            AiClient.ollamaBody(config(AiProvider.OLLAMA), "sys", chat, null),
        ).jsonObject
        val roles = body["messages"]!!.jsonArray.map {
            it.jsonObject["role"]!!.jsonPrimitive.content
        }
        assertEquals(listOf("system", "user", "assistant", "user"), roles)
    }

    @Test
    fun `a single-turn conversation builds the same bodies as before`() {
        val single = listOf(ChatTurn(ChatRole.USER, "hello"))
        val anthropic = json.parseToJsonElement(
            AiClient.anthropicBody(config(AiProvider.ANTHROPIC), "sys", single, 500),
        ).jsonObject
        assertEquals(1, anthropic["messages"]!!.jsonArray.size)
        assertEquals("sys", anthropic["system"]?.jsonPrimitive?.content)
        assertEquals(500, anthropic["max_tokens"]?.jsonPrimitive?.content?.toInt())

        val openAi = json.parseToJsonElement(
            AiClient.openAiCompatibleBody(config(AiProvider.OPENAI), "sys", single, null),
        ).jsonObject
        assertEquals(2, openAi["messages"]!!.jsonArray.size)
        assertNull(openAi["max_tokens"])

        val gemini = json.parseToJsonElement(
            AiClient.geminiBody("sys", single, null),
        ).jsonObject
        assertEquals(1, gemini["contents"]!!.jsonArray.size)
        assertNull(gemini["generationConfig"])
    }

    @Test
    fun `normalization merges adjacent same-role turns after a dropped failure`() {
        val turns = listOf(
            ChatTurn(ChatRole.USER, "first question"),
            // The failed assistant turn was dropped by the caller, leaving
            // two user turns in a row.
            ChatTurn(ChatRole.USER, "second question"),
        )
        val normalized = AiClient.normalizedTurns(turns)
        assertEquals(1, normalized.size)
        assertEquals("first question\n\nsecond question", normalized.single().text)
    }

    @Test
    fun `normalization drops a leading assistant turn and a trailing one`() {
        val turns = listOf(
            ChatTurn(ChatRole.ASSISTANT, "greeting from a trimmed history"),
            ChatTurn(ChatRole.USER, "question"),
            ChatTurn(ChatRole.ASSISTANT, "answer"),
        )
        val normalized = AiClient.normalizedTurns(turns)
        assertEquals(listOf(ChatRole.USER), normalized.map { it.role })
    }

    @Test
    fun `normalization skips blank messages`() {
        val turns = listOf(
            ChatTurn(ChatRole.USER, "question"),
            ChatTurn(ChatRole.ASSISTANT, "   "),
            ChatTurn(ChatRole.USER, "again"),
        )
        val normalized = AiClient.normalizedTurns(turns)
        assertEquals(1, normalized.size)
        assertEquals("question\n\nagain", normalized.single().text)
    }

    @Test
    fun `the reasoning switch reaches each provider's own field`() {
        fun parse(text: String) = json.parseToJsonElement(text).jsonObject
        val claudeOn = parse(AiClient.anthropicBody(config(AiProvider.ANTHROPIC).copy(reasoning = true), "s", chat, 1000))
        assertEquals("adaptive", claudeOn["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val claudeOff = parse(AiClient.anthropicBody(config(AiProvider.ANTHROPIC).copy(reasoning = false), "s", chat, 1000))
        assertEquals("disabled", claudeOff["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(parse(AiClient.anthropicBody(config(AiProvider.ANTHROPIC), "s", chat, 1000))["thinking"])
        val oldClaude = AiClient.Config(AiProvider.ANTHROPIC, "k", "claude-3-5-haiku-latest", "", reasoning = true)
        assertNull(parse(AiClient.anthropicBody(oldClaude, "s", chat, 1000))["thinking"])

        fun claude(model: String, on: Boolean, maxTokens: Int = 8000) = parse(
            AiClient.anthropicBody(AiClient.Config(AiProvider.ANTHROPIC, "k", model, "", reasoning = on), "s", chat, maxTokens),
        )
        fun JsonObject.thinking(key: String) = this["thinking"]?.jsonObject?.get(key)?.jsonPrimitive?.content
        // Sonnet 5.5 rejects "disabled"; between_tools is its off.
        assertEquals("between_tools", claude("claude-sonnet-5-5", false).thinking("type"))
        assertEquals("summarized", claude("claude-sonnet-5-5", true).thinking("display"))
        // Opus 5.5 and Fable cannot stop thinking: off is the lowest effort.
        for (model in listOf("claude-opus-5-5", "claude-fable-5-1")) {
            val off = claude(model, false)
            assertNull(off["thinking"])
            assertEquals("low", off["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        }
        assertEquals("disabled", claude("claude-opus-5", false).thinking("type"))
        assertEquals("disabled", claude("claude-haiku-5-5", false).thinking("type"))
        assertNull(claude("claude-sonnet-4-6", true).thinking("display"))
        // Before 4.6 there is no adaptive thinking, only a budget below max_tokens.
        val haiku45 = claude("claude-haiku-4-5", true)
        assertEquals("enabled", haiku45.thinking("type"))
        assertEquals("4000", haiku45.thinking("budget_tokens"))
        assertEquals("enabled", claude("claude-sonnet-4-20250514", true).thinking("type"))
        assertNull(claude("claude-haiku-4-5", true, maxTokens = 1024)["thinking"])
        assertNull(claude("claude-3-7-sonnet-latest", false)["thinking"])

        val flashOff = parse(AiClient.geminiBody("s", chat, null, reasoning = false, model = "gemini-2.5-flash"))
        assertEquals(
            0,
            flashOff["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingBudget"]!!.jsonPrimitive.content.toInt(),
        )
        val threeOff = parse(AiClient.geminiBody("s", chat, null, reasoning = false, model = "gemini-3.5-flash"))
        assertEquals(
            "minimal",
            threeOff["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content,
        )
        assertNull(parse(AiClient.geminiBody("s", chat, null))["generationConfig"])

        val ollamaOff = parse(AiClient.ollamaBody(config(AiProvider.OLLAMA).copy(reasoning = false), "s", chat, null))
        assertEquals("false", ollamaOff["think"]!!.jsonPrimitive.content)
        assertNull(parse(AiClient.ollamaBody(config(AiProvider.OLLAMA).copy(reasoning = true), "s", chat, null))["think"])

        val gptOff = AiClient.Config(AiProvider.OPENAI, "k", "gpt-5.6-luna", "", reasoning = false)
        assertEquals("low", parse(AiClient.openAiCompatibleBody(gptOff, "s", chat, null))["reasoning_effort"]!!.jsonPrimitive.content)
        val plainOff = config(AiProvider.OPENAI).copy(reasoning = false)
        assertNull(parse(AiClient.openAiCompatibleBody(plainOff, "s", chat, null))["reasoning_effort"])
    }
}
