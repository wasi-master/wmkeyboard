package com.wasimaster.wmkeyboard.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class JsonTextTest {

    @Test
    fun `a stale tail after the closing brace is dropped`() {
        val stale = """{"format":"wmkeyboard-layout","layout":{"rows":[[1,2]]}}longPress":["a"],"x":1}"""
        assertEquals("""{"format":"wmkeyboard-layout","layout":{"rows":[[1,2]]}}""", stale.firstJsonDocument())
    }

    @Test
    fun `a clean document comes back as the same string`() {
        val clean = """{"a":[1,2,{"b":"c"}]}"""
        assertSame(clean, clean.firstJsonDocument())
        val padded = "  $clean\n"
        assertSame(padded, padded.firstJsonDocument())
    }

    @Test
    fun `braces inside strings do not end the document`() {
        val tricky = """{"label":"}","esc":"\"}\\","n":1}garbage}"""
        assertEquals("""{"label":"}","esc":"\"}\\","n":1}""", tricky.firstJsonDocument())
    }

    @Test
    fun `a truncated document is left for the parser to reject`() {
        val cut = """{"a":[1,2"""
        assertSame(cut, cut.firstJsonDocument())
    }

    @Test
    fun `text that is not json is left alone`() {
        val text = "hello } world"
        assertSame(text, text.firstJsonDocument())
        assertSame("", "".firstJsonDocument())
    }

    @Test
    fun `a leading byte order mark is kept`() {
        val bom = "\uFEFF{\"a\":1}tail"
        assertEquals("\uFEFF{\"a\":1}", bom.firstJsonDocument())
    }
}
