package com.wasimaster.wmkeyboard.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Bottom row presets (competitor gap list item 15): one row drawn on every
 * letters layout in place of its own, with the 🌐 and comma settings acting on
 * it afterwards, and the rows a preset cannot stand in for left alone.
 */
class BottomRowPresetTest {

    private val symbols = Key("?123", action = KeyAction.Symbols, width = 1.5f)
    private val comma = Key(",", role = KeyRole.Comma, longPress = listOf("!", "?"))
    private val globe = Key("🌐", action = KeyAction.LanguageSwitch)
    private val space = Key(" ", action = KeyAction.Space, width = 4f)
    private val period = Key(".", role = KeyRole.Period)
    private val enter = Key("⏎", action = KeyAction.Enter, width = 1.5f)

    private fun grid(vararg bottom: Key, laidOut: Boolean = false) = KeyboardLayout(
        name = "t",
        rows = listOf(List(10) { Key("q") }, bottom.toList()),
        bottomRowAsLaidOut = laidOut,
    )

    /** `?123 , 🌐 ␣ . ⏎`, ten units wide. */
    private fun builtIn(laidOut: Boolean = false) = grid(symbols, comma, globe, space, period, enter, laidOut = laidOut)

    private fun ArrangedGrid.bottom() = layout.rows.last()
    private fun List<Key>.labels() = map { it.label }
    private fun List<Key>.widths() = map { it.width }
    private fun KeyboardLayout.arranged(preset: BottomRowPreset, rules: BottomRowRules = BottomRowRules()) =
        arrangedBy(rules.copy(preset = preset))

    @Test
    fun `as laid out changes nothing`() {
        val layout = builtIn()
        val arranged = layout.arranged(BottomRowPreset.AS_LAID_OUT)
        assertSame(layout, arranged.layout)
        assertTrue(arranged.applied.isEmpty())
    }

    @Test
    fun `classic drops the globe and widens the spacebar`() {
        val arranged = builtIn().arranged(BottomRowPreset.CLASSIC)
        assertEquals(listOf("?123", ",", " ", ".", "⏎"), arranged.bottom().labels())
        assertEquals(listOf(1.5f, 1f, 5f, 1f, 1.5f), arranged.bottom().widths())
        assertEquals(setOf(BottomRowRule.PRESET), arranged.applied)
    }

    @Test
    fun `iphone keeps the globe beside the symbols key`() {
        val row = builtIn().arranged(BottomRowPreset.IPHONE).bottom()
        assertEquals(listOf("?123", "🌐", " ", "⏎"), row.labels())
        assertEquals(listOf(1.5f, 1.5f, 5f, 2f), row.widths())
    }

    @Test
    fun `minimal keeps only the three keys`() {
        val row = builtIn().arranged(BottomRowPreset.MINIMAL).bottom()
        assertEquals(listOf(KeyAction.Symbols, KeyAction.Space, KeyAction.Enter), row.map { it.action })
        assertEquals(listOf(1.5f, 6.5f, 2f), row.widths())
    }

    @Test
    fun `with emoji adds an emoji key and keeps the globe`() {
        val row = builtIn().arranged(BottomRowPreset.WITH_EMOJI).bottom()
        assertEquals(
            listOf(KeyAction.Symbols, KeyAction.Emoji, KeyAction.Text, KeyAction.LanguageSwitch,
                KeyAction.Space, KeyAction.Text, KeyAction.Enter),
            row.map { it.action },
        )
        assertEquals(10f, row.sumOf { it.width.toDouble() }.toFloat(), 0.001f)
    }

    @Test
    fun `the authored keys are reused, holds and all`() {
        val row = builtIn().arranged(BottomRowPreset.CLASSIC).bottom()
        assertSame(symbols, row[0])
        assertSame(comma, row[1])
        assertSame(enter, row[4])
    }

    @Test
    fun `an arabic comma stays the comma`() {
        val arabicComma = Key("،", role = KeyRole.Comma, longPress = listOf("؟", "؛"))
        val row = grid(symbols, arabicComma, globe, space, period, enter).arranged(BottomRowPreset.CLASSIC).bottom()
        assertEquals("،", row[1].label)
    }

    @Test
    fun `a row without punctuation gets fresh comma and period keys`() {
        val layout = grid(symbols, space.copy(width = 7f), enter)
        val arranged = layout.arranged(BottomRowPreset.CLASSIC)
        val row = arranged.bottom()
        assertEquals(listOf("?123", ",", " ", ".", "⏎"), row.labels())
        assertEquals(KeyRole.Comma, row[1].role)
        assertEquals(KeyRole.Period, row[3].role)
        // A fresh key stands for nothing stored, so the editor bakes the row first.
        assertNull(arranged.sourceColumn(1, 1))
        assertTrue(arranged.rewritten(1, 1))
        assertEquals(0, arranged.sourceColumn(1, 0))
    }

    @Test
    fun `a narrower row keeps its own total`() {
        val half = grid(*listOf(symbols, comma, globe, space, period, enter).map { it.copy(width = it.width / 2) }.toTypedArray())
        val row = half.arranged(BottomRowPreset.MINIMAL).bottom()
        assertEquals(listOf(0.75f, 3.25f, 1f), row.widths())
    }

    @Test
    fun `a row laid out by hand is left alone`() {
        val layout = builtIn(laidOut = true)
        assertSame(layout, layout.arranged(BottomRowPreset.MINIMAL).layout)
    }

    @Test
    fun `a row that types letters is the layout's own`() {
        val letter = Key("ক")
        val layout = grid(symbols, letter, globe, space, period, enter)
        assertSame(layout, layout.arranged(BottomRowPreset.MINIMAL).layout)
    }

    @Test
    fun `a combining mark on the bottom row keeps it too`() {
        val virama = Key("্")
        val layout = grid(symbols, virama, space, period, enter)
        assertSame(layout, layout.arranged(BottomRowPreset.CLASSIC).layout)
    }

    @Test
    fun `pad and kana rows are skipped`() {
        val flick = Key("、", flick = mapOf(FlickDirection.UP to "。"))
        val kana = grid(symbols, flick, space, enter)
        assertSame(kana, kana.arranged(BottomRowPreset.MINIMAL).layout)
        val reading = grid(symbols, Key("変換", action = KeyAction.ReadingColumn), space, enter)
        assertSame(reading, reading.arranged(BottomRowPreset.MINIMAL).layout)
        val noEnter = grid(symbols, comma, space, period)
        assertSame(noEnter, noEnter.arranged(BottomRowPreset.MINIMAL).layout)
    }

    @Test
    fun `a key spanning rows keeps the row as it is`() {
        val tall = KeyboardLayout(
            name = "t",
            rows = listOf(
                List(9) { Key("q") } + Key("⏎", action = KeyAction.Enter, rowSpan = 2),
                listOf(symbols, comma, globe, space, period),
            ),
        )
        assertSame(tall, tall.arranged(BottomRowPreset.CLASSIC).layout)
    }

    @Test
    fun `hiding the globe gives its width to the spacebar after the preset`() {
        val row = builtIn().arranged(BottomRowPreset.IPHONE, BottomRowRules(hideGlobe = true)).bottom()
        assertEquals(listOf("?123", " ", "⏎"), row.labels())
        assertEquals(listOf(1.5f, 6.5f, 2f), row.widths())
    }

    @Test
    fun `the globe can still be the emoji key on the iphone row`() {
        val row = builtIn().arranged(BottomRowPreset.IPHONE, BottomRowRules(globeAsEmoji = true)).bottom()
        assertEquals(KeyAction.Emoji, row[1].action)
    }

    @Test
    fun `the with emoji row never draws two emoji keys`() {
        val arranged = builtIn().arranged(
            BottomRowPreset.WITH_EMOJI,
            BottomRowRules(globeAsEmoji = true, commaAsEmoji = true),
        )
        val row = arranged.bottom()
        assertEquals(1, row.count { it.action == KeyAction.Emoji })
        assertEquals(1, row.count { it.action == KeyAction.LanguageSwitch })
        assertTrue(arranged.ownEmojiKey)
        assertFalse(builtIn().arranged(BottomRowPreset.CLASSIC).ownEmojiKey)
    }

    @Test
    fun `the comma swap still runs on the preset row`() {
        val row = builtIn().arranged(BottomRowPreset.WITH_EMOJI, BottomRowRules(swapCommaAndGlobe = true)).bottom()
        assertEquals(listOf("?123", "☺", "🌐", ",", " ", ".", "⏎"), row.labels())
    }

    @Test
    fun `the globe placement stands down under a preset`() {
        val arranged = builtIn().arranged(
            BottomRowPreset.IPHONE,
            BottomRowRules(globeInOnePlace = true, swapCommaAndGlobe = true),
        )
        assertFalse(BottomRowRule.GLOBE_IN_ONE_PLACE in arranged.applied)
        assertEquals(listOf(1.5f, 1.5f, 5f, 2f), arranged.bottom().widths())
    }

    @Test
    fun `a preset name from a newer build reads as laid out`() {
        assertEquals(BottomRowPreset.AS_LAID_OUT, BottomRowPreset.parse("HEXAGONAL"))
        assertEquals(BottomRowPreset.AS_LAID_OUT, BottomRowPreset.parse(null))
        assertEquals(BottomRowPreset.MINIMAL, BottomRowPreset.parse("MINIMAL"))
    }
}
