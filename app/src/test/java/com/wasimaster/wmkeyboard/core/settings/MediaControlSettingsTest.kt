package com.wasimaster.wmkeyboard.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaControlSettingsTest {

    @Test
    fun `the pin is on out of the box`() {
        assertTrue(KeyboardSettings().mediaControl.pinWhilePlaying)
    }

    @Test
    fun `the shipped players are what a fresh install ticks`() {
        assertEquals(DefaultMusicApps, KeyboardSettings().mediaControl.musicApps)
        assertTrue("the shipped list is empty", DefaultMusicApps.isNotEmpty())
    }

    @Test
    fun `every shipped entry is a plausible package name`() {
        // A typo here is silent: the app it was meant to name simply never
        // pins anything, and nothing anywhere reports an entry that matches no
        // installed package.
        val malformed = DefaultMusicApps.filterNot {
            it == it.trim() && it.count { c -> c == '.' } >= 1 &&
                it.split('.').all { part -> part.isNotEmpty() } &&
                it.none { c -> c.isWhitespace() }
        }
        assertEquals("entries that cannot be package names", emptyList<String>(), malformed)
    }

    @Test
    fun `no video app is ticked by default`() {
        // The allowlist exists to keep a video, a game or a browser tab from
        // taking a toolbar slot, so the shipped list must not undo that.
        val video = setOf(
            "com.google.android.youtube",
            "com.netflix.mediaclient",
            "com.amazon.avod.thirdpartyclient",
            "com.disney.disneyplus",
        )
        assertTrue(DefaultMusicApps.none { it in video })
    }

    @Test
    fun `an empty choice is kept rather than reseeded`() {
        // "Nothing counts as music" is a legitimate answer, and the settings
        // shape has to be able to hold it — hence a set with a seeded default
        // rather than "empty means everything".
        val none = MediaControlSettings(musicApps = emptySet())
        assertTrue(none.musicApps.isEmpty())
        assertTrue("the switch is independent of the list", none.pinWhilePlaying)
    }
}
