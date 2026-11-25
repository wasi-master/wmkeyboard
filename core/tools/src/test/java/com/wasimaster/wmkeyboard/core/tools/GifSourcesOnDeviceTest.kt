package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.tools.GifSource.COMMONS
import com.wasimaster.wmkeyboard.core.tools.GifSource.DYNAMIC
import com.wasimaster.wmkeyboard.core.tools.GifSource.GIPHY
import com.wasimaster.wmkeyboard.core.tools.GifSource.KLIPY
import com.wasimaster.wmkeyboard.core.tools.GifSource.LOCAL
import com.wasimaster.wmkeyboard.core.tools.GifSource.OFFLINE
import com.wasimaster.wmkeyboard.tools.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GIF panel's imported packs ([OFFLINE]) are placed the way the sticker
 * panel's own packs ([LOCAL]) always were: a grid of their own, never
 * interleaved with a provider, and a chip of their own in mixed mode. GIPHY
 * is placed the same way, as its API terms forbid mixing its results with
 * another provider's.
 */
class GifSourcesOnDeviceTest {

    @Test
    fun `picking an on-device source is always its own grid`() {
        assertEquals(listOf(OFFLINE), GifSources.targets(listOf(KLIPY, GIPHY, OFFLINE), OFFLINE, tabs = false))
        assertEquals(listOf(OFFLINE), GifSources.targets(listOf(KLIPY, GIPHY, OFFLINE), OFFLINE, tabs = true))
        assertEquals(listOf(LOCAL), GifSources.targets(listOf(KLIPY, LOCAL), LOCAL, tabs = false))
    }

    @Test
    fun `mixed mode never interleaves GIPHY`() {
        assertEquals(listOf(KLIPY), GifSources.targets(listOf(KLIPY, GIPHY, OFFLINE), KLIPY, tabs = false))
        assertEquals(listOf(GIPHY), GifSources.targets(listOf(KLIPY, GIPHY, OFFLINE), GIPHY, tabs = false))
        assertEquals(listOf(KLIPY, COMMONS), GifSources.targets(listOf(KLIPY, GIPHY, COMMONS), KLIPY, tabs = false))
    }

    @Test
    fun `GIPHY alone is the whole panel in mixed mode`() {
        assertEquals(listOf(GIPHY), GifSources.targets(listOf(GIPHY), KLIPY, tabs = false))
        assertTrue(GifSources.chips(listOf(GIPHY), tabs = false).isEmpty())
    }

    @Test
    fun `offline packs alone are the whole panel`() {
        assertEquals(listOf(OFFLINE), GifSources.targets(listOf(OFFLINE), KLIPY, tabs = false))
        assertEquals(listOf(OFFLINE), GifSources.targets(listOf(OFFLINE), KLIPY, tabs = true))
        assertTrue(GifSources.chips(listOf(OFFLINE), tabs = false).isEmpty())
        assertTrue(GifSources.chips(listOf(OFFLINE), tabs = true).isEmpty())
    }

    @Test
    fun `mixed mode gives GIPHY and the offline packs a chip each`() {
        val chips = GifSources.chips(listOf(KLIPY, GIPHY, OFFLINE), tabs = false)
        assertEquals(listOf(KLIPY, GIPHY, OFFLINE), chips.map { it.source })
        assertEquals(2, GifSources.selectedChip(chips, OFFLINE))
        assertEquals(1, GifSources.selectedChip(chips, GIPHY))
        assertEquals(0, GifSources.selectedChip(chips, KLIPY))
    }

    @Test
    fun `the sticker panel gives GIPHY its own chip`() {
        val chips = GifSources.chips(listOf(KLIPY, GIPHY, LOCAL), tabs = false)
        assertEquals(listOf(KLIPY, GIPHY, LOCAL), chips.map { it.source })
    }

    @Test
    fun `mixed mode names a lone interleaved provider and calls several Online`() {
        val one = GifSources.chips(listOf(KLIPY, GIPHY), tabs = false)
        assertEquals(
            listOf(R.string.core_tools_gif_source_klipy, R.string.core_tools_gif_source_giphy),
            one.map { it.labelRes },
        )
        val several = GifSources.chips(listOf(KLIPY, GIPHY, COMMONS), tabs = false)
        assertEquals(listOf(KLIPY, GIPHY), several.map { it.source })
        assertEquals(R.string.core_tools_gif_source_online, several[0].labelRes)
        assertEquals(0, GifSources.selectedChip(several, COMMONS))
    }

    @Test
    fun `mixed mode with interleaved providers only has no chips`() {
        assertTrue(GifSources.chips(listOf(KLIPY, COMMONS), tabs = false).isEmpty())
    }

    @Test
    fun `only what is on the device counts as on-device`() {
        assertEquals(setOf(LOCAL, OFFLINE, DYNAMIC), GifSource.entries.filter { it.onDevice }.toSet())
    }

    @Test
    fun `an extra chip gives a single provider a chip to switch from`() {
        val chips = GifSources.chips(
            listOf(MediaTarget(LOCAL)),
            com.wasimaster.wmkeyboard.core.providers.ChainMode.TABS,
            extra = listOf(GifSources.dynamicChip("dynamic")),
        )
        assertEquals(listOf(LOCAL, DYNAMIC), chips.map { it.source })
        assertEquals(1, GifSources.selectedChip(chips, "dynamic", listOf(MediaTarget(LOCAL)), com.wasimaster.wmkeyboard.core.providers.ChainMode.TABS))
    }
}
