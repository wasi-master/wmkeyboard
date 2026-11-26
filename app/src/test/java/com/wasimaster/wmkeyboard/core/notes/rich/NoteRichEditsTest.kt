package com.wasimaster.wmkeyboard.core.notes.rich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteRichEditsTest {

    /** [text] with `|` as the caret, or `[` `]` round a selection. */
    private fun d(text: String): NoteDraft {
        val sel = text.indexOf('[')
        if (sel >= 0) {
            val end = text.indexOf(']') - 1
            return NoteDraft(text.replace("[", "").replace("]", ""), sel, end)
        }
        val at = text.indexOf('|')
        return NoteDraft(text.replace("|", ""), at)
    }

    private fun show(draft: NoteDraft): String =
        if (draft.collapsed) {
            draft.text.substring(0, draft.start) + "|" + draft.text.substring(draft.start)
        } else {
            draft.text.substring(0, draft.start) + "[" + draft.text.substring(draft.start, draft.end) + "]" + draft.text.substring(draft.end)
        }

    /** What typing [typed] at the caret of [before] settles to. */
    private fun type(before: String, typed: String, pending: List<NoteMark> = emptyList()): String {
        val b = d(before)
        val raw = NoteDraft(b.text.substring(0, b.start) + typed + b.text.substring(b.end), b.start + typed.length)
        return show(NoteRichEdits.settle(b, raw, pending).draft)
    }

    private fun backspace(before: String): String {
        val b = d(before)
        val raw = NoteDraft(b.text.removeRange(b.start - 1, b.start), b.start - 1)
        return show(NoteRichEdits.settle(b, raw).draft)
    }

    @Test
    fun plainTypingIsLeftAlone() {
        assertEquals("**bolder|** x", type("**bold|** x", "er"))
        assertEquals("hello|", type("hell|", "o"))
    }

    @Test
    fun typingInsideAMarkerMovesOut() {
        // Between the two stars of a closer: the letter goes inside the bold.
        assertEquals("**boldx|** y", type("**bold*|* y", "x"))
        // At the start of a list line, before its hidden bullet: after it.
        assertEquals("- x|item", type("|- item", "x"))
    }

    @Test
    fun backspaceAfterAMarkerTakesTheLetter() {
        assertEquals("**bol|** x", backspace("**bold**| x"))
    }

    @Test
    fun backspaceAtTheStartOfAnItemTakesTheBulletFirst() {
        assertEquals("one\n|two", backspace("one\n- |two"))
        assertEquals("one\n|two", backspace("one\n|- two"))
    }

    @Test
    fun enterCarriesAListOnAndEndsItWhenEmpty() {
        assertEquals("- a\n- |", type("- a|", "\n"))
        val task = NoteDraft("- [x] a", 7)
        val entered = NoteRichEdits.settle(task, NoteDraft("- [x] a\n", 8)).draft
        assertEquals(NoteDraft("- [x] a\n- [ ] ", 14), entered)
        assertEquals("1. a\n2. |", type("1. a|", "\n"))
        assertEquals("- a\n|", type("- a\n- |", "\n"))
    }

    @Test
    fun pendingMarksWrapWhatIsTypedNext() {
        val out = type("say |", "h", listOf(NoteMark.BOLD))
        assertEquals("say **h|**", out)
        assertEquals("say **hi|**", type(out, "i"))
        val coloured = type("a|", "b", listOf(NoteMark.color("#e53935")))
        assertEquals("a<span style=\"color:#e53935\">b|</span>", coloured)
    }

    @Test
    fun typingOnADividerGoesBelowIt() {
        assertEquals("***\nx|", type("***|", "x"))
    }

    @Test
    fun toggleMarkAddsAndRemoves() {
        val on = NoteRichEdits.toggleMark(d("a [word] b"), NoteMark.BOLD)
        assertEquals("a [**word**] b".replace("[**", "**[").replace("**]", "]**"), show(on))
        val off = NoteRichEdits.toggleMark(on, NoteMark.BOLD)
        assertEquals("a [word] b", show(off))
        val red = NoteRichEdits.toggleMark(d("[x]"), NoteMark.color("#e53935"))
        val blue = NoteRichEdits.toggleMark(red, NoteMark.color("#1e88e5"))
        assertEquals("<span style=\"color:#1e88e5\">x</span>", blue.text)
    }

    @Test
    fun marksAtReadsTheLetterBeforeTheCaret() {
        assertTrue(NoteMark.BOLD in NoteRichEdits.marksAt(d("**bo|ld**")))
        assertFalse(NoteMark.BOLD in NoteRichEdits.marksAt(d("**bold** x|")))
    }

    @Test
    fun setBlockSwapsPrefixes() {
        assertEquals("# |a", show(NoteRichEdits.setBlock(d("|a"), NoteBlockStyle.TITLE)))
        assertEquals("|a", show(NoteRichEdits.setBlock(d("# |a"), NoteBlockStyle.TITLE)))
        assertEquals("- [ ] a|", show(NoteRichEdits.setBlock(d("- a|"), NoteBlockStyle.TODO)))
        assertEquals("1. a\n2. b", NoteRichEdits.setBlock(d("[a\nb]"), NoteBlockStyle.NUMBERED).text)
    }

    @Test
    fun tablesGrowAndShrink() {
        val start = NoteRichEdits.insertBlock(d("x|"), NoteRichEdits.TABLE_TEMPLATE)
        assertEquals("x\n|  |  |\n| --- | --- |\n|  |  |\n", start.text)
        val wider = NoteRichEdits.tableEdit(start, NoteTableOp.ADD_COLUMN)
        assertEquals(3, NoteRichEdits.table(wider.text.substringAfter("x\n").trimEnd())!!.columns)
        val longer = NoteRichEdits.tableEdit(wider, NoteTableOp.ADD_ROW)
        assertEquals(3, NoteRichEdits.table(longer.text.substringAfter("x\n").trimEnd())!!.rows.size)
        val gone = NoteRichEdits.tableEdit(longer, NoteTableOp.DELETE)
        assertEquals("x\n", gone.text)
    }

    @Test
    fun picturesChangeLayout() {
        val two = "![](attachments/aaaaaaaa.jpg) ![](attachments/bbbbbbbb.jpg)"
        val stacked = NoteRichEdits.imagesLayout(two, 0, sideBySide = false)
        assertEquals("![](attachments/aaaaaaaa.jpg)\n![](attachments/bbbbbbbb.jpg)", stacked)
        assertEquals(two, NoteRichEdits.imagesLayout(stacked, 0, sideBySide = true))
        assertEquals("![](attachments/bbbbbbbb.jpg)", NoteRichEdits.removeImage(two, 0, 0))
    }

    @Test
    fun blocksMove() {
        assertEquals("b|\na", show(NoteRichEdits.moveBlock(d("a\nb|"), up = true)))
        assertEquals("b\na|", show(NoteRichEdits.moveBlock(d("a|\nb"), up = false)))
        assertEquals("c\na\nb", NoteRichEdits.reorder("a\nb\nc", listOf(2, 0, 1)))
    }
}
