package com.wasimaster.wmkeyboard.core.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An addon with variants is one manifest entry offering several builds of
 * itself: these pin what the codec keeps and how a variant becomes an entry
 * of its own, the way [AddonLocalizationTest] does for languages.
 */
class AddonVariantTest {

    private fun manifest(addons: String) = """
        { "format": "wmkeyboard-repo", "repo": { "id": "test" }, "addons": [ $addons ] }
    """.trimIndent()

    private val fluent = """
        {
          "id": "fluent-emoji", "type": "emoji_font", "name": "Fluent Emoji", "version": "1.1.0",
          "description": "Microsoft's emoji.", "path": "fonts/fluent-emoji.ttf", "variantName": "3D",
          "previews": ["previews/fluent.jpg"], "license": "MIT",
          "variants": [
            { "id": "flat", "name": "Flat", "path": "fonts/fluent-flat.ttf", "sizeBytes": 15,
              "previews": ["previews/fluent-flat.jpg"] },
            { "id": "high-contrast", "path": "fonts/fluent-hc.ttf", "version": "1.2.0",
              "description": "Black and white.", "minAppVersion": 46 },
            { "id": "flat", "path": "fonts/duplicate.ttf" },
            { "id": "has space", "path": "fonts/x.ttf" },
            { "id": "empty", "path": "" }
          ]
        }
    """.trimIndent()

    private fun decodeFluent(): AddonEntry = AddonRepoCodec.decode(manifest(fluent))!!.addons.single()

    @Test
    fun `the codec keeps well formed variants once each`() {
        assertEquals(listOf("flat", "high-contrast"), decodeFluent().variants.map { it.id })
    }

    @Test
    fun `the entry's own build comes first, then each variant`() {
        val entry = decodeFluent()
        assertTrue(entry.hasStyles)
        assertFalse(entry.isLocalized)
        assertEquals(
            listOf("fluent-emoji", "fluent-emoji@flat", "fluent-emoji@high-contrast"),
            entry.installables.map { it.id },
        )
        assertEquals("test/fluent-emoji@flat", entry.installables[1].key("test"))
    }

    @Test
    fun `a variant inherits what it does not set and carries its own name`() {
        val (_, flat, contrast) = decodeFluent().installables
        assertEquals("Fluent Emoji (Flat)", flat.name)
        assertEquals("Flat", flat.variantName)
        assertEquals("1.1.0", flat.version)
        assertEquals("Microsoft's emoji.", flat.description)
        assertEquals(listOf("previews/fluent-flat.jpg"), flat.previews)
        assertEquals(15L, flat.sizeBytes)
        assertEquals("MIT", flat.license)
        assertTrue(flat.variants.isEmpty())

        // No name falls back to the id; its own fields win.
        assertEquals("Fluent Emoji (high-contrast)", contrast.name)
        assertEquals("1.2.0", contrast.version)
        assertEquals("Black and white.", contrast.description)
        assertEquals(listOf("previews/fluent.jpg"), contrast.previews)
        assertEquals(46, contrast.minAppVersion)
    }

    @Test
    fun `a variant is not a language`() {
        // The id after the separator is a variant id, not a locale to file
        // a dictionary under or to label an installed row with.
        val flat = decodeFluent().installables[1]
        assertEquals("", flat.locale)
    }

    @Test
    fun `findVariant resolves a variant id`() {
        val addons = AddonRepoCodec.decode(manifest(fluent))!!.addons
        assertEquals("fonts/fluent-flat.ttf", addons.findVariant("fluent-emoji@flat")?.path)
        assertNull(addons.findVariant("fluent-emoji@missing"))
    }

    @Test
    fun `variants are dropped for a per-language type`() {
        val dictionary = """
            { "id": "medical", "type": "dictionary", "path": "en.txt", "langId": "en",
              "variants": [ { "id": "big", "path": "en-big.txt" } ] }
        """.trimIndent()
        val entry = AddonRepoCodec.decode(manifest(dictionary))!!.addons.single()
        assertTrue(entry.variants.isEmpty())
        assertFalse(entry.hasStyles)
        assertEquals(listOf(entry), entry.installables)
    }
}
