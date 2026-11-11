package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.CompositeWordSource
import com.wasimaster.wmkeyboard.core.prediction.PackedTrie
import org.junit.Assert.assertEquals
import org.junit.Test

/** The glide context blend is opt-in per language and list size (#1240). */
class GlideLanguageProfilesTest {

    @Test
    fun `english blends the small list with pieces and the large list from listed words`() {
        val en = GlideLanguageProfiles.forLanguage("en")
        assertEquals(0.7, en.glideLmBlend, 0.0)
        assertEquals(3.0, en.glideLmBlendCap, 0.0)
        assertEquals(true, en.glideLmSpellOov)
        assertEquals(0.5, en.glideLmBlendLargeList, 0.0)
        assertEquals(5.0, en.glideLmBlendCapLargeList, 0.0)
        assertEquals(false, en.glideLmSpellOovLargeList)
    }

    @Test
    fun `german keeps its measured blend on both list sizes`() {
        val de = GlideLanguageProfiles.forLanguage("de")
        assertEquals(0.5, de.glideLmBlend, 0.0)
        assertEquals(5.0, de.glideLmBlendCap, 0.0)
        assertEquals(0.5, de.glideLmBlendLargeList, 0.0)
        assertEquals(5.0, de.glideLmBlendCapLargeList, 0.0)
        assertEquals(true, de.glideLmSpellOovLargeList)
    }

    @Test
    fun `a language without a row has no glide blend until it is measured`() {
        val fr = GlideLanguageProfiles.forLanguage("fr")
        assertEquals(0.0, fr.glideLmBlend, 0.0)
        assertEquals(0.0, fr.glideLmBlendLargeList, 0.0)
    }

    @Test
    fun `the small list limit separates the bundled english list from AOSP`() {
        assertEquals(50_000, GlideLanguageProfiles.GLIDE_LM_BLEND_LIST_LIMIT)
    }

    @Test
    fun `german reads 0_75 without a context model and 0_5 with one`() {
        val de = GlideLanguageProfiles.GERMAN
        assertEquals(0.75, de.freqWeight, 0.0)
        assertEquals(0.5, de.freqWeightWithContext, 0.0)
        val without = GlideLanguageProfiles.tune(GlideBeam.Tuning.DEFAULT, "de")
        val with = GlideLanguageProfiles.tune(GlideBeam.Tuning.DEFAULT, "de", hasContext = true)
        assertEquals(0.75, without.freqWeight, 0.0)
        assertEquals(0.5, with.freqWeight, 0.0)
        // The context weight moves frequency alone: the profile's gap and join
        // weights are the same either way.
        assertEquals(without.gapWeight, with.gapWeight, 0.0f)
        assertEquals(without.joinCost, with.joinCost, 0.0)
        assertEquals(without.joinMinPart, with.joinMinPart)
        assertEquals(without.joinPartPenalty, with.joinPartPenalty, 0.0)
    }

    @Test
    fun `english keeps its frequency weight with a context model`() {
        val with = GlideLanguageProfiles.tune(GlideBeam.Tuning.DEFAULT, "en", hasContext = true)
        assertEquals(1.0, with.freqWeight, 0.0)
    }

    @Test
    fun `estonian uses the measured weights and leaves joins off`() {
        val et = GlideLanguageProfiles.forLanguage("et")
        assertEquals(0.75, et.freqWeight, 0.0)
        assertEquals(0.75, et.freqWeightWithContext, 0.0)
        assertEquals(2.0f, et.gapWeight, 0.0f)
        assertEquals(Double.POSITIVE_INFINITY, et.joinCost, 0.0)
        assertEquals(GlideBeam.Tuning.DEFAULT.joinMinPart, et.joinMinPart)
        assertEquals(GlideBeam.Tuning.DEFAULT.joinPartPenalty, et.joinPartPenalty, 0.0)
        assertEquals(0.0, et.glideLmBlend, 0.0)
        assertEquals(0.0, et.glideLmBlendLargeList, 0.0)
        val without = GlideLanguageProfiles.tune(GlideBeam.Tuning.DEFAULT, "et")
        val with = GlideLanguageProfiles.tune(GlideBeam.Tuning.DEFAULT, "et", hasContext = true)
        assertEquals(without, with)
    }

    @Test
    fun `hungarian uses the measured weights and leaves joins off`() {
        val hu = GlideLanguageProfiles.forLanguage("hu")
        assertEquals(0.75, hu.freqWeight, 0.0)
        assertEquals(0.75, hu.freqWeightWithContext, 0.0)
        assertEquals(2.0f, hu.gapWeight, 0.0f)
        assertEquals(Double.POSITIVE_INFINITY, hu.joinCost, 0.0)
        assertEquals(GlideBeam.Tuning.DEFAULT.joinMinPart, hu.joinMinPart)
        assertEquals(GlideBeam.Tuning.DEFAULT.joinPartPenalty, hu.joinPartPenalty, 0.0)
        assertEquals(0.0, hu.glideLmBlend, 0.0)
        assertEquals(0.0, hu.glideLmBlendLargeList, 0.0)
        val without = GlideLanguageProfiles.tune(GlideBeam.Tuning.DEFAULT, "hu")
        val with = GlideLanguageProfiles.tune(GlideBeam.Tuning.DEFAULT, "hu", hasContext = true)
        assertEquals(without, with)
    }

    @Test
    fun `finnish uses the measured weights and leaves joins off`() {
        val fi = GlideLanguageProfiles.forLanguage("fi")
        assertEquals(0.75, fi.freqWeight, 0.0)
        assertEquals(0.75, fi.freqWeightWithContext, 0.0)
        assertEquals(2.0f, fi.gapWeight, 0.0f)
        // The a-z slice moved 0/1,000 outcomes with joins on, and the app's
        // join path uses a different downloaded lexicon, so joins stay off.
        assertEquals(Double.POSITIVE_INFINITY, fi.joinCost, 0.0)
        assertEquals(GlideBeam.Tuning.DEFAULT.joinMinPart, fi.joinMinPart)
        assertEquals(GlideBeam.Tuning.DEFAULT.joinPartPenalty, fi.joinPartPenalty, 0.0)
        // No Finnish context model ships, so the glide blend stays off on both
        // list sizes and a model-aware decode keeps the same weights.
        assertEquals(0.0, fi.glideLmBlend, 0.0)
        assertEquals(0.0, fi.glideLmBlendLargeList, 0.0)
        val without = GlideLanguageProfiles.tune(GlideBeam.Tuning.DEFAULT, "fi")
        val with = GlideLanguageProfiles.tune(GlideBeam.Tuning.DEFAULT, "fi", hasContext = true)
        assertEquals(without, with)
        assertEquals(0.75, without.freqWeight, 0.0)
    }

    @Test
    fun `mixed german with a context model resolves to the shipped weights`() {
        val mixed = GlideLanguageProfiles.tune(
            GlideBeam.Tuning.DEFAULT, "de", mixed = true, hasContext = true,
        )
        assertEquals(GlideBeam.Tuning.DEFAULT.freqWeight, mixed.freqWeight, 0.0)
        assertEquals(1.0, mixed.freqWeight, 0.0)
    }

    @Test
    fun `a packed trie reports the word count the blend reads`() {
        val one = PackedTrie.of(listOf("one" to 1, "two" to 2))
        assertEquals(2, one.wordCount)
        val two = PackedTrie.of(listOf("two" to 2, "three" to 3))
        // The union sums its members; an overlap may count twice. The blend
        // only needs the size class, not a distinct word total.
        assertEquals(4, CompositeWordSource.of(listOf(one, two)).wordCount)
    }
}
