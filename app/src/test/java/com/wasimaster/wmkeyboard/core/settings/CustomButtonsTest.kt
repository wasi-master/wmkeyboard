package com.wasimaster.wmkeyboard.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomButtonsTest {

    private val shortcut = CustomButton(
        id = "c1",
        kind = CustomButtonKind.SHORTCUT,
        target = "intent:#Intent;component=org.example/.Incognito;end",
        label = "Incognito",
        packageName = "org.example",
    )
    private val numberRow = CustomButton("c2", CustomButtonKind.SETTING, CustomToggle.NUMBER_ROW.name, onToolbar = false)
    private val favourites = CustomButton("c3", CustomButtonKind.PAGE, CustomPage.FAVOURITES.name)
    private val weather = CustomButton("c5", CustomButtonKind.PLUGIN, "com.example.weather")

    @Test
    fun `round trips through the stored form`() {
        val list = listOf(shortcut, numberRow, favourites, weather)
        assertEquals(list, decodeCustomButtons(encodeCustomButtons(list)))
        assertTrue(weather.usable)
    }

    @Test
    fun `empty encodes as nothing and garbage decodes as nothing`() {
        assertNull(encodeCustomButtons(emptyList()))
        assertEquals(emptyList<CustomButton>(), decodeCustomButtons("not json"))
        assertEquals(emptyList<CustomButton>(), decodeCustomButtons("""[{"id":"c1","kind":"WHAT","target":"x"}]"""))
    }

    @Test
    fun `duplicate ids and blank targets are dropped`() {
        val list = listOf(shortcut, shortcut.copy(label = "again"), numberRow.copy(id = "c9", target = ""))
        assertEquals(listOf(shortcut), list.normalizedCustomButtons())
    }

    @Test
    fun `toolbar and toolbox split by placement and skip unknown switches`() {
        val gone = CustomButton("c4", CustomButtonKind.SETTING, "NO_SUCH_SETTING")
        val list = listOf(shortcut, numberRow, favourites, gone)
        assertEquals(listOf(shortcut, favourites), list.onToolbar())
        assertEquals(listOf(numberRow), list.inToolbox())
    }

    @Test
    fun `next id fills the first gap`() {
        assertEquals("c4", listOf(shortcut, numberRow, favourites).nextCustomButtonId())
        assertEquals("c1", listOf(numberRow).nextCustomButtonId())
    }

    @Test
    fun `moving clamps at the ends`() {
        val list = listOf(shortcut, numberRow, favourites)
        assertEquals(listOf(numberRow, shortcut, favourites), list.moved("c2", -1))
        assertEquals(list, list.moved("c1", -1))
        assertEquals(listOf(numberRow, favourites, shortcut), list.moved("c1", 5))
    }

    @Test
    fun `the floating button names a custom button by its id`() {
        val action = FloatingAction.Custom("c2")
        assertEquals("custom:c2", action.token)
        assertEquals(action, FloatingAction.parse(action.token))
        assertNull(FloatingAction.parse("custom:"))
        assertNull(FloatingAction.parse("custom:a,b"))
        assertTrue(action in FloatingAction.parseMenu("keyboard,custom:c2"))
    }

    @Test
    fun `a switch reads its setting`() {
        val on = KeyboardSettings(numberRow = true)
        assertTrue(CustomToggle.NUMBER_ROW.isOn(on))
        assertEquals(false, CustomToggle.NUMBER_ROW.isOn(on.copy(numberRow = false)))
    }
}
