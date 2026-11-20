package com.wasimaster.wmkeyboard.core.snippets

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The template variables beyond the original date/time/clip set. */
class SnippetVariableTest {

    private val fixedTime: Long = Calendar.getInstance().apply {
        set(2026, Calendar.JULY, 19, 16, 45, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private val context = SnippetStore.Companion.Context(
        clipboard = "copied text",
        appName = "Messages",
        packageName = "com.example.messages",
        selection = "selected bit",
    )

    private fun expand(text: String) =
        SnippetStore.expand(text, now = fixedTime, context = context)

    @Test fun contextVariables() {
        assertEquals("copied text", expand("{clip}"))
        assertEquals("Messages", expand("{app}"))
        assertEquals("com.example.messages", expand("{package}"))
        assertEquals("selected bit", expand("{selection}"))
    }

    @Test fun missingContextExpandsToEmptyNotTheToken() {
        assertEquals("[]", SnippetStore.expand("[{app}]", now = fixedTime))
        assertEquals("[]", SnippetStore.expand("[{selection}]", now = fixedTime))
    }

    @Test fun dateShiftsByCalendarUnits() {
        // fixedTime is 19 July 2026.
        assertEquals("June 2026", expand("{date-1M:MMMM yyyy}"))
        assertEquals("2025", expand("{date-1y:yyyy}"))
        assertEquals("2026-07-26", expand("{date+1w:yyyy-MM-dd}"))
        assertEquals("2026-07-18", expand("{date-1d:yyyy-MM-dd}"))
        assertEquals("2026-07-20", expand("{date+86400:yyyy-MM-dd}"))
    }

    @Test fun dateParts() {
        assertEquals("2026-07-19", expand("{isodate}"))
        assertEquals("Sunday", expand("{weekday}"))
        assertEquals("19", expand("{day}"))
        assertEquals("July", expand("{month}"))
        assertEquals("2026", expand("{year}"))
        assertEquals("16:45", expand("{time}"))
        assertEquals((fixedTime / 1000).toString(), expand("{timestamp}"))
    }

    @Test fun customDatePatternTakesPrecedenceOverPlainDate() {
        assertEquals("Sun 19/07", expand("{date:EEE dd/MM}"))
        // {datetime} must not be eaten by the {date:…} pattern matcher.
        assertEquals("19 Jul 2026 16:45", expand("{datetime}"))
    }

    @Test fun invalidDatePatternDoesNotThrow() {
        assertEquals("", expand("{date:'unterminated}"))
    }

    @Test fun uuidIsWellFormed() {
        val uuid = expand("{uuid}")
        assertTrue(uuid, Regex("[0-9a-f-]{36}").matches(uuid))
        assertNotEquals(uuid, expand("{uuid}"))
    }

    @Test fun cursorMarkerIsStrippedAndReported() {
        val result = SnippetStore.expandWithCursor(
            "Dear {cursor},\n\nRegards", now = fixedTime, context = context,
        )
        assertEquals("Dear ,\n\nRegards", result.text)
        assertEquals("Dear ".length, result.cursorOffset)
        assertFalse(result.text.contains(SnippetStore.CURSOR_MARKER))
    }

    @Test fun cursorDefaultsToEndWhenUnmarked() {
        val result = SnippetStore.expandWithCursor("plain", now = fixedTime)
        assertEquals("plain", result.text)
        assertEquals(5, result.cursorOffset)
    }

    @Test fun unknownTokensAreLeftAlone() {
        assertEquals("{nope} literal", expand("{nope} literal"))
    }

    @Test fun everyDeclaredVariableExpandsToSomethingElse() {
        for (variable in SnippetVariable.entries) {
            val expanded = expand(variable.token)
            assertNotEquals("${variable.token} was not expanded", variable.token, expanded)
        }
    }

    @Test fun legacyClipboardArgumentStillWorks() {
        assertEquals("hi", SnippetStore.expand("{clip}", now = fixedTime, clipboard = "hi"))
    }
}
