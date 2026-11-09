package com.wasimaster.wmkeyboard.core.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePunctuationTest {

    private fun en(text: String) = VoicePunctuation.apply(text, "en-US")

    @Test
    fun `sentence marks close up to the word before them`() {
        assertEquals("hello, world.", en("hello comma world period"))
        assertEquals("really?", en("really question mark"))
        assertEquals("wait…", en("wait dot dot dot"))
    }

    @Test
    fun `a line break takes no space on either side`() {
        assertEquals("one\ntwo\n\nthree", en("one new line two new paragraph three"))
    }

    @Test
    fun `an opening mark closes up to the word after it`() {
        assertEquals("see (page two) now", en("see open parenthesis page two close parenthesis now"))
        assertEquals("she said \"hi\" twice", en("she said open quote hi close quote twice"))
        assertEquals("so #blessed", en("so hashtag blessed"))
        assertEquals("about \$5", en("about dollar sign 5"))
    }

    @Test
    fun `a joining mark closes up on both sides`() {
        assertEquals("well-known", en("well hyphen known"))
        assertEquals("and/or", en("and slash or"))
        assertEquals("me@example", en("me at sign example"))
        assertEquals("snake_case", en("snake underscore case"))
    }

    @Test
    fun `an operator stands apart from both neighbours`() {
        assertEquals("salt & pepper", en("salt ampersand pepper"))
        assertEquals("two + two = four", en("two plus sign two equals sign four"))
    }

    @Test
    fun `an everyday word is not a symbol without its sign`() {
        assertEquals("fifty percent is greater than ten plus one", en("fifty percent is greater than ten plus one"))
        assertEquals("50%", en("50 percent sign"))
    }

    @Test
    fun `matching ignores case`() {
        assertEquals("Hello, world", en("Hello Comma world"))
    }

    @Test
    fun `a name the recognizer punctuated still types its mark`() {
        // A recognizer that punctuates on its own writes "Period." and "line,".
        assertEquals("done. And then", en("done Period. And then"))
        assertEquals("one\ntwo", en("one new line, two"))
        assertEquals("see (this) now", en("see open parenthesis, this close parenthesis. now"))
    }

    @Test
    fun `a spoken mark replaces the recognizer's guess before it`() {
        assertEquals("hello, world", en("hello, comma world"))
        assertEquals("really?", en("really. question mark"))
        // Not a line break, which keeps the full stop in front of it.
        assertEquals("done.\nnext", en("done. new line next"))
    }

    @Test
    fun `pairs answer to the names people use for them`() {
        assertEquals("she said \"hi\" twice", en("she said begin quote hi end quote twice"))
        assertEquals("he said \"no\"", en("he said open quotes no unquote"))
        assertEquals("see (page two)", en("see left paren page two right paren"))
        assertEquals("see (page two)", en("see open parentheses page two end parentheses"))
        assertEquals("[x]", en("open square bracket x close square bracket"))
        assertEquals("{x}", en("left curly brace x end curly brace"))
        // A bare "quote" or "brackets" is a word.
        assertEquals("a quote in brackets", en("a quote in brackets"))
    }

    @Test
    fun `Bangla keeps its own table`() {
        assertEquals("আমি ভালো।", VoicePunctuation.apply("আমি ভালো দাঁড়ি", "bn-BD"))
        // English names mean nothing in a Bangla dictation.
        assertEquals("আমি comma", VoicePunctuation.apply("আমি comma", "bn-BD"))
    }

    @Test
    fun `Portuguese and Spanish have their own names`() {
        assertEquals("Oi, tudo bem?", VoicePunctuation.apply("Oi vírgula tudo bem ponto de interrogação", "pt-BR"))
        assertEquals("sim; não.", VoicePunctuation.apply("sim ponto e vírgula não ponto final", "pt-BR"))
        // A bare "ponto" is a word.
        assertEquals("ponto de ônibus", VoicePunctuation.apply("ponto de ônibus", "pt-BR"))
        assertEquals("Hola, amigo!", VoicePunctuation.apply("Hola coma amigo signo de exclamación", "es-ES"))
    }

    @Test
    fun `a language without a table is left exactly as heard`() {
        assertEquals("hallo comma  welt", VoicePunctuation.apply("hallo comma  welt", "de-DE"))
    }
}
