package com.wasimaster.wmkeyboard.core.icons

import com.wasimaster.wmkeyboard.core.settings.LauncherToolSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The string side of launcher icon packs (#1275): appfilter components, `s:` sources, stored picks. */
class SystemIconPacksTest {

    @Test
    fun `an appfilter component reads as package and activity`() {
        assertEquals(
            "org.mozilla.firefox/org.mozilla.fenix.HomeActivity",
            SystemIconPacks.parseComponent("ComponentInfo{org.mozilla.firefox/org.mozilla.fenix.HomeActivity}"),
        )
    }

    @Test
    fun `a relative activity is spelled out`() {
        assertEquals("com.example.app/com.example.app.Main", SystemIconPacks.parseComponent("ComponentInfo{com.example.app/.Main}"))
    }

    @Test
    fun `entries that name no app are dropped`() {
        assertNull(SystemIconPacks.parseComponent(":CALENDAR_"))
        assertNull(SystemIconPacks.parseComponent("ComponentInfo{com.example.app/}"))
        assertNull(SystemIconPacks.parseComponent(null))
    }

    @Test
    fun `a source round-trips`() {
        val source = SystemIconPacks.source("com.donnnno.arcticons", "firefox")
        assertEquals("s:com.donnnno.arcticons/firefox", source)
        assertEquals("com.donnnno.arcticons" to "firefox", SystemIconPacks.parseSource(source))
    }

    @Test
    fun `a source that could break the override CSV is refused`() {
        assertNull(SystemIconPacks.parseSource("s:com.pack/a,b"))
        assertNull(SystemIconPacks.parseSource("s:com.pack/a=b"))
        assertNull(SystemIconPacks.parseSource("s:nopackage/icon"))
        assertNull(SystemIconPacks.parseSource("u:0123456789abcdef"))
    }

    @Test
    fun `stored app icon picks round-trip and skip malformed pairs`() {
        val picks = mapOf("org.mozilla.firefox" to "s:com.donnnno.arcticons/firefox")
        assertEquals(picks, LauncherToolSettings.decodeAppIcons(LauncherToolSettings.encodeAppIcons(picks)))
        assertEquals(picks, LauncherToolSettings.decodeAppIcons("broken\torg.mozilla.firefox=s:com.donnnno.arcticons/firefox\t=x"))
    }
}
