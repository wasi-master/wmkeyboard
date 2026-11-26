package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.AppUiSettings
import com.wasimaster.wmkeyboard.core.settings.Extension
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.PersonaDepth
import com.wasimaster.wmkeyboard.core.settings.SettingsDepth
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.allows
import com.wasimaster.wmkeyboard.core.settings.extensionsResolved
import com.wasimaster.wmkeyboard.core.settings.settingsDepth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Extensions (SETTINGS_REDESIGN.md §3): the far-fetched feature bundles,
 * what each owns, the defaults a settings depth starts with, and the two
 * filters the choke points read. Off has to mean gone from the Tools list,
 * the pickers, the root list and the deep links, and nothing but a test
 * holds the enum, the route allowlist and the tool groups to one another.
 */
class ExtensionsTest {

    // ---- the defaults per depth ----------------------------------------------

    @Test
    fun `simple starts with nothing and everything with all`() {
        assertEquals(emptySet<Extension>(), Extension.defaultsFor(SettingsDepth.SIMPLE))
        assertEquals(Extension.All, Extension.defaultsFor(SettingsDepth.EVERYTHING))
    }

    @Test
    fun `each depth adds to the one below it`() {
        val simple = Extension.defaultsFor(SettingsDepth.SIMPLE)
        val standard = Extension.defaultsFor(SettingsDepth.STANDARD)
        assertTrue(standard.containsAll(simple))
        assertTrue(standard.size > simple.size)
        assertTrue(Extension.All.containsAll(standard))
        assertTrue(Extension.All.size > standard.size)
    }

    @Test
    fun `auto resolves as standard`() {
        assertEquals(Extension.defaultsFor(SettingsDepth.STANDARD), Extension.defaultsFor(SettingsDepth.AUTO))
    }

    @Test
    fun `standard leaves off what needs another device, a server or the hardware`() {
        val standard = Extension.defaultsFor(SettingsDepth.STANDARD)
        for (ext in listOf(
            Extension.KDE_CONNECT, Extension.SEND_FILES, Extension.SMART_HOME, Extension.MUSIC_ID,
            Extension.SPEED_TEST, Extension.INSTRUMENTS, Extension.WEATHER_AND_LOCATION, Extension.MODES, Extension.REMOTE,
        )) {
            assertFalse("$ext should wait for Everything or a switch by hand", ext in standard)
        }
    }

    @Test
    fun `the necessities are not extensions`() {
        // Voice, the clipboard, snippets, translate and AI are what a keyboard
        // is for today; each has a switch where it lives, never here.
        for (tool in listOf(
            ToolbarTool.VOICE, ToolbarTool.CLIPBOARD, ToolbarTool.SNIPPETS, ToolbarTool.TRANSLATE,
            ToolbarTool.AI, ToolbarTool.AI_REPLY, ToolbarTool.EMOJI, ToolbarTool.TEXT_EDIT,
        )) {
            assertNull("$tool must belong to no bundle", Extension.owning(tool))
        }
        for (route in listOf("voice", "clipboard", "expander", "ai", "ai_chat", "typing/hardware", "statistics", "floating_button")) {
            assertNull("$route must belong to no bundle", Extension.owningRoute(route))
        }
    }

    @Test
    fun `the onboarding answer maps to the depth the defaults are picked by`() {
        assertEquals(SettingsDepth.SIMPLE, PersonaDepth.MINIMAL.settingsDepth())
        assertEquals(SettingsDepth.STANDARD, PersonaDepth.BALANCED.settingsDepth())
        assertEquals(SettingsDepth.STANDARD, PersonaDepth.UNSET.settingsDepth())
        assertEquals(SettingsDepth.EVERYTHING, PersonaDepth.POWER.settingsDepth())
    }

    @Test
    fun `an install that never chose keeps everything on`() {
        assertEquals(Extension.All, KeyboardSettings().extensionsResolved())
        assertEquals(Extension.All, KeyboardSettings(appUi = AppUiSettings(extensions = null)).extensionsResolved())
        val chosen = setOf(Extension.NOTES)
        assertEquals(chosen, KeyboardSettings(appUi = AppUiSettings(extensions = chosen)).extensionsResolved())
    }

    @Test
    fun `stored names decode and unknown ones are dropped`() {
        assertEquals(
            setOf(Extension.SCANNERS, Extension.NOTES),
            Extension.decode(setOf("SCANNERS", "NOTES", "AI", "FROM_A_LATER_VERSION")),
        )
        assertEquals(emptySet<Extension>(), Extension.decode(emptySet()))
    }

    // ---- ownership -----------------------------------------------------------

    @Test
    fun `a tool belongs to at most one bundle`() {
        val owners = Extension.entries.flatMap { ext -> ext.tools.map { it to ext } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
        assertEquals("tools claimed by two bundles", emptyMap<ToolbarTool, List<Extension>>(), owners)
    }

    @Test
    fun `a route belongs to at most one bundle`() {
        val owners = Extension.entries.flatMap { ext -> ext.routes.map { it to ext } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
        assertEquals("routes claimed by two bundles", emptyMap<String, List<Extension>>(), owners)
    }

    @Test
    fun `every route a bundle owns is an addressable screen`() {
        val unknown = Extension.entries.flatMap { it.routes }.filterNot { it in SettingsRoutes.all }
        assertEquals("extension routes with no entry in SettingsRoutes.all", emptyList<String>(), unknown)
    }

    @Test
    fun `every bundle owns at least one tool`() {
        // An extension is a feature on the keyboard; a settings-only bundle
        // would be a depth level wearing the wrong hat.
        for (ext in Extension.entries) assertTrue("$ext owns no tool", ext.tools.isNotEmpty())
    }

    @Test
    fun `the extensions screens themselves belong to no bundle`() {
        // Or switching one off would lock the way back.
        assertNull(Extension.owningRoute(ExtensionsRoute))
        assertNull(Extension.owningRoute(ExtensionRoutePattern))
        for (ext in Extension.entries) assertNull(extensionRedirect(extensionRoute(ext), emptySet()))
        assertNull(extensionRedirect(ExtensionsRoute, emptySet()))
    }

    @Test
    fun `every bundle has its own screen listed`() {
        for (ext in Extension.entries) {
            for ((route, _) in extensionScreens(ext)) {
                assertTrue("$ext lists $route, which it does not own", route in ext.routes)
            }
        }
    }

    // ---- off means not in ToolGroups -------------------------------------------

    @Test
    fun `off means not in ToolGroups`() {
        for (ext in Extension.entries) {
            val without = ToolGroups.withExtensions(Extension.All - ext).flatMap { it.second }.toSet()
            val stray = without.filter { it in ext.tools }
            assertEquals("$ext is off and its tools are still listed", emptyList<ToolbarTool>(), stray)
            // And nothing else left with it.
            val before = ToolGroups.flatMap { it.second }.toSet()
            assertEquals(before - ext.tools, without)
        }
    }

    @Test
    fun `on restores the groups untouched`() {
        assertEquals(ToolGroups, ToolGroups.withExtensions(Extension.All))
    }

    @Test
    fun `a group emptied by its bundle goes with it`() {
        // Scanners is a whole group of the Tools list.
        val titles = ToolGroups.withExtensions(Extension.All - Extension.SCANNERS).map { it.first }
        assertFalse(R.string.tools_group_scanners_title in titles)
    }

    @Test
    fun `the enabled set filter answers for tools too`() {
        val on = Extension.All - Extension.SCANNERS
        assertFalse(on.allows(ToolbarTool.OCR))
        assertFalse(on.allows(ToolbarTool.QR_SCAN))
        assertTrue(on.allows(ToolbarTool.EMOJI))
        assertTrue(on.allows(ToolbarTool.CLIPBOARD))
    }

    // ---- the root list and the deep links -------------------------------------

    @Test
    fun `no bundle owns a home row`() {
        // The necessities on Home are not extensions, so every bundle off
        // leaves the root list as it is, Extensions row included.
        assertEquals(RootEntries.map { it.route }, visibleRootEntries(emptySet()).map { it.route })
        assertTrue("extensions" in visibleRootEntries(emptySet()).map { it.route })
    }

    @Test
    fun `a link to an owned screen is redirected while its bundle is off`() {
        val off = Extension.All - Extension.MODES - Extension.KDE_CONNECT - Extension.SCANNERS
        assertEquals(Extension.MODES, extensionRedirect("modes", off))
        assertEquals(Extension.MODES, extensionRedirect("mode_edit/mode_chat", off))
        assertEquals(Extension.SCANNERS, extensionRedirect("tool/${ToolbarTool.OCR.name}", off))
        assertEquals(Extension.KDE_CONNECT, extensionRedirect("kdeconnect/devices", off))
        assertNull(extensionRedirect("modes", Extension.All))
        assertNull(extensionRedirect("tool/${ToolbarTool.EMOJI.name}", off))
        assertNull(extensionRedirect("typing", off))
        assertNull(extensionRedirect("no/such/screen", off))
    }
}
