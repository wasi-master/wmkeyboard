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
    fun `a saved mode without the field decodes as null`() {
        val decoded = KeyboardModeCodec.decodeList("[{\"id\":\"a\",\"name\":\"A\"}]")
        assertNull(decoded.single().commitAsTyped)
        assertEquals(emptyList<String>(), decoded.single().excludedApps)
        assertEquals(0, decoded.single().priority)
    }
}
