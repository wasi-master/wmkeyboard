package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchTriggersTest {

    private val triggers = SearchTriggers.DEFAULTS + SearchTrigger("!", SearchTarget.GIF) +
        SearchTrigger("@w", SearchTarget.WEB)

    @Test
    fun `a word shortcut takes the words after a space`() {
        val m = SearchTriggers.match("hello @gif happy birthday", triggers)!!
        assertEquals(SearchTarget.GIF, m.trigger.target)
        assertEquals("happy birthday", m.query)
        assertEquals("@gif happy birthday".length, m.span)
    }

    @Test
    fun `a symbol shortcut needs no space`() {
        val m = SearchTriggers.match("!cats", triggers)!!
        assertEquals("cats", m.query)
        assertEquals(5, m.span)
    }

    @Test
    fun `an address is not a shortcut`() {
        assertNull(SearchTriggers.match("write to me@web.de", triggers))
        assertNull(SearchTriggers.match("really!cats", triggers))
    }

    @Test
    fun `a word shortcut glued to its words is not one`() {
        assertNull(SearchTriggers.match("@gifcats", SearchTriggers.DEFAULTS))
        assertNull(SearchTriggers.match("@gif ", SearchTriggers.DEFAULTS))
    }

    @Test
    fun `the longer of two fitting prefixes wins`() {
        val m = SearchTriggers.match("@wiki tagore", triggers)!!
        assertEquals(SearchTarget.WIKIPEDIA, m.trigger.target)
    }

    @Test
    fun `the list survives a round trip`() {
        assertEquals(triggers, SearchTriggers.decode(SearchTriggers.encode(triggers)))
        assertEquals(emptyList<SearchTrigger>(), SearchTriggers.decode(SearchTriggers.encode(emptyList())))
    }

    @Test
    fun `a plugin keyword offers that plugin with the words`() {
        val ctx = SmartSuggest.Context(pluginKeywords = mapOf("weather" to ("com.example.weather" to "Weather")))
        val hit = SmartSuggest.detect("see @weather dhaka", ctx)!!
        assertEquals(SmartSuggest.Kind.SEARCH, hit.kind)
        assertEquals(ToolPrefill.Plugin("com.example.weather", "Weather", "weather", "dhaka"), hit.prefill)
        assertEquals("@weather dhaka".length, hit.replaceSpan)
        assertNull(SmartSuggest.detect("@weatherdhaka", ctx))
    }
}
