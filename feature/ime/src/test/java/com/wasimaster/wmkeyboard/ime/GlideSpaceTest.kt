package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spaces the keyboard types for the user: which marks take one back, and
 * how much text the undo and the tap-to-replace have to remove to take a whole
 * glide commit with them.
 */
class GlideSpaceTest {

    // ---- swallowsAutoSpace ----

    @Test
    fun `sentence enders hug the word`() {
        for (mark in listOf(".", "!", "?", "।")) {
            assertTrue(mark, swallowsAutoSpace(mark))
        }
    }

    @Test
    fun `clause separators hug the word`() {
        for (mark in listOf(",", ";", ":")) {
            assertTrue(mark, swallowsAutoSpace(mark))
        }
    }

    @Test
    fun `closers hug the word`() {
        // "(hello)" — the bracket closes the word, so the space it was given
        // belongs after the bracket, not before it.
        for (mark in listOf(")", "]", "}", "\u201d")) {
            assertTrue(mark, swallowsAutoSpace(mark))
        }
    }

    @Test
    fun `openers keep the space`() {
        // "hello (world)" is what anybody typing this means.
        for (mark in listOf("(", "[", "{")) {
            assertFalse(mark, swallowsAutoSpace(mark))
        }
    }

    @Test
    fun `a straight quote opens on the first press and closes on the second`() {
        // Issue #34: taking the space back both times gave `he said"hello"`.
        assertFalse(swallowsAutoSpace("\"") { "he said " })
        assertTrue(swallowsAutoSpace("\"") { "he said \"hello " })
        assertFalse(swallowsAutoSpace("\"") { "he said \"hello\" and " })
    }

    @Test
    fun `a quote counts over its own line only`() {
        // An unclosed quote in the paragraph above must not flip every quote
        // under it.
        assertFalse(swallowsAutoSpace("\"") { "\"unfinished\nhello " })
        assertTrue(swallowsAutoSpace("\"") { "\"unfinished\n\"hello " })
    }

    @Test
    fun `a quote with no text behind it opens`() {
        // The default context is empty, which is an even count.
        assertFalse(swallowsAutoSpace("\""))
    }

    @Test
    fun `an apostrophe keeps the space`() {
        // It is a letter inside a word ("don't"), so counting its appearances
        // says nothing about which end of a quotation the next one is.
        assertFalse(swallowsAutoSpace("'") { "don't " })
    }

    @Test
    fun `letters and digits keep the space`() {
        assertFalse(swallowsAutoSpace("a"))
        assertFalse(swallowsAutoSpace("7"))
    }

    @Test
    fun `a dash keeps the space`() {
        // "hello - world" is the common intent; "hello- world" is not.
        assertFalse(swallowsAutoSpace("-"))
    }

    @Test
    fun `multi-character text keeps the space`() {
        // Symbol-layer inserts and emoji are not marks that end a word.
        assertFalse(swallowsAutoSpace("..."))
        assertFalse(swallowsAutoSpace("🙂"))
    }

    @Test
    fun `url separators hug the word in a url field`() {
        // "example/" and "my-site", never "example /" (#37).
        for (mark in listOf("/", "#", "&", "=", "@", "-", "_", "+", "~")) {
            assertTrue(mark, swallowsAutoSpace(mark, FieldKind.URI))
        }
    }

    @Test
    fun `url separators keep the space in a text field`() {
        for (mark in listOf("/", "#", "&", "=", "@", "-", "_", "+", "~")) {
            assertFalse(mark, swallowsAutoSpace(mark, FieldKind.TEXT))
        }
    }

    @Test
    fun `prose marks still hug the word in a url field`() {
        assertTrue(swallowsAutoSpace(".", FieldKind.URI))
        assertTrue(swallowsAutoSpace("?", FieldKind.URI))
        assertFalse(swallowsAutoSpace("a", FieldKind.URI))
    }

    // ---- glideCommitLength ----

    @Test
    fun `a word and its trailing space measure together`() {
        assertEquals(6, glideCommitLength("hello ", "hello"))
    }

    @Test
    fun `a word with no trailing space measures alone`() {
        // Handwriting arms the same undo but types no trailing space.
        assertEquals(5, glideCommitLength("hello", "hello"))
    }

    @Test
    fun `text before the word does not count`() {
        // The caller reads word-length-plus-one characters, so a longer field
        // arrives with its head already cut off — here the "y" of "hey hello".
        assertEquals(5, glideCommitLength("yhello", "hello"))
    }

    @Test
    fun `a word the field no longer ends in measures zero`() {
        // The app moved the text on under us — leave the field alone.
        assertEquals(0, glideCommitLength("hello!", "hello"))
        assertEquals(0, glideCommitLength("", "hello"))
    }

    @Test
    fun `a one-letter word measures with its space`() {
        assertEquals(2, glideCommitLength("a ", "a"))
    }

    @Test
    fun `an empty word measures zero`() {
        // Every string ends with "", so without this guard a blank word would
        // report a length and the caller would delete a character it never
        // committed.
        assertEquals(0, glideCommitLength("hello ", ""))
    }

    @Test
    fun `a second space past the word measures zero`() {
        // The caller's window ends one space past the word, so a field that
        // gained another one no longer matches and the undo stands down rather
        // than guessing which space was the keyboard's.
        assertEquals(0, glideCommitLength("ello  ", "hello"))
    }
}
