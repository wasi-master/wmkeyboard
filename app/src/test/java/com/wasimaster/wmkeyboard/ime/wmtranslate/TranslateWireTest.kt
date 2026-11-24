package com.wasimaster.wmkeyboard.ime.wmtranslate

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslateWireTest {
    /** The same line WM Translate's TranslateWireTest reads; a change on one side breaks both. */
    @Test fun theWireMatchesWmTranslates() {
        assertEquals(
            "1|com.wasimaster.wmtranslate.keyboard|com.wasimaster.wmkeyboard.translate|com.wasimaster.wmtranslate|com.wasimaster.wmkeyboard|" +
                "status|translate|cancel|save|config|models|rows|run|" +
                "state|v|error|code|id|text|from|to|result|detected|provider|net|incognito|origin|hops|values|share|ready|path|row|" +
                "ok|off|boot|allow|deny|none|once|follow|" +
                "unknown_call|failed|loop|no_text|too_long|not_shared|no_row|10000",
            TranslateWire.GOLDEN,
        )
    }
}
