package com.wasimaster.wmkeyboard.ime.wmpass

import android.text.InputType
import com.wasimaster.wmkeyboard.ime.ui.wmpass.passwordBits
import com.wasimaster.wmkeyboard.ime.ui.wmpass.strengthLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WmPassToolTest {
    private fun row(kind: String, matches: Boolean = false, favourite: Boolean = false, hasCode: Boolean = false) =
        VaultWire.Row("id", kind, "Title", "", matches, hasPassword = true, hasCode = hasCode, favourite = favourite)

    @Test
    fun filtersSortRowsByKindAndMarks() {
        assertTrue(WmPassFilter.ALL.admits(row("wifi")))
        assertTrue(WmPassFilter.FOR_APP.admits(row("login", matches = true)))
        assertFalse(WmPassFilter.FOR_APP.admits(row("login")))
        assertTrue(WmPassFilter.FAVOURITES.admits(row("card", favourite = true)))
        assertTrue(WmPassFilter.CODES.admits(row("login", hasCode = true)))
        assertTrue(WmPassFilter.CARDS.admits(row("card")))
        assertTrue(WmPassFilter.NOTES.admits(row("note")))
        assertTrue(WmPassFilter.OTHER.admits(row("wifi")))
        assertFalse(WmPassFilter.OTHER.admits(row("login")))
    }

    @Test
    fun aFillTypesThePasswordOnlyIntoAPasswordBox() {
        val text = InputType.TYPE_CLASS_TEXT
        assertTrue(isPasswordInput(text or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(isPasswordInput(text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertTrue(isPasswordInput(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertFalse(isPasswordInput(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertFalse(isPasswordInput(text))
        assertFalse(isPasswordInput(0))
    }

    @Test
    fun strengthGrowsWithLengthAndKindsOfCharacter() {
        assertEquals(0, passwordBits(""))
        assertEquals(0, strengthLevel(passwordBits("abc")))
        assertTrue(passwordBits("aaaaaaaaaaaaaaaa") < passwordBits("abcdefghijklmnop"))
        assertTrue(passwordBits("abcdefgh") < passwordBits("aB3#efgh"))
        assertEquals(4, strengthLevel(passwordBits("t7#Qm!vZ2pL9@wXr4&")))
    }
}
