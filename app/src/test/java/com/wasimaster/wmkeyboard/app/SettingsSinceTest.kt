package com.wasimaster.wmkeyboard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `since=` a copied setting link carries: the release the row arrived in,
 * read from the docs site's table, not the version it was copied on.
 */
class SettingsSinceTest {

    private val table = SettingsSinceTable.parse(
        """
        {
          "floor": "0.5.3", "latest": "0.6.6", "next": "0.6.7", "modeRows": "0.5.12",
          "routes": { "typing": "0.5.3", "clipboard/rules": "0.6.4", "mode_edit/{modeId}": "0.5.3" },
          "settings": { "old_title": "0.5.3", "mid_title": "0.6.1", "next_title": "0.6.7" },
          "modes": { "mode_browser": "0.5.3" }
        }
        """.trimIndent(),
    )!!

    @Test
    fun `a row as old as the floor needs no since`() {
        assertEquals("", table.versionFor("typing", "old_title", "0.6.6"))
    }

    @Test
    fun `a row is dated by the release it arrived in, not the sender's`() {
        assertEquals("0.6.1", table.versionFor("typing", "mid_title", "0.6.6"))
        assertEquals("0.6.1", table.versionFor(null, "mid_title", "0.6.6"))
    }

    @Test
    fun `the screen counts when it is newer than the row`() {
        assertEquals("0.6.4", table.versionFor("clipboard/rules", "old_title", "0.6.6"))
    }

    @Test
    fun `a row on a mode's editor needs the release that finds rows there`() {
        assertEquals("0.5.12", table.versionFor("mode_edit/mode_browser", "old_title", "0.6.6"))
    }

    @Test
    fun `a row the table does not list is the sender's version`() {
        assertEquals("0.6.6", table.versionFor("typing", "brand_new_title", "0.6.6"))
        assertEquals("0.6.6", table.versionFor("mode_edit/mode_custom_1", "old_title", "0.6.6"))
    }

    @Test
    fun `the sender's version caps a date the table puts in the next release`() {
        assertEquals("0.6.6", table.versionFor("typing", "next_title", "0.6.6"))
        assertEquals("debug builds keep the table's date", "0.6.7", table.versionFor("typing", "next_title", "0.6.6-debug"))
    }

    @Test
    fun `the copied link carries the table's date`() {
        assertEquals(
            "wmkeyboard://settings/typing?setting=mid_title&since=0.6.1",
            SettingLinks.appLink("typing", "mid_title", table.versionFor("typing", "mid_title", "0.6.6")),
        )
        assertEquals(
            "wmkeyboard://settings/typing?setting=old_title",
            SettingLinks.appLink("typing", "old_title", table.versionFor("typing", "old_title", "0.6.6")),
        )
    }

    @Test
    fun `something that is not the table reads as none`() {
        assertNull(SettingsSinceTable.parse("[]"))
        assertNull(SettingsSinceTable.parse("{\"routes\": {}}"))
    }
}
