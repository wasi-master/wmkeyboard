package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The headline counts, measured from the registries themselves.
 *
 * These numbers are quoted in the README, the Play listing, the documentation
 * and a dozen KDoc comments, and every one of those is a copy that goes stale
 * silently. The Keyman conversion moved both by more than a factor of two and
 * left stale figures in all four places, which is what this exists to stop
 * happening again quietly.
 *
 * It is a change detector on purpose. When it fails, the counts really did
 * change, and the fix is to update this file **and** the places listed in
 * [PLACES_THAT_QUOTE_THESE] in the same commit.
 */
class ShippedCountsTest {

    private val assetDir = File("src/main/assets/layouts")

    @Test
    fun `the shipped counts are what everything else claims`() {
        val assets = assetDir.listFiles { f -> f.name.endsWith(".wmlayout.json") }.orEmpty()
        val keyman = assets.count { it.name.startsWith("kmn_") }
        val builtIn = BuiltInLayouts.all.size
        val languages = LanguageRegistry.all.size

        println(
            "shipped: $languages languages, ${builtIn + assets.size} layouts " +
                "($builtIn built-in + ${assets.size} assets, of which $keyman are Keyman)",
        )

        assertEquals("registered languages", LANGUAGES, languages)
        assertEquals("built-in layouts", BUILT_IN_LAYOUTS, builtIn)
        assertEquals("asset layouts", ASSET_LAYOUTS, assets.size)
        assertEquals("converted Keyman layouts", KEYMAN_LAYOUTS, keyman)
        assertEquals("layouts in total", TOTAL_LAYOUTS, builtIn + assets.size)
    }

    /** No language may be counted twice, or the headline figure is a lie. */
    @Test
    fun `every registered language id is unique`() {
        val duplicates = LanguageRegistry.all.map { it.id }
            .groupingBy { it }.eachCount()
            .filterValues { it > 1 }
        assertEquals("duplicate language ids", emptyMap<String, Int>(), duplicates)
    }

    companion object {
        const val LANGUAGES = 1_555
        const val BUILT_IN_LAYOUTS = 39
        const val ASSET_LAYOUTS = 3_649
        const val KEYMAN_LAYOUTS = 862
        const val TOTAL_LAYOUTS = BUILT_IN_LAYOUTS + ASSET_LAYOUTS

        /**
         * Everywhere these numbers are written down by hand. Update all of them
         * together, or the app will advertise one figure and ship another.
         *
         * - `README.md`
         * - `fastlane/metadata/android/en-US/full_description.txt`
         * - `fastlane/play/metadata/android/en-US/full_description.txt`
         * - `docs/CONTENT_GUIDE.md` (the code-verified counts block)
         * - `docs/src/content/docs/index.mdx`
         * - `docs/src/content/docs/languages/overview.mdx`
         * - `docs/src/content/docs/languages/custom-layouts.mdx`
         * - `docs/src/content/docs/reference/settings/languages.mdx`
         * - `docs/src/content/docs/reference/settings/keymaps.mdx`
         * - `FEATURES.md`
         *
         * The docs' language and wordlist tables are generated; rerun
         * `docs/scripts/extract_data.py` rather than editing the JSON it writes
         * under `docs/src/data`.
         */
        val PLACES_THAT_QUOTE_THESE = Unit
    }
}
