package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.settings.AiSettings
import com.wasimaster.wmkeyboard.core.tools.AiClient.ChatRole
import com.wasimaster.wmkeyboard.core.tools.AiClient.ChatTurn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Brave's Answers API is OpenAI-shaped on the wire but takes one user message
 * only, and writes metadata tags into the answer text. Those two differences
 * are what this covers.
 */
class AiClientBraveTest {

    @Test
    fun `the Brave key wins, and the web search key stands in when it is blank`() {
        val own = AiSettings(provider = AiProvider.BRAVE, braveKey = "ai", braveSearchKey = "search")
        assertEquals("ai", AiClient.config(own).apiKey)
        assertEquals("search", AiClient.config(own.copy(braveKey = "")).apiKey)
        assertEquals(AiClient.DefaultModels.BRAVE, AiClient.config(own).model)
    }

    @Test
    fun `either key configures Brave, and neither leaves it unconfigured`() {
        val none = AiSettings(provider = AiProvider.BRAVE)
        assertFalse(AiClient.isConfigured(none))
        assertTrue(AiClient.isConfigured(none.copy(braveKey = "k")))
        assertTrue(AiClient.isConfigured(none.copy(braveSearchKey = "k")))
    }

    @Test
    fun `the search key alone puts Brave in the pickers only once Brave is chosen`() {
        val searchKeyOnly = AiSettings(anthropicKey = "k", braveSearchKey = "k")
        assertEquals(
            listOf(AiProvider.ANTHROPIC),
            AiClient.configuredRemoteProviders(searchKeyOnly),
        )
        assertEquals(
            listOf(AiProvider.ANTHROPIC, AiProvider.BRAVE),
            AiClient.configuredRemoteProviders(searchKeyOnly.copy(provider = AiProvider.BRAVE)),
        )
        assertEquals(
            listOf(AiProvider.ANTHROPIC, AiProvider.BRAVE),
            AiClient.configuredRemoteProviders(searchKeyOnly.copy(braveKey = "ai")),
        )
    }

    @Test
    fun `the body holds exactly one user message and Brave's token field`() {
        val body = Json.parseToJsonElement(
            AiClient.braveBody(
                "Answer briefly.",
                listOf(ChatTurn(ChatRole.USER, "What is WM Keyboard?")),
                maxTokens = 512,
            ),
        ).jsonObject
        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(
            "Answer briefly.\n\nWhat is WM Keyboard?",
            messages[0].jsonObject["content"]!!.jsonPrimitive.content,
        )
        assertEquals(512, body["max_completion_tokens"]!!.jsonPrimitive.int)
        assertNull(body["max_tokens"])
        assertEquals("brave", body["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `provider maximum sends no ceiling`() {
        val body = Json.parseToJsonElement(
            AiClient.braveBody("", listOf(ChatTurn(ChatRole.USER, "hi")), maxTokens = null),
        ).jsonObject
        assertNull(body["max_completion_tokens"])
    }

    @Test
    fun `a chat is folded into a transcript that ends on the latest message`() {
        val prompt = AiClient.foldedPrompt(
            "Be kind.",
            listOf(
                ChatTurn(ChatRole.USER, "Who won?"),
                ChatTurn(ChatRole.ASSISTANT, "Team A."),
                ChatTurn(ChatRole.USER, "By how much?"),
            ),
        )
        assertEquals(
            "Be kind.\n\nConversation so far:\n\nUser: Who won?\n\nAssistant: Team A.\n\n" +
                "Reply to this latest message:\n\nBy how much?",
            prompt,
        )
    }

    @Test
    fun `usage and citation tags are removed even when split across chunks`() {
        val filter = AiClient.BraveTagFilter()
        val out = StringBuilder()
        listOf(
            "The answer", " is 42.<us", "age>{\"X-Request-Total-Cost\":0.01}</us", "age>",
            " See<cit", "ation>{\"number\":1}</citation> more.",
        ).forEach { out.append(filter.feed(it)) }
        out.append(filter.flush())
        assertEquals("The answer is 42. See more.", out.toString())
    }

    @Test
    fun `a less-than sign that is not a tag passes through`() {
        assertEquals("a < b and <b>bold</b>", AiClient.stripBraveTags("a < b and <b>bold</b>"))
        val filter = AiClient.BraveTagFilter()
        // Held back while it could still become <usage>, then released at the end.
        assertEquals("x ", filter.feed("x <us"))
        assertEquals("<us", filter.flush())
    }

    @Test
    fun `an unclosed tag at the end of the stream is dropped`() {
        assertEquals("done", AiClient.stripBraveTags("done<usage>{\"X-Request"))
    }

    @Test
    fun `stream events fold into the answer and report a cut-off`() {
        val buffer = AiClient.StreamBuffer()
        val filter = AiClient.BraveTagFilter()
        AiClient.applyBraveEvent(
            """{"choices":[{"delta":{"content":"Hello<usa"},"index":0}]}""", filter, buffer,
        )
        AiClient.applyBraveEvent(
            """{"choices":[{"delta":{"content":"ge>{}</usage> world"},"index":0,"finish_reason":"length"}]}""",
            filter, buffer,
        )
        val done = buffer.finish()
        assertEquals("Hello world", done.text)
        assertTrue(done.truncated)
    }

    @Test
    fun `Brave's error detail is read as the provider's message`() {
        assertEquals(
            "The provided subscription token is invalid.",
            ToolHttp.apiErrorText(
                """{"type":"ErrorResponse","error":{"status":401,"code":"SUBSCRIPTION_TOKEN_INVALID",""" +
                    """"detail":"The provided subscription token is invalid."}}""",
            ),
        )
    }

    @Test
    fun `research mode sends enable_research and sorts its tags`() {
        val body = Json.parseToJsonElement(
            AiClient.braveBody("", listOf(ChatTurn(ChatRole.USER, "q")), maxTokens = null, research = true),
        ).jsonObject
        assertEquals("true", body["enable_research"]!!.jsonPrimitive.content)
        assertNull(body["enable_citations"])

        val filter = AiClient.BraveTagFilter()
        val answer = StringBuilder()
        val reasoning = StringBuilder()
        listOf(
            "<queries>[\"a\"]</queries><prog", "ress>2 iterations</progress><thinking>pick ",
            "these</thinking><answer>The ans", "wer.<citation>1</citation></answer><usage>{}</usage>",
        ).forEach {
            answer.append(filter.feed(it))
            reasoning.append(filter.takeReasoning())
        }
        answer.append(filter.flush())
        reasoning.append(filter.takeReasoning())
        assertEquals("The answer.", answer.toString())
        assertEquals("2 iterations\npick these\n", reasoning.toString())
    }
}
