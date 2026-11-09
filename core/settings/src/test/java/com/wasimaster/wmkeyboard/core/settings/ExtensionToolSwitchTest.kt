package com.wasimaster.wmkeyboard.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1067: the Tools page switches single tools and Extensions switches bundles
 * of them, and the two have to agree about what is on.
 */
class ExtensionToolSwitchTest {

    private val instruments = ToolbarTool.FLASHLIGHT to Extension.INSTRUMENTS

    @Test
    fun `switching off the last tool of a bundle switches the bundle off`() {
        val after = Extension.All.afterToolSwitch(
            ToolbarTool.APP_LAUNCHER, enabled = false, disabledAfter = listOf(ToolbarTool.APP_LAUNCHER),
        )
        assertTrue(Extension.APP_LAUNCHER !in after)
        assertEquals(Extension.All - Extension.APP_LAUNCHER, after)
    }

    @Test
    fun `a bundle stays on while any of its tools is on`() {
        val (tool, bundle) = instruments
        val after = Extension.All.afterToolSwitch(tool, enabled = false, disabledAfter = listOf(tool))
        assertTrue(bundle in after)
    }

    @Test
    fun `the last of several tools takes the bundle with it`() {
        val all = Extension.INSTRUMENTS.liveTools()
        val after = Extension.All.afterToolSwitch(all.last(), enabled = false, disabledAfter = all)
        assertTrue(Extension.INSTRUMENTS !in after)
    }

    @Test
    fun `switching a tool on switches its bundle on`() {
        val off = Extension.All - Extension.APP_LAUNCHER
        assertTrue(Extension.APP_LAUNCHER in off.afterToolSwitch(ToolbarTool.APP_LAUNCHER, true, emptyList()))
    }

    @Test
    fun `a tool no bundle owns changes no bundle`() {
        val set = Extension.All - Extension.NOTES
        assertSame(set, set.afterToolSwitch(ToolbarTool.UNDO, enabled = false, disabledAfter = listOf(ToolbarTool.UNDO)))
    }

    @Test
    fun `a bundle switched on brings its tools back only when all were off`() {
        val all = Extension.INSTRUMENTS.liveTools()
        assertEquals(all.toSet(), Extension.INSTRUMENTS.toolsToRestore(all + ToolbarTool.UNDO))
        // One still on: the choice among them stands.
        assertEquals(emptySet<ToolbarTool>(), Extension.INSTRUMENTS.toolsToRestore(all.drop(1)))
        assertEquals(emptySet<ToolbarTool>(), Extension.INSTRUMENTS.toolsToRestore(emptyList()))
    }
}
