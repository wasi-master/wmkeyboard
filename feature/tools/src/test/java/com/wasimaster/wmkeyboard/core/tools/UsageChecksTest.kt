package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.settings.AiProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the services report about usage (#1135), read from their documented answers. */
class UsageChecksTest {

    @Test
    fun `Tavily's key limit wins over the plan's`() {
        val keyed = UsageChecks.parseTavily(
            """{"key":{"usage":150,"limit":1000},"account":{"current_plan":"Bootstrap","plan_usage":500,"plan_limit":15000}}""",
            0,
        )!!
        assertEquals(850.0, keyed.remaining!!, 0.0)
        assertEquals(1000.0, keyed.limit!!, 0.0)
        assertEquals("Bootstrap", keyed.plan)
        val plan = UsageChecks.parseTavily(
            """{"key":{"usage":150,"limit":null},"account":{"current_plan":"Researcher","plan_usage":200,"plan_limit":1000}}""",
            0,
        )!!
        assertEquals(800.0, plan.remaining!!, 0.0)
        assertEquals(ServiceUsage.Window.MONTH, plan.window)
    }

    @Test
    fun `DeepSeek's balance keeps its currency`() {
        val reported = UsageChecks.parseDeepSeek(
            """{"is_available":true,"balance_infos":[{"currency":"USD","total_balance":"110.00","granted_balance":"10.00","topped_up_balance":"100.00"}]}""",
            0,
        )!!
        assertEquals(110.0, reported.remaining!!, 0.0)
        assertEquals("USD", reported.currency)
        assertEquals(ServiceUsage.Measure.MONEY, reported.unit)
    }

    @Test
    fun `an OpenRouter key without a limit still says what it spent`() {
        val reported = UsageChecks.parseOpenRouter("""{"data":{"label":"k","usage":1.25,"limit":null,"limit_remaining":null}}""", 0)!!
        assertNull(reported.remaining)
        assertEquals(1.25, reported.used!!, 0.0)
    }

    @Test
    fun `only services with an account endpoint offer the check`() {
        assertTrue(UsageChecks.canCheck("tavily", ""))
        assertTrue(UsageChecks.canCheck("deepseek", ""))
        assertTrue(UsageChecks.canCheck("openai_compatible", "https://openrouter.ai/api/v1"))
        assertFalse(UsageChecks.canCheck("openai_compatible", "https://api.groq.com/openai/v1"))
        assertFalse(UsageChecks.canCheck("anthropic", ""))
    }

    @Test
    fun `token totals are read from each provider's stream`() {
        val anthropic = AiClient.StreamBuffer()
        AiClient.applyAnthropicEvent("""{"type":"message_start","message":{"usage":{"input_tokens":25,"output_tokens":1}}}""", anthropic)
        AiClient.applyAnthropicEvent("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":15}}""", anthropic)
        assertEquals(25L, anthropic.inputTokens)
        assertEquals(15L, anthropic.outputTokens)

        val openAi = AiClient.StreamBuffer()
        AiClient.applyOpenAiEvent("""{"choices":[],"usage":{"prompt_tokens":9,"completion_tokens":12,"total_tokens":21}}""", openAi)
        assertEquals(9L, openAi.inputTokens)
        assertEquals(12L, openAi.outputTokens)

        val gemini = AiClient.StreamBuffer()
        AiClient.applyGeminiEvent(
            """{"candidates":[{"content":{"parts":[{"text":"hi"}]}}],"usageMetadata":{"promptTokenCount":4,"candidatesTokenCount":2,"thoughtsTokenCount":3}}""",
            gemini,
        )
        assertEquals(4L, gemini.inputTokens)
        assertEquals(5L, gemini.outputTokens)

        val ollama = AiClient.StreamBuffer()
        AiClient.applyOllamaEvent("""{"done":true,"prompt_eval_count":30,"eval_count":7}""", ollama)
        assertEquals(30L, ollama.inputTokens)
        assertEquals(7L, ollama.outputTokens)
    }

    @Test
    fun `usage is asked for only where the service takes the field`() {
        val chat = listOf(AiClient.ChatTurn(AiClient.ChatRole.USER, "hi"))
        fun body(provider: AiProvider) =
            Json.parseToJsonElement(AiClient.openAiCompatibleBody(AiClient.Config(provider, "k", "m", "https://x.example/v1"), "s", chat, null)).jsonObject
        assertTrue("stream_options" in body(AiProvider.OPENAI))
        assertFalse("stream_options" in body(AiProvider.OPENAI_COMPATIBLE))
    }

    @Test
    fun `Tavily's advanced depth costs two credits`() {
        assertEquals(2L, TavilySearchClient.credits("advanced"))
        assertEquals(1L, TavilySearchClient.credits("basic"))
    }
}
