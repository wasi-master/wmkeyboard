package com.wasimaster.wmkeyboard.core.plugins.python

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Real Clink keyboard actions (`.clinkext` JSON whose `source` defines
 * `transform(text)`), run through the port. Expected outputs were checked
 * against PyMini 2.0.1 itself.
 */
class ClinkActionsTest {

    private fun transform(fixture: String, input: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream("clink/$fixture.clinkext")
        assertNotNull("missing fixture $fixture", stream)
        val json = stream!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val py = PyRuntime()
        py.load(sourceOf(json))
        return py.call("transform", input).stringValue!!
    }

    /** The `source` string of a fixture: a tiny JSON string reader, enough for these files. */
    private fun sourceOf(json: String): String {
        val key = "\"source\":\""
        var i = json.indexOf(key)
        check(i >= 0) { "no source field" }
        i += key.length
        val out = StringBuilder()
        while (json[i] != '"') {
            val c = json[i]
            if (c == '\\') {
                i += 1
                when (val e = json[i]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'u' -> { out.append(json.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                    else -> out.append(e)
                }
            } else {
                out.append(c)
            }
            i += 1
        }
        return out.toString()
    }

    @Test
    fun `reverse keeps grapheme clusters whole`() {
        assertEquals("👍🏽 olléh", transform("reverse", "héllo 👍🏽"))
        // A decomposed é (e + U+0301) and a skin-toned emoji each stay one character.
        assertEquals("👍🏽 olléh", transform("reverse", "héllo 👍🏽"))
    }

    @Test
    fun `shrug keeps its backslash`() {
        assertEquals("¯\\_(ツ)_/¯", transform("shrug", ""))
    }

    @Test
    fun `snake case`() {
        assertEquals("hello_world", transform("snake-case", "  Hello World "))
    }

    @Test
    fun `title case`() {
        assertEquals("Hello World", transform("title-case", "hello world"))
    }

    @Test
    fun `uppercase uses full case mapping`() {
        assertEquals("STRASSE", transform("uppercase", "straße"))
    }

    @Test
    fun `word count`() {
        assertEquals("3 words", transform("word-count", "one two  three"))
    }

    @Test
    fun `fix period spaces`() {
        assertEquals("word word end.", transform("fix-period-spaces", "word.word end."))
    }

    @Test
    fun `fix punctuation spaces`() {
        assertEquals("a b c", transform("fix-punctuation-spaces", "a,b.c"))
    }
}
