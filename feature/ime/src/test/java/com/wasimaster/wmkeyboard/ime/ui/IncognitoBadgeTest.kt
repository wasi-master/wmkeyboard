package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1177: the incognito badge stands down when the Incognito tool is on the bar. */
class IncognitoBadgeTest {

    @Test
    fun `the badge shows while incognito is on and the tool is not on the bar`() {
        assertTrue(incognitoBadgeShown(incognitoOn = true, barTools = listOf(ToolbarTool.UNDO)))
        assertTrue(incognitoBadgeShown(incognitoOn = true, barTools = emptyList()))
    }

    @Test
    fun `the lit Incognito tool on the bar replaces the badge`() {
        assertFalse(incognitoBadgeShown(incognitoOn = true, barTools = listOf(ToolbarTool.UNDO, ToolbarTool.INCOGNITO)))
    }

    @Test
    fun `no badge while incognito is off`() {
        assertFalse(incognitoBadgeShown(incognitoOn = false, barTools = emptyList()))
    }
}
