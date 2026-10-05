package com.wasimaster.wmkeyboard.core.voice.whistle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhistleLanguagesTest {
    @Test fun mapsSupportedTagsAndRejectsUnsupportedLanguages() {
        assertEquals("de", WhistleLanguages.normalize("DE-de"))
        assertEquals("pl", WhistleLanguages.normalize("pl"))
        assertNull(WhistleLanguages.normalize("ja"))
    }

    @Test fun maximumClipIsThirtySecondsAtSixteenKilohertz() {
        assertEquals(480_000, WhistleLanguages.maxSamples)
    }
}
