package com.wasimaster.wmkeyboard.core.layout

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Hebrew Gboard+ layout and its swipe-safe twin (#794, #836). Each twin is its
 * plain layout with the flicks on the bottom letter row taken off, so a glide
 * starting there is never read as a flick, and nothing else:
 *
 * | Layout | Bottom-row letters | Plain | Swipe-safe |
 * |---|---|---|---|
 * | he | ז ס ב ה נ מ צ | flick down `*` `"` `'` `:` `;` `!` `?`, flick up z x c v b n m | no flicks |
 * | he | ת ץ | flick down `,` `.` | no flicks |
 *
 * The English pair moved to the addon repository (`gboard-plus`).
 */
class GboardPlusTwinsTest {

    private fun spec(stem: String): LayoutSpec =
        LayoutFile.decode(File("src/main/assets/layouts/$stem.wmlayout.json").readText())!!.layout

    private fun letters(spec: LayoutSpec): LayerSpec = spec.layers.getValue(LayoutLayer.LETTERS.key)

    /** [spec] with the bottom letter row's flicks taken off, and no name of its own. */
    private fun withoutBottomFlicks(spec: LayoutSpec): LayoutSpec {
        val layer = letters(spec)
        val rows = layer.rows.mapIndexed { r, row ->
            if (r != BOTTOM_LETTERS) row else row.map { if (it.action == KeyAction.Text) it.copy(flick = emptyMap()) else it }
        }
        return spec.copy(id = "", name = "", layers = spec.layers + (LayoutLayer.LETTERS.key to layer.copy(rows = rows)))
    }

    @Test
    fun `each swipe-safe twin is its plain layout without the bottom row's flicks`() {
        for (lang in listOf("he")) {
            val plain = spec("${lang}_gboard_plus")
            val safe = spec("${lang}_gboard_plus_swipe_safe")
            assertEquals(lang, withoutBottomFlicks(plain), safe.copy(id = "", name = ""))
        }
    }

    @Test
    fun `no letter of a swipe-safe twin has a flick`() {
        for (lang in listOf("he")) {
            val keys = letters(spec("${lang}_gboard_plus_swipe_safe")).rows.flatten()
            assertTrue(lang, keys.none { it.flick.isNotEmpty() })
        }
    }

    @Test
    fun `the Hebrew bottom row flicks down to its marks and up to its English letter`() {
        val row = letters(spec("he_gboard_plus")).rows[BOTTOM_LETTERS].associateBy { it.label }
        val english = "zxcvbnm".map(Char::toString)
        for ((i, label) in listOf("ז", "ס", "ב", "ה", "נ", "מ", "צ").withIndex()) {
            assertEquals(
                label,
                mapOf(FlickDirection.DOWN to MARKS[i], FlickDirection.UP to english[i]),
                row.getValue(label).flick,
            )
        }
        assertEquals(mapOf(FlickDirection.DOWN to ","), row.getValue("ת").flick)
        assertEquals(mapOf(FlickDirection.DOWN to "."), row.getValue("ץ").flick)
    }

    private companion object {
        const val BOTTOM_LETTERS = 2
        val MARKS = listOf("*", "\"", "'", ":", ";", "!", "?")
    }
}
