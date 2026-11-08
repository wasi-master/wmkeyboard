package com.wasimaster.wmkeyboard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The links a settings row copies of itself (#646). What matters is that each
 * one goes the whole way round: the web address reads back through
 * [WebOpenLink] and the app link through [SettingsDeepLink] to the same screen
 * and row it was copied from.
 */
class SettingLinksTest {

    @Test
    fun `a row on a plain screen names both`() {
        assertEquals(
            "wmkeyboard://settings/typing?setting=typing_autocorrect_title&since=0.6.1",
            SettingLinks.appLink("typing", "typing_autocorrect_title", "0.6.1"),
        )
    }

    @Test
    fun `a row whose screen is unknown goes through the setting host`() {
        assertEquals(
            "wmkeyboard://setting/typing_autocorrect_title?since=0.6.1",
            SettingLinks.appLink(null, "typing_autocorrect_title", "0.6.1"),
        )
    }

    @Test
    fun `an unfilled pattern is not a screen`() {
        assertEquals(
            "wmkeyboard://setting/x_title",
            SettingLinks.appLink("mode_edit/{modeId}", "x_title", "dev"),
        )
    }

    @Test
    fun `a link into a theme's editor names the reader's theme, not the sender's`() {
        assertEquals(
            "wmkeyboard://settings/theme_edit/current?setting=theme_touch_bloom_title&since=0.6.5",
            SettingLinks.appLink("theme_edit/custom_1793115110139", "theme_touch_bloom_title", "0.6.5"),
        )
    }

    @Test
    fun `the editor opens the applied theme for current or for an id this phone lacks`() {
        val mine = setOf("custom_1", "custom_2")
        assertEquals("custom_2", SettingLinks.themeEditTarget("custom_2", "custom_1", mine::contains))
        assertEquals("custom_1", SettingLinks.themeEditTarget("current", "custom_1", mine::contains))
        assertEquals("custom_1", SettingLinks.themeEditTarget("custom_9", "custom_1", mine::contains))
        assertEquals("built-in applied", "custom_9", SettingLinks.themeEditTarget("custom_9", "default", mine::contains))
    }

    @Test
    fun `a version that is not a number is left out`() {
        assertEquals(
            "wmkeyboard://settings/typing?setting=typing_autocorrect_title",
            SettingLinks.appLink("typing", "typing_autocorrect_title", "0.6.1-debug"),
        )
    }

    @Test
    fun `the web link reads back to the same address`() {
        val app = SettingLinks.appLink("typing", "typing_autocorrect_title", "0.6.1")
        val web = SettingLinks.webLink(app)
        assertEquals("$DOCS_URL/open/#$app", web)
        assertEquals(app, WebOpenLink.appLink(web))
        val target = SettingsDeepLink.parse(app)!!
        assertEquals("typing", target.route)
        assertEquals("typing_autocorrect_title", target.setting)
        assertEquals("0.6.1", target.since)
    }

    @Test
    fun `arguments are filled in and encoded`() {
        assertEquals(
            "tool/CLIPBOARD",
            SettingLinks.concreteRoute("tool/{tool}") { if (it == "tool") "CLIPBOARD" else null },
        )
        assertEquals(
            "language/a%20b",
            SettingLinks.concreteRoute("language/{lang}") { "a b" },
        )
    }

    @Test
    fun `a missing argument names no route`() {
        assertNull(SettingLinks.concreteRoute("tool/{tool}") { null })
        assertNull(SettingLinks.concreteRoute(null) { "x" })
    }

    private val trail = listOf(
        SettingsCrumb("1", "Home"),
        SettingsCrumb("2", "Typing"),
        SettingsCrumb("3", "Autocorrect"),
        SettingsCrumb("4", "Below"),
    )

    @Test
    fun `the path stops at the row's own screen and ends with the row`() {
        assertEquals(
            listOf("Home", "Typing", "Autocorrect", "Undo on backspace"),
            SettingLinks.path(trail, "3", "Undo on backspace"),
        )
    }

    @Test
    fun `a row named like its screen is not named twice`() {
        assertEquals(listOf("Home", "Typing"), SettingLinks.path(trail, "2", "Typing"))
    }

    @Test
    fun `a screen not on the trail yet takes the whole trail`() {
        assertEquals(listOf("Home", "Typing", "Autocorrect", "Below", "Row"), SettingLinks.path(trail, "9", "Row"))
    }

    @Test
    fun `the copy puts the path above the link`() {
        assertEquals("Home › Typing\nhttps://x", SettingLinks.clipText(listOf("Home", "Typing"), "https://x"))
        assertEquals("https://x", SettingLinks.clipText(emptyList(), "https://x"))
    }
}
