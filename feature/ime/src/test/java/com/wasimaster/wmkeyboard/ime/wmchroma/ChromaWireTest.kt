package com.wasimaster.wmkeyboard.ime.wmchroma

import org.junit.Assert.assertEquals
import org.junit.Test

class ChromaWireTest {
    /** The same line WM Chroma's ChromaWireTest reads; a change on one side breaks both. */
    @Test fun theWireMatchesTheKeyboards() {
        assertEquals(
            "1|com.wasimaster.wmchroma.keyboard|com.wasimaster.wmchroma|com.wasimaster.wmkeyboard|com.wasimaster.wmchroma.ScreenPickActivity|" +
                "com.wasimaster.suite.extra.PICK_TOKEN|status|describe|palettes|take_pick|state|v|code|text|token|values|" +
                "ok|off|waiting|error|unknown_call|untrusted|not_a_colour|no_pick|failed|400|600000",
            ChromaWire.GOLDEN,
        )
    }
}
