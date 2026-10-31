package com.wasimaster.wmkeyboard.core.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/** The picker's Palette tab: any tone of a wallpaper palette, and back from a colour. */
class WallpaperShadesTest {

    /** A grey ramp whose channels are the tone in percent, so the arithmetic reads. */
    private val ramp = WallpaperTones.TONES.map { tone -> grey(tone) }

    private fun grey(level: Int): Long = 0xFF000000L or (level.toLong() shl 16) or (level.toLong() shl 8) or level.toLong()

    private val tones = WallpaperTones(ramp, ramp, ramp, ramp, ramp)

    @Test
    fun aPublishedToneIsThatShade() {
        assertEquals(grey(100), WallpaperTones.shade(ramp, 100))
        assertEquals(grey(40), WallpaperTones.shade(ramp, 40))
        assertEquals(grey(0), WallpaperTones.shade(ramp, 0))
    }

    @Test
    fun aToneBetweenTwoIsMixedFromThem() {
        assertEquals(grey(45), WallpaperTones.shade(ramp, 45))
        assertEquals(grey(97), WallpaperTones.shade(ramp, 97))
    }

    @Test
    fun aToneOutOfRangeStopsAtTheEnds() {
        assertEquals(grey(100), WallpaperTones.shade(ramp, 140))
        assertEquals(grey(0), WallpaperTones.shade(ramp, -5))
    }

    @Test
    fun aColourFindsItsPaletteAndTone() {
        val red = WallpaperTones.TONES.map { tone -> 0xFF000000L or (tone.toLong() shl 16) }
        val mixed = tones.copy(tertiary = red)
        assertEquals(2 to 60, mixed.nearestShade(0xFF3C0000L))
    }
}
