package com.wasimaster.wmkeyboard.core.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plugin grammars: rules before comments, strings, numbers and word lists; the time box. */
class PluginSyntaxTest {

    private fun compile(text: String) = PluginSyntax.compile(Json.parseToJsonElement(text) as JsonObject)

    private fun spans(code: String, grammar: PluginSyntax.Grammar) =
        PluginSyntax.tokens(code, grammar).map { code.substring(it.start, it.end) to it.kind }

    private val toml = compile(
        """{"rules": [{"match": "^\\[[^\\]\\n]*\\]", "kind": "heading", "flags": "m"},
                       {"match": "^[A-Za-z0-9_.-]+(?=\\s*=)", "kind": "attribute", "flags": "m"}],
            "lineComment": "#", "strings": ["\"\"\"", "\"", "'"], "constants": ["true", "false"]}""",
    )

    @Test
    fun `rules, comments, strings, numbers and constants`() {
        val code = "[server]\nport = 8080 # main\nname = \"a # b\"\non = true\n"
        assertEquals(
            listOf(
                "[server]" to PluginSyntax.Kind.HEADING,
                "port" to PluginSyntax.Kind.ATTRIBUTE,
                "8080" to PluginSyntax.Kind.NUMBER,
                "# main" to PluginSyntax.Kind.COMMENT,
                "name" to PluginSyntax.Kind.ATTRIBUTE,
                "\"a # b\"" to PluginSyntax.Kind.STRING,
                "on" to PluginSyntax.Kind.ATTRIBUTE,
                "true" to PluginSyntax.Kind.CONSTANT,
            ),
            spans(code, toml),
        )
    }

    @Test
    fun `triple quotes run across lines and a single quote stops at the line's end`() {
        val code = "a = \"\"\"x\ny\"\"\"\nb = 'open\nc = 1"
        val strings = spans(code, toml).filter { it.second == PluginSyntax.Kind.STRING }.map { it.first }
        assertEquals(listOf("\"\"\"x\ny\"\"\"", "'open"), strings)
    }

    @Test
    fun `keywords, block comments, calls and case`() {
        val g = compile("""{"keywords": ["SELECT", "FROM"], "blockComment": ["/*", "*/"], "calls": true, "caseInsensitive": true}""")
        val code = "select count(*) /* all */ From t"
        assertEquals(
            listOf("select" to PluginSyntax.Kind.KEYWORD, "count" to PluginSyntax.Kind.FUNCTION, "/* all */" to PluginSyntax.Kind.COMMENT, "From" to PluginSyntax.Kind.KEYWORD),
            spans(code, g),
        )
    }

    @Test
    fun `a broken grammar names its problem`() {
        assertNotNull(PluginSyntax.problem(Json.parseToJsonElement("""{"rules": [{"match": "(", "kind": "keyword"}]}""") as JsonObject))
        assertNotNull(PluginSyntax.problem(Json.parseToJsonElement("""{"rules": [{"match": "a"}]}""") as JsonObject))
        assertNull(PluginSyntax.problem(Json.parseToJsonElement("""{"keywords": ["a"]}""") as JsonObject))
    }

    @Test
    fun `colouring stops at its time box and leaves the rest plain`() {
        val slow = compile("""{"rules": [{"match": "(a+)+b", "kind": "keyword"}], "keywords": ["z"]}""")
        val code = "a".repeat(30) + " z".repeat(10_000)
        val started = System.nanoTime()
        val tokens = PluginSyntax.tokens(code, slow, budgetMillis = 20)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
        assertTrue(tokens.size < 10_000)
    }
}
