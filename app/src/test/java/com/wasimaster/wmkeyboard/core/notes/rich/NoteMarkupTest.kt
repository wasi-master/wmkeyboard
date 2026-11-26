package com.wasimaster.wmkeyboard.core.notes.rich

import com.wasimaster.wmkeyboard.core.notes.rich.NoteMarkup.LineKind
import com.wasimaster.wmkeyboard.core.notes.rich.NoteMarkup.Range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteMarkupTest {

    private fun shown(source: String): Pair<String, Set<Pair<String, NoteMark>>> {
        val v = NoteMarkup.readInline(source)
        return v.text to v.marks.map { v.text.substring(it.start, it.end) to it.mark }.toSet()
    }

    @Test
    fun markdownMarksAreReadAndHidden() {
        val (text, marks) = shown("a **bold** and _it_ and ~~gone~~ and `code`")
        assertEquals("a bold and it and gone and code", text)
        assertTrue(("bold" to NoteMark.BOLD) in marks)
        assertTrue(("it" to NoteMark.ITALIC) in marks)
        assertTrue(("gone" to NoteMark.STRIKE) in marks)
        assertTrue(("code" to NoteMark.CODE) in marks)
    }

    @Test
    fun wordsWithUnderscoresAndLoneStarsStayText() {
        assertEquals("snake_case_name" to emptySet<Pair<String, NoteMark>>(), shown("snake_case_name"))
        assertEquals("2 * 3 * 4", shown("2 * 3 * 4").first)
        assertEquals("****", shown("****").first)
    }

    @Test
    fun tagSubsetIsRead() {
        val (text, marks) = shown(
            "<span style=\"color:#E53935\">red</span> <mark>hi</mark> <u style=\"text-decoration-style:wavy\">w</u> " +
                "<span style=\"font-size:20px\">big</span> <span style=\"font-family:serif\">s</span>",
        )
        assertEquals("red hi w big s", text)
        assertTrue(("red" to NoteMark(NoteMarkKind.COLOR, "#e53935")) in marks)
        assertTrue(("hi" to NoteMark(NoteMarkKind.HIGHLIGHT, null)) in marks)
        assertTrue(("w" to NoteMark(NoteMarkKind.UNDERLINE, "wavy")) in marks)
        assertTrue(("big" to NoteMark(NoteMarkKind.SIZE, "1.25em")) in marks)
        assertTrue(("s" to NoteMark(NoteMarkKind.FONT, "serif")) in marks)
    }

    @Test
    fun nestedSpansMatchTheirOwnClose() {
        val (text, marks) = shown("<span style=\"color:#e53935\">a<span style=\"font-size:2em\">b</span>c</span>")
        assertEquals("abc", text)
        assertTrue(("abc" to NoteMark(NoteMarkKind.COLOR, "#e53935")) in marks)
        assertTrue(("b" to NoteMark(NoteMarkKind.SIZE, "2em")) in marks)
    }

    @Test
    fun unknownTagsAndImagesAreText() {
        assertEquals("<kbd>x</kbd>", shown("<kbd>x</kbd>").first)
        assertEquals("see ![p](a.png) [x](y)".replace("[x](y)", "x"), shown("see ![p](a.png) [x](y)").first)
    }

    @Test
    fun linesAreClassifiedAndPrefixesHidden() {
        val src = "# Title\n- item\n- [x] done\n1. one\n> said\n***\n![](attachments/abcdef12.jpg)\n| a | b |\n| --- | --- |\n| 1 | 2 |\nplain"
        val read = NoteMarkup.read(src)
        assertEquals(
            listOf(
                LineKind.HEADING, LineKind.BULLET, LineKind.TODO, LineKind.NUMBERED, LineKind.QUOTE, LineKind.DIVIDER,
                LineKind.IMAGES, LineKind.TABLE, LineKind.TABLE, LineKind.TABLE, LineKind.PARAGRAPH,
            ),
            read.lines.map { it.kind },
        )
        assertTrue(read.lines[2].checked)
        assertEquals("Title", NoteMarkup.visible(read, read.lines[0]).text)
        assertEquals("done", NoteMarkup.visible(read, read.lines[2]).text)
    }

    @Test
    fun writerRoundTrips() {
        val cases = listOf(
            "plain" to listOf(Range(0, 5, NoteMark.BOLD)),
            "a b c" to listOf(Range(0, 3, NoteMark.BOLD), Range(2, 5, NoteMark.ITALIC)),
            "word" to listOf(Range(1, 3, NoteMark.ITALIC)),
            "x 2*3 y" to listOf(Range(0, 1, NoteMark.UNDERLINE)),
            "red big" to listOf(Range(0, 7, NoteMark.color("#e53935")), Range(4, 7, NoteMark.size("1.5em"))),
            " lead" to listOf(Range(0, 5, NoteMark.BOLD)),
            "a`b" to listOf(Range(0, 3, NoteMark.CODE)),
            "go here" to listOf(Range(3, 7, NoteMark.link("https://x.org/a b"))),
            "hi" to listOf(Range(0, 2, NoteMark.highlight("#66bb6a")), Range(0, 2, NoteMark.underline(NoteUnderline.DOTTED))),
        )
        for ((text, marks) in cases) {
            val written = NoteMarkup.writeInline(text, marks)
            val back = NoteMarkup.readInline(written)
            assertEquals(written, text, back.text)
            val want = NoteMarkup.normalize(text.length, marks.map {
                if (it.mark.kind == NoteMarkKind.LINK) Range(it.start, it.end, NoteMark.link("https://x.org/a%20b")) else it
            })
            assertEquals(written, want, NoteMarkup.normalize(text.length, back.marks))
        }
    }

    @Test
    fun paragraphThatLooksLikeAListIsEscaped() {
        val written = NoteMarkup.writeParagraph("- not a list", emptyList())
        assertEquals(LineKind.PARAGRAPH, NoteMarkup.read(written).lines[0].kind)
        assertEquals("- not a list", NoteMarkup.visible(NoteMarkup.read(written), NoteMarkup.read(written).lines[0]).text)
    }

    @Test
    fun stripAndBlankKeepWords() {
        val src = "a <span style=\"color:#e53935\">red</span> ![cat](attachments/abcdef12.jpg) b"
        assertEquals("a red cat b", NoteMarkup.strip(src))
        val blank = NoteMarkup.blank(src)
        assertEquals(src.length, blank.length)
        assertEquals(src.indexOf("red"), blank.indexOf("red"))
        val (plain, map) = NoteMarkup.stripMapped(src)
        assertEquals("a red cat b", plain)
        assertEquals(plain.indexOf("red"), map[src.indexOf("red")])
        assertEquals(plain.indexOf("b", 6), map[src.lastIndexOf("b")])
    }
}
