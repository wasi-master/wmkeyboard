package com.wasimaster.wmkeyboard.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardModeFieldConditionsTest {

    private fun resolve(mode: KeyboardMode, context: ModeContext = ModeContext()) =
        resolveKeyboardMode(listOf(mode), "a.b", setOf(ModeField.TEXT), null, null, context)?.id

    @Test
    fun `a bluetooth condition can stand alone and name a device`() {
        val any = KeyboardMode("m", "M", bluetoothAudio = true)
        assertNull(resolve(any))
        assertEquals("m", resolve(any, ModeContext(bluetoothNames = listOf("Car Audio"))))
        val car = KeyboardMode("m", "M", bluetoothNames = listOf("car"))
        assertEquals("m", resolve(car, ModeContext(bluetoothNames = listOf("Car Audio"))))
        assertNull(resolve(car, ModeContext(bluetoothNames = listOf("Buds"))))
    }

    @Test
    fun `a field is matched by its name or its id number`() {
        val mode = KeyboardMode("m", "M", fieldNames = listOf("subject", "1234"))
        assertEquals("m", resolve(mode, ModeContext(fieldName = "email_Subject")))
        assertEquals("m", resolve(mode, ModeContext(fieldId = 1234)))
        assertNull(resolve(mode, ModeContext(fieldName = "body", fieldId = 1)))
    }

    @Test
    fun `a field's languages are matched by code`() {
        val mode = KeyboardMode("m", "M", fieldLanguages = listOf("JA"))
        assertEquals("m", resolve(mode, ModeContext(fieldLanguages = setOf("ja"))))
        assertNull(resolve(mode, ModeContext(fieldLanguages = setOf("en"))))
    }

    @Test
    fun `text at the start of the field decides`() {
        val empty = KeyboardMode("m", "M", fieldEmpty = true)
        assertEquals("m", resolve(empty, ModeContext(fieldText = "")))
        assertNull(resolve(empty, ModeContext(fieldText = "hi")))
        val command = KeyboardMode("m", "M", textStarts = listOf("/", "$"))
        assertEquals("m", resolve(command, ModeContext(fieldText = "/start")))
        assertNull(resolve(command, ModeContext(fieldText = "hi /start")))
    }

    @Test
    fun `screen size and one-handed are conditions of their own`() {
        val big = KeyboardMode("m", "M", screenSize = ModeScreenSize.TABLET)
        assertEquals("m", resolve(big, ModeContext(tablet = true)))
        assertNull(resolve(big))
        val hand = KeyboardMode("m", "M", oneHanded = true)
        assertEquals("m", resolve(hand, ModeContext(oneHanded = true)))
        assertNull(resolve(hand))
    }

    @Test
    fun `the new overrides reach the settings and leave the rest alone`() {
        val base = KeyboardSettings()
        val mode = KeyboardMode(
            "m", "M", numberRow = !base.numberRow, keyHeightPercent = 50, keyPreview = false,
            longPressDelayMs = 150, clipboardChip = false, keySwipes = false,
            voiceLanguages = listOf("de"),
        )
        val applied = base.applyMode(mode)
        assertEquals(!base.numberRow, applied.numberRow)
        assertEquals(base.keyHeightDp / 2, applied.keyHeightDp)
        assertEquals(false, applied.popup.enabled)
        assertEquals(150, applied.longPressDelayMs)
        assertEquals(false, applied.clipboard.suggestRecent)
        assertEquals(false, applied.gesture.keySwipesOn)
        assertEquals(listOf("de"), applied.whisper.languages)
        assertEquals(base.enabledLanguages, applied.enabledLanguages)
        assertEquals(base, base.applyMode(KeyboardMode("m", "M")))
    }
}
