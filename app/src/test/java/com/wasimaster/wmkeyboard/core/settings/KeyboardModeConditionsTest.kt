package com.wasimaster.wmkeyboard.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardModeConditionsTest {

    private fun resolve(mode: KeyboardMode, pkg: String = "a.b", context: ModeContext = ModeContext()) =
        resolveKeyboardMode(listOf(mode), pkg, setOf(ModeField.TEXT), null, null, context)?.id

    @Test
    fun `a group matches an app it lists and one android puts in it`() {
        val mode = KeyboardMode("m", "M", appGroups = listOf(AppGroup.BROWSER))
        assertNull(resolve(mode))
        assertEquals("m", resolve(mode, context = ModeContext(appGroups = setOf(AppGroup.BROWSER))))
    }

    @Test
    fun `a time window runs across midnight`() {
        val mode = KeyboardMode("m", "M", timeFrom = 22 * 60, timeTo = 7 * 60)
        assertEquals("m", resolve(mode, context = ModeContext(minuteOfDay = 23 * 60)))
        assertEquals("m", resolve(mode, context = ModeContext(minuteOfDay = 60)))
        assertNull(resolve(mode, context = ModeContext(minuteOfDay = 12 * 60)))
    }

    @Test
    fun `device conditions are one more and`() {
        val mode = KeyboardMode(
            "m", "M", apps = listOf("a.b"), orientation = ModeOrientation.LANDSCAPE, hardwareKeyboard = true,
        )
        assertNull(resolve(mode, context = ModeContext(landscape = true)))
        assertEquals("m", resolve(mode, context = ModeContext(landscape = true, hardwareKeyboard = true)))
        assertNull(resolve(mode, pkg = "x.y", context = ModeContext(landscape = true, hardwareKeyboard = true)))
    }

    @Test
    fun `a mode with no conditions stays manual`() {
        assertNull(resolve(KeyboardMode("m", "M")))
    }
}
