package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled Gboard+ Hebrew grids have no Shift key; each letter's shifted
 * form is the English letter on its Israeli SI-1452 desktop position, reachable
 * from long-press (second entry, after the symbol) and, where the key already
 * owns its flicks, an up-flick.
 */
class HebrewGboardPlusAssetTest {

    private val positional = mapOf(
        "'" to "q", "-" to "w", "ק" to "e", "ר" to "r", "א" to "t", "ט" to "y", "ו" to "u", "ן" to "i", "ם" to "o", "פ" to "p",
        "ש" to "a", "ד" to "s", "ג" to "d", "כ" to "f", "ע" to "g", "י" to "h", "ח" to "j", "ל" to "k", "ך" to "l",
        "ז" to "z", "ס" to "x", "ב" to "c", "ה" to "v", "נ" to "b", "מ" to "n", "צ" to "m",
    )

    private fun letters(stem: String): List<Key> =
        LayoutFile.decode(File("src/main/assets/layouts/$stem.wmlayout.json").readText())!!
            .layout.compile(LayoutLayer.LETTERS).rows.flatten()

    @Test
    fun `no shift key, and every SI-1452 letter shifts to its English letter`() {
        for (stem in listOf("he_gboard_plus", "he_gboard_plus_swipe_safe")) {
            val keys = letters(stem)
            assertTrue("$stem has a shift key", keys.none { it.action == KeyAction.Shift })
            val shifted = keys.filter { it.label in positional }.associate { it.label to it.shiftLabel }
            assertEquals(stem, positional, shifted)
            keys.filter { it.label in positional }.forEach { key ->
                assertEquals("$stem ${key.label} long-press", positional[key.label], key.longPress[1])
                if (key.flick.isNotEmpty()) {
                    assertEquals("$stem ${key.label} up-flick", positional[key.label], key.flick[FlickDirection.UP])
                }
            }
            assertEquals("$stem English letters", ('a'..'z').map { it.toString() }.toSet(), shifted.values.toSet())
        }
    }

    @Test
    fun `every bundled Gboard+ layout is listed under its language`() {
        val listed = mapOf(
            "he" to listOf(AssetLayouts.HE_GBOARD_PLUS_ID, AssetLayouts.HE_GBOARD_PLUS_SWIPE_SAFE_ID),
        )
        for ((lang, ids) in listed) {
            val layoutIds = LanguageRegistry.byId(lang).layoutIds
            for (id in ids) {
                assertTrue("$id missing from $lang", id in layoutIds)
                val file = File("src/main/assets/layouts/${id.removePrefix("asset_")}.wmlayout.json")
                assertEquals(id, id, LayoutFile.decode(file.readText())!!.layout.id)
                assertEquals(id, lang, LanguageRegistry.languageOf(id).id)
            }
        }
    }
}