package com.wasimaster.wmkeyboard.core.plugins.python

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of PyMini's tests/StringLiteralTests.swift; expected values unchanged. */
class StringLiteralTest {

    @Test
    fun `triple quoted strings`() {
        for (quote in listOf("\"", "'")) {
            val delimiter = quote.repeat(3)
            val bodies = listOf(
                "You are a text correction tool.\nThis is a multiline prompt.\nPlease return only the corrected text.\n",
                "\n  indented\n\n# literal comment, brackets: ([{\n\tend",
                "one $quote and two $quote$quote quotes\nnext",
                "", "single line", "Unicode: 🐍 café\n日本語",
            )
            for (body in bodies) {
                val source = "PROMPT = $delimiter$body$delimiter\ndef transform(text):\n    return PROMPT\n"
                PyRuntime.check(source)
                assertEquals(body, output(source))
            }
        }
    }

    @Test
    fun `indentation inside triple quotes is kept`() {
        val source = "def transform(text):\n    prompt = \"\"\"first\nno indentation\n    last\"\"\"\n    return prompt + text\n"
        assertEquals("first\nno indentation\n    last!", output(source, input = "!"))
    }

    @Test
    fun `escapes and line joins`() {
        for (quote in listOf("\"", "'")) {
            val delimiter = quote.repeat(3)
            val literal = delimiter + "first\\\nsecond\\n\\t\\\\\\" + quote + "end" + delimiter
            assertEquals("firstsecond\n\t\\" + quote + "end", output("result = $literal"))
        }
    }

    @Test
    fun `multiline f-strings`() {
        for (prefix in listOf("f", "F")) {
            val source = "def transform(text):\n    return $prefix\"\"\"Hello {text}\n{{literal}}\"\"\""
            assertEquals("Hello world\n{literal}", output(source, input = "world"))
        }
    }

    @Test
    fun `unterminated strings report the opening line`() {
        for (ending in listOf("", "\"", "\"\"")) {
            val error = pyError { PyRuntime.check("result = \"\"\"first\nlast$ending") }
            assertEquals("Line 1: unterminated string", error.toString())
        }
        pyError { PyRuntime.check("result = \"first\nlast\"") }
    }

    @Test
    fun `lines after a multiline string stay correct`() {
        val error = pyError { PyRuntime.check("result = \"\"\"first\nlast\"\"\"\n@") }
        assertTrue(error.toString(), error.toString().startsWith("Line 3:"))
    }

    @Test
    fun `newlines are normalised`() {
        for (newline in listOf("\r\n", "\r")) {
            assertEquals("first\nlast", output("result = \"\"\"first${newline}last\"\"\""))
        }
        assertEquals("ordinary\nstring", output("result = 'ordinary\\nstring'"))
    }
}
