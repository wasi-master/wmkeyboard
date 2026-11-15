package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The app chip's matcher (#656). */
class AppNamesTest {

    private val apps = AppNames.of(
        listOf(
            AppNames.App("WhatsApp", "com.whatsapp", "com.whatsapp.Main"),
            AppNames.App("Google Maps", "com.google.android.apps.maps", "Maps"),
            AppNames.App("Café Bazaar", "com.farsitel.bazaar", "Bazaar"),
            AppNames.App("X", "com.twitter.android", "Main"),
            AppNames.App("Notes", "a.notes", "A"),
            AppNames.App("Notes", "b.notes", "B"),
        ),
    )

    @Test
    fun `a whole name in any case matches`() {
        assertEquals("com.whatsapp", apps.match("whatsapp")?.packageName)
        assertEquals("com.google.android.apps.maps", apps.match("Google  maps")?.packageName)
        assertEquals("com.farsitel.bazaar", apps.match("cafe bazaar")?.packageName)
    }

    @Test
    fun `short names and shared names match nothing`() {
        assertNull(apps.match("x"))
        assertNull(apps.match("notes"))
        assertNull(apps.match("whats"))
    }
}
