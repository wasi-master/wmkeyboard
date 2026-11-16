package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The keyless search providers' answers, as their APIs gave them on 2026-11-16. */
class KeylessSearchClientsTest {

    @Test
    fun `Wiby's results lose their HTML entities`() {
        val results = WibySearchClient.parse(
            """[{"URL":"http://catb.org/~esr/faqs/tactile-keyboard-faq.html","Title":"Tactile Keyboard FAQ",""" +
                """"Snippet":" Eric S. Raymond &lt; esr@thyrsus.com &gt; (&#34;tab","Description":"x"}]""",
        )
        assertEquals(1, results.size)
        assertEquals("Tactile Keyboard FAQ", results[0].title)
        assertEquals("Eric S. Raymond < esr@thyrsus.com > (\"tab", results[0].snippet)
        assertEquals("catb.org", results[0].displayUrl)
    }

    @Test
    fun `Marginalia's results come from the results array`() {
        val results = MarginaliaSearchClient.parse(
            """{"query":"linear b","license":"CC-BY-NC-SA 4.0","results":[""" +
                """{"url":"https://example.org/linear-b","title":"Linear B","description":"A syllabic script."}]}""",
        )
        assertEquals("https://example.org/linear-b", results.single().url)
        assertEquals("A syllabic script.", results.single().snippet)
    }

    @Test
    fun `Wikipedia's hits become article links without their highlight tags`() {
        val results = WikipediaSearchClient.parse(
            """{"query":{"search":[{"title":"Computer keyboard","snippet":"A <span class=\"searchmatch\">keyboard</span> &amp; more"}]}}""",
            "https://en.wikipedia.org",
        )
        val hit = results.single()
        assertEquals("https://en.wikipedia.org/wiki/Computer_keyboard", hit.url)
        assertEquals("A keyboard & more", hit.snippet)
        assertTrue(hit.displayUrl == "en.wikipedia.org")
    }
}
