package com.wasimaster.wmkeyboard.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layout registry replaced a stored `InputMode` with a stored layout id.
 * Nothing is rewritten on disk — the old preference is translated on read — so
 * these pin that an existing install lands exactly where it left off, and that
 * the derived language of the active/enabled layouts is right.
 */
class LayoutSelectionTest {

    private fun select(
        layoutId: String? = null,
        inputMode: String? = null,
        enabledIds: String? = null,
        enabledModes: String? = null,
        custom: List<LayoutSpec> = emptyList(),
    ) = resolveLayoutSelection(layoutId, inputMode, enabledIds, enabledModes, custom)

    private val pad = LayoutSpec(
        id = "custom_pad",
        name = "Pad",
        langId = "en",
        secondary = true,
        layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(listOf(listOf(Key("a"))))),
    )

    /** Issue #62: a secondary layout is shown over a language layout, never as one. */
    @Test
    fun `a secondary layout is never enabled or active`() {
        val s = select(
            layoutId = "custom_pad",
            enabledIds = "custom_pad,${BuiltInLayouts.QWERTY_ID}",
            custom = listOf(pad),
        )
        assertEquals(listOf(BuiltInLayouts.QWERTY_ID), s.enabledLayoutIds)
        assertEquals(BuiltInLayouts.QWERTY_ID, s.active.id)
    }

    @Test
    fun `an enabled list holding only secondary layouts falls back to the defaults`() {
        val s = select(enabledIds = "custom_pad", custom = listOf(pad))
        assertEquals(BuiltInLayouts.defaultEnabledIds, s.enabledLayoutIds)
    }

    @Test
    fun `a fresh install gets the shipped defaults`() {
        val s = select()
        assertEquals(BuiltInLayouts.QWERTY_ID, s.active.id)
        assertEquals("en", s.active.language().id)
        assertEquals(BuiltInLayouts.defaultEnabledIds, s.enabledLayoutIds)
    }

    /** The migration case: an install typing Probhat before the registry existed. */
    @Test
    fun `a stored input mode translates to its built-in layout`() {
        val s = select(inputMode = "PROBHAT")
        assertEquals(BuiltInLayouts.PROBHAT_ID, s.active.id)
        assertEquals(
            "the derived language has to match what was stored, or the dictionary changes",
            "bn",
            s.active.language().id,
        )
    }

    @Test
    fun `stored enabled modes translate to their built-in layouts`() {
        val s = select(enabledModes = "ENGLISH,PROBHAT,JATIYA")
        assertEquals(
            listOf(BuiltInLayouts.QWERTY_ID, BuiltInLayouts.PROBHAT_ID, BuiltInLayouts.JATIYA_ID),
            s.enabledLayoutIds,
        )
        // Probhat and Jatiya are both Bengali, so the languages dedupe to two.
        assertEquals(listOf("en", "bn"), s.enabledLanguages.map { it.id })
    }

    @Test
    fun `a stored layout id wins over the legacy input mode`() {
        val s = select(layoutId = BuiltInLayouts.DVORAK_ID, inputMode = "PROBHAT")
        assertEquals(BuiltInLayouts.DVORAK_ID, s.active.id)
        assertEquals("en", s.active.language().id)
    }

    @Test
    fun `an unparseable stored mode falls back to the default`() {
        assertEquals(BuiltInLayouts.QWERTY_ID, select(inputMode = "KLINGON").active.id)
    }

    @Test
    fun `a custom layout supplies its own language`() {
        val mine = LayoutSpec(id = "custom_1", name = "My Bengali", langId = "bn")
        val s = select(layoutId = "custom_1", custom = listOf(mine))
        assertEquals("custom_1", s.active.id)
        assertEquals(
            "a custom layout inherits everything language-shaped from its langId",
            "bn",
            s.active.language().id,
        )
    }

    @Test
    fun `an active id whose layout was deleted heals to the default`() {
        val s = select(layoutId = "custom_gone")
        assertEquals(BuiltInLayouts.DEFAULT_ID, s.active.id)
    }

    /**
     * The reason the cycler works on ids rather than languages: three layouts all
     * typing English are three distinct stops. Cycling languages would collapse
     * them to one and make two of them unreachable from the keyboard.
     */
    @Test
    fun `several custom layouts sharing a language stay distinct`() {
        val a = LayoutSpec(id = "custom_a", name = "A", langId = "en")
        val b = LayoutSpec(id = "custom_b", name = "B", langId = "en")
        val s = select(enabledIds = "custom_a,custom_b", custom = listOf(a, b))
        assertEquals(listOf("custom_a", "custom_b"), s.enabledLayoutIds)
        assertEquals("but they collapse to one language", listOf("en"), s.enabledLanguages.map { it.id })
    }

    @Test
    fun `an empty enabled list never yields an empty language list`() {
        val s = select(enabledIds = ",,,")
        assertTrue(
            "hintedLanguage and the FORCE_ASCII fallback have no ifEmpty guard of their own",
            s.enabledLanguages.isNotEmpty(),
        )
    }

    @Test
    fun `an edited built-in keeps its slot and its id`() {
        val edited = BuiltInLayouts.PROBHAT.copy(name = "My Probhat")
        val s = select(layoutId = BuiltInLayouts.PROBHAT_ID, custom = listOf(edited))
        assertEquals(BuiltInLayouts.PROBHAT_ID, s.active.id)
        assertEquals("My Probhat", s.active.name)
        assertEquals("bn", s.active.language().id)
    }

    // ---- the fancy collapse: 22 per-style layouts became one -------------

    @Test
    fun `stored per-style fancy ids collapse to the one fancy layout`() {
        assertEquals(AssetLayouts.FANCY_ID, canonicalLayoutId("asset_fancy_fraktur", emptyList()))
        val s = select(
            layoutId = "asset_fancy_fraktur",
            enabledIds = BuiltInLayouts.QWERTY_ID +
                ",asset_fancy_bold,asset_fancy_italic,asset_fancy_fraktur",
        )
        // The enabled list is asserted rather than the resolved active spec:
        // asset layouts are not loaded in a JVM test, so the active id would
        // heal to the default here either way.
        assertEquals(
            "three enabled styles dedupe to one layout, not three copies of it",
            listOf(BuiltInLayouts.QWERTY_ID, AssetLayouts.FANCY_ID),
            s.enabledLayoutIds,
        )
    }

    @Test
    fun `a custom layout shadowing a legacy fancy id keeps resolving to itself`() {
        val edited = LayoutSpec(id = "asset_fancy_bold", name = "My Bold", langId = "fancy")
        val s = select(
            layoutId = "asset_fancy_bold",
            enabledIds = "asset_fancy_bold",
            custom = listOf(edited),
        )
        assertEquals(
            "the user's edited copy of the style still exists and wins its id",
            "My Bold",
            s.active.name,
        )
        assertEquals(listOf("asset_fancy_bold"), s.enabledLayoutIds)
    }

    @Test
    fun `canonicalLayoutId leaves every other id alone`() {
        assertEquals("qwerty", canonicalLayoutId("qwerty", emptyList()))
        assertEquals(AssetLayouts.FANCY_ID, canonicalLayoutId(AssetLayouts.FANCY_ID, emptyList()))
        assertEquals("custom_1", canonicalLayoutId("custom_1", emptyList()))
    }

    // ---- where the Fancy tool lands when it turns fancy off --------------

    @Test
    fun `the fancy tool goes back to the layout it came from`() {
        assertEquals(
            BuiltInLayouts.PROBHAT_ID,
            layoutAfterFancy(
                BuiltInLayouts.PROBHAT_ID,
                listOf(BuiltInLayouts.QWERTY_ID, BuiltInLayouts.PROBHAT_ID),
            ),
        )
    }

    @Test
    fun `a layout turned off while fancy was on is not landed on`() {
        assertEquals(
            BuiltInLayouts.QWERTY_ID,
            layoutAfterFancy(BuiltInLayouts.PROBHAT_ID, listOf(BuiltInLayouts.QWERTY_ID)),
        )
    }

    @Test
    fun `fancy as the only enabled layout still lands somewhere`() {
        assertEquals(BuiltInLayouts.DEFAULT_ID, layoutAfterFancy(null, emptyList()))
        assertEquals(BuiltInLayouts.DEFAULT_ID, layoutAfterFancy(AssetLayouts.FANCY_ID, emptyList()))
    }
}
