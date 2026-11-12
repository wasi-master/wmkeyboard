package com.wasimaster.wmkeyboard.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Apps with a button of their own on the toolbar (#1159). */
class LauncherToolbarAppsTest {

    @Test
    fun `an app goes on the end and comes off again`() {
        val one = LauncherToolSettings.toggledToolbarApp(emptyList(), "com.maps")
        assertEquals(listOf("com.maps"), one)
        val two = LauncherToolSettings.toggledToolbarApp(one, "com.mail@10")
        assertEquals(listOf("com.maps", "com.mail@10"), two)
        assertEquals(listOf("com.mail@10"), LauncherToolSettings.toggledToolbarApp(two, "com.maps"))
    }

    @Test
    fun `a full toolbar takes no more apps, but still lets one off`() {
        val full = (1..LauncherToolSettings.MAX_TOOLBAR_APPS).map { "app$it" }
        assertEquals(full, LauncherToolSettings.toggledToolbarApp(full, "one.more"))
        assertEquals(full - "app1", LauncherToolSettings.toggledToolbarApp(full, "app1"))
    }

    @Test
    fun `none are on the toolbar by default`() {
        assertEquals(emptyList<String>(), LauncherToolSettings().toolbarApps)
    }

    @Test
    fun `an app's place among the tools survives the round trip`() {
        val slots = mapOf("com.maps" to 0, "com.mail@10" to 3)
        val raw = LauncherToolSettings.encodeToolbarAppSlots(slots)
        assertEquals(slots, LauncherToolSettings.decodeToolbarAppSlots(raw))
    }

    @Test
    fun `a malformed place is dropped and the rest kept`() {
        assertEquals(
            mapOf("com.maps" to 2),
            LauncherToolSettings.decodeToolbarAppSlots("com.maps=2\tbroken\tcom.mail=-1\tcom.x=y"),
        )
        assertEquals(emptyMap<String, Int>(), LauncherToolSettings.decodeToolbarAppSlots(null))
    }
}
