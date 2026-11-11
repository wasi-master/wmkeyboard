package com.wasimaster.wmkeyboard.ime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The capture counters (#1215): counts only, every one of them in the summary. */
class ClipboardDiagnosticsTest {
    @After
    fun reset() = ClipboardDiagnostics.reset()

    @Test
    fun `the summary names every counter, and only counts`() {
        ClipboardDiagnostics.onCaptureAttempt()
        ClipboardDiagnostics.onCaptureAttempt()
        ClipboardDiagnostics.onStoredText()
        ClipboardDiagnostics.onStoredFiles(3)
        val summary = ClipboardDiagnostics.summary()
        val pairs = summary.split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals("2", pairs["capture_attempts"])
        assertEquals("1", pairs["stored_text"])
        assertEquals("3", pairs["stored_file"])
        assertEquals("0", pairs["oversized_image"])
        assertTrue(pairs.values.all { value -> value.all(Char::isDigit) })
    }

    @Test
    fun `reset zeroes everything`() {
        ClipboardDiagnostics.onGateDrop()
        ClipboardDiagnostics.reset()
        assertTrue(ClipboardDiagnostics.summary().split(' ').all { it.endsWith("=0") })
    }
}
