package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MwmblSearchClientTest {

    @Test
    fun `joins the runs of a title and an extract`() {
        val body = """
            [{"url": "https://github.com/fretshot/Kboard",
              "title": [{"value": "Custom ", "is_bold": false}, {"value": "Android", "is_bold": true},
                        {"value": " ", "is_bold": false}, {"value": "keyboard", "is_bold": true}],
              "extract": [{"value": " A keyboard for phones…", "is_bold": false}],
              "source": "mwmbl"}]
        """.trimIndent()
        val results = MwmblSearchClient.parseWeb(body)
        assertEquals(1, results.size)
        assertEquals("Custom Android keyboard", results[0].title)
        assertEquals("A keyboard for phones", results[0].snippet)
        assertEquals("github.com", results[0].displayUrl)
    }

    @Test
    fun `a result with no url is dropped and no title falls back to it`() {
        val body = """[{"title": [{"value": "x"}]}, {"url": "https://a.example/p", "title": []}]"""
        val results = MwmblSearchClient.parseWeb(body)
        assertEquals(listOf("https://a.example/p"), results.map { it.title })
    }

    @Test
    fun `an answer that is not a list is no results`() {
        assertTrue(MwmblSearchClient.parseWeb("""{"error": "busy"}""").isEmpty())
    }
}
