package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.tools.MetaSearchClient.Engine
import com.wasimaster.wmkeyboard.core.tools.MetaSearchClient.EngineHits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The built-in metasearch's merge: one result per page, fused ranks, every engine named. */
class MetaSearchClientTest {

    private fun hit(url: String, title: String = url, snippet: String = "") =
        WebResult(title = title, snippet = snippet, url = url, displayUrl = url.substringAfter("://").substringBefore('/'))

    @Test
    fun `the same page from two engines is one result naming both`() {
        val merged = MetaSearchClient.merge(
            listOf(
                EngineHits(Engine.MWMBL, listOf(hit("https://www.example.org/a/", "A", "short"))),
                EngineHits(Engine.MARGINALIA, listOf(hit("http://example.org/a#top", "A page", "a longer snippet"))),
            ),
            count = 10,
        )
        assertEquals(1, merged.size)
        assertEquals("https://www.example.org/a/", merged[0].url)
        assertEquals("a longer snippet", merged[0].snippet)
        assertEquals(listOf("Mwmbl", "Marginalia"), merged[0].engines)
        // Mwmbl ranked it as well and weighs more, so its title stands.
        assertEquals("A", merged[0].title)
    }

    @Test
    fun `agreement outranks one engine's first place`() {
        val merged = MetaSearchClient.merge(
            listOf(
                EngineHits(Engine.MWMBL, listOf(hit("https://solo.example/"), hit("https://both.example/"))),
                EngineHits(Engine.MARGINALIA, listOf(hit("https://other.example/"), hit("https://both.example/"))),
            ),
            count = 10,
        )
        assertEquals("https://both.example/", merged.first().url)
    }

    @Test
    fun `wiby fills gaps rather than leads`() {
        val merged = MetaSearchClient.merge(
            listOf(
                EngineHits(Engine.WIBY, listOf(hit("https://hobby.example/"))),
                EngineHits(Engine.MWMBL, listOf(hit("https://x.example/"), hit("https://y.example/"))),
            ),
            count = 10,
        )
        assertEquals("https://x.example/", merged.first().url)
    }

    @Test
    fun `the list is cut to count, and ties keep the engines' order`() {
        val merged = MetaSearchClient.merge(
            listOf(
                EngineHits(Engine.MWMBL, listOf(hit("https://a.example/"))),
                EngineHits(Engine.WIKIPEDIA, listOf(hit("https://b.example/"))),
            ),
            count = 1,
        )
        assertEquals(listOf("https://a.example/"), merged.map { it.url })
    }

    @Test
    fun `an engine listing one page twice counts it once`() {
        val merged = MetaSearchClient.merge(
            listOf(EngineHits(Engine.MWMBL, listOf(hit("https://a.example/"), hit("https://a.example"), hit("https://b.example/")))),
            count = 10,
        )
        assertEquals(listOf("Mwmbl"), merged[0].engines)
        assertEquals(2, merged.size)
    }

    @Test
    fun `keys fold the differences that do not change the page`() {
        assertEquals(MetaSearchClient.key("https://example.org/a"), MetaSearchClient.key("http://WWW.example.org/a/"))
        assertEquals(
            MetaSearchClient.key("https://en.wikipedia.org/wiki/Cat"),
            MetaSearchClient.key("https://en.m.wikipedia.org/wiki/Cat#Taxonomy"),
        )
        assertEquals(
            MetaSearchClient.key("https://example.org/p?id=3"),
            MetaSearchClient.key("https://example.org/p?utm_source=x&id=3&fbclid=y"),
        )
        // A different query is a different page.
        assertEquals(false, MetaSearchClient.key("https://example.org/p?id=3") == MetaSearchClient.key("https://example.org/p?id=4"))
        assertNull(MetaSearchClient.key("javascript:alert(1)"))
        assertNull(MetaSearchClient.key("https:///nohost"))
    }
}
