package com.wasimaster.wmkeyboard.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The translate panel's language menus: downloaded languages first, and the
 * short list the On device engine opens with (issue #324).
 */
class TranslateLanguageOrderTest {

    private val codes = listOf("en", "bn", "af", "de", "fr", "ml")
    private val ready = setOf("en", "de")

    private fun arrange(keep: Set<String> = emptySet(), first: Boolean, short: Boolean) =
        arrangeTranslateLanguages(codes, { it in ready }, { it in keep }, first, short)

    @Test
    fun `plain order is left alone`() {
        assertEquals(codes, arrange(first = false, short = false))
    }

    @Test
    fun `downloaded first keeps picker order inside each group`() {
        assertEquals(listOf("en", "de", "bn", "af", "fr", "ml"), arrange(first = true, short = false))
    }

    @Test
    fun `short list keeps downloaded, typed and selected languages`() {
        assertEquals(
            listOf("en", "bn", "de", "fr"),
            arrange(keep = setOf("bn", "fr"), first = false, short = true),
        )
    }

    @Test
    fun `short list with downloaded first puts the kept ones after`() {
        assertEquals(
            listOf("en", "de", "bn", "fr"),
            arrange(keep = setOf("bn", "fr"), first = true, short = true),
        )
    }

    @Test
    fun `recent and own languages lead, in their order, then the rest (#1308)`() {
        assertEquals(
            listOf("fr", "ML", "en", "de", "bn", "af").map { it.lowercase() },
            arrangeTranslateLanguages(codes, { it in ready }, { false }, true, false, pinned = listOf("fr", "ML", "xx", "fr")),
        )
    }
}
