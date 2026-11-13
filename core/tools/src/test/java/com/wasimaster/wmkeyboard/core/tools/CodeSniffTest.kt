package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the clipboard panel draws as code or Markdown. The prose cases matter
 * most: a chat message dressed in monospace and colours is worse than code
 * left plain.
 */
class CodeSniffTest {

    private fun lang(text: String) = (CodeSniff.of(text) as? CodeSniff.Code)?.language

    @Test
    fun jsonObjectsAndArrays() {
        assertEquals("json", lang("""{"name": "WM", "version": 6, "tags": ["a", "b"]}"""))
        assertEquals("json", lang("{\n  \"a\": 1,\n  \"b\": [true, null]\n}"))
        assertEquals("json", lang("""[{"id": 1}, {"id": 2}]"""))
        assertEquals("json", lang("[1, 2, 3]"))
    }

    @Test
    fun bracketedProseIsNotJson() {
        assertNull(CodeSniff.of("[citation needed]"))
        assertNull(CodeSniff.of("{this is a note}"))
    }

    @Test
    fun python() {
        val code = """
            import os
            from pathlib import Path

            def size(path):
                if os.path.isdir(path):
                    return sum(size(p) for p in Path(path).iterdir())
                return os.path.getsize(path)
        """.trimIndent()
        assertEquals("python", lang(code))
        assertEquals("python", lang("class Foo:\n    def __init__(self, x):\n        self.x = x"))
    }

    @Test
    fun shellCommands() {
        assertEquals("sh", lang("adb shell pm grant com.example android.permission.WRITE_SECURE_SETTINGS"))
        assertEquals("sh", lang("pip install --upgrade yt-dlp"))
        assertEquals("sh", lang("git clone https://github.com/user/repo.git"))
        assertEquals("sh", lang("$ sudo apt update && sudo apt upgrade -y"))
    }

    @Test
    fun commandWordsInSentencesStayProse() {
        assertNull(CodeSniff.of("cat is sleeping on the couch again."))
        assertNull(CodeSniff.of("git gud"))
        assertNull(CodeSniff.of("echo chamber"))
    }

    @Test
    fun markup() {
        assertEquals("xml", lang("""<?xml version="1.0"?><manifest package="a"/>"""))
        assertEquals("xml", lang("<LinearLayout android:id=\"@+id/a\">\n  <TextView />\n</LinearLayout>"))
        assertEquals("html", lang("<div class=\"x\"><p>Hi</p></div>"))
        assertNull(CodeSniff.of("<3 love you>"))
    }

    @Test
    fun cLikeLanguages() {
        assertEquals("kotlin", lang("fun main() {\n    val x = listOf(1, 2)\n    println(x)\n}"))
        assertEquals(
            "java",
            lang("public class Main {\n    public static void main(String[] args) {\n        System.out.println(\"hi\");\n    }\n}"),
        )
        assertEquals("javascript", lang("const add = (a, b) => {\n  return a + b;\n};\nconsole.log(add(1, 2));"))
    }

    @Test
    fun sqlAndCss() {
        assertEquals("sql", lang("SELECT name, age FROM users WHERE age > 18 ORDER BY name;"))
        assertEquals("css", lang(".card {\n  color: red;\n  padding: 4px;\n}"))
    }

    @Test
    fun markdown() {
        val md = "# Shopping\n\n- milk\n- **eggs**\n- [bread](https://example.com)"
        assertTrue(CodeSniff.of(md) == CodeSniff.Markdown)
        assertTrue(CodeSniff.of("Some text\n\n```kotlin\nval x = 1\n```\n") == CodeSniff.Markdown)
    }

    @Test
    fun proseStaysProse() {
        assertNull(CodeSniff.of("Hey, are we still on for dinner tonight? Let me know."))
        assertNull(CodeSniff.of("For example:\nbring a jacket\nfrom the car"))
        assertNull(CodeSniff.of("Name: John\nPhone: 555 1234\nEmail: john@example.com"))
        assertNull(CodeSniff.of("- milk\n- eggs\n- bread"))
        assertNull(CodeSniff.of("https://example.com/a?b=c"))
        assertNull(CodeSniff.of("True story"))
        assertNull(CodeSniff.of("Price = 500\nDelivery = 60\nTotal = 560"))
        assertNull(CodeSniff.of("Hi!\nCan you call me (after 5)?\nThanks"))
        assertNull(CodeSniff.of("*Order*\n- 2 shirts\n- 1 cap\n_Thanks_"))
        assertNull(CodeSniff.of("Meeting moved to 5pm (room 3).\nFrom: Alice\nWhere: office"))
    }

    @Test
    fun genericCodeHasNoLanguage() {
        val rust = "fn main() {\n    let v: Vec<i32> = vec![1, 2];\n    for x in &v {\n        println!(\"{}\", x);\n    }\n}"
        assertTrue(CodeSniff.of(rust) is CodeSniff.Code)
    }

    @Test
    fun jsonKeysAreSetApartFromValues() {
        val code = """{"a": "b"}"""
        val kinds = CodeHighlight.tokens(code, "json").map { code.substring(it.start, it.end) to it.kind }
        assertEquals(listOf("\"a\"" to CodeHighlight.Kind.TYPE, "\"b\"" to CodeHighlight.Kind.STRING), kinds)
    }

    @Test
    fun tripleQuotedStringsRunAcrossLines() {
        val code = "x = \"\"\"one\ntwo\"\"\"\ny = 1"
        val string = CodeHighlight.tokens(code, "python").first { it.kind == CodeHighlight.Kind.STRING }
        assertEquals("\"\"\"one\ntwo\"\"\"", code.substring(string.start, string.end))
    }

    @Test
    fun markdownSourceColoursMarksNotWords() {
        val md = "# Title\nif you **can**, see `code`"
        val tokens = CodeHighlight.tokens(md, "markdown").map { md.substring(it.start, it.end) to it.kind }
        assertTrue(("# Title" to CodeHighlight.Kind.KEYWORD) in tokens)
        assertTrue(("**can**" to CodeHighlight.Kind.TYPE) in tokens)
        assertTrue(("`code`" to CodeHighlight.Kind.STRING) in tokens)
        assertTrue(tokens.none { it.first == "if" })
    }
}
