package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.settings.OnboardingSettings
import com.wasimaster.wmkeyboard.core.settings.PersonaDepth
import com.wasimaster.wmkeyboard.core.settings.PersonaPrivacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingDiscoverTest {

    private fun features(
        depth: PersonaDepth = PersonaDepth.UNSET,
        privacy: PersonaPrivacy = PersonaPrivacy.UNSET,
        whisper: Boolean = true,
        supported: Boolean = true,
    ) = discoverFeatures(
        OnboardingSettings(personaDepth = depth, personaPrivacy = privacy),
        whisperAvailable = whisper,
        isToolSupported = { supported },
    )

    @Test
    fun `ids are unique`() {
        val ids = features(depth = PersonaDepth.POWER, privacy = PersonaPrivacy.STRICT).map { it.id }
        assertEquals(ids.toSet().size, ids.size)
    }

    @Test
    fun `minimal gets a short list led by the unique features`() {
        val list = features(depth = PersonaDepth.MINIMAL).map { it.id }
        assertEquals(
            listOf("chips", "modes", "hotwords", "toolbox", "photos", "clipboard"),
            list,
        )
    }

    @Test
    fun `balanced and unset get the head of the list`() {
        assertEquals(8, features(depth = PersonaDepth.BALANCED).size)
        assertEquals(8, features().size)
    }

    @Test
    fun `power gets everything including whisper and modes`() {
        val ids = features(depth = PersonaDepth.POWER).map { it.id }
        assertTrue("whisper" in ids)
        assertTrue("modes" in ids)
    }

    /**
     * Issue #41: modes surprise people who never asked for them, so the card
     * offering the off switch is on every persona's page, right behind the
     * chips, and short enough not to be trimmed by any cap.
     */
    @Test
    fun `every persona sees the modes card second`() {
        for (depth in PersonaDepth.entries) {
            val ids = features(depth = depth).map { it.id }
            assertEquals(depth.name, "modes", ids[1])
        }
    }

    @Test
    fun `whisper is dropped on lite builds`() {
        val ids = features(depth = PersonaDepth.POWER, whisper = false).map { it.id }
        assertFalse("whisper" in ids)
        assertTrue("modes" in ids)
    }

    @Test
    fun `strict privacy pins the keyboard toggles first and pushes ai last`() {
        val ids = features(depth = PersonaDepth.POWER, privacy = PersonaPrivacy.STRICT).map { it.id }
        assertEquals("quick_toggles", ids.first())
        assertEquals("ai", ids.last())
    }

    @Test
    fun `unsupported tools drop their cards`() {
        val ids = features(depth = PersonaDepth.POWER, supported = false).map { it.id }
        assertFalse("fancy" in ids)
        assertFalse("ai" in ids)
        assertFalse("snippets" in ids)
        assertTrue("chips" in ids)
    }
}
