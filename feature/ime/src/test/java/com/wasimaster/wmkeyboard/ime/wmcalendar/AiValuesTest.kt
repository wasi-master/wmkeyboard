package com.wasimaster.wmkeyboard.ime.wmcalendar

import com.wasimaster.wmkeyboard.core.providers.ProviderCredential
import com.wasimaster.wmkeyboard.core.providers.ProviderEntry
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.settings.ResolvedProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The AI setup as WM Calendar's `KeyboardAi.rows` reads it. */
class AiValuesTest {

    private fun row(typeId: String, key: String = typeId, apiKey: String = "", url: String = "", model: String = "", name: String = ""): ResolvedProvider {
        val entry = ProviderEntry(typeId, credentialId = key.takeIf { it != typeId })
        val credential = ProviderCredential(key, typeId, name = name, apiKey = apiKey, url = url, model = model)
        return ResolvedProvider(entry, ProviderTypes.of(typeId), credential, apiKey, url, model, problem = null)
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** WM Calendar's reading: the AI chain's entries in order, each with its credential, both values JSON strings. */
    private fun read(values: String): List<Map<String, String>> {
        val root = json.parseToJsonElement(values).jsonObject
        fun inner(k: String) = json.parseToJsonElement((root[k] as JsonPrimitive).content)
        val creds = (inner("provider_credentials") as JsonArray).map { it as JsonObject }
        val chain = (inner("provider_chains") as JsonObject)["ai"] as JsonObject
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return (chain["entries"] as? JsonArray).orEmpty().map { e ->
            val o = e as JsonObject
            val id = o.s("credentialId").ifEmpty { o.s("typeId") }
            val c = creds.first { it.s("id") == id }
            mapOf("typeId" to o.s("typeId"), "apiKey" to c.s("apiKey"), "url" to c.s("url"), "model" to c.s("model"), "name" to c.s("name"))
        }
    }

    @Test fun theRowInUseComesFirstWithItsKey() {
        val anthropic = row("anthropic", apiKey = "sk-ant", model = "claude-sonnet-5-5")
        val ollama = row("ollama", key = "ollama:ab12", url = "http://10.0.0.2:11434", model = "llama3", name = "Desk")
        val rows = read(aiValues(listOf(anthropic, ollama), chosen = ollama))
        assertEquals(listOf("ollama", "anthropic"), rows.map { it["typeId"] })
        assertEquals("http://10.0.0.2:11434", rows[0]["url"])
        assertEquals("Desk", rows[0]["name"])
        assertEquals("sk-ant", rows[1]["apiKey"])
        assertEquals("claude-sonnet-5-5", rows[1]["model"])
    }

    @Test fun theModelOnThePhoneStaysHere() {
        val values = aiValues(listOf(row("on_device_ai"), row("openai", apiKey = "sk-1")), chosen = row("on_device_ai"))
        assertEquals(listOf("openai"), read(values).map { it["typeId"] })
        assertFalse("on_device_ai" in values)
    }

    @Test fun nothingSetUpIsAnEmptyList() {
        assertEquals(emptyList<Map<String, String>>(), read(aiValues(emptyList(), chosen = null)))
    }
}
