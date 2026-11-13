package com.wasimaster.wmkeyboard.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeHighlightBudgetTest {

    /** [lines] lines of "ab cd", each word coloured: two spans a line. */
    private fun document(lines: Int): Pair<AnnotatedString, List<Int>> {
        val text = (0 until lines).joinToString("\n") { "ab cd" }
        val style = SpanStyle(color = Color.Red)
        val starts = (0 until lines).map { it * 6 }
        val spans = starts.flatMap { listOf(AnnotatedString.Range(style, it, it + 2), AnnotatedString.Range(style, it + 3, it + 5)) }
        return AnnotatedString(text, spans) to starts
    }

    @Test
    fun `the budget shrinks as the document grows`() {
        assertEquals(3_000, highlightBudget(100))
        assertEquals(1_000, highlightBudget(1_000))
        assertEquals(593, highlightBudget(1_684))
    }

    @Test
    fun `colours that fit are all kept`() {
        val (full, starts) = document(10)
        assertSame(full, highlightWindow(full, starts, 0, 5, budget = 20))
    }

    @Test
    fun `an over-budget file is coloured around the screen`() {
        val (full, starts) = document(1_000)
        val window = highlightWindow(full, starts, firstLine = 500, lastLine = 520, budget = 100)
        val spans = window.spanStyles
        assertTrue(spans.size <= 100)
        // The screen itself is coloured, and the margin is even above and below.
        val firstLine = spans.first().start / 6
        val lastLine = spans.last().start / 6
        assertTrue(firstLine <= 500 && lastLine >= 520)
        assertEquals(500 - firstLine, lastLine - 520)
        assertEquals(full.text, window.text)
    }

    @Test
    fun `a screen denser than the budget keeps its first colours`() {
        val (full, starts) = document(100)
        val window = highlightWindow(full, starts, firstLine = 10, lastLine = 60, budget = 8)
        assertEquals(8, window.spanStyles.size)
        assertEquals(10 * 6, window.spanStyles.first().start)
    }
}
