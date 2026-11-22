package com.wasimaster.wmkeyboard.core.engine

import com.wasimaster.wmkeyboard.core.text.StemMatch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The engine's search stemmers through the Kotlin facade, on the host build. */
class SearchStemsTest {

    @After fun forget() = SearchStems.clear()

    @Test fun `a text comes back as its stems`() {
        assertEquals("run late two chat", SearchStems.stem("en", "Running late, two Chats!"))
        assertEquals("", SearchStems.stem("en", "   "))
    }

    @Test fun `other forms of a word meet in each language`() {
        for ((tag, forms) in listOf(
            "en" to listOf("run", "running", "runs"),
            "en" to listOf("chat", "chats", "chatted"),
            "fr" to listOf("maison", "maisons"),
            "de" to listOf("Haus", "Häuser"),
            "es" to listOf("perro", "perros"),
            "ru" to listOf("книга", "книги", "книгами"),
            "hi" to listOf("लडका", "लडके", "लडकों"),
            "bn" to listOf("বই", "বইগুলো"),
            "id" to listOf("baca", "membaca", "pembaca"),
        )) {
            val stems = forms.map { SearchStems.stem(tag, it) }
            assertEquals("$tag $forms", 1, stems.toSet().size)
        }
    }

    @Test fun `words outside the language's script go by their own`() {
        // English words in a Bengali clip, with the keyboard on Bengali.
        assertEquals(SearchStems.stem("en", "meetings"), SearchStems.stem("bn", "meetings"))
        assertEquals(SearchStems.stem("ru", "книгами"), SearchStems.stem(null, "книгами"))
    }

    @Test fun `a batch is one stem per text, cached for the next search`() {
        val texts = listOf("Running late", "", "two chats")
        val stems = SearchStems.stemAll("en", texts)
        assertEquals(listOf("run late", "", "two chat"), stems)
        assertEquals("run late", SearchStems.cached("en", "Running late"))
    }

    @Test fun `the matcher finds other forms, after what was typed`() {
        val clips = listOf("run to the shop", "Running late", "walk home")
        val stemmed = SearchStems.matcher<String>("en", "running") { it }
        assertNotNull(stemmed)
        val found = StemMatch.literalFirst(clips, literal = { it.contains("running", ignoreCase = true) }, stemmed = stemmed)
        assertEquals(listOf("Running late", "run to the shop"), found)
    }

    @Test fun `a blank query has no stem matcher`() {
        assertTrue(SearchStems.matcher<String>("en", "  ") { it } == null)
    }
}
