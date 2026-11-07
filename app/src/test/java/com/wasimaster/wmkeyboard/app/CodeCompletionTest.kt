package com.wasimaster.wmkeyboard.app

import androidx.compose.ui.text.TextRange
import com.wasimaster.wmkeyboard.core.plugins.PyCompletionItem
import com.wasimaster.wmkeyboard.core.plugins.PyDiagnostic
import com.wasimaster.wmkeyboard.core.plugins.PySeverity
import com.wasimaster.wmkeyboard.core.plugins.PySpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The suggestion list's rules, and the two languages behind it. */
class CodeCompletionTest {

    private val open = CodeCompletions(TextRange(2, 4), listOf(CodeCompletion("abcd", CodeCompletionKind.VARIABLE, "abcd")))

    private fun item(kind: String, insert: String = "x", caret: Int = -1) =
        PyCompletionItem(label = "x", kind = kind, insert = insert, caret = caret, detail = "d", docPath = null)

    @Test
    fun `typing asks for suggestions`() {
        assertEquals(CompletionStep.ASK, completionStep("wm", "wm.", 3, null))
    }

    @Test
    fun `deleting asks again only while a list is open`() {
        assertEquals(CompletionStep.ASK, completionStep("abcd", "abc", 3, open))
        assertEquals(CompletionStep.CLOSE, completionStep("abcd", "abc", 3, null))
    }

    @Test
    fun `moving the caret keeps the list inside its word and closes it outside`() {
        assertEquals(CompletionStep.KEEP, completionStep("abcd", "abcd", 3, open))
        assertEquals(CompletionStep.CLOSE, completionStep("abcd", "abcd", 0, open))
        assertEquals(CompletionStep.CLOSE, completionStep("abcd", "abcd", 3, null))
    }

    @Test
    fun `choosing replaces the word and puts the caret where the item says`() {
        val shown = CodeCompletions(TextRange(5, 8), listOf(CodeCompletion("upper", CodeCompletionKind.FUNCTION, "upper()", 6)))
        val edit = shown.editFor(shown.items.single())
        assertEquals("text.upper()", edit.applyTo("text.upp"))
        assertEquals(TextRange(11), edit.selection)
    }

    @Test
    fun `Python suggests members after a dot`() {
        val found = requireNotNull(PythonCode.completions("wm.", 3, explicit = false)) { "no suggestions: is libwm_python loaded?" }
        assertEquals(TextRange(3, 3), found.replace)
        assertEquals(CodeCompletionKind.FUNCTION, found.items.single { it.label == "log" }.kind)
        assertTrue(found.items.any { it.label == "storage" })
    }

    @Test
    fun `every kind the tooling names has a place in the list`() {
        val kinds = mapOf(
            "variable" to CodeCompletionKind.VARIABLE,
            "parameter" to CodeCompletionKind.VARIABLE,
            "function" to CodeCompletionKind.FUNCTION,
            "method" to CodeCompletionKind.FUNCTION,
            "module" to CodeCompletionKind.MODULE,
            "constant" to CodeCompletionKind.CONSTANT,
            "field" to CodeCompletionKind.FIELD,
            "keyword" to CodeCompletionKind.KEYWORD,
            "snippet" to CodeCompletionKind.SNIPPET,
            "value" to CodeCompletionKind.VALUE,
            "something newer" to CodeCompletionKind.VALUE,
        )
        for ((wire, kind) in kinds) assertEquals(wire, kind, item(wire).toCodeCompletion().kind)
    }

    @Test
    fun `a caret outside the insert lands at its end`() {
        assertEquals(2, item("function", insert = "f()", caret = 2).toCodeCompletion().caret)
        assertEquals(3, item("function", insert = "f()", caret = -1).toCodeCompletion().caret)
        assertEquals(3, item("function", insert = "f()", caret = 9).toCodeCompletion().caret)
        assertEquals("d", item("function").toCodeCompletion().detail)
    }

    @Test
    fun `a diagnostic keeps its place, its weight and its arguments`() {
        val shown = PyDiagnostic("undefined_name", PySeverity.WARNING, PySpan(4, 9), listOf("count", "counts", "extra"))
            .toCodeDiagnostic()
        assertEquals(TextRange(4, 9), shown.range)
        assertEquals(CodeSeverity.WARNING, shown.severity)
        assertEquals("count", shown.arg1)
        assertEquals("counts", shown.arg2)
        assertEquals(pyDiagnosticMessage("undefined_name"), shown.messageRes)
        assertEquals(CodeSeverity.ERROR, PyDiagnostic("x", PySeverity.ERROR, PySpan(0, 1), emptyList()).toCodeDiagnostic().severity)
        assertEquals(CodeSeverity.INFO, PyDiagnostic("x", PySeverity.INFO, PySpan(0, 1), emptyList()).toCodeDiagnostic().severity)
        assertNull(PyDiagnostic("x", PySeverity.INFO, PySpan(0, 1), emptyList()).toCodeDiagnostic().arg1)
    }

    @Test
    fun `the JSON editor suggests nothing`() {
        assertNull(JsonCode.completions("{\"a\": ", 6, explicit = true))
    }
}
