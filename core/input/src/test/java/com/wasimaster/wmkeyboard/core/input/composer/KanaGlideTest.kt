package com.wasimaster.wmkeyboard.core.input.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a swipe over Kana JIS can spell (#958), and the reading each spelling
 * goes into the buffer as.
 */
class KanaGlideTest {

    private fun pack(vararg rows: String): ConversionDictionary = ConversionDictionary.parse(rows.asSequence())

    @Test
    fun `marks and small kana come off for the unmarked spelling`() {
        assertEquals("かつこう", KanaGlide.unmarked("がっこう"))
        assertEquals("はそこん", KanaGlide.unmarked("ぱそこん"))
        assertEquals("きよう", KanaGlide.unmarked("きょう"))
        assertEquals("うあいおりん", KanaGlide.unmarked("ゔぁいおりん"))
        assertEquals("こーひー", KanaGlide.unmarked("こーひー"))
    }

    @Test
    fun `marks are drawn through their keys and small kana stay full size`() {
        assertEquals("か゛つこう", KanaGlide.drawn("がっこう"))
        assertEquals("は゜そこん", KanaGlide.drawn("ぱそこん"))
        assertEquals("う゛あいおりん", KanaGlide.drawn("ゔぁいおりん"))
        // Nothing to draw: the unmarked spelling is the only one.
        assertNull(KanaGlide.drawn("きょう"))
        assertNull(KanaGlide.drawn("わたし"))
    }

    @Test
    fun `a drawn spelling reads back with its marks`() {
        assertEquals("がつこう", KanaGlide.reading("か゛つこう"))
        assertEquals("ぱそこん", KanaGlide.reading("は゜そこん"))
        assertEquals("ゔあいおりん", KanaGlide.reading("う゛あいおりん"))
        // The lattice restores what the stroke could not draw.
        assertEquals("かつこう", KanaGlide.reading("かつこう"))
    }

    @Test
    fun `a mark the kana before it cannot take is dropped`() {
        assertEquals("なに", KanaGlide.reading("な゛に"))
        assertEquals("か", KanaGlide.reading("か゜"))
        assertEquals("か", KanaGlide.reading("゛か"))
    }

    @Test
    fun `every reading is offered unmarked, the common ones drawn too`() {
        val spellings = KanaGlide.spellings(
            pack(
                "がっこう\t学校\t26742",
                "ぱん\tパン\t24500",
                "わたし\t私\t30000",
            ),
        ).toMap()
        assertTrue("かつこう" in spellings)
        assertTrue("か゛つこう" in spellings)
        assertTrue("わたし" in spellings)
        assertTrue("はん" in spellings)
        // Common enough to swipe, not to spend a second spelling on.
        assertFalse("は゜ん" in spellings)
    }

    @Test
    fun `rare, one-kana and non-kana readings are left out`() {
        val spellings = KanaGlide.spellings(
            pack(
                "かつこう\t葛洪\t23287",
                "て\t手\t29000",
                "えーびーしー\tABC\t24500",
                "ゝ\tゝ\t30000",
            ),
        ).toMap()
        assertTrue("えーひーしー" in spellings)
        assertEquals(1, spellings.size)
    }

    @Test
    fun `a spelling keeps the weight of its commonest reading and weights stay apart`() {
        val spellings = KanaGlide.spellings(
            pack(
                "かき\t柿\t25000",
                "かぎ\t鍵\t26500",
                "かき\t書き\t28000",
            ),
        ).toMap()
        val kaki = spellings.getValue("かき")
        val kagi = spellings.getValue("かき゛")
        assertTrue(kaki > kagi)
        // The table's scores are a log already; 1500 apart is a real gap.
        assertTrue(kaki / kagi.toDouble() > 10.0)
    }

    @Test
    fun `every spelling is drawn from the keys`() {
        val spellings = KanaGlide.spellings(
            pack("がっこう\t学校\t26742", "ぴゃく\t百\t25000", "でんしゃ\t電車\t26007"),
        )
        for ((spelling, _) in spellings) {
            assertTrue(spelling, spelling.all { it in KanaGlide.KEYS })
        }
    }

    @Test
    fun `a dakuten drawn over the shift key reads like one drawn over its own key`() {
        val viaShift = "か${KanaGlide.SHIFT_DAKUTEN}つこう"
        assertEquals("がつこう", KanaGlide.reading(viaShift))
        val spellings = KanaGlide.spellings(pack("がっこう\t学校\t26742", "ぱん\tパン\t25500")).toMap()
        assertEquals(spellings["か゛つこう"], spellings[viaShift])
        // The handakuten has no place on shift.
        assertTrue("は゜ん" in spellings)
        assertFalse(spellings.keys.any { KanaGlide.SHIFT_DAKUTEN in it && '゜' in it })
    }

    @Test
    fun `counted readings weigh their counts and lead uncounted ones`() {
        val table = pack("がっこう\t学校\t26742", "かつこう\t恰好\t27500", "ぱん\tパン\t25500")
        val counts = KanaGlide.readCounts(sequenceOf("がっこう\t3890", "not a row", "ぱん\tx"))
        assertEquals(mapOf("がっこう" to 3890), counts)
        val plain = KanaGlide.spellings(table).toMap()
        val counted = KanaGlide.spellings(table, counts).toMap()
        // Without counts the table's score decides: 恰好's 27500 is e^7 and leads the fold.
        assertEquals(1097, plain["かつこう"])
        // With them がっこう weighs its count, and the uncounted 恰好 falls under it.
        assertEquals(3890, counted["かつこう"])
        assertEquals(3890, counted["か゛つこう"])
        assertTrue(counted.getValue("はん") < 3890)
    }
}
