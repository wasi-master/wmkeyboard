package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.settings.AiSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The providers that share OpenAI's request shape differ only in their address
 * and their key, so the thing worth testing is that each one resolves to the
 * address its service actually publishes.
 */
class AiClientProvidersTest {

    private fun url(provider: AiProvider, baseUrl: String = "") =
        AiClient.openAiCompatibleUrl(AiClient.Config(provider, "k", "m", baseUrl))

    @Test
    fun `each OpenAI-shaped provider resolves to its own address`() {
        assertEquals("https://api.openai.com/v1/chat/completions", url(AiProvider.OPENAI))
        assertEquals("https://api.x.ai/v1/chat/completions", url(AiProvider.XAI))
        assertEquals("https://api.deepseek.com/v1/chat/completions", url(AiProvider.DEEPSEEK))
    }

    @Test
    fun `a self-hosted server keeps the version segment the app adds`() {
        assertEquals(
            "http://192.168.0.10:1234/v1/chat/completions",
            url(AiProvider.LM_STUDIO, "http://192.168.0.10:1234"),
        )
    }

    @Test
    fun `the other service supplies its own version segment`() {
        // Services disagree about it (/v1, /openai/v1, /api/v1), so the user
        // gives the whole prefix and the app only adds the endpoint.
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            url(AiProvider.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1"),
        )
        // A trailing slash must not double up.
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            url(AiProvider.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1/"),
        )
    }

    @Test
    fun `the blank-model defaults are what the hints advertise`() {
        // The settings screen renders DefaultModels directly, so a drift here
        // would show the user a model the client never actually requests.
        assertEquals(
            AiClient.DefaultModels.XAI,
            AiClient.config(AiSettings(provider = AiProvider.XAI, xaiKey = "k")).model,
        )
        assertEquals(
            AiClient.DefaultModels.DEEPSEEK,
            AiClient.config(AiSettings(provider = AiProvider.DEEPSEEK, deepSeekKey = "k")).model,
        )
    }

    @Test
    fun `a key configures Grok and DeepSeek`() {
        assertTrue(AiClient.isConfigured(AiSettings(provider = AiProvider.XAI, xaiKey = "k")))
        assertFalse(AiClient.isConfigured(AiSettings(provider = AiProvider.XAI)))
        assertTrue(
            AiClient.isConfigured(AiSettings(provider = AiProvider.DEEPSEEK, deepSeekKey = "k")),
        )
        assertFalse(AiClient.isConfigured(AiSettings(provider = AiProvider.DEEPSEEK)))
    }

    @Test
    fun `the other service needs an address and a model, but not a key`() {
        val noKey = AiSettings(
            provider = AiProvider.OPENAI_COMPATIBLE,
            compatibleUrl = "http://192.168.0.10:8000/v1",
            compatibleModel = "llama-3.3-70b",
        )
        // A gateway on the user's own network often wants no key at all.
        assertTrue(AiClient.isConfigured(noKey))
        assertFalse(AiClient.isConfigured(noKey.copy(compatibleModel = "")))
        assertFalse(AiClient.isConfigured(noKey.copy(compatibleUrl = "")))
    }

    @Test
    fun `the panel picker offers every configured service in display order`() {
        val settings = AiSettings(
            deepSeekKey = "k",
            anthropicKey = "k",
            xaiKey = "k",
        )
        assertEquals(
            listOf(AiProvider.ANTHROPIC, AiProvider.XAI, AiProvider.DEEPSEEK),
            AiClient.configuredRemoteProviders(settings),
        )
    }

    @Test
    fun `Grok and DeepSeek are read as reasoning models`() {
        // They spend the answer's budget on the think block first, so they need
        // the larger ceiling.
        assertTrue(AiClient.expectsReasoning(AiSettings(provider = AiProvider.XAI, xaiKey = "k")))
        assertTrue(
            AiClient.expectsReasoning(AiSettings(provider = AiProvider.DEEPSEEK, deepSeekKey = "k")),
        )
    }

    @Test
    fun `an OpenAI-compatible server's own route replaces chat completions`() {
        assertEquals("https://h/v1/chat/completions", AiClient.routed("https://h/v1/", "", "/chat/completions"))
        assertEquals("https://h/v1/generate", AiClient.routed("https://h/v1", "/generate", "/chat/completions"))
        assertEquals("https://h/v1/generate", AiClient.routed("https://h/v1", "generate", "/chat/completions"))
        assertEquals("https://other/x", AiClient.routed("https://h/v1", " https://other/x ", "/chat/completions"))
    }
}
