package com.wasimaster.wmkeyboard.core.settings

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A mode's Commit every key override (#1024), its app exclusions and its priority (#1025). */
class KeyboardModeCommitAsTypedTest {

    private fun mode(id: String, block: KeyboardMode.() -> KeyboardMode = { this }) =
        KeyboardMode(id = id, name = id).block()

    @Test
    fun `the override lands in the settings and null inherits`() {
        val base = KeyboardSettings()
        assertFalse(base.applyMode(mode("a")).autoText.commitAsTyped)
        assertTrue(base.applyMode(mode("a") { copy(commitAsTyped = true) }).autoText.commitAsTyped)
    }

    @Test
    fun `the Enter key's action is a field a mode can bind to`() {
        val send = EditorInfo.IME_ACTION_SEND
        assertEquals(setOf(ModeField.SEND_KEY), imeActionFields(send, secure = false))
        assertEquals(setOf(ModeField.SEARCH_KEY), imeActionFields(EditorInfo.IME_ACTION_SEARCH, secure = false))
        assertTrue(imeActionFields(send, secure = true).isEmpty())
        assertTrue(imeActionFields(send or EditorInfo.IME_FLAG_NO_ENTER_ACTION, secure = false).isEmpty())
        assertTrue(imeActionFields(EditorInfo.IME_ACTION_UNSPECIFIED, secure = false).isEmpty())
    }

    @Test
    fun `a mode can turn incognito on but not off`() {
        val on = mode("a") { copy(incognito = true) }
        assertTrue(KeyboardSettings().applyMode(on).incognito)
        assertTrue(KeyboardSettings(incognito = true).applyMode(mode("b")).incognito)
    }

    @Test
    fun `sound, vibration and Enter override and null inherits`() {
        val base = KeyboardSettings()
        val quiet = base.applyMode(mode("a") { copy(keySound = true, keyHaptics = false, enterNewline = true) })
        assertTrue(quiet.sound.enabled)
        assertFalse(quiet.haptics.enabled)
        assertTrue(quiet.layoutBehavior.enterAlwaysNewline)
        val plain = base.applyMode(mode("b"))
        assertEquals(base.sound.enabled, plain.sound.enabled)
        assertEquals(base.haptics.enabled, plain.haptics.enabled)
        assertFalse(plain.layoutBehavior.enterAlwaysNewline)
    }

    @Test
    fun `an excluded app keeps the mode off`() {
        val everywhere = mode("a") { copy(fieldKinds = listOf(ModeField.TEXT), excludedApps = listOf("com.bank")) }
        val fields = setOf(ModeField.TEXT)
        assertNull(resolveKeyboardMode(listOf(everywhere), "com.bank", fields, null))
        assertEquals("a", resolveKeyboardMode(listOf(everywhere), "com.chat", fields, null)?.id)
    }

    @Test
    fun `priority beats specificity, and equal priority leaves the old order`() {
        val fields = setOf(ModeField.TEXT)
        val byField = mode("field") { copy(fieldKinds = listOf(ModeField.TEXT)) }
        val byApp = mode("app") { copy(apps = listOf("com.chat")) }
        assertEquals("field", resolveKeyboardMode(listOf(byApp, byField), "com.chat", fields, null)?.id)
        val raised = byApp.copy(priority = 1)
        assertEquals("app", resolveKeyboardMode(listOf(byField, raised), "com.chat", fields, null)?.id)
    }

    @Test
    fun `a saved mode without the field decodes as null`() {
        val decoded = KeyboardModeCodec.decodeList("[{\"id\":\"a\",\"name\":\"A\"}]")
        assertNull(decoded.single().commitAsTyped)
        assertEquals(emptyList<String>(), decoded.single().excludedApps)
        assertEquals(0, decoded.single().priority)
    }
}
