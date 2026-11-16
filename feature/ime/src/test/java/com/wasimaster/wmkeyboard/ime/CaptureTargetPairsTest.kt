package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which of the keyboard's own boxes close brackets and quotes (#612). */
class CaptureTargetPairsTest {

    @Test
    fun theClipEditorCombineAndFindAndReplaceClosePairs() {
        listOf(
            CaptureTarget.CLIP_EDIT, CaptureTarget.CLIP_JOINER, CaptureTarget.CLIP_BASE,
            CaptureTarget.CLIP_APPEND, CaptureTarget.FIND_QUERY, CaptureTarget.FIND_REPLACEMENT,
            CaptureTarget.NOTE_BODY,
        ).forEach { assertTrue(it.name, it.closesPairs) }
    }

    @Test
    fun searchesNumbersAndTheLiveComputerLineDoNot() {
        listOf(
            CaptureTarget.CLIPBOARD_SEARCH, CaptureTarget.EMOJI_SEARCH, CaptureTarget.CALC,
            CaptureTarget.CONVERTER, CaptureTarget.KDE_REMOTE, CaptureTarget.WORD_SPELL,
            CaptureTarget.SEND_PASSWORD, CaptureTarget.TYPING_TEST,
        ).forEach { assertFalse(it.name, it.closesPairs) }
    }
}
