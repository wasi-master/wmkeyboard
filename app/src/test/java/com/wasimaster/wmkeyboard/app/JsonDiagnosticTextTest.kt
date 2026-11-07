package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.layout.json.JsonIssueCode
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element

/** The layout JSON checks and their words, held together: every check has a sentence, and every sentence a check. */
class JsonDiagnosticTextTest {

    private val strings: Map<String, String> by lazy {
        val file = listOf(
            File("src/main/res/values/strings_json_editor.xml"),
            File("app/src/main/res/values/strings_json_editor.xml"),
        ).first { it.isFile }
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        (0 until nodes.length).associate { index ->
            val element = nodes.item(index) as Element
            element.getAttribute("name") to element.textContent.trim()
        }
    }

    private fun key(code: JsonIssueCode) = "json_diag_" + code.name.lowercase()

    @Test
    fun `every code has its own string, named after it`() {
        for (code in JsonIssueCode.entries) {
            assertEquals(code.name, R.string::class.java.getField(key(code)).getInt(null), code.messageRes)
        }
        assertEquals(JsonIssueCode.entries.map(::key).toSet(), strings.keys)
    }

    @Test
    fun `every string asks for exactly the arguments its code fills`() {
        for (code in JsonIssueCode.entries) {
            val text = strings.getValue(key(code))
            val count = when (code) {
                in TWO_ARGUMENTS -> 2
                in NO_ARGUMENTS -> 0
                else -> 1
            }
            assertEquals("${code.name} and %1\$s", count >= 1, "%1\$s" in text)
            assertEquals("${code.name} and %2\$s", count == 2, "%2\$s" in text)
        }
    }

    @Test
    fun `no string uses a dash for a pause`() {
        for ((name, text) in strings) assertFalse(name, '–' in text || '—' in text)
    }

    private companion object {
        val TWO_ARGUMENTS = setOf(
            JsonIssueCode.PROPERTY_TYPO, JsonIssueCode.VALUE_TYPO, JsonIssueCode.ACTION_TYPO, JsonIssueCode.LAYER_TYPO,
            JsonIssueCode.UNKNOWN_VALUE, JsonIssueCode.UNKNOWN_VALUE_REQUIRED, JsonIssueCode.OUT_OF_RANGE,
        )
        val NO_ARGUMENTS = setOf(
            JsonIssueCode.UNCLOSED_STRING, JsonIssueCode.EXPECTED_VALUE, JsonIssueCode.EXPECTED_KEY, JsonIssueCode.EXPECTED_COMMA,
            JsonIssueCode.TRAILING_COMMA, JsonIssueCode.UNCLOSED_OBJECT, JsonIssueCode.UNCLOSED_ARRAY, JsonIssueCode.TRAILING_CONTENT,
            JsonIssueCode.FIELD_OUTSIDE_PANEL,
        )
    }
}
