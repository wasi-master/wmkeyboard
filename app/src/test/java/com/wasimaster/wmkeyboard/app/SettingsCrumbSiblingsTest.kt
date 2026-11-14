package com.wasimaster.wmkeyboard.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** The pages a long press on a path step lists (#1317), and the recent pages kept (#1317). */
class SettingsCrumbSiblingsTest {

    private fun section(title: String, route: String, vararg path: String) =
        SettingsSearchEntry(title, "", path.toList(), route, EntryWeight.SECTION)

    private fun tool(title: String, route: String) =
        SettingsSearchEntry(title, "", listOf("Tools", title), route, EntryWeight.TOOL)

    private val index = listOf(
        section("Typing", "typing", "Settings"),
        section("Key press", "keypress", "Settings"),
        section("Tools", "tools", "Settings"),
        section("Suggestions", "typing/suggestions", "Typing"),
        section("Gestures", "typing/gestures", "Typing"),
        section("Glide typing", "typing/gestures/glide", "Typing", "Gestures"),
        section("Spacebar", "typing/gestures/spacebar", "Typing", "Gestures"),
        // A button the index files as a destination, on a page already listed.
        section("Import from a file", "typing/suggestions", "Typing"),
        SettingsSearchEntry("Autocorrect", "", listOf("Typing", "Suggestions"), "typing/suggestions"),
        tool("Clipboard", "tool/CLIPBOARD"),
        tool("Calculator", "tool/CALCULATOR"),
        section("A language", "language/{langId}", "Typing"),
    )

    private fun siblings(vararg path: String) =
        crumbSiblings(path.toList(), index, homeTitle = "Home", rootTitle = "Settings").map { it.route }

    @Test
    fun `a step under home lists the home rows`() {
        assertEquals(listOf("typing", "keypress", "tools"), siblings("Home", "Typing"))
    }

    @Test
    fun `a deeper step lists its parent's pages, once each, and no argument routes`() {
        assertEquals(listOf("typing/suggestions", "typing/gestures"), siblings("Home", "Typing", "Gestures"))
        assertEquals(
            listOf("typing/gestures/glide", "typing/gestures/spacebar"),
            siblings("Home", "Typing", "Gestures", "Spacebar"),
        )
    }

    @Test
    fun `a tool's page lists the other tools`() {
        assertEquals(listOf("tool/CLIPBOARD", "tool/CALCULATOR"), siblings("Home", "Tools", "Clipboard"))
    }

    @Test
    fun `home itself and an unknown step have none`() {
        assertEquals(emptyList<String>(), siblings("Home"))
        assertEquals(emptyList<String>(), siblings("Home", "Somewhere", "Nowhere"))
    }

    @Test
    fun `a visit goes first, replaces an earlier one, and the list is capped`() {
        fun page(n: Int) = SettingsFavourite("wmkeyboard://settings/p$n", "P$n")
        var visits = emptyList<SettingsFavourite>()
        for (n in 1..RecentPages.MAX + 3) visits = RecentPages.withVisit(visits, page(n))
        assertEquals(RecentPages.MAX, visits.size)
        assertEquals(page(RecentPages.MAX + 3), visits.first())
        visits = RecentPages.withVisit(visits, page(10))
        assertEquals(page(10), visits.first())
        assertEquals(1, visits.count { it == page(10) })
    }
}
