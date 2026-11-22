package com.wasimaster.wmkeyboard.core.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A localized addon is one manifest entry that installs as several: these pin
 * what the codec keeps, how a localization becomes an entry of its own, and
 * which languages a one-tap install picks.
 */
class AddonLocalizationTest {

    private fun manifest(addons: String) = """
        { "format": "wmkeyboard-repo", "repo": { "id": "test" }, "addons": [ $addons ] }
    """.trimIndent()

    private val medical = """
        {
          "id": "medical", "type": "dictionary", "name": "Medical", "version": "1.0.0",
          "path": "dictionaries/medical/en.txt", "langId": "en",
          "localizations": [
            { "locale": "es", "path": "dictionaries/medical/es.txt", "name": "Médico" },
            { "locale": "es-419", "path": "dictionaries/medical/es-419.txt", "version": "1.1.0" },
            { "locale": "pt-BR", "path": "dictionaries/medical/pt-BR.txt" },
            { "locale": "en", "path": "dictionaries/medical/en-again.txt" },
            { "locale": "es-419", "path": "dictionaries/medical/duplicate.txt" },
            { "locale": "Not a locale", "path": "x.txt" },
            { "locale": "fr", "path": "" }
          ]
        }
    """.trimIndent()

    private fun decodeMedical(): AddonEntry =
        AddonRepoCodec.decode(manifest(medical))!!.addons.single()

    @Test
    fun `the codec keeps well formed localizations once each`() {
        val locales = decodeMedical().localizations.map { it.locale }
        // The base language, the duplicate, the malformed tag and the empty
        // path all drop; the addon itself stays.
        assertEquals(listOf("es", "es-419", "pt-BR"), locales)
    }

    @Test
    fun `localizations are dropped for a type with no language folder`() {
        val theme = """
            { "id": "dusk", "type": "theme", "path": "dusk.wmtheme.json",
              "localizations": [ { "locale": "es", "path": "dusk-es.wmtheme.json" } ] }
        """.trimIndent()
        val entry = AddonRepoCodec.decode(manifest(theme))!!.addons.single()
        assertTrue(entry.localizations.isEmpty())
        assertFalse(entry.isLocalized)
        assertEquals(listOf(entry), entry.installables)
    }

    @Test
    fun `an entry id holding the separator is refused`() {
        val bad = """{ "id": "medical@es", "type": "dictionary", "path": "a.txt", "langId": "es" }"""
        assertTrue(AddonRepoCodec.decode(manifest(bad))!!.addons.isEmpty())
    }

    @Test
    fun `a localization installs as its own entry`() {
        val entry = decodeMedical()
        val latam = entry.installables.single { it.locale == "es-419" }
        assertEquals("medical@es-419", latam.id)
        assertEquals("test/medical@es-419", latam.key("test"))
        assertEquals("es", latam.langId)
        assertEquals("dictionaries/medical/es-419.txt", latam.path)
        assertEquals("1.1.0", latam.version)
        assertEquals("Medical", latam.name)
        assertFalse(latam.isLocalized)
        assertEquals("Médico", entry.installables.single { it.locale == "es" }.name)
        assertEquals("1.0.0", entry.installables.single { it.locale == "es" }.version)
        assertEquals("en", entry.installables.first().locale)
    }

    @Test
    fun `findVariant resolves both the parent and its languages`() {
        val addons = listOf(decodeMedical())
        assertEquals("medical", addons.findVariant("medical")?.id)
        assertEquals("pt", addons.findVariant("medical@pt-BR")?.langId)
        assertNull(addons.findVariant("medical@de"))
        assertNull(addons.findVariant("legal@es"))
    }

    @Test
    fun `one tap picks one list per keyboard language by the phone's region`() {
        val entry = decodeMedical()
        fun pick(langs: List<String>, device: List<String>) =
            AddonLocales.pick(entry, langs, device).map { it.locale }

        assertEquals(listOf("en", "es-419"), pick(listOf("en", "es"), listOf("es-MX")))
        // Android's Latin American Spanish is es-US.
        assertEquals(listOf("es-419"), pick(listOf("es"), listOf("en-GB", "es-US")))
        assertEquals(listOf("es"), pick(listOf("es"), listOf("es-ES")))
        assertEquals(listOf("es"), pick(listOf("es"), emptyList()))
        assertEquals(listOf("pt-BR"), pick(listOf("pt"), listOf("pt-PT")))
        // Nothing in common: the base list, so the tap still installs something.
        assertEquals(listOf("en"), pick(listOf("bn"), listOf("bn-BD")))
    }

    @Test
    fun `regionOf skips a script subtag`() {
        assertEquals("TW", AddonLocales.regionOf("zh-Hant-TW"))
        assertEquals("419", AddonLocales.regionOf("es-419"))
        assertEquals("", AddonLocales.regionOf("es"))
    }
}
