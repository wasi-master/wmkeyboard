package com.wasimaster.wmkeyboard.core.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/** [GlideLanguageProfiles]: the decoder's weights by language (issue #966). */
class GlideLanguageProfilesTest {

    @Test
    fun `german lowers the frequency weight, raises the gap weight and reads compounds`() {
        val tuned = GlideLanguageProfiles.tune(GlideBeam.Tuning.DEFAULT, "de")
        assertEquals(0.75, tuned.freqWeight, 0.0)
        assertEquals(2.0f, tuned.gapWeight, 0f)
        assertEquals(8.0, tuned.joinCost, 0.0)
        assertEquals(4, tuned.joinMinPart)
        assertEquals(0.1, tuned.joinPartPenalty, 0.0)
        // Nothing else moves.
        assertEquals(
            GlideBeam.Tuning.DEFAULT,
            tuned.copy(
                freqWeight = 1.0,
                gapWeight = GlideBeam.Tuning.DEFAULT.gapWeight,
                joinCost = Double.POSITIVE_INFINITY,
                joinMinPart = GlideBeam.Tuning.DEFAULT.joinMinPart,
                joinPartPenalty = GlideBeam.Tuning.DEFAULT.joinPartPenalty,
            ),
        )
    }

    @Test
    fun `the compound reading is on for german and off elsewhere`() {
        assertEquals(Double.POSITIVE_INFINITY, GlideBeam.Tuning.DEFAULT.joinCost, 0.0)
        for (id in listOf("en", "fr", "nl", "fi", "et", "hu", null)) {
            assertEquals(id, Double.POSITIVE_INFINITY, GlideLanguageProfiles.tune(GlideBeam.Tuning.DEFAULT, id).joinCost, 0.0)
        }
        assertEquals(8.0, GlideLanguageProfiles.tune(GlideBeam.Tuning.DEFAULT, "de").joinCost, 0.0)
    }

    @Test
    fun `estonian takes the measured weights and leaves joins off`() {
        val tuned = GlideLanguageProfiles.tune(GlideBeam.Tuning.DEFAULT, "et")
        assertEquals(0.75, tuned.freqWeight, 0.0)
        assertEquals(2.0f, tuned.gapWeight, 0f)
        assertEquals(Double.POSITIVE_INFINITY, tuned.joinCost, 0.0)
        assertEquals(GlideBeam.Tuning.DEFAULT.joinMinPart, tuned.joinMinPart)
        assertEquals(GlideBeam.Tuning.DEFAULT.joinPartPenalty, tuned.joinPartPenalty, 0.0)
        assertEquals(tuned, GlideLanguageProfiles.tune(GlideBeam.Tuning.DEFAULT, "et", hasContext = true))
        assertSame(GlideLanguageProfiles.ESTONIAN, GlideLanguageProfiles.forLanguage("et-EE"))
        assertSame(GlideBeam.Tuning.DEFAULT, GlideLanguageProfiles.tune(GlideBeam.Tuning.DEFAULT, "et", mixed = true))
    }

    @Test
    fun `hungarian takes the measured weights and leaves joins off`() {
        val tuned = GlideLanguageProfiles.tune(GlideBeam.Tuning.DEFAULT, "hu")
        assertEquals(0.75, tuned.freqWeight, 0.0)
        assertEquals(2.0f, tuned.gapWeight, 0f)
        assertEquals(Double.POSITIVE_INFINITY, tuned.joinCost, 0.0)
        assertEquals(GlideBeam.Tuning.DEFAULT.joinMinPart, tuned.joinMinPart)
        assertEquals(GlideBeam.Tuning.DEFAULT.joinPartPenalty, tuned.joinPartPenalty, 0.0)
        assertEquals(tuned, GlideLanguageProfiles.tune(GlideBeam.Tuning.DEFAULT, "hu", hasContext = true))
        assertSame(GlideLanguageProfiles.HUNGARIAN, GlideLanguageProfiles.forLanguage("hu-HU"))
        assertSame(GlideBeam.Tuning.DEFAULT, GlideLanguageProfiles.tune(GlideBeam.Tuning.DEFAULT, "hu", mixed = true))
    }

    @Test
    fun `finnish takes the measured weights and leaves joins off`() {
        val tuned = GlideLanguageProfiles.tune(GlideBeam.Tuning.DEFAULT, "fi")
        assertEquals(0.75, tuned.freqWeight, 0.0)
        assertEquals(2.0f, tuned.gapWeight, 0f)
        // The join slice moved nothing and the app's join path is a different
        // downloaded lexicon, so the row does not enable it.
        assertEquals(Double.POSITIVE_INFINITY, tuned.joinCost, 0.0)
        assertEquals(GlideBeam.Tuning.DEFAULT.joinMinPart, tuned.joinMinPart)
        assertEquals(GlideBeam.Tuning.DEFAULT.joinPartPenalty, tuned.joinPartPenalty, 0.0)
        // No Finnish context model: the weight does not move.
        assertEquals(tuned, GlideLanguageProfiles.tune(GlideBeam.Tuning.DEFAULT, "fi", hasContext = true))
        // Regional ids resolve to the row; mixing in another list does not.
        assertSame(GlideLanguageProfiles.FINNISH, GlideLanguageProfiles.forLanguage("fi-FI"))
        assertSame(GlideBeam.Tuning.DEFAULT, GlideLanguageProfiles.tune(GlideBeam.Tuning.DEFAULT, "fi", mixed = true))
    }

    @Test
    fun `english and unknown languages decode with the shipped weights`() {
        for (id in listOf("en", "en-US", "fr", "bn", "", null)) {
            assertSame(id, GlideBeam.Tuning.DEFAULT, GlideLanguageProfiles.tune(GlideBeam.Tuning.DEFAULT, id))
        }
        for (id in listOf("en", "en-US")) {
            assertSame(id, GlideLanguageProfiles.ENGLISH, GlideLanguageProfiles.forLanguage(id))
        }
        for (id in listOf("fr", "bn", "", null)) {
            assertSame(id, GlideLanguageProfiles.DEFAULT, GlideLanguageProfiles.forLanguage(id))
        }
    }

    @Test
    fun `german mixed with another list decodes with the shipped weights`() {
        // #1051: the German weights were measured on German's list alone.
        assertSame(GlideBeam.Tuning.DEFAULT, GlideLanguageProfiles.tune(GlideBeam.Tuning.DEFAULT, "de", mixed = true))
    }

    @Test
    fun `regional and script variants of german take the german profile`() {
        for (id in listOf("de-DE", "de-Runr", "de_AT", "DE")) {
            assertSame(id, GlideLanguageProfiles.GERMAN, GlideLanguageProfiles.forLanguage(id))
        }
        assertNotSame(GlideLanguageProfiles.GERMAN, GlideLanguageProfiles.forLanguage("den"))
    }

    @Test
    fun `the profile sits on top of what the user moved`() {
        val mine = GlideBeam.Tuning.DEFAULT.copy(startRadius = 2.2f, endRadius = 2.6f, nearRadius = 1.4f, vocabularyRank = 30000)
        val tuned = GlideLanguageProfiles.tune(mine, "de")
        assertEquals(2.2f, tuned.startRadius, 0f)
        assertEquals(2.6f, tuned.endRadius, 0f)
        assertEquals(1.4f, tuned.nearRadius, 0f)
        assertEquals(30000, tuned.vocabularyRank)
        assertEquals(0.75, tuned.freqWeight, 0.0)
        assertEquals(8.0, tuned.joinCost, 0.0)
        // Applying it again changes nothing, so a language re-set is free.
        assertSame(tuned, GlideLanguageProfiles.tune(tuned, "de"))
        // And a switch back to English gives the user's own weights back.
        assertEquals(
            mine,
            GlideLanguageProfiles.tune(tuned, "en").copy(
                gapWeight = mine.gapWeight,
                freqWeight = mine.freqWeight,
                joinCost = mine.joinCost,
                joinMinPart = mine.joinMinPart,
                joinPartPenalty = mine.joinPartPenalty,
            ),
        )
    }
}
