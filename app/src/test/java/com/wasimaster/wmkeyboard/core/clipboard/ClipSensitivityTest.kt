package com.wasimaster.wmkeyboard.core.clipboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detector's job is to be quiet. Most of these tests are about what it does
 * *not* claim — a masked card the user has to work around is friction, and
 * friction spread over ordinary clips gets the feature turned off.
 */
class ClipSensitivityTest {

    @Test fun bareCodesAreSensitive() {
        assertTrue(ClipSensitivity.looksSensitive("483920"))
        assertTrue(ClipSensitivity.looksSensitive("G4K7QP"))
        assertTrue(ClipSensitivity.looksSensitive(" 55213 "))
    }

    @Test fun generatedPasswordsAreSensitive() {
        assertTrue(ClipSensitivity.looksSensitive("Tr0ub4dor&3xz"))
        assertTrue(ClipSensitivity.looksSensitive("qX7#mB2pLw9!"))
    }

    @Test fun prosePassesThrough() {
        assertFalse(ClipSensitivity.looksSensitive("Your code is 483920"))
        assertFalse(ClipSensitivity.looksSensitive("see you at 8"))
        assertFalse(ClipSensitivity.looksSensitive(""))
        assertFalse(ClipSensitivity.looksSensitive("   "))
    }

    @Test fun everydayTokensPassThrough() {
        // Long, symbol-rich, and none of them a secret.
        assertFalse(ClipSensitivity.looksSensitive("https://example.com/a/B3?x=1"))
        assertFalse(ClipSensitivity.looksSensitive("someone@example.com"))
        assertFalse(ClipSensitivity.looksSensitive("supercalifragilistic"))
        assertFalse(ClipSensitivity.looksSensitive("kebab-case-file-name"))
    }

    @Test fun yearsAreNotCodes() {
        assertFalse(ClipSensitivity.looksSensitive("2026"))
        assertFalse(ClipSensitivity.looksSensitive("1999"))
        // Four digits outside the year range still read as a PIN.
        assertTrue(ClipSensitivity.looksSensitive("4821"))
    }

    @Test fun shoutedWordsAreNotCodes() {
        assertFalse(ClipSensitivity.looksSensitive("PLEASE"))
        assertFalse(ClipSensitivity.looksSensitive("HTTPS"))
    }

    @Test fun bareNumbersStayInTheHistory() {
        // A number copied out of a game, an order code: kept like any clip.
        assertFalse(ClipSensitivity.looksSecretToKeepOut("483920"))
        assertFalse(ClipSensitivity.looksSecretToKeepOut("12345678"))
        assertFalse(ClipSensitivity.looksSecretToKeepOut("SAVE20"))
        assertTrue(ClipSensitivity.looksSecretToKeepOut("Tr0ub4dor&3xz"))
        assertFalse(ClipSensitivity.looksSecretToKeepOut("Your code is 483920"))
    }
}
