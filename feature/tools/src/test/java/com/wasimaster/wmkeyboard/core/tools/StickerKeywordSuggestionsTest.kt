package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading a model's keyword suggestions for a sticker (#1158). */
class StickerKeywordSuggestionsTest {

    @Test
    fun `a JSON array is read as it is`() {
        assertEquals(
            listOf("happy", "yay", "🎉"),
            StickerKeywordSuggestions.parse("""Sure! ["happy", "yay", "🎉"]"""),
        )
    }

    @Test
    fun `a plain list loses its markers and quotes`() {
        assertEquals(
            listOf("good morning", "sleepy", "coffee"),
            StickerKeywordSuggestions.parse("1. \"good morning\"\n- sleepy\n* coffee"),
        )
    }

    @Test
    fun `reasoning, repeats and the keywords it had are left out`() {
        val answer = "<think>It looks like a cat</think>[\"Cat\", \"cat\", \"meow\", \"hello\"]"
        assertEquals(listOf("Cat", "meow"), StickerKeywordSuggestions.parse(answer, existing = listOf("hello")))
    }

    @Test
    fun `no more than ten, and no sentences`() {
        val many = (1..15).joinToString(",") { "word$it" } + ",this is far too long to be anything a person types for a sticker"
        val parsed = StickerKeywordSuggestions.parse(many)
        assertEquals(StickerKeywordSuggestions.MAX_SUGGESTIONS, parsed.size)
        assertTrue(parsed.none { it.length > 40 })
    }

    @Test
    fun `commas in other scripts split too`() {
        assertEquals(listOf("শুভ সকাল", "ঘুম"), StickerKeywordSuggestions.parse("শুভ সকাল، ঘুম"))
    }

    @Test
    fun `saving keeps the old keywords first and adds each new one once`() {
        assertEquals(
            listOf("hi", "wave", "👋", "hello"),
            StickerKeywordSuggestions.merged(listOf("hi"), listOf("wave", "👋"), listOf("HI", "hello")),
        )
    }

    @Test
    fun `the prompt names the user's languages and their own words`() {
        val request = StickerKeywordSuggestions.Request(
            packName = "Cats",
            title = "Waving cat",
            existing = listOf("hi"),
            languages = listOf("English", "Bengali"),
            withPicture = true,
            instruction = "for my family chat",
        )
        val system = StickerKeywordSuggestions.systemPrompt(request)
        assertTrue("English, Bengali" in system)
        assertTrue("JSON array" in system)
        val user = StickerKeywordSuggestions.userPrompt(request)
        assertTrue("do not repeat them: hi" in user)
        assertTrue("attached picture" in user)
        assertTrue("for my family chat" in user)
        assertFalse("no picture" in user)
        assertTrue("no picture" in StickerKeywordSuggestions.userPrompt(request.copy(withPicture = false)))
    }
}
