package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.settings.AiSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Privatemode in the AI client (#1145): on the phone, or through the user's proxy. */
class AiClientPrivatemodeTest {

    private fun settings(key: String = "", proxy: String = "", model: String = "") = AiSettings(
        provider = AiProvider.PRIVATEMODE,
        privatemodeKey = key,
        privatemodeProxyUrl = proxy,
        privatemodeModel = model,
    )

    @Test
    fun `a key alone or a proxy alone is enough`() {
        assertTrue(AiClient.isConfigured(settings(key = "k")))
        assertTrue(AiClient.isConfigured(settings(proxy = "http://192.168.1.20:8080")))
        assertFalse(AiClient.isConfigured(settings()))
    }

    @Test
    fun `a proxy address is used with or without its v1`() {
        val expected = "http://192.168.1.20:8080/v1/chat/completions"
        for (typed in listOf("http://192.168.1.20:8080", "http://192.168.1.20:8080/", "http://192.168.1.20:8080/v1", "http://192.168.1.20:8080/v1/")) {
            assertEquals(typed, expected, AiClient.openAiCompatibleUrl(AiClient.config(settings(proxy = typed))))
        }
    }

    @Test
    fun `a blank model asks for the default the hint names`() {
        assertEquals(AiClient.DefaultModels.PRIVATEMODE, AiClient.config(settings(key = "k")).model)
        assertEquals("glm-5.3", AiClient.config(settings(key = "k", model = "glm-5.3")).model)
    }

    @Test
    fun `the lock never claims more than what runs`() {
        assertEquals(AiPrivacy.ENCRYPTED, AiClient.privacy(settings(key = "k")))
        assertEquals(AiPrivacy.PROXY, AiClient.privacy(settings(key = "k", proxy = "http://10.0.0.2:8080")))
        assertEquals(AiPrivacy.PLAIN, AiClient.privacy(AiSettings(provider = AiProvider.OPENAI, openAiKey = "k")))
    }

    @Test
    fun `pictures are not sent, so the token estimate needs no image rule`() {
        assertFalse(AiClient.supportsImages(AiProvider.PRIVATEMODE))
    }

    @Test
    fun `it is offered in the pickers once it has a key or a proxy`() {
        assertTrue(AiProvider.PRIVATEMODE in AiClient.configuredRemoteProviders(settings(key = "k")))
        assertFalse(AiProvider.PRIVATEMODE in AiClient.configuredRemoteProviders(settings()))
    }
}
