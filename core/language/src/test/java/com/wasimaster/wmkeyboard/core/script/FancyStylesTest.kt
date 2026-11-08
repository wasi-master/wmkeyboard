package com.wasimaster.wmkeyboard.core.script

import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Fancy Text style table. The glyphs were lifted from the retired
 * per-style layouts, so the spot checks below pin a few of their exact
 * values — including the awkward ones: astral pairs, combining marks, the
 * capital-less small caps, and superscript's plain-q fallback.
 */
class FancyStylesTest {

    @Test
    fun `normal plus the 50 styles and the two starting zalgo levels are present, in order`() {
        assertEquals(53, FancyStyles.all.size)
        assertEquals(FancyStyles.NORMAL_ID, FancyStyles.all.first().id)
        assertEquals("bold", FancyStyles.all[1].id)
        // The tail of the original 22, then the tail of the whole table: the
        // order is append-only, and both ends pin it.
        assertEquals("underline", FancyStyles.all[22].id)
        assertEquals("bypass", FancyStyles.all[32].id)
        assertEquals("zalgo_heavy", FancyStyles.all.last().id)
        assertNotNull(FancyStyles.byId(FancyStyles.DEFAULT_ID))
        assertEquals(FancyStyles.all.size, FancyStyles.all.distinctBy { it.id }.size)
    }

    @Test
    fun `normal leaves text exactly as typed`() {
        val normal = FancyStyles.byId(FancyStyles.NORMAL_ID)!!
        assertEquals("Hello World! 123", FancyStyles.transform("Hello World! 123", normal))
    }

    @Test
    fun `every style maps all 26 letters in both cases`() {
        for (style in FancyStyles.all) {
            assertEquals(style.id, 26, style.lower.size)
            assertEquals(style.id, 26, style.upper.size)
            for (c in 'a'..'z') assertTrue(style.id, style.lower.getValue(c).isNotEmpty())
            for (c in 'A'..'Z') assertTrue(style.id, style.upper.getValue(c).isNotEmpty())
        }
    }

    @Test
    fun `bold maps onto the mathematical bold block`() {
        val bold = FancyStyles.byId("bold")!!
        assertEquals("𝐚", bold.lower.getValue('a'))
        assertEquals("𝐀", bold.upper.getValue('A'))
        assertEquals("𝐇𝐞𝐥𝐥𝐨 𝐖𝐨𝐫𝐥𝐝!", FancyStyles.transform("Hello World!", bold))
    }

    @Test
    fun `small caps has no capitals of its own`() {
        val smallCaps = FancyStyles.byId("small_caps")!!
        for (c in 'a'..'z') {
            assertEquals(
                smallCaps.lower.getValue(c),
                smallCaps.upper.getValue(c.uppercaseChar()),
            )
        }
    }

    @Test
    fun `superscript falls back to the plain letter where no form exists`() {
        val superscript = FancyStyles.byId("superscript")!!
        assertEquals("q", superscript.lower.getValue('q'))
    }

    @Test
    fun `the combining styles append their mark to the plain letter`() {
        val underline = FancyStyles.byId("underline")!!
        val styled = underline.lower.getValue('a')
        assertEquals(2, styled.length)
        assertEquals('a', styled[0])
        assertEquals('̲', styled[1])
    }

    @Test
    fun `non-letters pass through the transform untouched`() {
        val fraktur = FancyStyles.byId("fraktur")!!
        assertEquals("123 ,.!? ৳", FancyStyles.transform("123 ,.!? ৳", fraktur))
        assertEquals("", FancyStyles.transform("", fraktur))
    }

    @Test
    fun `styles with digits of their own style numbers too`() {
        assertEquals("𝐀𝟏𝟐", FancyStyles.transform("A12", FancyStyles.byId("bold")!!))
        assertEquals("𝟘𝟡", FancyStyles.transform("09", FancyStyles.byId("double_struck")!!))
        assertEquals("𝟶𝟿", FancyStyles.transform("09", FancyStyles.byId("monospace")!!))
        assertEquals("⓪①⑨", FancyStyles.transform("019", FancyStyles.byId("circled")!!))
        assertEquals("⓿❶❾", FancyStyles.transform("019", FancyStyles.byId("circled_filled")!!))
        assertEquals("⁰¹²³⁴⁹", FancyStyles.transform("012349", FancyStyles.byId("superscript")!!))
        // Parenthesized has no zero: it stays plain rather than vanishing.
        assertEquals("0⑴⑼", FancyStyles.transform("019", FancyStyles.byId("parenthesized")!!))
        // No digits of its own: borrows the bold set rather than going plain.
        assertEquals("𝟕", FancyStyles.transform("7", FancyStyles.byId("bold_italic")!!))
    }

    @Test
    fun `a digit's long press lists every styled set, astral ones whole`() {
        val forms = FancyStyles.digitForms('1')
        assertEquals(
            listOf("𝟏", "𝟙", "𝟣", "𝟭", "𝟷", "１", "①", "❶", "➀", "⑴", "⒈", "¹", "₁", "🯱"),
            forms,
        )
        // Zero: parenthesized has none, the rest keep it out of their run.
        val zeros = FancyStyles.digitForms('0')
        assertEquals(13, zeros.size)
        assertTrue("🄋" in zeros && "🄀" in zeros && "⓪" in zeros && "⓿" in zeros)
        assertTrue(FancyStyles.digitForms('a').isEmpty())
    }

    @Test
    fun `upside down text is laid out back to front`() {
        val upsideDown = FancyStyles.byId("upside_down")!!
        // Rotated glyphs alone would spell it backwards to anyone who turns
        // the phone round; reversing the order is what makes it read.
        assertEquals("uʍop", FancyStyles.transform("down", upsideDown))
        // Reversal is by mapped unit, not by char: B maps to an astral pair,
        // and reversing chars would split it into two orphaned surrogates.
        val b = upsideDown.upper.getValue('B')
        assertEquals(2, b.length)
        assertEquals(b, FancyStyles.transform("B", upsideDown))
        assertEquals(upsideDown.lower.getValue('a') + b, FancyStyles.transform("Ba", upsideDown))
    }

    @Test
    fun `bypass separates visible characters and leaves whitespace alone`() {
        val bypass = FancyStyles.byId("bypass")!!
        assertEquals("a\u200Bb\u200Bc", FancyStyles.transform("abc", bypass))
        // No separator either side of a space: the text already breaks there.
        assertEquals("a\u200Bb c\u200Bd", FancyStyles.transform("ab cd", bypass))
        // Digits and punctuation map to themselves and are still held apart —
        // a filter matching "a1!" must not see three adjacent characters.
        assertEquals("a\u200B1\u200B!", FancyStyles.transform("a1!", bypass))
        assertEquals("a", FancyStyles.transform("a", bypass))
        assertEquals("", FancyStyles.transform("", bypass))
    }

    /**
     * Fancy Text styles everything committed to the field (#950), emoji and
     * text that is styled already included, so neither may change on a
     * second pass.
     */
    @Test
    fun `bypass keeps an emoji whole and leaves styled text alone`() {
        val bypass = FancyStyles.byId("bypass")!!
        assertEquals("👍🏽", FancyStyles.transform("👍🏽", bypass))
        assertEquals("❤️", FancyStyles.transform("❤️", bypass))
        val once = FancyStyles.transform("ab cd", bypass)
        assertEquals(once, FancyStyles.transform(once, bypass))
    }

    @Test
    fun `unstyle reads every style but upside down back to plain`() {
        for (style in FancyStyles.all) {
            if (style.assembly == FancyAssembly.REVERSED) continue
            val plain = FancyStyles.unstyle(FancyStyles.transform("hello world 42", style))
            // Small caps and superscript have no capitals to read back.
            assertEquals(style.id, "hello world 42", plain.lowercase())
        }
        // Plain text, including letters other styles borrow, is left alone.
        assertEquals("unusual café", FancyStyles.unstyle("unusual café"))
    }

    @Test
    fun `samples are written in their own style`() {
        for (style in FancyStyles.all) {
            assertEquals(
                style.id,
                FancyStyles.transform(style.name, style),
                style.sample,
            )
        }
    }

    @Test
    fun `an alphabet splits into user-perceived characters, or into spaced items`() {
        // Astral pairs and a letter with a combining mark each count once.
        assertEquals(listOf("\uD835\uDCEA", "\uD835\uDCEB", "c\u0336"), FancyStyles.splitAlphabet("\uD835\uDCEA\uD835\uDCEBc\u0336"))
        assertEquals(listOf("(a)", "(b)", "(c)"), FancyStyles.splitAlphabet("  (a) (b)\n(c) "))
        assertEquals(emptyList<String>(), FancyStyles.splitAlphabet("   "))
    }

    @Test
    fun `a pasted alphabet maps in order, short alphabets leave the rest plain`() {
        val style = FancyStyles.fromAlphabets(
            id = FancyStyles.CUSTOM_PREFIX + "t",
            name = "ab",
            lower = "\u0250\u0071",
            digits = "\u24EA\u2460",
        )
        assertEquals("\u0250\u0071", style.sample)
        // No capitals given: capitals type the small forms.
        assertEquals("\u0250\u0250\u0071z \u24EA\u24602", FancyStyles.transform("aAbz 012", style))
    }

    @Test
    fun `custom styles follow the built-in ones, cannot shadow them, and unstyle`() {
        val before = FancyStyles.all.size
        try {
            val mine = FancyStyles.fromAlphabets(FancyStyles.CUSTOM_PREFIX + "x", "Mine", "(a) (b)")
            val shadow = FancyStyles.fromAlphabets("bold", "Fake", "x")
            FancyStyles.useCustom(listOf(mine, shadow))
            assertEquals(before + 1, FancyStyles.all.size)
            assertEquals(mine, FancyStyles.all.last())
            assertEquals(mine, FancyStyles.byId(mine.id))
            assertEquals("\uD835\uDC1B", FancyStyles.transform("b", FancyStyles.byId("bold")!!))
            assertEquals("ab", FancyStyles.unstyle("(a)(b)"))
        } finally {
            FancyStyles.useCustom(emptyList())
        }
        assertEquals(before, FancyStyles.all.size)
    }

    @Test
    fun `subscript styles the letters it has and every digit`() {
        val subscript = FancyStyles.byId("subscript")!!
        // No subscript b: it stays plain, and a capital types the small form.
        assertEquals("\u2090b\u2080\u2089", FancyStyles.transform("Ab09", subscript))
    }

    @Test
    fun `mirror is laid out back to front like upside down`() {
        val mirror = FancyStyles.byId("mirror")!!
        assertEquals(FancyAssembly.REVERSED, mirror.assembly)
        assertEquals("\u0252d", FancyStyles.transform("ba", mirror))
    }

    @Test
    fun `bracketed and boxed wrap letters and digits whole, and read back`() {
        assertEquals("\u3010h\u3011\u30101\u3011", FancyStyles.transform("h1", FancyStyles.byId("bracketed")!!))
        assertEquals("h\u20DE1\u20DE", FancyStyles.transform("h1", FancyStyles.byId("boxed")!!))
        assertEquals("h1", FancyStyles.unstyle("\u3010h\u3011\u30101\u3011"))
    }

    @Test
    fun `vaporwave spaces fullwidth letters apart and reads back without the gaps`() {
        val vaporwave = FancyStyles.byId("vaporwave")!!
        val styled = FancyStyles.transform("ab cd", vaporwave)
        assertEquals("\uFF41\u2005\uFF42 \uFF43\u2005\uFF44", styled)
        assertEquals(styled, FancyStyles.transform(styled, vaporwave))
        assertEquals("ab cd", FancyStyles.unstyle(styled))
    }

    @Test
    fun `zalgo marks every letter, the same way each time, and only once`() {
        for (id in listOf("zalgo", "zalgo_heavy")) {
            val zalgo = FancyStyles.byId(id)!!
            val styled = FancyStyles.transform("hey 7!", zalgo)
            assertTrue(id, styled.length > "hey 7!".length + 4)
            // The word being typed keeps its marks as it grows.
            assertEquals(id, styled, FancyStyles.transform("hey 7!", zalgo))
            assertTrue(id, FancyStyles.transform("heya", zalgo).startsWith(FancyStyles.transform("hey", zalgo)))
            // Styling styled text adds nothing, and reading it back drops every mark.
            assertEquals(id, styled, FancyStyles.transform(styled, zalgo))
            assertEquals(id, "hey 7!", FancyStyles.unstyle(styled))
            // Spaces and punctuation stay bare; the digit after the space is marked.
            assertEquals(id, '7', styled[styled.indexOf(' ') + 1])
            assertTrue(id, styled.endsWith("!"))
        }
    }

    @Test
    fun `the marked styles survive NFC, so a normalising app keeps them`() {
        val text = "The quick brown fox jumps over the lazy dog 0123456789"
        for (id in listOf("glitch", "zalgo", "zalgo_heavy", "tilde_strike", "cross_above", "seagull_below", "arrow_below", "boxed", "ringed")) {
            val styled = FancyStyles.transform(text + text.uppercase(), FancyStyles.byId(id)!!)
            assertEquals(id, styled, Normalizer.normalize(styled, Normalizer.Form.NFC))
        }
    }

    @Test
    fun `arranged follows the user's order, hides, and keeps the rest in table order`() {
        val ids = { styles: List<FancyStyle> -> styles.map { it.id } }
        val all = ids(FancyStyles.all)
        assertEquals(all, ids(FancyStyles.arranged(emptyList(), emptySet())))
        // Named first, unknown ids skipped, everything else after in table order.
        val arranged = ids(FancyStyles.arranged(listOf("zalgo", "gone", "bold"), setOf("italic", "script")))
        assertEquals(listOf("zalgo", "bold", FancyStyles.NORMAL_ID), arranged.take(3))
        assertFalse("italic" in arranged)
        assertEquals(all.size - 2, arranged.size)
        // The style in use stays, hidden or not.
        assertTrue("italic" in ids(FancyStyles.arranged(emptyList(), setOf("italic"), keep = "italic")))
        // Hiding everything shows everything rather than nothing.
        assertEquals(all, ids(FancyStyles.arranged(emptyList(), all.toSet())))
    }

    @Test
    fun `a higher zalgo level stacks taller, and the levels are the user's to set`() {
        val text = "the quick brown fox jumps over the lazy dog"
        val low = FancyStyles.transform(text, FancyStyles.zalgo("zalgo_t1", 1))
        val high = FancyStyles.transform(text, FancyStyles.zalgo("zalgo_t2", 10))
        assertTrue(high.length > low.length * 2)
        // Out-of-range levels are pulled into range, and an unnamed level names itself.
        assertEquals(10, FancyStyles.zalgo("zalgo_t3", 99).zalgoLevel)
        assertEquals("Zalgo 1", FancyStyles.zalgo("zalgo_t4", 0).name)
        assertEquals("Mine", FancyStyles.zalgo("zalgo_t5", 4, " Mine ").name)
        val defaults = FancyStyles.zalgoStyles
        try {
            FancyStyles.useZalgo(listOf(FancyStyles.zalgo("zalgo_t6", 5)))
            assertEquals(listOf("zalgo_t6"), FancyStyles.zalgoStyles.map { it.id })
            assertNotNull(FancyStyles.byId("zalgo_t6"))
            assertEquals(null, FancyStyles.byId("zalgo_heavy"))
            // Zalgo sits between the built-in styles and the user's fonts.
            assertEquals("zalgo_t6", FancyStyles.all[FancyStyles.builtIn.size].id)
        } finally {
            FancyStyles.useZalgo(defaults)
        }
        assertEquals(listOf("zalgo", "zalgo_heavy"), FancyStyles.zalgoStyles.map { it.id })
    }
}
