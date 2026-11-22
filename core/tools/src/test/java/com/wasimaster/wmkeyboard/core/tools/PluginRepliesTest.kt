package com.wasimaster.wmkeyboard.core.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A plugin provider's reply read back into each list's own types, forgivingly. */
class PluginRepliesTest {

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun `web results from a bare list or an object, bad items left out`() {
        val bare = PluginReplies.webPage(
            json("""[{"title": "A", "url": "https://a.example/x", "snippet": "s"}, {"title": "no url"}, {"url": "javascript:alert(1)"}]"""),
            engine = "DDG",
        )
        assertEquals(1, bare.results.size)
        assertEquals("a.example", bare.results[0].displayUrl)
        assertEquals(listOf("DDG"), bare.results[0].engines)
        val wrapped = PluginReplies.webPage(json("""{"results": [{"url": "https://b.example"}], "answer": "42"}"""), "x")
        assertEquals("https://b.example", wrapped.results[0].title)
        assertEquals("42", wrapped.answer)
    }

    @Test
    fun `images keep their page and guess a mime from the address`() {
        val images = PluginReplies.images(json("""[{"image": "https://i.example/cat.png", "page": "https://p.example/"}]"""))
        assertEquals("image/png", images.single().mime)
        assertEquals("https://i.example/cat.png", images.single().thumbUrl)
        assertEquals("https://p.example/", images.single().contextUrl)
    }

    @Test
    fun `a translation is a string or an object, nothing is null`() {
        assertEquals("Hallo", PluginReplies.translation(json("\"Hallo\""))?.text)
        assertEquals("de", PluginReplies.translation(json("""{"text": "x", "from": "de"}"""))?.detectedSource)
        assertNull(PluginReplies.translation(json("""{"text": "  "}""")))
        assertNull(PluginReplies.translation(json("null")))
    }

    @Test
    fun `dictionary entries take plain definitions and drop empty meanings`() {
        val entries = PluginReplies.dictionary(
            json("""{"phonetic": "/kæt/", "meanings": [{"pos": "noun", "definitions": ["a small cat", {"definition": "a person", "example": "cool cat"}]}, {"pos": "verb", "definitions": []}]}"""),
            asked = "cat",
        )
        val entry = entries.single()
        assertEquals("cat", entry.word)
        assertEquals(1, entry.meanings.size)
        assertEquals("cool cat", entry.meanings[0].definitions[1].example)
    }

    @Test
    fun `synonyms as a bare list or groups`() {
        assertEquals(listOf("calm", "still"), PluginReplies.synonymGroups(json("""["calm", "still"]""")).single().words)
        val groups = PluginReplies.synonymGroups(json("""{"groups": [{"pos": "adj", "words": ["quiet"]}, {"words": []}]}"""))
        assertEquals("adj", groups.single().pos)
    }

    @Test
    fun `fiat rates are turned into units per US dollar`() {
        val rates = PluginReplies.fiatRates(json("""{"base": "EUR", "rates": {"USD": 2.0, "GBP": 1.0}}""")) ?: error("no rates")
        assertEquals("USD", rates.base)
        assertEquals(0.5, rates.rates.getValue("GBP"), 1e-9)
        assertEquals(0.5, rates.rates.getValue("EUR"), 1e-9)
        assertEquals(1.0, rates.rates.getValue("USD"), 1e-9)
        // Against another base with no dollar in it, there is no way to the dollar.
        assertNull(PluginReplies.fiatRates(json("""{"base": "EUR", "rates": {"GBP": 0.8}}""")))
    }

    @Test
    fun `coin prices become coins per dollar, only for the codes asked`() {
        val rates = PluginReplies.cryptoRates(json("""{"BTC": 50000, "DOGE": 0.1}"""), setOf("BTC"))
        assertEquals(setOf("BTC"), rates.keys)
        assertEquals(1.0 / 50000, rates.getValue("BTC"), 1e-12)
    }

    @Test
    fun `media items need a url, and carry the row in their id`() {
        val items = PluginReplies.media(json("""[{"url": "https://g.example/a.gif", "width": 200, "height": 100}, {"title": "x"}]"""), "plugin:p/gif/gif")
        assertEquals(1, items.size)
        assertEquals(2f, items[0].aspectRatio)
        assertEquals(GifSource.PLUGIN, items[0].source)
        assertTrue(items[0].id.startsWith("plugin:p/gif/gif#"))
    }

    @Test
    fun `sound goes out as little-endian 16-bit samples`() {
        val pcm = PluginReplies.pcm16(floatArrayOf(0f, 1f, -1f))
        assertEquals(6, pcm.size)
        assertEquals(0x7F, pcm[3].toInt() and 0xFF)
        assertEquals(0xFF, pcm[2].toInt() and 0xFF)
        assertEquals("hi", PluginReplies.text(json("""{"text": "hi"}""")))
        assertNull(PluginReplies.text(json("[]")))
    }
}
