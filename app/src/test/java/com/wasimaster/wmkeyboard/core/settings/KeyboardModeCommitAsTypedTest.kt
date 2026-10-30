package com.wasimaster.wmkeyboard.core.settings

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
