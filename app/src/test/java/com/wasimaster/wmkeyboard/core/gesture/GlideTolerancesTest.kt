package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.settings.GestureSettings
import com.wasimaster.wmkeyboard.core.settings.GlideRadiusRange
import com.wasimaster.wmkeyboard.core.settings.glideTolerancesFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** [GlideTolerances]: glide radii a language keeps for itself (issue #1136). */
class GlideTolerancesTest {

    private val global = GlideBeam.Tuning.DEFAULT.copy(startRadius = 2.0f, endRadius = 2.5f, nearRadius = 2.2f)

    @Test
    fun `the active language's own radii replace the global ones`() {
        val byLanguage = mapOf("ja" to GlideTolerances(startRadius = 1.0f, endRadius = 1.1f, nearRadius = 0.9f))
        val tuned = GlideTolerances.resolve(global, byLanguage, "ja")
        assertEquals(1.0f, tuned.startRadius, 0f)
        assertEquals(1.1f, tuned.endRadius, 0f)
        assertEquals(0.9f, tuned.nearRadius, 0f)
        // Nothing but the radii moves.
        assertEquals(global, tuned.copy(startRadius = 2.0f, endRadius = 2.5f, nearRadius = 2.2f))
    }

    @Test
    fun `a radius the language left unset follows the global one`() {
        val byLanguage = mapOf("ja" to GlideTolerances(nearRadius = 0.9f))
        val tuned = GlideTolerances.resolve(global, byLanguage, "ja")
        assertEquals(2.0f, tuned.startRadius, 0f)
        assertEquals(2.5f, tuned.endRadius, 0f)
        assertEquals(0.9f, tuned.nearRadius, 0f)
    }

    @Test
    fun `other languages, a blank id and an empty map decode with the global radii`() {
        val byLanguage = mapOf("ja" to GlideTolerances(nearRadius = 0.9f))
        assertSame(global, GlideTolerances.resolve(global, byLanguage, "en"))
        assertSame(global, GlideTolerances.resolve(global, byLanguage, ""))
        assertSame(global, GlideTolerances.resolve(global, byLanguage, null))
        assertSame(global, GlideTolerances.resolve(global, emptyMap(), "ja"))
    }

    @Test
    fun `the id is matched exactly, so a regional variant does not borrow its parent's`() {
        val byLanguage = mapOf("de" to GlideTolerances(nearRadius = 1.0f))
        assertSame(global, GlideTolerances.resolve(global, byLanguage, "de-CH"))
    }

    @Test
    fun `own radii sit under the language profile, which moves only its own weights`() {
        val byLanguage = mapOf("de" to GlideTolerances(startRadius = 1.2f))
        val tuned = GlideLanguageProfiles.tune(GlideTolerances.resolve(global, byLanguage, "de"), "de")
        assertEquals(1.2f, tuned.startRadius, 0f)
        assertEquals(GlideLanguageProfiles.GERMAN.freqWeight, tuned.freqWeight, 0.0)
    }

    @Test
    fun `values equal to the global ones give back the same tuning`() {
        val same = GlideTolerances(startRadius = 2.0f, endRadius = 2.5f, nearRadius = 2.2f)
        assertSame(global, same.applyTo(global))
    }

    @Test
    fun `the map survives a round trip through its preference string`() {
        val byLanguage = mapOf(
            "ja" to GlideTolerances(startRadius = 1.0f, nearRadius = 0.9f),
            "en" to GlideTolerances(endRadius = 3.5f),
        )
        val raw = GlideTolerances.encode(byLanguage)
        assertEquals("en=,3.5,;ja=1.0,,0.9", raw)
        assertEquals(byLanguage, GlideTolerances.decode(raw, GlideRadiusRange))
    }

    @Test
    fun `a language with nothing of its own is not written`() {
        val raw = GlideTolerances.encode(mapOf("ja" to GlideTolerances.NONE, "en" to GlideTolerances(nearRadius = 1f)))
        assertEquals("en=,,1.0", raw)
    }

    @Test
    fun `decoding skips malformed entries and holds radii to the range`() {
        val decoded = GlideTolerances.decode("ja=9,,0.1;bad;=1,1,1;fr=1,2;de=,,;ko=x,,1.5", GlideRadiusRange)
        assertEquals(
            mapOf(
                "ja" to GlideTolerances(startRadius = GlideRadiusRange.endInclusive, nearRadius = GlideRadiusRange.start),
                "ko" to GlideTolerances(nearRadius = 1.5f),
            ),
            decoded,
        )
        assertTrue(GlideTolerances.decode(null, GlideRadiusRange).isEmpty())
        assertTrue(GlideTolerances.decode("", GlideRadiusRange).isEmpty())
    }

    @Test
    fun `the language page reads its own value where set and the global one elsewhere`() {
        val gesture = GestureSettings(
            startRadius = 2.0f,
            endRadius = 2.5f,
            nearRadius = 2.2f,
            tolerancesByLanguage = mapOf("ja" to GlideTolerances(nearRadius = 0.9f)),
        )
        assertEquals(GlideTolerances(2.0f, 2.5f, 0.9f), gesture.glideTolerancesFor("ja"))
        assertEquals(GlideTolerances(2.0f, 2.5f, 2.2f), gesture.glideTolerancesFor("en"))
    }
}
