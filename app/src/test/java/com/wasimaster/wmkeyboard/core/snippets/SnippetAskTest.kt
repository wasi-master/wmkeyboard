package com.wasimaster.wmkeyboard.core.snippets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `{ask:Name}` fill-in fields (competitor gap list item 13, LeanType L3). */
class SnippetAskTest {

    private fun expand(text: String, answers: Map<String, String>? = null, clip: String? = null) =
        SnippetStore.expand(
            text,
            context = SnippetStore.Companion.Context(clipboard = clip, answers = answers),
        )

    @Test fun readsNamesAndDefaultsInOrder() {
        assertEquals(
            listOf(SnippetAsk("Name"), SnippetAsk("Day", "Friday")),
            SnippetStore.asks("Dear {ask:Name}, see you {ask:Day:Friday}."),
        )
    }

    @Test fun theSameNameAsksOnceInAnyCase() {
        val asks = SnippetStore.asks("{ask:Name} and {ask:name:Sam} and {ask:NAME}")
        assertEquals(listOf(SnippetAsk("Name")), asks)
        assertEquals("Ann and Ann and Ann", expand("{ask:Name} and {ask:name:Sam} and {ask:NAME}", mapOf("Name" to "Ann")))
    }

    @Test fun aDefaultMayHoldColons() {
        assertEquals(listOf(SnippetAsk("Time", "9:30")), SnippetStore.asks("At {ask:Time:9:30}"))
        assertEquals("At 9:30", expand("At {ask:Time:9:30}", emptyMap()))
    }

    @Test fun answersFillAndBlankAnswersTakeTheDefault() {
        assertEquals("Hi Ann, from Bo", expand("Hi {ask:Name}, from {ask:Me:Sam}", mapOf("Name" to "Ann", "Me" to "Bo")))
        assertEquals("Hi , from Sam", expand("Hi {ask:Name}, from {ask:Me:Sam}", mapOf("Name" to "", "Me" to "")))
    }

    @Test fun withoutAnswersTheFieldsStayForTheKeyboardToAsk() {
        val text = "Hi {ask:Name}, it is {isodate}"
        val out = expand(text)
        assertTrue(out.startsWith("Hi {ask:Name}, it is "))
        assertFalse(out.contains("{isodate}"))
        assertTrue(SnippetStore.hasAsks(out))
    }

    @Test fun anAnswerIsNeverReadAsAVariable() {
        // Typed text that looks like a token goes in as typed, clipboard and all.
        assertEquals("Note: {clip}", expand("Note: {ask:Text}", mapOf("Text" to "{clip}"), clip = "secret"))
        assertEquals("{ask:Other}", expand("{ask:Text}", mapOf("Text" to "{ask:Other}")))
    }

    @Test fun theOtherTokensAreNotFields() {
        assertTrue(SnippetStore.asks("{random:a|b} {date:HH:mm} {clip:upper} {selection:snake} {place:Home}").isEmpty())
        assertTrue(SnippetStore.asks("{ask:} {ask:   } {ask:line\nbreak}").isEmpty())
    }

    @Test fun aFieldBesideARandomPickStillAsks() {
        val out = expand("{random:Hi|Hello} {ask:Name}")
        assertEquals(listOf(SnippetAsk("Name")), SnippetStore.asks(out))
        assertTrue(out == "Hi {ask:Name}" || out == "Hello {ask:Name}")
    }

    @Test fun fillingKeepsTheCursorWhereTheMarkerPutIt() {
        val expanded = SnippetStore.expandWithCursor("Dear {ask:Name},\n{cursor}\nBest")
        val filled = SnippetStore.fillAsks(expanded, mapOf("Name" to "Ann"))
        assertEquals("Dear Ann,\n\nBest", filled.text)
        assertEquals("Dear Ann,\n".length, filled.cursorOffset)
    }

    @Test fun aShoutedTriggerStillFindsItsFields() {
        val expanded = SnippetStore.expandWithCursor("hi {ask:name}", casing = TriggerCasing.UPPER)
        assertEquals("HI {ASK:NAME}", expanded.text)
        assertEquals(listOf("NAME"), SnippetStore.asks(expanded.text).map { it.name })
        assertEquals("HI ann", SnippetStore.fillAsks(expanded.text, mapOf("name" to "ann")))
    }

    @Test fun aPatternSnippetsFieldsSurviveItsTemplate() {
        val blank = SnippetMatcher.blankTemplate("Thanks $1, {ask:Sign:Sam}")
        assertTrue(blank.text.contains("{ask:Sign:Sam}"))
    }

    @Test fun pastTheCapNothingMoreIsAsked() {
        val text = (1..SnippetStore.MAX_ASKS + 3).joinToString(" ") { "{ask:F$it}" }
        assertEquals(SnippetStore.MAX_ASKS, SnippetStore.asks(text).size)
        // The fields past the cap were never asked, so they are left as typed.
        val filled = SnippetStore.fillAsks(text, emptyMap())
        assertTrue(filled.endsWith("{ask:F${SnippetStore.MAX_ASKS + 3}}"))
    }
}
